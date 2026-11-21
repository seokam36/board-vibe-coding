import 'package:material_ui/material_ui.dart';

import '../common/utf16_length_formatter.dart';
import '../theme/app_colors.dart';

// 글·댓글 입력칸 글자 모양: 글 보기 본문과 같은 15·줄 간격 1.65 (글쓰기·글 수정·댓글 쓰기·댓글 수정이 같이 쓴다)
const postBodyInputStyle = TextStyle(fontSize: AppSizes.fsBody, height: AppSizes.bodyLineHeight);

// 위에 라벨 글자가 있는 입력칸 (라벨 + 입력칸 + .field 아래 여백 16, 칸 안 힌트 글자는 선택).
// maxLength가 있으면 그 글자 수를 넘겨 입력되지 않는다(글자 수 표시는 below로 붙일 수 있다). lines가 1보다 크면 여러 줄 칸.
// 글쓰기·글 수정, 중고 글 폼, 비자 후기 폼, 인증 승인 폼이 같이 쓴다.
class LabeledField extends StatelessWidget {
  const LabeledField({
    super.key,
    required this.fieldKey,
    required this.label,
    required this.controller,
    this.hint,
    this.maxLength,
    this.lines = 1,
    this.onSubmitted,
    this.textStyle,
    this.keyboardType,
    this.below,
  });

  final Key fieldKey;
  final String label;
  final TextEditingController controller;
  final String? hint; // 칸이 비었을 때 보이는 안내 글자 (placeholder)
  final int? maxLength;
  final int lines;
  final VoidCallback? onSubmitted; // 한 줄 칸에서 Enter를 눌렀을 때
  final TextStyle? textStyle; // 입력 글자 모양(없으면 기본). 글 내용칸이 글 보기 본문과 같은 줄 간격을 쓰려고 넘긴다
  final TextInputType? keyboardType; // 숫자 칸 등 (없으면 한 줄은 글자, 여러 줄은 multiline)
  final Widget? below; // 칸 바로 아래에 붙일 글자 수 안내 등 (있으면 아래 여백 16은 이 위젯이 가진다)

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(bottom: below == null ? AppSizes.space4 : 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 눈에 보이는 라벨은 스크린리더에서 빼고, 입력칸 자체에 이름을 붙인다(두 번 읽지 않게)
          ExcludeSemantics(
            child: Text(
              label,
              style: const TextStyle(
                fontSize: AppSizes.fsSmall2,
                fontWeight: FontWeight.w500,
                color: AppColors.ink,
              ),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            label: label,
            child: TextField(
              key: fieldKey,
              controller: controller,
              style: textStyle,
              decoration: InputDecoration(
                hintText: hint,
                hintStyle: const TextStyle(color: AppColors.muted),
              ),
              // UTF-16 길이로 제한한다 (React maxLength와 같은 단위, 이모지 = 2)
              inputFormatters: [if (maxLength != null) Utf16LengthFormatter(maxLength!)],
              // 여러 줄 칸은 줄 수(lines 인자)를 고정하고 넘치면 안에서 스크롤된다
              minLines: lines,
              maxLines: lines,
              keyboardType: keyboardType ?? (lines > 1 ? TextInputType.multiline : TextInputType.text),
              textInputAction: lines > 1 ? TextInputAction.newline : TextInputAction.done,
              onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
            ),
          ),
          ?below,
        ],
      ),
    );
  }
}
