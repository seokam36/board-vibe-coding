// S26-k2 쪽지 대화 새 모양 테스트: 머리 판(이니셜·닉네임 22·700·heading 1·한 줄 글 제목),
// 말풍선·답장 칸이 흰 판(Panel) 안에 있는지, 말풍선 색·폭, 375×667에서 스크롤 끝 위치.
// 기능 동작(마지막 쪽지 보임·답장 붙음·탈퇴·미인증 안내)은 message_pages_test가 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/messages/message_composer.dart';
import 'package:board_web/widgets/app_link.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/message_json.dart';

// 서울대 인증 회원
Map<String, dynamic> _user() => userJson(school: marketSeoul);

// 대화 3을 연다. 화면 크기는 width×height(논리 픽셀)
Future<TestApp> _open(
  WidgetTester tester, {
  Map<String, dynamic>? conv,
  Map<String, dynamic>? user, // 안 주면 서울대 인증 회원
  double width = 375,
  double height = 667,
  void Function(FakeAdapter adapter)? setUp, // 시험마다 더 필요한 가짜 응답을 깐다
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, height);
  addTearDown(tester.view.reset);
  // 한 테스트에서 두 번 열 때 앞 화면이 남지 않게 비운다 (남으면 두 번째 내용이 안 보인다)
  await tester.pumpWidget(const SizedBox.shrink());
  final adapter = FakeAdapter()
    ..on('GET', '/conversations/3', FakeReply(200, conv ?? conversationJson()))
    ..on('PUT', '/conversations/3/read', FakeReply(204))
    ..on('GET', '/report-reasons', FakeReply(200, [{'code': 'ABUSE', 'name': '욕설'}]));
  setUp?.call(adapter);
  final app = await pumpApp(tester, location: '/messages/3', user: user ?? _user(), adapter: adapter);
  await tester.pumpAndSettle();
  return app;
}

// 쪽지 n통 (번호 1..n, 내 것·상대 것이 번갈아)
List<Map<String, dynamic>> _manyMessages(int n) => [
  for (var i = 1; i <= n; i++) messageJson(id: i, content: '쪽지 번호 $i', mine: i.isOdd),
];

// 쪽지 판(답장 칸을 품은 Panel)
Finder _messagePanel() => find.ancestor(of: find.byKey(const ValueKey('message-composer-field')), matching: find.byType(Panel)).first;

// 머리 판(상대 닉네임을 품은 Panel)의 높이
double _headHeight(WidgetTester tester, String nickname) {
  final panel = find.ancestor(of: find.text(nickname), matching: find.byType(Panel));
  return tester.getSize(panel.first).height;
}

final _bubbles = find.byWidgetPredicate((w) => w.key is ValueKey<String> && (w.key! as ValueKey<String>).value.startsWith('msg-bubble-'));

Color _bg(WidgetTester tester, Finder f) => (tester.widget<Container>(f).decoration! as BoxDecoration).color!;

void main() {
  testWidgets('머리 판: 이니셜(36) 1개, 닉네임 22·700·heading 1', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester);
    final headPanel = find.ancestor(of: find.text('판매자닉'), matching: find.byType(Panel)).first;
    expect(find.descendant(of: headPanel, matching: find.byType(InitialAvatar)), findsOneWidget);
    final avatar = tester.widget<InitialAvatar>(find.byType(InitialAvatar));
    expect(avatar.size, 36);
    expect(avatar.nickname, '판매자닉');
    final style = tester.widget<Text>(find.text('판매자닉')).style!;
    expect(style.fontSize, AppSizes.fsTitle);
    expect(style.fontWeight, FontWeight.w700);
    final data = tester.getSemantics(find.text('판매자닉'));
    expect(data.flagsCollection.isHeader, isTrue);
    expect(data.headingLevel, 1);
    handle.dispose(); // 테스트 끝 검사가 teardown보다 먼저라 여기서 직접 닫는다
  });

  testWidgets('머리 판: 탈퇴한 상대는 글자 없는 빈 동그라미', (tester) async {
    await _open(tester, conv: conversationJson(partner: null));
    expect(find.byType(InitialAvatar), findsOneWidget);
    final avatar = tester.widget<InitialAvatar>(find.byType(InitialAvatar));
    expect(avatar.nickname, isNull);
    expect(avatar.initial, '');
  });

  for (final width in [375.0, 320.0]) {
    testWidgets('머리 판 ${width.toInt()}px: 100자 글 제목이어도 높이가 10자일 때와 같다', (tester) async {
      await _open(tester, width: width, conv: conversationJson(item: itemRefJson(title: '가' * 10)));
      final short = _headHeight(tester, '판매자닉');
      await _open(tester, width: width, conv: conversationJson(item: itemRefJson(title: '가' * 100)));
      final long = _headHeight(tester, '판매자닉');
      expect(find.text('가' * 100), findsOneWidget); // 두 번째로 연 화면이 정말 긴 제목인지
      expect(long, short);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('머리 판: 글이 지워졌어도 100자 제목이 한 줄로 줄어든다', (tester) async {
    await _open(tester, conv: conversationJson(item: itemRefJson(id: null, deleted: true, title: '가' * 10)));
    final short = _headHeight(tester, '판매자닉');
    await _open(tester, conv: conversationJson(item: itemRefJson(id: null, deleted: true, title: '가' * 100)));
    expect(find.text('가' * 100), findsOneWidget);
    expect(_headHeight(tester, '판매자닉'), short);
    expect(tester.takeException(), isNull);
  });

  testWidgets('말풍선은 모두 Panel 안에 있고 색은 내 것 accentSoft·상대 hover, 폭은 판 안쪽의 85% 이하', (tester) async {
    await _open(
      tester,
      conv: conversationJson(
        messages: [
          messageJson(id: 1, content: '내 긴 쪽지 ' * 80, mine: true),
          messageJson(id: 2, content: '상대 긴 쪽지 ' * 80, mine: false),
        ],
      ),
    );
    expect(_bubbles, findsNWidgets(2));
    for (final id in [1, 2]) {
      final bubble = find.byKey(ValueKey('msg-bubble-$id'));
      final panel = find.ancestor(of: bubble, matching: find.byType(Panel));
      expect(panel, findsOneWidget);
      // 판 안쪽 폭 = 판 폭 - 안쪽 여백 16×2 - 테두리 1×2
      final inner = tester.getSize(panel).width - 2 * AppSizes.space4 - 2;
      expect(tester.getSize(bubble).width, lessThanOrEqualTo(inner * 0.85 + 0.5));
    }
    expect(_bg(tester, find.byKey(const ValueKey('msg-bubble-1'))), AppColors.accentSoft);
    expect(_bg(tester, find.byKey(const ValueKey('msg-bubble-2'))), AppColors.hover);
    // 내 것은 오른쪽, 상대는 왼쪽
    expect(tester.getTopLeft(find.byKey(const ValueKey('msg-bubble-1'))).dx, greaterThan(tester.getTopLeft(find.byKey(const ValueKey('msg-bubble-2'))).dx));
  });

  testWidgets('빈 문구와 "최근 200통" 안내도 말풍선과 같은 Panel 안에 있다', (tester) async {
    await _open(tester, conv: conversationJson(messages: [], hasMore: true));
    final empty = find.ancestor(of: find.text('아직 쪽지가 없습니다'), matching: find.byType(Panel));
    final notice = find.ancestor(of: find.byKey(const ValueKey('msg-truncated')), matching: find.byType(Panel));
    expect(empty, findsOneWidget);
    expect(notice, findsOneWidget);
    expect(tester.getRect(empty), tester.getRect(notice));
  });

  testWidgets('답장 입력칸은 말풍선과 같은 Panel 안에 있다', (tester) async {
    await _open(tester);
    final field = find.byKey(const ValueKey('message-composer-field'));
    final panel = find.ancestor(of: field, matching: find.byType(Panel));
    expect(panel, findsOneWidget);
    expect(find.descendant(of: panel, matching: find.byKey(const ValueKey('msg-bubble-1'))), findsOneWidget);
  });

  // 열자마자(자동으로 끝까지 내린 뒤): 보내기 버튼과 판 아래 끝이 모두 탭 막대 위에 있어야 한다
  void expectEndVisible(WidgetTester tester) {
    final send = tester.getRect(find.widgetWithText(FilledButton, '답장 보내기'));
    final panel = tester.getRect(_messagePanel());
    final tabs = tester.getRect(find.byKey(const ValueKey('bottom-tab-0')));
    expect(send.bottom, lessThan(tabs.top));
    expect(send.bottom, lessThan(panel.bottom)); // 버튼 아래에 판 아래 여백이 남는다
    expect(panel.bottom, lessThanOrEqualTo(tabs.top));
    expect(send.top, greaterThanOrEqualTo(0));
  }

  testWidgets('375×667 쪽지 40통: 열자마자 버튼 아래 끝·판 아래 끝이 탭 막대 위에 있다', (tester) async {
    await _open(tester, conv: conversationJson(messages: _manyMessages(40)));
    expectEndVisible(tester);
    // 맨 처음 쪽지는 화면 밖(위)으로 올라가 있다 = 정말 끝까지 내려갔다
    expect(tester.getTopLeft(find.byKey(const ValueKey('msg-1'))).dy, lessThan(0));
    expect(tester.takeException(), isNull);
  });

  testWidgets('375×667 쪽지 40통: 답장을 보낸 뒤에도 끝이 탭 막대 위에 있다', (tester) async {
    await _open(
      tester,
      conv: conversationJson(messages: _manyMessages(40)),
      setUp: (a) => a.on('POST', '/conversations/3/messages', FakeReply(201, messageJson(id: 41, content: '새 답장'))),
    );
    await tester.enterText(find.byKey(const ValueKey('message-composer-field')), '새 답장');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, '답장 보내기'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('msg-41')), findsOneWidget);
    expectEndVisible(tester);
    expect(tester.takeException(), isNull);
  });

  testWidgets('말풍선 사이 12, 쪽지 목록과 답장 칸 사이 16', (tester) async {
    await _open(tester);
    final m1 = tester.getRect(find.byKey(const ValueKey('msg-1')));
    final m2 = tester.getRect(find.byKey(const ValueKey('msg-2')));
    expect(m2.top - m1.bottom, AppSizes.space3);
    final composer = tester.getRect(find.byType(MessageComposer));
    expect(composer.top - m2.bottom, AppSizes.space4);
  });

  testWidgets('미인증 회원: 답장 안내가 같은 Panel 안에 있고 입력칸은 없다', (tester) async {
    await _open(tester, user: userJson()); // verifiedSchool 없음
    final hint = find.text('유학생 인증 후 답장할 수 있어요');
    expect(hint, findsOneWidget);
    final panel = find.ancestor(of: hint, matching: find.byType(Panel));
    expect(panel, findsOneWidget);
    expect(find.descendant(of: panel, matching: find.byKey(const ValueKey('msg-bubble-1'))), findsOneWidget);
    expect(find.byKey(const ValueKey('message-composer-field')), findsNothing);
  });

  testWidgets('줄임표 된 글 제목 링크: 스크린리더 라벨은 제목 전체, 누르면 글 주소로 간다', (tester) async {
    final handle = tester.ensureSemantics();
    final title = '가' * 100;
    final app = await _open(
      tester,
      conv: conversationJson(item: itemRefJson(title: title)),
      setUp: (a) => a.on('GET', '/market-items/7', FakeReply(200, marketDetailJson(sellerId: 5))),
    );
    final link = find.descendant(of: find.ancestor(of: find.text('판매자닉'), matching: find.byType(Panel)).first, matching: find.byType(AppLink));
    expect(link, findsOneWidget);
    final data = tester.getSemantics(link);
    expect(data.label, title);
    expect(data.flagsCollection.isLink, isTrue);
    await tester.tap(link);
    await tester.pumpAndSettle();
    expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/market/7');
    handle.dispose(); // 테스트 끝 검사가 teardown보다 먼저라 여기서 직접 닫는다
  });

  group('AppLink oneLine 단독', () {
    Future<double> linkHeight(WidgetTester tester, {required bool oneLine}) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(200, 600);
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            // 폭을 정해 주는 부모(Align + SizedBox 150)
            body: Align(
              alignment: Alignment.topLeft,
              child: SizedBox(width: 150, child: AppLink(label: '가나다라마바사 ' * 20, oneLine: oneLine, onTap: () {})),
            ),
          ),
        ),
      );
      return tester.getSize(find.byType(AppLink)).height;
    }

    testWidgets('기본은 줄바꿈이라 여러 줄, true면 한 줄 높이', (tester) async {
      final multi = await linkHeight(tester, oneLine: false);
      final single = await linkHeight(tester, oneLine: true);
      // 한 줄 높이 = 글자 한 줄 + 위아래 여백 4+4. 기본은 그보다 훨씬 크다
      expect(single, lessThan(multi));
      expect(multi, greaterThan(single * 2));
      expect(single, lessThan(AppSizes.tapMin)); // 한 줄이면 48보다 작다
    });
  });

  testWidgets('320px 글씨 ×2.0: "삭제된 글" 뱃지 + 긴 제목 머리 판이 넘치지 않는다', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2.0;
    addTearDown(tester.platformDispatcher.clearAllTestValues);
    await _open(
      tester,
      width: 320,
      height: 1000,
      conv: conversationJson(item: itemRefJson(id: null, deleted: true, title: '나' * 100)),
    );
    expect(find.text('삭제된 글'), findsOneWidget);
    expect(find.text('나' * 100), findsOneWidget);
    expect(tester.takeException(), isNull); // 넘침(overflow) 오류가 없다
  });

  testWidgets('탈퇴한 상대면 안내가 같은 Panel 안에 있고 입력칸은 없다', (tester) async {
    await _open(tester, conv: conversationJson(partner: null));
    final notice = find.text('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요');
    expect(find.ancestor(of: notice, matching: find.byType(Panel)), findsOneWidget);
    expect(find.byKey(const ValueKey('message-composer-field')), findsNothing);
  });
}
