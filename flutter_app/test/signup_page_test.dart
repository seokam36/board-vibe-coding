// S16-d2b 회원가입 화면 테스트: React SignupPage와 같은 동작(검증 없음, 문구 6종, 버튼만 잠금, 성공 시 로그인 화면)을 본다.
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _httpWarning = 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요';
const _nicknameHint = '한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가';

final _usernameField = find.widgetWithText(TextField, '아이디');
final _nicknameField = find.widgetWithText(TextField, '닉네임');
final _passwordField = find.widgetWithText(TextField, '비밀번호');
final _submitButton = find.widgetWithText(FilledButton, '가입');

String _path(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.path;

void main() {
  testWidgets('화면 구성: 제목 · HTTP 경고 · 칸 3개 · 닉네임 힌트 · 가입/목록으로', (tester) async {
    await pumpApp(tester, location: '/signup');
    // 상단바 링크에도 같은 글자가 있어서 카드 안에서만 찾는다
    expect(find.descendant(of: find.byType(AutofillGroup), matching: find.text('회원가입')), findsOneWidget);
    expect(find.text(_httpWarning), findsOneWidget);
    expect(_usernameField, findsOneWidget);
    expect(_nicknameField, findsOneWidget);
    expect(_passwordField, findsOneWidget);
    expect(find.text(_nicknameHint), findsOneWidget);
    expect(_submitButton, findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '목록으로'), findsOneWidget);

    final username = tester.widget<TextField>(_usernameField);
    final nickname = tester.widget<TextField>(_nicknameField);
    final password = tester.widget<TextField>(_passwordField);
    // 자동완성 힌트
    expect(username.autofillHints, [AutofillHints.username]);
    expect(nickname.autofillHints, [AutofillHints.nickname]);
    expect(password.autofillHints, [AutofillHints.newPassword]);
    // 자동 대문자 끔 / 비밀번호만 가려짐 / 닉네임엔 글자 수 제한 없음
    expect(username.textCapitalization, TextCapitalization.none);
    expect(password.obscureText, isTrue);
    expect(username.obscureText, isFalse);
    expect(nickname.maxLength, isNull);
    // 한 묶음(AutofillGroup) 안에 있다
    expect(find.ancestor(of: _usernameField, matching: find.byType(AutofillGroup)), findsOneWidget);
  });

  testWidgets('성공: 칸 3개 값을 JSON 키 3개로 보내고 /login 으로 가서 "가입 완료" 안내를 보여 준다', (tester) async {
    final adapter = FakeAdapter()..on('POST', '/auth/signup', FakeReply(201, {}));
    final app = await pumpApp(tester, location: '/signup', adapter: adapter);
    await tester.enterText(_usernameField, 'user01');
    await tester.enterText(_nicknameField, '철수');
    await tester.enterText(_passwordField, 'pw123456');
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();

    expect(adapter.count('POST', '/auth/signup'), 1);
    expect(_path(app), '/login');
    expect(find.text('가입 완료! 로그인해 주세요'), findsOneWidget);
    expect(find.text(_httpWarning), findsOneWidget); // 로그인 화면의 경고도 같이
  });

  testWidgets('요청 본문은 username·nickname·password 세 키뿐이다', (tester) async {
    Object? sent;
    final adapter = FakeAdapter()..on('POST', '/auth/signup', FakeReply(201, {}));
    adapter.onRequestBody = (body) => sent = body;
    await pumpApp(tester, location: '/signup', adapter: adapter);
    await tester.enterText(_usernameField, 'user01');
    await tester.enterText(_nicknameField, '철수');
    await tester.enterText(_passwordField, 'pw123456');
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();

    expect(sent, {'username': 'user01', 'nickname': '철수', 'password': 'pw123456'});
  });

  testWidgets('빈 값도 클라이언트 검증 없이 서버로 보낸다', (tester) async {
    final adapter = FakeAdapter()..on('POST', '/auth/signup', FakeReply(400));
    await pumpApp(tester, location: '/signup', adapter: adapter);
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();
    expect(adapter.count('POST', '/auth/signup'), 1);
  });

  // 실패 문구 6종: 글자 하나라도 바뀌면 잡힌다
  const badRequest =
      '아이디는 영문 소문자·숫자·_ 4~20자, 닉네임은 한글(완성형)·영문·숫자·_ 2~20자(공백·자음/모음만은 불가), 비밀번호는 8~64자로 입력해 주세요';
  final failures = <(String, FakeReply, String)>[
    ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ('409', FakeReply(409), '아이디 또는 닉네임이 이미 사용 중입니다'),
    ('400', FakeReply(400), badRequest),
    ('403(CSRF)', FakeReply(403), '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요'),
    ('401', FakeReply(401), '가입하지 못했습니다'),
    ('500(그 밖)', FakeReply(500), '가입하지 못했습니다'),
  ];
  for (final (name, reply, message) in failures) {
    testWidgets('실패 $name → "$message", 버튼은 다시 눌러 볼 수 있고 화면·입력값은 그대로', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/auth/signup', reply);
      final app = await pumpApp(tester, location: '/signup', adapter: adapter);
      await tester.enterText(_usernameField, 'u');
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();

      expect(find.text(message), findsOneWidget);
      expect(_path(app), '/signup');
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
      expect(tester.widget<TextField>(_usernameField).controller?.text, 'u');
    });
  }

  testWidgets('요청 중: 버튼이 "가입 중..." 으로 잠기고 입력칸은 안 잠기며, Enter를 또 눌러도 한 번만 보낸다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('POST', '/auth/signup', FakeReply(201, {}, gate));
    await pumpApp(tester, location: '/signup', adapter: adapter);
    await tester.tap(_submitButton);
    await tester.pump(const Duration(milliseconds: 50));

    final saving = find.widgetWithText(FilledButton, '가입 중...');
    expect(saving, findsOneWidget);
    expect(tester.widget<FilledButton>(saving).onPressed, isNull);
    expect(tester.widget<TextField>(_usernameField).enabled, isNot(false));
    await tester.enterText(_passwordField, 'p');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump();
    expect(adapter.count('POST', '/auth/signup'), 1);

    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('가입 완료! 로그인해 주세요'), findsOneWidget);
  });

  testWidgets('다시 시도하면 이전 오류 문구는 지워진다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('POST', '/auth/signup', FakeReply(409))
      ..on('POST', '/auth/signup', FakeReply(500, null, gate));
    await pumpApp(tester, location: '/signup', adapter: adapter);
    await tester.tap(_submitButton);
    await tester.pumpAndSettle();
    expect(find.text('아이디 또는 닉네임이 이미 사용 중입니다'), findsOneWidget);
    await tester.tap(_submitButton);
    await tester.pump();
    expect(find.text('아이디 또는 닉네임이 이미 사용 중입니다'), findsNothing);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('가입하지 못했습니다'), findsOneWidget);
  });

  testWidgets('[목록으로]를 누르면 "/" 로 간다', (tester) async {
    final app = await pumpApp(tester, location: '/signup');
    await tester.tap(find.text('목록으로'));
    await tester.pumpAndSettle();
    expect(_path(app), '/');
    expect(find.text('전체 게시판'), findsOneWidget);
  });
}
