// S16-d1 서버 통로 테스트: XSRF 헤더 붙이기, 401 자동 재발급·재시도, 동시 401 한 번만 재발급을 본다.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';

void main() {
  late FakeAdapter adapter;
  late FakeCookies cookies;
  late ApiClient client;
  late List<AuthUser> refreshed;
  late int expiredCount;

  setUp(() {
    adapter = FakeAdapter();
    cookies = FakeCookies();
    refreshed = [];
    expiredCount = 0;
    client = ApiClient(adapter: adapter, cookieReader: cookies, baseUrl: fakeBaseUrl)
      ..onRefreshed = refreshed.add
      ..onExpired = () => expiredCount++;
  });

  // 재발급 요청이 가짜 서버에 도착할 때까지(= 첫 요청은 이미 옛 쿠키로 나감) 기다리는 도우미
  Future<void> untilRefreshStarted() async {
    for (var i = 0; i < 100 && adapter.count('POST', '/auth/refresh') == 0; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(adapter.count('POST', '/auth/refresh'), 1, reason: '재발급 요청이 시작돼야 한다');
  }

  // 요청이 실패하길 기대하고 그 에러를 돌려주는 도우미
  Future<DioException> failure(Future<Object?> request) async {
    try {
      await request;
    } on DioException catch (e) {
      return e;
    }
    fail('실패해야 하는 요청이 성공했다');
  }

  group('XSRF 헤더', () {
    test('쿠키가 있으면 X-XSRF-TOKEN 헤더로 붙는다', () async {
      cookies.values['XSRF-TOKEN'] = 'abc';
      adapter.on('GET', '/ping', FakeReply(200));
      await client.dio.get<dynamic>('/ping');
      expect(adapter.callsTo('GET', '/ping').single.headers['X-XSRF-TOKEN'], 'abc');
    });

    test('쿠키가 없으면 헤더도 없다', () async {
      adapter.on('GET', '/ping', FakeReply(200));
      await client.dio.get<dynamic>('/ping');
      expect(adapter.callsTo('GET', '/ping').single.headers.containsKey('X-XSRF-TOKEN'), isFalse);
    });

    test('쿠키 값은 요청을 보내는 시점에 읽는다 (바뀌면 새 값)', () async {
      adapter.on('GET', '/ping', FakeReply(200));
      adapter.on('GET', '/ping', FakeReply(200));
      cookies.values['XSRF-TOKEN'] = 'first';
      await client.dio.get<dynamic>('/ping');
      cookies.values['XSRF-TOKEN'] = 'second';
      await client.dio.get<dynamic>('/ping');
      final calls = adapter.callsTo('GET', '/ping');
      expect(calls[0].headers['X-XSRF-TOKEN'], 'first');
      expect(calls[1].headers['X-XSRF-TOKEN'], 'second');
    });

    test('재발급 요청에도 헤더가 붙는다', () async {
      cookies.values['XSRF-TOKEN'] = 'tok';
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
      adapter.on('GET', '/me', FakeReply(200));
      await client.dio.get<dynamic>('/me');
      expect(adapter.callsTo('POST', '/auth/refresh').single.headers['X-XSRF-TOKEN'], 'tok');
    });

    test('재발급 뒤 재시도 요청은 그 시점의 새 쿠키 값을 쓴다 (옛 헤더가 남지 않음)', () async {
      // 재발급 응답을 붙잡아 둔 사이에 쿠키가 바뀌는 상황(서버가 새 쿠키를 준 것처럼)
      final gate = Completer<void>();
      cookies.values['XSRF-TOKEN'] = 'old';
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(), gate));
      adapter.on('GET', '/me', FakeReply(200));

      final pending = client.dio.get<dynamic>('/me');
      await untilRefreshStarted();
      cookies.values['XSRF-TOKEN'] = 'new';
      gate.complete();
      await pending;

      final calls = adapter.callsTo('GET', '/me');
      expect(calls[0].headers['X-XSRF-TOKEN'], 'old');
      expect(calls[1].headers['X-XSRF-TOKEN'], 'new');
    });

    test('재시도 시점에 쿠키가 없어졌으면 옛 헤더도 빠진다', () async {
      final gate = Completer<void>();
      cookies.values['XSRF-TOKEN'] = 'old';
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(), gate));
      adapter.on('GET', '/me', FakeReply(200));

      final pending = client.dio.get<dynamic>('/me');
      await untilRefreshStarted();
      cookies.values.remove('XSRF-TOKEN');
      gate.complete();
      await pending;

      expect(adapter.callsTo('GET', '/me')[1].headers.containsKey('X-XSRF-TOKEN'), isFalse);
    });
  });

  group('401 자동 재발급', () {
    test('401 → 재발급 → 원래 요청 재시도 성공, onRefreshed에 사용자 정보', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(nickname: '새닉')));
      adapter.on('GET', '/me', FakeReply(200, {'ok': true}));

      final res = await client.dio.get<Map<String, dynamic>>('/me');

      expect(res.statusCode, 200);
      expect(res.data, {'ok': true});
      expect(adapter.count('GET', '/me'), 2);
      expect(adapter.count('POST', '/auth/refresh'), 1);
      expect(refreshed.single.nickname, '새닉');
      expect(expiredCount, 0);
    });

    test('재시도도 401이면 그 401로 실패하고 재발급은 1번만', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
      adapter.on('GET', '/me', FakeReply(401));

      final e = await failure(client.dio.get<dynamic>('/me'));

      expect(e.response?.statusCode, 401);
      expect(adapter.count('POST', '/auth/refresh'), 1);
      expect(adapter.count('GET', '/me'), 2);
    });

    test('재시도가 다른 에러(500)면 그 에러로 실패', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
      adapter.on('GET', '/me', FakeReply(500));

      final e = await failure(client.dio.get<dynamic>('/me'));
      expect(e.response?.statusCode, 500);
    });

    for (final (method, path) in [
      ('POST', '/auth/login'),
      ('POST', '/auth/signup'),
      ('POST', '/auth/logout'),
      ('POST', '/auth/refresh'),
    ]) {
      test('제외 경로 $path 의 401은 재발급을 부르지 않는다', () async {
        adapter.on(method, path, FakeReply(401));
        final e = await failure(client.dio.post<dynamic>(path));
        expect(e.response?.statusCode, 401);
        // 제외 경로가 /auth/refresh 일 때도 "추가" 재발급 호출이 없어야 한다(원래 호출 1번뿐)
        expect(adapter.count('POST', '/auth/refresh'), path == '/auth/refresh' ? 1 : 0);
        expect(expiredCount, 0);
      });
    }

    test('제외 경로 판단은 쿼리를 뗀 뒤 끝부분 비교 (/auth/me는 제외가 아님)', () async {
      adapter.on('POST', '/auth/login', FakeReply(401));
      final e = await failure(client.dio.post<dynamic>('/auth/login?x=1'));
      expect(e.response?.statusCode, 401);
      expect(adapter.count('POST', '/auth/refresh'), 0);

      adapter.on('GET', '/auth/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
      adapter.on('GET', '/auth/me', FakeReply(200, userJson()));
      await client.dio.get<dynamic>('/auth/me');
      expect(adapter.count('POST', '/auth/refresh'), 1);
    });

    test('동시에 401 두 개가 나도 재발급은 정확히 1번, 둘 다 성공', () async {
      final gate = Completer<void>();
      adapter.on('GET', '/a', FakeReply(401));
      adapter.on('GET', '/b', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(), gate));
      adapter.on('GET', '/a', FakeReply(200, {'name': 'a'}));
      adapter.on('GET', '/b', FakeReply(200, {'name': 'b'}));

      final fa = client.dio.get<Map<String, dynamic>>('/a');
      final fb = client.dio.get<Map<String, dynamic>>('/b');
      // 재발급 응답이 붙잡혀 있는 동안 두 요청의 401이 모두 도착해 기다리게 한다
      await pumpEventQueue();
      expect(adapter.count('GET', '/a'), 1);
      expect(adapter.count('GET', '/b'), 1);
      expect(adapter.count('POST', '/auth/refresh'), 1);

      gate.complete();
      final results = await Future.wait([fa, fb]);

      expect(results[0].data, {'name': 'a'});
      expect(results[1].data, {'name': 'b'});
      expect(adapter.count('POST', '/auth/refresh'), 1);
      expect(refreshed, hasLength(1));
    });

    test('재발급이 끝나면 비워져서, 다음 만료 때는 다시 재발급한다', () async {
      for (var i = 0; i < 2; i++) {
        adapter.on('GET', '/me', FakeReply(401));
        adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
        adapter.on('GET', '/me', FakeReply(200));
        await client.dio.get<dynamic>('/me');
      }
      expect(adapter.count('POST', '/auth/refresh'), 2);
    });

    test('재발급이 401이면 onExpired 1번 + 원래 401로 실패', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401));

      final e = await failure(client.dio.get<dynamic>('/me'));

      expect(e.requestOptions.path, '/me');
      expect(e.response?.statusCode, 401);
      expect(expiredCount, 1);
      expect(adapter.count('GET', '/me'), 1); // 재시도 안 함
    });

    test('동시 401 두 개 + 재발급 401이면 onExpired는 1번', () async {
      final gate = Completer<void>();
      adapter.on('GET', '/a', FakeReply(401));
      adapter.on('GET', '/b', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401, null, gate));

      final fa = failure(client.dio.get<dynamic>('/a'));
      final fb = failure(client.dio.get<dynamic>('/b'));
      await pumpEventQueue();
      gate.complete();
      final errors = await Future.wait([fa, fb]);

      expect(errors.map((e) => e.response?.statusCode), [401, 401]);
      expect(expiredCount, 1);
      expect(adapter.count('POST', '/auth/refresh'), 1);
    });

    test('재발급이 500이면 onExpired 0번 + 재발급의 500 에러를 전달', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(500));

      final e = await failure(client.dio.get<dynamic>('/me'));

      expect(e.response?.statusCode, 500);
      expect(e.requestOptions.path, '/auth/refresh');
      expect(expiredCount, 0);
    });

    test('재발급이 네트워크 오류면 onExpired 0번 + 응답 없는 에러를 전달', () async {
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply.networkError());

      final e = await failure(client.dio.get<dynamic>('/me'));

      expect(e.response, isNull);
      expect(expiredCount, 0);
    });

    test('401이 아닌 에러는 재발급 없이 그대로 실패', () async {
      adapter.on('GET', '/me', FakeReply(404));
      final e = await failure(client.dio.get<dynamic>('/me'));
      expect(e.response?.statusCode, 404);
      expect(adapter.count('POST', '/auth/refresh'), 0);
    });
  });
}
