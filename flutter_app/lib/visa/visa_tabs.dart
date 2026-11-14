import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// "후기 / 통계" 탭 (React의 VisaReviewTabs). 현재 주소의 쿼리(필터)를 그대로 들고 가서 탭을 오가도 조건이 유지된다.
// 현재 탭은 청자색 글자 + 밑줄, 나머지는 muted
class VisaReviewTabs extends StatelessWidget {
  const VisaReviewTabs({super.key, required this.current});

  final VisaTab current;

  @override
  Widget build(BuildContext context) {
    // 누르는 순간의 주소 쿼리(물음표 뒤 글자, 없으면 "")를 읽는다. build 때 담아 두면 조건을 바꾼 뒤에도 옛 값이 남을 수 있다
    String to(String path) {
      final query = GoRouterState.of(context).uri.query;
      return query.isEmpty ? path : '$path?$query';
    }

    return Container(
      margin: const EdgeInsets.only(bottom: AppSizes.space4),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
      child: Wrap(
        spacing: AppSizes.space4,
        children: [
          _TabLink(label: '후기', selected: current == VisaTab.list, onTap: () => context.go(to('/visa-reviews'))),
          _TabLink(label: '통계', selected: current == VisaTab.stats, onTap: () => context.go(to('/visa-reviews/stats'))),
        ],
      ),
    );
  }
}

enum VisaTab { list, stats }

class _TabLink extends StatelessWidget {
  const _TabLink({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.celadon : AppColors.muted;
    return Semantics(
      link: true,
      selected: selected,
      child: InkWell(
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minHeight: 40),
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space1),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: selected ? AppColors.celadon : Colors.transparent, width: 2)),
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
    );
  }
}
