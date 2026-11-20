// 앱 쿠키 보관(S23-c) 기본 확인: 로그인 응답의 쿠키가 보관되고, 다음 요청에 쿠키와 XSRF 헤더가 붙는지
import 'dart:io';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/api/app_cookies_io.dart';
import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';

void main() {
  test('Set-Cookie를 보관했다가 다음 요청에 Cookie와 X-XSRF-TOKEN으로 보낸다', () async {
    final adapter = FakeAdapter();
    final api = ApiClient(
      adapter: adapter,
      baseUrl: fakeBaseUrl,
      extraInterceptors: buildCookieInterceptors(CookieJar()),
    );
    adapter.on('GET', '/csrf', FakeReply(200, {}, null, {
      'set-cookie': ['XSRF-TOKEN=abc123; Path=/'],
    }));
    adapter.on('POST', '/posts', FakeReply(200, {}));

    await api.dio.get<dynamic>('/csrf');
    await api.dio.post<dynamic>('/posts');

    final call = adapter.callsTo('POST', '/posts').single;
    expect(call.headers['X-XSRF-TOKEN'], 'abc123');
    expect(call.headers['cookie'].toString(), contains('XSRF-TOKEN=abc123'));
  });

  // 테스트용 클라이언트 (메모리 보관함)
  (FakeAdapter, ApiClient) make() {
    final adapter = FakeAdapter();
    final api = ApiClient(
      adapter: adapter,
      baseUrl: 'http://localhost:8080/api', // 가짜 어댑터가 /api/... 키로 응답을 찾는다
      extraInterceptors: buildCookieInterceptors(CookieJar()),
    );
    return (adapter, api);
  }

  test('조건1: 받은 access_token이 다음 요청 Cookie에 실리고, 받은 적 없으면 Cookie 헤더가 없다', () async {
    final (adapter, api) = make();
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.get<dynamic>('/posts');
    expect(adapter.calls.single.headers['cookie'], isNull); // 키가 있어도 값이 null이면 실제로 안 보내진다

    adapter.on('POST', '/auth/login', FakeReply(200, {}, null, {
      'set-cookie': ['access_token=a; Path=/'],
    }));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.post<dynamic>('/auth/login');
    await api.dio.get<dynamic>('/posts');
    expect(adapter.callsTo('GET', '/posts').last.headers['cookie'].toString(), contains('access_token=a'));
  });

  test('조건2: XSRF 쿠키를 받으면 POST와 재발급 요청에 X-XSRF-TOKEN이 붙고, 없으면 안 붙는다', () async {
    final (adapter, api) = make();
    adapter.on('POST', '/posts', FakeReply(200, {}));
    await api.dio.post<dynamic>('/posts');
    expect(adapter.calls.single.headers.containsKey('X-XSRF-TOKEN'), isFalse);

    adapter.on('GET', '/csrf', FakeReply(200, {}, null, {
      'set-cookie': ['XSRF-TOKEN=t; Path=/'],
    }));
    adapter.on('POST', '/posts', FakeReply(200, {}));
    adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
    await api.dio.get<dynamic>('/csrf');
    await api.dio.post<dynamic>('/posts');
    expect(adapter.callsTo('POST', '/posts').last.headers['X-XSRF-TOKEN'], 't');
    // 재발급 전용 Dio(_refreshDio)도 같은 보관함을 쓴다: 앱이 직접 부를 수 없으니 401 흐름 대신 dio로 확인
    await api.dio.post<dynamic>('/auth/refresh');
    expect(adapter.callsTo('POST', '/auth/refresh').single.headers['X-XSRF-TOKEN'], 't');
  });

  test('조건2: 401 뒤 재발급 전용 요청에도 X-XSRF-TOKEN이 붙는다', () async {
    final (adapter, api) = make();
    adapter.on('GET', '/csrf', FakeReply(200, {}, null, {
      'set-cookie': ['XSRF-TOKEN=t; Path=/'],
    }));
    await api.dio.get<dynamic>('/csrf');
    adapter.on('GET', '/posts', FakeReply(401, {}));
    adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.get<dynamic>('/posts');
    expect(adapter.callsTo('POST', '/auth/refresh').single.headers['X-XSRF-TOKEN'], 't');
  });

  test('조건3: Path=/api/auth 인 refresh_token은 /api/auth/refresh에만 실린다', () async {
    final (adapter, api) = make();
    adapter.on('GET', '/authx', FakeReply(200, {}));
    adapter.on('POST', '/auth/login', FakeReply(200, {}, null, {
      'set-cookie': ['refresh_token=r; Path=/api/auth', 'access_token=a; Path=/'],
    }));
    adapter.on('POST', '/auth/refresh', FakeReply(200, {}));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.post<dynamic>('/auth/login');
    await api.dio.post<dynamic>('/auth/refresh');
    await api.dio.get<dynamic>('/posts');
    await api.dio.get<dynamic>('/authx'); // 이름만 비슷한 /api/authx 에는 실리면 안 된다
    expect(adapter.callsTo('POST', '/auth/refresh').single.headers['cookie'].toString(), contains('refresh_token=r'));
    expect(adapter.callsTo('GET', '/posts').single.headers['cookie'].toString(), isNot(contains('refresh_token')));
    expect(adapter.callsTo('GET', '/authx').single.headers['cookie'].toString(), isNot(contains('refresh_token')));
  });

  // 참고: api_client.dart가 재시도 직전에 Cookie 헤더를 지우는데, dio_cookie_manager 3.5.0도 같은 요청을
  // 재사용할 때 자기가 붙인 헤더를 새로 만든다. 지금은 둘 다 처리하지만 라이브러리 버전이 바뀌어도 안전하도록 이중 방어를 두고,
  // 이 테스트가 그 결과(옛 토큰이 안 섞임)를 계속 지켜 준다.
  test('조건4: 401 재시도 때 옛 access_token a가 아니라 새 b 하나만 실리고 XSRF도 최신이다', () async {
    final (adapter, api) = make();
    adapter.on('POST', '/auth/login', FakeReply(200, {}, null, {
      'set-cookie': ['access_token=a; Path=/', 'XSRF-TOKEN=old; Path=/', 'refresh_token=r; Path=/api/auth'],
    }));
    await api.dio.post<dynamic>('/auth/login');

    adapter.on('POST', '/posts', FakeReply(401, {}));
    adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(), null, {
      'set-cookie': ['access_token=b; Path=/', 'XSRF-TOKEN=new; Path=/'],
    }));
    adapter.on('POST', '/posts', FakeReply(200, {}));
    await api.dio.post<dynamic>('/posts');

    final retry = adapter.callsTo('POST', '/posts').last;
    final cookie = retry.headers['cookie'].toString();
    expect(cookie, contains('access_token=b'));
    expect(cookie, isNot(contains('access_token=a')));
    expect('access_token='.allMatches(cookie).length, 1);
    expect(retry.headers['X-XSRF-TOKEN'], 'new');
  });

  test('조건5: Max-Age=0 으로 지우면 다음 요청에 access_token이 없다', () async {
    final (adapter, api) = make();
    adapter.on('POST', '/auth/login', FakeReply(200, {}, null, {
      'set-cookie': ['access_token=a; Path=/'],
    }));
    adapter.on('POST', '/auth/logout', FakeReply(204, null, null, {
      'set-cookie': ['access_token=; Max-Age=0; Path=/'],
    }));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.post<dynamic>('/auth/login');
    await api.dio.post<dynamic>('/auth/logout');
    await api.dio.get<dynamic>('/posts');
    expect(adapter.callsTo('GET', '/posts').single.headers['cookie'].toString(), isNot(contains('access_token')));
  });

  test('조건7(관찰): 재발급이 401로 거절되며 쿠키 삭제를 보내면 보관함에서도 지워진다', () async {
    final (adapter, api) = make();
    adapter.on('POST', '/auth/login', FakeReply(200, {}, null, {
      'set-cookie': ['access_token=a; Path=/', 'refresh_token=r; Path=/api/auth'],
    }));
    await api.dio.post<dynamic>('/auth/login');
    adapter.on('GET', '/posts', FakeReply(401, {}));
    adapter.on('POST', '/auth/refresh', FakeReply(401, {}, null, {
      'set-cookie': ['access_token=; Max-Age=0; Path=/', 'refresh_token=; Max-Age=0; Path=/api/auth'],
    }));
    await expectLater(api.dio.get<dynamic>('/posts'), throwsA(isA<DioException>()));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.get<dynamic>('/posts');
    expect(adapter.callsTo('GET', '/posts').last.headers['cookie'], isNull);
  });

  test('조건7(관찰): 일반 요청의 401 응답에 실린 Set-Cookie는 저장되지 않는 현재 동작', () async {
    final (adapter, api) = make();
    adapter.on('GET', '/posts', FakeReply(401, {}, null, {
      'set-cookie': ['access_token=z; Path=/'],
    }));
    adapter.on('POST', '/auth/refresh', FakeReply(200, userJson()));
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.get<dynamic>('/posts');
    // 관찰 결과: 일반 요청의 401 응답 쿠키는 보관되지 않는다 (ApiClient가 먼저 처리해서).
    // 정확히는 "재시도가 성공할 때만" 저장 안 됨이다 (재발급이 실패하면 그 응답은 그대로 저장됨).
    // 실제 서버는 이런 응답을 안 줘서 지금은 문제 없음
    expect(adapter.callsTo('GET', '/posts').last.headers['cookie'], isNull);
  });

  test('앱을 껐다 켜도(같은 폴더로 새 보관함·새 클라이언트) 저장된 access_token이 다음 요청에 실린다', () async {
    // 진짜 파일 저장을 쓰려고 임시 폴더를 만든다
    final dir = await Directory.systemTemp.createTemp('cookie_test');
    try {
      // 첫 번째 "앱 실행": 로그인 응답의 쿠키가 파일에 저장된다
      final adapter1 = FakeAdapter();
      final api1 = ApiClient(
        adapter: adapter1,
        baseUrl: 'http://localhost:8080/api',
        extraInterceptors: buildCookieInterceptors(PersistCookieJar(storage: FileStorage(dir.path))),
      );
      adapter1.on('POST', '/auth/login', FakeReply(200, {}, null, {
        'set-cookie': ['access_token=saved; Path=/; Max-Age=3600'],
      }));
      await api1.dio.post<dynamic>('/auth/login');

      // 두 번째 "앱 실행": 같은 폴더로 보관함을 새로 만들면 쿠키를 파일에서 읽어 온다
      final adapter2 = FakeAdapter();
      final api2 = ApiClient(
        adapter: adapter2,
        baseUrl: 'http://localhost:8080/api',
        extraInterceptors: buildCookieInterceptors(PersistCookieJar(storage: FileStorage(dir.path))),
      );
      adapter2.on('GET', '/posts', FakeReply(200, {}));
      await api2.dio.get<dynamic>('/posts');
      expect(adapter2.calls.single.headers['cookie'].toString(), contains('access_token=saved'));
    } finally {
      await dir.delete(recursive: true); // 임시 폴더 정리
    }
  });

  test('보관된 쿠키가 없으면 X-XSRF-TOKEN을 넣지 않는다', () async {
    final adapter = FakeAdapter();
    final api = ApiClient(
      adapter: adapter,
      baseUrl: fakeBaseUrl,
      extraInterceptors: buildCookieInterceptors(CookieJar()),
    );
    adapter.on('GET', '/posts', FakeReply(200, {}));
    await api.dio.get<dynamic>('/posts');
    expect(adapter.calls.single.headers.containsKey('X-XSRF-TOKEN'), isFalse);
  });
}
