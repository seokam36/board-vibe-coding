import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 본문 틀 (React의 .page): 가운데 정렬, 최대 720 폭, 좌우 16·위아래 24 여백, 넘치면 스크롤
class PageFrame extends StatelessWidget {
  const PageFrame({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      child: Align(
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
    );
  }
}
