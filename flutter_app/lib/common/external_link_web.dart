// 웹용: window.open으로 새 창을 연다 (package:web)
import 'package:web/web.dart' as web;

import 'external_link.dart';

class _WebOpener implements ExternalLinkOpener {
  @override
  void open(String url) {
    // 세 번째 인자: 열린 창이 이 사이트를 조작하지 못하게 하는 공용 상수 (external_link.dart)
    web.window.open(url, '_blank', externalLinkWindowFeatures);
  }
}

ExternalLinkOpener createPlatformExternalLinkOpener() => _WebOpener();
