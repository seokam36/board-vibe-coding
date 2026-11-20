// 앱(안드로이드 등)용 쿠키 보관 도우미.
// 웹은 브라우저가 쿠키를 알아서 보관하고 보내 주지만, 앱의 dio에는 그런 저장소가 없어서 로그인이 안 된다.
// 그래서 앱에서는 "쿠키 보관함(cookie_jar)"을 dio 인터셉터로 끼워 브라우저 대신 같은 일을 하게 한다.
// (조건부 import: dart.library.io 가 있는 곳 = 앱·VM일 때만 app_cookies_io.dart 를 가져온다.
//  덕분에 웹 번들에는 cookie_jar·path_provider 코드가 들어가지 않는다.)
import 'package:dio/dio.dart';

import 'app_cookies_stub.dart'
    if (dart.library.io) 'app_cookies_io.dart'
    as platform;

// 앱이면 쿠키 보관 인터셉터 목록을, 웹이면 빈 목록을 돌려준다. ApiClient의 extraInterceptors에 넣는다.
Future<List<Interceptor>> createAppCookieInterceptors() => platform.createPlatformCookieInterceptors();

// 파일 보관함이 실패했을 때의 대체(메모리 보관함). 로그인 유지만 포기하고 앱은 뜬다.
Future<List<Interceptor>> createMemoryAppCookieInterceptors() => platform.createMemoryCookieInterceptors();
