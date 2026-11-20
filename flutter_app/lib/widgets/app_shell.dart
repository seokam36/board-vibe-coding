import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../common/app_history.dart';

// 모든 화면의 공통 바탕 (React의 App.tsx 레이아웃).
// 상단바는 여기가 아니라 본문 틀(PageFrame) 안에 있다: 상단바도 본문과 같이 스크롤되게 하려는 구조다.
// (여기서 스크롤을 감싸면 화면 전환 때 옛·새 화면이 겹치며 높이가 달라 옛 화면이 잘린다.)
// 라우터의 ShellRoute가 감싼다 (404 화면도 ShellRoute 안의 마지막 라우트라 같은 틀을 쓴다).
//
// 앱(안드로이드) 모드일 때는 기기의 뒤로 가기를 받는다 (lib/common/app_history.dart의 방문 기록):
//  - 돌아갈 곳이 없으면 canPop: true → 그대로 앱이 닫힌다(홈으로).
//  - 돌아갈 곳이 있으면 canPop: false → 앱이 안 닫히고, 기록의 한 칸 앞 주소로 go 한다.
// 확인 창(다이얼로그)은 이 화면 위에 따로 쌓여 있어서, 창이 떠 있으면 창만 먼저 닫힌다.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final history = context.watch<AppHistory>();
    final scaffold = Scaffold(body: child);
    if (!history.enabled) return scaffold; // 웹·기본: 지금까지와 똑같이
    return PopScope<Object?>(
      canPop: !history.canGoBack,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return; // 그대로 나갔다면 할 일 없음
        final target = history.goBack();
        if (target != null) context.go(target);
      },
      child: scaffold,
    );
  }
}
