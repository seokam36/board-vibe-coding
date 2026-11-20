// 앱용: 쿠키를 파일에 저장해 두는 보관함을 만들어 dio에 끼운다.
import 'dart:io';

import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio/dio.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';
import 'package:path_provider/path_provider.dart';

import 'api_client.dart' show ApiClient;
import 'cookie_reader.dart' show parseCookie;

// 앱 전용 폴더에 쿠키 파일을 두는 보관함(PersistCookieJar)을 만든다.
// 앱을 껐다 켜도 로그인이 유지되게 하려고 파일에 저장한다. (다른 앱은 이 폴더를 못 읽는다)
// 하위 폴더 이름 cookies 는 android/app/src/main/res/xml/data_extraction_rules.xml 의 백업 제외 경로와 같아야 한다.
Future<List<Interceptor>> createPlatformCookieInterceptors() async {
  final dir = await getApplicationSupportDirectory();
  final jar = PersistCookieJar(storage: FileStorage('${dir.path}${Platform.pathSeparator}cookies'));
  return buildCookieInterceptors(jar);
}

// 파일 보관함을 못 만들 때(저장 공간 오류 등) 쓰는 대체: 메모리 보관함. 앱은 뜨지만 앱을 끄면 로그인이 풀린다.
Future<List<Interceptor>> createMemoryCookieInterceptors() async => buildCookieInterceptors(CookieJar());

// 보관함을 받아 인터셉터 목록을 만든다 (테스트에서는 메모리 CookieJar()를 넣는다).
// 순서가 중요하다: CookieManager가 먼저 Cookie 헤더를 붙이고, 그다음 XSRF 옮기기가 그 헤더에서 값을 읽는다.
List<Interceptor> buildCookieInterceptors(CookieJar jar) => [CookieManager(jar), XsrfFromCookieInterceptor()];

// 서버(Spring)는 XSRF-TOKEN 쿠키 값과 X-XSRF-TOKEN 헤더 값이 같아야 쓰기 요청을 받아 준다.
// 브라우저에선 코드가 document.cookie에서 읽지만, 앱에서는 보관함이 붙인 Cookie 헤더에서 읽어 옮긴다.
class XsrfFromCookieInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final cookieHeader = options.headers[HttpHeaders.cookieHeader];
    if (cookieHeader is String) {
      final token = parseCookie(cookieHeader, ApiClient.xsrfCookieName);
      // 쿠키에 토큰이 없으면 헤더를 넣지 않는다
      if (token != null && token.isNotEmpty) {
        options.headers[ApiClient.xsrfHeaderName] = token;
      }
    }
    handler.next(options);
  }
}
