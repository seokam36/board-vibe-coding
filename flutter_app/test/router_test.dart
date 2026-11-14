// S16-b 라우팅 뼈대 테스트: 주소마다 맞는 빈 화면이 뜨는지, id가 넘어가는지, 404·뒤로 가기가 되는지 본다.
import 'package:board_web/app.dart';
import 'package:board_web/router.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'support/loaded_auth.dart';

const notFoundText = '페이지를 찾을 수 없습니다';

// 시작 주소로 앱을 띄우는 도우미. 테스트마다 새 라우터를 만들고 끝나면 정리한다.
Future<GoRouter> _start(WidgetTester tester, String location) async {
  final router = createRouter(initialLocation: location);
  addTearDown(router.dispose);
  // 서버 확인(loading)이 끝난 상태의 가짜 로그인 상태를 넣어, 라우팅만 본다
  final auth = LoadedAuthState();
  addTearDown(auth.dispose);
  await tester.pumpWidget(BoardApp(router: router, authState: auth));
  await tester.pumpAndSettle();
  return router;
}

void main() {
  // 완료 조건 1: 주소 21개 → 화면 이름 (id 있는 주소는 id까지)
  const pages = <(String, String)>[
    ('/', '글 목록'),
    ('/signup', '회원가입'),
    ('/login', '로그인'),
    ('/posts/new', '글쓰기'),
    ('/posts/7', '글 상세 (id: 7)'),
    ('/posts/7/edit', '글 수정 (id: 7)'),
    ('/me', '내 정보'),
    ('/admin/reports', '신고 관리'),
    ('/admin/reports/7/conversation', '신고된 대화 보기 (id: 7)'),
    ('/visa-reviews', '비자 후기 목록'),
    ('/visa-reviews/new', '비자 후기 쓰기'),
    ('/visa-reviews/stats', '비자 후기 통계'),
    ('/visa-reviews/7', '비자 후기 상세 (id: 7)'),
    ('/visa-reviews/7/edit', '비자 후기 수정 (id: 7)'),
    ('/visa-plan', '내 비자 일정'),
    ('/market', '중고·나눔 목록'),
    ('/market/new', '중고·나눔 쓰기'),
    ('/market/7', '중고·나눔 상세 (id: 7)'),
    ('/market/7/edit', '중고·나눔 수정 (id: 7)'),
    ('/messages', '쪽지함'),
    ('/messages/7', '대화 (id: 7)'),
  ];
  for (final (path, label) in pages) {
    testWidgets('주소 $path 로 시작하면 "$label" 화면이 보이고 404는 안 보인다', (tester) async {
      await _start(tester, path);
      expect(find.text(label), findsOneWidget);
      expect(find.text(notFoundText), findsNothing);
    });
  }

  // 완료 조건 2: id 화면 8개가 주소의 id를 그대로 보여 준다(화면마다 다른 숫자로 섞임 확인)
  const idPages = <(String, String, String)>[
    ('/posts/101', '글 상세', '101'),
    ('/posts/102/edit', '글 수정', '102'),
    ('/admin/reports/103/conversation', '신고된 대화 보기', '103'),
    ('/visa-reviews/104', '비자 후기 상세', '104'),
    ('/visa-reviews/105/edit', '비자 후기 수정', '105'),
    ('/market/106', '중고·나눔 상세', '106'),
    ('/market/107/edit', '중고·나눔 수정', '107'),
    ('/messages/108', '대화', '108'),
  ];
  for (final (path, name, id) in idPages) {
    testWidgets('$name 화면은 주소 $path 의 id $id 를 그대로 보여 준다', (tester) async {
      await _start(tester, path);
      expect(find.text('$name (id: $id)'), findsOneWidget);
      // id 글자는 화면에 딱 하나(다른 id가 섞여 들어오지 않음)
      expect(find.textContaining('(id:'), findsOneWidget);
    });
  }

  // 사용자 결정: 숫자가 아닌 id도 라우터는 거르지 않고 글자 그대로 넘긴다(처리는 각 화면 단계에서)
  testWidgets('숫자가 아닌 id(/posts/abc)도 글자 그대로 넘어간다', (tester) async {
    await _start(tester, '/posts/abc');
    expect(find.text('글 상세 (id: abc)'), findsOneWidget);
    expect(find.text(notFoundText), findsNothing);
  });

  // React Router처럼 대소문자를 가리지 않는다
  for (final (path, label) in const [('/Login', '로그인'), ('/Posts/5', '글 상세 (id: 5)')]) {
    testWidgets('대소문자가 달라도($path) "$label" 화면이 뜬다', (tester) async {
      await _start(tester, path);
      expect(find.text(label), findsOneWidget);
      expect(find.text(notFoundText), findsNothing);
    });
  }

  // 완료 조건 3: 고정 주소(new·stats)가 :id 로 잘못 잡히지 않는다
  const fixedPages = <(String, String)>[
    ('/posts/new', '글쓰기'),
    ('/visa-reviews/new', '비자 후기 쓰기'),
    ('/visa-reviews/stats', '비자 후기 통계'),
    ('/market/new', '중고·나눔 쓰기'),
  ];
  for (final (path, label) in fixedPages) {
    testWidgets('겹치는 주소 $path 는 "$label" 이고 id로 잘못 잡히지 않는다', (tester) async {
      await _start(tester, path);
      expect(find.text(label), findsOneWidget);
      expect(find.textContaining('(id: new)'), findsNothing);
      expect(find.textContaining('(id: stats)'), findsNothing);
    });
  }

  // 완료 조건 4: 없는 주소는 404 + [홈으로] → 글 목록
  for (final path in ['/nope', '/posts', '/posts/1/nope']) {
    testWidgets('없는 주소 $path 는 404 화면이고 [홈으로]를 누르면 글 목록', (tester) async {
      await _start(tester, path);
      expect(find.text(notFoundText), findsOneWidget);
      expect(find.text('홈으로'), findsOneWidget);

      await tester.tap(find.text('홈으로'));
      await tester.pumpAndSettle();
      expect(find.text('글 목록'), findsOneWidget);
      expect(find.text(notFoundText), findsNothing);
    });
  }

  // 완료 조건 5: 뒤로 가기.
  // 웹 링크는 go()로 이동해서 화면 더미가 쌓이지 않는다. 진짜 브라우저에서 뒤로 가기를 누르면
  // 브라우저가 "이전 주소는 / 야"라고 앱에 알려 주는데, 테스트에는 브라우저가 없으니 그 알림을 직접 보낸다.
  // 한계: 이 테스트는 "알림을 받으면 그 화면으로 간다"까지만 본다. 브라우저 기록이 실제로 쌓이는지
  // (뒤로·앞으로 가기)는 손 확인표로만 지킨다.
  testWidgets('뒤로 가기: 브라우저가 이전 주소를 다시 알려 주는 것을 흉내 내면 글 목록으로 돌아온다', (tester) async {
    final router = await _start(tester, '/');
    router.go('/posts/1');
    await tester.pumpAndSettle();
    expect(find.text('글 상세 (id: 1)'), findsOneWidget);

    final message = const JSONMethodCodec().encodeMethodCall(
      const MethodCall('pushRouteInformation', {'location': '/'}),
    );
    await tester.binding.defaultBinaryMessenger
        .handlePlatformMessage('flutter/navigation', message, (_) {});
    await tester.pumpAndSettle();

    expect(find.text('글 목록'), findsOneWidget);
    expect(find.text('글 상세 (id: 1)'), findsNothing);
  });
}
