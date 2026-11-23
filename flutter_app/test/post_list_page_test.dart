// S16-e1 글 목록 화면 테스트: React PostListPage와 같은 동작(주소 쿼리가 진짜 값, 머리말, 드롭다운, 행, 문구 4종)을 본다.
import 'dart:async';
import 'dart:ui' show Tristate;

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/page_frame.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

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

// 말머리 칩 (코드로 찾는다, "전체"는 'all')
Finder _chip(String code) => find.byKey(ValueKey('category-chip-$code'));
// 글 한 줄(InkWell) 전체: 맨 숫자 찾기를 그 줄 안으로 좁힐 때 쓴다
Finder _rowOf(String title) => find.ancestor(of: find.text(title), matching: find.byType(InkWell)).first;

// 칩이 스크린리더에 "선택됨"으로 읽히는지
bool _selected(WidgetTester tester, Finder chip) {
  final node = tester.getSemantics(find.ancestor(of: chip, matching: find.byType(Semantics)).first);
  return node.getSemanticsData().flagsCollection.isSelected == Tristate.isTrue;
}

// 학교 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pick(WidgetTester tester, Finder select, String text) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

// 말머리 칩을 누른다
Future<void> _tapChip(WidgetTester tester, String code) async {
  await tester.tap(_chip(code));
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

  group('드롭다운·칩 → 주소 → 재요청', () {
    testWidgets('학교를 고르고 말머리 칩을 누르면 주소가 바뀌고 그 조건으로 다시 요청한다', (tester) async {
      final adapter = _adapter(
        posts: [FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])],
      );
      final app = await pumpApp(tester, adapter: adapter);
      expect(find.text('전체 학교'), findsOneWidget);

      await _pick(tester, _schoolSelect, '서울대');
      expect(_uri(app), '/?schoolId=1');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1'});
      expect(_title(tester), '서울대');

      await _tapChip(tester, 'QUESTION');
      expect(_uri(app), '/?schoolId=1&category=QUESTION');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1', 'category': 'QUESTION'});
      expect(adapter.count('GET', '/posts'), 3);
      // 드롭다운 목록은 다시 부르지 않는다
      expect(adapter.count('GET', '/schools'), 1);
      expect(adapter.count('GET', '/categories'), 1);
    });

    testWidgets('"전체"를 고르면 그 파라미터가 주소에서 빠진다 (말머리는 전체 칩)', (tester) async {
      final adapter = _adapter(
        posts: [FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])],
      );
      final app = await pumpApp(tester, location: '/?schoolId=1&category=CHAT', adapter: adapter);

      await _tapChip(tester, 'all');
      expect(_uri(app), '/?schoolId=1');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '1'});

      await _pick(tester, _schoolSelect, '전체 학교');
      expect(_uri(app), '/');
      expect(adapter.callsTo('GET', '/posts').last.query, isEmpty);
      expect(_title(tester), '전체 게시판');
    });

    testWidgets('주소가 직접 바뀌어도(뒤로 가기 등) 다시 요청하고 칩이 따라간다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, adapter: adapter);
      app.router.go('/?category=QUESTION');
      await tester.pumpAndSettle();
      expect(adapter.callsTo('GET', '/posts').last.query, {'category': 'QUESTION'});
      expect(find.text('질문'), findsOneWidget); // 말머리 칩이 주소 값을 보여 준다
      final handle = tester.ensureSemantics();
      expect(_selected(tester, _chip('QUESTION')), isTrue);
      handle.dispose();
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
      // 댓글·공감 수: 눈에는 아이콘 + 숫자 (스크린리더 라벨은 아래 별도 테스트)
      expect(find.descendant(of: _rowOf('첫 글'), matching: find.text('3')), findsOneWidget);
      expect(find.descendant(of: _rowOf('첫 글'), matching: find.text('4')), findsOneWidget);
      for (final title in ['둘째 글', '셋째 글']) {
        expect(find.descendant(of: _rowOf(title), matching: find.text('0')), findsNWidgets(2)); // 댓글, 공감
      }
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

      // 같은 글자가 칩 줄에도 있어 줄 안의 뱃지는 마지막 것을 본다
      // 같은 글자가 칩 줄에도 있어(칩이 먼저) 줄 안의 뱃지는 마지막 것을 본다
      Color? ink(String t) => tester.widget<Text>(find.text(t).last).style?.color;
      Color? bg(String t) {
        final box = tester.widget<Container>(
          find.ancestor(of: find.text(t).last, matching: find.byType(Container)).first,
        );
        return (box.decoration as BoxDecoration).color;
      }

      expect(ink('잡담'), AppColors.muted);
      expect(bg('잡담'), AppColors.badgeChatBg);
      expect(ink('질문'), AppColors.badgeQuestionInk);
      expect(bg('질문'), AppColors.badgeQuestionBg);
      expect(ink('캠퍼스 생활'), AppColors.highlightInk);
      expect(bg('캠퍼스 생활'), AppColors.highlightSoft);

      // 학교 뱃지: 글자 muted, 배경 paper, 테두리 line
      expect(ink('서울대'), AppColors.muted);
      expect(bg('서울대'), AppColors.paper);
      final schoolBox = tester.widget<Container>(
        find.ancestor(of: find.text('서울대').last, matching: find.byType(Container)).first,
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

    testWidgets('제목을 누르면 /posts/{id} 로 간다', (tester) async {
      // S16-f1: 상세 화면이 진짜가 되어, 상세가 불러올 글(GET /posts/11)도 준비한다
      final adapter = _adapter(posts: [FakeReply(200, [_post(10, '첫 글'), _post(11, '둘째 글')])])
        ..on('GET', '/posts/11', FakeReply(200, postJson(id: 11, title: '상세에서 본 글')));
      final app = await pumpApp(tester, adapter: adapter);
      await tester.tap(find.text('둘째 글'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/11');
      expect(find.text('상세에서 본 글'), findsOneWidget);
    });
  });

  group('글쓰기 연필 버튼', () {
    testWidgets('연필 버튼(tooltip 글쓰기)은 하나뿐이고 FilledButton 글쓰기는 없다', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(find.byTooltip('글쓰기'), findsOneWidget);
      expect(find.byType(FloatingActionButton), findsOneWidget);
      expect(find.widgetWithText(FilledButton, '글쓰기'), findsNothing);
    });

    testWidgets('비로그인이면 /login', (tester) async {
      final app = await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      await tester.tap(find.byTooltip('글쓰기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    testWidgets('로그인이면 /posts/new', (tester) async {
      final app = await pumpApp(
        tester,
        user: userJson(),
        adapter: _adapter(posts: [FakeReply(200, [])]),
      );
      await tester.tap(find.byTooltip('글쓰기'));
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

  testWidgets('375px 폭: 긴 이름·긴 제목도 넘치지 않고, 칩 줄이 폭 안에 있다', (tester) async {
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
        author: const {'id': 5, 'nickname': '아주아주아주아주아주아주아주아주 긴 닉네임'},
      ),
    ];
    await pumpApp(
      tester,
      user: userJson(),
      adapter: _adapter(posts: [FakeReply(200, posts)]),
    );
    expect(tester.takeException(), isNull); // 넘침(overflow)은 테스트 중 예외로 잡힌다

    expect(tester.getRect(_schoolSelect).right, lessThanOrEqualTo(375));
    // 칩 줄은 화면 폭 안에서 시작하고 가로 스크롤이라 폭을 넘겨 늘어나지 않는다
    expect(tester.getRect(_chip('all')).left, 16);
    // 긴 제목·긴 학교 이름의 오른쪽 끝도 화면 오른쪽 여백(16)을 넘지 않는다
    expect(tester.getRect(find.textContaining('아주 긴 제목')).right, lessThanOrEqualTo(375 - 16));
    expect(tester.getRect(find.textContaining('아주아주아주아주아주아주아주아주아주 긴 이름')).right, lessThanOrEqualTo(375 - 16));
  });

  testWidgets('375px 폭: 말머리 칩이 10개 넘어도 넘침 예외가 없고 가로로 밀린다', (tester) async {
    tester.view.physicalSize = const Size(375, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final many = [
      for (var i = 0; i < 12; i++) {'code': 'C$i', 'name': '말머리 번호 $i'},
    ];
    final adapter = FakeAdapter()
      ..on('GET', '/schools', FakeReply(200, _schools))
      ..on('GET', '/categories', FakeReply(200, many))
      ..on('GET', '/posts', FakeReply(200, [_post(1, '글')]));
    await pumpApp(tester, adapter: adapter);
    expect(tester.takeException(), isNull);
    // 끝쪽 칩은 화면 밖(오른쪽)에 있다가, 칩 줄을 밀면 보인다
    expect(tester.getRect(_chip('C11')).left, greaterThan(375));
    await tester.drag(_chip('all'), const Offset(-3000, 0));
    await tester.pumpAndSettle();
    expect(tester.getRect(_chip('C11')).right, lessThanOrEqualTo(375));
    expect(tester.takeException(), isNull);
  });

  testWidgets('375px 폭: 연필 버튼은 아래 탭 막대 바로 위·오른쪽 여백 16', (tester) async {
    tester.view.physicalSize = const Size(375, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
    final fab = tester.getRect(find.byType(FloatingActionButton));
    final tabTop = tester.getRect(find.byKey(const ValueKey('bottom-tab-0'))).top;
    expect(fab.right, 375 - 16);
    expect(fab.bottom, closeTo(tabTop - 16, 1.5)); // 탭 막대 위, 바깥 여백 16
  });

  testWidgets('넓은 화면(1200): 연필 버튼은 본문 기둥(720) 오른쪽 끝에 맞는다', (tester) async {
    tester.view.physicalSize = const Size(1200, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
    final fab = tester.getRect(find.byType(FloatingActionButton));
    expect(fab.right, 1200 / 2 + 720 / 2 - 16); // 기둥 오른쪽 끝(960) - 여백 16
    // 제목 줄 오른쪽의 학교 드롭다운 오른쪽 끝과도 맞는다
    // (키는 테두리 안쪽 드롭다운에 달려 있어 바깥 상자 Container로 잰다)
    final box = find.ancestor(of: _schoolSelect, matching: find.byType(Container)).first;
    expect(tester.getRect(box).right, closeTo(fab.right, 0.5));
  });

  group('피드형 줄', () {
    testWidgets('375px: 모든 글의 제목 왼쪽 x가 같다 (닉네임·작성자 없음과 상관없이)', (tester) async {
      tester.view.physicalSize = const Size(375, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final posts = [
        _post(1, '첫 글'),
        _post(2, '둘째 글', author: null),
        _post(3, '셋째 글', author: const {'id': 7, 'nickname': '😀이모지'}, school: null),
      ];
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));
      final x = tester.getTopLeft(find.text('첫 글')).dx;
      expect(tester.getTopLeft(find.text('둘째 글')).dx, x);
      expect(tester.getTopLeft(find.text('셋째 글')).dx, x);
    });

    testWidgets('줄의 빈 곳(왼쪽 여백)을 눌러도 /posts/{id} 로 가고, 줄 높이는 48 이상', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, [_post(10, '첫 글'), _post(11, '둘째 글')])])
        ..on('GET', '/posts/11', FakeReply(200, postJson(id: 11, title: '상세에서 본 글')));
      final app = await pumpApp(tester, adapter: adapter);
      final row = find.ancestor(of: find.text('둘째 글'), matching: find.byType(InkWell)).first;
      expect(tester.getSize(row).height, greaterThanOrEqualTo(48));
      final rect = tester.getRect(row);
      await tester.tapAt(Offset(rect.left + 4, rect.center.dy)); // 글자가 없는 왼쪽 가장자리
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/11');
    });

    testWidgets('내용이 아주 짧은 줄도 눌리는 높이가 48 이상', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [_post(1, '짧')])]));
      final row = find.ancestor(of: find.text('짧'), matching: find.byType(InkWell)).first;
      expect(tester.getSize(row).height, greaterThanOrEqualTo(48));
    });

    testWidgets('줄 하나는 링크 하나로 읽힌다 (제목이 따로 또 링크로 읽히지 않는다)', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(
        tester,
        adapter: _adapter(posts: [FakeReply(200, [_post(1, '첫 글'), _post(2, '둘째 글')])]),
      );
      final row = find.ancestor(of: find.text('첫 글'), matching: find.byType(InkWell)).first;
      final data = tester.getSemantics(row).getSemanticsData();
      expect(data.flagsCollection.isLink, isTrue);
      expect(data.linkUrl, Uri.parse('/posts/1')); // 링크 주소 = 눌러서 가는 곳
      expect(data.label, contains('첫 글'));
      expect(data.label, contains('철수'));
      // "첫 글"이 들어간 링크 노드는 줄 하나뿐: 줄 하나 = 링크 하나
      var links = 0;
      void walk(SemanticsNode n) {
        final d = n.getSemanticsData();
        if (d.flagsCollection.isLink && d.label.contains('첫 글')) links++;
        n.visitChildren((c) {
          walk(c);
          return true;
        });
      }

      walk(tester.getSemantics(find.byType(PageFrame)));
      expect(links, 1);
      handle.dispose();
    });

    testWidgets('스크린리더 라벨은 "댓글 3"·"공감 4" 그대로', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(
        tester,
        adapter: _adapter(posts: [FakeReply(200, [_post(1, '첫 글', commentCount: 3, likeCount: 4)])]),
      );
      final label = tester
          .getSemantics(find.ancestor(of: find.text('첫 글'), matching: find.byType(InkWell)).first)
          .getSemanticsData()
          .label;
      expect(label, contains('댓글 3'));
      expect(label, contains('공감 4'));
      // 읽는 순서: 제목, 닉네임, 말머리, 학교, 댓글, 공감, 시각
      final order = ['첫 글', '철수', '잡담', '서울대', '댓글 3', '공감 4', '11월 8일'];
      final at = [for (final t in order) label.indexOf(t)];
      expect(at.every((i) => i >= 0), isTrue, reason: label);
      expect(at, [...at]..sort(), reason: label);
      handle.dispose();
    });

    testWidgets('숫자 색: 0은 muted, 1 이상은 ink', (tester) async {
      await pumpApp(
        tester,
        adapter: _adapter(posts: [FakeReply(200, [_post(1, '첫 글', commentCount: 0, likeCount: 5)])]),
      );
      expect(tester.widget<Text>(find.descendant(of: _rowOf('첫 글'), matching: find.text('0'))).style?.color, AppColors.muted);
      expect(tester.widget<Text>(find.descendant(of: _rowOf('첫 글'), matching: find.text('5'))).style?.color, AppColors.ink);
    });

    testWidgets('이니셜: 한글·영문·이모지는 첫 글자, 작성자 없음은 빈 칸', (tester) async {
      final posts = [
        _post(1, 'a', author: const {'id': 1, 'nickname': '철수'}),
        _post(2, 'b', author: const {'id': 2, 'nickname': 'kim'}),
        _post(3, 'c', author: const {'id': 3, 'nickname': '😀웃음'}),
        _post(4, 'd', author: null),
      ];
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));
      Finder initial(String t) => find.descendant(of: find.byType(InitialAvatar), matching: find.text(t));
      expect(initial('철'), findsOneWidget);
      expect(initial('k'), findsOneWidget);
      expect(initial('😀'), findsOneWidget);
      expect(find.byType(InitialAvatar), findsNWidgets(4)); // 작성자 없음도 칸은 있다
    });

    testWidgets('마지막 줄이 연필 버튼에 가리지 않는다 (끝까지 내리면 줄 아래가 버튼 위)', (tester) async {
      tester.view.physicalSize = const Size(375, 500);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final posts = [for (var i = 1; i <= 8; i++) _post(i, '글 번호 $i')];
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, posts)]));
      await tester.drag(find.text('글 번호 1'), const Offset(0, -3000));
      await tester.pumpAndSettle();
      final lastRow = tester.getRect(find.ancestor(of: find.text('글 번호 8'), matching: find.byType(InkWell)).first);
      final fab = tester.getRect(find.byType(FloatingActionButton));
      expect(lastRow.bottom, lessThanOrEqualTo(fab.top));
    });
  });

  group('리뷰 수정 (S26-d)', () {
    testWidgets('연필 버튼은 동그라미 모양이다', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(tester.widget<FloatingActionButton>(find.byType(FloatingActionButton)).shape, isA<CircleBorder>());
    });

    testWidgets('줄과 칩은 스크린리더로도 누를 수 있다 (tap 동작)', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [_post(1, '첫 글')])]));
      final row = find.ancestor(of: find.text('첫 글'), matching: find.byType(InkWell)).first;
      expect(tester.getSemantics(row).getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
      expect(tester.getSemantics(_chip('QUESTION')).getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
      handle.dispose();
    });

    testWidgets('연필 버튼이 본문보다 먼저 읽히도록 순서표(sortKey)가 0, 본문이 1', (tester) async {
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      double keyOf(Finder f) {
        final keyed = find.ancestor(of: f, matching: find.byWidgetPredicate((w) => w is Semantics && w.properties.sortKey != null));
        final s = tester.widget<Semantics>(keyed.first);
        return (s.properties.sortKey! as OrdinalSortKey).order;
      }

      expect(keyOf(find.byType(FloatingActionButton)), 0);
      expect(keyOf(find.byType(PageFrame)), 1);
    });

    testWidgets('이미 고른 칩을 다시 눌러도 이동·재요청이 없고 탈퇴 안내(extra)가 남는다', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/login', adapter: adapter);
      app.router.go('/', extra: '회원 탈퇴가 완료되었습니다');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/posts'), 1);
      await _tapChip(tester, 'all'); // 이미 선택된 칩
      expect(_uri(app), '/');
      expect(adapter.count('GET', '/posts'), 1);
      expect(find.text('회원 탈퇴가 완료되었습니다'), findsOneWidget);
    });
  });

  group('말머리 칩', () {
    testWidgets('칩을 누르면 /?category=코드, 전체 칩이면 category만 빠지고 학교는 유지', (tester) async {
      final adapter = _adapter(posts: [FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/?schoolId=2', adapter: adapter);
      await _tapChip(tester, 'CAMPUS_LIFE');
      expect(_uri(app), '/?schoolId=2&category=CAMPUS_LIFE');
      await _tapChip(tester, 'all');
      expect(_uri(app), '/?schoolId=2');
      expect(adapter.callsTo('GET', '/posts').last.query, {'schoolId': '2'});
    });

    testWidgets('학교 없이 칩만 누르면 /?category=코드', (tester) async {
      final app = await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, []), FakeReply(200, [])]));
      await _tapChip(tester, 'CHAT');
      expect(_uri(app), '/?category=CHAT');
    });

    testWidgets('고른 칩만 selected, 체크가 붙는다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/?category=QUESTION', adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(_selected(tester, _chip('QUESTION')), isTrue);
      expect(_selected(tester, _chip('all')), isFalse);
      expect(_selected(tester, _chip('CHAT')), isFalse);
      expect(find.descendant(of: _chip('QUESTION'), matching: find.byIcon(Icons.check)), findsOneWidget);
      expect(find.descendant(of: _chip('CHAT'), matching: find.byIcon(Icons.check)), findsNothing);
      handle.dispose();
    });

    testWidgets('주소가 없으면 "전체" 칩이 선택돼 있다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, adapter: _adapter(posts: [FakeReply(200, [])]));
      expect(_selected(tester, _chip('all')), isTrue);
      handle.dispose();
    });

    testWidgets('말머리 목록이 실패하면 "전체" 칩만 남고 안내가 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, _schools))
        ..on('GET', '/categories', FakeReply(500))
        ..on('GET', '/posts', FakeReply(200, []));
      await pumpApp(tester, adapter: adapter);
      expect(_chip('all'), findsOneWidget);
      expect(_chip('CHAT'), findsNothing);
      expect(find.text('학교·말머리 목록을 불러오지 못했습니다'), findsOneWidget);
    });
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
