import 'package:material_ui/material_ui.dart';

import 'nav_bar.dart';

// 모든 화면의 공통 틀: 맨 위 상단바 + 그 아래 본문 (React의 App.tsx 레이아웃).
// 라우터의 ShellRoute와 404 화면이 같이 쓴다.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const NavBar(),
          Expanded(child: child),
        ],
      ),
    );
  }
}
