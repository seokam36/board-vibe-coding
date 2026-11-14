import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 판 안의 작은 제목 (React의 h2). danger면 빨간 글자 (React의 .panel-danger h2)
// 내 정보 화면의 각 폼과 학교 인증 섹션이 같이 쓴다.
class SectionHeading extends StatelessWidget {
  const SectionHeading(this.text, {super.key, this.danger = false});

  final String text;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSizes.space3),
      child: Semantics(
        header: true,
        headingLevel: 2,
        child: Text(
          text,
          style: TextStyle(
            fontSize: AppSizes.fsRow,
            fontWeight: FontWeight.w700,
            color: danger ? AppColors.danger : AppColors.ink,
          ),
        ),
      ),
    );
  }
}

// 입력칸 아래 작은 안내 글자 (React의 .hint)
class FieldHint extends StatelessWidget {
  const FieldHint(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space1, bottom: AppSizes.space4),
      child: Text(text, style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted)),
    );
  }
}
