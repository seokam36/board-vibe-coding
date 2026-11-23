// S26-k7 챗봇 화면 모양 테스트: 참고한 글이 FeedRow(줄 어디나 눌림·라벨 하나), 안내·입력칸·보내기가 Panel 한 장 안, 답 말풍선은 Text 하나.
import 'package:board_web/pages/chat_page.dart' show chatGuide, chatGuideExample, chatPrivacyNotice;
import 'package:board_web/widgets/feed_row.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/chat_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/screen.dart';

final _field = find.byKey(const ValueKey('chat-field'));
final _send = find.widgetWithText(FilledButton, '보내기');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 챗봇 화면을 한 번만 열고(pumpApp은 테스트당 한 번), 질문을 보내 답을 받는다
Future<TestApp> _openAndAsk(WidgetTester tester, FakeReply reply, {double? width}) async {
  useTallScreen(tester);
  if (width != null) tester.view.physicalSize = Size(width, 1800); // useTallScreen 뒤에 폭만 바꾼다
  final adapter = FakeAdapter()
    ..on('POST', '/chat', reply)
    ..on('GET', '/market-items/12', FakeReply(200, marketDetailJson(id: 12)))
    ..on('GET', '/market-items/5', FakeReply(200, marketDetailJson(id: 5, title: '책상 드려요')));
  final app = await pumpApp(tester, location: '/chat', user: userJson(school: marketSeoul), adapter: adapter);
  await tester.enterText(_field, '질문');
  await tester.pump();
  await tester.tap(_send);
  await tester.pumpAndSettle();
  return app;
}

FakeReply _twoSources() => FakeReply(
  200,
  chatJson(
    sources: [
      chatSourceJson(id: 12, title: '자전거 팝니다'),
      chatSourceJson(
        id: 5,
        title: '책상 드려요',
        tradeCode: 'FREE',
        tradeName: '나눔',
        price: 0,
        statusCode: 'RESERVED',
        statusName: '예약중',
      ),
    ],
  ),
);

void main() {
  group('참고한 글 줄 (S26-k7)', () {
    testWidgets('줄마다 FeedRow 하나, 눌림 높이 48 이상, 판은 FeedPanel', (tester) async {
      await _openAndAsk(tester, _twoSources());
      expect(find.byType(FeedPanel), findsOneWidget);
      expect(find.byType(FlushPanel), findsNothing);
      expect(find.byType(FeedRow), findsNWidgets(2));
      for (var i = 0; i < 2; i++) {
        expect(tester.getSize(find.byType(FeedRow).at(i)).height, greaterThanOrEqualTo(48));
      }
    });

    testWidgets('가격 글자를 눌러도 /market/{id}로 간다', (tester) async {
      final app = await _openAndAsk(tester, _twoSources());
      final first = find.byKey(const ValueKey('chat-source-0-12'));
      await tester.tap(find.descendant(of: first, matching: find.text('30,000원')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/12');
    });

    testWidgets('줄의 빈 곳(상태 뱃지 오른쪽 끝)을 눌러도 /market/{id}로 간다', (tester) async {
      final app = await _openAndAsk(tester, _twoSources());
      final row = find.byKey(const ValueKey('chat-source-1-5'));
      final rect = tester.getRect(row);
      await tester.tapAt(Offset(rect.right - 8, rect.center.dy)); // 글자 없는 오른쪽 끝
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/5');
    });

    testWidgets('스크린리더: 줄 하나가 "제목, 가격, 상태" 순서의 링크 하나', (tester) async {
      final handle = tester.ensureSemantics();
      await _openAndAsk(tester, _twoSources());
      final node = tester.getSemantics(find.byKey(const ValueKey('chat-source-0-12')));
      final data = node.getSemanticsData();
      expect(data.flagsCollection.isLink, isTrue);
      expect(data.linkUrl, Uri.parse('/market/12')); // 링크 주소 = 눌러서 가는 곳
      expect(data.hasAction(SemanticsAction.tap), isTrue);
      expect(data.label, '자전거 팝니다, 30,000원, 판매중');
      // 안쪽에 따로 읽히는 항목이 없다
      var children = 0;
      node.visitChildren((_) {
        children++;
        return true;
      });
      expect(children, 0);
      final second = tester.getSemantics(find.byKey(const ValueKey('chat-source-1-5'))).getSemanticsData();
      expect(second.label, '책상 드려요, 나눔, 예약중');
      handle.dispose();
    });

    for (final w in [375.0, 320.0]) {
      testWidgets('폭 ${w.toInt()}px: 100자 제목도 넘치지 않는다', (tester) async {
        final title = '긴제목' * 33 + 'a'; // 100자
        expect(title.length, 100);
        await _openAndAsk(
          tester,
          FakeReply(200, chatJson(sources: [chatSourceJson(title: title)])),
          width: w,
        );
        expect(tester.takeException(), isNull); // 오버플로는 예외로 잡힌다
        expect(find.text(title), findsOneWidget);
        expect(tester.getSize(find.text(title)).width, lessThanOrEqualTo(w));
      });
    }

    testWidgets('같은 글이 두 번 와도 예외 없이 두 줄이 보인다', (tester) async {
      await _openAndAsk(
        tester,
        FakeReply(200, chatJson(sources: [chatSourceJson(id: 7), chatSourceJson(id: 7)])),
      );
      expect(tester.takeException(), isNull);
      expect(find.byType(FeedRow), findsNWidgets(2));
    });
  });

  group('질문 판 (S26-k7)', () {
    testWidgets('안내 3줄·입력칸·"0 / 500"·보내기가 같은 Panel 한 장 안에 있다', (tester) async {
      useTallScreen(tester);
      await pumpApp(tester, location: '/chat', user: userJson(school: marketSeoul), adapter: FakeAdapter());
      final panel = find.byType(Panel);
      expect(panel, findsOneWidget);
      for (final inside in [
        find.text(chatGuide),
        find.text(chatGuideExample),
        find.text(chatPrivacyNotice),
        _field,
        find.text('0 / 500'),
        _send,
      ]) {
        expect(find.descendant(of: panel, matching: inside), findsOneWidget);
      }
    });

    testWidgets('답 말풍선은 Text 하나이고 링크·눌림이 없다', (tester) async {
      await _openAndAsk(tester, FakeReply(200, chatJson(answer: '답입니다 https://a.b', sources: [chatSourceJson()])));
      final bubble = find.byKey(const ValueKey('chat-answer'));
      expect(find.descendant(of: bubble, matching: find.byType(Text)), findsOneWidget);
      expect(find.descendant(of: bubble, matching: find.byType(FeedRow)), findsNothing);
      expect(find.descendant(of: bubble, matching: find.byType(InkWell)), findsNothing);
      // 눌러서 가는 줄은 참고한 글 쪽 FeedRow 하나뿐
      expect(find.byType(FeedRow), findsOneWidget);
    });
  });
}
