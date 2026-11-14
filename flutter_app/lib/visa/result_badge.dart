import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import 'models.dart';

// 결과 뱃지 (React의 .badge-result-*): 승인은 청자 계열, 불허는 회색(빨강은 되돌릴 수 없는 동작 전용이라 쓰지 않는다).
// 모르는 code는 색 없는 기본 뱃지. 후기 목록 행과 후기 상세가 같이 쓴다.
class ResultBadge extends StatelessWidget {
  const ResultBadge({super.key, required this.result});

  final VisaOption result;

  @override
  Widget build(BuildContext context) {
    final (ink, bg) = switch (result.code) {
      'APPROVED' => (AppColors.celadonInk, AppColors.celadonSoft),
      'DENIED' => (AppColors.muted, AppColors.badgeChatBg),
      _ => (AppColors.ink, Colors.transparent),
    };
    return AppBadge(text: result.name, ink: ink, bg: bg);
  }
}
