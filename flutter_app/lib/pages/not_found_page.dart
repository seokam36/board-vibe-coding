import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../widgets/app_link.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

// 없는 주소로 들어왔을 때(404) 보여 주는 본문 (상단바는 AppShell이 그려 준다).
class NotFoundPage extends StatelessWidget {
  const NotFoundPage({super.key});

  @override
  Widget build(BuildContext context) {
    return PageFrame(
      // 다른 화면과 같은 판 + 제목(PageTitle, heading 1)
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const PageTitle('페이지를 찾을 수 없습니다'),
            // 누르면 글 목록(홈)으로 이동. 단독 링크라 눌림 높이를 48로 키운다(tall)
            AppLink(label: '홈으로', tall: true, onTap: () => context.go('/')),
          ],
        ),
      ),
    );
  }
}
