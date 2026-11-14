// S16-i1 비자 일정 보관소(VisaPlanStore) 테스트: 언제 GET을 부르는지, 회원이 바뀔 때·늦은 응답·setPlan 규칙을 본다.
// (React의 VisaPlanProvider와 같은 동작.) 화면 없이 보관소만 만든다. 시계는 서울 2026-11-10으로 고정.
import 'dart:async';

import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/visa/visa_plan.dart';
import 'package:board_web/visa/visa_plan_store.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';
import 'support/visa_plan_json.dart';

const _path = '/users/me/visa-plan';
const _today = '2026-11-10';

// 서울 2026-11-10 낮 (UTC 03:00)
DateTime _now() => DateTime.utc(2026, 11, 10, 3);

Map<String, dynamic> _verified({int id = 1}) => userJson(id: id, school: visaSeoul);

AuthUser _asUser(Map<String, dynamic> json) => AuthUser.fromJson(json);

// 보관소를 만들고, 끝나면 정리한다
VisaPlanStore _store(LoadedAuthState auth) {
  final store = VisaPlanStore(auth: auth, now: _now);
  addTearDown(auth.dispose);
  addTearDown(store.dispose);
  return store;
}

void main() {
  group('언제 GET을 부르나', () {
    test('비로그인이면 부르지 않고 idle', () async {
      final auth = LoadedAuthState();
      final store = _store(auth);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.idle);
      expect(store.plan, isNull);
      expect(auth.adapter.calls, isEmpty);
    });

    test('학교 미인증 회원이면 부르지 않고 idle', () async {
      final auth = LoadedAuthState(user: userJson());
      final store = _store(auth);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.idle);
      expect(auth.adapter.count('GET', _path), 0);
    });

    test('인증 회원이면 만들 때 1번 불러와 ready, loadedDay는 서울 오늘', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson()));
      final auth = LoadedAuthState(adapter: adapter, user: _verified());
      final store = _store(auth);
      expect(store.status, VisaPlanStatus.loading); // 답이 오기 전
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(store.plan!.expiryDate, '2027-03-01');
      expect(store.plan!.daysLeft, 111);
      expect(store.plan!.stage, 'PREPARE');
      expect(store.plan!.todos, hasLength(3));
      expect(store.loadedDay, _today);
      expect(adapter.count('GET', _path), 1);
    });

    test('일정이 없어도 ready이고 plan.hasPlan이 false', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, emptyPlanJson));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(store.plan!.hasPlan, isFalse);
      expect(store.plan!.todos, isEmpty);
    });

    test('미인증 → 학교 인증 완료로 회원 정보가 바뀌면 그때 1번 부른다', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson()));
      final auth = LoadedAuthState(adapter: adapter, user: userJson());
      final store = _store(auth);
      await pumpEventQueue();
      expect(adapter.count('GET', _path), 0);
      auth.updateUser(_asUser(_verified()));
      expect(store.status, VisaPlanStatus.loading);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(adapter.count('GET', _path), 1);
    });

    test('같은 회원의 닉네임만 바뀌면 다시 부르지 않는다', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson()));
      final auth = LoadedAuthState(adapter: adapter, user: _verified());
      final store = _store(auth);
      await pumpEventQueue();
      final plan = store.plan;
      auth.updateUser(_asUser(userJson(id: 1, nickname: '새이름', school: visaSeoul)));
      await pumpEventQueue();
      expect(adapter.count('GET', _path), 1);
      expect(store.status, VisaPlanStatus.ready);
      expect(identical(store.plan, plan), isTrue);
    });
  });

  group('실패와 다시 불러오기', () {
    test('500이면 failed + 문구, 자동 재시도는 없고 reload()를 불러야 다시 간다', () async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(500))
        ..on('GET', _path, FakeReply(200, planJson()));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.failed);
      expect(store.errorMessage, '일정을 불러오지 못했습니다');
      expect(store.needLogin, isFalse);
      expect(store.plan, isNull);
      await pumpEventQueue(); // 시간이 지나도 두 번째 GET은 안 나간다
      expect(adapter.count('GET', _path), 1);

      store.reload();
      expect(store.status, VisaPlanStatus.loading);
      expect(store.errorMessage, isNull);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(adapter.count('GET', _path), 2);
    });

    test('네트워크 오류는 공용 문구', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply.networkError());
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.failed);
      expect(store.errorMessage, '서버에 연결할 수 없습니다');
    });

    test('401이고 재발급은 됐는데 다시 보내도 401이면 failed + needLogin (문구 없음)', () async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, _verified()))
        ..on('GET', _path, FakeReply(401));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.failed);
      expect(store.needLogin, isTrue);
      expect(store.errorMessage, isNull);
    });

    test('401이고 재발급도 401이면 로그인이 풀려 보관소가 비워진다 (idle)', () async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final auth = LoadedAuthState(adapter: adapter, user: _verified());
      final store = _store(auth);
      await pumpEventQueue();
      expect(auth.user, isNull);
      expect(store.status, VisaPlanStatus.idle);
      expect(store.plan, isNull);
    });

    test('reload()는 이미 요청 중이면 새로 보내지 않는다', () async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson(), gate));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      store.reload(); // 첫 요청이 아직 안 끝났다
      store.reload();
      await pumpEventQueue();
      expect(adapter.count('GET', _path), 1);
      gate.complete();
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(adapter.count('GET', _path), 1);
    });

    test('대상이 아닐 때(비로그인) reload()는 아무것도 안 한다', () async {
      final auth = LoadedAuthState();
      final store = _store(auth);
      store.reload();
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.idle);
      expect(auth.adapter.calls, isEmpty);
    });
  });

  group('회원이 바뀔 때와 늦은 응답', () {
    test('로그아웃하면 상태를 비우고, 늦게 온 응답은 버린다', () async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson(), gate));
      final auth = LoadedAuthState(adapter: adapter, user: _verified());
      final store = _store(auth);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.loading);
      auth.clearUser(); // 응답이 오기 전에 로그아웃
      expect(store.status, VisaPlanStatus.idle);
      gate.complete(); // 이제 옛 회원의 응답이 도착
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.idle);
      expect(store.plan, isNull);
      expect(store.loadedDay, isNull);
    });

    test('회원이 바뀌면 옛 일정을 비우고 새 회원 것을 부른다. 옛 회원의 늦은 응답은 버린다', () async {
      final gateA = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(200, planJson(), gateA)) // 회원 1의 응답 (늦게 온다)
        ..on('GET', _path, FakeReply(200, farPlanJson())); // 회원 2의 응답
      final auth = LoadedAuthState(adapter: adapter, user: _verified(id: 1));
      final store = _store(auth);
      await pumpEventQueue();
      auth.updateUser(_asUser(userJson(id: 2, school: visaBusan)));
      expect(store.status, VisaPlanStatus.loading);
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.ready);
      expect(store.plan!.daysLeft, 201); // 회원 2의 일정
      gateA.complete();
      await pumpEventQueue();
      expect(store.plan!.daysLeft, 201); // 회원 1의 늦은 응답이 덮어쓰지 못한다
      expect(adapter.count('GET', _path), 2);
    });

    test('받아 둔 일정이 있어도 다른 회원이 되면 바로 비운다', () async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(200, planJson()))
        ..on('GET', _path, FakeReply(200, farPlanJson(), gate));
      final auth = LoadedAuthState(adapter: adapter, user: _verified(id: 1));
      final store = _store(auth);
      await pumpEventQueue();
      expect(store.plan, isNotNull);
      auth.updateUser(_asUser(userJson(id: 2, school: visaBusan)));
      expect(store.status, VisaPlanStatus.loading);
      expect(store.plan, isNull);
      expect(store.loadedDay, isNull);
      gate.complete();
      await pumpEventQueue();
    });

    test('보관소가 정리된 뒤에 늦은 응답이 와도 에러가 나지 않는다', () async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson(), gate));
      final auth = LoadedAuthState(adapter: adapter, user: _verified());
      final store = VisaPlanStore(auth: auth, now: _now);
      addTearDown(auth.dispose);
      await pumpEventQueue();
      store.dispose();
      gate.complete();
      await pumpEventQueue(); // notifyListeners 에러 없이 끝나야 한다
    });
  });

  group('setPlan', () {
    test('인증 회원이면 일정을 바꾸고 ready로 만든다 (실패 문구도 지운다)', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(500));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      expect(store.status, VisaPlanStatus.failed);
      store.setPlan(VisaPlan.fromJson(planJson()));
      expect(store.status, VisaPlanStatus.ready);
      expect(store.plan!.daysLeft, 111);
      expect(store.errorMessage, isNull);
      expect(store.needLogin, isFalse);
      expect(store.loadedDay, _today);
    });

    test('대상이 아니면(미인증·비로그인·로그인 풀림) 무시한다', () async {
      final auth = LoadedAuthState(user: userJson());
      final store = _store(auth);
      store.setPlan(VisaPlan.fromJson(planJson()));
      expect(store.status, VisaPlanStatus.idle);
      expect(store.plan, isNull);

      // 로그인이 풀린 뒤에도 마찬가지
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson()));
      final auth2 = LoadedAuthState(adapter: adapter, user: _verified());
      final store2 = _store(auth2);
      await pumpEventQueue();
      auth2.clearUser();
      store2.setPlan(VisaPlan.fromJson(farPlanJson()));
      expect(store2.status, VisaPlanStatus.idle);
      expect(store2.plan, isNull);
    });

    test('바뀔 때마다 구독자에게 알린다', () async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson()));
      final store = _store(LoadedAuthState(adapter: adapter, user: _verified()));
      await pumpEventQueue();
      var notified = 0;
      store.addListener(() => notified++);
      store.setPlan(VisaPlan.empty);
      expect(notified, 1);
      expect(store.plan!.hasPlan, isFalse);
    });
  });

  test('today()는 주입한 시계의 서울 날짜 (UTC 15시 이후는 다음 날)', () {
    final auth = LoadedAuthState();
    addTearDown(auth.dispose);
    final store = VisaPlanStore(auth: auth, now: () => DateTime.utc(2026, 11, 10, 15, 30));
    addTearDown(store.dispose);
    expect(store.today(), '2026-11-11');
  });
}
