import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'api/api_client.dart';
import 'auth/auth_state.dart';
import 'router.dart';
import 'site_name.dart';
import 'theme/app_theme.dart';
import 'widgets/loading.dart';
import 'widgets/page_frame.dart';

// 앱 전체의 뼈대. 서버 통로(ApiClient)와 로그인 상태(AuthState)를 만들어 앱 전체에 나눠 주고,
// 주소에 따라 화면을 바꾸는 라우터를 연결한다.
class BoardApp extends StatefulWidget {
  const BoardApp({super.key, this.initialLocation = '/', this.apiClient, this.authState});

  // 테스트에서 시작 주소를 바꿔 보려고 받는다. 없으면 '/'에서 시작.
  final String initialLocation;

  // 테스트에서 가짜 서버를 끼운 통로 / 이미 만들어 둔 로그인 상태를 넣어 보려고 받는다.
  // authState를 넣으면 init()도 부르지 않는다(넣은 쪽이 책임진다).
  // authState만 넣어도 화면들이 쓸 통로는 그 authState의 통로를 나눠 준다.
  final ApiClient? apiClient;
  final AuthState? authState;

  @override
  State<BoardApp> createState() => _BoardAppState();
}

class _BoardAppState extends State<BoardApp> {
  // 라우터는 문지기가 로그인 상태를 봐야 해서 AuthState를 받는다
  late final GoRouter _router = createRouter(
    authState: _auth,
    initialLocation: widget.initialLocation,
  );
  late final ApiClient _api =
      widget.apiClient ?? widget.authState?.api ?? ApiClient();
  late final AuthState _auth =
      widget.authState ??
      AuthState(
        api: _api,
        // 로그인이 풀린 순간 있던 화면의 경로. 문지기가 비교하는 것과 같은 출처(라우터가 확정한 주소)·같은 정리(normalizePath)를 쓴다
        currentPath: () =>
            normalizePath(_router.routerDelegate.currentConfiguration.uri.path),
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
    _router.dispose(); // 로그인 상태 구독을 먼저 끊고
    if (widget.authState == null) _auth.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        Provider<ApiClient>.value(value: _api),
        ChangeNotifierProvider<AuthState>.value(value: _auth),
      ],
      child: MaterialApp.router(
        title: siteName,
        // 어두운 청자 테마 (색·글자 크기·버튼·입력칸은 lib/theme 참고)
        theme: buildAppTheme(),
        routerConfig: _router,
        // 시작 확인이 끝날 때까지는 화면 대신 "불러오는 중..." 문구만 (쓰기 요청 전에 CSRF 쿠키가 있어야 해서).
        // React처럼 상단바도 아직 안 보인다.
        builder: (context, child) {
          if (context.watch<AuthState>().loading) {
            return const Scaffold(body: PageFrame(child: Loading()));
          }
          return child ?? const SizedBox.shrink();
        },
      ),
    );
  }
}
