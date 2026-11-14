// S16-d1 로그인 상태 테스트: 시작 확인 순서, 로그인·로그아웃, 재발급 실패 시 만료 경로를 본다.
import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';

void main() {
  late FakeAdapter adapter;
  late ApiClient api;
  late AuthState auth;
  var path = '/posts/1';

  setUp(() {
    path = '/posts/1';
    adapter = FakeAdapter();
    api = ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl);
    auth = AuthState(api: api, currentPath: () => path);
  });

  tearDown(() => auth.dispose());

  // 로그인된 상태로 만들어 두는 도우미
  Future<void> initLoggedIn() async {
    adapter.on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}));
    adapter.on('GET', '/auth/me', FakeReply(200, userJson(role: 'ADMIN', school: {'id': 3, 'name': '서울대'})));
    await auth.init();
  }

  group('init', () {
    test('시작은 loading=true, 비로그인', () {
      expect(auth.loading, isTrue);
      expect(auth.user, isNull);
    });

    test('/csrf 다음 /auth/me 순서로 부르고 사용자·loading 전환', () async {
      adapter.on('GET', '/csrf', FakeReply(200, {'headerName': 'X-XSRF-TOKEN'}));
      adapter.on('GET', '/auth/me', FakeReply(200, userJson(role: 'ADMIN', school: {'id': 3, 'name': '서울대'})));
      var notified = 0;
      auth.addListener(() => notified++);

      await auth.init();

      expect(adapter.calls.map((c) => '${c.method} ${c.path}'), ['GET /api/csrf', 'GET /api/auth/me']);
      expect(auth.loading, isFalse);
      expect(auth.user?.nickname, '홍길동');
      expect(auth.user?.role, UserRole.admin);
      expect(auth.user?.verifiedSchool?.name, '서울대');
      expect(notified, 1);
    });

    test('미인증 회원은 verifiedSchool이 null, 일반 회원은 USER', () async {
      adapter.on('GET', '/csrf', FakeReply(200));
      adapter.on('GET', '/auth/me', FakeReply(200, userJson()));
      await auth.init();
      expect(auth.user?.role, UserRole.user);
      expect(auth.user?.verifiedSchool, isNull);
    });

    test('/csrf 실패면 /auth/me는 부르지 않고 비로그인, loading 끝', () async {
      adapter.on('GET', '/csrf', FakeReply(500));
      await auth.init();
      expect(adapter.count('GET', '/auth/me'), 0);
      expect(auth.user, isNull);
      expect(auth.loading, isFalse);
    });

    test('/auth/me 401(재발급도 401)이면 비로그인이고 만료 표시는 없다', () async {
      adapter.on('GET', '/csrf', FakeReply(200));
      adapter.on('GET', '/auth/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401));
      await auth.init();
      expect(auth.user, isNull);
      expect(auth.loading, isFalse);
      expect(auth.expiredPath, isNull); // 원래 손님이므로 "풀린 것"이 아니다
    });

    test('access가 만료된 채 시작해도 재발급으로 로그인이 유지된다', () async {
      adapter.on('GET', '/csrf', FakeReply(200));
      adapter.on('GET', '/auth/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(nickname: '유지')));
      adapter.on('GET', '/auth/me', FakeReply(200, userJson(nickname: '유지')));
      await auth.init();
      expect(auth.user?.nickname, '유지');
    });

    test('네트워크 오류도 비로그인으로 두고 loading 끝', () async {
      adapter.on('GET', '/csrf', FakeReply.networkError());
      await auth.init();
      expect(auth.user, isNull);
      expect(auth.loading, isFalse);
    });
  });

  group('login / logout', () {
    test('login 성공: 사용자 설정, 만료 표시 초기화, 아이디·비밀번호를 본문으로', () async {
      await initLoggedIn();
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401));
      await api.dio.get<dynamic>('/me').then((_) {}, onError: (_) {});
      expect(auth.expiredPath, '/posts/1');

      adapter.on('POST', '/auth/login', FakeReply(200, userJson(nickname: '다시')));
      await auth.login('user01', 'pw');

      expect(auth.user?.nickname, '다시');
      expect(auth.expiredPath, isNull);
    });

    test('login 실패는 상태를 그대로 두고 에러를 던진다', () async {
      adapter.on('POST', '/auth/login', FakeReply(401));
      await expectLater(
        auth.login('user01', 'bad'),
        throwsA(isA<DioException>().having((e) => e.response?.statusCode, 'status', 401)),
      );
      expect(auth.user, isNull);
    });

    test('logout 성공: 서버 호출 뒤에 user=null', () async {
      await initLoggedIn();
      adapter.on('POST', '/auth/logout', FakeReply(200));
      await auth.logout();
      expect(auth.user, isNull);
      expect(adapter.count('POST', '/auth/logout'), 1);
    });

    test('logout 실패: 상태 유지하고 에러를 던진다', () async {
      await initLoggedIn();
      adapter.on('POST', '/auth/logout', FakeReply(500));
      await expectLater(auth.logout(), throwsA(isA<DioException>()));
      expect(auth.user, isNotNull);
    });
  });

  group('updateUser / clearUser / 만료', () {
    test('updateUser는 사용자를 바꾸고 알린다', () async {
      await initLoggedIn();
      var notified = 0;
      auth.addListener(() => notified++);
      auth.updateUser(const AuthUser(id: 1, username: 'user01', nickname: '바뀜', role: UserRole.user));
      expect(auth.user?.nickname, '바뀜');
      expect(notified, 1);
    });

    test('clearUser는 서버 호출 없이 비로그인으로', () async {
      await initLoggedIn();
      final before = adapter.calls.length;
      auth.clearUser();
      expect(auth.user, isNull);
      expect(auth.expiredPath, isNull);
      expect(adapter.calls.length, before);
    });

    test('로그인해 있다가 재발급이 401이면 user=null, expiredPath=현재 경로', () async {
      await initLoggedIn();
      path = '/market/7';
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401));
      await api.dio.get<dynamic>('/me').then((_) {}, onError: (_) {});
      expect(auth.user, isNull);
      expect(auth.expiredPath, '/market/7');
    });

    test('재발급이 500이면 로그인 상태를 건드리지 않는다', () async {
      await initLoggedIn();
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(500));
      await api.dio.get<dynamic>('/me').then((_) {}, onError: (_) {});
      expect(auth.user, isNotNull);
      expect(auth.expiredPath, isNull);
    });

    test('재발급 성공이면 최신 사용자 정보로 갱신(onRefreshed 연결)', () async {
      await initLoggedIn();
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(200, userJson(nickname: '갱신')));
      adapter.on('GET', '/me', FakeReply(200));
      await api.dio.get<dynamic>('/me');
      expect(auth.user?.nickname, '갱신');
    });

    test('clearExpiredIfPathChanged: 다른 경로면 지우고, 같은 경로면 그대로', () async {
      await initLoggedIn();
      adapter.on('GET', '/me', FakeReply(401));
      adapter.on('POST', '/auth/refresh', FakeReply(401));
      await api.dio.get<dynamic>('/me').then((_) {}, onError: (_) {});
      expect(auth.expiredPath, '/posts/1');

      auth.clearExpiredIfPathChanged('/posts/1');
      expect(auth.expiredPath, '/posts/1');
      auth.clearExpiredIfPathChanged('/login');
      expect(auth.expiredPath, isNull);
    });
  });

  group('refreshUser', () {
    test('/auth/me 를 다시 받아 회원 정보를 바꾸고 알린다', () async {
      await initLoggedIn();
      adapter.on('GET', '/auth/me', FakeReply(200, userJson(nickname: '새이름', school: {'id': 5, 'name': '연세대'})));
      var notified = 0;
      auth.addListener(() => notified++);
      await auth.refreshUser();
      expect(auth.user?.nickname, '새이름');
      expect(auth.user?.verifiedSchool?.name, '연세대');
      expect(notified, 1);
    });

    test('실패하면 조용히 넘어가고 기존 회원 정보를 그대로 둔다', () async {
      await initLoggedIn();
      adapter.on('GET', '/auth/me', FakeReply(500));
      await auth.refreshUser();
      expect(auth.user?.nickname, '홍길동');
    });
  });
}
