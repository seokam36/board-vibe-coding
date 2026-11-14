// 웹이 아닌 곳(VM 테스트 등)용: 브라우저 쿠키가 없으니 항상 "없음"
import 'cookie_reader.dart';

class _NoCookieReader implements CookieReader {
  @override
  String? read(String name) => null;
}

CookieReader createPlatformCookieReader() => _NoCookieReader();
