import 'package:board_web/api/api_base_url.dart';
import 'package:board_web/widgets/config_error_app.dart';
import 'package:flutter_test/flutter_test.dart';

// 앱(비웹)에서 서버 주소 설정이 잘못됐을 때 안내를 내는 검사 함수와 안내 화면을 시험한다.
void main() {
  // 짧게 부르려는 도우미: 앱(비웹)·디버그가 기본
  String? problem(String url, {bool isWeb = false, bool isRelease = false}) =>
      apiBaseUrlProblem(url, isWeb: isWeb, isRelease: isRelease);

  test('웹이면_상대_주소여도_문제없다', () {
    expect(problem('/api', isWeb: true), isNull);
  });

  test('앱에서_dart-define을_빠뜨려_상대_주소면_안내_문장이_나온다', () {
    expect(problem('/api'), contains('API_BASE_URL'));
  });

  test('앱에서_끝에_슬래시가_있으면_안내_문장이_나온다', () {
    expect(problem('http://10.0.2.2:8080/'), contains('"/"'));
  });

  test('디버그_앱에서_http_절대_주소면_문제없다', () {
    expect(problem('http://10.0.2.2:8080'), isNull);
    expect(problem('http://localhost:8080'), isNull);
    expect(problem('https://example.com/api'), isNull);
  });

  test('릴리스_앱에서_https_주소면_문제없다', () {
    expect(problem('https://example.com/api', isRelease: true), isNull);
  });

  test('릴리스_앱에서_http_주소면_안내_문장이_나온다', () {
    expect(problem('http://10.0.2.2:8080', isRelease: true), contains('https'));
  });

  test('오타_주소_httpx는_안내_문장이_나온다', () {
    expect(problem('httpx://a'), contains('API_BASE_URL'));
  });

  test('웹이면_릴리스_여부와_어떤_값이든_null이다', () {
    expect(problem('', isWeb: true), isNull);
    expect(problem('https://x.test/api/', isWeb: true), isNull);
    expect(problem('http://x.test', isWeb: true, isRelease: true), isNull);
  });

  test('앱에서_https_주소_끝에_슬래시가_있으면_안내_문장이_나온다', () {
    expect(problem('https://x.test/api/'), isNotNull);
  });

  test('앱에서_빈_문자열이면_안내_문장이_나온다', () {
    expect(problem(''), contains('API_BASE_URL'));
  });

  testWidgets('안내_화면은_문장을_보여_준다', (tester) async {
    await tester.pumpWidget(const ConfigErrorApp(message: '서버 주소가 설정되지 않았습니다.'));
    expect(find.text('서버 주소가 설정되지 않았습니다.'), findsOneWidget);
  });
}
