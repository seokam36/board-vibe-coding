// S16-d1 앱 시작 테스트: 시작 확인(/csrf → /auth/me)이 끝날 때까지 로딩, 끝나면 화면이 보이는지 본다.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/app.dart';
import 'package:board_web/router.dart';
import 'package:material_ui/material_ui.dart' show CircularProgressIndicator;
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';

void main() {
  testWidgets('init이 끝나기 전엔 로딩만, 끝나면 화면이 보인다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}, gate))
      ..on('GET', '/auth/me', FakeReply(401))
      ..on('POST', '/auth/refresh', FakeReply(401));
    final router = createRouter();
    addTearDown(router.dispose);

    await tester.pumpWidget(
      BoardApp(
        router: router,
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.text('글 목록'), findsNothing);

    gate.complete();
    await tester.pumpAndSettle();
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('글 목록'), findsOneWidget);
    // 시작 확인이 정해진 순서로 한 번씩 일어났다
    expect(adapter.count('GET', '/csrf'), 1);
    expect(adapter.count('GET', '/auth/me'), 1);
  });

  testWidgets('깊은 주소(/posts/7)로 시작해도 로딩이 끝나면 그 화면이 보인다', (tester) async {
    // 로딩 화면이 라우터를 통째로 가리는 구조라, 시작 주소가 로딩 뒤에도 유지되는지 확인
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}))
      ..on('GET', '/auth/me', FakeReply(401))
      ..on('POST', '/auth/refresh', FakeReply(401));
    final router = createRouter(initialLocation: '/posts/7');
    addTearDown(router.dispose);

    await tester.pumpWidget(
      BoardApp(
        router: router,
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('글 상세 (id: 7)'), findsOneWidget);
  });

  testWidgets('/csrf 가 실패해도 로딩은 끝나고 화면이 보인다', (tester) async {
    final adapter = FakeAdapter()..on('GET', '/csrf', FakeReply.networkError());
    final router = createRouter();
    addTearDown(router.dispose);

    await tester.pumpWidget(
      BoardApp(
        router: router,
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('글 목록'), findsOneWidget);
    expect(adapter.count('GET', '/auth/me'), 0);
  });
}
