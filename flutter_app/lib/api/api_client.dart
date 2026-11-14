import 'dart:async';

import 'package:dio/dio.dart';

import '../auth/auth_user.dart';
import 'cookie_reader.dart';

// 서버(백엔드) 호출 담당 (React의 api.ts). 주소는 여기서만 정한다.
// 기본값 '/api': 같은 출처(화면과 같은 주소)로 보내서, 로컬은 개발 서버 프록시가, 배포는 nginx가 백엔드로 전달한다.
// 같은 출처라서 withCredentials 없이도 쿠키가 자동으로 오간다.
// 다만 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 옮기는 건 dio가 안 해 주므로 아래 인터셉터가 직접 한다.
class ApiClient {
  ApiClient({
    HttpClientAdapter? adapter, // 테스트에서 가짜 서버를 끼우려고 받는다. 없으면 웹 기본 어댑터
    CookieReader? cookieReader, // 테스트에서 가짜 쿠키를 주려고 받는다
    String baseUrl = '/api',
    Duration refreshTimeout = const Duration(seconds: 10),
  }) : _cookies = cookieReader ?? createCookieReader() {
    dio = Dio(BaseOptions(baseUrl: baseUrl));
    // 재발급 전용 Dio: 인터셉터(401 처리)가 없다. 재발급 요청이 또 401을 받아 재발급을 부르는 순환·교착을 막으려고.
    // timeout: 서버가 대답을 안 하면 _refreshing이 영원히 안 비워져 기다리는 요청이 전부 멈추기 때문.
    _refreshDio = Dio(
      BaseOptions(
        baseUrl: baseUrl,
        connectTimeout: refreshTimeout,
        receiveTimeout: refreshTimeout,
      ),
    );
    if (adapter != null) {
      dio.httpClientAdapter = adapter;
      _refreshDio.httpClientAdapter = adapter;
    }
    _refreshDio.interceptors.add(
      InterceptorsWrapper(onRequest: (options, handler) {
        _attachXsrf(options);
        handler.next(options);
      }),
    );
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          _attachXsrf(options);
          handler.next(options);
        },
        onError: _onError,
      ),
    );
  }

  // 앱 전체가 쓰는 Dio (화면 코드는 이걸로 요청한다)
  late final Dio dio;
  late final Dio _refreshDio;
  final CookieReader _cookies;

  // 재발급 결과를 로그인 상태(AuthState)에 알리는 콜백. AuthState가 만들어질 때 연결한다.
  void Function(AuthUser user)? onRefreshed; // 재발급 성공: 최신 사용자 정보
  void Function()? onExpired; // 재발급이 401: 로그인이 진짜 풀림

  static const _xsrfCookie = 'XSRF-TOKEN';
  static const _xsrfHeader = 'X-XSRF-TOKEN';
  static const _retryKey = 'retried'; // 재시도 표시: 이미 한 번 다시 보낸 요청이라는 뜻
  // 요청 extra에 FormData 생성 함수(FormData Function())를 넣을 때 쓰는 이름. 401 재시도 때 본문을 새로 만든다
  static const formDataFactoryKey = 'formDataFactory';

  // 재발급 대상에서 빼는 주소들. 이 주소의 401은 "재발급으로 해결될 문제"가 아니기 때문이다.
  // (refresh 자체가 401이면 무한 반복, login/signup 401은 아이디·비밀번호 오류, logout은 이미 로그아웃 상태)
  // /auth/me 는 일부러 뺐다: access가 만료된 채 새로고침해도 로그인이 유지되게 하려고.
  static const _noRefreshPaths = [
    '/auth/refresh',
    '/auth/login',
    '/auth/signup',
    '/auth/logout',
  ];

  // single-flight: 진행 중인 재발급은 항상 하나만. 그 사이 401을 받은 요청들은 이 Future를 같이 기다린다.
  // refresh 토큰은 한 번 쓰면 폐기되고, 두 번째 사용은 401과 함께 쿠키를 지워 버리기 때문에 꼭 필요하다.
  Future<void>? _refreshing;

  // 요청을 보내는 시점의 XSRF 쿠키 값을 헤더로 붙인다 (쿠키가 없으면 헤더도 없음)
  void _attachXsrf(RequestOptions options) {
    options.headers.remove(_xsrfHeader);
    final token = _cookies.read(_xsrfCookie);
    if (token != null && token.isNotEmpty) {
      options.headers[_xsrfHeader] = token;
    }
  }

  bool _isNoRefreshPath(String path) {
    // baseUrl이 붙어 있든 아니든, 쿼리를 뗀 뒤 끝부분이 같은지로 비교한다
    final clean = path.split('?').first;
    return _noRefreshPaths.any(clean.endsWith);
  }

  // 401이면 access 토큰을 다시 받고(재발급) 원래 요청을 한 번만 다시 보낸다.
  // 콜백을 async로 만들지 않고 안에서 Future를 이어 붙인다 (handler는 정확히 한 번만 부르면 된다).
  void _onError(DioException err, ErrorInterceptorHandler handler) {
    final original = err.requestOptions;
    if (err.response?.statusCode != 401 ||
        original.extra[_retryKey] == true || // 이미 한 번 재시도한 요청의 401은 그대로 실패 (무한 반복 방지)
        _isNoRefreshPath(original.path)) {
      handler.next(err);
      return;
    }
    original.extra[_retryKey] = true;
    _refreshOnce().then(
      (_) {
        // 파일 올리기(FormData)는 한 번 보내면 내용이 소진돼 다시 못 쓴다.
        // 그래서 호출한 쪽이 extra에 "FormData를 새로 만드는 함수"를 넣어 두면 다시 보내기 직전에 새로 만들어 바꿔 끼운다
        final factory = original.extra[formDataFactoryKey];
        if (factory is FormData Function()) original.data = factory();
        // 새 쿠키가 생겼으니 원래 요청을 다시 보낸다 (성공하면 그 응답이 호출한 쪽에 간다)
        dio.fetch<dynamic>(original).then(
          handler.resolve,
          onError: (Object e) => handler.next(e is DioException ? e : err),
        );
      },
      onError: (Object refreshErr) {
        if (refreshErr is DioException &&
            refreshErr.response?.statusCode == 401) {
          // 재발급이 401로 거절됨(로그인이 진짜 풀림): 호출한 쪽에는 원래 401을 넘긴다
          handler.next(err);
        } else {
          // 네트워크 끊김·timeout·5xx·403 등: 로그인이 풀린 게 아니므로 그 에러를 그대로 넘긴다
          handler.next(refreshErr is DioException ? refreshErr : err);
        }
      },
    );
  }

  Future<void> _refreshOnce() {
    // 이미 진행 중이면 그 Future를 같이 쓰고, 끝나면(성공이든 실패든) 비워서 다음 만료 때 다시 재발급할 수 있게 한다
    return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<void> _doRefresh() async {
    try {
      final res = await _refreshDio.post<Map<String, dynamic>>('/auth/refresh');
      onRefreshed?.call(AuthUser.fromJson(res.data!));
    } on DioException catch (e) {
      // 진짜 401(refresh도 만료·폐기)일 때만 로그아웃 처리. 서버 꺼짐 같은 일시 오류는 로그인 상태를 건드리지 않는다
      if (e.response?.statusCode == 401) onExpired?.call();
      rethrow; // 어떤 에러였는지는 _onError가 보고 판단한다
    }
  }
}
