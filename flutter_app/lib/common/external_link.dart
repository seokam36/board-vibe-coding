// 바깥 사이트를 여는 도우미의 자리. S29-b부터 웹의 바깥 링크는 AppLink.external의 Link(진짜 <a>)가 열어서,
// 웹 구현(external_link_web.dart, window.open)은 화면에서 안 쓰인다. 비상용으로 남겨 둔다.
// cookie_reader처럼 조건부 import: 웹이면 웹 구현, 아니면 아무것도 안 하는 구현이다(테스트 VM에는 브라우저가 없다).
// 앱(안드로이드)은 main.dart가 app_external_link.dart(url_launcher)의 도구로 바꿔 끼운다 (S23-d1).
import 'external_link_stub.dart'
    if (dart.library.js_interop) 'external_link_web.dart'
    as platform;

// window.open의 세 번째 인자. 열린 창이 window.opener로 이 사이트를 조작하지 못하게(noopener),
// 이 사이트 주소를 Referer로 넘기지 않게(noreferrer) 한다. 웹 구현이 쓰고, 테스트가 값을 고정해 둔다
const externalLinkWindowFeatures = 'noopener,noreferrer';

abstract interface class ExternalLinkOpener {
  // url을 바깥에서 연다. 앱은 기기 브라우저. 웹 구현은 새 탭(noopener·noreferrer)이지만 지금 화면에서는 안 쓴다
  void open(String url);
}

// 화면이 쓰는 열기 도구. 테스트는 이 값을 가짜로 바꿔 "어떤 주소를 열려 했는지"만 확인한다
ExternalLinkOpener externalLinkOpener = platform.createPlatformExternalLinkOpener();
