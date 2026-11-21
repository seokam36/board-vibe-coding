// S24-e 검사: 비자 일정 화면의 "휴대폰 알림" 칸 (앱에서만, 학교 인증 회원만)과 로그아웃 버튼 두 번 누름 방지.
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

// 가짜 푸시 도구: 지금 권한과 "팝업을 누른 뒤의 권한"을 정해 두고, 요청 횟수를 센다
class _FakePush implements AppPush {
  _FakePush(this.current, {PushPermission? afterRequest, this.requestThrows = false}) : afterRequest = afterRequest ?? current;

  PushPermission current;
  final PushPermission afterRequest;
  final bool requestThrows; // true면 권한 요청이 예외를 던진다
  int requestCalls = 0;

  @override
  Future<bool> init() async => true;

  @override
  Future<PushPermission> permission() async => current;

  @override
  Future<PushPermission> requestPermission() async {
    requestCalls++;
    if (requestThrows) throw StateError('권한 요청 실패');
    current = afterRequest;
    return current;
  }

  @override
  Future<String?> getToken() async => 'fake-token-1';

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();
}

final _button = find.widgetWithText(FilledButton, '알림 켜기');
const _panelTitle = '휴대폰 알림';
const _introText = '비자 만료 120·30·7일 전에 알림을 보내 드려요';
const _onText = '알림이 켜져 있어요';
const _settingsText = '휴대폰 설정에서 이 앱의 알림을 허용해 주세요';

void main() {
  // /visa-plan으로 앱을 띄운다. push가 null이면 웹처럼 푸시 부품이 없는 앱
  Future<FakeAdapter> pump(WidgetTester tester, {AppPush? push, Map<String, dynamic>? user, List<FakeReply>? putReplies}) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('GET', _planPath, FakeReply(200, emptyPlanJson));
    for (final r in putReplies ?? [FakeReply(204)]) {
      adapter.on('PUT', _tokensPath, r);
    }
    final auth = LoadedAuthState(adapter: adapter, user: user ?? userJson(school: visaSeoul));
    addTearDown(auth.dispose);
    await tester.pumpWidget(BoardApp(
      authState: auth,
      initialLocation: '/visa-plan',
      now: () => DateTime.utc(2026, 11, 10, 3),
      push: push,
    ));
    await tester.pumpAndSettle();
    return adapter;
  }

  group('휴대폰 알림 칸', () {
    testWidgets('웹(푸시 부품 없음): 칸 자체가 없다', (tester) async {
      await pump(tester);
      expect(find.text('비자 일정'), findsWidgets);
      expect(find.text(_panelTitle), findsNothing);
      expect(_button, findsNothing);
    });

    testWidgets('앱 + 미결정: 설명과 [알림 켜기]가 보이고, 누르기 전엔 요청도 PUT도 없다', (tester) async {
      final push = _FakePush(PushPermission.notDetermined);
      final adapter = await pump(tester, push: push);
      expect(find.text(_panelTitle), findsOneWidget);
      expect(find.text(_introText), findsOneWidget);
      expect(_button, findsOneWidget);
      expect(push.requestCalls, 0);
      expect(adapter.count('PUT', _tokensPath), 0);
    });

    testWidgets('[알림 켜기] 누르고 허용: 권한 요청 1번, PUT 1번, "알림이 켜져 있어요", 버튼 없음', (tester) async {
      final push = _FakePush(PushPermission.notDetermined, afterRequest: PushPermission.granted);
      final adapter = await pump(tester, push: push);
      await tester.tap(_button);
      await tester.pumpAndSettle();
      expect(push.requestCalls, 1);
      expect(adapter.count('PUT', _tokensPath), 1);
      expect(find.text(_onText), findsOneWidget);
      expect(_button, findsNothing);
    });

    testWidgets('[알림 켜기] 누르고 거부: PUT 0번, 휴대폰 설정 안내, 버튼 없음', (tester) async {
      final push = _FakePush(PushPermission.notDetermined, afterRequest: PushPermission.denied);
      final adapter = await pump(tester, push: push);
      await tester.tap(_button);
      await tester.pumpAndSettle();
      expect(push.requestCalls, 1);
      expect(adapter.count('PUT', _tokensPath), 0);
      expect(find.text(_settingsText), findsOneWidget);
      expect(_button, findsNothing);
    });

    testWidgets('처음부터 "거부"로 오는 안드로이드: 버튼이 보이고 한 번은 물어볼 수 있다 (허용되면 켜짐)', (tester) async {
      final push = _FakePush(PushPermission.denied, afterRequest: PushPermission.granted);
      final adapter = await pump(tester, push: push);
      expect(_button, findsOneWidget);
      expect(find.text(_settingsText), findsNothing);
      await tester.tap(_button);
      await tester.pumpAndSettle();
      expect(push.requestCalls, 1);
      expect(adapter.count('PUT', _tokensPath), 1);
      expect(find.text(_onText), findsOneWidget);
    });

    testWidgets('이미 허용으로 들어옴: 버튼 없이 켜짐 문구, 권한 요청 0번, PUT은 1번(중복 없음)', (tester) async {
      final push = _FakePush(PushPermission.granted);
      final adapter = await pump(tester, push: push);
      expect(find.text(_onText), findsOneWidget);
      expect(_button, findsNothing);
      expect(push.requestCalls, 0);
      expect(adapter.count('PUT', _tokensPath), 1);
    });

    testWidgets('앱이 다시 앞으로 돌아오면(설정에서 허용하고 온 경우) 상태를 다시 읽어 켜짐으로 바뀐다', (tester) async {
      final push = _FakePush(PushPermission.denied);
      final adapter = await pump(tester, push: push);
      expect(_button, findsOneWidget);
      push.current = PushPermission.granted; // 사용자가 설정 앱에서 허용했다
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text(_onText), findsOneWidget);
      expect(_button, findsNothing);
      expect(adapter.count('PUT', _tokensPath), 1);
    });

    testWidgets('거부 → 설정 안내 → 설정에서 허용하고 돌아오면(resumed) "알림이 켜져 있어요" + PUT 1번', (tester) async {
      final push = _FakePush(PushPermission.notDetermined, afterRequest: PushPermission.denied);
      final adapter = await pump(tester, push: push);
      await tester.tap(_button);
      await tester.pumpAndSettle();
      expect(find.text(_settingsText), findsOneWidget);
      expect(adapter.count('PUT', _tokensPath), 0);
      push.current = PushPermission.granted; // 사용자가 설정 앱에서 허용했다
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text(_onText), findsOneWidget);
      expect(find.text(_settingsText), findsNothing);
      expect(adapter.count('PUT', _tokensPath), 1);
    });

    testWidgets('허용 상태에서 PUT이 실패(500)하면 돌아올 때(resumed) PUT을 다시 하고, 성공한 뒤엔 더 안 나간다', (tester) async {
      final push = _FakePush(PushPermission.granted);
      final adapter = await pump(tester, push: push, putReplies: [FakeReply(500), FakeReply(500), FakeReply(204)]);
      // 시작 때 2번(앱 시작 자동 등록 + 칸이 처음 읽을 때 확인)이 둘 다 실패
      expect(adapter.count('PUT', _tokensPath), 2);
      Future<void> resume() async {
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
        tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
        await tester.pumpAndSettle();
      }

      await resume();
      expect(adapter.count('PUT', _tokensPath), 3); // 실패했으니 1번 다시 (성공)
      await resume();
      expect(adapter.count('PUT', _tokensPath), 3); // 성공했으니 더 안 나간다
      expect(find.text(_onText), findsOneWidget);
    });

    testWidgets('권한 요청이 예외를 던져도 오류 없이 지나간다', (tester) async {
      final push = _FakePush(PushPermission.notDetermined, requestThrows: true);
      final adapter = await pump(tester, push: push);
      await tester.tap(_button);
      await tester.pumpAndSettle();
      expect(push.requestCalls, 1);
      expect(tester.takeException(), isNull);
      expect(adapter.count('PUT', _tokensPath), 0);
    });

    testWidgets('학교 미인증 회원: 칸이 없다', (tester) async {
      final push = _FakePush(PushPermission.notDetermined);
      await pump(tester, push: push, user: userJson());
      expect(find.text('비자 일정은 유학생 인증 후에 등록할 수 있어요'), findsOneWidget);
      expect(find.text(_panelTitle), findsNothing);
      expect(_button, findsNothing);
    });
  });

  group('로그아웃 버튼', () {
    testWidgets('연타해도 /auth/logout은 1번만 나간다 (진행 중엔 버튼이 막힌다)', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', '/auth/logout', FakeReply(200, null, gate));
      await pumpApp(tester, user: userJson(), adapter: adapter);
      await tester.tap(find.text('로그아웃'));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(find.text('로그아웃 중...'), warnIfMissed: false); // 진행 중엔 라벨이 바뀐다
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '로그아웃 중...')).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/auth/logout'), 1);
    });

    testWidgets('요청 진행 중에는 라벨이 "로그아웃 중..."이고, 10초 제한이 걸려 있고, 끝나면 되돌아온다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', '/auth/logout', FakeReply(500, null, gate));
      await pumpApp(tester, user: userJson(), adapter: adapter);
      expect(find.text('로그아웃 중...'), findsNothing);
      await tester.tap(find.text('로그아웃'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('로그아웃 중...'), findsOneWidget);
      expect(find.text('로그아웃'), findsNothing);
      expect(adapter.callsTo('POST', '/auth/logout').single.receiveTimeout, const Duration(seconds: 10));
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('로그아웃'), findsOneWidget);
      expect(find.text('로그아웃 중...'), findsNothing);
    });

    testWidgets('실패(500) 뒤에는 버튼이 다시 눌리고, 다시 누르면 요청이 2번째로 나간다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/auth/logout', FakeReply(500))
        ..on('POST', '/auth/logout', FakeReply(500));
      await pumpApp(tester, user: userJson(), adapter: adapter);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '로그아웃')).onPressed, isNotNull);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/auth/logout'), 2);
    });
  });
}
