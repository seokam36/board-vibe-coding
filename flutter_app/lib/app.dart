import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'api/api_client.dart';
import 'auth/auth_state.dart';
import 'router.dart';

// 앱 전체의 뼈대. 서버 통로(ApiClient)와 로그인 상태(AuthState)를 만들어 앱 전체에 나눠 주고,
// 주소에 따라 화면을 바꾸는 라우터를 연결한다.
class BoardApp extends StatefulWidget {
  const BoardApp({super.key, this.router, this.apiClient, this.authState});

  // 테스트에서 시작 주소가 다른 라우터를 넣어 보려고 받는다. 없으면 '/'에서 시작.
  final GoRouter? router;

  // 테스트에서 가짜 서버를 끼운 통로 / 이미 만들어 둔 로그인 상태를 넣어 보려고 받는다.
  // authState를 넣으면 init()도 부르지 않는다(넣은 쪽이 책임진다).
  final ApiClient? apiClient;
  final AuthState? authState;

  @override
  State<BoardApp> createState() => _BoardAppState();
}

class _BoardAppState extends State<BoardApp> {
  late final GoRouter _router = widget.router ?? createRouter();
  // 밖에서 로그인 상태를 넣어 줬고 통로는 안 넣었다면 통로는 만들지 않는다(null)
  late final ApiClient? _api =
      widget.apiClient ?? (widget.authState == null ? ApiClient() : null);
  late final AuthState _auth =
      widget.authState ??
      AuthState(
        api: _api!,
        // 로그인이 풀린 순간 있던 화면의 경로를 라우터에서 읽어 온다
        currentPath: () => _router.routeInformationProvider.value.uri.path,
      );

  @override
  void initState() {
    super.initState();
    // 우리가 만든 AuthState일 때만 시작 확인(/csrf → /auth/me)을 한다
    if (widget.authState == null) _auth.init();
  }

  @override
  void dispose() {
    // 우리가 만든 것만 정리한다 (밖에서 넣어 준 건 넣은 쪽이 정리)
    if (widget.authState == null) _auth.dispose();
    if (widget.router == null) _router.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        if (_api != null) Provider<ApiClient>.value(value: _api),
        ChangeNotifierProvider<AuthState>.value(value: _auth),
      ],
      child: MaterialApp.router(
        title: '게시판',
        // Material 3 테마 (씨앗 색 하나로 전체 색을 만든다)
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
          // 모든 OS에서 같은 글꼴(번들한 IBM Plex Sans KR, pubspec에서 'Roboto' 이름으로 등록)을 쓰게 못 박는다.
          // (안 적으면 맥·아이폰 브라우저는 테마 글꼴 이름이 달라져 엔진 기본값에 기대게 된다)
          fontFamily: 'Roboto',
        ),
        routerConfig: _router,
        // 시작 확인이 끝날 때까지는 화면 대신 가운데 로딩 표시 (쓰기 요청 전에 CSRF 쿠키가 있어야 해서)
        builder: (context, child) {
          if (context.watch<AuthState>().loading) {
            return const Scaffold(
              body: Center(child: CircularProgressIndicator()),
            );
          }
          return child ?? const SizedBox.shrink();
        },
      ),
    );
  }
}
