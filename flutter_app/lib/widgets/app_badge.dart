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

// 말머리 뱃지: code마다 색이 다르다 (CHAT 회색, QUESTION 파랑, CAMPUS_LIFE 청자).
// 모르는 code는 React의 .badge 기본처럼 색 없는 뱃지(ink 글자, 배경 없음).
// 글 목록의 행과 글 상세가 같이 쓴다.
class CategoryBadge extends StatelessWidget {
  const CategoryBadge({super.key, required this.code, required this.name});

  final String code;
  final String name;

  @override
  Widget build(BuildContext context) {
    final (ink, bg) = switch (code) {
      'CHAT' => (AppColors.muted, AppColors.badgeChatBg),
      'QUESTION' => (AppColors.badgeQuestionInk, AppColors.badgeQuestionBg),
      'CAMPUS_LIFE' => (AppColors.celadonInk, AppColors.celadonSoft),
      _ => (AppColors.ink, Colors.transparent),
    };
    return AppBadge(text: name, ink: ink, bg: bg);
  }
}
