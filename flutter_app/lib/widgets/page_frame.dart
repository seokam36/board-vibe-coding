import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'nav_bar.dart';

// 본문 틀 (React의 .page): 맨 위 상단바 + 가운데 정렬, 최대 720 폭, 좌우 16·위아래 24 여백.
// 상단바와 본문이 한 스크롤 안에 있어 같이 올라간다(React처럼). 내용이 짧아도 화면 높이만큼은 차지한다.
// showNavBar: false는 상단바를 쓸 수 없는 앱 시작 로딩 화면용.
class PageFrame extends StatelessWidget {
  const PageFrame({super.key, required this.child, this.showNavBar = true});

  final Widget child;
  final bool showNavBar;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (showNavBar) const NavBar(),
              Align(
                alignment: Alignment.topCenter,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: AppSizes.pageMaxWidth),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: AppSizes.space4,
                      vertical: AppSizes.space5,
                    ),
                    child: child,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
