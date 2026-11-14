// S16-b 라우팅 뼈대 테스트: 주소마다 맞는 빈 화면이 뜨는지, id가 넘어가는지, 404·뒤로 가기가 되는지 본다.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

const notFoundText = '페이지를 찾을 수 없습니다';

// 시작 주소로 앱을 띄우는 도우미.
// S16-d2a부터 문지기가 생겨서, 비로그인이면 보호 주소(/posts/new, /me ...)가 /login 으로 바뀐다.
// 이 파일은 "주소 → 화면 연결"만 보는 테스트라 로그인한 상태로 띄워 문지기 영향을 뺀다.
// (비로그인일 때의 문지기 동작은 gatekeeper_test.dart에서 본다)
Future<GoRouter> _start(WidgetTester tester, String location, {FakeAdapter? adapter}) async {
  final app = await pumpApp(tester, location: location, user: userJson(), adapter: adapter);
  // S16-f1: 글 상세는 글이 뜬 뒤 공감 버튼이 likes/me를 묻는다. 그 요청의 타이머가 끝나도록 한 번 더 흘려 보낸다
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
  return app.router;
}

// S16-f1: 글 상세는 진짜 화면이 되어 글을 불러온다. id 글 한 건을 돌려주는 가짜 서버 (제목은 "글 {id}")
FakeAdapter _detailAdapter(String id) =>
    FakeAdapter()
      ..on('GET', '/posts/$id', FakeReply(200, postJson(title: '글 $id')))
      ..on('GET', '/posts/$id/comments', FakeReply(200, [])); // S16-f2: 상세가 댓글도 불러온다

void main() {
  // 완료 조건 1: 주소 21개 → 화면 이름 (id 있는 주소는 id까지)
  const pages = <(String, String)>[
    ('/', '전체 게시판'), // S16-e1: "글 목록" 자리표시 대신 진짜 글 목록 화면의 머리말로 확인한다
    ('/signup', '회원가입'),
    ('/login', '아이디'), // S16-d2a: 로그인은 진짜 화면이 되어 "아이디" 입력칸 라벨로 확인한다
    ('/posts/new', '글쓰기'),
    ('/posts/7/edit', '글 수정'), // S16-e2: 진짜 글 수정 화면이 되어 제목 글자로 확인한다 (id는 아래 별도 테스트)
    ('/me', '학교 인증'), // S16-g1: 진짜 내 정보 화면이 되어 "학교 인증" 제목 글자로 확인한다 (상단바 "내 정보"와 겹치지 않게)
    ('/admin/reports', '신고 관리'),
    ('/admin/reports/7/conversation', '신고된 대화 보기 (id: 7)'),
    // S16-h1: /visa-reviews·/visa-reviews/stats·/visa-reviews/:id 는 진짜 화면이 되어 아래 별도 테스트로 옮겼다
    // S16-h2: /visa-reviews/new·/visa-reviews/:id/edit 는 진짜 화면이 되어 아래 별도 테스트로 옮겼다
    // S16-i1: /visa-plan 은 진짜 화면이 되어 visa_plan_page_test.dart 로 옮겼다
    // S16-j1: /market 은 진짜 화면이 되어 market_list_page_test.dart 로 옮겼다
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
    ('/admin/reports/103/conversation', '신고된 대화 보기', '103'),
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

  // S16-f1: 글 상세도 진짜 화면이 되어 "(id: 7)" 글자 대신 글 제목과 그 id로 글을 불러오는 요청으로 확인한다
  testWidgets('주소 /posts/7 로 시작하면 글 상세 화면(글 제목)이 보이고 404는 안 보인다', (tester) async {
    await _start(tester, '/posts/7', adapter: _detailAdapter('7'));
    expect(find.text('글 7'), findsOneWidget);
    expect(find.text(notFoundText), findsNothing);
  });

  testWidgets('글 상세 화면은 주소 /posts/101 의 id 101 로 글을 불러온다', (tester) async {
    final adapter = _detailAdapter('101');
    await _start(tester, '/posts/101', adapter: adapter);
    expect(adapter.count('GET', '/posts/101'), 1);
    expect(find.text('글 101'), findsOneWidget);
  });

  // S16-e2: 글 수정은 진짜 화면이 되어 "(id: 102)" 글자 대신 그 id로 글을 불러오는 요청으로 확인한다
  testWidgets('글 수정 화면은 주소 /posts/102/edit 의 id 102 로 글을 불러온다', (tester) async {
    final adapter = FakeAdapter();
    await pumpApp(tester, location: '/posts/102/edit', user: userJson(), adapter: adapter);
    expect(adapter.count('GET', '/posts/102'), 1);
  });

  // S16-h2: 후기 수정은 진짜 화면이 되어 "(id: 105)" 글자 대신 그 id로 후기를 불러오는 요청으로 확인한다
  testWidgets('후기 수정 화면은 주소 /visa-reviews/105/edit 의 id 105 로 후기를 불러온다', (tester) async {
    final adapter = FakeAdapter();
    await pumpApp(tester, location: '/visa-reviews/105/edit', user: userJson(), adapter: adapter);
    expect(adapter.count('GET', '/visa-reviews/105'), 1);
    expect(find.text('비자 후기 수정'), findsOneWidget);
  });

  // 사용자 결정: 숫자가 아닌 id도 라우터는 거르지 않고 글자 그대로 넘긴다(처리는 각 화면 단계에서)
  testWidgets('숫자가 아닌 id(/posts/abc)도 글자 그대로 넘어간다', (tester) async {
    final adapter = FakeAdapter()..on('GET', '/posts/abc', FakeReply(400)); // 서버가 400을 돌려주는 상황
    await _start(tester, '/posts/abc', adapter: adapter);
    expect(adapter.count('GET', '/posts/abc'), 1); // S16-f1: 글자 그대로 넘어가 그 id로 요청한다
    expect(find.text('글을 찾을 수 없습니다'), findsOneWidget);
    expect(find.text(notFoundText), findsNothing);
  });

  // React Router처럼 대소문자를 가리지 않는다
  for (final (path, label) in const [('/Login', '아이디')]) {
    testWidgets('대소문자가 달라도($path) "$label" 화면이 뜬다', (tester) async {
      await _start(tester, path);
      expect(find.text(label), findsOneWidget);
      expect(find.text(notFoundText), findsNothing);
    });
  }

  testWidgets('대소문자가 달라도(/Posts/5) 글 상세 화면이 뜬다', (tester) async {
    await _start(tester, '/Posts/5', adapter: _detailAdapter('5'));
    expect(find.text('글 5'), findsOneWidget);
    expect(find.text(notFoundText), findsNothing);
  });

  // S16-h1: 비자 후기 목록·통계·상세는 진짜 화면. "(id: …)" 글자 대신 화면이 보내는 요청으로 확인한다
  testWidgets('주소 /visa-reviews 는 후기 목록 화면 (목록·선택지 요청이 간다)', (tester) async {
    final adapter = FakeAdapter()
      ..on('GET', '/schools', FakeReply(200, []))
      ..on('GET', '/visa-review-options', FakeReply(200, {'visaTypes': [], 'applicationTypes': [], 'results': []}))
      ..on('GET', '/visa-reviews', FakeReply(200, []));
    await _start(tester, '/visa-reviews', adapter: adapter);
    expect(find.text('아직 후기가 없습니다'), findsOneWidget);
    expect(find.text(notFoundText), findsNothing);
  });

  // 완료 조건 3: 고정 주소(new·stats)가 :id 로 잘못 잡히지 않는다
  testWidgets('겹치는 주소 /visa-reviews/stats 는 통계 화면이고 상세(:id)로 잘못 잡히지 않는다', (tester) async {
    final adapter = FakeAdapter()
      ..on('GET', '/schools', FakeReply(200, []))
      ..on('GET', '/visa-review-options', FakeReply(200, {'visaTypes': [], 'applicationTypes': [], 'results': []}))
      ..on('GET', '/visa-reviews/stats', FakeReply(200, []));
    await _start(tester, '/visa-reviews/stats', adapter: adapter);
    expect(find.text('아직 통계가 없습니다'), findsOneWidget); // 상세였다면 선택지 요청도 없고 이 문구도 없다
    expect(adapter.count('GET', '/visa-review-options'), 1);
  });

  for (final path in ['/visa-reviews/104', '/Visa-Reviews/104']) {
    testWidgets('후기 상세는 주소 $path 의 id 104 로 후기를 불러온다 (대소문자는 가리지 않는다)', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/visa-reviews/104', FakeReply(404));
      await _start(tester, path, adapter: adapter);
      expect(adapter.count('GET', '/visa-reviews/104'), 1);
      expect(find.text('후기를 찾을 수 없습니다'), findsOneWidget);
      expect(find.text(notFoundText), findsNothing);
    });
  }
  const fixedPages = <(String, String)>[
    ('/posts/new', '글쓰기'),
    ('/visa-reviews/new', '비자 후기 쓰기'),
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
      expect(find.text('전체 게시판'), findsOneWidget); // S16-e1: 진짜 글 목록 화면의 머리말
      expect(find.text(notFoundText), findsNothing);
    });
  }

  // 완료 조건 5: 뒤로 가기.
  // 웹 링크는 go()로 이동해서 화면 더미가 쌓이지 않는다. 진짜 브라우저에서 뒤로 가기를 누르면
  // 브라우저가 "이전 주소는 / 야"라고 앱에 알려 주는데, 테스트에는 브라우저가 없으니 그 알림을 직접 보낸다.
  // 한계: 이 테스트는 "알림을 받으면 그 화면으로 간다"까지만 본다. 브라우저 기록이 실제로 쌓이는지
  // (뒤로·앞으로 가기)는 손 확인표로만 지킨다.
  testWidgets('뒤로 가기: 브라우저가 이전 주소를 다시 알려 주는 것을 흉내 내면 글 목록으로 돌아온다', (tester) async {
    final router = await _start(tester, '/', adapter: _detailAdapter('1'));
    router.go('/posts/1');
    await tester.pumpAndSettle();
    expect(find.text('글 1'), findsOneWidget);

    final message = const JSONMethodCodec().encodeMethodCall(
      const MethodCall('pushRouteInformation', {'location': '/'}),
    );
    await tester.binding.defaultBinaryMessenger
        .handlePlatformMessage('flutter/navigation', message, (_) {});
    await tester.pumpAndSettle();

    expect(find.text('전체 게시판'), findsOneWidget); // S16-e1: 진짜 글 목록 화면의 머리말
    expect(find.text('글 1'), findsNothing);
  });
}
