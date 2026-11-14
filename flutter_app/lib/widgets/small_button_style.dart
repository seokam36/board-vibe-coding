import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 작은 버튼 모양 (React의 .btn-sm): 높이 36, 좌우 여백 12. 나머지(선·글자 색)는 앱 테마 그대로.
// 글 상세의 작업 줄(공감·수정·삭제·신고)과 신고 양식이 같이 쓴다.
final ButtonStyle smallButtonStyle = OutlinedButton.styleFrom(
  minimumSize: const Size(0, 36),
  padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
);

// 작은 주요 버튼 (React의 .btn-sm.btn-primary): 크기만 작게, 색은 앱 테마의 주요 버튼 그대로
final ButtonStyle smallFilledButtonStyle = FilledButton.styleFrom(
  minimumSize: const Size(0, 36),
  padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
);

// 위험 버튼의 색 (React의 .btn-danger): 평소엔 빨간 글자·선만, 마우스를 올리면 옅은 빨강 배경,
// 누르는 동안엔 React의 :active처럼 배경·선이 진한 빨강(danger-strong)이고 글자는 본문색(ink).
// 비활성은 React의 opacity 0.5처럼 반투명. 크기는 건드리지 않아 확인 창(보통 크기)과 작업 줄(작은 크기)이 같이 쓴다.
final ButtonStyle dangerButtonStyle = OutlinedButton.styleFrom().copyWith(
  foregroundColor: WidgetStateProperty.resolveWith((states) {
    if (states.contains(WidgetState.disabled)) return AppColors.danger.withValues(alpha: 0.5);
    if (states.contains(WidgetState.pressed)) return AppColors.ink;
    return AppColors.danger;
  }),
  side: WidgetStateProperty.resolveWith((states) {
    if (states.contains(WidgetState.disabled)) return BorderSide(color: AppColors.danger.withValues(alpha: 0.5));
    if (states.contains(WidgetState.pressed)) return const BorderSide(color: AppColors.dangerStrong);
    return const BorderSide(color: AppColors.danger);
  }),
  backgroundColor: WidgetStateProperty.resolveWith((states) {
    if (states.contains(WidgetState.disabled)) return null;
    if (states.contains(WidgetState.pressed)) return AppColors.dangerStrong;
    if (states.contains(WidgetState.hovered)) return AppColors.dangerSoft;
    return null;
  }),
);

// 작은 위험 버튼 (글 상세·댓글의 [삭제])
final ButtonStyle dangerSmallButtonStyle = smallButtonStyle.merge(dangerButtonStyle);
