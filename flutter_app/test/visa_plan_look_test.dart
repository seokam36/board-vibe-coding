// S26-k3 비자 일정 새 모양 테스트: 일정이 있을 때 세로 순서 D-n < 만료일 칸 < "휴대폰 알림"(앱 조건), 320px 넘침 없음.
// 날짜 칸 세 벌이 같은 부품(DateField)이고 키가 그대로인지도 본다. 기능 동작은 visa_plan_page_test 등이 본다.
import 'dart:async';

import 'package:board_web/app.dart';
import 'package:board_web/push/app_push.dart';
import 'package:board_web/widgets/date_field.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';
import 'support/visa_plan_json.dart';

// 가짜 푸시 도구: 아직 정하지 않은 권한(notDetermined)을 돌려줘서 "알림 켜기" 칸이 보이게 한다
class _FakePush implements AppPush {
  @override
  Future<bool> init() async => true;

  @override
  Future<PushPermission> permission() async => PushPermission.notDetermined;

  @override
  Future<PushPermission> requestPermission() async => PushPermission.notDetermined;

  @override
  Future<String?> getToken() async => 'fake-token-1';

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();
}

// /visa-plan 을 정한 폭으로 연다. push가 null이면 웹처럼 푸시 부품이 없는 앱
Future<void> _open(
  WidgetTester tester, {
  double width = 375,
  AppPush? push,
  FakeAdapter? adapter, // 정하지 않으면 일정 있음(planJson) 응답
  bool settle = true, // false면 응답을 기다리지 않는다 (불러오는 중 상태를 볼 때)
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 2400);
  addTearDown(tester.view.reset);
  adapter ??= FakeAdapter()..on('GET', '/users/me/visa-plan', FakeReply(200, planJson()));
  final auth = LoadedAuthState(adapter: adapter, user: userJson(school: visaSeoul));
  addTearDown(auth.dispose);
  await tester.pumpWidget(BoardApp(
    authState: auth,
    initialLocation: '/visa-plan',
    now: () => DateTime.utc(2026, 11, 10, 3),
    push: push,
  ));
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
  }
}

void main() {
  testWidgets('앱: D-n → 만료일 칸 → "휴대폰 알림" 순서로 세로로 놓인다', (tester) async {
    await _open(tester, push: _FakePush());
    final dday = tester.getTopLeft(find.byKey(const ValueKey('visa-dday'))).dy;
    final expiry = tester.getTopLeft(find.byKey(const ValueKey('visa-expiry-field'))).dy;
    final push = tester.getTopLeft(find.text('휴대폰 알림')).dy;
    expect(dday, lessThan(expiry));
    expect(expiry, lessThan(push));
    expect(find.text('알림 켜기'), findsOneWidget);
  });

  testWidgets('웹(푸시 부품 없음): "휴대폰 알림" 칸이 없고 D-n < 만료일 칸 순서는 같다', (tester) async {
    await _open(tester);
    expect(find.text('휴대폰 알림'), findsNothing);
    final dday = tester.getTopLeft(find.byKey(const ValueKey('visa-dday'))).dy;
    final expiry = tester.getTopLeft(find.byKey(const ValueKey('visa-expiry-field'))).dy;
    expect(dday, lessThan(expiry));
  });

  testWidgets('안내 문구는 맨 위, 할 일 줄 키는 그대로', (tester) async {
    await _open(tester, push: _FakePush());
    final guide = tester.getTopLeft(find.textContaining('일반 안내이며')).dy;
    expect(guide, lessThan(tester.getTopLeft(find.byKey(const ValueKey('visa-dday'))).dy));
    for (var i = 0; i < 3; i++) {
      expect(find.byKey(ValueKey('visa-todo-$i')), findsOneWidget);
    }
  });

  testWidgets('만료일 칸은 공용 DateField이고 키·힌트가 그대로다', (tester) async {
    await _open(tester);
    final field = find.ancestor(of: find.byKey(const ValueKey('visa-expiry-field')), matching: find.byType(DateField));
    expect(field, findsOneWidget);
    final input = tester.widget<TextField>(find.byKey(const ValueKey('visa-expiry-field')));
    expect(input.decoration!.hintText, '날짜를 선택하세요');
    expect(input.readOnly, isTrue);
  });

  testWidgets('후기 작성 폼의 접수일·결과일 칸도 같은 DateField, 키 2개 그대로', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(800, 1800);
    addTearDown(tester.view.reset);
    final adapter = visaFilterAdapter();
    await pumpApp(tester, location: '/visa-reviews/new', user: userJson(school: visaSeoul), adapter: adapter);
    await tester.pumpAndSettle();
    expect(find.byType(DateField), findsNWidgets(2));
    for (final key in ['received-date-field', 'result-date-field']) {
      expect(find.ancestor(of: find.byKey(ValueKey(key)), matching: find.byType(DateField)), findsOneWidget);
    }
  });

  testWidgets('앱: 일정이 없으면 만료일 폼 < "휴대폰 알림" 순서', (tester) async {
    final adapter = FakeAdapter()..on('GET', '/users/me/visa-plan', FakeReply(200, emptyPlanJson));
    await _open(tester, push: _FakePush(), adapter: adapter);
    final expiry = tester.getTopLeft(find.byKey(const ValueKey('visa-expiry-field'))).dy;
    final push = tester.getTopLeft(find.text('휴대폰 알림')).dy;
    expect(expiry, lessThan(push));
  });

  testWidgets('앱: 삭제에 실패하면 실패 문구가 "휴대폰 알림" 판보다 위에 있다', (tester) async {
    final adapter = FakeAdapter()
      ..on('GET', '/users/me/visa-plan', FakeReply(200, planJson()))
      ..on('DELETE', '/users/me/visa-plan', FakeReply(500));
    await _open(tester, push: _FakePush(), adapter: adapter);
    await tester.tap(find.widgetWithText(OutlinedButton, '삭제'));
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '삭제')));
    await tester.pumpAndSettle();
    final error = tester.getTopLeft(find.text('삭제하지 못했습니다')).dy;
    final push = tester.getTopLeft(find.text('휴대폰 알림')).dy;
    expect(error, lessThan(push));
  });

  testWidgets('앱: 불러오는 중에는 "휴대폰 알림" 판이 없고, 응답이 오면 나타난다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()..on('GET', '/users/me/visa-plan', FakeReply(200, planJson(), gate));
    await _open(tester, push: _FakePush(), adapter: adapter, settle: false);
    expect(find.text('휴대폰 알림'), findsNothing);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('휴대폰 알림'), findsOneWidget);
  });

  testWidgets('앱: 불러오기에 실패하면 "휴대폰 알림" 판을 그리지 않는다', (tester) async {
    final adapter = FakeAdapter()..on('GET', '/users/me/visa-plan', FakeReply(500));
    await _open(tester, push: _FakePush(), adapter: adapter);
    expect(find.text('휴대폰 알림'), findsNothing);
  });

  testWidgets('DateField: 마지막 날·오늘은 그릴 때가 아니라 칸을 누르는 순간 읽는다 (자정 넘김 대비)', (tester) async {
    var lastReads = 0;
    var todayReads = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: DateField(
          fieldKey: const ValueKey('f'),
          label: '날짜',
          controller: TextEditingController(),
          firstDay: '2026-01-01',
          lastDay: () {
            lastReads++;
            return '2026-11-10';
          },
          today: () {
            todayReads++;
            return '2026-11-10';
          },
        ),
      ),
    ));
    expect(lastReads, 0);
    expect(todayReads, 0);
    await tester.tap(find.byKey(const ValueKey('f')));
    await tester.pumpAndSettle();
    expect(lastReads, 1);
    expect(todayReads, 1);
  });

  testWidgets('320px: 앱 조건에서도 넘치지 않는다', (tester) async {
    await _open(tester, width: 320, push: _FakePush());
    expect(tester.takeException(), isNull);
    expect(tester.getRect(find.text('알림 켜기')).right, lessThanOrEqualTo(320));
  });
}
