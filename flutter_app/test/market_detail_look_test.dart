// S26-k1 중고 글 보기 새 모양 테스트: 판매자 머리(이니셜·닉네임·시각), 제목 22·700, 가격 17·700,
// 쪽지 칸이 판(Panel) 안에 있는지, 긴 닉네임 + 뱃지 넘침을 본다. 기능 동작은 market_detail_page_test 등이 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';

// 서울대 인증 회원. 판매자는 id 1 → 기본 id 5는 남의 글을 보는 사람
Map<String, dynamic> _user({int id = 5}) => userJson(id: id, school: marketSeoul);

Future<void> _open(
  WidgetTester tester, {
  Map<String, dynamic>? item,
  Map<String, dynamic>? user,
  double width = 1200,
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 2400);
  addTearDown(tester.view.reset);
  final adapter = FakeAdapter()
    ..on('GET', '/market-items/7', FakeReply(200, item ?? marketDetailJson()))
    ..on('GET', '/report-reasons', FakeReply(200, [{'code': 'ABUSE', 'name': '욕설'}]));
  await pumpApp(tester, location: '/market/7', user: user ?? _user(), adapter: adapter);
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
}

TextStyle _style(WidgetTester tester, String text) => tester.widget<Text>(find.text(text)).style!;

// 쪽지 칸 제목 글자 (이 글자의 조상 Panel이 쪽지 칸 판이다)
Finder get _boxHeading => find.text('판매자에게 쪽지');

void main() {
  testWidgets('머리: 이니셜(36) 1개 + 판매자 닉네임(15·700) + 시각이 닉네임 아래', (tester) async {
    await _open(tester);
    expect(find.byType(InitialAvatar), findsOneWidget);
    expect(tester.widget<InitialAvatar>(find.byType(InitialAvatar)).size, 36);
    expect(find.descendant(of: find.byType(InitialAvatar), matching: find.text('길')), findsOneWidget);
    final nick = _style(tester, '길동이');
    expect(nick.fontSize, AppSizes.fsBody);
    expect(nick.fontWeight, FontWeight.w700);
    final time = find.textContaining('2020');
    expect(tester.widget<Text>(time.first).style!.color, AppColors.muted);
    expect(tester.getTopLeft(time.first).dy, greaterThanOrEqualTo(tester.getBottomLeft(find.text('길동이')).dy - 1));
  });

  testWidgets('제목은 22·700·heading 1, 머리 아래에 있다', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester);
    final title = _style(tester, '전공 서적 팝니다');
    expect(title.fontSize, AppSizes.fsTitle);
    expect(title.fontWeight, FontWeight.w700);
    final data = tester.getSemantics(find.text('전공 서적 팝니다'));
    expect(data.flagsCollection.isHeader, isTrue);
    expect(data.headingLevel, 1);
    expect(tester.getTopLeft(find.text('전공 서적 팝니다')).dy, greaterThan(tester.getBottomLeft(find.text('길동이')).dy));
    handle.dispose();
  });

  testWidgets('가격은 17·700이고 제목 아래, 본문 위에 있다', (tester) async {
    await _open(tester);
    final price = _style(tester, '15,000원');
    expect(price.fontSize, 17);
    expect(price.fontWeight, FontWeight.w700);
    expect(tester.getTopLeft(find.text('15,000원')).dy, greaterThan(tester.getBottomLeft(find.text('전공 서적 팝니다')).dy - 1));
    expect(tester.getTopLeft(find.text('거의 새 책이에요.')).dy, greaterThan(tester.getBottomLeft(find.text('15,000원')).dy));
  });

  testWidgets('쪽지 칸(입력칸이 보일 때)은 Panel 안에 있고 제목이 있다', (tester) async {
    await _open(tester);
    expect(find.ancestor(of: _boxHeading, matching: find.byType(Panel)), findsOneWidget);
    expect(find.text('쪽지 보내기'), findsOneWidget); // 입력칸이 보이는 경우
  });

  testWidgets('쪽지 칸(이유 한 줄만 보일 때)도 Panel 안에 있다: 비로그인', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(1200, 2400);
    addTearDown(tester.view.reset);
    final adapter = FakeAdapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson()));
    await pumpApp(tester, location: '/market/7', adapter: adapter);
    await tester.pumpAndSettle();
    final hint = find.text('로그인하면 판매자에게 쪽지를 보낼 수 있습니다');
    expect(hint, findsOneWidget);
    expect(find.ancestor(of: hint, matching: find.byType(Panel)), findsOneWidget);
    expect(find.ancestor(of: _boxHeading, matching: find.byType(Panel)), findsOneWidget);
  });

  testWidgets('쪽지 칸(이유 한 줄만 보일 때)도 Panel 안에 있다: 거래완료 글', (tester) async {
    await _open(tester, item: marketDetailJson(statusCode: 'DONE', statusName: '거래완료'));
    expect(find.textContaining('거래가 완료된 글이라'), findsOneWidget);
    expect(find.ancestor(of: _boxHeading, matching: find.byType(Panel)), findsOneWidget);
    expect(find.text('쪽지 보내기'), findsNothing);
  });

  testWidgets('내 글이면 쪽지 칸이 없다', (tester) async {
    await _open(tester, user: _user(id: 1));
    expect(_boxHeading, findsNothing);
  });

  for (final width in [375.0, 320.0]) {
    testWidgets('${width.toInt()}px: 20자 닉네임 + 뱃지 2개 + 신고 줄도 넘치지 않는다', (tester) async {
      await _open(
        tester,
        item: marketDetailJson(sellerNickname: '가나다라마바사아자차카타파하가나다라마바', statusName: '예약중', statusCode: 'RESERVED'),
        width: width,
      );
      expect(tester.takeException(), isNull);
      // 닉네임은 판 안(화면 폭 안)에 있다
      expect(tester.getRect(find.text('가나다라마바사아자차카타파하가나다라마바')).right, lessThanOrEqualTo(width));
    });
  }

  testWidgets('작업 줄: 관리자·남의 글·같은 학교면 삭제·신고 버튼이 한 줄(세로 중심 같음)', (tester) async {
    await _open(tester, user: userJson(id: 9, role: 'ADMIN', school: marketSeoul));
    final del = find.widgetWithText(OutlinedButton, '삭제');
    final report = find.widgetWithText(OutlinedButton, '신고');
    expect(del, findsOneWidget);
    expect(report, findsOneWidget);
    expect(tester.getCenter(del).dy, closeTo(tester.getCenter(report).dy, 0.5));
    expect(tester.getRect(report).left, greaterThan(tester.getRect(del).right)); // 가로로 나란히
  });

  testWidgets('작업 줄: 본인 글이면 삭제 버튼 오른쪽 끝이 글 판 오른쪽 끝(안쪽 여백 포함 아님)과 같은 줄에서 끝난다', (tester) async {
    await _open(tester, user: _user(id: 1));
    final del = find.widgetWithText(OutlinedButton, '삭제');
    expect(del, findsOneWidget);
    // 작업 줄은 판 밖(PageFrame 안)에 있어 글 판과 같은 오른쪽 끝에 맞춰진다 (WrapAlignment.end)
    final panel = find.ancestor(of: find.text('전공 서적 팝니다'), matching: find.byType(Panel)).first;
    expect(tester.getRect(del).right, closeTo(tester.getRect(panel).right, 0.5));
  });

  testWidgets('쪽지 칸(입력칸): 제목~입력칸 12, 판 위 여백 = 판 아래 여백 16', (tester) async {
    await _open(tester);
    final panel = find.ancestor(of: _boxHeading, matching: find.byType(Panel)).first;
    final box = tester.getRect(panel);
    final headingBottom = tester.getBottomLeft(_boxHeading).dy; // 제목 글자 바닥
    final field = tester.getRect(find.byKey(const ValueKey('message-composer-field')));
    expect(field.top - headingBottom, closeTo(12, 0.5)); // 제목 아래 12 하나만
    expect(tester.getTopLeft(_boxHeading).dy - box.top, closeTo(16, 1.5)); // 판 위 여백 (테두리 포함 오차)
    final button = tester.getRect(find.widgetWithText(FilledButton, '쪽지 보내기'));
    // Panel 위젯 사각형은 바깥 아래 여백 16을 포함하므로 그만큼 뺀다 → 판 안쪽 아래 여백
    expect(box.bottom - AppSizes.space4 - button.bottom, closeTo(16, 1.5));
  });

  testWidgets('한 줄이어야 하는 곳: 가격·시각은 한 줄 높이다', (tester) async {
    await _open(tester);
    // 17px·줄 간격 1.5 → 한 줄 25.5 안팎 (두 줄이면 51 안팎)
    expect(tester.getSize(find.text('15,000원')).height, lessThan(30));
    expect(tester.getSize(find.textContaining('2020').first).height, lessThan(24)); // 13px·1.65 한 줄
  });
}
