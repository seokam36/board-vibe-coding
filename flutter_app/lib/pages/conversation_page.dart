import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../market/market_status_badge.dart';
import '../messages/message_composer.dart';
import '../messages/message_text.dart';
import '../messages/models.dart';
import '../messages/unread_store.dart';
import '../theme/app_colors.dart';
import '../widgets/app_link.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/report_form.dart';

// 대화 화면 "/messages/:id" (React의 ConversationPage): 위에 상대와 글, 가운데 쪽지(오래된 순), 아래 답장 칸.
// 로그인한 사람만 (라우터 문지기가 막는다). 라우터가 key=id로 붙여서, 다른 대화로 건너뛰면 화면이 통째로 새로 만들어진다
// (이전 대화 내용·쓰던 답장이 새 대화에 남지 않는다).
class ConversationPage extends StatefulWidget {
  const ConversationPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<ConversationPage> createState() => _ConversationPageState();
}

class _ConversationPageState extends State<ConversationPage> {
  ConversationDetail? _conv;
  bool _loading = true;
  String? _error;
  bool _needLogin = false; // 불러오다 401(재발급 실패)
  final _endKey = GlobalKey(); // 쪽지 목록 끝(답장 칸 포함): 열 때·답장했을 때 여기까지 내린다

  // id를 경로에 넣기 전에 인코딩한다: '?' 같은 글자가 섞여 다른 API로 바뀌는 것을 막는다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    _load();
  }

  // 들어오면 대화를 불러온다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted)
  // 불러온 뒤에는 "여기까지 봤다"는 읽음 PUT을 보낸다 (_markRead)
  void _load() {
    // 응답이 늦게 와도 쓸 수 있게 미리 잡아 둔다
    final unread = context.read<UnreadStore>();
    context
        .read<ApiClient>()
        .dio
        .get<Map<String, dynamic>>('/conversations/$_safeId')
        .then((res) {
          if (!mounted) return;
          final conv = ConversationDetail.fromJson(res.data!);
          setState(() {
            _conv = conv;
            _error = null;
            _needLogin = false;
            _loading = false;
          });
          _scrollToEnd();
          _markRead(conv, unread);
        })
        .catchError((Object e) {
          debugPrint('대화 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
          if (!mounted) return;
          final info = describeError(e, {
            400: ErrorMessages.conversationNotFound,
            404: ErrorMessages.conversationNotFound,
          }, ErrorMessages.conversationLoadFailed);
          setState(() {
            _conv = null;
            if (info is NeedLogin) {
              _needLogin = true;
            } else if (info is ErrorMessage) {
              _error = info.message;
            }
            _loading = false;
          });
        });
  }

  // 대화를 열었으니 "여기까지 봤다"고 서버에 알린다 (마지막 쪽지 번호). 쪽지가 없으면 보낼 게 없다.
  // 열 때 한 번만 보낸다(답장을 보내도 다시 안 보냄). 성공하면 상단바 숫자를 다시 받고, 실패는 부가 동작이라 조용히 무시한다
  void _markRead(ConversationDetail conv, UnreadStore unread) {
    if (conv.messages.isEmpty) return;
    context
        .read<ApiClient>()
        .dio
        .put<dynamic>('/conversations/$_safeId/read', data: {'lastMessageId': conv.messages.last.id})
        .then((_) => unread.refresh())
        .catchError((Object e) {
          debugPrint('읽음 처리 실패(무시): $e');
        });
  }

  // 쪽지가 늘어나면(처음 불러왔을 때, 답장했을 때) 맨 아래가 보이게 내린다. 쪽지가 없으면 내리지 않는다
  void _scrollToEnd() {
    if ((_conv?.messages.length ?? 0) == 0) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final ctx = _endKey.currentContext;
      if (mounted && ctx != null) Scrollable.ensureVisible(ctx, alignment: 1.0);
    });
  }

  // 답장 보내기: 성공하면 목록 끝에 붙인다. 실패하면 던져서 MessageComposer가 안내를 띄운다.
  // 보내는 곳은 주소가 아니라 "화면에 보이는 대화"(convId)다
  Future<void> _sendReply(int convId, String content) async {
    final res = await context.read<ApiClient>().dio.post<Map<String, dynamic>>(
      '/conversations/$convId/messages',
      data: {'content': content},
    );
    if (!mounted) return;
    final msg = ConversationMessage.fromJson(res.data!);
    setState(() {
      final prev = _conv;
      if (prev != null && prev.id == convId) _conv = prev.withMessage(msg);
    });
    _scrollToEnd();
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final conv = _conv;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_loading) const Loading(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (_needLogin) const ReloginNotice(),
          if (conv != null) ...[
            _buildHead(conv),
            // 신고: 학교 인증 회원만 (서버가 미인증은 403). 상대가 탈퇴했거나 글이 지워진 방도 신고할 수 있어 그런 조건은 안 본다
            if (user?.verifiedSchool != null)
              Padding(
                padding: const EdgeInsets.only(top: AppSizes.space3),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: ReportForm(
                    key: ValueKey('report-${conv.id}'), // 다른 대화로 건너뛰면 양식이 새로 만들어진다
                    url: '/conversations/${conv.id}/reports',
                    label: '대화',
                    // 안내는 대화 영역 밖(위)에서 보여 준다 (로그인이 풀리면 이 버튼이 사라지므로)
                    onNeedLogin: () {
                      if (mounted) setState(() => _needLogin = true);
                    },
                  ),
                ),
              ),
            if (conv.messages.isEmpty)
              const PanelState(child: Text('아직 쪽지가 없습니다')),
            // 서버가 최근 200통만 줬으면(hasMore) 목록 위에 안내
            if (conv.hasMore)
              const Padding(
                padding: EdgeInsets.only(top: AppSizes.space4),
                child: Text(
                  messagesTruncated,
                  key: ValueKey('msg-truncated'),
                  style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                ),
              ),
            _buildMessages(conv),
            Container(key: _endKey, child: _buildReply(conv, user)),
          ],
          // 대화를 못 찾았을 때도 돌아갈 길이 있게 항상 보여 준다
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: () => context.go('/messages'),
                child: const Text('쪽지함으로'),
              ),
            ),
          ),
        ],
      ),
    );
  }

  // 머리: 상대 닉네임(제목)과 글 링크 (글이 지워졌으면 링크 없이 "삭제된 글" 표시와 제목)
  Widget _buildHead(ConversationDetail conv) {
    final item = conv.item;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space1),
            child: Semantics(
              header: true,
              headingLevel: 1,
              child: Text(
                conv.partner?.nickname ?? withdrawnPartner,
                style: const TextStyle(
                  fontSize: AppSizes.fsTitle,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  height: 1.35,
                ),
              ),
            ),
          ),
          if (item.id != null && !item.deleted)
            AppLink(
              label: item.title,
              onTap: () => context.go('/market/${item.id}'),
            )
          else
            Wrap(
              spacing: AppSizes.space2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                const MarketStatusBadge(code: 'DONE', name: '삭제된 글'),
                Text(
                  item.title,
                  style: const TextStyle(
                    fontSize: AppSizes.fsSmall,
                    color: AppColors.muted,
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }

  // 말풍선 목록: 내 쪽지는 오른쪽(청자 배경), 상대 쪽지는 왼쪽(hover 배경), 폭은 최대 85%
  Widget _buildMessages(ConversationDetail conv) {
    return Semantics(
      container: true,
      label: '쪽지 목록',
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSizes.space4),
        child: LayoutBuilder(
          builder: (context, constraints) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < conv.messages.length; i++)
                Padding(
                  padding: EdgeInsets.only(top: i == 0 ? 0 : AppSizes.space3),
                  child: _MessageBubble(
                    m: conv.messages[i],
                    maxWidth: constraints.maxWidth * 0.85,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  // 답장 칸 분기: 상대 탈퇴 → 학교 미인증 안내 → 입력칸
  Widget _buildReply(ConversationDetail conv, AuthUser? u) {
    if (conv.partner == null) {
      // 상대가 탈퇴했으면 서버가 새 쪽지를 409로 막는다. 답장 칸 대신 안내만
      return const Notice(
        kind: NoticeKind.info,
        message: '상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요',
      );
    }
    if (u != null && u.verifiedSchool == null) {
      // 답장도 학교 인증 회원만 (서버 403). 로그인이 풀려 user가 null이 된 경우는 안내로 바꾸지 않는다
      // (쓰던 답장을 지우지 않으려고)
      return Padding(
        padding: const EdgeInsets.only(top: AppSizes.space1),
        child: Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: AppSizes.space1,
          children: [
            const Text('학교 인증 후 답장할 수 있어요'),
            AppLink(label: '학교 인증하기', onTap: () => context.go('/me')),
          ],
        ),
      );
    }
    return MessageComposer(
      key: ValueKey('reply-${conv.id}'),
      label: '답장 내용',
      placeholder: '답장을 입력하세요',
      submitLabel: '답장 보내기',
      errorTable: const {
        400: ErrorMessages.messageContentInvalid,
        403: '쪽지를 보내지 못했습니다. 새로고침 후 다시 시도해 주세요',
        404: ErrorMessages.conversationNotFound,
        409: '상대가 탈퇴해 쪽지를 보낼 수 없습니다',
      },
      fallbackError: ErrorMessages.messageSendFailed,
      send: (content) => _sendReply(conv.id, content),
    );
  }
}

// 말풍선 한 개 + 시각. 스크린리더에는 "내가 보냄 "/"받은 쪽지 "를 시각 앞에 붙여 읽어 준다
class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.m, required this.maxWidth});

  final ConversationMessage m;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final time = formatDate(m.createdAt);
    // 쪽지 한 통을 스크린리더 항목 하나로 묶는다 (본문 → "내가 보냄 시각" 순으로 읽힌다)
    return Semantics(
      container: true,
      child: Align(
        key: ValueKey('msg-${m.id}'),
        alignment: m.mine ? Alignment.centerRight : Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxWidth),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: m.mine
                ? CrossAxisAlignment.end
                : CrossAxisAlignment.start,
            children: [
              Container(
                key: ValueKey('msg-bubble-${m.id}'),
                padding: const EdgeInsets.symmetric(
                  horizontal: AppSizes.space3,
                  vertical: AppSizes.space2,
                ),
                decoration: BoxDecoration(
                  color: m.mine ? AppColors.celadonSoft : AppColors.hover,
                  borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
                ),
                // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
                child: Text(
                  m.content,
                  style: TextStyle(
                    color: m.mine ? AppColors.celadonInk : AppColors.ink,
                    height: 1.5,
                  ),
                ),
              ),
              const SizedBox(height: AppSizes.space1),
              Semantics(
                label: '${m.mine ? '내가 보냄 ' : '받은 쪽지 '}$time',
                excludeSemantics: true,
                child: Text(
                  time,
                  style: const TextStyle(
                    fontSize: AppSizes.fsSmall,
                    color: AppColors.muted,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
