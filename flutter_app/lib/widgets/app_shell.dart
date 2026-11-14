import 'package:material_ui/material_ui.dart';

// 모든 화면의 공통 바탕 (React의 App.tsx 레이아웃).
// 상단바는 여기가 아니라 본문 틀(PageFrame) 안에 있다: 상단바도 본문과 같이 스크롤되게 하려는 구조다.
// (여기서 스크롤을 감싸면 화면 전환 때 옛·새 화면이 겹치며 높이가 달라 옛 화면이 잘린다.)
// 라우터의 ShellRoute와 404 화면이 같이 쓴다.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Scaffold(body: child);
  }
}
