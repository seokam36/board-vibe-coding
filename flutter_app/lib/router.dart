import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart' show ValueKey;

import 'auth/auth_state.dart';
import 'pages/login_page.dart';
import 'pages/market_detail_page.dart';
import 'pages/market_list_page.dart';
import 'pages/my_page.dart';
import 'pages/not_found_page.dart';
import 'pages/placeholder_page.dart';
import 'pages/post_create_page.dart';
import 'pages/post_detail_page.dart';
import 'pages/post_edit_page.dart';
import 'pages/post_list_page.dart';
import 'pages/signup_page.dart';
import 'pages/visa_review_create_page.dart';
import 'pages/visa_review_detail_page.dart';
import 'pages/visa_review_edit_page.dart';
import 'pages/visa_review_list_page.dart';
import 'pages/visa_plan_page.dart';
import 'pages/visa_review_stats_page.dart';
import 'widgets/app_shell.dart';

// 주소 하나를 "화면 이름"과 묶는 짧은 도우미. id가 있는 주소(:id)면 id를 글자 그대로 넘긴다.
GoRoute _route(String path, String name) {
  return GoRoute(
    path: path,
    // React Router처럼 대소문자를 가리지 않는다(/Login도 로그인). go_router 기본값은 가림.
    caseSensitive: false,
    builder: (context, state) =>
        PlaceholderPage(name: name, id: state.pathParameters['id']),
  );
}

// 경로 비교용 정리: 소문자로 바꾸고 끝의 / 를 뗀다.
// 라우트가 대소문자를 가리지 않으므로(/Me 도 /me), 문지기 검사와 "로그인이 풀린 경로" 기록·비교가
// 모두 이 함수를 거쳐 같은 글자로 맞춰져야 /ME 로 우회하거나 /Me 에서 풀렸을 때 어긋나지 않는다.
String normalizePath(String path) {
  final lower = path.toLowerCase();
  return lower.length > 1 && lower.endsWith('/')
      ? lower.substring(0, lower.length - 1)
      : lower;
}

// 로그인한 사람만 볼 수 있는 주소 10개 (React의 RequireLogin으로 감싼 화면들). 정리된(소문자) 경로로 검사한다.
final _protectedPaths = <RegExp>[
  RegExp(r'^/posts/new$'),
  RegExp(r'^/posts/[^/]+/edit$'),
  RegExp(r'^/me$'),
  RegExp(r'^/visa-reviews/new$'),
  RegExp(r'^/visa-reviews/[^/]+/edit$'),
  RegExp(r'^/visa-plan$'),
  RegExp(r'^/market/new$'),
  RegExp(r'^/market/[^/]+/edit$'),
  RegExp(r'^/messages$'),
  RegExp(r'^/messages/[^/]+$'),
];

bool _isProtected(String normalizedPath) =>
    _protectedPaths.any((p) => p.hasMatch(normalizedPath));

// 규칙: 화면 이동은 context.go만 쓴다(push로 쌓으면 pop이 redirect를 안 거쳐 문지기가 늦게 반응).
// 시작 주소를 받아 라우터를 새로 만든다. (테스트마다 새 라우터가 필요해서 함수로 둠)
// 순서 주의: /posts/new 같은 고정 주소를 /posts/:id 보다 먼저 적어야 "new"가 id로 잡히지 않는다.
GoRouter createRouter({required AuthState authState, String initialLocation = '/'}) {
  return GoRouter(
    initialLocation: initialLocation,
    // 로그인 상태가 바뀌면(로그아웃·로그인 풀림 등) 문지기를 다시 돌린다
    refreshListenable: authState,
    // 문지기 (React의 RequireLogin). 주소가 바뀔 때마다, 그리고 로그인 상태가 바뀔 때마다 불린다.
    // 반환값이 주소면 그리로 보내고(기록은 교체라 뒤로 가기로 못 돌아옴), null이면 통과.
    redirect: (context, state) {
      final path = normalizePath(state.uri.path);
      // 로그인이 풀렸던 화면을 떠났다면 "풀림" 표시를 지운다 (안 지우면 나중에 그 주소로 와도 문이 열려 있다)
      authState.clearExpiredIfPathChanged(path);
      // 시작 확인(/auth/me)이 끝나기 전엔 로그인 여부를 모르니 판단을 미룬다 (끝나면 알림이 와서 다시 돈다)
      if (authState.loading) return null;
      if (!_isProtected(path)) return null;
      // 로그인해 있다가 재발급 실패로 풀린 "그 화면"은 입력 내용을 지키려고 그대로 둔다
      // (화면 안에서 401을 받으면 "다시 로그인해 주세요" 안내가 뜬다). 다른 경로면 expiredPath와 달라 /login 으로 간다.
      if (authState.user == null && authState.expiredPath != path) return '/login';
      return null;
    },
    // 상단바가 모든 화면 위에 보이도록 공통 틀(AppShell)로 감싼다
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(
            path: '/',
            caseSensitive: false,
            // 필터의 진짜 값은 주소 쿼리(?schoolId·?category). 탈퇴 화면이 extra로 넘긴 안내 문구도 전달한다
            builder: (context, state) => PostListPage(
              schoolId: state.uri.queryParameters['schoolId'] ?? '',
              category: state.uri.queryParameters['category'] ?? '',
              notice: state.extra is String ? state.extra as String : null,
            ),
          ),
          GoRoute(
            path: '/signup',
            caseSensitive: false,
            builder: (context, state) => const SignupPage(),
          ),
          GoRoute(
            path: '/login',
            caseSensitive: false,
            // 회원가입 화면이 extra로 넘겨준 안내 문구(문자열)가 있으면 보여 준다
            builder: (context, state) =>
                LoginPage(notice: state.extra is String ? state.extra as String : null),
          ),
          GoRoute(
            path: '/posts/new',
            caseSensitive: false,
            builder: (context, state) => const PostCreatePage(),
          ),
          GoRoute(
            path: '/posts/:id',
            caseSensitive: false,
            // id가 바뀌면(다른 글로 이동) 상태를 새로 만들도록 key를 id로 준다
            builder: (context, state) {
              final id = state.pathParameters['id']!;
              return PostDetailPage(key: ValueKey(id), id: id);
            },
          ),
          GoRoute(
            path: '/posts/:id/edit',
            caseSensitive: false,
            // id가 바뀌면(다른 글의 수정 주소로 이동) 입력 상태를 새로 만들도록 key를 id로 준다
            builder: (context, state) {
              final id = state.pathParameters['id']!;
              return PostEditPage(key: ValueKey(id), id: id);
            },
          ),
          GoRoute(
            path: '/me',
            caseSensitive: false,
            builder: (context, state) => const MyPage(), // S16-g1: 진짜 내 정보 화면
          ),
          _route('/admin/reports', '신고 관리'),
          _route('/admin/reports/:id/conversation', '신고된 대화 보기'),
          // S16-h1: 비자 후기 목록·통계·상세는 진짜 화면. 필터의 진짜 값은 주소 쿼리
          GoRoute(
            path: '/visa-reviews',
            caseSensitive: false,
            builder: (context, state) => VisaReviewListPage(
              schoolId: state.uri.queryParameters['schoolId'] ?? '',
              visaType: state.uri.queryParameters['visaType'] ?? '',
              applicationType: state.uri.queryParameters['applicationType'] ?? '',
            ),
          ),
          GoRoute(
            path: '/visa-reviews/new',
            caseSensitive: false,
            builder: (context, state) => const VisaReviewCreatePage(), // S16-h2: 진짜 쓰기 화면
          ),
          // 고정 주소 stats는 :id 보다 먼저 적어야 "stats"가 id로 잡히지 않는다
          GoRoute(
            path: '/visa-reviews/stats',
            caseSensitive: false,
            builder: (context, state) => VisaReviewStatsPage(
              schoolId: state.uri.queryParameters['schoolId'] ?? '',
              visaType: state.uri.queryParameters['visaType'] ?? '',
              applicationType: state.uri.queryParameters['applicationType'] ?? '',
            ),
          ),
          GoRoute(
            path: '/visa-reviews/:id',
            caseSensitive: false,
            // id가 바뀌면 상태를 새로 만들도록 key를 id로 준다
            builder: (context, state) {
              final id = state.pathParameters['id']!;
              return VisaReviewDetailPage(key: ValueKey(id), id: id);
            },
          ),
          GoRoute(
            path: '/visa-reviews/:id/edit',
            caseSensitive: false,
            // id가 바뀌면 입력 상태를 새로 만들도록 key를 id로 준다
            builder: (context, state) {
              final id = state.pathParameters['id']!;
              return VisaReviewEditPage(key: ValueKey(id), id: id);
            },
          ),
          GoRoute(
            path: '/visa-plan',
            caseSensitive: false,
            builder: (context, state) => const VisaPlanPage(), // S16-i1: 진짜 내 비자 일정 화면
          ),
          // S16-j1: 중고·나눔 목록은 진짜 화면. 필터·검색어의 진짜 값은 주소 쿼리
          GoRoute(
            path: '/market',
            caseSensitive: false,
            builder: (context, state) => MarketListPage(
              schoolId: state.uri.queryParameters['schoolId'] ?? '',
              tradeType: state.uri.queryParameters['tradeType'] ?? '',
              status: state.uri.queryParameters['status'] ?? '',
              q: state.uri.queryParameters['q'] ?? '',
            ),
          ),
          _route('/market/new', '중고·나눔 쓰기'),
          // S16-j2: 중고·나눔 상세는 진짜 화면. id가 바뀌면 상태를 새로 만들도록 key를 id로 준다.
          // extra(int)는 글쓰기에서 넘어온 "못 올린 사진 수" (그 밖의 모양은 없는 것으로 본다)
          GoRoute(
            path: '/market/:id',
            caseSensitive: false,
            builder: (context, state) {
              final id = state.pathParameters['id']!;
              final extra = state.extra;
              return MarketDetailPage(key: ValueKey(id), id: id, photoFailed: extra is int ? extra : null);
            },
          ),
          _route('/market/:id/edit', '중고·나눔 수정'),
          _route('/messages', '쪽지함'),
          _route('/messages/:id', '대화'),
        ],
      ),
    ],
    // 위 주소에 없으면 404 화면 (상단바는 똑같이 보인다)
    errorBuilder: (context, state) => const AppShell(child: NotFoundPage()),
  );
}
