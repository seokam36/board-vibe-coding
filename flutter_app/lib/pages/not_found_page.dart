import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import '../widgets/app_link.dart';
import '../widgets/page_frame.dart';

// 없는 주소로 들어왔을 때(404) 보여 주는 본문 (상단바는 AppShell이 그려 준다).
class NotFoundPage extends StatelessWidget {
  const NotFoundPage({super.key});

  @override
  Widget build(BuildContext context) {
    return PageFrame(
      child: Container(
        padding: const EdgeInsets.all(AppSizes.space4),
        decoration: BoxDecoration(
          color: AppColors.paper,
          border: Border.all(color: AppColors.line),
          borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: Text('페이지를 찾을 수 없습니다', style: Theme.of(context).textTheme.titleLarge),
            ),
            // 누르면 글 목록(홈)으로 이동
            AppLink(label: '홈으로', onTap: () => context.go('/')),
          ],
        ),
      ),
    );
  }
}
