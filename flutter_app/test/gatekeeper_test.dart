// S16-d2a 문지기 테스트: 보호 주소 10개, 로그인 상태 통과, 로그인 풀림(expiredPath) 예외, 대소문자를 본다.
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

String _path(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.path;

// 로그인한 화면에서 재발급까지 실패(401·401)하는 요청을 한 번 보내 "로그인 풀림"을 일으킨다
// (위젯 테스트는 가짜 시계 안에서 돌아 서버 요청이 저절로 안 흘러가므로 runAsync로 진짜 시계에서 기다린다)
Future<void> _expire(WidgetTester tester, TestApp app) async {
  app.adapter.on('GET', '/boom', FakeReply(401));
  app.adapter.on('POST', '/auth/refresh', FakeReply(401));
  await tester.runAsync(
    () => app.auth.api.dio.get<dynamic>('/boom').then((_) {}, onError: (_) {}),
  );
}

void main() {
  // 보호 주소 10개 (id 자리는 아무 값)
  const protectedPaths = [
    '/posts/new',
    '/posts/7/edit',
    '/me',
    '/visa-reviews/new',
    '/visa-reviews/7/edit',
    '/visa-plan',
    '/market/new',
    '/market/7/edit',
    '/messages',
    '/messages/7',
  ];

  for (final path in protectedPaths) {
    testWidgets('비로그인으로 $path 에 가면 /login 화면이 된다', (tester) async {
      final app = await pumpApp(tester, location: path);
      expect(_path(app), '/login');
      expect(find.text('아이디'), findsOneWidget);
    });

    testWidgets('로그인 상태면 $path 는 그대로 통과한다', (tester) async {
      final app = await pumpApp(tester, location: path, user: userJson());
      expect(_path(app), path);
      expect(find.text('아이디'), findsNothing);
    });
  }

  // 공개 화면은 비로그인이어도 막지 않는다 (신고 관리는 화면 안에서 관리자 여부를 확인한다)
  for (final path in ['/', '/signup', '/login', '/posts/7', '/visa-reviews', '/visa-reviews/stats', '/visa-reviews/7', '/market', '/market/7', '/admin/reports']) {
    testWidgets('공개 주소 $path 는 비로그인으로도 열린다', (tester) async {
      final app = await pumpApp(tester, location: path);
      expect(_path(app), path);
    });
  }

  // 라우트가 대소문자를 가리지 않으므로 문지기도 대소문자로 우회되면 안 된다
  for (final path in ['/ME', '/Posts/New', '/POSTS/5/EDIT', '/Messages/9', '/Visa-Plan']) {
    testWidgets('대소문자가 달라도($path) 비로그인이면 /login', (tester) async {
      final app = await pumpApp(tester, location: path);
      expect(_path(app), '/login');
    });
  }

  testWidgets('끝에 / 가 붙은 보호 주소(/me/)도 비로그인이면 /login', (tester) async {
    final app = await pumpApp(tester, location: '/me/');
    expect(_path(app), '/login');
  });

  testWidgets('/login 으로 보낼 때 기록을 교체한다: 뒤로 가도 보호 주소로 돌아오지 않는다', (tester) async {
    final app = await pumpApp(tester, location: '/me');
    expect(_path(app), '/login');
    // 쌓인 화면이 /login 하나뿐이다 (보호 주소가 밑에 깔려 있지 않다)
    expect(app.router.canPop(), isFalse);
  });

  group('로그인이 풀린 화면(expiredPath) 예외', () {
    testWidgets('풀린 그 화면(/me)에는 머무르고, 다른 보호 주소로 가면 /login', (tester) async {
      final app = await pumpApp(tester, location: '/me', user: userJson());
      await _expire(tester, app);
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull);
      expect(app.auth.expiredPath, '/me');
      expect(_path(app), '/me'); // 입력 중인 내용이 사라지지 않게 그대로
      expect(find.text('내 정보 화면'), findsOneWidget);

      app.router.go('/posts/new'); // 다른 보호 주소
      await tester.pumpAndSettle();
      expect(_path(app), '/login');
      expect(app.auth.expiredPath, isNull); // 화면을 떠나서 표시는 지워졌다
    });

    testWidgets('풀린 화면을 떠났다가(공개 화면) 같은 주소로 돌아오면 /login (문이 영원히 열려 있지 않다)', (tester) async {
      final app = await pumpApp(tester, location: '/me', user: userJson());
      await _expire(tester, app);
      await tester.pumpAndSettle();
      expect(_path(app), '/me');

      app.router.go('/'); // 공개 화면으로 이동 → 표시가 지워진다
      await tester.pumpAndSettle();
      expect(app.auth.expiredPath, isNull);
      app.router.go('/me');
      await tester.pumpAndSettle();
      expect(_path(app), '/login');
    });

    testWidgets('대소문자가 다른 주소(/ME)에서 풀려도 같은 화면으로 보고 머무른다', (tester) async {
      final app = await pumpApp(tester, location: '/ME', user: userJson());
      await _expire(tester, app);
      await tester.pumpAndSettle();
      expect(app.auth.expiredPath, '/me'); // 기록도 같은 정리(소문자)로
      expect(_path(app), '/ME');
      expect(find.text('내 정보 화면'), findsOneWidget);
    });

    testWidgets('다시 로그인하면 풀림 표시가 지워진다', (tester) async {
      final app = await pumpApp(tester, location: '/me', user: userJson());
      await _expire(tester, app);
      await tester.pumpAndSettle();
      app.adapter.on('POST', '/auth/login', FakeReply(200, userJson()));
      await tester.runAsync(() => app.auth.login('user01', 'pw'));
      await tester.pumpAndSettle();
      expect(app.auth.expiredPath, isNull);
      expect(app.auth.user, isNotNull);
      expect(_path(app), '/me');
    });
  });

  testWidgets('로그인 상태가 비워지면(로그아웃·클리어) 보호 화면에서 바로 /login 으로 간다', (tester) async {
    final app = await pumpApp(tester, location: '/messages', user: userJson());
    expect(_path(app), '/messages');
    app.auth.clearUser(); // 비밀번호 변경·탈퇴 성공 때처럼 상태만 비움
    await tester.pumpAndSettle();
    expect(_path(app), '/login');
  });
}
