import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../errors/error_messages.dart';
import '../permit/admin_permit_models.dart';
import '../permit/admin_permit_retry_notice.dart';
import '../permit/admin_permit_row.dart';
import '../permit/admin_permit_tab.dart';
import '../permit/admin_permit_texts.dart';
import '../posts/models.dart' show School;
import '../theme/app_colors.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';

// 관리자 "인증 심사" 화면 "/admin/permits": 대기/승인/반려/취소 탭 + 신청 목록 + 사진 보기.
// 대기 탭 줄에는 [승인]·[반려], 승인 탭 줄에는 [인증 취소] 버튼이 있다. 관리자가 아니면 안내만 보여 주고 API는 부르지 않는다.
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
  // 목록이 보이는 상태의 재조회 실패 안내 (목록은 지우지 않고 그대로 둔다). 두 가지 뜻으로 쓴다:
  // 일시적 실패(permitRefreshFailed, [다시 불러오기] 버튼 보임) / 권한 없음 403(ErrorMessages.adminOnly, 버튼 숨김 — 다시 눌러도 소용없음)
  String? _refreshError;
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
        options: Options(receiveTimeout: adminPermitTimeout), // 응답이 없으면 실패로 보고 [다시 시도]를 보여 준다
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
        options: Options(receiveTimeout: adminPermitTimeout),
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
      final info = describeError(e, {403: ErrorMessages.adminOnly}, permitListFailed);
      setState(() {
        if (keepOnFail && _items != null) {
          // 기존 목록(그리고 줄에 쳐 둔 입력)을 지키고 안내만 띄운다
          if (info is NeedLogin) {
            _needLogin = true;
          } else if (info is ErrorMessage && info.status == 403) {
            // 관리자 권한이 사라졌다: 다시 불러와도 소용없으니 권한 안내만 (버튼은 build에서 숨긴다)
            _refreshError = ErrorMessages.adminOnly;
          } else {
            _refreshError = permitRefreshFailed;
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

  // [다시 불러오기]: 기존 목록은 두고 다시 읽는다 (성공하면 안내가 사라지고, 또 실패하면 안내가 남는다)
  Future<void> _reload() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _fetch(keepOnFail: true);
    } finally {
      if (mounted) setState(() => _busy = false);
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
        _actionError = permitAdminTimeout;
        _actionErrorFor = requestId;
      });
      await _fetch(keepOnFail: true);
      return;
    }
    final info = describeError(err, {...permitAdminFailMessages, ...messages}, ErrorMessages.actionFailed);
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
        options: Options(receiveTimeout: adminPermitTimeout), // 공용 dio엔 시간 제한이 없어 요청마다 건다
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

  // 인증 취소: 승인된 회원의 인증을 거둔다 (사유는 반려 사유 코드 중 EXPIRED 제외)
  Future<bool> _revoke(AdminPermitRequest item, String reason) => _decide(
    requestId: item.id,
    confirmMessage: permitRevokeConfirm(item.nickname),
    confirmLabel: '인증 취소',
    danger: true,
    path: '/admin/permit-requests/${item.id}/revoke',
    body: {'reason': reason},
    doneNotice: permitRevokeDone,
    messages: {400: permitRevokeBadReason, 409: permitRevokeConflict}, // 409는 목록을 다시 읽어 그 줄을 맞춘다
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
    final emptyText = adminPermitTabs.firstWhere((t) => t.$1 == _status).$3;
    final myId = context.watch<AuthState>().user?.id; // 본인 신청은 직접 심사할 수 없다
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('인증 심사'),
          Container(
            margin: const EdgeInsets.only(bottom: AppSizes.space4),
            decoration: const BoxDecoration(
              border: Border(bottom: BorderSide(color: AppColors.line)),
            ),
            child: Wrap(
              spacing: AppSizes.space4,
              children: [
                for (final t in adminPermitTabs)
                  AdminPermitTab(
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
          // 기존 목록은 그대로 두고 다시 읽기만 시도한다. 처리 중에는 막는다
          if (_refreshError != null)
            AdminPermitRetryNotice(message: _refreshError!, buttonLabel: '다시 불러오기', onPressed: _busy ? null : _reload),
          if (loadError != null) AdminPermitRetryNotice(message: loadError, buttonLabel: '다시 시도', onPressed: _retry),
          if (items == null && loadError == null && !_needLogin) const Loading(),
          if (items != null)
            FlushPanel(
              children: [
                if (items.isEmpty)
                  PanelState(
                    child: Text(emptyText, style: const TextStyle(color: AppColors.muted)),
                  ),
                for (var i = 0; i < items.length; i++)
                  AdminPermitRow(
                    key: ValueKey('permit-${items[i].id}'),
                    item: items[i],
                    first: i == 0,
                    myId: myId,
                    enabled: !_busy,
                    onReject: _reject,
                    onApprove: _approve,
                    onRevoke: _revoke,
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
