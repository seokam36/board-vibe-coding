import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 뱃지 한 개 (React의 .badge). school이면 학교 뱃지(배경 paper + 테두리 line + 글자 muted).
// 글 목록의 행과 글쓰기·수정 화면의 학교 표시가 같이 쓴다.
class AppBadge extends StatelessWidget {
  const AppBadge({super.key, required this.text, this.ink = AppColors.muted, this.bg = AppColors.paper, this.school = false});

  final String text;
  final Color ink;
  final Color bg;
  final bool school;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      decoration: BoxDecoration(
        color: bg,
        border: school ? Border.all(color: AppColors.line) : null,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis, // 긴 학교명이 좁은 화면에서 넘치지 않게
        style: TextStyle(
          fontSize: AppSizes.fsSmall,
          fontWeight: FontWeight.w500,
          color: ink,
          height: 1.6,
        ),
      ),
    );
  }
}
