// 웹·기본용: 웹은 main.dart에서 이 도구를 쓰지 않고 기존 window.open 방식을 쓴다.
// 혹시 불려도 해롭지 않게 아무것도 하지 않는다.
import 'external_link.dart';

class _NoopAppOpener implements ExternalLinkOpener {
  @override
  void open(String url) {}
}

ExternalLinkOpener createPlatformExternalLinkOpener() => _NoopAppOpener();
