// S24-d 보충 검사: 다른 회원, 로그아웃 실패 회귀, getToken 실패·시간 초과, 등록과 로그아웃이 겹치는 경쟁.
// 가짜 푸시·가짜 서버만 쓰고 토큰은 지어낸 글자다.
import 'dart:async';

import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/push/app_push.dart';
import 'package:board_web/push/push_registrar.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _tokensPath = '/users/me/device-tokens';

// 토큰을 줄 때 붙잡아 둘 수 있고, 예외도 던질 수 있는 가짜 푸시
class GatedPush implements AppPush {
  Completer<void>? tokenGate; // 있으면 getToken이 이게 끝날 때까지 기다린다
  bool tokenThrows = false;
  bool tokenNever = false; // 영원히 안 끝나는 getToken (시간 초과 시험)
  String token = 'fake-token-1';

  @override
  Future<bool> init() async => true;
  bool permissionNever = false; // 영원히 안 끝나는 권한 확인
  @override
  Future<PushPermission> permission() async {
    if (permissionNever) return Completer<PushPermission>().future;
    return PushPermission.granted;
  }

  @override
  Future<PushPermission> requestPermission() async => PushPermission.granted;
  @override
  Future<String?> getToken() async {
    if (tokenNever) return Completer<String?>().future;
    if (tokenThrows) throw StateError('getToken 실패');
    if (tokenGate != null) await tokenGate!.future;
    return token;
  }

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();
}

void main() {
  late FakeAdapter adapter;
  late LoadedAuthState auth;

  setUp(() {
    adapter = FakeAdapter();
    auth = LoadedAuthState(adapter: adapter, user: userJson());
    addTearDown(auth.dispose);
  });

  Future<PushRegistrar> start(GatedPush push) async {
    final r = PushRegistrar(api: auth.api, auth: auth, push: push);
    addTearDown(r.dispose);
    await r.start();
    await pumpEventQueue();
    return r;
  }

  List<String> order() => adapter.calls.map((c) => '${c.method} ${c.path}').toList();

  test('다른 회원으로 바뀌면 같은 토큰이어도 PUT을 다시 한다', () async {
    adapter.on('PUT', _tokensPath, FakeReply(204));
    adapter.on('PUT', _tokensPath, FakeReply(204));
    await start(GatedPush());
    expect(adapter.count('PUT', _tokensPath), 1);

    auth.updateUser(AuthUser.fromJson(userJson(id: 2, username: 'user02', nickname: '김철수')));
    await pumpEventQueue();

    expect(adapter.count('PUT', _tokensPath), 2);
  });

  test('/auth/logout이 실패하면 기존처럼 에러가 던져지고 로그인 상태는 유지된다 (토큰 DELETE는 이미 나감)', () async {
    adapter.on('PUT', _tokensPath, FakeReply(204));
    adapter.on('DELETE', _tokensPath, FakeReply(204));
    adapter.on('POST', '/auth/logout', FakeReply(500));
    await start(GatedPush());

    await expectLater(auth.logout(), throwsA(isA<DioException>()));

    expect(auth.user, isNotNull);
    expect(adapter.count('DELETE', _tokensPath), 1);
  });

  test('푸시 없는(beforeLogout 없는) 로그아웃 실패도 기존과 같다: 에러 + 상태 유지', () async {
    adapter.on('POST', '/auth/logout', FakeReply(500));

    await expectLater(auth.logout(), throwsA(isA<DioException>()));

    expect(auth.user, isNotNull);
  });

  test('getToken이 예외를 던져도 /auth/logout은 나가고 user가 비워진다', () async {
    adapter.on('PUT', _tokensPath, FakeReply(204));
    adapter.on('POST', '/auth/logout', FakeReply(204));
    final push = GatedPush();
    await start(push);
    push.tokenThrows = true;

    await auth.logout();

    expect(adapter.count('DELETE', _tokensPath), 0);
    expect(adapter.count('POST', '/auth/logout'), 1);
    expect(auth.user, isNull);
  });

  // 로그아웃 단계(unregister)에서 getToken이 멈추는 경우도 같은 5초 제한으로 풀린다
  testWidgets('로그아웃 단계에서 getToken이 끝나지 않아도 5초 뒤 /auth/logout이 나가고 user가 비워진다', (tester) async {
    adapter.on('PUT', _tokensPath, FakeReply(204));
    adapter.on('POST', '/auth/logout', FakeReply(204));
    final push = GatedPush();
    final r = PushRegistrar(api: auth.api, auth: auth, push: push);
    addTearDown(r.dispose);
    await r.start();
    await tester.pump();
    push.tokenNever = true;

    final f = auth.logout();
    await tester.pump(const Duration(seconds: 6));
    await f;

    expect(adapter.count('POST', '/auth/logout'), 1);
    expect(auth.user, isNull);
  });

  testWidgets('등록 확인이 권한 확인(permission)에서 멈춘 채 로그아웃해도 제한 시간 뒤 /auth/logout이 나가고 user가 비워진다', (tester) async {
    adapter.on('POST', '/auth/logout', FakeReply(204));
    final push = GatedPush()..permissionNever = true;
    final r = PushRegistrar(api: auth.api, auth: auth, push: push);
    addTearDown(r.dispose);
    await r.start();
    await tester.pump(); // 시작 때 확인이 permission에서 멈춘다

    final f = auth.logout();
    await tester.pump(const Duration(seconds: 6));
    await f;

    expect(adapter.count('POST', '/auth/logout'), 1);
    expect(auth.user, isNull);
  });

  testWidgets('등록 확인이 getToken에서 멈춘 채 로그아웃해도 제한 시간 뒤 /auth/logout이 나가고 user가 비워진다', (tester) async {
    adapter.on('POST', '/auth/logout', FakeReply(204));
    final push = GatedPush()..tokenNever = true;
    final r = PushRegistrar(api: auth.api, auth: auth, push: push);
    addTearDown(r.dispose);
    await r.start();
    await tester.pump(); // 시작 때 확인이 getToken에서 멈춘다

    final f = auth.logout();
    await tester.pump(const Duration(seconds: 6));
    await f;

    expect(adapter.count('POST', '/auth/logout'), 1);
    expect(auth.user, isNull);
  });

  test('A의 PUT이 날아가는 중 A가 풀리고 B로 로그인하면 B도 PUT한다', () async {
    final gate = Completer<void>();
    adapter.on('PUT', _tokensPath, FakeReply(204, null, gate));
    adapter.on('PUT', _tokensPath, FakeReply(204));
    await start(GatedPush()); // A의 PUT이 응답을 기다리는 중
    expect(adapter.count('PUT', _tokensPath), 1);

    auth.clearUser(); // A 로그인이 풀리고
    auth.updateUser(AuthUser.fromJson(userJson(id: 2, username: 'user02', nickname: '김철수'))); // B가 로그인
    gate.complete();
    await pumpEventQueue();

    expect(adapter.count('PUT', _tokensPath), 2);
  });

  test('등록(PUT) 응답을 기다리는 중 로그아웃해도 순서는 PUT, DELETE, /auth/logout', () async {
    final gate = Completer<void>();
    adapter.on('PUT', _tokensPath, FakeReply(204, null, gate));
    adapter.on('DELETE', _tokensPath, FakeReply(204));
    adapter.on('POST', '/auth/logout', FakeReply(204));
    await start(GatedPush()); // PUT이 나갔지만 응답이 붙잡혀 있다
    expect(adapter.count('PUT', _tokensPath), 1);

    final f = auth.logout();
    await pumpEventQueue();
    expect(adapter.count('DELETE', _tokensPath), 0); // PUT이 끝나기 전엔 DELETE도 안 나간다
    gate.complete();
    await f;

    expect(order(), ['PUT /api$_tokensPath', 'DELETE /api$_tokensPath', 'POST /api/auth/logout']);
    expect(auth.user, isNull);
  });

  test('토큰을 기다리는 중 로그인이 풀리면(만료) 그 뒤에 PUT이 나가지 않는다', () async {
    final push = GatedPush()..tokenGate = Completer<void>();
    await start(push); // 확인이 getToken에서 멈춘 채
    expect(adapter.count('PUT', _tokensPath), 0);

    auth.clearUser(); // 로그인이 풀림
    push.tokenGate!.complete();
    await pumpEventQueue();

    expect(adapter.count('PUT', _tokensPath), 0);
  });

  test('토큰을 기다리는 중 로그아웃을 시작하면 그 확인이 끝난 뒤에야 DELETE가 나가고, 로그아웃 뒤 PUT은 없다', () async {
    adapter.on('PUT', _tokensPath, FakeReply(204));
    adapter.on('DELETE', _tokensPath, FakeReply(204));
    adapter.on('POST', '/auth/logout', FakeReply(204));
    final push = GatedPush()..tokenGate = Completer<void>();
    await start(push);

    final f = auth.logout();
    await pumpEventQueue();
    push.tokenGate!.complete();
    await f;
    await pumpEventQueue();

    final o = order();
    expect(o.last, 'POST /api/auth/logout'); // 로그아웃 뒤에 나간 요청이 없다
    expect(o.indexOf('DELETE /api$_tokensPath'), greaterThan(o.indexOf('PUT /api$_tokensPath')));
  });
}
