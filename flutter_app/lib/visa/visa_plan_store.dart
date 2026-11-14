import 'package:flutter/foundation.dart';

import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import 'visa_plan.dart';

// 보관소 상태: idle = 대상 아님(비로그인·미인증), loading = 모름(불러오는 중), ready = 일정 있음/없음, failed = 불러오기 실패
enum VisaPlanStatus { idle, loading, ready, failed }

// 내 비자 일정을 앱 전체에서 한 번만 불러와 나눠 주는 보관소 (React의 VisaPlanProvider).
// 상단 띠(i2)와 /visa-plan 화면이 같이 쓴다. 학교 인증 회원일 때만 GET을 부르고, 화면을 옮겨 다녀도 다시 부르지 않는다.
// 자동 재시도는 없다: 실패하면 reload()를 부를 때까지 failed로 둔다.
class VisaPlanStore extends ChangeNotifier {
  // now: "서울 오늘"을 정할 때 쓰는 지금 시각. 테스트에서 고정하려고 받는다(없으면 진짜 지금).
  VisaPlanStore({required this._auth, DateTime Function()? now}) : _now = now ?? DateTime.now {
    _userId = _currentUserId();
    _blank();
    _auth.addListener(_onAuthChanged); // 로그인·로그아웃·학교 인증 완료를 지켜본다
    if (_userId != null) _load(_userId!);
  }

  final AuthState _auth;
  final DateTime Function() _now;

  int? _userId; // 이 상태가 누구 것인지 (회원이 바뀌면 비운다). null = 대상 아님
  VisaPlanStatus _status = VisaPlanStatus.idle;
  VisaPlan? _plan;
  String? _errorMessage;
  bool _needLogin = false;
  String? _loadedDay;
  int? _inFlight; // 지금 요청 중인 회원 (같은 요청을 두 번 보내지 않으려고)
  int _gen = 0; // 세대 번호: GET 시작·setPlan마다 올라간다
  bool _disposed = false;

  VisaPlanStatus get status => _status;
  // ready일 때만 값이 있다 (일정 없음도 expiryDate가 null인 객체)
  VisaPlan? get plan => _plan;
  // failed일 때 화면에 보여 줄 문구 (401이면 null)
  String? get errorMessage => _errorMessage;
  // failed 이유가 401(재발급까지 실패)인지
  bool get needLogin => _needLogin;
  // 이 일정을 받은 서울 날짜(yyyy-MM-dd). 자정을 넘겼는지 알아보는 데 쓴다
  String? get loadedDay => _loadedDay;

  // 서울 기준 오늘 (화면도 같은 시계를 쓰도록 여기서 준다)
  String today() => seoulToday(now: _now());

  // 보관소의 대상 회원: 학교 인증을 마친 회원만 id가 있다
  int? _currentUserId() {
    final user = _auth.user;
    return user?.verifiedSchool != null ? user!.id : null;
  }

  // 새 회원(또는 대상 아님)의 빈 상태로 되돌린다. 알림은 부르는 쪽이 한다
  void _blank() {
    _status = _userId == null ? VisaPlanStatus.idle : VisaPlanStatus.loading;
    _plan = null;
    _errorMessage = null;
    _needLogin = false;
    _loadedDay = null;
  }

  // 로그인 상태가 바뀔 때마다 불린다. 대상 회원이 달라졌을 때만 비우고 다시 부른다 (닉네임 변경 등은 무시)
  void _onAuthChanged() {
    final id = _currentUserId();
    if (id == _userId) return;
    _userId = id;
    _blank();
    _notify();
    if (id != null) _load(id);
  }

  // GET 한 번. 답이 늦게 와도 그사이 회원이 바뀌었으면 버린다
  Future<void> _load(int id) async {
    if (_inFlight == id) return;
    _inFlight = id;
    final gen = ++_gen; // 이 요청의 세대 번호. 그사이 setPlan이 오면 번호가 달라져 늦은 응답을 버린다
    // 상태를 바꾸기 전에 "요청 중" 표시부터 지운다 (그 사이 reload가 와도 막히지 않게)
    void done() {
      if (_inFlight == id && _gen == gen) _inFlight = null;
    }

    try {
      final res = await _auth.api.dio.get<Map<String, dynamic>>('/users/me/visa-plan');
      done();
      if (_gen != gen) return; // 요청 중에 저장·삭제 결과(setPlan)가 들어왔다: 더 새 값이므로 덮지 않는다
      _apply(id, () {
        _status = VisaPlanStatus.ready;
        _plan = VisaPlan.fromJson(res.data!);
        _loadedDay = today();
      });
    } catch (e) {
      done();
      if (_gen != gen) return; // 실패 응답도 마찬가지로 더 새 값을 덮지 않는다
      final info = describeError(e, const {}, '일정을 불러오지 못했습니다');
      _apply(id, () {
        _status = VisaPlanStatus.failed;
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _errorMessage = info.message;
        }
      });
    }
  }

  // 같은 회원의 응답일 때만 상태에 반영한다
  void _apply(int id, void Function() change) {
    if (_userId != id) return;
    change();
    _notify();
  }

  // 저장·삭제 성공 뒤 화면 쪽에서 보관소를 바로 갱신한다 (띠도 같이 바뀐다). 대상이 아니면(로그인 풀림 등) 무시
  void setPlan(VisaPlan plan) {
    if (_userId == null) return;
    _gen++; // 날아가는 중인 GET의 응답은 이 값보다 오래된 것이니 버리게 한다
    _inFlight = null; // 이후 reload가 막히지 않게
    _status = VisaPlanStatus.ready;
    _plan = plan;
    _errorMessage = null;
    _needLogin = false;
    _loadedDay = today();
    _notify();
  }

  // 다시 불러오기 (실패 상태에서 /visa-plan에 들어왔을 때 등). 이미 요청 중이면 새로 보내지 않는다
  void reload() {
    final id = _userId;
    if (id == null) return;
    _status = VisaPlanStatus.loading;
    _errorMessage = null;
    _needLogin = false;
    _notify();
    _load(id);
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
