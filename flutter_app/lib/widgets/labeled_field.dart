import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 위에 라벨 글자가 있는 입력칸 (React의 <label>제목<input placeholder="제목"/></label> + .field 아래 여백 16).
// maxLength가 있으면 그 글자 수를 넘겨 입력되지 않는다(글자 수 표시는 없다). lines가 1보다 크면 여러 줄 칸.
// 글쓰기·글 수정 화면이 같이 쓴다.
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
  });

  final Key fieldKey;
  final String label;
  final TextEditingController controller;
  final String? hint; // 칸이 비었을 때 보이는 안내 글자 (placeholder)
  final int? maxLength;
  final int lines;
  final VoidCallback? onSubmitted; // 한 줄 칸에서 Enter를 눌렀을 때

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSizes.space4),
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
              decoration: InputDecoration(
                hintText: hint,
                hintStyle: const TextStyle(color: AppColors.muted),
              ),
              inputFormatters: [if (maxLength != null) LengthLimitingTextInputFormatter(maxLength)],
              // 여러 줄 칸은 줄 수를 고정하고(10줄) 넘치면 안에서 스크롤된다
              minLines: lines,
              maxLines: lines,
              keyboardType: lines > 1 ? TextInputType.multiline : TextInputType.text,
              textInputAction: lines > 1 ? TextInputAction.newline : TextInputAction.done,
              onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
            ),
          ),
        ],
      ),
    );
  }
}
