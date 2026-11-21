import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../market/market_status_badge.dart';
import '../messages/message_text.dart';
import '../messages/models.dart';
import '../theme/app_colors.dart';
import '../widgets/feed_row.dart';
import '../widgets/initial_avatar.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';

// 쪽지함 "/messages" (React의 MessageListPage): 내가 참여한 대화를 마지막 쪽지가 최근인 순으로 보여 준다.
// 로그인한 사람만 (라우터 문지기가 막는다). 들어올 때 한 번만 불러온다.
class MessageListPage extends StatefulWidget {
  const MessageListPage({super.key});

  @override
  State<MessageListPage> createState() => _MessageListPageState();
}

class _MessageListPageState extends State<MessageListPage> {
  List<ConversationSummary>? _conversations; // null이면 아직 못 불러온 상태
  String? _error;
  bool _needLogin = false; // 불러오다 401(재발급 실패): 로그인 안내

  @override
  void initState() {
    super.initState();
    _load();
  }

  // 화면이 사라진 뒤 도착한 응답은 버린다(mounted)
  void _load() {
    context.read<ApiClient>().dio.get<List<dynamic>>('/conversations').then((res) {
      if (!mounted) return;
      setState(() {
        _conversations = [
          for (final c in res.data!) ConversationSummary.fromJson(c as Map<String, dynamic>),
        ];
        _error = null;
        _needLogin = false;
      });
    }).catchError((Object e) {
      debugPrint('쪽지함 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
      if (!mounted) return;
      final info = describeError(e, const {}, '쪽지함을 불러오지 못했습니다');
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final list = _conversations;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('쪽지함'),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (_needLogin) const ReloginNotice(),
          if (list == null && _error == null && !_needLogin) const Loading(prefix: '쪽지함'),
          if (list != null)
            // FeedPanel엔 바깥 여백이 없어서 판 아래 여백 16을 직접 준다
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: FeedPanel(
                children: [
                  if (list.isEmpty) const PanelState(child: Text('아직 쪽지가 없습니다')),
                  for (var i = 0; i < list.length; i++) _ConversationRow(c: list[i], first: i == 0),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

// 쪽지함 한 줄: 줄 전체가 링크. 상대 닉네임 · 안 읽은 수 배지 / 글 제목 / 마지막 쪽지 + 시각(오른쪽 끝)
class _ConversationRow extends StatelessWidget {
  const _ConversationRow({required this.c, required this.first});

  final ConversationSummary c;
  final bool first;

  @override
  Widget build(BuildContext context) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted);
    final nickname = c.partner?.nickname; // null이면 탈퇴한 상대
    final nickText = nickname ?? withdrawnPartner;
    // 길면 한 줄에서 줄임표(…)로 자른다(줄바꿈은 공백으로, 웹의 nowrap처럼). 내가 보낸 쪽지에는 "나: "를 붙인다
    final lastText =
        '${c.lastMessage.mine ? '나: ' : ''}${c.lastMessage.content.replaceAll(RegExp(r'\s*[\r\n]+\s*'), ' ')}';
    final time = formatDate(c.lastMessage.createdAt);
    // 줄 하나를 링크 하나로 읽게 읽을 말을 직접 정한다 (순서: 닉네임, 안 읽은 쪽지, 삭제된 글, 글 제목, 마지막 쪽지, 시각).
    // 안 읽은 수는 눈에는 99+지만 스크린리더에는 실제 개수.
    return FeedRow(
      key: ValueKey('conv-row-${c.id}'),
      label: [
        nickText,
        if (c.unreadCount > 0) '안 읽은 쪽지 ${c.unreadCount}개',
        if (c.item.deleted) '삭제된 글',
        c.item.title,
        lastText,
        time,
      ].join(', '),
      onTap: () => context.go('/messages/${c.id}'),
      first: first,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InitialAvatar(nickname: nickname), // 탈퇴한 상대는 회색 빈 동그라미
          const SizedBox(width: AppSizes.space3),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 1단: 닉네임 + 안 읽은 수 배지. 닉네임은 줄어들 수 있고(말줄임), 배지는 줄어들지 않는다
                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  children: [
                    Flexible(
                      child: Text(
                        nickText,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: AppSizes.fsFeedTitle,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink,
                          height: 1.5,
                        ),
                      ),
                    ),
                    // 안 읽은 쪽지가 있을 때만 배지 (읽을 말은 줄 라벨에 들어 있다)
                    if (c.unreadCount > 0) ...[
                      const SizedBox(width: AppSizes.space2),
                      Container(
                        key: ValueKey('conv-unread-${c.id}'),
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        decoration: BoxDecoration(
                          color: AppColors.accentSoft,
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          formatUnreadBadge(c.unreadCount),
                          maxLines: 1,
                          style: const TextStyle(
                            fontSize: AppSizes.fsSmall,
                            fontWeight: FontWeight.w500,
                            color: AppColors.accentInk,
                            height: 1.6,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: AppSizes.space1),
                Wrap(
                  spacing: AppSizes.space2,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (c.item.deleted) const MarketStatusBadge(code: 'DONE', name: '삭제된 글'),
                    Text(c.item.title, style: metaStyle),
                  ],
                ),
                const SizedBox(height: AppSizes.space1),
                // 3단: 마지막 쪽지(줄임표로 양보) + 시각(오른쪽 끝, 항상 한 줄).
                // 시각을 먼저 자리 잡고 남는 폭을 쪽지가 쓴다. 시각의 최대 폭은 줄 폭 − 간격으로 막아, 아주 좁아도 넘침 예외 없이 줄임표로 끝난다
                LayoutBuilder(
                  builder: (context, box) => Row(
                    children: [
                      Expanded(
                        child: Text(
                          lastText,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: AppColors.ink),
                        ),
                      ),
                      const SizedBox(width: AppSizes.space2),
                      ConstrainedBox(
                        constraints: BoxConstraints(maxWidth: box.maxWidth > AppSizes.space2 ? box.maxWidth - AppSizes.space2 : 0),
                        child: Text(time, maxLines: 1, softWrap: false, overflow: TextOverflow.ellipsis, style: metaStyle),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
