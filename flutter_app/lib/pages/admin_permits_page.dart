import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../admin/permit_approve_form.dart';
import '../admin/permit_decision_form.dart';
import '../admin/permit_models.dart';
import '../admin/report_meta_row.dart';
import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../permit/permit_reject_reasons.dart' show permitRejectReasons;
import '../posts/models.dart' show School;
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/small_button_style.dart';

const _listFailedMessage = '인증 신청 목록을 불러오지 못했습니다';
const _photoFailedMessage = '사진을 불러오지 못했어요';
const _photoGoneMessage = '사진이 없어요(이미 처리되었을 수 있어요)';
const _photoDecodeFailed = '사진을 표시할 수 없어요';
const _requestTimeout = Duration(seconds: 30); // 요청마다 거는 응답 제한
const _forbiddenMessage = '처리하지 못했어요. 새로고침 후 다시 시도해 주세요';
const _goneMessage = '이미 사라진 신청이에요 (철회했거나 탈퇴했을 수 있어요)';
const _alreadyHandledMessage = '이미 처리된 신청이에요';
const _timeoutMessage = '처리됐는지 확인하지 못했어요. 목록에서 확인해 주세요'; // 재조회도 실패할 수 있어 "다시 불러왔어요"라고 단정하지 않는다
const _ownRequestMessage = '본인 신청은 직접 심사할 수 없어요';
const _refreshFailedMessage = '목록을 다시 불러오지 못했어요'; // 처리 뒤 재조회 실패: 기존 목록은 그대로 둔다
// 처리 실패 기본 문구(상태 코드별). 호출한 쪽이 넘긴 문구가 같은 코드를 덮어쓴다
const _failMessages = {403: _forbiddenMessage, 404: _goneMessage, 409: _alreadyHandledMessage};

// 탭: (서버 status 값, 탭 글자, 빈 목록 안내)
const _tabs = [
  ('PENDING', '대기', '대기 중인 신청이 없어요'),
  ('APPROVED', '승인', '승인된 신청이 없어요'),
  ('REJECTED', '반려', '반려된 신청이 없어요'),
];

// 관리자 "인증 심사" 화면 "/admin/permits": 대기/승인/반려 탭 + 신청 목록 + 사진 보기.
// 대기 탭 줄에는 [승인]·[반려] 버튼이 있다. 관리자가 아니면 안내만 보여 주고 API는 부르지 않는다.
class AdminPermitsPage extends StatefulWidget {
  const AdminPermitsPage({super.key});

  @override
  State<AdminPermitsPage> createState() => _AdminPermitsPageState();
}

class _AdminPermitsPageState extends State<AdminPermitsPage> {
  String _status = 'PENDING';
  List<AdminPermitRequest>? _items; // null이면 아직 못 불러온 상태
  String? _loadError;
  bool _needLogin = false;
  bool _started = false;
  int _seq = 0; // 마지막 요청의 응답만 반영한다
  bool _busy = false; // 처리 요청·목록 재조회 중 연타 방지
  String? _actionError; // 처리 실패 안내
  int? _actionErrorFor; // 그 안내가 난 신청 id (그 줄이 아직 있으면 안내는 줄의 양식 안에만 보여 준다)
  String? _notice; // 처리 성공 안내 (탭을 바꾸거나 다음 처리를 시작할 때 지운다)
  String? _refreshError; // 처리 뒤 재조회 실패 안내 (목록은 지우지 않고 그대로 둔다)
  // 학교 목록(승인 양식용): 화면당 한 번만 불러온다
  List<School>? _schools;
  bool _schoolsFailed = false;
  bool _schoolsLoading = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 관리자가 되는 순간 첫 조회. 관리자가 아니면 요청을 보내지 않는다
    final admin = Provider.of<AuthState>(context).user?.isAdmin ?? false;
    if (admin && !_started) {
      _started = true;
      _fetch();
    } else if (!admin) {
      _started = false;
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  // 승인 양식을 처음 열 때 부른다. 이미 불러왔거나 불러오는 중이면 다시 보내지 않는다 (실패 뒤 [다시 시도]는 새로 보낸다)
  Future<void> _loadSchools() async {
    if (_schools != null || _schoolsLoading) return;
    setState(() {
      _schoolsLoading = true;
      _schoolsFailed = false;
    });
    try {
      final res = await context.read<ApiClient>().dio.get<List<dynamic>>(
        '/schools',
        options: Options(receiveTimeout: _requestTimeout), // 응답이 없으면 실패로 보고 [다시 시도]를 보여 준다
      );
      if (!mounted) return;
      setState(() {
        _schools = [for (final e in res.data!) School.fromJson(e as Map<String, dynamic>)];
        _schoolsLoading = false;
      });
    } catch (e) {
      debugPrint('학교 목록 불러오기 실패: $e');
      if (!mounted) return;
      setState(() {
        _schoolsLoading = false;
        _schoolsFailed = true;
      });
    }
  }

  // 목록을 읽는다. 성공하면 true.
  // keepOnFail이 true(처리 뒤 재조회·사진 404 재조회)면 실패해도 기존 목록을 지우지 않고 안내만 띄운다
  Future<bool> _fetch({bool keepOnFail = false}) async {
    final seq = ++_seq;
    try {
      final res = await context.read<ApiClient>().dio.get<List<dynamic>>(
        '/admin/permit-requests',
        queryParameters: {'status': _status},
        options: Options(receiveTimeout: _requestTimeout),
      );
      if (!mounted || seq != _seq) return false; // 더 새 요청에 밀렸으면 이 결과는 믿을 수 없다
      final list = [for (final e in res.data!) AdminPermitRequest.fromJson(e as Map<String, dynamic>)];
      setState(() {
        _items = list;
        _loadError = null;
        _refreshError = null;
        _needLogin = false;
      });
      return true;
    } catch (e) {
      debugPrint('인증 신청 목록 불러오기 실패: $e');
      if (!mounted || seq != _seq) return false;
      final info = describeError(e, {403: ErrorMessages.adminOnly}, _listFailedMessage);
      setState(() {
        if (keepOnFail && _items != null) {
          // 기존 목록(그리고 줄에 쳐 둔 입력)을 지키고 안내만 띄운다
          if (info is NeedLogin) {
            _needLogin = true;
          } else {
            _refreshError = _refreshFailedMessage;
          }
          return;
        }
        _items = null;
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _loadError = info.message;
        }
      });
      return false;
    }
  }

  void _changeTab(String next) {
    if (next == _status) return;
    setState(() {
      _status = next;
      _items = null;
      _loadError = null;
      _needLogin = false; // 이전 탭의 로그인 안내가 남아 빈 화면이 되지 않게 한다
      _refreshError = null;
      _actionError = null;
      _actionErrorFor = null;
      _notice = null;
    });
    _fetch();
  }

  // 사진이 404라 신청이 사라졌을 수 있다: 목록을 다시 읽어 맞춘다
  void _onPhotoGone() {
    if (mounted) _fetch(keepOnFail: true);
  }

  // 처리(승인·반려) 시작: 이전 안내를 지우고 버튼을 막는다
  void _startAction() {
    setState(() {
      _busy = true;
      _actionError = null;
      _actionErrorFor = null;
      _refreshError = null;
      _notice = null;
      _needLogin = false;
    });
  }

  // 처리 실패 공통 안내. 404/409/시간 초과는 처리 결과를 알 수 없거나 이미 바뀐 것이라 목록을 다시 읽는다.
  // 안내 문구는 _actionError 한 군데에만 둔다 (줄이 남아 있으면 줄의 양식이, 사라졌으면 페이지 위쪽이 보여 준다)
  Future<void> _handleFail(
    Object err,
    Map<int, String> messages, {
    required int requestId,
    String? conflictIfStillListed,
  }) async {
    debugPrint('인증 신청 처리 실패: $err');
    if (!mounted) return;
    // 시간 초과는 응답이 없어 네트워크 오류로 분류되므로 먼저 따로 본다 (서버가 처리했을 수도 있다)
    if (isTimeoutError(err)) {
      setState(() {
        _actionError = _timeoutMessage;
        _actionErrorFor = requestId;
      });
      await _fetch(keepOnFail: true);
      return;
    }
    final info = describeError(
      err,
      {..._failMessages, ...messages},
      ErrorMessages.actionFailed,
    );
    if (info is NeedLogin) {
      setState(() => _needLogin = true);
      return;
    }
    if (info is! ErrorMessage) return;
    var message = info.message;
    if (info.status == 404 || info.status == 409) {
      final refreshed = await _fetch(keepOnFail: true);
      if (!mounted) return;
      // 409는 이유가 둘이다: 신청이 목록에서 사라졌으면 이미 처리된 것, 아직 남아 있으면 다른 이유(중복 등)
      final stillListed = refreshed && (_items?.any((e) => e.id == requestId) ?? false);
      if (info.status == 409 && stillListed && conflictIfStillListed != null) message = conflictIfStillListed;
    }
    setState(() {
      _actionError = message;
      _actionErrorFor = requestId;
    });
  }

  // 줄이 양식을 닫거나 다른 양식을 열 때 부른다: 옛 안내가 나중에 페이지 위쪽에 튀어나오지 않게 지운다
  void _clearActionError() {
    if (_actionError == null) return;
    setState(() {
      _actionError = null;
      _actionErrorFor = null;
    });
  }

  // 승인·반려 공통 뼈대: 확인 창 → PUT → 성공 안내 → 목록 재조회(끝날 때까지 _busy 유지).
  // 성공 여부를 돌려준다 (줄이 성공이면 양식을 닫는다). 실패 문구는 _actionError에 남는다
  Future<bool> _decide({
    required int requestId,
    required String confirmMessage,
    required String confirmLabel,
    required bool danger,
    required String path,
    required Map<String, dynamic> body,
    required String doneNotice,
    required Map<int, String> messages, // 상태 코드별로 기본 문구를 덮어쓸 문구
    String? conflictIfStillListed, // 409인데 재조회 뒤에도 신청이 목록에 있을 때 쓸 문구
  }) async {
    if (_busy) return false;
    final ok = await showConfirm(context, message: confirmMessage, confirmLabel: confirmLabel, danger: danger);
    if (!ok || !mounted || _busy) return false;
    _startAction();
    var success = false;
    try {
      await context.read<ApiClient>().dio.put<dynamic>(
        path,
        data: body,
        options: Options(receiveTimeout: _requestTimeout), // 공용 dio엔 시간 제한이 없어 요청마다 건다
      );
      success = true;
      if (!mounted) return success;
      setState(() => _notice = doneNotice);
      await _fetch(keepOnFail: true); // 목록을 다시 읽을 때까지 버튼을 계속 막는다. 실패해도 목록은 지우지 않고 안내만
    } catch (err) {
      await _handleFail(err, messages, requestId: requestId, conflictIfStillListed: conflictIfStillListed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    return success;
  }

  Future<bool> _reject(AdminPermitRequest item, String reason) => _decide(
    requestId: item.id,
    confirmMessage: '${item.nickname} 님의 신청을 반려할까요?',
    confirmLabel: '반려',
    danger: true,
    path: '/admin/permit-requests/${item.id}/reject',
    body: {'reason': reason},
    doneNotice: '반려했어요',
    messages: {400: '반려 사유를 확인해 주세요'},
  );

  // 승인: 허가서 번호는 로그에 남기지 않는다(요청 본문에만 쓴다)
  Future<bool> _approve(AdminPermitRequest item, int schoolId, String permitNumber) {
    final schoolName = _schools?.where((s) => s.id == schoolId).firstOrNull?.name ?? '선택한 학교';
    return _decide(
      requestId: item.id,
      confirmMessage: '${item.nickname} 님을 $schoolName 유학생으로 승인할까요? 사진은 바로 지워져요',
      confirmLabel: '승인',
      danger: false,
      path: '/admin/permit-requests/${item.id}/approve',
      body: {'schoolId': schoolId, 'permitNumber': permitNumber},
      doneNotice: '승인했어요',
      messages: {400: '허가서 번호를 확인해 주세요 (글자·숫자, 최대 50자)', 404: '신청이나 학교를 찾을 수 없어요'},
      conflictIfStillListed: '승인하지 못했어요. 이미 인증된 회원이거나 이미 승인된 허가서 번호예요. 반려(중복)로 정리해 주세요',
    );
  }

  // 사진 요청이 401(재발급도 실패)이라 로그아웃되어 이 줄이 사라진 경우에만 화면이 대신 로그인 안내를 켠다
  void _onPhotoNeedLogin() {
    if (mounted) setState(() => _needLogin = true);
  }

  void _retry() {
    setState(() => _loadError = null);
    _fetch();
  }

  @override
  Widget build(BuildContext context) {
    final admin = context.watch<AuthState>().user?.isAdmin ?? false;
    if (!admin) {
      return PageFrame(
        child: _needLogin
            ? const ReloginNotice()
            : const Notice(kind: NoticeKind.error, message: ErrorMessages.adminOnly),
      );
    }
    final items = _items;
    final loadError = _loadError;
    final emptyText = _tabs.firstWhere((t) => t.$1 == _status).$3;
    final myId = context.watch<AuthState>().user?.id; // 본인 신청은 직접 심사할 수 없다
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('인증 심사'),
          Container(
            margin: const EdgeInsets.only(bottom: AppSizes.space4),
            decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
            child: Wrap(
              spacing: AppSizes.space4,
              children: [
                for (final t in _tabs)
                  _PermitTab(
                    label: t.$2,
                    selected: _status == t.$1,
                    enabled: !_busy, // 처리 중에는 탭도 막는다
                    onTap: () => _changeTab(t.$1),
                  ),
              ],
            ),
          ),
          if (_notice != null) Notice(kind: NoticeKind.success, message: _notice),
          // 그 신청 줄이 아직 목록에 있으면 안내는 줄의 양식 안에 보이므로 여기선 숨긴다 (줄이 사라진 경우만 위에 보여 준다)
          if (_actionError != null && !(items?.any((e) => e.id == _actionErrorFor) ?? false))
            Notice(kind: NoticeKind.error, message: _actionError),
          if (_refreshError != null) Notice(kind: NoticeKind.error, message: _refreshError),
          if (loadError != null)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  Text(loadError),
                  // 권한 문제(403)는 다시 해도 같으니 버튼을 숨긴다
                  if (loadError != ErrorMessages.adminOnly)
                    OutlinedButton(style: smallButtonStyle, onPressed: _retry, child: const Text('다시 시도')),
                ],
              ),
            ),
          if (items == null && loadError == null && !_needLogin) const Loading(),
          if (items != null)
            FlushPanel(
              children: [
                if (items.isEmpty) PanelState(child: Text(emptyText, style: const TextStyle(color: AppColors.muted))),
                for (var i = 0; i < items.length; i++)
                  _PermitRow(
                    key: ValueKey('permit-${items[i].id}'),
                    item: items[i],
                    first: i == 0,
                    myId: myId,
                    enabled: !_busy,
                    onReject: _reject,
                    onApprove: _approve,
                    error: _actionErrorFor == items[i].id ? _actionError : null,
                    onClearError: _clearActionError,
                    schools: _schools,
                    schoolsFailed: _schoolsFailed,
                    onOpenApprove: _loadSchools,
                    onNeedLogin: _onPhotoNeedLogin,
                    onPhotoGone: _onPhotoGone,
                  ),
              ],
            ),
          if (_needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}

// 탭 하나 (신고 관리 화면 탭과 같은 모양)
class _PermitTab extends StatelessWidget {
  const _PermitTab({required this.label, required this.selected, required this.enabled, required this.onTap});

  final String label;
  final bool selected;
  final bool enabled; // false면 눌러도 반응하지 않는다
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.celadon : AppColors.muted;
    return Semantics(
      button: true,
      selected: selected,
      child: InkWell(
        onTap: enabled ? onTap : null,
        child: Container(
          constraints: const BoxConstraints(minHeight: 40),
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space1),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: selected ? AppColors.celadon : Colors.transparent, width: 2)),
          ),
          child: Center(
            widthFactor: 1,
            child: Text(
              label,
              style: TextStyle(
                fontSize: AppSizes.fsBody,
                color: color,
                fontWeight: selected ? FontWeight.w500 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// 신청 한 줄: 닉네임(+자동 배지) / 메타 / [사진 보기] / 펼친 사진
class _PermitRow extends StatefulWidget {
  const _PermitRow({
    super.key,
    required this.item,
    required this.first,
    required this.myId,
    required this.enabled,
    required this.onReject,
    required this.onApprove,
    required this.error,
    required this.onClearError,
    required this.schools,
    required this.schoolsFailed,
    required this.onOpenApprove,
    required this.onNeedLogin,
    required this.onPhotoGone,
  });

  final AdminPermitRequest item;
  final bool first;
  final int? myId; // 로그인한 관리자 id
  final bool enabled; // 처리 중이 아닐 때만 처리 버튼을 누를 수 있다
  final Future<bool> Function(AdminPermitRequest item, String reason) onReject; // 성공하면 true
  final Future<bool> Function(AdminPermitRequest item, int schoolId, String permitNumber) onApprove;
  final String? error; // 이 줄의 처리 실패 안내 (부모가 한 군데에 들고 있다가 내려 준다)
  final VoidCallback onClearError; // 양식을 닫거나 다른 양식을 열 때 부모의 안내를 지운다
  final List<School>? schools; // 승인 양식의 학교 목록 (화면이 한 번만 불러온다)
  final bool schoolsFailed;
  final VoidCallback onOpenApprove; // 승인 양식을 열 때 학교 목록 불러오기를 요청한다 (이미 있으면 아무 일 없음)
  final VoidCallback onPhotoGone; // 사진이 404면 부모가 목록을 다시 읽게 알린다
  final VoidCallback onNeedLogin; // 로그인이 풀렸음을 부모 화면에 알린다

  @override
  State<_PermitRow> createState() => _PermitRowState();
}

class _PermitRowState extends State<_PermitRow> {
  bool _rejectOpen = false; // 반려 양식이 펼쳐졌는가
  bool _approveOpen = false; // 승인 양식이 펼쳐졌는가 (반려 양식과 동시에 열리지 않는다)
  bool _open = false; // 사진이 펼쳐졌는가
  bool _loading = false;
  MemoryImage? _image; // 사진 (메모리에만 두고 파일에 저장하지 않는다)
  String? _error;
  bool _needLogin = false; // 줄이 남아 있는 채로 로그인이 풀린 경우, 사진 칸에 안내
  CancelToken? _cancel; // 접기·화면 이탈 때 진행 중인 요청을 끊는다
  AuthState? _auth; // dispose 때 로그아웃 여부를 보려고 잡아 둔다
  int _seq = 0;

  // 양식을 하나만 연다: 다른 양식은 닫고 이전 실패 안내는 지운다
  void _openForm({required bool approve}) {
    setState(() {
      _approveOpen = approve;
      _rejectOpen = !approve;
    });
    widget.onClearError();
    if (approve) widget.onOpenApprove();
  }

  void _closeForms() {
    setState(() {
      _approveOpen = false;
      _rejectOpen = false;
    });
    widget.onClearError();
  }

  // 처리가 성공이면 양식을 닫는다 (실패 안내는 부모가 error로 내려 준다)
  void _applyResult(bool success) {
    if (mounted && success) _closeForms();
  }

  @override
  void dispose() {
    _seq++;
    // 사진 칸에 로그인 안내를 띄운 직후 로그아웃으로 줄이 사라지면, 안내가 같이 사라지므로 부모 화면이 대신 보여 준다
    if (_needLogin && _auth?.user == null) {
      final onNeedLogin = widget.onNeedLogin;
      Future.microtask(onNeedLogin); // 지금은 화면을 정리하는 중이라 setState를 미룬다
    }
    _cancel?.cancel();
    _discardPhoto(); // 화면을 떠나도 사진이 메모리에 남지 않게 한다
    super.dispose();
  }

  // 사진을 버린다: 전역 이미지 캐시에서도 지워야 메모리에서 사라진다
  void _discardPhoto() {
    _image?.evict();
    _image = null;
  }

  Future<void> _toggle() async {
    if (_open) {
      // 접기: 진행 중인 요청 결과는 버린다
      _cancel?.cancel();
      _discardPhoto();
      setState(() {
        _open = false;
        _loading = false;
        _error = null;
        _needLogin = false;
        _seq++;
      });
      return;
    }
    setState(() {
      _open = true;
      _loading = true;
      _error = null;
      _needLogin = false;
    });
    final seq = ++_seq;
    final cancel = _cancel = CancelToken();
    _auth = context.read<AuthState>();
    final onNeedLogin = widget.onNeedLogin; // 줄이 사라진 뒤에도 쓸 수 있게 미리 잡아 둔다
    final onPhotoGone = widget.onPhotoGone;
    try {
      final res = await context.read<ApiClient>().dio.get<List<int>>(
        '/admin/permit-requests/${widget.item.id}/photo',
        options: Options(responseType: ResponseType.bytes, receiveTimeout: _requestTimeout),
        cancelToken: cancel,
      );
      if (!mounted || seq != _seq) return;
      setState(() {
        final data = res.data!;
        _image = MemoryImage(data is Uint8List ? data : Uint8List.fromList(data)); // 이미 Uint8List면 복사하지 않는다
        _loading = false;
      });
    } catch (e) {
      debugPrint('허가서 사진 불러오기 실패: $e');
      final info = describeError(e, {403: ErrorMessages.adminOnly, 404: _photoGoneMessage}, _photoFailedMessage);
      // 로그아웃으로 줄이 이미 사라졌으면(mounted false) 로그인 안내는 부모 화면이 한 번만 보여 준다
      if (!mounted && info is NeedLogin) onNeedLogin();
      // 사진이 없다면 신청이 철회·처리됐을 수 있으니 목록을 다시 읽게 알린다
      if (info is ErrorMessage && info.status == 404) onPhotoGone();
      if (!mounted || seq != _seq) return;
      setState(() {
        _loading = false;
        // 줄이 남아 있으면 안내는 사진 칸에만 띄운다(부모 안내와 겹치지 않게)
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    final detected = it.detectedSchoolName;
    final reason = it.rejectReason;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
      decoration: BoxDecoration(border: widget.first ? null : const Border(top: BorderSide(color: AppColors.line))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(it.nickname, style: const TextStyle(color: AppColors.ink, fontWeight: FontWeight.w500)),
              if (it.autoDecided) const AppBadge(text: '자동'),
            ],
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSizes.space2),
            child: Column(
              children: [
                ReportMetaRow(label: '신청 시각', value: formatDate(it.createdAt)),
                ReportMetaRow(label: '기계 결과', value: machineResultText(it.machineResult)),
                if (detected != null) ReportMetaRow(label: '찾은 학교', value: detected),
                if (it.status == 'APPROVED' && it.schoolName != null)
                  ReportMetaRow(label: '승인 학교', value: it.schoolName!),
                if (it.status == 'REJECTED')
                  ReportMetaRow(label: '반려 사유', value: reason == null ? '-' : (permitRejectReasons[reason] ?? reason)),
              ],
            ),
          ),
          Wrap(
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (it.hasPhoto)
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: _toggle,
                  child: Text(_open ? '사진 접기' : '사진 보기'),
                ),
              // 처리 버튼은 대기 탭의 남의 신청에만. 본인 신청은 안내만 (서버도 403으로 막는다)
              if (it.status == 'PENDING' && it.userId != widget.myId && !_approveOpen)
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: widget.enabled ? () => _openForm(approve: true) : null,
                  child: const Text('승인'),
                ),
              if (it.status == 'PENDING' && it.userId != widget.myId && !_rejectOpen)
                OutlinedButton(
                  style: dangerSmallButtonStyle,
                  onPressed: widget.enabled ? () => _openForm(approve: false) : null,
                  child: const Text('반려'),
                ),
              if (it.status == 'PENDING' && it.userId == widget.myId)
                const Text(_ownRequestMessage, style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted)),
            ],
          ),
          if (_open) _buildPhoto(),
          if (_rejectOpen)
            PermitRejectForm(
              enabled: widget.enabled,
              error: widget.error,
              onCancel: _closeForms,
              onSubmit: (reason) async => _applyResult(await widget.onReject(it, reason)),
            ),
          if (_approveOpen)
            PermitApproveForm(
              enabled: widget.enabled,
              schools: widget.schools,
              schoolsFailed: widget.schoolsFailed,
              detectedSchoolId: it.detectedSchoolId,
              error: widget.error,
              onRetrySchools: widget.onOpenApprove,
              onCancel: _closeForms,
              onSubmit: (schoolId, number) async => _applyResult(await widget.onApprove(it, schoolId, number)),
            ),
        ],
      ),
    );
  }

  Widget _buildPhoto() {
    if (_loading) return const Loading(prefix: '사진');
    if (_needLogin) return const ReloginNotice();
    final error = _error;
    if (error != null) return Notice(kind: NoticeKind.error, message: error);
    final image = _image;
    if (image == null) return const SizedBox.shrink();
    // 원래 크기보다 커지지 않고, 높이는 화면의 80%까지만 (큰 사진이 화면을 덮지 않게)
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space2),
      child: Align(
        alignment: Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
          child: Image(
            image: image,
            fit: BoxFit.contain,
            semanticLabel: '허가서 사진',
            errorBuilder: (context, error, stack) => const Notice(kind: NoticeKind.error, message: _photoDecodeFailed),
          ),
        ),
      ),
    );
  }
}
