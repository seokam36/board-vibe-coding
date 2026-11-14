import 'package:material_ui/material_ui.dart';

import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/notice.dart';
import '../widgets/relogin_notice.dart';

// 쪽지 길이 한도 (서버 규칙과 같다: 1~1000자). 글자 수는 서버처럼 UTF-16 길이(text.length)로 센다
const messageMaxLength = 1000;

// 쪽지 쓰는 칸 하나: 글자 수 표시 + 보내기 버튼 + 실패 안내 (React의 MessageComposer).
// "판매자에게 쪽지"(상세)와 "답장"(대화 화면)이 똑같이 쓴다. 실제 요청은 send가 하고, 실패하면 던져야 한다.
class MessageComposer extends StatefulWidget {
  const MessageComposer({
    super.key,
    required this.label,
    required this.placeholder,
    required this.submitLabel,
    required this.errorTable,
    required this.fallbackError,
    required this.send,
    this.onSubmitStart,
  });

  final String label; // 입력칸의 접근성 이름
  final String placeholder;
  final String submitLabel; // 평소 버튼 글자 (보내는 중에는 "보내는 중..."으로 바뀐다)
  final Map<int, String> errorTable; // 상태코드별 실패 문구
  final String fallbackError; // 표에 없는 실패의 기본 문구
  final Future<void> Function(String content) send;
  // 보내기를 누른 순간(요청 직전) 부모에게 알린다. 부모는 "로그인이 풀려도 이 칸을 없애지 않는다"고 기억한다
  final VoidCallback? onSubmitStart;

  @override
  State<MessageComposer> createState() => _MessageComposerState();
}

class _MessageComposerState extends State<MessageComposer> {
  final _controller = TextEditingController();
  final _focus = FocusNode(); // 보낸 뒤 다시 바로 쓸 수 있게 포커스를 돌려줄 때 쓴다
  bool _busy = false; // 보내는 중이면 버튼을 잠가 두 번 눌림을 막는다
  bool _sending = false; // 화면이 다시 그려지기 전의 연타까지 막는 즉시 잠금
  String? _error;
  bool _needLogin = false; // 401(재발급 실패): 로그인 안내

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

  Future<void> _submit() async {
    if (_empty || _sending) return;
    _sending = true;
    widget.onSubmitStart?.call();
    setState(() {
      _busy = true;
      _error = null;
      _needLogin = false;
    });
    try {
      await widget.send(_controller.text);
      if (!mounted) return;
      _controller.clear(); // 성공하면 입력칸을 비운다 (상세에서는 이 뒤에 화면이 바뀐다)
      _focus.requestFocus(); // 키보드로 쓰는 사람이 이어서 쓸 수 있게 (보내는 동안 잠긴 버튼에서 포커스를 잃기 때문)
    } catch (err) {
      final info = describeError(err, widget.errorTable, widget.fallbackError);
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
      });
    } finally {
      _sending = false;
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            label: widget.label,
            child: TextField(
              key: const ValueKey('message-composer-field'),
              controller: _controller,
              focusNode: _focus,
              decoration: InputDecoration(
                hintText: widget.placeholder,
                hintStyle: const TextStyle(color: AppColors.muted),
              ),
              // UTF-16 길이로 제한한다 (React maxLength와 같은 단위, 이모지 = 2)
              inputFormatters: const [Utf16LengthFormatter(messageMaxLength)],
              minLines: 3,
              maxLines: 3,
              keyboardType: TextInputType.multiline,
            ),
          ),
          // 아래줄: 글자 수(왼쪽)와 보내기 버튼(오른쪽)
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space2, bottom: AppSizes.space3),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '${_controller.text.length} / $messageMaxLength',
                  style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                ),
                Flexible(
                  child: FilledButton(
                    onPressed: (_empty || _busy) ? null : _submit,
                    child: Text(_busy ? '보내는 중...' : widget.submitLabel),
                  ),
                ),
              ],
            ),
          ),
          // 실패 안내는 입력칸 아래에 둔다 (쓴 내용은 그대로 남는다)
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (_needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}
