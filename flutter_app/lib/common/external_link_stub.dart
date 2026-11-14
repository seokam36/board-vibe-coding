// 웹이 아닌 곳(VM 테스트 등)용: 열 브라우저가 없으니 아무것도 하지 않는다
import 'external_link.dart';

class _NoopOpener implements ExternalLinkOpener {
  @override
  void open(String url) {}
}

ExternalLinkOpener createPlatformExternalLinkOpener() => _NoopOpener();
