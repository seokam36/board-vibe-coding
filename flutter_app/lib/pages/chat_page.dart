import 'package:dio/dio.dart' show Options;
import 'package:flutter/semantics.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../chat/models.dart';
import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../market/market_format.dart';
import '../market/market_status_badge.dart';
import '../theme/app_colors.dart';
import '../widgets/feed_row.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';

// 질문 길이 한도. 서버는 앞뒤 공백을 뺀 코드포인트 500자, 화면은 다른 입력칸들과 같은 UTF-16 500(이모지는 2칸)이라
// 조금 더 엄격하다 — 화면을 통과한 질문이 길이 때문에 400이 되지 않는다
const chatMaxLength = 500;

// 이 요청 하나에만 거는 화면 쪽 제한 시간: 서버 AI 제한(제미나이 30초·올라마 60초 중 큰 쪽 60초) + 여유. 다른 요청에는 안 걸린다
const _chatTimeout = Duration(seconds: 90);

// 질문 칸 위 안내 문구(S18-d2). 서버의 "범위 안내" 답과 같은 뜻이다: 중고·나눔 글만 찾는다
const chatGuide = '중고·나눔 글을 찾아 주는 챗봇이에요. 물건 이름과 가격을 넣어 물어보세요.';
const chatGuideExample = '예) 3만 원 이하 자전거 있어?';
// 질문 내용이 구글 AI(제미나이)로 넘어간다는 개인정보 안내(S18-g). 늘 보인다.
// 운영(gemini) 기준 문구 — 로컬 ollama·off에서는 실제로 구글로 안 간다
const chatPrivacyNotice = '질문은 구글 AI로 전송돼요. 연락처 같은 개인정보는 쓰지 마세요.';

// 실패 상태코드별 문구. 401은 표에 두지 않는다 → describeError가 로그인 안내(NeedLogin)로 바꾼다
const _chatErrors = <int, String>{
  400: ErrorMessages.chatQuestionInvalid,
  403: csrfMessage, // 보안 토큰(CSRF) 문제
  429: ErrorMessages.chatRateLimited, // 질문 횟수 제한 초과
  503: ErrorMessages.chatUnavailable,
  504: ErrorMessages.chatTimeout,
};

// 중고 글 챗봇 "/chat" (S17-d): 질문을 보내면 AI가 중고 글을 찾아 답하고, 참고한 글(출처)을 같이 보여 준다.
// 로그인한 사람만 (라우터 문지기가 막는다). 대화 기억이 없어서 마지막 질문·답 하나만 보여 준다.
class ChatPage extends StatefulWidget {
  const ChatPage({super.key});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  final _controller = TextEditingController();
  final _focus = FocusNode(); // 답을 받은 뒤 다시 바로 물을 수 있게 포커스를 돌려줄 때 쓴다
  bool _busy = false; // 보내는 중이면 버튼을 잠근다 (화면에 보이는 잠금)
  bool _sending = false; // setState 전에 바로 잡히는 잠금 — _busy와 겹치지만 한 번 더 막는 안전장치
  String? _error;
  bool _needLogin = false; // 401(재발급 실패): 로그인 안내
  String? _question; // 답이 온 질문 (답 위에 보여 준다)
  ChatAnswer? _result;

  @override
  void initState() {
    super.initState();
    // 글자가 바뀔 때마다 글자 수 표시와 버튼 활성을 다시 그린다
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  // 비었거나 공백뿐이면 보낼 수 없다 (서버도 400)
  bool get _empty => _controller.text.trim().isEmpty;

  // 보내기: 버튼과 Enter가 같이 쓴다. 보내는 중이거나 빈 값이면 아무것도 하지 않는다 (요청 0번 또는 1번)
  Future<void> _submit() async {
    if (_empty || _sending) return;
    _sending = true;
    final question = _controller.text;
    setState(() {
      _busy = true;
      _error = null;
      _needLogin = false;
      _question = null; // 이전 답·출처는 지우고 "만드는 중"만 보여 준다
      _result = null;
    });
    try {
      final res = await context.read<ApiClient>().dio.post<Map<String, dynamic>>(
        '/chat',
        data: {'question': question},
        // 이 요청에만 90초 제한 (웹에서는 요청 전체 제한 시간). 401 재시도도 같은 옵션으로 다시 나간다
        options: Options(receiveTimeout: _chatTimeout),
      );
      final answer = ChatAnswer.fromJson(res.data!);
      if (!mounted) return;
      setState(() {
        _question = question;
        _result = answer;
      });
      // 성공하면 질문 칸을 비운다. 기다리는 동안 새로 쓴 글자가 있으면(칸의 글자가 보낸 것과 다르면) 지우지 않는다
      if (_controller.text == question) _controller.clear();
      _focus.requestFocus(); // 키보드로 쓰는 사람이 이어서 물을 수 있게
    } catch (err) {
      if (!mounted) return;
      // 화면 쪽 시간 초과는 응답이 없는 에러라 describeError가 "연결 불가"로 읽는다 → 먼저 따로 가른다
      if (isTimeoutError(err)) {
        setState(() => _error = ErrorMessages.chatTimeout);
      } else {
        final info = describeError(err, _chatErrors, ErrorMessages.chatFailed);
        setState(() {
          if (info is NeedLogin) {
            _needLogin = true;
          } else if (info is ErrorMessage) {
            _error = info.message;
          }
        });
      }
    } finally {
      _sending = false;
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    // 지금은 중고 글(MARKET_ITEM)만 그린다. 모르는 종류의 줄은 건너뛴다
    final sources = result == null ? const <ChatSource>[] : [for (final s in result.sources) if (s.type == 'MARKET_ITEM') s];
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('중고 글 챗봇'),
          // 늘 보이는 안내: AI 답은 틀릴 수 있으니 출처로 확인하게 한다
          const Notice(kind: NoticeKind.info, message: "AI 답은 틀릴 수 있어요. 아래 '참고한 글'에서 직접 확인해 주세요"),
          // 질문 판(S26-k7): 안내 세 줄·입력칸·글자 수·보내기를 흰 판 하나에 담는다 (판 아래 여백 16은 Panel이 가진다)
          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 늘 보이는 사용 안내(S18-d2): 이 챗봇은 중고·나눔 글 찾기만 한다고 미리 알려 준다. 답이 나온 뒤에도 그대로 둔다.
                // container: 세 줄(범위·예시·구글 전송 안내)을 스크린리더가 한 덩어리로 읽게 묶는다. Text는 길면 줄바꿈되어 320px에서도 넘치지 않는다
                Semantics(
                  container: true,
                  child: const Padding(
                    padding: EdgeInsets.only(bottom: AppSizes.space3),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(chatGuide, style: TextStyle(color: AppColors.ink, height: 1.5)),
                        Text(chatGuideExample, style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.5)),
                        SizedBox(height: AppSizes.space1), // 예시 줄과 살짝 떼기
                        Text(chatPrivacyNotice, style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.5)),
                      ],
                    ),
                  ),
                ),
                Semantics(
                  label: '질문',
                  child: TextField(
                    key: const ValueKey('chat-field'),
                    controller: _controller,
                    focusNode: _focus,
                    decoration: const InputDecoration(
                      hintText: '질문을 입력하세요',
                      hintStyle: TextStyle(color: AppColors.muted),
                    ),
                    // UTF-16 길이로 제한한다 (이모지 = 2칸. 서버의 코드포인트 500자보다 조금 더 엄격해서 400이 안 난다)
                    inputFormatters: const [Utf16LengthFormatter(chatMaxLength)],
                    textInputAction: TextInputAction.send,
                    // 포커스는 남기되, 한글 조합 중인 마지막 글자가 빠지지 않게 조합은 직접 확정한다 (검색칸과 같은 방식)
                    onEditingComplete: _controller.clearComposing,
                    // Enter로도 보낸다. 보내는 중이면 _submit이 무시한다
                    onSubmitted: (_) => _submit(),
                  ),
                ),
                // 아래줄: 글자 수(왼쪽)와 보내기 버튼(오른쪽)
                Padding(
                  padding: const EdgeInsets.only(top: AppSizes.space2),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        '${_controller.text.length} / $chatMaxLength',
                        style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                      ),
                      FilledButton(
                        onPressed: (_empty || _busy) ? null : _submit,
                        child: const Text('보내기'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          // 실패 안내는 입력칸 아래에 둔다 (쓴 질문은 그대로 남는다)
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (_needLogin) const ReloginNotice(),
          if (_busy) const Notice(kind: NoticeKind.info, message: '답을 만드는 중...'),
          if (_question != null && result != null) ...[
            _QuestionBubble(text: _question!),
            _AnswerBubble(text: result.answer),
          ],
          if (sources.isNotEmpty) _Sources(sources: sources),
        ],
      ),
    );
  }
}

// 내가 보낸 질문 (오른쪽 정렬). 순수 글자
class _QuestionBubble extends StatelessWidget {
  const _QuestionBubble({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerRight,
      child: Container(
        margin: const EdgeInsets.only(bottom: AppSizes.space3),
        padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3, vertical: AppSizes.space2),
        decoration: BoxDecoration(
          color: AppColors.accentSoft,
          borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
        ),
        child: Text(text, style: const TextStyle(color: AppColors.ink, height: 1.5)),
      ),
    );
  }
}

// AI의 답 (왼쪽 정렬). 스크린리더는 답이 나타나면 status로 알려 준다.
// 중요: 반드시 Text 하나로 글자 그대로만 그린다. 링크·마크다운으로 바꾸지 않는다.
// 이유: 답은 글 작성자가 본문에 숨긴 지시에 영향받을 수 있어(프롬프트 주입), 답 안의 주소를 눌러 가게 만들면
// 악의적인 링크가 AI 입을 빌려 나올 수 있다. 글로 가는 길은 서버가 준 출처(sources)로만 연다.
class _AnswerBubble extends StatelessWidget {
  const _AnswerBubble({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      container: true,
      role: SemanticsRole.status,
      child: Align(
        alignment: Alignment.centerLeft,
        child: Container(
          key: const ValueKey('chat-answer'),
          margin: const EdgeInsets.only(bottom: AppSizes.space4),
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3, vertical: AppSizes.space2),
          decoration: BoxDecoration(
            color: AppColors.paper,
            border: Border.all(color: AppColors.line),
            borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
          ),
          child: Text(text, style: const TextStyle(color: AppColors.ink, height: 1.5)),
        ),
      ),
    );
  }
}

// "참고한 글": 서버가 AI에게 실제로 준 글을 응답 순서대로. 줄을 누르면 글 상세로 간다
class _Sources extends StatelessWidget {
  const _Sources({required this.sources});

  final List<ChatSource> sources;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Padding(
          padding: EdgeInsets.only(bottom: AppSizes.space2),
          child: Text('참고한 글', style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.ink)),
        ),
        // FeedPanel엔 바깥 여백이 없어 아래 여백 16을 직접 준다
        Padding(
          padding: const EdgeInsets.only(bottom: AppSizes.space4),
          child: FeedPanel(
            children: [
              for (var i = 0; i < sources.length; i++)
                _SourceRow(
                  // 같은 글이 두 번 와도 키가 겹쳐 화면이 죽지 않게 순번을 섞는다
                  key: ValueKey('chat-source-$i-${sources[i].id}'),
                  source: sources[i],
                  first: i == 0,
                ),
            ],
          ),
        ),
      ],
    );
  }
}

// 참고한 글 한 줄: 제목(16·700) + 아래 가격·상태. 줄 어디를 눌러도(가격 글자 포함) 글 상세로 간다 (중고 목록 줄과 같은 방식)
class _SourceRow extends StatelessWidget {
  const _SourceRow({super.key, required this.source, required this.first});

  final ChatSource source;
  final bool first;

  @override
  Widget build(BuildContext context) {
    final price = formatPrice(source.tradeType.code, source.price);
    return FeedRow(
      // 줄 하나를 링크 하나로 읽게 읽을 말을 정해 준다 (제목, 가격, 상태 순서)
      label: [source.title, price, source.status.name].join(', '),
      onTap: () => context.go('/market/${source.id}'),
      first: first,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 제목: 길면 줄바꿈한다(말줄임 안 함)
          Text(
            source.title,
            style: const TextStyle(
              fontSize: AppSizes.fsFeedTitle,
              fontWeight: FontWeight.w700,
              color: AppColors.ink,
              height: 1.5,
            ),
          ),
          const SizedBox(height: AppSizes.space1), // 제목과 메타 사이
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSizes.space2,
            children: [
              Text(price, style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65, fontWeight: FontWeight.w700)),
              MarketStatusBadge(code: source.status.code, name: source.status.name),
            ],
          ),
        ],
      ),
    );
  }
}
