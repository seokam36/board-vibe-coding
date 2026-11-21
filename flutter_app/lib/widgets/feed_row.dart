import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 피드형 목록의 줄 한 개 틀 (게시판·비자 후기·중고 목록·쪽지함·내 정보 바로가기에서 같이 쓴다).
// 줄 어디를 눌러도 이동하고, 스크린리더에는 줄 하나가 링크 하나로 읽힌다.
// 읽을 말(label)은 호출하는 쪽이 직접 정해 주고, 안쪽 글자는 excludeSemantics로 빼서 중복해 읽지 않게 한다.
// 줄 안의 모양(아바타·제목 등)은 child로 받는다.
class FeedRow extends StatelessWidget {
  const FeedRow({
    super.key,
    required this.label,
    required this.onTap,
    required this.first,
    required this.child,
  });

  final String label; // 스크린리더가 이 줄을 읽을 말
  final VoidCallback onTap;
  final bool first; // 첫 줄은 위 선이 없다 (판 테두리와 겹치지 않게)
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      link: true,
      label: label,
      excludeSemantics: true,
      onTap: onTap, // 스크린리더로 누를 수 있게 같이 준다
      child: InkWell(
        onTap: onTap,
        hoverColor: AppColors.hover, // 올리거나 누르면 조용히 hover 바탕
        splashColor: AppColors.hover,
        highlightColor: AppColors.hover,
        child: Container(
          constraints: const BoxConstraints(minHeight: 48), // 눌리는 높이
          padding: const EdgeInsets.symmetric(
            horizontal: AppSizes.space4,
            vertical: AppSizes.space3,
          ),
          decoration: BoxDecoration(
            border: first
                ? null
                : const Border(top: BorderSide(color: AppColors.line)),
          ),
          child: child,
        ),
      ),
    );
  }
}
