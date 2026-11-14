import 'package:flutter/foundation.dart';

import '../api/api_client.dart';
import 'auth_user.dart';

// 로그인 정보(user)와 login/logout 함수를 들고 있는 공용 보관함 (React의 AuthContext)
// 값이 바뀌면 notifyListeners()로 화면에 알린다.
class AuthState extends ChangeNotifier {
  AuthState({required ApiClient api, required this._currentPath}) : _api = api {
    // 인터셉터(ApiClient)의 재발급 결과를 받아 로그인 상태에 반영한다
    api.onRefreshed = updateUser;
    api.onExpired = _handleExpired;
  }

  final ApiClient _api;
  // 지금 어느 화면(경로)인지 알려 주는 함수. 로그인이 풀린 화면을 기억하려고 쓴다(라우터에서 넘겨받음)
  final String Function() _currentPath;

  // 이 로그인 상태가 쓰는 서버 통로. 앱이 화면들에 같은 통로를 나눠 줄 때 쓴다
  ApiClient get api => _api;

  AuthUser? _user;
  bool _loading = true;
  String? _expiredPath;
  bool _disposed = false;

  // null이면 비로그인
  AuthUser? get user => _user;
  // 시작할 때 /csrf 와 /auth/me 확인이 끝났는지 (끝나기 전엔 true)
  bool get loading => _loading;
  // 로그인이 풀린 그 순간 있던 화면의 경로. null이면 "풀린 적 없음"
  String? get expiredPath => _expiredPath;

  // 앱 시작 시 한 번: 먼저 /csrf 로 CSRF 쿠키를 받고, 그다음 /auth/me 로 로그인 여부를 확인한다.
  // (쓰기 요청 전에 쿠키가 있어야 하므로 loading이 끝나기 전에는 화면을 그리지 않는다)
  Future<void> init() async {
    try {
      await _api.dio.get<dynamic>('/csrf');
      final res = await _api.dio.get<Map<String, dynamic>>('/auth/me');
      _user = AuthUser.fromJson(res.data!);
    } catch (_) {
      // /auth/me 가 401이면 그냥 비로그인. /csrf 실패면 /auth/me 는 부르지 않고 비로그인.
      // 네트워크 오류도 일단 비로그인으로 둔다
      _user = null;
    } finally {
      _loading = false;
      _notify();
    }
  }

  // 로그인: 실패하면 에러가 그대로 던져져서 로그인 화면이 상태코드별 문구를 보여준다
  Future<void> login(String username, String password) async {
    final res = await _api.dio.post<Map<String, dynamic>>(
      '/auth/login',
      data: {'username': username, 'password': password},
    );
    _expiredPath = null; // 다시 로그인했으니 만료 표시 초기화
    _user = AuthUser.fromJson(res.data!);
    _notify();
  }

  // 로그아웃: 서버에서 쿠키를 지운 "뒤에만" 화면의 로그인 상태를 비운다 (실패하면 상태 유지, 에러는 던짐)
  Future<void> logout() async {
    await _api.dio.post<dynamic>('/auth/logout');
    _user = null;
    _notify();
  }

  // 닉네임 변경 성공 때: 서버 응답 그대로 로그인 정보를 바꿔 상단바 등에 바로 반영한다
  void updateUser(AuthUser user) {
    _user = user;
    _notify();
  }

  // 비밀번호 변경·탈퇴 성공 때: 서버가 이미 쿠키를 지웠으므로 /auth/logout 을 또 부르지 않고 화면 상태만 비운다
  void clearUser() {
    _expiredPath = null;
    _user = null;
    _notify();
  }

  // 만료된 화면을 떠나면 표시를 지운다. 안 지우면 나중에 같은 주소로 돌아왔을 때 비로그인인데도 문지기가 통과시킨다.
  // 호출은 라우터의 redirect(문지기)에서 한다. redirect는 화면을 그리는 도중에 불리므로
  // 여기서 notifyListeners()를 하면 "그리는 중에 다시 그리라"는 에러가 난다 → 일부러 알림 없이 값만 비운다.
  // (이 값을 보고 그리는 화면은 없고, 문지기가 다음 판단에서 읽기만 한다)
  void clearExpiredIfPathChanged(String path) {
    if (_expiredPath != null && path != _expiredPath) {
      _expiredPath = null;
    }
  }

  // 재발급 실패(401): 로그인 상태를 비운다. 로그인해 있던 사람이면 "풀린 그 화면의 경로"를 남겨
  // 그 화면만 /login 으로 튕기지 않고 안내 문구를 보여 주게 한다.
  // 시작 때 /auth/me 가 401인 손님은 원래 비로그인이므로 표시하지 않는다.
  void _handleExpired() {
    if (_user != null) _expiredPath = _currentPath();
    _user = null;
    _notify();
  }

  // dispose된 뒤에 늦게 끝난 요청이 알림을 보내면 에러가 나서, 막아 둔다
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _api.onRefreshed = null;
    _api.onExpired = null;
    super.dispose();
  }
}
