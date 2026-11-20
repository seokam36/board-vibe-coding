// 앱(안드로이드 등)에서 바깥 링크를 여는 도우미.
// 웹은 window.open(external_link_web.dart)으로 열지만, 앱에는 window가 없어서 url_launcher로 연다.
// (조건부 import: dart.library.io 가 있는 곳 = 앱·VM일 때만 app_external_link_io.dart 를 가져온다.
//  덕분에 웹 번들에는 우리 쪽 url_launcher 호출 코드가 들어가지 않는다.
//  url_launcher_web 플러그인 등록 코드는 Flutter가 자동으로 조금 넣을 수 있지만 동작은 그대로다.)
import 'external_link.dart';
import 'app_external_link_stub.dart'
    if (dart.library.io) 'app_external_link_io.dart'
    as platform;

// 앱이면 url_launcher로 여는 도구를, 웹이면 아무것도 안 하는 도구를 돌려준다.
// main.dart의 앱(비웹) 갈래에서 externalLinkOpener에 끼워 넣는다.
ExternalLinkOpener createAppExternalLinkOpener() => platform.createPlatformExternalLinkOpener();
