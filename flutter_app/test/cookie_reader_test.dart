// S16-d1 쿠키 읽기 테스트: 브라우저 없이(VM) 쿠키 문자열 해석과 "웹이 아니면 null"을 본다.
import 'package:board_web/api/cookie_reader.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('이름이 정확히 같은 쿠키 값을 꺼낸다', () {
    expect(parseCookie('a=1; XSRF-TOKEN=tok; b=2', 'XSRF-TOKEN'), 'tok');
  });

  test('이름이 비슷한 쿠키와 헷갈리지 않는다', () {
    expect(parseCookie('X-XSRF-TOKEN=wrong; XSRF-TOKEN-OLD=old', 'XSRF-TOKEN'), isNull);
  });

  test('없으면 null, 빈 문자열이면 null', () {
    expect(parseCookie('a=1', 'XSRF-TOKEN'), isNull);
    expect(parseCookie('', 'XSRF-TOKEN'), isNull);
  });

  test('퍼센트 인코딩은 풀고, 못 풀면 원문 그대로', () {
    expect(parseCookie('XSRF-TOKEN=a%20b', 'XSRF-TOKEN'), 'a b');
    expect(parseCookie('XSRF-TOKEN=100%', 'XSRF-TOKEN'), '100%');
  });

  test('값에 = 가 들어 있어도 첫 = 까지만 이름으로 본다', () {
    expect(parseCookie('XSRF-TOKEN=ab==', 'XSRF-TOKEN'), 'ab==');
  });

  test('웹이 아닌 곳(VM)의 기본 구현은 항상 null', () {
    expect(createCookieReader().read('XSRF-TOKEN'), isNull);
  });
}
