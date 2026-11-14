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

// 위험 버튼 모양 (React의 .btn-danger): 평소엔 빨간 글자·선만, 마우스를 올리면 옅은 빨강, 누르면 진한 빨강
final ButtonStyle dangerSmallButtonStyle = smallButtonStyle
    .merge(
      OutlinedButton.styleFrom(
        foregroundColor: AppColors.danger,
        side: const BorderSide(color: AppColors.danger),
      ),
    )
    .copyWith(
      backgroundColor: WidgetStateProperty.resolveWith((states) {
        if (states.contains(WidgetState.pressed)) return AppColors.dangerStrong;
        if (states.contains(WidgetState.hovered)) return AppColors.dangerSoft;
        return null;
      }),
    );
