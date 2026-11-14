// S16-g1 내 정보 화면 테스트: React MyPage와 같은 동작
// (골격·순서, 학교 인증 자리, 닉네임 변경, 비밀번호 변경, 회원 탈퇴, 요청 중 잠금, 로그인 풀림)을 본다.
import 'dart:async';

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _httpWarning = 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요';
const _nicknameHint = '한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가';
const _csrf = '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요';
const _school = {'id': 1, 'name': '서울대'};
const _withdrawMessage = "탈퇴하면 되돌릴 수 없습니다. 쓴 글은 남고 작성자는 '(작성자 없음)'으로 바뀝니다. 탈퇴할까요?";

final _nicknameField = find.widgetWithText(TextField, '새 닉네임');
final _currentField = find.widgetWithText(TextField, '현재 비밀번호');
final _newField = find.widgetWithText(TextField, '새 비밀번호');
final _withdrawField = find.widgetWithText(TextField, '비밀번호');
final _nicknameButton = find.widgetWithText(FilledButton, '닉네임 변경');
final _passwordButton = find.widgetWithText(FilledButton, '비밀번호 변경');
final _withdrawButton = find.widgetWithText(OutlinedButton, '회원 탈퇴');

String _path(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.path;
String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;

// 화면이 길어서 긴 화면으로 시험한다
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

// 상태코드만 담은 실패 응답으로 요청 한 번 보내고 끝까지 기다린다
Future<void> _settle(WidgetTester tester) async {
  await tester.pumpAndSettle();
}

// 401 → 재발급 401 흐름은 진짜 시계로 잠깐 기다려야 끝난다
Future<void> _settleReal(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
}

// 탈퇴: 버튼 → 확인 창 [탈퇴]
Future<void> _withdraw(WidgetTester tester) async {
  await tester.tap(_withdrawButton);
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(OutlinedButton, '탈퇴'));
}

// 글 목록(탈퇴 성공 뒤 이동하는 곳)이 불러오는 요청들
FakeAdapter _listAdapter() => FakeAdapter()
  ..on('GET', '/schools', FakeReply(200, []))
  ..on('GET', '/categories', FakeReply(200, []))
  ..on('GET', '/posts', FakeReply(200, []));

void main() {
  group('골격', () {
    _test('제목 → 아이디·닉네임 판 → 학교 인증 → 닉네임 → 비밀번호 → 탈퇴 → 목록으로 순서', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson(username: 'user01', nickname: '홍길동'));
      expect(find.text('내 정보'), findsWidgets); // 화면 제목 (상단바 링크에도 같은 글자)
      expect(find.text('아이디: user01'), findsOneWidget);
      expect(find.text('닉네임: 홍길동'), findsOneWidget);

      // 위에서 아래로의 세로 순서
      double y(Finder f) => tester.getTopLeft(f.first).dy;
      final order = [
        y(find.text('아이디: user01')),
        y(find.text('학교 인증')),
        y(find.text('닉네임 변경').first), // 제목 h2 (버튼과 글자가 같아 첫 번째)
        y(find.text('비밀번호 변경').first),
        y(find.text('회원 탈퇴').first),
        y(find.widgetWithText(OutlinedButton, '목록으로')),
      ];
      expect([...order]..sort(), order);
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text('닉네임 변경'), findsNWidgets(2)); // 제목 + 버튼
      expect(find.text('회원 탈퇴'), findsNWidgets(2)); // 제목 + 버튼
    });

    _test('폼 구성: 칸 이름·힌트·경고·자동완성·가려짐', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson());
      expect(find.text(_nicknameHint), findsOneWidget);
      expect(find.text(_httpWarning), findsOneWidget);
      expect(find.text('8~64자'), findsOneWidget);
      expect(tester.widget<TextField>(_nicknameField).autofillHints, [AutofillHints.nickname]);
      expect(tester.widget<TextField>(_nicknameField).obscureText, isFalse);
      expect(tester.widget<TextField>(_nicknameField).maxLength, isNull); // 글자 수 제한·검증 없음
      expect(tester.widget<TextField>(_currentField).obscureText, isTrue);
      expect(tester.widget<TextField>(_currentField).autofillHints, [AutofillHints.password]);
      expect(tester.widget<TextField>(_newField).obscureText, isTrue);
      expect(tester.widget<TextField>(_newField).autofillHints, [AutofillHints.newPassword]);
      expect(tester.widget<TextField>(_withdrawField).obscureText, isTrue);
      expect(tester.widget<TextField>(_withdrawField).autofillHints, [AutofillHints.password]);
    });

    _test('[목록으로]를 누르면 /', (tester) async {
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: _listAdapter());
      await tester.tap(find.widgetWithText(OutlinedButton, '목록으로'));
      await tester.pumpAndSettle();
      expect(_path(app), '/');
    });
  });

  group('학교 인증 자리', () {
    _test('인증한 회원: 뱃지 "학교 인증 완료 · 학교명" + "졸업 후에도 유지돼요", 안내 문구는 없다', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson(school: _school));
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(find.ancestor(of: find.text('학교 인증 완료 · 서울대'), matching: find.byType(AppBadge)), findsOneWidget);
      expect(find.text('졸업 후에도 유지돼요'), findsOneWidget);
      expect(find.text('학교 메일로 인증하면 글쓰기·댓글·공감·신고를 할 수 있어요'), findsNothing);
    });

    _test('미인증 회원: 안내 한 줄만, 뱃지는 없다', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson());
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text('학교 메일로 인증하면 글쓰기·댓글·공감·신고를 할 수 있어요'), findsOneWidget);
      expect(find.textContaining('학교 인증 완료'), findsNothing);
      expect(find.text('졸업 후에도 유지돼요'), findsNothing);
    });
  });

  group('닉네임 변경', () {
    _test('성공: PUT {nickname} → 위쪽 닉네임·상단바 갱신, 칸 비움, 성공 문구', (tester) async {
      Object? body;
      final adapter = FakeAdapter()
        ..on('PUT', '/users/me/nickname', FakeReply(200, userJson(nickname: '철수')));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      final app = await pumpApp(tester, location: '/me', user: userJson(nickname: '홍길동'), adapter: adapter);
      expect(find.text('홍길동 님'), findsOneWidget); // 상단바
      await tester.enterText(_nicknameField, '철수');
      await tester.tap(_nicknameButton);
      await _settle(tester);

      expect(body, {'nickname': '철수'});
      expect(app.auth.user!.nickname, '철수');
      expect(find.text('닉네임: 철수'), findsOneWidget);
      expect(find.text('철수 님'), findsOneWidget); // 상단바도 바뀜
      expect(find.text('홍길동 님'), findsNothing);
      expect(_text(tester, _nicknameField), '');
      expect(find.text('닉네임이 바뀌었습니다'), findsOneWidget);
      expect(tester.widgetList<Notice>(find.byType(Notice)).any((n) => n.kind == NoticeKind.success), isTrue);
      expect(_path(app), '/me');
    });

    // 상태코드별 실패 문구: 입력은 그대로, 버튼은 되살아난다
    final failures = <(String, FakeReply, String)>[
      ('409', FakeReply(409), '이미 사용 중인 닉네임입니다'),
      ('400', FakeReply(400), '닉네임은 한글(완성형)·영문·숫자·_ 2~20자로 입력해 주세요 (공백·자음/모음만은 불가)'),
      ('403', FakeReply(403), _csrf),
      ('500', FakeReply(500), '닉네임을 바꾸지 못했습니다'),
      ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (label, reply, message) in failures) {
      _test('실패 $label: "$message", 입력 유지·버튼 되살아남', (tester) async {
        final adapter = FakeAdapter()..on('PUT', '/users/me/nickname', reply);
        await pumpApp(tester, location: '/me', user: userJson(nickname: '홍길동'), adapter: adapter);
        await tester.enterText(_nicknameField, '철수');
        await tester.tap(_nicknameButton);
        await _settle(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text('닉네임이 바뀌었습니다'), findsNothing);
        expect(_text(tester, _nicknameField), '철수');
        expect(tester.widget<FilledButton>(_nicknameButton).onPressed, isNotNull);
        expect(find.text('닉네임: 홍길동'), findsOneWidget); // 안 바뀜
      });
    }

    _test('실패 401(재발급도 401): 로그인 안내가 뜨고 입력·화면 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', '/users/me/nickname', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_nicknameField, '철수');
      await tester.tap(_nicknameButton);
      await _settleReal(tester);
      expect(app.auth.user, isNull);
      expect(_path(app), '/me');
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('닉네임을 바꾸지 못했습니다'), findsNothing);
      expect(_text(tester, _nicknameField), '철수');
    });

    _test('성공 뒤 다시 실패하면 성공 문구는 사라진다', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', '/users/me/nickname', FakeReply(200, userJson(nickname: '철수')))
        ..on('PUT', '/users/me/nickname', FakeReply(409));
      await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_nicknameField, '철수');
      await tester.tap(_nicknameButton);
      await _settle(tester);
      expect(find.text('닉네임이 바뀌었습니다'), findsOneWidget);
      await tester.enterText(_nicknameField, '영희');
      await tester.tap(_nicknameButton);
      await _settle(tester);
      expect(find.text('닉네임이 바뀌었습니다'), findsNothing);
      expect(find.text('이미 사용 중인 닉네임입니다'), findsOneWidget);
    });
  });

  group('비밀번호 변경', () {
    _test('성공: PUT 본문 {currentPassword,newPassword} → /login + 안내 문구, 상태 비움, logout 요청 없음', (tester) async {
      Object? body;
      final adapter = FakeAdapter()..on('PUT', '/users/me/password', FakeReply(204));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_currentField, 'oldpw1234');
      await tester.enterText(_newField, 'newpw5678');
      await tester.tap(_passwordButton);
      await _settle(tester);

      expect(body, {'currentPassword': 'oldpw1234', 'newPassword': 'newpw5678'});
      expect(_path(app), '/login');
      expect(find.text('비밀번호가 바뀌었습니다. 다시 로그인해 주세요'), findsOneWidget); // 이동 먼저 → 안내가 덮어써지지 않는다
      expect(app.auth.user, isNull);
      expect(adapter.count('POST', '/auth/logout'), 0);
    });

    final failures = <(String, FakeReply, String)>[
      ('400', FakeReply(400), '현재 비밀번호가 맞는지, 새 비밀번호가 8~64자이고 현재 비밀번호와 다른지 확인해 주세요'),
      ('403', FakeReply(403), _csrf),
      ('500', FakeReply(500), '비밀번호가 이미 바뀌었을 수 있습니다. 새 비밀번호로 다시 로그인해 보세요'),
      ('404(그 밖)', FakeReply(404), '비밀번호를 바꾸지 못했습니다'),
      ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (label, reply, message) in failures) {
      _test('실패 $label: "$message", 입력 유지·잠금 해제·로그인 유지', (tester) async {
        final adapter = FakeAdapter()..on('PUT', '/users/me/password', reply);
        final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
        await tester.enterText(_currentField, 'oldpw1234');
        await tester.enterText(_newField, 'newpw5678');
        await tester.tap(_passwordButton);
        await _settle(tester);
        expect(find.text(message), findsOneWidget);
        expect(_text(tester, _currentField), 'oldpw1234');
        expect(_text(tester, _newField), 'newpw5678');
        expect(tester.widget<FilledButton>(_passwordButton).onPressed, isNotNull);
        expect(app.auth.user, isNotNull);
        expect(_path(app), '/me');
      });
    }

    _test('실패 401(재발급도 401): 로그인 안내가 뜨고 입력·화면 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', '/users/me/password', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_currentField, 'oldpw1234');
      await tester.enterText(_newField, 'newpw5678');
      await tester.tap(_passwordButton);
      await _settleReal(tester);
      expect(_path(app), '/me');
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('비밀번호를 바꾸지 못했습니다'), findsNothing);
      expect(_text(tester, _currentField), 'oldpw1234');
    });

    _test('요청 중에는 버튼이 "변경 중..."으로 잠기고 Enter를 또 눌러도 요청은 한 번', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('PUT', '/users/me/password', FakeReply(204, null, gate));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_currentField, 'oldpw1234');
      await tester.enterText(_newField, 'newpw5678');
      await tester.tap(_passwordButton);
      await tester.pump(const Duration(milliseconds: 50));
      final locked = find.widgetWithText(FilledButton, '변경 중...');
      expect(locked, findsOneWidget);
      expect(tester.widget<FilledButton>(locked).onPressed, isNull);
      await tester.tap(_newField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('PUT', '/users/me/password'), 1);

      gate.complete();
      await tester.pumpAndSettle();
      expect(_path(app), '/login');
      expect(adapter.count('PUT', '/users/me/password'), 1);
    });
  });

  group('회원 탈퇴', () {
    _test('칸 "비밀번호"와 빨간 제목, 버튼 [회원 탈퇴]', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson());
      expect(_withdrawField, findsOneWidget);
      expect(_withdrawButton, findsOneWidget);
      // 제목(h2)은 danger 색 (React의 .panel-danger h2). 버튼과 글자가 같아 Text 위젯 중 제목 크기(17)인 것을 찾는다
      final heading = tester.widgetList<Text>(find.text('회원 탈퇴')).firstWhere((t) => t.style?.fontSize == AppSizes.fsRow);
      expect(heading.style?.color, AppColors.danger);
    });

    _test('확인 창 문구가 뜨고, 취소하면 요청을 보내지 않는다', (tester) async {
      final adapter = FakeAdapter();
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_withdrawField, 'pw123456');
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      expect(find.text(_withdrawMessage), findsOneWidget);
      await tester.tap(find.widgetWithText(OutlinedButton, '취소'));
      await tester.pumpAndSettle();
      expect(find.text(_withdrawMessage), findsNothing);
      expect(adapter.calls.where((c) => c.method == 'DELETE'), isEmpty);
      expect(app.auth.user, isNotNull);
      expect(_path(app), '/me');
      expect(_text(tester, _withdrawField), 'pw123456');
      expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNotNull); // 잠기지 않았다
    });

    _test('성공: DELETE 본문 {password} → / + "탈퇴가 완료되었습니다", 상태 비움, logout 요청 없음', (tester) async {
      Object? body;
      final adapter = _listAdapter()..on('DELETE', '/users/me', FakeReply(204));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_withdrawField, 'pw123456');
      await _withdraw(tester);
      await _settle(tester);

      expect(body, {'password': 'pw123456'});
      expect(_path(app), '/');
      expect(find.text('탈퇴가 완료되었습니다'), findsOneWidget);
      expect(app.auth.user, isNull);
      expect(adapter.count('POST', '/auth/logout'), 0);
    });

    final failures = <(String, FakeReply, String)>[
      ('400', FakeReply(400), '비밀번호를 입력했는지, 맞는지 확인해 주세요'),
      ('403', FakeReply(403), _csrf),
      ('500', FakeReply(500), '탈퇴가 이미 처리됐을 수 있습니다. 새로고침해서 로그인 상태를 확인해 주세요'),
      ('404(그 밖)', FakeReply(404), '탈퇴하지 못했습니다'),
      ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (label, reply, message) in failures) {
      _test('실패 $label: "$message", 입력 유지·잠금 해제·로그인 유지', (tester) async {
        final adapter = FakeAdapter()..on('DELETE', '/users/me', reply);
        final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
        await tester.enterText(_withdrawField, 'pw123456');
        await _withdraw(tester);
        await _settle(tester);
        expect(find.text(message), findsOneWidget);
        expect(_text(tester, _withdrawField), 'pw123456');
        expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNotNull);
        expect(app.auth.user, isNotNull); // 500에서도 로그인 상태를 비우지 않는다
        expect(_path(app), '/me');
      });
    }

    _test('실패 401(재발급도 401): 로그인 안내가 뜨고 입력·화면 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('DELETE', '/users/me', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_withdrawField, 'pw123456');
      await _withdraw(tester);
      await _settleReal(tester);
      expect(_path(app), '/me');
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('탈퇴하지 못했습니다'), findsNothing);
      expect(_text(tester, _withdrawField), 'pw123456');
    });

    _test('요청 중에는 버튼이 "처리 중..."으로 잠기고 요청은 한 번', (tester) async {
      final gate = Completer<void>();
      final adapter = _listAdapter()..on('DELETE', '/users/me', FakeReply(204, null, gate));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      await tester.enterText(_withdrawField, 'pw123456');
      await _withdraw(tester);
      await tester.pump(const Duration(milliseconds: 50));
      final locked = find.widgetWithText(OutlinedButton, '처리 중...');
      expect(locked, findsOneWidget);
      expect(tester.widget<OutlinedButton>(locked).onPressed, isNull);
      expect(adapter.count('DELETE', '/users/me'), 1);

      gate.complete();
      await tester.pumpAndSettle();
      expect(_path(app), '/');
      expect(adapter.count('DELETE', '/users/me'), 1);
    });
  });

  testWidgets('문지기: 비로그인으로 /me 에 오면 /login', (tester) async {
    final app = await pumpApp(tester, location: '/me');
    expect(_path(app), '/login');
  });
}
