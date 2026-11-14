// S16-d1 앱 시작 테스트: 시작 확인(/csrf → /auth/me)이 끝날 때까지 로딩, 끝나면 화면이 보이는지 본다.
// S16-d2a: 로딩 표시는 빙글빙글 대신 React처럼 "불러오는 중..." 문구(상단바도 아직 안 보임).
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/app.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/post_json.dart';

void main() {
  testWidgets('init이 끝나기 전엔 로딩 문구만, 끝나면 화면이 보인다', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}, gate))
      ..on('GET', '/auth/me', FakeReply(401))
      ..on('POST', '/auth/refresh', FakeReply(401));

    await tester.pumpWidget(
      BoardApp(
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pump();
    expect(find.text('불러오는 중...'), findsOneWidget);
    expect(find.text('전체 게시판'), findsNothing); // S16-e1: "글 목록" 자리표시 대신 글 목록 화면 머리말로 확인
    expect(find.text('비자 후기'), findsNothing); // 상단바도 아직 없다

    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('불러오는 중...'), findsNothing);
    expect(find.text('전체 게시판'), findsOneWidget);
    expect(find.text('비자 후기'), findsOneWidget); // 상단바가 나타났다
    // 시작 확인이 정해진 순서로 한 번씩 일어났다
    expect(adapter.count('GET', '/csrf'), 1);
    expect(adapter.count('GET', '/auth/me'), 1);
  });

  testWidgets('깊은 주소(/posts/7)로 시작해도 로딩이 끝나면 그 화면이 보인다', (tester) async {
    // 로딩 화면이 라우터를 통째로 가리는 구조라, 시작 주소가 로딩 뒤에도 유지되는지 확인
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}))
      ..on('GET', '/auth/me', FakeReply(401))
      ..on('POST', '/auth/refresh', FakeReply(401))
      ..on('GET', '/posts/7', FakeReply(200, postJson(title: '깊은 주소의 글'))) // S16-f1: 진짜 상세 화면이 글을 불러온다
      ..on('GET', '/posts/7/comments', FakeReply(200, [])); // S16-f2: 댓글도 불러온다

    await tester.pumpWidget(
      BoardApp(
        initialLocation: '/posts/7',
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pumpAndSettle();
    // 글이 뜬 뒤에 시작하는 댓글 요청의 타이머가 끝나도록 한 번 더 흘려 보낸다 (S16-f2)
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
    expect(find.text('깊은 주소의 글'), findsOneWidget); // S16-f1: 자리표시 대신 글 제목으로 확인
  });

  testWidgets('/csrf 가 실패해도 로딩은 끝나고 화면이 보인다', (tester) async {
    final adapter = FakeAdapter()..on('GET', '/csrf', FakeReply.networkError());

    await tester.pumpWidget(
      BoardApp(
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('전체 게시판'), findsOneWidget);
    expect(adapter.count('GET', '/auth/me'), 0);
  });

  // 문지기는 시작 확인이 끝나기 전엔 판단을 미룬다: 로그인한 사람이 /me 를 새로고침해도 /login 으로 튕기지 않는다
  // 로그인 안 한 채 보호 주소로 시작: 확인이 끝나기 전엔 보호 화면이 한 순간도 안 보이고, 끝나면 로그인 화면
  testWidgets('비로그인으로 /me 시작: 로딩 중엔 내 정보 화면이 없고, 끝나면 로그인 화면', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}))
      ..on('GET', '/auth/me', FakeReply(401, null, gate))
      ..on('POST', '/auth/refresh', FakeReply(401));

    await tester.pumpWidget(
      BoardApp(
        initialLocation: '/me',
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pump();
    expect(find.text('불러오는 중...'), findsOneWidget);
    expect(find.text('학교 인증'), findsNothing); // S16-g1: 내 정보 화면은 "학교 인증" 제목으로 확인
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('불러오는 중...'), findsNothing);
    expect(find.text('학교 인증'), findsNothing);
    expect(find.widgetWithText(TextField, '아이디'), findsOneWidget); // 로그인 화면
  });

  testWidgets('로그인한 채 보호 주소(/me)로 시작하면 로딩 뒤에도 /me 화면 (로딩 중 /login 으로 안 튕김)', (tester) async {
    final gate = Completer<void>();
    final adapter = FakeAdapter()
      ..on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}))
      ..on('GET', '/auth/me', FakeReply(200, userJson(), gate));

    await tester.pumpWidget(
      BoardApp(
        initialLocation: '/me',
        apiClient: ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
      ),
    );
    await tester.pump();
    expect(find.text('불러오는 중...'), findsOneWidget);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('학교 인증'), findsOneWidget); // S16-g1: 진짜 내 정보 화면
    expect(find.text('아이디'), findsNothing); // 로그인 화면이 아니다
  });
}
