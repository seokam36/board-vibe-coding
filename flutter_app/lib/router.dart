import 'package:go_router/go_router.dart';

import 'pages/not_found_page.dart';
import 'pages/placeholder_page.dart';

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

// 시작 주소를 받아 라우터를 새로 만든다. (테스트마다 새 라우터가 필요해서 함수로 둠)
// 순서 주의: /posts/new 같은 고정 주소를 /posts/:id 보다 먼저 적어야 "new"가 id로 잡히지 않는다.
GoRouter createRouter({String initialLocation = '/'}) {
  return GoRouter(
    initialLocation: initialLocation,
    // 로그인 검사는 아직 안 한다(전부 열림).
    routes: [
      _route('/', '글 목록'),
      _route('/signup', '회원가입'),
      _route('/login', '로그인'),
      _route('/posts/new', '글쓰기'),
      _route('/posts/:id', '글 상세'),
      _route('/posts/:id/edit', '글 수정'),
      _route('/me', '내 정보'),
      _route('/admin/reports', '신고 관리'),
      _route('/admin/reports/:id/conversation', '신고된 대화 보기'),
      _route('/visa-reviews', '비자 후기 목록'),
      _route('/visa-reviews/new', '비자 후기 쓰기'),
      _route('/visa-reviews/stats', '비자 후기 통계'),
      _route('/visa-reviews/:id', '비자 후기 상세'),
      _route('/visa-reviews/:id/edit', '비자 후기 수정'),
      _route('/visa-plan', '내 비자 일정'),
      _route('/market', '중고·나눔 목록'),
      _route('/market/new', '중고·나눔 쓰기'),
      _route('/market/:id', '중고·나눔 상세'),
      _route('/market/:id/edit', '중고·나눔 수정'),
      _route('/messages', '쪽지함'),
      _route('/messages/:id', '대화'),
    ],
    // 위 주소에 없으면 404 화면
    errorBuilder: (context, state) => const NotFoundPage(),
  );
}
