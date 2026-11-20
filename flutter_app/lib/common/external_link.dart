// 바깥 사이트를 여는 도우미. 웹용은 window.open으로 직접 만든 아주 작은 버전이다.
// 브라우저에서만 window.open을 쓸 수 있고 테스트(VM)에는 브라우저가 없다.
// 그래서 cookie_reader처럼 조건부 import: 웹이면 진짜 구현, 아니면 아무것도 안 하는 구현을 쓴다.
// 앱(안드로이드)은 main.dart가 app_external_link.dart(url_launcher)의 도구로 바꿔 끼운다 (S23-d1).
import 'external_link_stub.dart'
    if (dart.library.js_interop) 'external_link_web.dart'
    as platform;

// window.open의 세 번째 인자. 열린 창이 window.opener로 이 사이트를 조작하지 못하게(noopener),
// 이 사이트 주소를 Referer로 넘기지 않게(noreferrer) 한다. 웹 구현이 쓰고, 테스트가 값을 고정해 둔다
const externalLinkWindowFeatures = 'noopener,noreferrer';

abstract interface class ExternalLinkOpener {
  // url을 바깥에서 연다. 웹은 새 탭(noopener·noreferrer로 이 사이트를 못 건드리게), 앱은 기기 브라우저
  void open(String url);
}

// 화면이 쓰는 열기 도구. 테스트는 이 값을 가짜로 바꿔 "어떤 주소를 열려 했는지"만 확인한다
ExternalLinkOpener externalLinkOpener = platform.createPlatformExternalLinkOpener();
