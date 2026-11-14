// 웹용: window.open으로 새 창을 연다 (package:web)
import 'package:web/web.dart' as web;

import 'external_link.dart';

class _WebOpener implements ExternalLinkOpener {
  @override
  void open(String url) {
    // 세 번째 인자의 noopener,noreferrer: 열린 창이 window.opener로 이 사이트를 조작하지 못하게 한다
    web.window.open(url, '_blank', 'noopener,noreferrer');
  }
}

ExternalLinkOpener createPlatformExternalLinkOpener() => _WebOpener();
