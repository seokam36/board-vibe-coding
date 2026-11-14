import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 글자 링크 (React의 <a>): 청자색 밑줄 글자. 누르면 onTap(보통 context.go)
class AppLink extends StatelessWidget {
  const AppLink({super.key, required this.label, required this.onTap, this.style});

  final String label;
  final VoidCallback onTap;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      link: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: AppSizes.space1),
          child: Text(
            label,
            style: const TextStyle(
              fontSize: AppSizes.fsSmall2,
              color: AppColors.celadon,
              decoration: TextDecoration.underline,
              decorationColor: AppColors.celadon,
            ).merge(style),
          ),
        ),
      ),
    );
  }
}
