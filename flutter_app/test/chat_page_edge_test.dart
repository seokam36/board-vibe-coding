// S17-d 챗봇 화면 빈틈 테스트: 보내는 중 화면 이동·로그아웃, 긴 답·좁은 화면 넘침, 이상한 응답, 공백 섞인 질문.
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/chat_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/screen.dart';

Map<String, dynamic> _user() => userJson(school: marketSeoul);
final _field = find.byKey(const ValueKey('chat-field'));
final _send = find.widgetWithText(FilledButton, '보내기');
String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter) =>
    pumpApp(tester, location: '/chat', user: _user(), adapter: adapter);

Future<void> _ask(WidgetTester tester, String q) async {
  await tester.enterText(_field, q);
  await tester.pump();
  await tester.tap(_send);
  await tester.pump(const Duration(milliseconds: 50));
}

void main() {
  _boundaryTests();
  testWidgets('보내는 중 다른 화면으로 가도 늦은 응답이 에러를 내지 않는다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
    final app = await _open(tester, adapter);
    await _ask(tester, '자전거');
    app.router.go('/market'); // 화면을 떠난다
    await tester.pumpAndSettle();
    expect(_uri(app), '/market');
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('#12번 글에서 자전거를 3만 원에 팔고 있어요.'), findsNothing);
  });

  testWidgets('보내는 중 다른 화면으로 가도 늦은 실패 응답이 에러를 내지 않는다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(503, {'message': 'x'}, gate));
    final app = await _open(tester, adapter);
    await _ask(tester, '자전거');
    app.router.go('/market');
    await tester.pumpAndSettle();
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('보내는 중 로그아웃하면 /login 으로 가고 늦은 응답에도 에러가 없다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('POST', '/chat', FakeReply(200, chatJson(), gate))
      ..on('POST', '/auth/logout', FakeReply(200));
    final app = await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    expect(_uri(app), '/login');
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('수천 자·줄바꿈 많은 답과 출처가 375px 좁은 화면에서도 넘치지 않는다', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(375, 1800);
    addTearDown(tester.view.reset);
    final longAnswer = '${'가나다라마바사 ' * 600}\n${'줄\n' * 100}${'x' * 3000}';
    final adapter = FakeAdapter()
      ..on(
        'POST',
        '/chat',
        FakeReply(
          200,
          chatJson(
            answer: longAnswer,
            sources: [chatSourceJson(title: '아주아주 긴 제목 ' * 20), chatSourceJson(id: 5, title: 'y' * 300)],
          ),
        ),
      );
    await _open(tester, adapter);
    await _ask(tester, '긴 질문 ' * 100);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull); // 오버플로는 예외로 잡힌다
    expect(find.byKey(const ValueKey('chat-answer')), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-source-1-5')), findsOneWidget);
  });

  testWidgets('같은 글(id)이 두 번 온 응답에서도 화면이 죽지 않고 두 줄이 보인다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('POST', '/chat', FakeReply(200, chatJson(sources: [chatSourceJson(id: 7), chatSourceJson(id: 7)])));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull); // Duplicate keys 예외가 없어야 한다
    expect(find.byKey(const ValueKey('chat-source-0-7')), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-source-1-7')), findsOneWidget);
  });

  testWidgets('답을 기다리는 중 칸에 새로 쓴 글자는 응답이 와도 지워지지 않는다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.enterText(_field, '다음 질문'); // 기다리는 동안 새 질문을 쓴다
    await tester.pump();
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('chat-answer')), findsOneWidget); // 답은 보인다
    expect(tester.widget<TextField>(_field).controller!.text, '다음 질문');
  });

  testWidgets('기다리는 중 칸을 안 건드렸으면 응답 뒤 칸이 비워진다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('chat-answer')), findsOneWidget);
    expect(tester.widget<TextField>(_field).controller!.text, '');
  });

  testWidgets('answer가 빈 문자열이어도 화면이 죽지 않는다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(answer: '')));
    await _open(tester, adapter);
    await _ask(tester, '질문');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('chat-answer')), findsOneWidget);
  });

  testWidgets('sources에 필드가 빠진 이상한 응답이면 실패 문구가 나오고 화면은 산다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('POST', '/chat', FakeReply(200, {'answer': '답', 'sources': [{'type': 'MARKET_ITEM', 'id': 1}]}));
    await _open(tester, adapter);
    await _ask(tester, '질문');
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('답을 받지 못했습니다'), findsOneWidget);
    expect(tester.widget<FilledButton>(_send).onPressed, isNotNull);
  });

  testWidgets('질문 앞뒤 공백은 그대로 보내고, 질문 말풍선에는 공백 섞인 채 보인다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson()));
    await _open(tester, adapter);
    final bodies = <Object?>[];
    adapter.onRequestBody = (d) {
      if (d != null) bodies.add(d);
    };
    await _ask(tester, '  자전거  ');
    await tester.pumpAndSettle();
    expect(bodies, [
      {'question': '  자전거  '},
    ]);
    // 말풍선 글자는 공백이 그대로 들어 있다 (보고용: 눈에는 앞뒤 공백이 안 보임)
    expect(find.text('  자전거  '), findsOneWidget);
  });
}

// S17-d review 반영 경계 테스트: 기다리는 중 칸을 바꾼 여러 경우
void _boundaryTests() {
  testWidgets('기다리는 중 칸을 지워 빈칸으로 만들면 응답 뒤에도 빈칸이다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.enterText(_field, '');
    await tester.pump();
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byKey(const ValueKey('chat-answer')), findsOneWidget);
    expect(tester.widget<TextField>(_field).controller!.text, '');
  });

  testWidgets('기다리는 중 칸을 바꿨다가 원래 질문과 똑같이 되돌리면 비워진다(의도된 동작)', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(200, chatJson(), gate));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.enterText(_field, '다른 글');
    await tester.pump();
    await tester.enterText(_field, '자전거');
    await tester.pump();
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(_field).controller!.text, '');
  });

  testWidgets('실패 응답이면 새로 쓴 글자도, 안 건드린 질문도 그대로 남는다', (tester) async {
    useTallScreen(tester);
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(503, {'message': 'x'}, gate));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.enterText(_field, '다음 질문');
    await tester.pump();
    gate.complete();
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(_field).controller!.text, '다음 질문');
  });

  testWidgets('실패 응답이면 안 건드린 질문은 그대로 남는다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('POST', '/chat', FakeReply(503, {'message': 'x'}));
    await _open(tester, adapter);
    await _ask(tester, '자전거');
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(_field).controller!.text, '자전거');
  });
}
