// S24-d 검사: 앱의 기기 토큰 등록·삭제(PushRegistrar)와 로그아웃 순서.
// 진짜 Firebase 대신 가짜 AppPush를, 진짜 서버 대신 FakeAdapter를 끼운다. 토큰은 지어낸 글자만 쓴다.
import 'dart:async';

import 'package:board_web/app.dart';
import 'package:board_web/push/app_push.dart';
import 'package:board_web/push/app_push_io.dart';
import 'package:board_web/push/push_registrar.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _tokensPath = '/users/me/device-tokens';

// 가짜 푸시 도구: 권한·토큰을 마음대로 정하고, 무엇이 몇 번 불렸는지 센다
class FakePush implements AppPush {
  FakePush({this.permissionValue = PushPermission.granted, this.token = 'fake-token-1', this.initOk = true, this.initThrows = false});

  PushPermission permissionValue;
  String? token;
  final bool initOk;
  final bool initThrows;
  int requestPermissionCalls = 0;
  final refresh = StreamController<String>.broadcast();

  @override
  Future<bool> init() async {
    if (initThrows) throw StateError('init 실패');
    return initOk;
  }

  @override
  Future<PushPermission> permission() async => permissionValue;

  @override
  Future<PushPermission> requestPermission() async {
    requestPermissionCalls++;
    return permissionValue;
  }

  @override
  Future<String?> getToken() async => token;

  @override
  Stream<String> get onTokenRefresh => refresh.stream;
}

void main() {
  late FakeAdapter adapter;
  late LoadedAuthState auth;

  setUp(() {
    adapter = FakeAdapter();
    auth = LoadedAuthState(adapter: adapter, user: userJson());
    addTearDown(auth.dispose);
  });

  // 등록 담당을 만들어 시작한다 (끝나면 정리)
  Future<PushRegistrar> startRegistrar(FakePush push, {LoadedAuthState? forAuth}) async {
    final a = forAuth ?? auth;
    final registrar = PushRegistrar(api: a.api, auth: a, push: push);
    addTearDown(registrar.dispose);
    await registrar.start();
    await pumpEventQueue();
    return registrar;
  }

  List<Object?> bodies() {
    final list = <Object?>[];
    adapter.onRequestBody = list.add;
    return list;
  }

  group('등록(PUT)', () {
    test('이미 로그인 + 권한 허용 → PUT 1번, 본문 token이 기기 토큰과 같다', () async {
      final sent = bodies();
      adapter.on('PUT', _tokensPath, FakeReply(204));
      final push = FakePush();

      await startRegistrar(push);

      expect(adapter.count('PUT', _tokensPath), 1);
      expect(sent.single, {'token': 'fake-token-1'});
      expect(push.requestPermissionCalls, 0);
    });

    test('권한이 허용이 아니면(거부·미결정) PUT 0번, 권한 요청도 0번', () async {
      for (final p in [PushPermission.denied, PushPermission.notDetermined]) {
        final push = FakePush(permissionValue: p);
        await startRegistrar(push);
        expect(push.requestPermissionCalls, 0);
      }
      expect(adapter.count('PUT', _tokensPath), 0);
    });

    test('비로그인이면 PUT 0번, 로그인한 순간 1번', () async {
      final guest = LoadedAuthState(adapter: adapter);
      addTearDown(guest.dispose);
      adapter.on('PUT', _tokensPath, FakeReply(204));
      await startRegistrar(FakePush(), forAuth: guest);
      expect(adapter.count('PUT', _tokensPath), 0);

      guest.updateUser(auth.user!); // 로그인 성공 직후와 같은 변화
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 1);
    });

    test('같은 토큰을 반복해서 확인해도 추가 PUT이 없다 (닉네임 변경 같은 변화 포함)', () async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      final push = FakePush();
      await startRegistrar(push);

      auth.updateUser(auth.user!); // 같은 회원 정보가 다시 들어와도
      push.refresh.add('fake-token-1'); // 같은 토큰이 갱신으로 또 와도
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 1);
    });

    test('토큰이 갱신되면 PUT 1번 더, 본문은 새 토큰', () async {
      final sent = bodies();
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('PUT', _tokensPath, FakeReply(204));
      final push = FakePush();
      await startRegistrar(push);

      push.token = 'fake-token-2';
      push.refresh.add('fake-token-2');
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 2);
      expect(sent.last, {'token': 'fake-token-2'});
    });

    test('토큰 갱신이 와도 로그아웃 상태면 PUT이 없다', () async {
      final guest = LoadedAuthState(adapter: adapter);
      addTearDown(guest.dispose);
      final push = FakePush();
      await startRegistrar(push, forAuth: guest);

      push.refresh.add('fake-token-9');
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 0);
    });

    test('PUT이 실패해도 앱은 멀쩡하고, 다음 확인(토큰 갱신)에 다시 시도한다', () async {
      adapter.on('PUT', _tokensPath, FakeReply(500));
      adapter.on('PUT', _tokensPath, FakeReply(204));
      final push = FakePush();
      await startRegistrar(push);
      expect(adapter.count('PUT', _tokensPath), 1);

      push.refresh.add('fake-token-1');
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 2);
    });
  });

  group('로그아웃', () {
    test('DELETE가 /auth/logout보다 먼저 나가고, 본문에 지금 토큰이 있다', () async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('DELETE', _tokensPath, FakeReply(204));
      adapter.on('POST', '/auth/logout', FakeReply(204));
      final push = FakePush();
      await startRegistrar(push);
      final sent = bodies();

      await auth.logout();

      final order = adapter.calls.map((c) => '${c.method} ${c.path}').toList();
      expect(order.indexOf('DELETE /api$_tokensPath'), lessThan(order.indexOf('POST /api/auth/logout')));
      expect(sent.first, {'token': 'fake-token-1'});
      expect(auth.user, isNull);
    });

    test('DELETE가 500이어도 /auth/logout은 나가고 로그인 상태가 비워진다', () async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('DELETE', _tokensPath, FakeReply(500));
      adapter.on('POST', '/auth/logout', FakeReply(204));
      await startRegistrar(FakePush());

      await auth.logout();

      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(auth.user, isNull);
    });

    test('DELETE가 연결 실패여도 /auth/logout은 나가고 로그인 상태가 비워진다', () async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('DELETE', _tokensPath, FakeReply.networkError());
      adapter.on('POST', '/auth/logout', FakeReply(204));
      await startRegistrar(FakePush());

      await auth.logout();

      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(auth.user, isNull);
    });

    test('토큰을 얻을 수 없으면 DELETE는 생략하고 로그아웃은 그대로 된다', () async {
      adapter.on('POST', '/auth/logout', FakeReply(204));
      await startRegistrar(FakePush(token: null));

      await auth.logout();

      expect(adapter.count('DELETE', _tokensPath), 0);
      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(auth.user, isNull);
    });

    test('로그아웃 뒤 다시 로그인하면 같은 토큰이어도 PUT을 다시 한다', () async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('PUT', _tokensPath, FakeReply(204));
      adapter.on('DELETE', _tokensPath, FakeReply(204));
      adapter.on('POST', '/auth/logout', FakeReply(204));
      final user = auth.user!;
      await startRegistrar(FakePush());

      await auth.logout();
      auth.updateUser(user);
      await pumpEventQueue();

      expect(adapter.count('PUT', _tokensPath), 2);
    });
  });

  group('푸시가 없거나 못 켜는 경우', () {
    test('Firebase 준비(init)가 false여도 요청 0건, 로그아웃은 정상', () async {
      adapter.on('POST', '/auth/logout', FakeReply(204));
      await startRegistrar(FakePush(initOk: false));

      await auth.logout();

      expect(adapter.count('PUT', _tokensPath), 0);
      expect(adapter.count('DELETE', _tokensPath), 0);
      expect(auth.user, isNull);
    });

    test('init이 예외를 던져도 앱은 죽지 않고 요청 0건', () async {
      adapter.on('POST', '/auth/logout', FakeReply(204));
      await startRegistrar(FakePush(initThrows: true));

      await auth.logout();

      expect(adapter.calls.where((c) => c.path.contains('device-tokens')), isEmpty);
      expect(adapter.count('POST', '/auth/logout'), 1);
    });

    test('진짜 Firebase 구현은 플러그인이 없는 환경에서도 안전하다 (init false, 나머지는 기본값)', () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      final push = FirebaseAppPush();

      expect(await push.init(), isFalse);
      expect(await push.permission(), PushPermission.denied);
      expect(await push.getToken(), isNull);
    });
  });

  group('BoardApp 연결', () {
    testWidgets('push를 안 끼우면(웹·기존) device-tokens 요청이 0건', (tester) async {
      final app = await pumpApp(tester, user: userJson());
      expect(app.adapter.calls.where((c) => c.path.contains('device-tokens')), isEmpty);
    });

    testWidgets('push를 끼운 앱 + 로그인 + 허용 → PUT 1번', (tester) async {
      adapter.on('PUT', _tokensPath, FakeReply(204));
      final push = FakePush();
      await tester.pumpWidget(BoardApp(authState: auth, push: push));
      await tester.pumpAndSettle();

      expect(adapter.count('PUT', _tokensPath), 1);
      expect(push.requestPermissionCalls, 0);
    });

    testWidgets('init이 실패하는 푸시를 끼워도 화면은 정상', (tester) async {
      await tester.pumpWidget(BoardApp(authState: auth, push: FakePush(initThrows: true)));
      await tester.pumpAndSettle();

      expect(find.text('비자 후기'), findsOneWidget); // 상단바가 그려졌다
      expect(adapter.calls.where((c) => c.path.contains('device-tokens')), isEmpty);
    });
  });
}
