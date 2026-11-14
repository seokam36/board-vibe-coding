// 바깥 사이트를 새 창으로 여는 도우미 (url_launcher 대신 직접 만든 아주 작은 버전).
// 브라우저에서만 window.open을 쓸 수 있고 테스트(VM)에는 브라우저가 없다.
// 그래서 cookie_reader처럼 조건부 import: 웹이면 진짜 구현, 아니면 아무것도 안 하는 구현을 쓴다.
import 'external_link_stub.dart'
    if (dart.library.js_interop) 'external_link_web.dart'
    as platform;

abstract interface class ExternalLinkOpener {
  // url을 새 창(탭)으로 연다. 열린 창이 이 사이트를 건드리지 못하게 noopener·noreferrer로 연다
  void open(String url);
}

// 화면이 쓰는 열기 도구. 테스트는 이 값을 가짜로 바꿔 "어떤 주소를 열려 했는지"만 확인한다
ExternalLinkOpener externalLinkOpener = platform.createPlatformExternalLinkOpener();
