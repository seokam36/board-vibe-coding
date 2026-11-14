// 화면·라우터 테스트용: 시작 확인(loading)이 이미 끝난 가짜 AuthState + 가짜 서버(어댑터) 묶음.
// 서버 호출은 시작 때 일어나지 않는다(init()을 안 부른다). 가짜 어댑터를 같이 들고 있어서,
// 화면이 ApiClient를 read 해도 터지지 않고 어떤 요청이 갔는지(adapter.calls)도 볼 수 있다.
import 'package:board_web/api/api_client.dart';
import 'package:board_web/app.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/router.dart' show normalizePath;
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart' show Scaffold;

import 'fake_adapter.dart';

class LoadedAuthState extends AuthState {
  LoadedAuthState._(this.adapter, ApiClient api, String Function() currentPath, AuthUser? user)
    : super(api: api, currentPath: currentPath) {
    if (user != null) updateUser(user); // 로그인한 상태로 시작
  }

  // user가 null이면 비로그인. currentPath는 로그인이 풀린 화면 경로 기록용(테스트가 정한다)
  factory LoadedAuthState({
    FakeAdapter? adapter,
    Map<String, dynamic>? user,
    String Function()? currentPath,
  }) {
    final fake = adapter ?? FakeAdapter();
    return LoadedAuthState._(
      fake,
      ApiClient(adapter: fake, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      currentPath ?? () => '/',
      user == null ? null : AuthUser.fromJson(user),
    );
  }

  final FakeAdapter adapter;

  @override
  bool get loading => false;
}

// 앱을 띄운 결과: 만든 로그인 상태·가짜 서버, 그리고 주소를 바꿔 볼 라우터
class TestApp {
  TestApp(this.auth, this.router);
  final LoadedAuthState auth;
  final GoRouter router;
  FakeAdapter get adapter => auth.adapter;
}

// 시작 주소·로그인 사용자를 정해 앱을 띄운다. user가 null이면 비로그인.
// 끝나면 로그인 상태는 자동 정리된다(라우터는 앱이 정리).
Future<TestApp> pumpApp(
  WidgetTester tester, {
  String location = '/',
  Map<String, dynamic>? user,
  FakeAdapter? adapter,
}) async {
  final auth = LoadedAuthState(
    adapter: adapter,
    user: user,
    // 로그인이 풀린 화면 경로 기록용: 실제 앱(BoardApp)과 같이 라우터가 확정한 주소를 소문자로 읽는다
    currentPath: () => _currentPath(tester),
  );
  addTearDown(auth.dispose);
  await tester.pumpWidget(BoardApp(authState: auth, initialLocation: location));
  await tester.pumpAndSettle();
  return TestApp(auth, routerOf(tester));
}

String _currentPath(WidgetTester tester) =>
    normalizePath(routerOf(tester).routerDelegate.currentConfiguration.uri.path);

// 화면 안(상단바)에서 현재 라우터를 꺼낸다
GoRouter routerOf(WidgetTester tester) =>
    GoRouter.of(tester.element(find.byType(Scaffold).first));
