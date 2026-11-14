import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import 'router.dart';

// 앱 전체의 뼈대. 주소에 따라 화면을 바꾸는 라우터를 연결한다.
class BoardApp extends StatefulWidget {
  const BoardApp({super.key, this.router});

  // 테스트에서 시작 주소가 다른 라우터를 넣어 보려고 받는다. 없으면 '/'에서 시작.
  final GoRouter? router;

  @override
  State<BoardApp> createState() => _BoardAppState();
}

class _BoardAppState extends State<BoardApp> {
  late final GoRouter _router = widget.router ?? createRouter();

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: '게시판',
      // Material 3 테마 (씨앗 색 하나로 전체 색을 만든다)
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
      ),
      routerConfig: _router,
    );
  }
}
