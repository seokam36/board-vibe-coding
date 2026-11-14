import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// "이름: 값" 한 줄 (React의 .report-meta의 div+dt+dd): 왼쪽 이름은 72 폭 muted, 오른쪽 값은 남는 폭.
// 신고 목록의 행 메타와 대화 보기 머리가 같이 쓴다. 값의 줄바꿈은 그대로 보인다(Text 기본).
class ReportMetaRow extends StatelessWidget {
  const ReportMetaRow({super.key, required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(label, style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65)),
          ),
          const SizedBox(width: AppSizes.space2),
          Expanded(
            child: Text(value, style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.ink, height: 1.65)),
          ),
        ],
      ),
    );
  }
}
