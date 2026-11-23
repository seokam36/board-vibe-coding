import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/link.dart';

import '../common/external_link.dart';
import '../theme/app_colors.dart';
import 'nav_link.dart';

// 글자 링크 (React의 <a>): 포인트색 밑줄 글자.
// - AppLink(path: '/login'): 앱 안 이동. 웹에서는 진짜 <a href> 링크(스크린리더가 "링크"로 읽음), 앱에서는 context.go.
// - AppLink.external(url: ...): 바깥 사이트 링크. 웹은 새 탭으로 여는 진짜 링크(Link, noopener는 Link가 붙임), 앱은 externalLinkOpener로 연다.
// 이동할 곳을 onTap(함수)이 아니라 path/url(주소)로 받는 이유: 웹에서 진짜 링크가 되려면 주소가 필요하다 (FeedRow와 같은 방식).
class AppLink extends StatelessWidget {
  // 앱 안 이동용
  const AppLink({super.key, required this.label, required String this.path, this.style, this.tall = false, this.oneLine = false})
      : url = null;

  // 바깥 링크용
  const AppLink.external({super.key, required this.label, required String this.url, this.style, this.tall = false, this.oneLine = false})
      : path = null;

  final String label;
  final String? path; // 앱 안 주소 (예: /login). external이면 null
  final String? url; // 바깥 주소. 앱 안 이동이면 null
  // true면 글자를 한 줄로 두고 길면 끝을 줄임표로 줄인다(긴 제목이 칸 높이를 키우지 않게). 기본은 줄바꿈.
  // 부모가 폭을 정해 줄 때(Expanded/Flexible 안)만 줄임표가 된다
  final bool oneLine;
  final TextStyle? style;
  // true면 눌림 높이를 최소 48로 키운다(글자는 세로 가운데). 단독으로 놓인 링크(위 막대)용.
  // 문장 속 링크는 높이가 커지면 줄 간격이 깨지니 false(기본)로 둔다.
  final bool tall;

  // 링크 모양 (눌림 효과·높이·패딩·글자). 웹과 앱, 안 이동과 바깥 링크가 같이 쓴다.
  // wrap: NavLink가 주는 "Semantics로 한 번 더 감싸는 함수" (앱에서는 그대로 돌려준다).
  Widget _body(VoidCallback onTap, Widget Function(Widget) wrap) {
    final content = ConstrainedBox(
      constraints: BoxConstraints(minHeight: tall ? AppSizes.tapMin : 0),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSizes.space1),
        // Align은 글자 크기에 딱 맞게(heightFactor 1) 두고, 최소 높이(tall)는 위 ConstrainedBox가 정한다.
        // 최소 높이가 더 크면 Align이 그 안에서 글자를 세로 가운데에 놓는다.
        child: Align(
          alignment: Alignment.center,
          widthFactor: 1,
          heightFactor: 1,
          child: Text(
            label,
            maxLines: oneLine ? 1 : null,
            overflow: oneLine ? TextOverflow.ellipsis : null,
            style: const TextStyle(
              fontSize: AppSizes.fsSmall2,
              color: AppColors.accent,
              decoration: TextDecoration.underline,
              decorationColor: AppColors.accent,
            ).merge(style),
          ),
        ),
      ),
    );
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
      child: wrap(content),
    );
  }

  @override
  Widget build(BuildContext context) {
    final externalUrl = url;
    if (externalUrl != null) {
      // 바깥 링크: 웹은 새 탭으로 여는 진짜 링크(Link, 주소가 있어 가운데 클릭·Ctrl+클릭도 됨), 앱은 externalLinkOpener.
      // 웹의 안전장치(noopener)는 Link가 붙인다.
      return NavLink(
        uri: Uri.parse(externalUrl),
        label: label,
        target: LinkTarget.blank,
        onOpen: () => externalLinkOpener.open(externalUrl),
        builder: _body,
      );
    }
    final appPath = path!;
    // 앱 안 이동: 웹은 진짜 <a href>(재로드 없이 라우터로 이동), 앱은 기존처럼 context.go(뒤로 가기 기록도 그대로)
    return NavLink(
      uri: Uri.parse(appPath),
      label: label,
      onOpen: () => context.go(appPath),
      builder: _body,
    );
  }
}
