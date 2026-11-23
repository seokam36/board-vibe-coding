// 앱(안드로이드 등)에서 바깥 링크를 여는 도우미. 앱에는 브라우저 window가 없어서 url_launcher로 연다.
// 이 파일은 앱 전용 열기 도구다. 웹의 바깥 링크(하이코리아 등)는 AppLink.external의 Link(새 탭)가 열고,
// 웹 구현 external_link_web.dart는 지금 안 쓰이지만 남겨 둔다.
// (조건부 import: dart.library.io 가 있는 곳 = 앱·VM일 때만 app_external_link_io.dart 를 가져온다.)
import 'external_link.dart';
import 'app_external_link_stub.dart'
    if (dart.library.io) 'app_external_link_io.dart'
    as platform;

// 앱이면 url_launcher로 여는 도구를, 웹이면 아무것도 안 하는 도구를 돌려준다.
// main.dart의 앱(비웹) 갈래에서 externalLinkOpener에 끼워 넣는다.
ExternalLinkOpener createAppExternalLinkOpener() => platform.createPlatformExternalLinkOpener();
