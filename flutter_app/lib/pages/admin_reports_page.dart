import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../admin/models.dart';
import '../admin/report_meta_row.dart';
import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/app_link.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/small_button_style.dart';

const _listFailedMessage = '신고 목록을 불러오지 못했습니다';
const _alreadyHandled = '이미 처리됐거나 사라진 신고입니다';

// 신고 대상 종류별 문구. 삭제 확인 창·"이미 삭제된 ○입니다"에 쓰는 이름 (대화는 삭제가 없어 빠진다)
const _targetLabel = {'POST': '글', 'COMMENT': '댓글', 'MARKET_ITEM': '중고 글'};
// 행 앞 배지의 글자
const _badgeLabel = {'POST': '글', 'COMMENT': '댓글', 'MARKET_ITEM': '중고', 'CONVERSATION': '쪽지'};

// 삭제 API 주소 (중고 글은 관리자도 DELETE /market-items/{id}로 지울 수 있다)
String? _deletePath(AdminReportTarget t) => switch (t.type) {
  'POST' => '/posts/${t.id}',
  'COMMENT' => '/comments/${t.id}',
  'MARKET_ITEM' => '/market-items/${t.id}',
  _ => null,
};

// 관리자 신고 관리 화면 "/admin/reports" (React의 AdminReportsPage): 미처리 / 기각 탭,
// 미처리 신고에는 [삭제]·[문제 없음] 버튼. 탭의 진짜 값은 주소의 ?status (라우터가 status로 넘겨 준다).
// 관리자가 아니면 안내만 보여 주고 관리자 API는 부르지 않는다 (진짜 보호는 서버의 403).
class AdminReportsPage extends StatefulWidget {
  const AdminReportsPage({super.key, this.status = ''});

  final String status; // 주소의 ?status (DISMISSED만 기각 탭, 그 밖은 모두 미처리)

  @override
  State<AdminReportsPage> createState() => _AdminReportsPageState();
}

class _AdminReportsPageState extends State<AdminReportsPage> {
  List<AdminReport>? _reports; // null이면 아직 못 불러온 상태
  String? _loadError;
  bool _busy = false; // 처리 요청·목록 재조회 중 연타 방지
  String? _actionError;
  String? _notice; // 처리 성공 안내 (탭을 바꾸거나 다음 처리를 시작할 때만 지운다)
  bool _needLogin = false;
  bool _started = false; // 관리자로 확인돼 첫 조회를 보냈는가
  // 요청 번호: 가장 마지막에 보낸 목록 요청의 응답만 화면에 반영한다 (늦게 온 옛 응답 무시)
  int _seq = 0;

  String get _status => widget.status == 'DISMISSED' ? 'DISMISSED' : 'PENDING';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 관리자가 되는 순간(처음 열 때 포함) 목록을 읽는다. 관리자가 아니면 요청 자체를 보내지 않는다
    final admin = Provider.of<AuthState>(context).user?.isAdmin ?? false;
    if (admin && !_started) {
      _started = true;
      _fetch();
    } else if (!admin) {
      _started = false;
    }
  }

  @override
  void didUpdateWidget(AdminReportsPage old) {
    super.didUpdateWidget(old);
    // 주소의 탭이 바뀌면(탭 클릭·뒤로 가기) 이전 탭 목록을 비우고 다시 읽는다
    final oldStatus = old.status == 'DISMISSED' ? 'DISMISSED' : 'PENDING';
    if (oldStatus != _status && _started) {
      setState(() {
        _reports = null;
        _loadError = null;
      });
      _fetch();
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  // 현재 탭의 목록을 읽는다. 끝날 때까지 기다릴 수 있게 Future를 돌려준다
  Future<void> _fetch() async {
    final seq = ++_seq;
    try {
      final res = await context.read<ApiClient>().dio.get<List<dynamic>>(
        '/admin/reports',
        queryParameters: {'status': _status},
      );
      if (!mounted || seq != _seq) return;
      final list = [for (final e in res.data!) AdminReport.fromJson(e as Map<String, dynamic>)];
      setState(() {
        _reports = list;
        _loadError = null;
        _needLogin = false;
      });
    } catch (e) {
      debugPrint('신고 목록 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
      if (!mounted || seq != _seq) return;
      final info = describeError(e, {403: ErrorMessages.adminOnly}, _listFailedMessage);
      setState(() {
        _reports = null; // 실패했는데 옛 목록과 버튼이 남지 않게 비운다
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _loadError = info.message;
        }
      });
    }
  }

  // 탭 바꾸기: 목록·에러·안내를 비우고 주소만 바꾼다 (didUpdateWidget이 다시 읽는다).
  // Router.neglect: 기록을 쌓지 않고 교체해서(React의 replace) 탭을 누를 때마다 뒤로 가기가 쌓이지 않게 한다
  void _changeTab(String next) {
    // 지금 보는 탭을 다시 누르면 아무것도 안 한다 (비우기만 하고 다시 읽지 않아 "불러오는 중"에 멈추는 것을 막는다)
    if (next == _status) return;
    setState(() {
      _reports = null;
      _loadError = null;
      _actionError = null;
      _notice = null;
    });
    final url = Uri(path: '/admin/reports', queryParameters: {'status': next}).toString();
    final router = GoRouter.of(context);
    Router.neglect(context, () => router.go(url));
  }

  void _retry() {
    setState(() => _loadError = null);
    _fetch();
  }

  // 처리(삭제·문제 없음) 실패 공통 안내. 404/409는 이미 처리된 것이라 목록을 다시 읽어 맞춘다
  Future<void> _handleFail(Object err, String fallback, String gone) async {
    debugPrint('신고 처리 실패: $err');
    final info = describeError(err, {403: ErrorMessages.adminOnly, 404: gone, 409: gone}, fallback);
    if (!mounted) return;
    if (info is NeedLogin) {
      setState(() => _needLogin = true);
    } else if (info is ErrorMessage) {
      setState(() => _actionError = info.message);
      if (info.status == 404 || info.status == 409) await _fetch();
    }
  }

  void _startAction() {
    setState(() {
      _busy = true;
      _actionError = null;
      _notice = null;
      _needLogin = false;
    });
  }

  Future<void> _delete(AdminReport r) async {
    final path = _deletePath(r.target);
    final what = _targetLabel[r.target.type];
    if (path == null || what == null) return; // 대화는 삭제할 수 없다 (버튼도 없다)
    // 되돌릴 수 없는 동작이라 먼저 확인한다
    final ok = await showConfirm(context, message: '신고된 $what을(를) 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    _startAction();
    try {
      await context.read<ApiClient>().dio.delete<dynamic>(path);
      if (!mounted) return;
      setState(() => _notice = '처리했습니다');
      await _fetch(); // 목록을 다시 읽을 때까지 버튼을 계속 막는다
    } catch (err) {
      await _handleFail(err, ErrorMessages.deleteFailed, ErrorMessages.alreadyDeleted(what));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _dismiss(AdminReport r) async {
    _startAction();
    try {
      await context.read<ApiClient>().dio.put<dynamic>('/admin/reports/${r.id}/dismiss');
      if (!mounted) return;
      setState(() => _notice = '처리했습니다');
      await _fetch();
    } catch (err) {
      await _handleFail(err, ErrorMessages.actionFailed, _alreadyHandled);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final admin = context.watch<AuthState>().user?.isAdmin ?? false;
    // 관리자가 아니면 안내만 (비로그인·일반 회원 모두). 처리 중 로그인이 풀려(401) 관리자가 아니게 된 경우엔 로그인 안내
    if (!admin) {
      return PageFrame(
        child: _needLogin
            ? const ReloginNotice()
            : const Notice(kind: NoticeKind.error, message: ErrorMessages.adminOnly),
      );
    }
    final reports = _reports;
    final loadError = _loadError;
    final pending = _status == 'PENDING';
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('신고 관리'),
          // 탭: 선택된 탭은 청자색 글자 + 밑줄. 처리 중에는 눌리지 않는다
          Container(
            margin: const EdgeInsets.only(bottom: AppSizes.space4),
            decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
            child: Wrap(
              spacing: AppSizes.space4,
              children: [
                _StatusTab(label: '미처리', selected: pending, enabled: !_busy, onTap: () => _changeTab('PENDING')),
                _StatusTab(label: '기각', selected: !pending, enabled: !_busy, onTap: () => _changeTab('DISMISSED')),
              ],
            ),
          ),
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
          if (reports == null && loadError == null && !_needLogin) const Loading(),
          // 신고 한 건 = 판 안의 행
          if (reports != null)
            FlushPanel(
              children: [
                if (reports.isEmpty)
                  const PanelState(child: Text('신고가 없습니다', style: TextStyle(color: AppColors.muted))),
                for (var i = 0; i < reports.length; i++)
                  _ReportRow(
                    key: ValueKey('report-${reports[i].id}'),
                    report: reports[i],
                    first: i == 0,
                    showActions: pending,
                    busy: _busy,
                    onDelete: () => _delete(reports[i]),
                    onDismiss: () => _dismiss(reports[i]),
                  ),
              ],
            ),
          if (_notice != null) Notice(kind: NoticeKind.success, message: _notice!),
          if (_actionError != null) Notice(kind: NoticeKind.error, message: _actionError!),
          if (_needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}

// 탭 하나 (React의 .tabs button): 선택됨은 청자 글자+밑줄(굵기는 그대로), 처리 중엔 반투명하고 안 눌린다
class _StatusTab extends StatelessWidget {
  const _StatusTab({required this.label, required this.selected, required this.enabled, required this.onTap});

  final String label;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.celadon : AppColors.muted;
    return Semantics(
      button: true,
      selected: selected,
      enabled: enabled,
      child: Opacity(
        opacity: enabled ? 1 : 0.5,
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
      ),
    );
  }
}

// 신고 한 줄: 배지+이동 링크 / 미리보기 / 메타(작성자·사유·상세·신고자·날짜) / (미처리면) 버튼
class _ReportRow extends StatelessWidget {
  const _ReportRow({
    super.key,
    required this.report,
    required this.first,
    required this.showActions,
    required this.busy,
    required this.onDelete,
    required this.onDismiss,
  });

  final AdminReport report;
  final bool first;
  final bool showActions;
  final bool busy;
  final VoidCallback onDelete;
  final VoidCallback onDismiss;

  // 종류별 배지 색 (글자, 배경). React의 .badge-POST 등과 같은 조합
  (Color, Color) get _badgeColors => switch (report.target.type) {
    'POST' => (AppColors.celadonInk, AppColors.celadonSoft),
    'COMMENT' => (AppColors.muted, AppColors.badgeChatBg),
    'MARKET_ITEM' => (AppColors.badgeQuestionInk, AppColors.badgeQuestionBg),
    'CONVERSATION' => (AppColors.celadonInk, AppColors.hover),
    _ => (AppColors.ink, Colors.transparent),
  };

  @override
  Widget build(BuildContext context) {
    final t = report.target;
    final conversation = t.type == 'CONVERSATION';
    final (ink, bg) = _badgeColors;
    // 이동 링크: 대화는 "신고 id"(방 id 아님)로 대화 보기, 중고 글은 postId 없이 /market/{id}, 글·댓글은 /posts/{postId}
    final (linkLabel, linkTo) = switch (t.type) {
      'CONVERSATION' => ('대화 보기', '/admin/reports/${report.id}/conversation'),
      'MARKET_ITEM' => ('글로 가기', '/market/${t.id}'),
      _ => ('글로 가기', t.postId == null ? null : '/posts/${t.postId}'),
    };
    final detail = report.detail;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
      decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: AppColors.line))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              AppBadge(text: _badgeLabel[t.type] ?? t.type, ink: ink, bg: bg),
              if (linkTo != null) AppLink(label: linkLabel, onTap: () => context.go(linkTo)),
            ],
          ),
          // 줄바꿈은 그대로, 글자로만 그린다(HTML이 실행되지 않음)
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space1),
            child: Text(t.preview, style: const TextStyle(color: AppColors.ink, height: 1.65)),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSizes.space2),
            child: Column(
              children: [
                // 대화 신고의 author는 "신고자가 아닌 쪽 참가자"라서 라벨을 바꾼다
                ReportMetaRow(
                  label: conversation ? '대화 상대' : '대상 작성자',
                  value: t.author?.nickname ?? (conversation ? '탈퇴한 회원 또는 상대 없음' : '탈퇴한 회원 또는 작성자 없음'),
                ),
                ReportMetaRow(label: '사유', value: report.reason.name),
                ReportMetaRow(label: '상세', value: (detail == null || detail.isEmpty) ? '-' : detail),
                ReportMetaRow(label: '신고자', value: report.reporter?.nickname ?? '탈퇴한 회원'),
                ReportMetaRow(label: '날짜', value: formatDate(report.createdAt)),
              ],
            ),
          ),
          if (showActions)
            Wrap(
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                // 대화 신고는 삭제 없이 [문제 없음](기각)만
                if (!conversation)
                  OutlinedButton(
                    style: dangerSmallButtonStyle,
                    onPressed: busy ? null : onDelete,
                    child: const Text('삭제'),
                  ),
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: busy ? null : onDismiss,
                  child: const Text('문제 없음'),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
