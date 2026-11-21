import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../common/app_history.dart';
import 'bottom_tabs.dart';

// 모든 화면의 공통 바탕 (React의 App.tsx 레이아웃).
// 상단바는 여기가 아니라 본문 틀(PageFrame) 안에 있다: 상단바도 본문과 같이 스크롤되게 하려는 구조다.
// (여기서 스크롤을 감싸면 화면 전환 때 옛·새 화면이 겹치며 높이가 달라 옛 화면이 잘린다.)
// 라우터의 ShellRoute가 감싼다 (404 화면도 ShellRoute 안의 마지막 라우트라 같은 틀을 쓴다).
//
// 앱(안드로이드) 모드일 때는 기기의 뒤로 가기를 받는다 (lib/common/app_history.dart의 방문 기록):
//  - 돌아갈 곳이 없으면 canPop: true → 그대로 앱이 닫힌다(홈으로).
//  - 돌아갈 곳이 있으면 canPop: false → 앱이 안 닫히고, 기록의 한 칸 앞 주소로 go 한다.
// 확인 창(다이얼로그)은 이 화면 위에 따로 쌓여 있어서, 창이 떠 있으면 창만 먼저 닫힌다.
//
// 아래 탭 막대(BottomTabs, S26-c)는 Scaffold의 bottomNavigationBar로 붙인다 (본문은 그 위 남은 자리에만 그려져 가려지지 않는다).
// 폰 아래 제스처 막대 여백은 app.dart의 builder가 SafeArea로 이미 비워 두므로 여기서 또 처리하지 않는다.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.child, required this.location});

  final Widget child;
  final String location; // 지금 주소의 경로 (라우터가 넘겨 준다). 탭 선택 표시에 쓴다

  @override
  Widget build(BuildContext context) {
    final history = context.watch<AppHistory>();
    // 키보드가 올라와 있으면 탭 막대를 숨긴다. Scaffold는 bottomNavigationBar를 키보드 위로 올려 주지 않아
    // (막대가 키보드 뒤에 반쯤 걸치고 본문만 줄어든다), 숨겨서 본문 공간을 넓히고 그 걸침을 피한다.
    // 조건이 viewInsets라 키보드가 올라오는 모바일 웹에서도 똑같이 숨는다.
    final keyboardOpen = MediaQuery.viewInsetsOf(context).bottom > 0;
    final scaffold = Scaffold(
      body: child,
      bottomNavigationBar: keyboardOpen ? null : BottomTabs(location: location),
    );
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
