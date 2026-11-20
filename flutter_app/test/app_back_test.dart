// 앱 뒤로 가기(S23-e): 앱 모드를 켜고 기기의 뒤로 가기(handlePopRoute)를 흉내 내 방문 기록대로 돌아가는지 본다.
import 'package:board_web/app.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/common/app_history.dart';
import 'package:board_web/router.dart' show normalizePath;
import 'package:board_web/widgets/confirm_dialog.dart';
import 'package:flutter/foundation.dart' show TargetPlatform, debugDefaultTargetPlatformOverride;
import 'package:flutter/widgets.dart' show AppLifecycleState;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart' show PopScope, Scaffold;
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

// 앱 모드를 켠 채 앱을 띄운다. "시스템에 넘어간 횟수"(앱이 가로채지 않고 기기가 처리한 뒤로 가기)도 센다.
class _Back {
  _Back(this.app, this.systemPops, this.handlesBack);
  final TestApp app;
  final List<int> systemPops; // 길이 = SystemNavigator.pop 호출 횟수
  final List<bool> handlesBack; // Flutter가 기기에 알린 "뒤로 가기를 내가 받겠다" 신호들 (순서대로)
}

Future<_Back> _pump(WidgetTester tester, {String location = '/', Map<String, dynamic>? user}) async {
  final systemPops = <int>[];
  final handlesBack = <bool>[];
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
    if (call.method == 'SystemNavigator.pop') systemPops.add(1);
    if (call.method == 'SystemNavigator.setFrameworkHandlesBack') handlesBack.add(call.arguments as bool);
    return null;
  });
  addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
  // 로그인이 풀린 화면 경로는 실제 앱처럼 라우터가 확정한 주소를 읽는다 (세션 만료 흉내에 필요)
  GoRouter? router;
  final auth = LoadedAuthState(
    user: user,
    currentPath: () => normalizePath(router!.routerDelegate.currentConfiguration.uri.path),
  );
  addTearDown(auth.dispose);
  await tester.pumpWidget(BoardApp(authState: auth, initialLocation: location, enableAppBack: true));
  await tester.pumpAndSettle();
  router = routerOf(tester);
  return _Back(TestApp(auth, router), systemPops, handlesBack);
}

// 실제 만료 경로(재발급 401 → ApiClient.onExpired → AuthState._handleExpired)를 그대로 부른다
Future<void> _expire(WidgetTester tester, _Back b) async {
  b.app.auth.api.onExpired!();
  await tester.pumpAndSettle();
}

String _where(_Back b) => b.app.router.routerDelegate.currentConfiguration.uri.toString();

Future<void> _back(WidgetTester tester) async {
  await tester.binding.handlePopRoute();
  await tester.pumpAndSettle();
}

Future<void> _go(WidgetTester tester, _Back b, String location) async {
  b.app.router.go(location);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('/ → /market?tradeType=SHARE → /market/3 뒤 뒤로 1번이면 쿼리까지 돌아오고, 1번 더면 /', (tester) async {
    final b = await _pump(tester);
    await _go(tester, b, '/market?tradeType=SHARE');
    await _go(tester, b, '/market/3');

    await _back(tester);
    expect(_where(b), '/market?tradeType=SHARE');
    await _back(tester);
    expect(_where(b), '/');
    expect(b.systemPops, isEmpty);
  });

  testWidgets('시작 화면에서 바로 뒤로 가기: 앱이 가로채지 않고 시스템(앱 닫기)으로 넘어간다', (tester) async {
    final b = await _pump(tester);
    await _back(tester);
    expect(b.systemPops.length, 1);
    expect(_where(b), '/');
  });

  testWidgets('/ → /market → 다시 /market 뒤 뒤로 1번이면 /', (tester) async {
    final b = await _pump(tester);
    await _go(tester, b, '/market');
    await _go(tester, b, '/market');
    await _back(tester);
    expect(_where(b), '/');
    expect(b.systemPops, isEmpty);
  });

  testWidgets('확인 창이 열린 채 뒤로 가기: 창만 닫히고 주소는 그대로, 확인 뒤 요청은 0번', (tester) async {
    final b = await _pump(tester);
    await _go(tester, b, '/market');
    final context = tester.element(find.byType(Scaffold).first);
    final answer = showConfirm(context, message: '정말 삭제할까요?', confirmLabel: '삭제', danger: true);
    await tester.pumpAndSettle();
    expect(find.text('정말 삭제할까요?'), findsOneWidget);

    await _back(tester);
    expect(find.text('정말 삭제할까요?'), findsNothing);
    expect(await answer, isFalse); // 취소로 끝났다
    expect(_where(b), '/market'); // 화면은 그대로
    expect(b.systemPops, isEmpty);
    expect(b.app.adapter.calls.where((c) => c.method != 'GET'), isEmpty);
  });

  testWidgets('로그아웃해 /login 이 된 뒤 뒤로 가기는 /me 로 안 가고, 반복해도 무한 반복 없이 앱 밖으로 넘어간다', (tester) async {
    final b = await _pump(tester, user: userJson());
    await _go(tester, b, '/me');
    expect(_where(b), '/me');

    b.app.auth.clearUser(); // 로그아웃: 문지기가 /login 으로 보낸다
    await tester.pumpAndSettle();
    expect(_where(b), '/login');

    for (var i = 0; i < 3; i++) {
      await _back(tester);
      expect(_where(b), '/login'); // 보호 화면(/me)으로 돌아가지 않는다
    }
    expect(b.systemPops.length, 3); // 앱이 안 가로채고 시스템으로 넘어갔다
  });

  testWidgets('앱 모드가 꺼져 있으면(기본·웹) 기록도 가로채기도 없다', (tester) async {
    final systemPops = <int>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'SystemNavigator.pop') systemPops.add(1);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final app = await pumpApp(tester); // 앱 모드를 안 켠 기본 앱
    app.router.go('/market');
    await tester.pumpAndSettle();
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(systemPops.length, 1); // 기록이 없으니 그대로 시스템 몫
    expect(GoRouter.of(tester.element(find.byType(Scaffold).first)).routerDelegate.currentConfiguration.uri.path, '/market');
    // 기록이 비어 있고, 뒤로 가기를 받는 PopScope도 아예 없다
    final history = Provider.of<AppHistory>(tester.element(find.byType(Scaffold).first), listen: false);
    expect(history.entries, isEmpty);
    expect(find.byType(PopScope<Object?>), findsNothing);
  });

  testWidgets('세션 만료 흐름 A: 만료된 보호 화면에서 로그인으로 간 뒤 뒤로 가기는 헛돌지 않고 시스템으로 넘어간다', (tester) async {
    final b = await _pump(tester, user: userJson());
    await _go(tester, b, '/posts/new');
    await _expire(tester, b); // 로그인은 풀렸지만 화면은 그대로 (입력 내용 보호)
    expect(_where(b), '/posts/new');

    await _go(tester, b, '/login'); // 상단바 로그인
    expect(_where(b), '/login');
    await _back(tester);
    expect(b.systemPops.length, 1); // 앱이 안 가로챔: /posts/new 로 갔다 문지기에 튕기는 헛돎이 없다
    expect(_where(b), '/login');
  });

  testWidgets('세션 만료 흐름 B: 만료 화면에서 /market 으로 간 뒤 뒤로 가기는 /login 으로 튕기지 않고 시스템으로 넘어간다', (tester) async {
    final b = await _pump(tester, user: userJson());
    await _go(tester, b, '/posts/new');
    await _expire(tester, b);

    await _go(tester, b, '/market');
    await _back(tester);
    expect(b.systemPops.length, 1); // 기록상 앞 주소(만료된 보호 화면)가 빠졌으니 시스템 몫
    expect(_where(b), '/market');
  });

  testWidgets('로그인 상태에서는 보호 화면 기록을 지우지 않아 /posts/new → /me → /market 을 뒤로 가기로 거슬러 간다', (tester) async {
    final b = await _pump(tester, user: userJson());
    await _go(tester, b, '/posts/new');
    await _go(tester, b, '/me');
    await _go(tester, b, '/market');
    await _back(tester);
    expect(_where(b), '/me');
    await _back(tester);
    expect(_where(b), '/posts/new');
    expect(b.systemPops, isEmpty);
  });

  testWidgets('비로그인이어도 공개 주소(/market 등) 기록은 지우지 않는다', (tester) async {
    final b = await _pump(tester);
    await _go(tester, b, '/market');
    await _go(tester, b, '/login');
    await _go(tester, b, '/market/3');
    await _back(tester);
    expect(_where(b), '/login');
    await _back(tester);
    expect(_where(b), '/market');
    await _back(tester);
    expect(_where(b), '/');
    expect(b.systemPops, isEmpty);
  });

  testWidgets('뒤로 가기 신호: 기록 2칸 이상이면 true, 1칸이면 false, 주소만 바뀐 이동 뒤에도 true', (tester) async {
    // 이 신호는 안드로이드에서만 기기로 나간다 → 안드로이드인 척한다 (끝나면 원래대로 돌려놔야 테스트 끝 검사를 통과한다)
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    try {
      final b = await _pump(tester);
      // 테스트에선 앱 상태가 "미정"이라 신호를 안 보낸다 → 실제 폰처럼 "켜진 상태"로 만든다
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();

      await _go(tester, b, '/market');
      expect(b.handlesBack.last, isTrue); // 2칸: 받겠다
      await _go(tester, b, '/market/3'); // 3칸 (canPop은 그대로 false)
      expect(b.handlesBack.last, isTrue);

      await _back(tester);
      expect(_where(b), '/market');
      expect(b.handlesBack.last, isTrue); // 아직 2칸
      await _back(tester);
      expect(_where(b), '/');
      expect(b.handlesBack.last, isFalse); // 1칸으로 돌아오면 안 받는다
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('로그인 직후 뒤로 가기는 로그인 화면으로 안 돌아가고 바로 시스템으로 넘어간다', (tester) async {
    final b = await _pump(tester); // 비로그인으로 시작
    await _go(tester, b, '/login');
    b.app.auth.updateUser(AuthUser.fromJson(userJson())); // 로그인 성공 흉내
    b.app.router.go('/'); // 로그인 화면이 바로 이어서 하는 이동(홈으로, 같은 턴)
    await tester.pumpAndSettle();
    final here = _where(b);
    expect(here, isNot('/login'));
    await _back(tester);
    expect(b.systemPops.length, 1);
    expect(_where(b), here);
  });

  testWidgets('비로그인으로 보호 주소를 열어 /login 으로 바뀐 뒤 뒤로 가기를 반복해도 무한 반복 없이 시스템으로 넘어간다', (tester) async {
    final b = await _pump(tester, location: '/me');
    expect(_where(b), '/login');
    for (var i = 0; i < 3; i++) {
      await _back(tester);
      expect(_where(b), '/login');
    }
    expect(b.systemPops.length, 3);
  });

  testWidgets('없는 주소(404 화면)에서도 뒤로 가기로 앞 화면에 돌아간다', (tester) async {
    final b = await _pump(tester);
    await _go(tester, b, '/market');
    await _go(tester, b, '/없는/주소/abc');
    await _back(tester);
    expect(_where(b), '/market');
    expect(b.systemPops, isEmpty);
  });
}
