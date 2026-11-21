import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/services.dart' show SystemUiOverlayStyle;
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'api/api_client.dart';
import 'auth/auth_state.dart';
import 'common/app_history.dart';
import 'messages/unread_store.dart';
import 'push/app_push.dart';
import 'push/push_registrar.dart';
import 'router.dart';
import 'site_name.dart';
import 'theme/app_colors.dart';
import 'theme/app_theme.dart';
import 'visa/visa_banner_state.dart';
import 'visa/visa_plan_store.dart';
import 'widgets/loading.dart';
import 'widgets/page_frame.dart';

// 앱 전체의 뼈대. 서버 통로(ApiClient)와 로그인 상태(AuthState)를 만들어 앱 전체에 나눠 주고,
// 주소에 따라 화면을 바꾸는 라우터를 연결한다.
class BoardApp extends StatefulWidget {
  const BoardApp({
    super.key,
    this.initialLocation = '/',
    this.apiClient,
    this.authState,
    this.now,
    this.enableAppBack = false,
    this.push,
  });

  // 앱(안드로이드) 전용 스위치: 켜면 방문 기록을 적고 기기의 뒤로 가기를 받아 한 칸 앞 화면으로 보낸다.
  // 웹은 브라우저가 뒤로 가기를 맡으니 꺼 둔다(기본값).
  final bool enableAppBack;

  // 앱(안드로이드) 전용: 푸시 알림 도구(S24-d). 있으면 로그인 뒤 기기 토큰을 서버에 등록한다.
  // 웹·기존 테스트는 null(푸시 없음)이라 /users/me/device-tokens 요청이 한 번도 나가지 않는다.
  final AppPush? push;

  // 테스트에서 "지금 시각"을 고정하려고 받는다(비자 일정의 서울 오늘 계산용). 없으면 진짜 지금.
  final DateTime Function()? now;

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

  // 내 비자 일정 보관소: 로그인 상태(AuthState)를 구독해, 학교 인증 회원일 때만 일정을 한 번 불러온다
  late final VisaPlanStore _visaPlan;
  final VisaBannerState _visaBanner = VisaBannerState();
  late final UnreadStore _unread;
  PushRegistrar? _pushRegistrar; // 푸시 도구가 끼워진 앱에서만 있다 (기기 토큰 등록·삭제 담당)

  @override
  void initState() {
    super.initState();
    // 우리가 만든 AuthState일 때만 시작 확인(/csrf → /auth/me)을 한다
    if (widget.authState == null) _auth.init();
    // 첫 화면을 그리기 전에 만들어 둔다 (이미 로그인한 AuthState가 들어오면 여기서 바로 일정 요청을 시작)
    _visaPlan = VisaPlanStore(auth: _auth, now: widget.now);
    // 안 읽은 쪽지 수 보관소: 로그인 회원이 바뀔 때, 그리고 화면 경로가 바뀔 때마다 숫자를 다시 받는다
    _unread = UnreadStore(auth: _auth);
    _history = AppHistory(enabled: widget.enableAppBack);
    _loggedIn = _auth.user != null;
    _router.routerDelegate.addListener(_onRouteChanged);
    _onRouteChanged(); // 지금 경로를 기억해 둔다 (첫 경로는 새로 받는 계기가 아니다)
    _auth.addListener(_onAuthChanged);
    // 푸시 도구가 끼워진 앱에서만: 기기 토큰 등록·삭제 담당을 시작한다 (준비는 뒤에서 하므로 첫 화면을 막지 않는다)
    final push = widget.push;
    if (push != null) {
      _pushRegistrar = PushRegistrar(api: _api, auth: _auth, push: push)..start();
    }
  }

  // 앱 뒤로 가기용 방문 기록 (앱 모드가 꺼져 있으면 아무것도 안 한다)
  late final AppHistory _history;
  bool _loggedIn = false; // 마지막으로 본 로그인 여부 (바뀐 순간을 알아채려고)

  // 지금 라우터가 가리키는 전체 주소 (쿼리 포함)
  String _currentLocation() => _router.routerDelegate.currentConfiguration.uri.toString();

  // 로그인 여부가 바뀌면 방문 기록을 비운다 (로그아웃 뒤 뒤로 가기로 보호 화면에 못 가게, 로그인 직후 뒤로 가기는 바로 앱 밖).
  // 문지기가 /login 으로 바꾸는 이동이 같은 때 일어나므로, 그게 끝난 뒤(다음 턴)에 지금 주소 하나만 남긴다.
  void _onAuthChanged() {
    if (!widget.enableAppBack) return; // 앱 모드가 아니면 할 일 없음 (웹·기존 테스트엔 타이머도 안 만든다)
    final now = _auth.user != null;
    if (now == _loggedIn) return;
    _loggedIn = now;
    Future<void>.delayed(Duration.zero, () {
      if (mounted) _history.reset(_currentLocation());
    });
  }

  // 라우터가 주소를 확정할 때마다 불린다. 경로(쿼리 뺀 주소)를 보관소에 알려 주면 이전과 다를 때만 다시 받는다.
  // (GoRouterState.of를 build에서 쓰면 무한 반복 문제가 있어, 위젯이 아니라 라우터에 리스너를 단다)
  void _onRouteChanged() {
    final path = _router.routerDelegate.currentConfiguration.uri.path;
    if (path.isEmpty) return; // 아직 첫 주소가 안 정해진 상태
    _unread.onPathChanged(path);
    final here = _currentLocation();
    _history.record(here); // 앱 뒤로 가기용 방문 기록 (앱 모드일 때만 적힌다)
    // 비로그인인데 기록에 "지금 화면이 아닌 보호 주소"가 남아 있으면 뺀다.
    // (세션이 풀린 보호 화면을 떠나는 순간 정리 → 뒤로 가기로 거기 돌아가려다 문지기에 튕기며 헛도는 일을 막는다)
    if (_auth.user == null) {
      _history.removeWhere(
        (location) => location != here && isProtectedPath(normalizePath(Uri.parse(location).path)),
      );
    }
  }

  @override
  void dispose() {
    // 우리가 만든 것만 정리한다 (밖에서 넣어 준 건 넣은 쪽이 정리)
    _router.routerDelegate.removeListener(_onRouteChanged);
    _auth.removeListener(_onAuthChanged);
    _pushRegistrar?.dispose(); // AuthState보다 먼저 (로그인 상태 구독을 끊는다)
    _history.dispose();
    _unread.dispose();
    _router.dispose(); // 로그인 상태 구독을 먼저 끊고
    _visaBanner.dispose();
    _visaPlan.dispose(); // 보관소도 로그인 상태 구독을 끊는다 (AuthState보다 먼저)
    if (widget.authState == null) _auth.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        Provider<ApiClient>.value(value: _api),
        // 앱에서만 값이 있다(웹은 null). 비자 일정 화면의 "휴대폰 알림" 칸이 쓴다
        Provider<PushRegistrar?>.value(value: _pushRegistrar),
        ChangeNotifierProvider<AuthState>.value(value: _auth),
        ChangeNotifierProvider<VisaPlanStore>.value(value: _visaPlan),
        ChangeNotifierProvider<UnreadStore>.value(value: _unread), // 아래 탭 쪽지 배지
        ChangeNotifierProvider<AppHistory>.value(value: _history), // 앱 뒤로 가기 (AppShell이 쓴다)
        // 이번 화면에서 닫은 비자 띠 (화면을 옮겨도 유지)
        ChangeNotifierProvider<VisaBannerState>.value(value: _visaBanner),
      ],
      child: MaterialApp.router(
        title: siteName,
        // 밝은 테마 (색·글자 크기·버튼·입력칸은 lib/theme 참고)
        theme: buildAppTheme(),
        routerConfig: _router,
        // 한국어 전용 앱: 달력(showDatePicker)의 요일·월·툴팁 같은 기본 글자를 한국어로
        locale: const Locale('ko'),
        supportedLocales: const [Locale('ko')],
        // material_ui가 Material·Widgets·Cupertino 번역을 한 묶음(delegates)으로 준다 (셋이 짝이 맞아야 경고가 안 난다)
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        // 시작 확인이 끝날 때까지는 화면 대신 "불러오는 중..." 문구만 (쓰기 요청 전에 CSRF 쿠키가 있어야 해서).
        // React처럼 상단바도 아직 안 보인다.
        builder: (context, child) {
          // 폰(앱)에서는 상태 표시줄·아래 제스처 막대 밑으로 화면이 들어가지 않게 안전 영역 안에 그린다.
          // 위 띠(상태 표시줄 자리)는 본문 바탕(canvas), 아래 띠(제스처 막대 자리)는 흰 아래 탭 막대와 이어지게 paper로 칠한다.
          Widget safe(Widget w) {
            if (kIsWeb) return w;
            final bottomInset = MediaQuery.paddingOf(context).bottom;
            return Column(
              children: [
                Expanded(
                  child: ColoredBox(
                    color: Theme.of(context).scaffoldBackgroundColor,
                    // 아래 여백은 아래 띠가 맡으니 위 안전 영역에서는 뺀다
                    child: SafeArea(
                      bottom: false,
                      // 안쪽 context로 지워야 SafeArea가 이미 지운 위 여백이 되살아나지 않는다
                      child: Builder(
                        builder: (inner) => MediaQuery.removePadding(context: inner, removeBottom: true, child: w),
                      ),
                    ),
                  ),
                ),
                ColoredBox(color: AppColors.paper, child: SizedBox(height: bottomInset, width: double.infinity)),
              ],
            );
          }
          // 밝은 화면이라 상태 표시줄은 canvas, 내비게이션 바는 paper 바탕 + 어두운 아이콘으로 정한다
          // (SystemUiOverlayStyle.dark 그대로는 내비게이션 바가 검정+흰 아이콘이라 직접 지정)
          // 상태 표시줄은 canvas: 위 막대가 본문과 같이 스크롤되어 올라가면 canvas 본문 바탕이 비치기 때문
          Widget darkSystemIcons(Widget w) => AnnotatedRegion<SystemUiOverlayStyle>(
            value: SystemUiOverlayStyle.dark.copyWith(
              statusBarColor: AppColors.canvas,
              systemNavigationBarColor: AppColors.paper, // 흰 아래 탭 막대와 이어지게
              systemNavigationBarIconBrightness: Brightness.dark,
            ),
            child: w,
          );
          if (context.watch<AuthState>().loading) {
            return darkSystemIcons(safe(const Scaffold(body: PageFrame(showNavBar: false, child: Loading()))));
          }
          return darkSystemIcons(safe(child ?? const SizedBox.shrink()));
        },
      ),
    );
  }
}
