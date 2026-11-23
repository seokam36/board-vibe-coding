import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'nav_link.dart';

// 피드형 목록의 줄 한 개 틀 (게시판·비자 후기·중고 목록·쪽지함·내 정보 바로가기·챗봇 참고한 글에서 같이 쓴다).
// 줄 어디를 눌러도 이동하고, 스크린리더에는 줄 하나가 링크 하나로 읽힌다.
// 읽을 말(label)은 호출하는 쪽이 직접 정해 주고, 안쪽 글자는 excludeSemantics로 빼서 중복해 읽지 않게 한다.
// 줄 안의 모양(아바타·제목 등)은 child로 받는다.
// 이동할 곳은 onTap(함수)이 아니라 path(앱 안 주소)로 받는다: 웹에서 진짜 <a href> 링크가 되려면 주소가 필요하다.
class FeedRow extends StatelessWidget {
  const FeedRow({
    super.key,
    required this.label,
    required this.path,
    required this.first,
    required this.child,
  });

  final String label; // 스크린리더가 이 줄을 읽을 말
  final String path; // 눌렀을 때 이동할 앱 안 주소 (예: /posts/3)
  final bool first; // 첫 줄은 위 선이 없다 (판 테두리와 겹치지 않게)
  final Widget child;

  // 줄 안쪽 모양 (눌림 효과·높이·패딩·위 선). 웹과 앱이 같이 쓴다.
  // wrap: NavLink가 주는 "Semantics로 한 번 더 감싸는 함수" (앱에서는 그대로 돌려준다).
  Widget _body(VoidCallback onTap, Widget Function(Widget) wrap) {
    final row = Container(
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
    );
    return InkWell(
      onTap: onTap,
      hoverColor: AppColors.hover, // 올리거나 누르면 조용히 hover 바탕
      splashColor: AppColors.hover,
      highlightColor: AppColors.hover,
      child: wrap(row),
    );
  }

  @override
  Widget build(BuildContext context) {
    // 웹은 진짜 <a href> 링크(재로드 없이 라우터로 이동), 앱은 기존 context.go(뒤로 가기 방문 기록 AppHistory도 그대로)
    return NavLink(
      uri: Uri.parse(path),
      label: label,
      onOpen: () => context.go(path),
      builder: _body,
    );
  }
}