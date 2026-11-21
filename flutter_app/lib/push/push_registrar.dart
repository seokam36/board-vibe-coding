// 기기 토큰을 서버에 등록·삭제하는 순수 로직 (S24-d). Firebase를 모른다: ApiClient·AuthState·AppPush 모양만 쓴다.
// 규칙:
//  - 로그인 상태이고 알림 권한이 "이미 허용"일 때만 토큰을 서버에 등록한다 (PUT /users/me/device-tokens).
//  - 권한 팝업은 여기서 절대 띄우지 않는다 (S24-e의 버튼에서만).
//  - 로그아웃 때는 /auth/logout 보다 먼저 이 기기 토큰을 서버에서 지운다 (AuthState.beforeLogout).
//  - 토큰 값은 로그에 남기지 않는다.
import 'dart:async';

import 'package:dio/dio.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import 'app_push.dart';

class PushRegistrar {
  PushRegistrar({
    required this._api,
    required this._auth,
    required this._push,
    this._callLimit = const Duration(seconds: 10), // 시험에서만 짧게 줄인다
  });

  final ApiClient _api;
  final AuthState _auth;
  final AppPush _push;

  // 응답이 없으면 무한정 기다리지 않게 보내기·받기 각 10초 제한 (AuthState의 /auth/logout과 같은 방식)
  static final Options _limit = Options(
    sendTimeout: const Duration(seconds: 10),
    receiveTimeout: const Duration(seconds: 10),
  );
  // 위 Options는 연결 단계(서버가 안 닿을 때)를 못 막아서, 호출 전체에도 10초 제한을 건다 (큐·[알림 켜기]가 안 멈추게)
  final Duration _callLimit;

  StreamSubscription<String>? _refreshSub;
  bool _started = false;
  bool _disposed = false;
  bool _ready = false; // 푸시 준비(init)가 성공했는지
  int? _lastUserId; // 직전에 본 로그인 회원 (로그인·회원 바뀜을 알아채려고)
  String? _registeredToken; // 이번 로그인 동안 서버에 등록해 둔 마지막 토큰 (같은 토큰 반복 PUT 방지)
  Future<void> _queue = Future.value(); // 확인 작업을 한 줄로 세워 겹치지 않게 한다
  final Completer<bool> _initDone = Completer<bool>(); // 푸시 준비가 끝났는지(성공 여부) — 알림 칸이 기다린다

  // 앱 시작 때 한 번: 로그인 변화 구독 → 푸시 준비 → (이미 로그인돼 있으면) 바로 한 번 확인
  Future<void> start() async {
    if (_started) return;
    _started = true;
    _auth.beforeLogout = _unregisterBeforeLogout;
    _auth.addListener(_onAuthChanged);
    var ready = false;
    try {
      ready = await _push.init();
    } catch (_) {
      ready = false; // 준비가 실패해도 앱은 그대로, 푸시만 꺼진 채로 돈다
    }
    if (!_initDone.isCompleted) _initDone.complete(ready && !_disposed);
    if (_disposed || !ready) return;
    _ready = true;
    // 토큰이 새로 바뀌면(로그인 상태+허용일 때) 다시 등록한다
    _refreshSub = _push.onTokenRefresh.listen((_) => _enqueueCheck(), onError: (Object _) {});
    // 준비되는 동안 로그인했을 수도 있으니, 지금 로그인돼 있으면 한 번 확인한다
    _lastUserId = _auth.user?.id;
    if (_auth.user != null) _enqueueCheck();
  }

  void dispose() {
    _disposed = true;
    if (!_initDone.isCompleted) _initDone.complete(false);
    _refreshSub?.cancel();
    _auth.removeListener(_onAuthChanged);
    _auth.beforeLogout = null;
  }

  // 로그인 상태가 바뀔 때마다 불린다. "로그인됨(또는 다른 회원)"으로 바뀐 순간에만 확인한다
  void _onAuthChanged() {
    final id = _auth.user?.id;
    if (id == _lastUserId) return; // 닉네임 변경 같은 다른 변화는 무시
    _lastUserId = id;
    _registeredToken = null; // 로그아웃·다른 회원이면 등록 기억을 비운다
    if (id != null && _ready) _enqueueCheck();
  }

  Future<void> _enqueueCheck() {
    _queue = _queue.then((_) => _checkAndRegister()).catchError((Object _) {});
    return _queue;
  }

  // ---- S24-e: 비자 일정 화면의 "휴대폰 알림" 칸이 쓰는 입구 ----

  // 지금 알림 권한 상태 (팝업 없음). 푸시를 못 쓰는 기기(준비 실패)면 null
  Future<PushPermission?> readPermission() async {
    if (!await _initDone.future || _disposed) return null;
    try {
      return await _push.permission();
    } catch (_) {
      return null;
    }
  }

  // [알림 켜기] 버튼: 권한 팝업을 한 번 띄운다. 허용이면 바로 서버 등록까지 하고, 결과 권한을 돌려준다
  Future<PushPermission?> askPermission() async {
    if (!await _initDone.future || _disposed) return null;
    try {
      final result = await _push.requestPermission();
      if (result == PushPermission.granted) await registerNow();
      return result;
    } catch (_) {
      return null;
    }
  }

  // 지금 바로 서버 등록을 확인한다. 기존 규칙 그대로(로그인 상태·허용일 때만, 같은 토큰은 한 번, 한 줄로 순서대로)
  Future<void> registerNow() => _ready ? _enqueueCheck() : Future.value();

  Future<void> _checkAndRegister() async {
    if (_disposed || _auth.user == null) return;
    // 권한이 "허용"일 때만. 허용이 아니면 요청도 안 하고 끝 (팝업 띄우기는 S24-e 몫)
    if (await _push.permission() != PushPermission.granted) return;
    final token = await _push.getToken();
    if (token == null || token.isEmpty) return;
    if (_disposed || _auth.user == null) return; // 기다리는 사이 로그아웃했으면 등록하지 않는다
    if (token == _registeredToken) return; // 이미 등록한 같은 토큰
    final userId = _auth.user?.id; // PUT 보내는 동안 회원이 바뀌었는지 보려고 잡아 둔다
    try {
      await _api.dio
          .put<dynamic>('/users/me/device-tokens', data: {'token': token}, options: _limit)
          .timeout(_callLimit); // 시간 초과 예외도 아래 catch에서 조용히 삼킨다
      // 그 사이 로그아웃·다른 회원이면 기억하지 않는다 (새 회원이 같은 토큰으로 PUT을 건너뛰지 않게)
      if (_auth.user?.id == userId) _registeredToken = token;
    } catch (_) {
      // 실패하면 다음 기회(다음 로그인·토큰 갱신·앱 시작)에 다시 한다. 오류 내용에는 토큰이 있을 수 있어 남기지 않는다
    }
  }

  // 로그아웃 직전: 서버에서 이 기기 토큰을 지운다. 전체 시간 제한은 AuthState.logout이 건다. 실패해도 로그아웃은 계속되도록 모든 오류를 삼킨다
  Future<void> _unregisterBeforeLogout() async {
    if (!_ready) return; // 푸시가 꺼져 있으면 지울 토큰도 없다
    final userId = _auth.user?.id; // 시작할 때 "누구의 로그아웃인지" 잡아 둔다
    if (userId == null) return;
    try {
      await _queue; // 등록 작업이 진행 중이면 끝난 뒤에 (등록이 삭제 뒤에 나가는 일을 막는다)
      final token = await _push.getToken();
      // 5초 제한을 넘겨 늦게 여기까지 왔을 때, 그 사이 로그아웃이 끝났거나 다른 회원이 로그인했으면
      // 지금 쿠키는 그 회원 것이라 남의 토큰을 지울 수 있다 -> 같은 회원일 때만 보낸다
      if (token != null && token.isNotEmpty && _auth.user?.id == userId) {
        await _api.dio
            .delete<dynamic>('/users/me/device-tokens', data: {'token': token}, options: _limit)
            .timeout(_callLimit);
        // 같은 회원일 때만 기억을 비운다 (늦은 A 정리가 새로 로그인한 B의 기억을 지우지 않게)
        _registeredToken = null;
      }
    } catch (_) {
      // 일부러 무시: 토큰 정리가 안 돼도 로그아웃은 막지 않는다
    }
  }
}
