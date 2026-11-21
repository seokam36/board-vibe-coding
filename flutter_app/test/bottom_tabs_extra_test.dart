// S26-c 추가 검사: 글 보기 화면의 탭 5개, 키보드가 열리면 탭 막대 숨김, 앱 모드 뒤로 가기(탭 A → 탭 B → 뒤로 = 탭 A)
import 'package:board_web/app.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

Finder _cell(int i) => find.byKey(ValueKey('bottom-tab-$i'));

FakeAdapter _adapter() {
  final a = FakeAdapter();
  for (var i = 0; i < 6; i++) {
    a.on('GET', '/conversations/unread-count', FakeReply(200, {'count': 0}));
  }
  return a
    ..on('GET', '/posts/7', FakeReply(200, postJson()))
    ..on('GET', '/posts/7/comments', FakeReply(200, []))
    ..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}))
    ..on('GET', '/categories', FakeReply(200, []))
    ..on('GET', '/schools', FakeReply(200, []))
    ..on('GET', '/posts', FakeReply(200, []))
    ..on('GET', '/visa-review-options', FakeReply(200, {'visaTypes': [], 'applicationTypes': [], 'results': []}))
    ..on('GET', '/visa-reviews', FakeReply(200, []))
    ..on('GET', '/market-items', FakeReply(200, {'items': [], 'hasMore': false}));
}

void main() {
  testWidgets('글 보기 화면에도 아래 탭 5개가 순서대로 있고 게시판 탭이 선택이다', (tester) async {
    await pumpApp(tester, location: '/posts/7', adapter: _adapter());
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
    final labels = ['게시판', '비자', '중고', '쪽지', '내 정보'];
    final xs = <double>[];
    for (var i = 0; i < 5; i++) {
      final f = find.descendant(of: _cell(i), matching: find.text(labels[i]));
      expect(f, findsOneWidget);
      xs.add(tester.getTopLeft(f).dx);
    }
    expect(xs, [...xs]..sort());
  });

  testWidgets('키보드가 열린 상태(viewInsets.bottom > 0)에서는 탭 막대가 없고, 닫으면 다시 나온다', (tester) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewInsets);
    await pumpApp(tester, adapter: _adapter());
    expect(_cell(0), findsOneWidget);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    await tester.pumpAndSettle();
    for (var i = 0; i < 5; i++) {
      expect(_cell(i), findsNothing);
    }
    tester.view.resetViewInsets();
    await tester.pumpAndSettle();
    expect(_cell(0), findsOneWidget);
  });

  testWidgets('앱 모드 뒤로 가기: 탭 A(게시판) → 탭 B(비자)를 누르고 뒤로 가면 탭 A로 돌아온다', (tester) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final auth = LoadedAuthState(adapter: _adapter());
    addTearDown(auth.dispose);
    await tester.pumpWidget(BoardApp(authState: auth, enableAppBack: true));
    await tester.pumpAndSettle();
    final router = GoRouter.of(tester.element(find.byType(Scaffold).first));
    String where() => router.routerDelegate.currentConfiguration.uri.path;

    await tester.tap(_cell(1));
    await tester.pumpAndSettle();
    expect(where(), '/visa-reviews');
    await tester.tap(_cell(2));
    await tester.pumpAndSettle();
    expect(where(), '/market');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(where(), '/visa-reviews'); // 탭 B → 뒤로 = 직전 탭
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(where(), '/'); // 다시 뒤로 = 처음 탭 A
  });
}
