import 'package:dio/dio.dart' show Interceptor;
import 'package:flutter/foundation.dart' show kIsWeb, kReleaseMode;
import 'package:flutter/semantics.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'package:material_ui/material_ui.dart';

import 'api/api_base_url.dart';
import 'api/api_client.dart';
import 'api/app_cookies.dart';
import 'app.dart';
import 'common/app_external_link.dart';
import 'common/app_photo_picker.dart';
import 'common/file_picker.dart' show photoPicker;
import 'common/external_link.dart';
import 'push/app_push.dart';
import 'widgets/config_error_app.dart';

Future<void> main() async {
  // 엔진이 준비된 뒤에야 SemanticsBinding을 쓸 수 있어서 먼저 초기화한다.
  WidgetsFlutterBinding.ensureInitialized();
  // 앱(비웹)에서 서버 주소 설정이 잘못됐으면 알아보기 힘든 오류로 죽지 않게 안내 화면만 띄우고 멈춘다.
  final problem = apiBaseUrlProblem(apiBaseUrl, isWeb: kIsWeb, isRelease: kReleaseMode);
  if (problem != null) {
    runApp(ConfigErrorApp(message: problem));
    return;
  }
  if (kIsWeb) {
    // 주소를 /#/posts/1 이 아니라 /posts/1 처럼 깔끔하게 쓴다. (웹 전용이라 앱에서는 부르지 않는다)
    usePathUrlStrategy();
    // Flutter 웹은 화면을 통째로 그림(canvas)으로 그려서, 평소엔 버튼·입력 칸이
    // 브라우저(스크린리더, Playwright)에게 보이지 않는다. 접근성 트리를 미리 켜 둔다.
    SemanticsBinding.instance.ensureSemantics();
    runApp(const BoardApp()); // 웹은 브라우저가 쿠키를 다루니까 지금까지와 똑같이
    return;
  }
  // 앱: 브라우저가 없으니 쿠키 보관함(파일 저장)을 dio에 끼운 ApiClient를 만들어 쓴다
  List<Interceptor> cookieInterceptors;
  try {
    cookieInterceptors = await createAppCookieInterceptors();
  } catch (_) {
    // 파일 보관함을 못 만들어도 앱은 떠야 한다: 메모리 보관함으로 대신한다 (앱을 끄면 로그인이 풀릴 뿐)
    cookieInterceptors = await createMemoryAppCookieInterceptors();
  }
  // 앱에는 window.open이 없으니 바깥 링크(하이코리아 등)는 기기 브라우저로 여는 도구로 바꾼다
  externalLinkOpener = createAppExternalLinkOpener();
  // 앱에는 <input type=file>이 없으니 사진 고르기는 갤러리(image_picker)로 바꾼다
  photoPicker = createAppPhotoPicker();
  // 앱에는 브라우저의 뒤로 가기가 없으니 방문 기록으로 기기의 뒤로 가기를 받는다 (앱 모드 스위치 켬)
  runApp(
    BoardApp(
      apiClient: ApiClient(extraInterceptors: cookieInterceptors),
      enableAppBack: true,
      // 푸시 알림(FCM): 앱에서만 끼운다. 설정이 없으면 init이 실패해도 앱은 그대로 뜬다
      push: createAppPush(),
    ),
  );
}
