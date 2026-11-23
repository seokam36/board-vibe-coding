import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/link.dart';

import '../theme/app_colors.dart';

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
  // wrap: 줄 모양(Container)을 InkWell 안에서 한 번 더 감쌀 때만 준다 (웹은 Semantics로 감싼다, 앱은 안 준다).
  Widget _body({required VoidCallback? onTap, Widget Function(Widget)? wrap}) {
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
      child: wrap == null ? row : wrap(row),
    );
  }

  @override
  Widget build(BuildContext context) {
    final uri = Uri.parse(path);
    if (kIsWeb) {
      // 웹: url_launcher의 Link로 감싸 진짜 링크(<a href>)로 만든다. 스크린리더가 "링크"로 읽는다.
      // 주소만 주는 Semantics(linkUrl)는 누르면 문서가 통째로 다시 열려서 쓰지 않는다.
      // Link가 link 의미를 이미 주므로 안쪽에서 link: true를 또 주지 않는다.
      // onTap에 followLink를 꼭 연결해야 재로드 없이 라우터로 이동한다.
      return Link(
        uri: uri,
        target: LinkTarget.self,
        // uri를 항상 주므로 followLink는 늘 있다 (그대로 onTap에 연결)
        builder: (context, followLink) => _body(
          onTap: followLink,
          wrap: (inner) => Semantics(label: label, excludeSemantics: true, child: inner),
        ),
      );
    }
    // 앱(안드로이드 등): Link는 상대 주소를 라우터 채널로 보내지만, 앱에서는 기존 context.go가 확실하고
    // 뒤로 가기 방문 기록(AppHistory)도 그대로 남는다. 그래서 이동은 context.go, 의미만 링크로 맞춘다.
    void open() => context.go(path); // 눌러도, 스크린리더로 눌러도 같은 이동
    return Semantics(
      link: true,
      linkUrl: uri,
      label: label,
      excludeSemantics: true,
      onTap: open, // 스크린리더로 누를 수 있게 같이 준다
      child: _body(onTap: open),
    );
  }
}
