import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';

// 중고·나눔 상태 뱃지 (React의 .badge-ON_SALE 등): 판매중 청자, 예약중 파랑, 거래완료 회색(빨강은 쓰지 않는다).
// 모르는 code는 색 없는 뱃지. 글자는 항상 서버가 준 name을 그대로 쓴다.
class MarketStatusBadge extends StatelessWidget {
  const MarketStatusBadge({super.key, required this.code, required this.name});

  final String code;
  final String name;

  @override
  Widget build(BuildContext context) {
    final (ink, bg) = switch (code) {
      'ON_SALE' => (AppColors.celadonInk, AppColors.celadonSoft),
      'RESERVED' => (AppColors.badgeQuestionInk, AppColors.badgeQuestionBg),
      'DONE' => (AppColors.muted, AppColors.badgeChatBg),
      _ => (AppColors.ink, Colors.transparent),
    };
    return AppBadge(text: name, ink: ink, bg: bg);
  }
}
