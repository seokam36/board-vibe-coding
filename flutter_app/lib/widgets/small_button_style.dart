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
// 누르는 동안엔 React의 :active처럼 배경·선이 진한 빨강(danger-strong)이고 글자는 흰색(paper).
// 비활성은 React의 opacity 0.5처럼 반투명. 크기는 건드리지 않아 확인 창(보통 크기)과 작업 줄(작은 크기)이 같이 쓴다.
final ButtonStyle dangerButtonStyle = OutlinedButton.styleFrom().copyWith(
  foregroundColor: WidgetStateProperty.resolveWith((states) {
    if (states.contains(WidgetState.disabled)) return AppColors.danger.withValues(alpha: 0.5);
    if (states.contains(WidgetState.pressed)) return AppColors.paper;
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

// 조용한 작은 버튼 (S26-e, 남의 댓글의 [신고]): 테두리 없이 muted 13px 글자(+작은 깃발 아이콘은 쓰는 쪽에서 child로).
// 여전히 OutlinedButton이라 높이는 36 이상. 왼쪽 정렬이고 좌우 여백은 6이라 글자가 댓글 내용 왼쪽 선에서 약간 안쪽에 놓인다.
// 마우스를 올리거나 키보드 포커스가 오면 hover 바탕이 깔리고(포커스는 accent 1px 테두리 추가), 누르면 조금 더 진해진다.
final ButtonStyle quietSmallButtonStyle = smallButtonStyle.copyWith(
  side: WidgetStateProperty.resolveWith(
    (states) => states.contains(WidgetState.focused) ? const BorderSide(color: AppColors.accent) : BorderSide.none,
  ),
  minimumSize: const WidgetStatePropertyAll(Size(48, 36)),
  padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: 6)),
  alignment: Alignment.centerLeft,
  foregroundColor: const WidgetStatePropertyAll(AppColors.muted),
  textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: AppSizes.fsSmall)),
  overlayColor: WidgetStateProperty.resolveWith((states) {
    if (states.contains(WidgetState.pressed)) return AppColors.line;
    if (states.contains(WidgetState.focused) || states.contains(WidgetState.hovered)) return AppColors.hover;
    return null;
  }),
);
