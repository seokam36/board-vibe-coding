import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/link.dart';

// 링크 하나의 웹/앱 분기를 한곳에 모은 공용 틀 (FeedRow·AppLink·위 막대 사이트 이름·비자 탭이 같이 쓴다).
// - 웹: url_launcher의 Link로 감싸 진짜 <a href>를 만든다. 스크린리더가 "링크"로 읽는다.
//   Link가 link 의미를 주므로 안쪽에서 link: true를 또 주지 않는다. followLink를 builder의 onTap에 꼭 연결해야 한다.
// - 앱: 이동은 호출하는 쪽이 정한 onOpen(기존 동작 그대로), 의미만 링크로 맞춘다(linkUrl).
//   excludeSemantics가 InkWell의 누르기를 지우므로 Semantics에도 onTap을 같이 준다.
// 모양은 builder가 그린다. wrap은 "글자 모양을 Semantics로 한 번 더 감싸는 함수"다(앱에서는 그대로 돌려준다).
class NavLink extends StatelessWidget {
  const NavLink({
    super.key,
    required this.uri,
    required this.label,
    required this.onOpen,
    required this.builder,
    this.target = LinkTarget.self,
    this.selected,
  });

  final Uri uri; // 링크 주소 (앱 안 주소 "/login" 또는 바깥 주소 "https://...")
  final String label; // 스크린리더가 읽을 말
  final VoidCallback onOpen; // 앱에서 누를 때 할 일 (웹은 Link가 이동을 맡고, 이 값은 예비로만 남는다)
  final LinkTarget target; // 웹에서 같은 탭(self)인지 새 탭(blank)인지
  final bool? selected; // 현재 위치 표시(비자 탭). null이면 표시하지 않는다
  final Widget Function(VoidCallback onTap, Widget Function(Widget inner) wrap) builder;

  @override
  Widget build(BuildContext context) {
    if (kIsWeb) {
      return Link(
        uri: uri,
        target: target,
        builder: (context, followLink) => builder(
          // uri가 항상 있어 followLink는 null이 아니다. ??는 타입을 맞추기 위한 예비다
          followLink ?? onOpen,
          (inner) => Semantics(label: label, selected: selected, excludeSemantics: true, child: inner),
        ),
      );
    }
    return Semantics(
      link: true,
      linkUrl: uri,
      label: label,
      selected: selected,
      excludeSemantics: true, // 안쪽 글자는 위 label로 대신 읽는다 (중복 방지)
      onTap: onOpen, // 스크린리더로 누를 수 있게 같이 준다
      child: builder(onOpen, (inner) => inner),
    );
  }
}
