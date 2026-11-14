// S16-e1 글 목록 화면 테스트: React PostListPage와 같은 동작(주소 쿼리가 진짜 값, 머리말, 드롭다운, 행, 문구 4종)을 본다.
import 'dart:async';

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _seoul = {'id': 1, 'name': '서울대'};
const _busan = {'id': 2, 'name': '부산대'};
const _schools = [_seoul, _busan];
const _categories = [
  {'code': 'CHAT', 'name': '잡담'},
  {'code': 'QUESTION', 'name': '질문'},
  {'code': 'CAMPUS_LIFE', 'name': '캠퍼스 생활'},
];

// 글 JSON 한 건
Map<String, dynamic> _post(
  int id,
  String title, {
  Map<String, dynamic>? author = const {'id': 5, 'nickname': '철수'},
  Map<String, dynamic>? school = _seoul,
  Map<String, dynamic> category = const {'code': 'CHAT', 'name': '잡담'},
  String createdAt = '2020-11-08T06:19:00Z',
  int commentCount = 0,
  int likeCount = 0,
}) => {
  'id': id,
  'title': title,
  'content': '내용',
  'author': author,
  'school': school,
  'category': category,
  'createdAt': createdAt,
  'commentCount': commentCount,
  'likeCount': likeCount,
};

// 드롭다운 목록은 항상 성공, 글 목록 응답은 호출하는 쪽이 정한다
FakeAdapter _adapter({List<FakeReply> posts = const []}) {
  final a = FakeAdapter()
    ..on('GET', '/schools', FakeReply(200, _schools))
    ..on('GET', '/categories', FakeReply(200, _categories));
  for (final p in posts) {
    a.on('GET', '/posts', p);
  }
  return a;
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 머리말(30px 글자)의 글자
String _title(WidgetTester tester) => tester
    .widget<Text>(find.byWidgetPredicate((w) => w is Text && w.style?.fontSize == AppSizes.fsBoard))
    .data!;

final _schoolSelect = find.byKey(const ValueKey('filter-school'));
final _categorySelect = find.byKey(const ValueKey('filter-category'));

// 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pick(WidgetTester tester, Finder select, String text) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

void main() {
  group('머리말', () {
    testWidgets('학교를 안 골랐으면 "전체 게시판"', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(_title(tester), '전체 게시판');
    });

    testWidgets('학교를 골랐으면 학교 목록의 이름', (tester) async {
      await pumpApp(tester, location: '/?schoolId=2', adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(_title(tester), '부산대');
    });

    testWidgets('학교 목록을 기다리는 중이면 높이만 맞춘 공백, 드롭다운은 "불러오는 중..."', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, _schools, gate))
        ..on('GET', '/categories', FakeReply(200, _categories))
        ..on('GET', '/posts', FakeReply(200, []));
      await pumpApp(tester, location: '/?schoolId=1', adapter: adapter);
      expect(_title(tester), ' ');
      expect(find.text('불러오는 중...'), findsOneWidget); // 드롭다운의 임시 항목 (글 목록은 이미 끝나 판 안 로딩은 없다)

      gate.complete();
      await tester.pumpAndSettle();
      expect(_title(tester), '서울대');
    });

    testWidgets('학교 목록에 없는 id면 "학교 게시판"', (tester) async {
      await pumpApp(tester, location: '/?schoolId=99', adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(_title(tester), '학교 게시판');
    });

    testWidgets('학교 목록이 실패해도 "학교 게시판"', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(500))
        ..on('GET', '/categories', FakeReply(200, _categories))
        ..on('GET', '/posts', FakeReply(200, []));
      await pumpApp(tester, location: '/?schoolId=1', adapter: adapter);
      expect(_title(tester), '학교 게시판');
    });
  });

  group('주소 쿼리 → 요청', () {
    testWidgets('쿼리가 없으면 조건 없이 요청한다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, [])]);
      await pumpApp(tester, adapter: adapter);
      expect(adapter.callsTo('GET', '/posts').single.query, isEmpty);
      // 드롭다운 목록은 한 번씩만
      expect(adapter.count('GET', '/schools'), 1);
      expect(adapter.count('GET', '/categories'), 1);
    });

    testWidgets('schoolId·category가 그대로 쿼리로 간다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, [])]);
      await pumpApp(tester, location: '/?schoolId=1&category=CHAT', adapter: adapter);
      expect(adapter.callsTo('GET', '/posts').single.query, {'schoolId': '1', 'category': 'CHAT'});
    });

    testWidgets('빈 값은 쿼리에 넣지 않는다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, [])]);
      await pumpApp(tester, location: '/?schoolId=&category=QUESTION', adapter: adapter);
      expect(adapter.callsTo('GET', '/posts').single.query, {'category': 'QUESTION'});
    });

    testWidgets('이상한 값도 거르지 않고 서버로 보낸다 (판단은 서버)', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, [])]);
      await pumpApp(tester, location: '/?schoolId=abc', adapter: adapter);
      expect(adapter.callsTo('GET', '/posts').single.query, {'schoolId': 'abc'});
    });
  });

  group('드롭다운 → 주소 → 재요청', () {
    testWidgets('학교·말머리를 고르면 주소가 바뀌고 그 조건으로 다시 요청한다', (tester) async {
      final adapter = _adapter(
        posts: [FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])],
      );
      final app = await pumpApp(tester, adapter: adapter);
      expect(find.text('전체 학교'), findsOneWidget);
      expect(find.text('전체 말머리'), findsOneWidget);

      await _pick(tester, _schoolSelect, '서울대');
      expect(_uri(app), '/?schoolId=1');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1'});
      expect(_title(tester), '서울대');

      await _pick(tester, _categorySelect, '질문');
      expect(_uri(app), '/?schoolId=1&category=QUESTION');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1', 'category': 'QUESTION'});
      expect(adapter.count('GET', '/posts'), 3);
      // 드롭다운 목록은 다시 부르지 않는다
      expect(adapter.count('GET', '/schools'), 1);
      expect(adapter.count('GET', '/categories'), 1);
    });

    testWidgets('"전체"를 고르면 그 파라미터가 주소에서 빠진다', (tester) async {
      final adapter = _adapter(
        posts: [FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])],
      );
      final app = await pumpApp(tester, location: '/?schoolId=1&category=CHAT', adapter: adapter);

      await _pick(tester, _categorySelect, '전체 말머리');
      expect(_uri(app), '/?schoolId=1');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1'});

      await _pick(tester, _schoolSelect, '전체 학교');
      expect(_uri(app), '/');
      expect(adapter.callsTo('GET', '/posts').last.query, isEmpty);
      expect(_title(tester), '전체 게시판');
    });

    testWidgets('주소가 직접 바뀌어도(뒤로 가기 등) 다시 요청하고 드롭다운이 따라간다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, adapter: adapter);
      app.router.go('/?category=QUESTION');
      await tester.pumpAndSettle();
      expect(adapter.callsTo('GET', '/posts').last.query, {'category': 'QUESTION'});
      expect(find.text('질문'), findsOneWidget); // 말머리 드롭다운이 주소 값을 보여 준다
    });
  });

  group('임시 항목', () {
    testWidgets('목록에 없는 학교·말머리는 "알 수 없는 ..." 항목으로 보인다', (tester) async {
      await pumpApp(
        tester,
        location: '/?schoolId=99&category=XYZ',
        adapter: _adapter(posts: [FakeReply(200, [])]),
      );
      expect(find.text('알 수 없는 학교 (99)'), findsOneWidget);
      expect(find.text('알 수 없는 말머리 (XYZ)'), findsOneWidget);
    });

    testWidgets('"전체"를 고르면 임시 항목에서 빠져나온다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/?schoolId=99', adapter: adapter);
      await _pick(tester, _schoolSelect, '전체 학교');
      expect(_uri(app), '/');
      expect(find.text('알 수 없는 학교 (99)'), findsNothing);
    });

    testWidgets('목록을 받는 중이면 "알 수 없는"이 아니라 "불러오는 중..."', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, _schools))
        ..on('GET', '/categories', FakeReply(200, _categories, gate))
        ..on('GET', '/posts', FakeReply(200, []));
      await pumpApp(tester, location: '/?category=QUESTION', adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.textContaining('알 수 없는'), findsNothing);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('질문'), findsOneWidget);
      expect(find.text('불러오는 중...'), findsNothing);
    });
  });

  group('드롭다운 목록 실패', () {
    testWidgets('하나라도 실패하면 안내 1개, 글 목록은 그대로 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, _schools))
        ..on('GET', '/categories', FakeReply(500))
        ..on('GET', '/posts', FakeReply(200, [_post(1, '첫 글')]));
      await pumpApp(tester, adapter: adapter);
      expect(find.text('학교·말머리 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('첫 글'), findsOneWidget);
    });

    testWidgets('둘 다 실패해도 안내는 1개', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply.networkError())
        ..on('GET', '/categories', FakeReply(500))
        ..on('GET', '/posts', FakeReply(200, [_post(1, '첫 글')]));
      await pumpApp(tester, adapter: adapter);
      expect(find.text('학교·말머리 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('첫 글'), findsOneWidget);
    });

    testWidgets('실패 안내는 error 종류(alert로 읽힘) 하나만, 성공이면 error 안내가 없다', (tester) async {
      final failing = find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.error);
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(500))
        ..on('GET', '/categories', FakeReply(200, _categories))
        ..on('GET', '/posts', FakeReply(200, []));
      await pumpApp(tester, adapter: adapter);
      expect(failing, findsOneWidget);
    });

    testWidgets('둘 다 성공하면 안내가 없다', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(find.byType(Notice), findsNothing);
    });
  });

  group('행', () {
    testWidgets('말머리 뱃지·학교 뱃지·제목·작성자·날짜·댓글·공감이 보인다', (tester) async {
      final posts = [
        _post(10, '첫 글', commentCount: 3, likeCount: 4),
        _post(
          11,
          '둘째 글',
          category: const {'code': 'QUESTION', 'name': '질문'},
          school: _busan,
          author: const {'id': 6, 'nickname': '영희'},
        ),
        _post(
          12,
          '셋째 글',
          category: const {'code': 'CAMPUS_LIFE', 'name': '캠퍼스 생활'},
          author: null,
          school: null,
        ),
      ];
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));

      expect(find.text('첫 글'), findsOneWidget);
      expect(find.text('둘째 글'), findsOneWidget);
      expect(find.text('셋째 글'), findsOneWidget);
      // 작성자: 닉네임 / 없으면 "(작성자 없음)"
      expect(find.text('철수'), findsOneWidget);
      expect(find.text('영희'), findsOneWidget);
      expect(find.text('(작성자 없음)'), findsOneWidget);
      // 날짜는 한국 시간 (2020년이라 연도가 붙는다)
      expect(find.text('2020년 11월 8일 15:19'), findsNWidgets(3));
      // 댓글·공감 수
      expect(find.text('댓글 3'), findsOneWidget);
      expect(find.text('공감 4'), findsOneWidget);
      expect(find.text('댓글 0'), findsNWidgets(2));
      // 학교 뱃지 (학교 필터가 없을 때): 값 없으면 "학교 미지정"
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('부산대'), findsOneWidget);
      expect(find.text('학교 미지정'), findsOneWidget);
    });

    testWidgets('말머리 뱃지 색: CHAT·QUESTION·CAMPUS_LIFE', (tester) async {
      final posts = [
        _post(10, 'a'),
        _post(11, 'b', category: const {'code': 'QUESTION', 'name': '질문'}),
        _post(12, 'c', category: const {'code': 'CAMPUS_LIFE', 'name': '캠퍼스 생활'}),
      ];
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));

      // 같은 글자가 여러 줄에 있을 수 있어(학교 뱃지) 첫 것을 본다
      Color? ink(String t) => tester.widget<Text>(find.text(t).first).style?.color;
      Color? bg(String t) {
        final box = tester.widget<Container>(
          find.ancestor(of: find.text(t).first, matching: find.byType(Container)).first,
        );
        return (box.decoration as BoxDecoration).color;
      }

      expect(ink('잡담'), AppColors.muted);
      expect(bg('잡담'), const Color(0xFF262F2C));
      expect(ink('질문'), const Color(0xFFA9C2F0));
      expect(bg('질문'), const Color(0xFF1F2B42));
      expect(ink('캠퍼스 생활'), AppColors.celadonInk);
      expect(bg('캠퍼스 생활'), AppColors.celadonSoft);

      // 학교 뱃지: 글자 muted, 배경 paper, 테두리 line
      expect(ink('서울대'), AppColors.muted);
      expect(bg('서울대'), AppColors.paper);
      final schoolBox = tester.widget<Container>(
        find.ancestor(of: find.text('서울대').first, matching: find.byType(Container)).first,
      );
      expect((schoolBox.decoration as BoxDecoration).border, isNotNull);
    });

    testWidgets('학교로 거른 화면에서는 학교 뱃지를 생략한다', (tester) async {
      final posts = [
        _post(10, 'a', school: _busan),
        _post(11, 'b', school: null),
      ];
      await pumpApp(
        tester,
        location: '/?schoolId=1',
        adapter: _adapter(posts: [FakeReply(200, posts)]),
      );
      expect(find.text('부산대'), findsNothing);
      expect(find.text('학교 미지정'), findsNothing);
      expect(find.text('a'), findsOneWidget);
    });

    testWidgets('제목만 링크: 누르면 /posts/{id} 로 간다', (tester) async {
      final app = await pumpApp(
        tester,
        adapter: _adapter(posts: [FakeReply(200, [_post(10, '첫 글'), _post(11, '둘째 글')])]),
      );
      await tester.tap(find.text('둘째 글'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/11');
      expect(find.text('글 상세 (id: 11)'), findsOneWidget);
    });
  });

  group('글쓰기 버튼', () {
    testWidgets('비로그인이면 /login', (tester) async {
      final app = await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      await tester.tap(find.widgetWithText(FilledButton, '글쓰기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    testWidgets('로그인이면 /posts/new', (tester) async {
      final app = await pumpApp(
        tester,
        user: userJson(),
        adapter: _adapter(posts: [FakeReply(200, [])]),
      );
      await tester.tap(find.widgetWithText(FilledButton, '글쓰기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/new');
    });
  });

  group('로딩·빈 목록·에러', () {
    testWidgets('불러오는 동안 "불러오는 중..."', (tester) async {
      final gate = Completer<void>();
      await pumpApp(
        tester,
        adapter: _adapter(posts: [FakeReply(200, [_post(1, '첫 글')], gate)]),
      );
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.text('아직 글이 없습니다'), findsNothing);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('첫 글'), findsOneWidget);
    });

    testWidgets('빈 목록이면 "아직 글이 없습니다"', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(find.text('아직 글이 없습니다'), findsOneWidget);
    });

    final errors = <(String, FakeReply, String)>[
      ('404', FakeReply(404), '존재하지 않는 학교입니다'),
      ('400', FakeReply(400), '잘못된 조건입니다. 학교와 말머리를 다시 골라 주세요'),
      ('500', FakeReply(500), '목록을 불러오지 못했습니다'),
      ('401', FakeReply(401), '목록을 불러오지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, text) in errors) {
      testWidgets('에러 $name → "$text" (빈 목록 글자는 안 나온다)', (tester) async {
        final adapter = _adapter(posts: [reply])..on('POST', '/auth/refresh', FakeReply(401));
        await pumpApp(tester, adapter: adapter);
        expect(find.text(text), findsOneWidget);
        expect(find.text('아직 글이 없습니다'), findsNothing);
      });
    }
  });

  testWidgets('늦게 도착한 옛 응답은 무시한다', (tester) async {
    final gate = Completer<void>();
    final adapter = _adapter(
      posts: [
        FakeReply(200, [_post(1, '옛 조건의 글')], gate), // 첫 요청: 붙잡아 둔다
        FakeReply(200, [_post(2, '새 조건의 글')]), // 두 번째 요청: 바로 응답
      ],
    );
    final app = await pumpApp(tester, adapter: adapter);
    expect(find.text('불러오는 중...'), findsOneWidget);

    app.router.go('/?schoolId=1');
    await tester.pumpAndSettle();
    expect(find.text('새 조건의 글'), findsOneWidget);

    gate.complete(); // 이제 옛 응답이 도착해도
    await tester.pumpAndSettle();
    expect(find.text('옛 조건의 글'), findsNothing);
    expect(find.text('새 조건의 글'), findsOneWidget);
  });

  testWidgets('옛 조건이 에러로 늦게 와도 새 결과를 덮지 않는다', (tester) async {
    final gate = Completer<void>();
    final adapter = _adapter(
      posts: [
        FakeReply(500, null, gate),
        FakeReply(200, [_post(2, '새 조건의 글')]),
      ],
    );
    final app = await pumpApp(tester, adapter: adapter);
    app.router.go('/?category=CHAT');
    await tester.pumpAndSettle();
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('목록을 불러오지 못했습니다'), findsNothing);
    expect(find.text('새 조건의 글'), findsOneWidget);
  });

  testWidgets('조건이 바뀌면 옛 결과 대신 로딩이 보인다', (tester) async {
    final gate = Completer<void>();
    final adapter = _adapter(
      posts: [
        FakeReply(200, [_post(1, '옛 글')]),
        FakeReply(200, [_post(2, '새 글')], gate),
      ],
    );
    final app = await pumpApp(tester, adapter: adapter);
    expect(find.text('옛 글'), findsOneWidget);
    app.router.go('/?schoolId=1');
    await tester.pump();
    await tester.pump();
    expect(find.text('옛 글'), findsNothing);
    expect(find.text('불러오는 중...'), findsOneWidget);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('새 글'), findsOneWidget);
  });

  group('탈퇴 화면이 넘긴 안내', () {
    testWidgets('extra 문자열이 있으면 안내(info)로 보인다', (tester) async {
      final app = await pumpApp(
        tester,
        location: '/login',
        adapter: _adapter(posts: [FakeReply(200, [])]),
      );
      app.router.go('/', extra: '회원 탈퇴가 완료되었습니다');
      await tester.pumpAndSettle();
      expect(find.text('회원 탈퇴가 완료되었습니다'), findsOneWidget);
    });

    testWidgets('없으면 안내가 안 보인다', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(find.byType(Notice), findsNothing);
    });
  });

  testWidgets('375px 폭: 툴바가 줄바꿈되고 가로로 넘치지 않는다 (긴 이름·제목 포함)', (tester) async {
    tester.view.physicalSize = const Size(375, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final longSchool = {'id': 3, 'name': '아주아주아주아주아주아주아주아주아주 긴 이름의 대학교'};
    final posts = [
      _post(
        1,
        '아주 긴 제목이 좁은 화면에서도 가로로 넘치지 않고 줄바꿈되어야 한다 아주 긴 제목이 좁은 화면에서도 가로로 넘치지 않고',
        school: longSchool,
      ),
    ];
    await pumpApp(
      tester,
      user: userJson(),
      adapter: _adapter(posts: [FakeReply(200, posts)]),
    );
    expect(tester.takeException(), isNull); // 넘침(overflow)은 테스트 중 예외로 잡힌다

    // 오른쪽 끝(375)을 넘는 위젯이 없다
    final write = tester.getRect(find.widgetWithText(FilledButton, '글쓰기'));
    expect(write.right, lessThanOrEqualTo(375));
    // 필터 두 개와 글쓰기가 한 줄에 못 들어가 글쓰기가 아래 줄로 내려갔다
    final school = tester.getRect(_schoolSelect);
    expect(write.top, greaterThan(school.top));
    expect(tester.getRect(_categorySelect).right, lessThanOrEqualTo(375));
    // 필터 묶음 아래와 글쓰기 위 간격은 줄 간격 8 (필터 묶음에 따로 아래 여백이 없다)
    // 키는 테두리 안쪽 드롭다운에 달려 있어, 테두리까지 포함한 바깥 상자(Container)로 잰다
    final box = find.ancestor(of: _categorySelect, matching: find.byType(Container)).first;
    expect(write.top - tester.getRect(box).bottom, 8);
    // 긴 제목·긴 학교 이름의 오른쪽 끝도 화면 오른쪽 여백(16)을 넘지 않는다
    final title = find.textContaining('아주 긴 제목');
    expect(tester.getRect(title).right, lessThanOrEqualTo(375 - 16));
    expect(tester.getRect(find.textContaining('아주아주아주')).right, lessThanOrEqualTo(375 - 16));
  });

  testWidgets('넓은 화면: 글쓰기 버튼과 드롭다운의 세로 중심이 맞는다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
    final write = tester.getRect(find.widgetWithText(FilledButton, '글쓰기'));
    final school = tester.getRect(_schoolSelect);
    expect(write.center.dy, closeTo(school.center.dy, 0.5));
  });

  group('목록 요청 횟수', () {
    testWidgets('같은 주소로 다시 go 해도 /posts 는 다시 부르지 않는다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/?schoolId=1', adapter: adapter);
      app.router.go('/?schoolId=1');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/posts'), 1);
    });

    testWidgets('extra만 바뀌어도 다시 부르지 않는다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, adapter: adapter);
      app.router.go('/', extra: '첫 안내');
      await tester.pumpAndSettle();
      app.router.go('/', extra: '다른 안내');
      await tester.pumpAndSettle();
      expect(find.text('다른 안내'), findsOneWidget);
      expect(adapter.count('GET', '/posts'), 1);
    });
  });

  testWidgets('깨진 JSON 응답이면 에러 문구만 나오고 빈 목록 글자는 없다', (tester) async {
    // 글 목록이 배열이지만 원소가 객체가 아니라 fromJson 캐스팅이 실패한다
    await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [123])]));
    expect(find.text('목록을 불러오지 못했습니다'), findsOneWidget);
    expect(find.text('아직 글이 없습니다'), findsNothing);
  });

  testWidgets('모르는 말머리 code는 색 없는 기본 뱃지 (ink 글자, 배경 없음)', (tester) async {
    final posts = [_post(10, 'a', category: const {'code': 'XYZ', 'name': '새말머리'})];
    await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));
    expect(tester.widget<Text>(find.text('새말머리')).style?.color, AppColors.ink);
    final box = tester.widget<Container>(
      find.ancestor(of: find.text('새말머리'), matching: find.byType(Container)).first,
    );
    expect((box.decoration as BoxDecoration).color, Colors.transparent);
  });
}
