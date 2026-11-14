// S16-d2a 로그인 화면 테스트: React LoginPage와 같은 동작(검증 없음, 문구 5종, 버튼만 잠금, 성공 시 홈)을 본다.
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _httpWarning = 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요';

// 입력칸·버튼 찾기 (라벨 글자로)
final _usernameField = find.widgetWithText(TextField, '아이디');
final _passwordField = find.widgetWithText(TextField, '비밀번호');
final _submitButton = find.widgetWithText(FilledButton, '로그인');

String _path(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.path;

void main() {
  testWidgets('화면 구성: 제목 · HTTP 경고(항상) · 아이디/비밀번호 · 로그인/목록으로 버튼', (tester) async {
    await pumpApp(tester, location: '/login');
    expect(find.text(_httpWarning), findsOneWidget);
    expect(_usernameField, findsOneWidget);
    expect(_passwordField, findsOneWidget);
    expect(_submitButton, findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '목록으로'), findsOneWidget);
    // 비밀번호는 가려진다
    expect(tester.widget<TextField>(_passwordField).obscureText, isTrue);
    expect(tester.widget<TextField>(_usernameField).obscureText, isFalse);
    // 자동완성 힌트와 자동 대문자 끔
    expect(tester.widget<TextField>(_usernameField).autofillHints, [AutofillHints.username]);
    expect(tester.widget<TextField>(_passwordField).autofillHints, [AutofillHints.password]);
    expect(tester.widget<TextField>(_usernameField).textCapitalization, TextCapitalization.none);
    // placeholder(hint)는 없다
    expect(tester.widget<TextField>(_usernameField).decoration?.hintText, isNull);
  });

  testWidgets('이미 로그인해 있어도 폼은 그대로 보인다', (tester) async {
    await pumpApp(tester, location: '/login', user: userJson());
    expect(_usernameField, findsOneWidget);
  });

  testWidgets('성공: 아이디·비밀번호를 보내고 "/"(글 목록)로 가며 로그인 상태가 된다', (tester) async {
    final adapter = FakeAdapter()..on('POST', '/auth/login', FakeReply(200, userJson(nickname: '철수')));
    final app = await pumpApp(tester, location: '/login', adapter: adapter);
    await tester.enterText(_usernameField, 'user01');
    await tester.enterText(_passwordField, 'pw1234');
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();

    expect(adapter.count('POST', '/auth/login'), 1);
    expect(_path(app), '/');
    expect(find.text('글 목록'), findsOneWidget);
    expect(find.text('철수 님'), findsOneWidget);
    expect(app.auth.user?.nickname, '철수');
  });

  testWidgets('빈 값도 클라이언트 검증 없이 서버로 보낸다 (서버 400 문구를 보여 준다)', (tester) async {
    final adapter = FakeAdapter()..on('POST', '/auth/login', FakeReply(400));
    await pumpApp(tester, location: '/login', adapter: adapter);
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();
    expect(adapter.count('POST', '/auth/login'), 1);
    expect(find.text('아이디와 비밀번호를 입력해 주세요'), findsOneWidget);
  });

  // 실패 문구 5종: 글자 하나라도 바뀌면 잡힌다
  final failures = <(String, FakeReply, String)>[
    ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ('401', FakeReply(401), '아이디 또는 비밀번호가 틀렸습니다'),
    ('400', FakeReply(400), '아이디와 비밀번호를 입력해 주세요'),
    ('403(CSRF)', FakeReply(403), '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요'),
    ('500(그 밖)', FakeReply(500), '로그인하지 못했습니다'),
  ];
  for (final (name, reply, message) in failures) {
    testWidgets('실패 $name → "$message", 버튼은 다시 눌러 볼 수 있고 화면은 그대로', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/auth/login', reply);
      final app = await pumpApp(tester, location: '/login', adapter: adapter);
      await tester.enterText(_usernameField, 'u');
      await tester.enterText(_passwordField, 'p');
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();

      expect(find.text(message), findsOneWidget);
      expect(_path(app), '/login');
      expect(app.auth.user, isNull);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
      // 입력한 값은 지워지지 않는다
      expect(tester.widget<TextField>(_usernameField).controller?.text, 'u');
    });
  }

  testWidgets('요청 중: 버튼이 "로그인 중..." 으로 바뀌고 잠기며, 입력칸은 잠기지 않고, Enter를 또 눌러도 한 번만 보낸다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/auth/login', FakeReply(200, userJson(), gate));
    await pumpApp(tester, location: '/login', adapter: adapter);
    await tester.enterText(_usernameField, 'u');
    await tester.enterText(_passwordField, 'p');
    await tester.tap(_submitButton);
    await tester.pump(const Duration(milliseconds: 50)); // 요청이 가짜 서버까지 가도록 잠깐 흘려 보낸다

    final saving = find.widgetWithText(FilledButton, '로그인 중...');
    expect(saving, findsOneWidget);
    expect(tester.widget<FilledButton>(saving).onPressed, isNull);
    expect(tester.widget<TextField>(_usernameField).enabled, isNot(false)); // 입력칸은 그대로
    // 요청 중 Enter 로 또 제출해도 요청은 1번
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    expect(adapter.count('POST', '/auth/login'), 1);

    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('글 목록'), findsOneWidget);
  });

  testWidgets('비밀번호 칸에서 Enter 를 치면 제출된다', (tester) async {
    final adapter = FakeAdapter()..on('POST', '/auth/login', FakeReply(401));
    await pumpApp(tester, location: '/login', adapter: adapter);
    await tester.enterText(_passwordField, 'p');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(adapter.count('POST', '/auth/login'), 1);
    expect(find.text('아이디 또는 비밀번호가 틀렸습니다'), findsOneWidget);
  });

  testWidgets('다시 시도하면 이전 오류 문구는 지워진다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('POST', '/auth/login', FakeReply(401))
      ..on('POST', '/auth/login', FakeReply(500, null, gate));
    await pumpApp(tester, location: '/login', adapter: adapter);
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();
    expect(find.text('아이디 또는 비밀번호가 틀렸습니다'), findsOneWidget);
    await tester.tap(_submitButton);
    await tester.pump();
    expect(find.text('아이디 또는 비밀번호가 틀렸습니다'), findsNothing);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('로그인하지 못했습니다'), findsOneWidget);
  });

  testWidgets('다른 화면이 extra로 넘긴 안내 문구가 있으면 HTTP 경고와 함께 보인다', (tester) async {
    final app = await pumpApp(tester);
    app.router.go('/login', extra: '가입이 완료되었습니다. 로그인해 주세요');
    await tester.pumpAndSettle();
    expect(find.text('가입이 완료되었습니다. 로그인해 주세요'), findsOneWidget);
    expect(find.text(_httpWarning), findsOneWidget);
  });

  testWidgets('안내 문구가 없으면 HTTP 경고만 있다', (tester) async {
    await pumpApp(tester, location: '/login');
    expect(find.text(_httpWarning), findsOneWidget);
    expect(find.text('가입이 완료되었습니다. 로그인해 주세요'), findsNothing);
  });

  testWidgets('[목록으로]를 누르면 "/" 로 간다', (tester) async {
    final app = await pumpApp(tester, location: '/login');
    await tester.tap(find.text('목록으로'));
    await tester.pumpAndSettle();
    expect(_path(app), '/');
    expect(find.text('글 목록'), findsOneWidget);
  });
}
