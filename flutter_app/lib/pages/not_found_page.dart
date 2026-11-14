import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

// 없는 주소로 들어왔을 때(404) 보여 주는 화면.
class NotFoundPage extends StatelessWidget {
  const NotFoundPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('게시판')),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('페이지를 찾을 수 없습니다'),
            const SizedBox(height: 16),
            // 누르면 글 목록(홈)으로 이동
            FilledButton(
              onPressed: () => context.go('/'),
              child: const Text('홈으로'),
            ),
          ],
        ),
      ),
    );
  }
}
