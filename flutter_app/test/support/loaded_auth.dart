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
  // 미인증 회원이 내 정보(/me)를 열면 "유학생 인증" 칸이 상태 GET을 1번 보낸다 (S20-g1).
  // (시작 주소가 /me일 때만. /messages 같은 다른 주소는 해당 없음)
  // 그 응답을 테스트가 따로 준비하지 않았으면 "신청한 적 없음(NONE)"을 기본으로 깔아 준다
  adapter ??= FakeAdapter();
  const permitPath = '/users/me/permit-verification';
  if (user != null &&
      user['verifiedSchool'] == null &&
      _isMePath(location) &&
      !adapter.hasReply('GET', permitPath)) {
    adapter.on('GET', permitPath, FakeReply(200, {'status': 'NONE'}));
  }
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

// 시작 주소가 내 정보(/me)인지 (대소문자 무시, 뒤에 ?나 /가 붙는 것까지). /messages는 아니다
bool _isMePath(String location) {
  final l = location.toLowerCase();
  return l == '/me' || l.startsWith('/me?') || l.startsWith('/me/');
}

String _currentPath(WidgetTester tester) =>
    normalizePath(routerOf(tester).routerDelegate.currentConfiguration.uri.path);

// 화면 안(상단바)에서 현재 라우터를 꺼낸다
GoRouter routerOf(WidgetTester tester) =>
    GoRouter.of(tester.element(find.byType(Scaffold).first));
