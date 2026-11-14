// 웹용: 브라우저의 document.cookie 를 읽는다 (package:web)
import 'package:web/web.dart' as web;

import 'cookie_reader.dart';

class _WebCookieReader implements CookieReader {
  @override
  String? read(String name) => parseCookie(web.document.cookie, name);
}

CookieReader createPlatformCookieReader() => _WebCookieReader();
