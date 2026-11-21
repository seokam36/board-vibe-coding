// S17-d·f4 중고 글 챗봇 화면 테스트: 보내기·잠금(버튼/Enter 연타)·답은 글자 그대로(링크 0개)·출처 줄·실패 문구 8가지(f4: 429 추가)·401 재발급·90초 제한.
import 'dart:async';

import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/pages/chat_page.dart' show chatGuide, chatGuideExample, chatPrivacyNotice;
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/chat_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/screen.dart';

Map<String, dynamic> _user() => userJson(school: marketSeoul);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 401은 자동 재발급을 먼저 시도한다. 그 비동기 요청이 끝나길 기다린다
Future<void> _waitRefresh(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
}

final _field = find.byKey(const ValueKey('chat-field'));
final _send = find.widgetWithText(FilledButton, '보내기');
String _text(WidgetTester tester) => tester.widget<TextField>(_field).controller!.text;
bool _enabled(WidgetTester tester) => tester.widget<FilledButton>(_send).onPressed != null;
bool _focused(WidgetTester tester) => tester.widget<TextField>(_field).focusNode!.hasFocus;
Finder _notice(String message) => find.byWidgetPredicate((w) => w is Notice && w.message == message);

// 챗봇 화면을 로그인 상태로 연다
Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter) =>
    pumpApp(tester, location: '/chat', user: _user(), adapter: adapter);

Future<void> _type(WidgetTester tester, String text) async {
  await tester.enterText(_field, text);
  await tester.pump();
}

Future<void> _tapSend(WidgetTester tester) async {
  await tester.tap(_send);
  await tester.pumpAndSettle();
}

void main() {
  group('열기와 문지기', () {
    _test('로그인 회원은 /chat 에 머물고 제목·설명·안내가 보인다', (tester) async {
      final app = await _open(tester, FakeAdapter());
      expect(_uri(app), '/chat');
      expect(find.text('중고 글 챗봇'), findsOneWidget);
      expect(find.text('판매중·예약중 중고 글을 찾아 AI가 답해요'), findsNothing); // 중복 부제는 지웠다
      expect(_notice("AI 답은 틀릴 수 있어요. 아래 '참고한 글'에서 직접 확인해 주세요"), findsOneWidget);
      expect(find.text('0 / 500'), findsOneWidget);
      expect(find.text('참고한 글'), findsNothing);
    });

    // S18-d2: 질문 칸 위 사용 안내(범위 + 예시)
    _test('질문 칸 위에 중고·나눔 안내와 예시 문구가 보인다', (tester) async {
      await _open(tester, FakeAdapter());
      expect(find.text('중고·나눔 글을 찾아 주는 챗봇이에요. 물건 이름과 가격을 넣어 물어보세요.'), findsOneWidget);
      expect(find.text('예) 3만 원 이하 자전거 있어?'), findsOneWidget);
      // 안내가 질문 칸보다 위에 있다
      final guideY = tester.getTopLeft(find.text(chatGuide)).dy;
      expect(guideY, lessThan(tester.getTopLeft(_field).dy));
    });

    _test('폭 320px에서도 안내 문구가 넘치지 않는다', (tester) async {
      tester.view.physicalSize = const Size(320, 1800); // useTallScreen 뒤에 폭만 좁힌다
      await _open(tester, FakeAdapter());
      expect(find.text(chatGuide), findsOneWidget);
      expect(tester.takeException(), isNull); // 오버플로는 예외로 잡힌다
    });

    // 안내 세 줄은 스크린리더에 한 덩어리(container)로 읽혀야 한다
    _test('안내 세 줄이 스크린리더에 읽힌다(접근성 트리에 글자가 있다)', (tester) async {
      final semantics = tester.ensureSemantics();
      await _open(tester, FakeAdapter());
      final data = tester.getSemantics(find.text(chatGuide)).getSemanticsData();
      expect(data.label, contains(chatGuide));
      expect(data.label, contains(chatGuideExample));
      expect(data.label, contains(chatPrivacyNotice)); // S18-g: 세 번째 줄(개인정보 안내)도 같은 덩어리
      semantics.dispose();
    });

    // S18-g: 질문이 구글 AI로 간다는 개인정보 안내가 늘 보이고, 320px에서도 넘치지 않는다
    _test('구글 전송·개인정보 안내가 보이고 320px에서 넘치지 않는다', (tester) async {
      tester.view.physicalSize = const Size(320, 1800);
      await _open(tester, FakeAdapter());
      expect(find.text('질문은 구글 AI로 전송돼요. 연락처 같은 개인정보는 쓰지 마세요.'), findsOneWidget);
      expect(tester.takeException(), isNull);
      expect(tester.getSize(find.text(chatPrivacyNotice)).width, lessThanOrEqualTo(320));
    });

    // 320px·375px 두 폭 모두 오버플로(예외) 없음
    for (final w in [320.0, 375.0]) {
      _test('폭 ${w.toInt()}px: 안내 세 줄이 화면 안에 들어오고 예외가 없다', (tester) async {
        tester.view.physicalSize = Size(w, 1800);
        await _open(tester, FakeAdapter());
        expect(tester.takeException(), isNull);
        expect(tester.getSize(find.text(chatGuide)).width, lessThanOrEqualTo(w));
        expect(tester.getSize(find.text(chatGuideExample)).width, lessThanOrEqualTo(w));
        expect(tester.getSize(find.text(chatPrivacyNotice)).width, lessThanOrEqualTo(w)); // S18-g
      });
    }

    for (final path in ['/chat', '/Chat', '/chat/']) {
      _test('비로그인으로 $path 를 열면 /login', (tester) async {
        final app = await pumpApp(tester, location: path);
        expect(_uri(app), '/login');
      });
    }

    _test('상단바 챗봇을 누르면 /chat', (tester) async {
      final app = await pumpApp(tester, user: _user());
      await tester.tap(find.text('챗봇'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/chat');
    });
  });

  group('입력', () {
    _test('비었거나 공백뿐이면 보내기가 꺼져 있고 요청 0번', (tester) async {
      final adapter = FakeAdapter();
      await _open(tester, adapter);
      expect(_enabled(tester), isFalse);
      await _type(tester, '   ');
      expect(_enabled(tester), isFalse);
      // Enter로도 보내지 않는다
      await tester.testTextInput.receiveAction(TextInputAction.send);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/chat'), 0);
      await _type(tester, '자전거');
      expect(_enabled(tester), isTrue);
    });

    _test('501번째 글자는 입력되지 않고 500 / 500', (tester) async {
      await _open(tester, FakeAdapter());
      await _type(tester, 'a' * 501);
      expect(_text(tester).length, 500);
      expect(find.text('500 / 500'), findsOneWidget);
    });

    _test('UTF-16로 센다: 이모지(2칸)가 한도에서 반쪽으로 잘리지 않는다', (tester) async {
      await _open(tester, FakeAdapter());
      await _type(tester, '${'a' * 499}😀');
      expect(_text(tester), 'a' * 499);
      expect(find.text('499 / 500'), findsOneWidget);
    });
  });

  group('보내기', () {
    _test('버튼: 본문 {"question": 입력값} 1번, 보내는 중 잠금, 성공하면 칸 비움·포커스', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
      final bodies = <Object?>[];
      await _open(tester, adapter);
      adapter.onRequestBody = (d) {
        if (d != null) bodies.add(d);
      };
      await _type(tester, '3만 원 이하 자전거 있어?');
      await tester.tap(_send);
      await tester.pump(const Duration(milliseconds: 50)); // 요청이 가짜 서버에 닿을 시간
      expect(find.text('답을 만드는 중...'), findsOneWidget);
      expect(_enabled(tester), isFalse);
      // 잠긴 동안 버튼·Enter를 또 눌러도 요청은 1번
      await tester.tap(_send, warnIfMissed: false);
      await tester.testTextInput.receiveAction(TextInputAction.send);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', '/chat'), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(bodies, [
        {'question': '3만 원 이하 자전거 있어?'},
      ]);
      expect(find.text('답을 만드는 중...'), findsNothing);
      expect(_text(tester), '');
      expect(_focused(tester), isTrue);
      expect(find.text('3만 원 이하 자전거 있어?'), findsOneWidget); // 보낸 질문이 답 위에
      expect(_enabled(tester), isFalse); // 칸이 비었으니 다시 꺼짐
    });

    _test('Enter(키보드 전송)로도 보내고, 연달아 두 번 눌러도 요청은 1번', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
      await _open(tester, adapter);
      await _type(tester, '자전거');
      await tester.testTextInput.receiveAction(TextInputAction.send);
      await tester.testTextInput.receiveAction(TextInputAction.send);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', '/chat'), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('#12번 글에서 자전거를 3만 원에 팔고 있어요.'), findsOneWidget);
    });

    _test('다시 물으면 이전 답·출처는 지워지고 "만드는 중"만 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('POST', '/chat', FakeReply(200, chatJson(answer: '첫 답', sources: [chatSourceJson()])))
        ..on('POST', '/chat', FakeReply(200, chatJson(answer: '둘째 답'), gate));
      await _open(tester, adapter);
      await _type(tester, '첫 질문');
      await _tapSend(tester);
      expect(find.text('첫 답'), findsOneWidget);
      expect(find.text('참고한 글'), findsOneWidget);
      await _type(tester, '둘째 질문');
      await tester.tap(_send);
      await tester.pump();
      expect(find.text('첫 답'), findsNothing);
      expect(find.text('첫 질문'), findsNothing);
      expect(find.text('참고한 글'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('둘째 답'), findsOneWidget);
      expect(find.text('첫 답'), findsNothing); // 쌓지 않는다
    });
  });

  group('답과 출처', () {
    _test('답은 Text 하나에 글자 그대로: 마크다운·주소를 바꾸지 않고 링크 위젯 0개', (tester) async {
      const answer = '**굵게** [링크](http://x) https://a.b';
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(answer: answer)));
      await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      expect(find.text(answer), findsOneWidget);
      final text = tester.widget<Text>(find.text(answer));
      expect(text.data, answer);
      expect(text.textSpan, isNull);
      // 답 말풍선 안에는 눌러서 가는 것이 없다 (링크로 바뀌지 않았다)
      final bubble = find.byKey(const ValueKey('chat-answer'));
      expect(find.descendant(of: bubble, matching: find.byType(InkWell)), findsNothing);
      expect(find.descendant(of: bubble, matching: find.byType(GestureDetector)), findsNothing);
      expect(find.descendant(of: bubble, matching: find.byType(Text)), findsOneWidget);
    });

    _test('출처 2건: 응답 순서대로 제목·가격·상태, 줄을 누르면 /market/{id}', (tester) async {
      final adapter = FakeAdapter()
        ..on(
          'POST',
          '/chat',
          FakeReply(
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
          ),
        )
        ..on('GET', '/market-items/5', FakeReply(200, marketDetailJson(id: 5, title: '책상 드려요')));
      final app = await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      expect(find.text('참고한 글'), findsOneWidget);
      final first = find.byKey(const ValueKey('chat-source-0-12'));
      final second = find.byKey(const ValueKey('chat-source-1-5'));
      expect(tester.getTopLeft(first).dy, lessThan(tester.getTopLeft(second).dy)); // 응답 순서
      expect(find.descendant(of: first, matching: find.text('자전거 팝니다')), findsOneWidget);
      expect(find.descendant(of: first, matching: find.text('30,000원')), findsOneWidget);
      expect(find.descendant(of: first, matching: find.text('판매중')), findsOneWidget);
      expect(find.descendant(of: second, matching: find.text('책상 드려요')), findsOneWidget);
      expect(find.descendant(of: second, matching: find.text('나눔')), findsOneWidget);
      expect(find.descendant(of: second, matching: find.text('예약중')), findsOneWidget);
      await tester.tap(find.text('책상 드려요'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/5');
    });

    _test('출처가 []이면 answer만 보이고 "참고한 글"은 없다', (tester) async {
      const answer = '관련 글을 찾지 못했어요. 다른 단어로 물어봐 주세요.';
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(answer: answer)));
      await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      expect(find.text(answer), findsOneWidget);
      expect(find.text('참고한 글'), findsNothing);
    });

    // S18-d2: 서버의 "범위 안내" 답(sources []) 도 정상 답 말풍선으로 그대로 보이고, 안내 두 줄도 그대로 남는다
    _test('서버가 범위 안내 답을 주면 정상 답처럼 그대로 보이고 오류 안내는 없다', (tester) async {
      const answer = '저는 중고·나눔 글만 찾아 드려요. 찾는 물건 이름을 넣어 다시 물어봐 주세요.';
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(answer: answer)));
      await _open(tester, adapter);
      await _type(tester, '안녕');
      await _tapSend(tester);
      final bubble = find.byKey(const ValueKey('chat-answer'));
      expect(find.descendant(of: bubble, matching: find.text(answer)), findsOneWidget);
      expect(find.text('참고한 글'), findsNothing);
      expect(find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.error), findsNothing);
      expect(find.text(chatGuide), findsOneWidget); // 답이 나온 뒤에도 안내는 남는다
      expect(find.text(chatGuideExample), findsOneWidget);
      expect(find.text(chatPrivacyNotice), findsOneWidget);
    });

    _test('type이 MARKET_ITEM이 아닌 출처 줄은 보이지 않는다', (tester) async {
      final adapter = FakeAdapter()
        ..on(
          'POST',
          '/chat',
          FakeReply(
            200,
            chatJson(sources: [chatSourceJson(id: 12), chatSourceJson(id: 99, type: 'POST', title: '다른 종류 글')]),
          ),
        );
      await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      expect(find.text('자전거 팝니다'), findsOneWidget);
      expect(find.text('다른 종류 글'), findsNothing);
    });

    _test('답 영역은 스크린리더에 status 역할로 알린다', (tester) async {
      final semantics = tester.ensureSemantics();
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(answer: '답입니다')));
      await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      // 답 영역(chat-answer 키)의 노드와 그 조상 중에 status 역할이 있는지
      SemanticsNode? node = tester.getSemantics(find.byKey(const ValueKey('chat-answer')));
      var found = false;
      while (node != null) {
        if (node.getSemanticsData().role == SemanticsRole.status) found = true;
        node = node.parent;
      }
      expect(found, isTrue);
      semantics.dispose();
    });
  });

  group('실패', () {
    // 이름 · 가짜 응답 · 기대 문구
    final cases = <(String, FakeReply Function(), String)>[
      ('400', () => FakeReply(400, {'message': 'x'}), ErrorMessages.chatQuestionInvalid),
      ('403', () => FakeReply(403, {'message': 'x'}), csrfMessage),
      // 429는 본문 문구가 없어도(빈 본문) 화면의 새 문구가 보여야 한다. 기본 문구("답을 받지 못했습니다")는 안 보임
      ('429', () => FakeReply(429), ErrorMessages.chatRateLimited),
      ('503', () => FakeReply(503, {'message': 'x'}), ErrorMessages.chatUnavailable),
      ('504', () => FakeReply(504, {'message': 'x'}), ErrorMessages.chatTimeout),
      ('500', () => FakeReply(500, {'message': 'x'}), ErrorMessages.chatFailed),
      ('네트워크 끊김', FakeReply.networkError, networkErrorMessage),
      ('화면 쪽 시간 초과', FakeReply.timeout, ErrorMessages.chatTimeout),
    ];
    for (final (name, reply, message) in cases) {
      _test('$name: 문구, 질문 남음, 버튼 다시 켜짐', (tester) async {
        final adapter = FakeAdapter()..on('POST', '/chat', reply());
        await _open(tester, adapter);
        await _type(tester, '내 질문');
        await _tapSend(tester);
        expect(_notice(message), findsOneWidget);
        // 기본 문구로 떨어지지 않았는지(500 케이스만 기본 문구가 기대값)
        if (message != ErrorMessages.chatFailed) expect(find.text(ErrorMessages.chatFailed), findsNothing);
        expect(_text(tester), '내 질문');
        expect(_enabled(tester), isTrue);
        expect(find.text('답을 만드는 중...'), findsNothing);
        expect(find.text('참고한 글'), findsNothing);
      });
    }

    // 429 문구는 스크린리더에 alert 역할(다른 에러와 같은 Notice)로 알려야 한다
    _test('429: 문구가 alert 역할 영역에 들어 있다', (tester) async {
      final semantics = tester.ensureSemantics();
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(429));
      await _open(tester, adapter);
      await _type(tester, '내 질문');
      await _tapSend(tester);
      SemanticsNode? node = tester.getSemantics(find.text(ErrorMessages.chatRateLimited));
      var found = false;
      while (node != null) {
        if (node.getSemanticsData().role == SemanticsRole.alert) found = true;
        node = node.parent;
      }
      expect(found, isTrue);
      semantics.dispose();
    });

    // 휴대폰 폭(320px)에서 긴 429 문구가 넘치지 않고 여러 줄로 보인다
    testWidgets('429: 320px 좁은 화면에서도 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(320, 1800);
      addTearDown(tester.view.reset);
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(429));
      await _open(tester, adapter);
      await _type(tester, '내 질문');
      await _tapSend(tester);
      expect(tester.takeException(), isNull); // 오버플로는 예외로 잡힌다
      expect(_notice(ErrorMessages.chatRateLimited), findsOneWidget);
      expect(tester.getSize(find.text(ErrorMessages.chatRateLimited)).width, lessThanOrEqualTo(320));
    });
  });

  group('401', () {
    _test('401이면 /auth/refresh 1번 뒤 다시 보내 200이면 답이 보인다 (같은 90초 제한으로)', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/chat', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, _user()))
        ..on('POST', '/chat', FakeReply(200, chatJson(answer: '재시도 답')));
      await _open(tester, adapter);
      await _type(tester, '질문');
      await tester.tap(_send);
      await _waitRefresh(tester);
      expect(adapter.count('POST', '/auth/refresh'), 1);
      expect(adapter.count('POST', '/chat'), 2);
      expect(find.text('재시도 답'), findsOneWidget);
      for (final c in adapter.callsTo('POST', '/chat')) {
        expect(c.receiveTimeout, const Duration(seconds: 90));
      }
    });

    _test('재발급도 401이면 ReloginNotice, 주소 /chat, 질문 남음', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/chat', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter);
      await _type(tester, '남길 질문');
      await tester.tap(_send);
      await _waitRefresh(tester);
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(_uri(app), '/chat');
      expect(_text(tester), '남길 질문');
      expect(_enabled(tester), isTrue);
    });
  });

  group('제한 시간', () {
    _test('POST /chat 만 receiveTimeout 90초, 다른 요청에는 없다', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson()));
      await _open(tester, adapter);
      await _type(tester, '질문');
      await _tapSend(tester);
      final chat = adapter.callsTo('POST', '/chat');
      expect(chat, hasLength(1));
      expect(chat.single.receiveTimeout, const Duration(seconds: 90));
      // 챗봇이 아닌 다른 요청에는 제한 시간이 걸려 있지 않다 (재발급 전용 통로의 10초는 별개)
      for (final c in adapter.calls.where((c) => c.path != '/api/chat' && c.path != '/api/auth/refresh')) {
        expect(c.receiveTimeout, isNull, reason: '${c.method} ${c.path}');
      }
    });
  });
}
