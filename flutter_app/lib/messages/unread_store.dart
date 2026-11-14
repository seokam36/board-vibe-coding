import 'package:flutter/foundation.dart';

import '../auth/auth_state.dart';

// 안 읽은 쪽지 수를 앱 전체에서 한 번만 들고 있다가 상단바에 나눠 주는 보관소 (React의 UnreadProvider).
// 로그인했을 때만 GET을 부른다(비로그인 401이 토큰 재발급을 돌리지 않게). 로그아웃하면 0으로 되돌린다.
// 회원이 바뀔 때(로그인 직후 포함)와 화면 경로가 바뀔 때 다시 받아 온다(실시간은 아님, 쿼리만 바뀌면 안 부른다).
class UnreadStore extends ChangeNotifier {
  UnreadStore({required this._auth}) {
    _userId = _auth.user?.id;
    _auth.addListener(_onAuthChanged);
    refresh(); // 이미 로그인한 채로 시작하면 바로 한 번 받는다 (비로그인이면 아무 일도 안 한다)
  }

  final AuthState _auth;

  int? _userId; // 이 숫자가 누구 것인지. null = 비로그인
  int _count = 0;
  int _seq = 0; // 요청 번호: 여러 번 겹쳐 불러도 가장 마지막에 보낸 응답만 반영한다
  String? _lastPath; // 마지막으로 본 화면 경로 (null = 아직 못 봄)
  bool _disposed = false;

  // 안 읽은 쪽지 수 (비로그인이면 항상 0)
  int get count => _count;

  // 로그인 상태가 바뀔 때마다 불린다. 회원이 달라졌을 때만 0으로 비우고 다시 받는다 (닉네임 변경 등은 무시)
  void _onAuthChanged() {
    final id = _auth.user?.id;
    if (id == _userId) return;
    _userId = id;
    _seq++; // 날아가는 중인 이전 회원의 응답은 버리게 한다
    _count = 0; // 이전 회원의 숫자가 잠깐이라도 보이지 않게
    _notify();
    refresh();
  }

  // 화면 경로가 바뀌었을 때 라우터 쪽(app.dart)이 불러 준다. 경로가 이전과 같으면(쿼리만 바뀜) 무시한다.
  // 맨 처음 본 경로는 기록만 한다 (시작 때의 숫자는 위 생성자·로그인 변화가 받아 온다)
  void onPathChanged(String path) {
    final prev = _lastPath;
    _lastPath = path;
    if (prev == null || prev == path) return;
    refresh();
  }

  // 지금 숫자를 서버에서 다시 받는다. 실패하면 이전 숫자를 그대로 둔다 (숫자는 부가 표시라 조용히)
  Future<void> refresh() async {
    final id = _userId;
    if (id == null) return; // 로그인 안 했으면 부르지 않는다
    final mine = ++_seq;
    try {
      final res = await _auth.api.dio.get<Map<String, dynamic>>('/conversations/unread-count');
      if (mine != _seq || _userId != id) return; // 더 새 요청이 있거나 회원이 바뀌었으니 버린다
      final n = res.data?['count'];
      if (n is! int) return;
      if (n != _count) {
        _count = n;
        _notify();
      }
    } catch (_) {
      // 실패해도 이전 숫자 유지
    }
  }

  // dispose된 뒤에 늦게 끝난 요청이 알림을 보내면 에러가 나서, 막아 둔다
  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _auth.removeListener(_onAuthChanged);
    super.dispose();
  }
}
