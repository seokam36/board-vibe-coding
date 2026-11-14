import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 드롭다운 한 개 (React의 <select aria-label>). options는 (값, 보여 줄 글자) 목록.
// 스크린리더가 label("학교"·"말머리")로 읽는다. 글 목록 필터와 글쓰기·수정의 말머리 칸이 같이 쓴다.
class SelectField extends StatelessWidget {
  const SelectField({
    super.key,
    required this.fieldKey,
    required this.label,
    required this.width,
    required this.value,
    required this.options,
    required this.onChanged,
  });

  final Key fieldKey;
  final String label;
  final double width;
  final String value;
  final List<(String, String)> options;
  final void Function(String value) onChanged;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: label,
      child: Container(
        width: width,
        height: 40,
        padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
        decoration: BoxDecoration(
          color: AppColors.paper,
          border: Border.all(color: AppColors.line),
          borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
        ),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            key: fieldKey,
            isExpanded: true, // 칸 폭에 맞추고 긴 이름은 말줄임
            value: value,
            dropdownColor: AppColors.paper,
            style: const TextStyle(fontSize: AppSizes.fsBody, color: AppColors.ink),
            items: [
              for (final (v, text) in options)
                DropdownMenuItem<String>(
                  value: v,
                  child: Text(text, overflow: TextOverflow.ellipsis),
                ),
            ],
            onChanged: (v) {
              if (v != null && v != value) onChanged(v);
            },
          ),
        ),
      ),
    );
  }
}
