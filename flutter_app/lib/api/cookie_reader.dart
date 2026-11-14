// 쿠키 읽기 도우미.
// 브라우저에서만 쿠키(document.cookie)를 읽을 수 있고, 테스트(VM)에는 브라우저가 없다.
// 그래서 "쿠키 읽는 방법"을 인터페이스로 두고, 웹에서는 진짜 구현을, 그 밖에서는 항상 null을 주는 구현을 쓴다.
// (조건부 import: dart.library.js_interop 이 있는 곳 = 웹일 때만 cookie_reader_web.dart 를 가져온다.
//  덕분에 VM 테스트에서는 package:web 이 아예 import되지 않는다.)
import 'cookie_reader_stub.dart'
    if (dart.library.js_interop) 'cookie_reader_web.dart'
    as platform;

abstract interface class CookieReader {
  // 이름이 정확히 같은 쿠키의 값. 없으면 null
  String? read(String name);
}

// 지금 실행 중인 곳(웹/그 외)에 맞는 구현을 만들어 준다
CookieReader createCookieReader() => platform.createPlatformCookieReader();

// "a=1; b=2" 같은 쿠키 문자열(document.cookie)에서 이름이 정확히 같은 쿠키의 값을 꺼낸다.
// 이름 비교는 통째로 한다: 'XSRF-TOKEN'을 찾는데 'X-XSRF-TOKEN=...'이 있어도 헷갈리지 않는다.
// 값은 퍼센트 인코딩을 풀어서 돌려주고, 풀 수 없는 글자면 원문 그대로 돌려준다.
String? parseCookie(String cookieHeader, String name) {
  for (final part in cookieHeader.split(';')) {
    final pair = part.trim();
    final eq = pair.indexOf('=');
    if (eq < 0) continue;
    if (pair.substring(0, eq) != name) continue;
    final raw = pair.substring(eq + 1);
    try {
      return Uri.decodeComponent(raw);
    } catch (_) {
      return raw;
    }
  }
  return null;
}
