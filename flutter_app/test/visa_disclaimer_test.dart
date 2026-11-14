// S16-h1 면책 문구 테스트: 문구 글자, 하이코리아 링크가 새 창 열기 도구로 올바른 주소를 넘기는지 본다.
import 'package:board_web/common/external_link.dart';
import 'package:board_web/visa/visa_disclaimer.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

// 열려는 주소만 기록하는 가짜 열기 도구
class _FakeOpener implements ExternalLinkOpener {
  final List<String> opened = [];

  @override
  void open(String url) => opened.add(url);
}

void main() {
  late _FakeOpener opener;
  late ExternalLinkOpener original;

  setUp(() {
    original = externalLinkOpener;
    opener = _FakeOpener();
    externalLinkOpener = opener;
  });
  tearDown(() => externalLinkOpener = original);

  Future<void> pumpDisclaimer(WidgetTester tester) => tester.pumpWidget(
    const MaterialApp(home: Scaffold(body: VisaDisclaimer())),
  );

  testWidgets('안내(info) 문구가 React와 같은 글자로 보이고 링크 글자는 "하이코리아"', (tester) async {
    await pumpDisclaimer(tester);
    final notice = tester.widget<Notice>(find.byType(Notice));
    expect(notice.kind, NoticeKind.info);
    // WidgetSpan(링크)은 글자 자리표시(U+FFFC)로 들어가므로 나머지 글자를 이어 붙여 비교한다
    final plain = tester
        .widgetList<RichText>(find.byType(RichText))
        .map((r) => r.text.toPlainText())
        .firstWhere((t) => t.startsWith('후기는'));
    expect(
      plain.replaceAll('￼', '[하이코리아]'),
      '후기는 개인 경험이라 지금 기준과 다를 수 있어요(작성일을 확인하세요). '
      '신청 대행·개인별 법률 상담은 하지 않아요. 공식 안내는 [하이코리아]에서 확인하세요',
    );
    expect(find.text('하이코리아'), findsOneWidget);
  });

  testWidgets('"하이코리아"를 누르면 https://www.hikorea.go.kr/ 을 새 창 열기 도구에 넘긴다', (tester) async {
    await pumpDisclaimer(tester);
    expect(opener.opened, isEmpty);
    await tester.tap(find.text('하이코리아'));
    await tester.pump();
    expect(opener.opened, ['https://www.hikorea.go.kr/']);
    expect(hikoreaUrl, 'https://www.hikorea.go.kr/');
  });

  testWidgets('링크 시맨틱이 있다', (tester) async {
    final handle = tester.ensureSemantics();
    await pumpDisclaimer(tester);
    expect(tester.getSemantics(find.text('하이코리아')).flagsCollection.isLink, isTrue);
    handle.dispose();
  });
}
