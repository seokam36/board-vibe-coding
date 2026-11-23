import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import '../widgets/nav_link.dart';

// "후기 / 통계" 탭 (React의 VisaReviewTabs). 현재 주소의 쿼리(필터)를 그대로 들고 가서 탭을 오가도 조건이 유지된다.
// 현재 탭은 포인트색 글자 + 밑줄, 나머지는 muted
class VisaReviewTabs extends StatelessWidget {
  const VisaReviewTabs({super.key, required this.current});

  final VisaTab current;

  @override
  Widget build(BuildContext context) {
    // 탭 주소(href)는 그릴 때 정해진다. 그래서 build에서 현재 주소의 쿼리(물음표 뒤 글자, 없으면 "")를 읽는다.
    // GoRouterState.of는 주소가 바뀌면 이 위젯을 다시 그리게 하므로, 조건을 바꾸면 탭 주소도 새 쿼리로 다시 만들어진다.
    final query = GoRouterState.of(context).uri.query;
    String to(String path) => query.isEmpty ? path : '$path?$query';

    return Container(
      margin: const EdgeInsets.only(bottom: AppSizes.space4),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
      child: Wrap(
        spacing: AppSizes.space4,
        children: [
          _TabLink(label: '후기', selected: current == VisaTab.list, to: to('/visa-reviews')),
          _TabLink(label: '통계', selected: current == VisaTab.stats, to: to('/visa-reviews/stats')),
        ],
      ),
    );
  }
}

enum VisaTab { list, stats }

class _TabLink extends StatelessWidget {
  const _TabLink({required this.label, required this.selected, required this.to});

  final String label;
  final bool selected;
  final String to; // 이동할 앱 안 주소 (쿼리 포함, 예: /visa-reviews/stats?schoolId=1)

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.accent : AppColors.muted;
    // 탭 역할(tab)이 아니라 링크로 둔다. 현재 탭 표시(selected)는 NavLink가 의미에 같이 준다.
    return NavLink(
      uri: Uri.parse(to),
      label: label,
      selected: selected,
      onOpen: () => context.go(to),
      builder: (onTap, wrap) => InkWell(
        onTap: onTap,
        child: wrap(
          Container(
            constraints: const BoxConstraints(minHeight: 40),
            padding: const EdgeInsets.symmetric(horizontal: AppSizes.space1),
            decoration: BoxDecoration(
              border: Border(bottom: BorderSide(color: selected ? AppColors.accent : Colors.transparent, width: 2)),
            ),
            // widthFactor 1: 글자 폭만 차지한다. (Container의 alignment를 쓰면 줄 폭을 다 먹어 탭이 위아래로 쌓였다)
            child: Center(
              widthFactor: 1,
              child: Text(
                label,
                style: TextStyle(
                  fontSize: AppSizes.fsBody,
                  color: color,
                  fontWeight: selected ? FontWeight.w500 : FontWeight.w400,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
