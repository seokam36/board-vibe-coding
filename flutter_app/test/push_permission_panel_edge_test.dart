// S24-e 보강 검사: 알림 칸의 가장자리 경우 (꺼짐 복귀, 연타, 준비 실패·진행 중, 화면 떠난 뒤 결과 도착, 비로그인).
// 진짜 Firebase 대신 가짜 AppPush를, 진짜 서버 대신 FakeAdapter를 끼운다. 토큰은 지어낸 글자만 쓴다.
import 'dart:async';

import 'package:board_web/app.dart';
import 'package:board_web/push/app_push.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';
import 'support/visa_plan_json.dart';

const _tokensPath = '/users/me/device-tokens';
const _planPath = '/users/me/visa-plan';

// 가짜 푸시 도구: init·권한 읽기·권한 요청을 Completer로 붙잡아 둘 수 있다
class _GatedPush implements AppPush {
  _GatedPush(this.current, {this.afterRequest, this.initGate, this.initResult = true, this.initThrows = false});

  PushPermission current;
  PushPermission? afterRequest;
  Completer<void>? initGate;
  Completer<void>? permissionGate; // 있으면 permission()이 이게 끝날 때까지 기다린다
  Completer<void>? requestGate; // 있으면 requestPermission()이 이게 끝날 때까지 기다린다
  final bool initResult;
  final bool initThrows;
  int requestCalls = 0;

  @override
  Future<bool> init() async {
    if (initGate != null) await initGate!.future;
    if (initThrows) throw StateError('init 실패');
    return initResult;
  }

  @override
  Future<PushPermission> permission() async {
    if (permissionGate != null) await permissionGate!.future;
    return current;
  }

  @override
  Future<PushPermission> requestPermission() async {
    requestCalls++;
    if (requestGate != null) await requestGate!.future;
    current = afterRequest ?? current;
    return current;
  }

  @override
  Future<String?> getToken() async => 'fake-token-1';

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();
}

final _button = find.widgetWithText(FilledButton, '알림 켜기');
const _panelTitle = '휴대폰 알림';
const _onText = '알림이 켜져 있어요';
const _introText = '비자 만료 120·30·7일 전에 알림을 보내 드려요';

void main() {
  Future<FakeAdapter> pump(WidgetTester tester, {required AppPush push, Map<String, dynamic>? user, bool settle = true}) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', _planPath, FakeReply(200, emptyPlanJson))
      ..on('PUT', _tokensPath, FakeReply(204));
    final auth = LoadedAuthState(adapter: adapter, user: user);
    addTearDown(auth.dispose);
    await tester.pumpWidget(BoardApp(
      authState: auth,
      initialLocation: '/visa-plan',
      now: () => DateTime.utc(2026, 11, 10, 3),
      push: push,
    ));
    if (settle) await tester.pumpAndSettle();
    return adapter;
  }

  final schoolUser = userJson(school: visaSeoul);

  testWidgets('조건7: 켜짐 상태에서 설정으로 꺼진 뒤 복귀하면 "켜져 있어요" 문구가 남지 않는다', (tester) async {
    final push = _GatedPush(PushPermission.granted);
    await pump(tester, push: push, user: schoolUser);
    expect(find.text(_onText), findsOneWidget);
    push.current = PushPermission.denied; // 사용자가 설정 앱에서 알림을 껐다
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.text(_onText), findsNothing);
    // 꺼진 뒤에는 처음 거부와 구분이 안 되므로 소개 문구 + [알림 켜기] 버튼으로 보인다
    expect(find.text(_introText), findsOneWidget);
    expect(_button, findsOneWidget);
  });

  testWidgets('조건9: [알림 켜기]를 빠르게 두 번 눌러도 권한 요청은 1번만 나간다', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined, afterRequest: PushPermission.granted)
      ..requestGate = Completer<void>();
    final adapter = await pump(tester, push: push, user: schoolUser);
    await tester.tap(_button);
    await tester.pump(); // 아직 응답 전: 진행 중
    await tester.tap(_button, warnIfMissed: false);
    await tester.pump();
    expect(push.requestCalls, 1);
    expect(tester.widget<FilledButton>(_button).onPressed, isNull);
    push.requestGate!.complete();
    await tester.pumpAndSettle();
    expect(push.requestCalls, 1);
    expect(adapter.count('PUT', _tokensPath), 1);
    expect(find.text(_onText), findsOneWidget);
  });

  testWidgets('조건10: 푸시 준비(init)가 실패(false)하면 칸이 안 그려지고 오류도 없다', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined, initResult: false);
    final adapter = await pump(tester, push: push, user: schoolUser);
    expect(find.text('비자 일정'), findsWidgets);
    expect(find.text(_panelTitle), findsNothing);
    expect(_button, findsNothing);
    expect(adapter.count('PUT', _tokensPath), 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('조건10: 푸시 준비(init)가 예외로 실패해도 칸이 안 그려지고 오류도 없다', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined, initThrows: true);
    await pump(tester, push: push, user: schoolUser);
    expect(find.text(_panelTitle), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('조건10: 푸시 준비가 진행 중이면 칸이 없다가, 끝나면 나타난다', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined, initGate: Completer<void>());
    await pump(tester, push: push, user: schoolUser);
    expect(find.text(_panelTitle), findsNothing);
    expect(tester.takeException(), isNull);
    push.initGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text(_panelTitle), findsOneWidget);
    expect(_button, findsOneWidget);
  });

  testWidgets('조건10: 권한을 읽는 중에 화면을 떠나도(dispose) 결과가 와도 오류가 없다', (tester) async {
    final push = _GatedPush(PushPermission.granted)..permissionGate = Completer<void>();
    final adapter = await pump(tester, push: push, user: schoolUser);
    expect(find.text(_panelTitle), findsNothing); // 아직 읽는 중
    routerOf(tester).go('/');
    await tester.pumpAndSettle();
    push.permissionGate!.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text(_panelTitle), findsNothing);
    // PUT 1번은 칸이 아니라 registrar가 앱 시작 때 하는 자동 등록(S24-d)이다. 중복 없이 딱 1번이어야 한다
    expect(adapter.count('PUT', _tokensPath), 1);
  });

  testWidgets('조건10: 권한 팝업이 떠 있는 동안 화면을 떠나도(dispose) 결과가 오면 오류가 없다', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined, afterRequest: PushPermission.denied)
      ..requestGate = Completer<void>();
    await pump(tester, push: push, user: schoolUser);
    await tester.tap(_button);
    await tester.pump();
    routerOf(tester).go('/');
    await tester.pumpAndSettle();
    push.requestGate!.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('조건8: 로그아웃 상태로 /visa-plan에 가도 칸이 없다 (로그인 화면으로 보낸다)', (tester) async {
    final push = _GatedPush(PushPermission.notDetermined);
    final adapter = await pump(tester, push: push, user: null);
    expect(find.text(_panelTitle), findsNothing);
    expect(_button, findsNothing);
    expect(adapter.count('PUT', _tokensPath), 0);
    expect(tester.takeException(), isNull);
  });
}
