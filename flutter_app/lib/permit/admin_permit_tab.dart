import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 탭 하나 (신고 관리 화면 탭과 같은 모양)
class AdminPermitTab extends StatelessWidget {
  const AdminPermitTab({
    super.key,
    required this.label,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final bool enabled; // false면 눌러도 반응하지 않는다
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.accent : AppColors.muted;
    return Semantics(
      button: true,
      selected: selected,
      child: InkWell(
        onTap: enabled ? onTap : null,
        child: Container(
          constraints: const BoxConstraints(minHeight: 40),
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space1),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: selected ? AppColors.accent : Colors.transparent, width: 2)),
          ),
          child: Center(
            widthFactor: 1,
            child: Text(
              label,
              style: TextStyle(
                fontSize: AppSizes.fsBody,
                color: color,
                fontWeight: selected ? FontWeight.w500 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
