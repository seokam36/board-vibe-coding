import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../admin/models.dart';
import '../admin/report_meta_row.dart';
import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../messages/models.dart' show withdrawnPartner;
import '../theme/app_colors.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/small_button_style.dart';

// 없는 신고(404)와 대화 신고가 아닌 신고(404), 숫자가 아닌 주소(400)가 모두 이 문구다
const _reportNotConversation = '없는 신고이거나 대화 신고가 아닙니다';
const _viewingInfo = '관리자가 신고된 대화를 열람 중입니다. 읽기만 할 수 있어요';
// 서버가 최근 200통만 줬을 때(hasMore) 목록 위에 보여 주는 안내
const _truncatedInfo = '최근 200통만 보여요. 이전 쪽지는 표시되지 않아요';

// 신고된 대화 보기 "/admin/reports/:id/conversation" (React의 AdminConversationPage). :id = 신고 id. 읽기 전용.
// 라우터가 key=id로 붙여서 신고 번호가 바뀌면 화면이 통째로 새로 만들어진다 (이전 대화가 남지 않게).
// 관리자가 아니면 안내만 보여 주고 요청은 보내지 않는다. 화면에서 보내는 요청은 GET 한 가지뿐이다(답장·신고 없음).
class AdminConversationPage extends StatefulWidget {
  const AdminConversationPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<AdminConversationPage> createState() => _AdminConversationPageState();
}

class _AdminConversationPageState extends State<AdminConversationPage> {
  AdminConversationView? _view;
  bool _loading = true;
  String? _error;
  bool _retryable = false; // 다시 시도해 볼 만한 실패(네트워크·서버 오류)인가
  bool _needLogin = false; // 불러오다 401(재발급 실패)
  bool _started = false; // 관리자로 확인돼 첫 조회를 보냈는가
  int _seq = 0; // 늦게 온 옛 응답을 버리기 위한 요청 번호

  // '?' 같은 글자가 경로에 섞여 다른 API로 바뀌는 것을 막는다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 관리자가 되는 순간(처음 열 때 포함) 불러온다. 관리자가 아니면 요청 자체를 보내지 않는다
    final admin = Provider.of<AuthState>(context).user?.isAdmin ?? false;
    if (admin && !_started) {
      _started = true;
      _load();
    } else if (!admin) {
      _started = false;
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  void _load() {
    final seq = ++_seq;
    context
        .read<ApiClient>()
        .dio
        .get<Map<String, dynamic>>('/admin/reports/$_safeId/conversation')
        .then((res) {
          if (!mounted || seq != _seq) return;
          final view = AdminConversationView.fromJson(res.data!);
          setState(() {
            _view = view;
            _error = null;
            _needLogin = false;
            _loading = false;
          });
        })
        .catchError((Object e) {
          debugPrint('신고된 대화 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
          if (!mounted || seq != _seq) return;
          final info = describeError(e, {
            400: _reportNotConversation,
            403: ErrorMessages.adminOnly,
            404: _reportNotConversation,
          }, ErrorMessages.conversationLoadFailed);
          setState(() {
            _view = null;
            if (info is NeedLogin) {
              _needLogin = true;
            } else if (info is ErrorMessage) {
              _error = info.message;
              // 주소·권한 문제(400·403·404)는 다시 해도 같으니 버튼을 숨긴다
              _retryable = info.status != 400 && info.status != 403 && info.status != 404;
            }
            _loading = false;
          });
        });
  }

  void _retry() {
    setState(() {
      _error = null;
      _loading = true;
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final admin = context.watch<AuthState>().user?.isAdmin ?? false;
    // 관리자가 아니면 안내만. 보는 중에 로그인이 풀려(401) 관리자가 아니게 된 경우엔 로그인 안내
    if (!admin) {
      return PageFrame(
        child: _needLogin
            ? const ReloginNotice()
            : const Notice(kind: NoticeKind.error, message: ErrorMessages.adminOnly),
      );
    }
    final view = _view;
    final error = _error;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('신고된 대화'),
          // 대화가 실제로 보일 때만 안내한다 (로딩·오류 중에는 열람 중이 아니다)
          if (view != null) const Notice(kind: NoticeKind.info, message: _viewingInfo),
          if (_loading) const Loading(),
          if (error != null)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  Text(error),
                  if (_retryable)
                    OutlinedButton(style: smallButtonStyle, onPressed: _retry, child: const Text('다시 시도')),
                ],
              ),
            ),
          if (_needLogin) const ReloginNotice(),
          if (view != null) ..._buildView(view),
          // 어떤 상태에서도 돌아갈 길이 있게 항상 보여 준다
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(onPressed: () => context.go('/admin/reports'), child: const Text('신고 목록으로')),
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _buildView(AdminConversationView view) {
    return [
      // 머리: 글 제목 + 구매자·판매자 (탈퇴했으면 "탈퇴한 회원")
      Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space1),
              child: Semantics(
                header: true,
                headingLevel: 2,
                child: Text(
                  view.itemTitle,
                  style: const TextStyle(
                    fontSize: AppSizes.fsRow,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink,
                    height: 1.35,
                  ),
                ),
              ),
            ),
            ReportMetaRow(label: '구매자', value: view.buyer?.nickname ?? withdrawnPartner),
            ReportMetaRow(label: '판매자', value: view.seller?.nickname ?? withdrawnPartner),
          ],
        ),
      ),
      if (view.messages.isEmpty) const PanelState(child: Text('아직 쪽지가 없습니다')),
      if (view.hasMore)
        const Padding(
          padding: EdgeInsets.only(top: AppSizes.space4),
          child: Text(
            _truncatedInfo,
            key: ValueKey('msg-truncated'),
            style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
          ),
        ),
      // 판매자는 오른쪽(청자 배경), 구매자·누구인지 모르는(탈퇴) 사람은 왼쪽. 폭은 최대 85%
      Semantics(
        container: true,
        label: '쪽지 목록',
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: AppSizes.space4),
          child: LayoutBuilder(
            builder: (context, constraints) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < view.messages.length; i++)
                  Padding(
                    padding: EdgeInsets.only(top: i == 0 ? 0 : AppSizes.space3),
                    child: _AdminBubble(
                      m: view.messages[i],
                      role: _roleOf(view, view.messages[i].sender),
                      maxWidth: constraints.maxWidth * 0.85,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    ];
  }

  // 보낸 사람이 구매자인지 판매자인지 (탈퇴해서 sender가 null이면 알 수 없다). id로 비교한다
  String? _roleOf(AdminConversationView view, AdminUser? sender) {
    if (sender == null) return null;
    if (view.buyer != null && sender.id == view.buyer!.id) return '구매자';
    if (view.seller != null && sender.id == view.seller!.id) return '판매자';
    return null;
  }
}

// 말풍선 한 개 + 보낸 사람(닉네임 · 역할) + 시각. 방향은 "판매자냐"로 정한다 (관리자에게는 "내 것"이 없다)
class _AdminBubble extends StatelessWidget {
  const _AdminBubble({required this.m, required this.role, required this.maxWidth});

  final AdminConversationMessage m;
  final String? role; // '구매자' | '판매자' | null
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final right = role == '판매자';
    final name = '${m.sender?.nickname ?? withdrawnPartner}${role == null ? '' : ' · $role'}';
    return Semantics(
      container: true,
      child: Align(
        key: ValueKey('msg-${m.id}'),
        alignment: right ? Alignment.centerRight : Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: right ? CrossAxisAlignment.end : CrossAxisAlignment.start,
            children: [
              Text(name, style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted)),
              const SizedBox(height: AppSizes.space1),
              Container(
                key: ValueKey('msg-bubble-${m.id}'),
                padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3, vertical: AppSizes.space2),
                decoration: BoxDecoration(
                  color: right ? AppColors.celadonSoft : AppColors.hover,
                  borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
                ),
                // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
                child: Text(
                  m.content,
                  style: TextStyle(color: right ? AppColors.celadonInk : AppColors.ink, height: 1.5),
                ),
              ),
              const SizedBox(height: AppSizes.space1),
              Text(
                formatDate(m.createdAt),
                style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
