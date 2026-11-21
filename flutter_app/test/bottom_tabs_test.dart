// S26-c 아래 탭 막대 테스트: 탭 5개 순서, 주소 → 선택 탭 매핑, 선택 표시(색·굵기·아이콘·스크린리더),
// 쪽지 배지(3 / 0), 눌림 영역 48 이상, 비로그인 쪽지·내 정보 → /login, 1200폭에서 5칸이 본문 720 안.
import 'dart:ui' show Tristate;

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/bottom_tabs.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _labels = ['게시판', '비자', '중고', '쪽지', '내 정보'];

// i번째 탭 칸(InkWell)과 그 안의 글자
Finder _cell(int i) => find.byKey(ValueKey('bottom-tab-$i'));
Finder _label(int i) => find.descendant(of: _cell(i), matching: find.text(_labels[i]));
final _badge = find.byKey(const ValueKey('unread-badge'));

// 안 읽은 수 응답을 넉넉히 깐 가짜 서버 (시작·화면 이동마다 한 번씩 부른다)
FakeAdapter _counts(int n) {
  final a = FakeAdapter();
  for (var i = 0; i < 6; i++) {
    a.on('GET', '/conversations/unread-count', FakeReply(200, {'count': n}));
  }
  return a;
}

// 여러 화면을 열 수 있게 목록 응답들을 깐다
FakeAdapter _listsAdapter() => _counts(0)
  ..on('GET', '/conversations', FakeReply(200, []))
  ..on('GET', '/schools', FakeReply(200, []))
  ..on('GET', '/categories', FakeReply(200, []))
  ..on('GET', '/posts', FakeReply(200, []))
  ..on('GET', '/visa-review-options', FakeReply(200, {'visaTypes': [], 'applicationTypes': [], 'results': []}))
  ..on('GET', '/visa-reviews', FakeReply(200, []))
  ..on('GET', '/market-items', FakeReply(200, {'items': [], 'hasMore': false}));

void main() {
  group('주소 → 선택 탭 (tabIndexFor)', () {
    const cases = <(String, int)>[
      ('/', 0),
      ('/posts/3', 0),
      ('/posts/new', 0),
      ('/posts/3/edit', 0),
      ('/visa-reviews', 1),
      ('/visa-reviews/stats', 1),
      ('/visa-reviews/5', 1),
      ('/visa-plan', 1),
      ('/market', 2),
      ('/market/new', 2),
      ('/market/7/edit', 2),
      ('/chat', 2),
      ('/messages', 3),
      ('/messages/3', 3),
      ('/me', 4),
      ('/admin/reports', 4),
      ('/admin/permits', 4),
      ('/admin/reports/9/conversation', 4),
      ('/login', -1), // S26-j1: 로그인·회원가입은 선택 없음
      ('/signup', -1),
      ('/ME', 4), // 라우터가 대소문자를 가리지 않으니 탭도 같다
      ('/Visa-Reviews', 1),
      ('/zzz', -1), // 404는 선택 없음
      ('/marketplace', -1), // 첫 마디가 정확히 같을 때만
    ];
    for (final (path, index) in cases) {
      test('$path → ${index < 0 ? "선택 없음" : _labels[index]}', () {
        expect(tabIndexFor(path), index);
      });
    }
  });

  group('모양', () {
    testWidgets('탭 5개가 게시판 · 비자 · 중고 · 쪽지 · 내 정보 순서로 왼쪽부터 놓인다 (비로그인도 똑같이)', (tester) async {
      await pumpApp(tester);
      for (var i = 0; i < 5; i++) {
        expect(_label(i), findsOneWidget);
      }
      final xs = [for (var i = 0; i < 5; i++) tester.getTopLeft(_label(i)).dx];
      expect(xs, [...xs]..sort());
      expect(find.byKey(const ValueKey('bottom-tab-5')), findsNothing);
    });

    testWidgets('막대 바탕은 paper, 위쪽에 line 선 하나, 그림자 없음', (tester) async {
      await pumpApp(tester);
      // 선을 그리는 DecoratedBox가 paper(Material) "앞"(foreground)에 그려져야 불투명 바탕에 덮이지 않는다
      final line = find.ancestor(
        of: _cell(0),
        matching: find.byWidgetPredicate((w) => w is DecoratedBox && w.position == DecorationPosition.foreground),
      );
      expect(line, findsOneWidget);
      final decoration = tester.widget<DecoratedBox>(line).decoration as BoxDecoration;
      expect(decoration.border, const Border(top: BorderSide(color: AppColors.line)));
      expect(decoration.boxShadow, isNull);
      // 그 선 상자 안쪽에 paper 바탕(Material)이 있다 = 선이 바탕 위에 겹쳐 그려지는 구조
      final paper = find.descendant(of: line, matching: find.byWidgetPredicate((w) => w is Material && w.color == AppColors.paper));
      expect(paper, findsOneWidget);
    });

    testWidgets('탭 한 칸의 눌리는 영역은 가로·세로 48 이상 (375px에서도)', (tester) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await pumpApp(tester);
      for (var i = 0; i < 5; i++) {
        final size = tester.getSize(_cell(i));
        expect(size.width, greaterThanOrEqualTo(48));
        expect(size.height, greaterThanOrEqualTo(48));
      }
    });

    testWidgets('기기 글자 크기를 2배로 키워도 탭 막대가 넘치지 않고 높이 56이 그대로다', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = 2.0;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await pumpApp(tester, user: userJson(), adapter: _counts(3));
      expect(tester.takeException(), isNull); // 넘침(overflow) 에러가 없다
      expect(tester.getSize(_cell(0)).height, 56);
    });

    testWidgets('1200px 넓은 화면: 5칸은 본문 폭 720 안에 모이고, 막대 바탕은 화면 끝까지 간다', (tester) async {
      tester.view.physicalSize = const Size(1200, 800);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await pumpApp(tester);
      final left = tester.getTopLeft(_cell(0)).dx;
      final right = tester.getTopRight(_cell(4)).dx;
      expect(left, 240); // (1200 - 720) / 2
      expect(right, 960);
      expect(right - left, 720);
      final bar = find.ancestor(of: _cell(0), matching: find.byType(Material)).first;
      expect(tester.getSize(bar).width, 1200); // 바탕은 화면 전체 폭
    });
  });

  group('선택 표시', () {
    // 선택된 칸: accent 색 + 굵은 글자 + 채운 아이콘. 안 고른 칸: muted + 보통 굵기 + 테두리 아이콘
    void expectOnlySelected(WidgetTester tester, int selected) {
      for (var i = 0; i < 5; i++) {
        final style = tester.widget<Text>(_label(i)).style!;
        final on = i == selected;
        expect(style.color, on ? AppColors.accent : AppColors.muted, reason: '${_labels[i]} 글자 색');
        expect(style.fontWeight, on ? FontWeight.w700 : FontWeight.w400, reason: '${_labels[i]} 굵기');
        expect(style.fontSize, 13);
        final icon = tester.widget<Icon>(find.descendant(of: _cell(i), matching: find.byType(Icon)));
        expect(icon.color, on ? AppColors.accent : AppColors.muted, reason: '${_labels[i]} 아이콘 색');
      }
    }

    const samples = <(String, int)>[('/', 0), ('/visa-reviews', 1), ('/market', 2), ('/messages', 3), ('/me', 4), ('/zzz', -1)];
    for (final (path, index) in samples) {
      testWidgets('$path → ${index < 0 ? "선택 없음" : _labels[index]}', (tester) async {
        // 보호 화면(/messages·/me)을 열 수 있게 로그인 상태로 시작한다
        await pumpApp(tester, location: path, user: userJson(), adapter: _listsAdapter());
        expectOnlySelected(tester, index);
      });
    }

    testWidgets('선택된 칸은 채운 아이콘·안 고른 칸은 테두리 아이콘이고, 스크린리더에 selected로 읽힌다', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(tester);
      Icon iconOf(int i) => tester.widget<Icon>(find.descendant(of: _cell(i), matching: find.byType(Icon)));
      expect(iconOf(0).icon, Icons.home); // 채운
      expect(iconOf(1).icon, Icons.flight_takeoff_outlined); // 테두리
      expect(tester.getSemantics(_cell(0)).getSemanticsData().flagsCollection.isSelected, Tristate.isTrue);
      expect(tester.getSemantics(_cell(1)).getSemanticsData().flagsCollection.isSelected, Tristate.isFalse);
      // 스크린리더로도 누를 수 있다 (excludeSemantics가 InkWell의 누르기를 지우므로 Semantics에 직접 tap을 준다)
      expect(tester.getSemantics(_cell(1)).getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
      semantics.dispose();
    });
  });

  group('쪽지 배지', () {
    testWidgets('안 읽은 쪽지가 3이면 쪽지 탭에 노랑 동그라미와 ink 숫자 "3", 스크린리더는 개수까지 읽는다', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(tester, user: userJson(), adapter: _counts(3));
      expect(find.descendant(of: _cell(3), matching: _badge), findsOneWidget);
      final dot = tester.widget<Container>(_badge);
      expect((dot.decoration as BoxDecoration).color, AppColors.highlight);
      final number = tester.widget<Text>(find.descendant(of: _badge, matching: find.text('3')));
      expect(number.style?.color, AppColors.ink);
      expect(find.bySemanticsLabel('쪽지, 안 읽은 쪽지 3개'), findsOneWidget);
      semantics.dispose();
    });

    testWidgets('0이면 배지(숫자)가 없다', (tester) async {
      await pumpApp(tester, user: userJson(), adapter: _counts(0));
      expect(_badge, findsNothing);
      expect(_label(3), findsOneWidget);
    });
  });

  group('누르기', () {
    testWidgets('탭을 누르면 그 주소로 가고 눌린 탭이 선택으로 바뀐다 (게시판 → 비자 → 중고)', (tester) async {
      final app = await pumpApp(tester, adapter: _listsAdapter());
      await tester.tap(_cell(1));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-reviews');
      expect(tester.widget<Text>(_label(1)).style?.color, AppColors.accent);
      await tester.tap(_cell(2));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/market');
    });

    for (final (i, name) in [(3, '쪽지'), (4, '내 정보')]) {
      testWidgets('비로그인이 $name 탭을 누르면 /login 으로 간다 (기존 문지기)', (tester) async {
        final app = await pumpApp(tester);
        await tester.tap(_cell(i));
        await tester.pumpAndSettle();
        expect(app.router.routerDelegate.currentConfiguration.uri.path, '/login');
      });
    }
  });

  group('내 정보 바로가기', () {
    testWidgets('인증 회원이면 비자 일정 링크가 있고 관리자 링크는 없다', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson(school: {'id': 3, 'name': '서울대'}));
      expect(find.text('신고 관리'), findsNothing);
      expect(find.text('인증 심사'), findsNothing);
      expect(find.text('비자 일정'), findsOneWidget);
    });

    testWidgets('관리자(미인증)면 신고 관리·인증 심사 링크가 있고 비자 일정은 없다', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson(role: 'ADMIN'));
      expect(find.text('신고 관리'), findsOneWidget);
      expect(find.text('인증 심사'), findsOneWidget);
      expect(find.text('비자 일정'), findsNothing);
    });

    testWidgets('바로가기를 누르면 그 주소로 간다 (비자 일정 → /visa-plan, 신고 관리 → /admin/reports)', (tester) async {
      final app = await pumpApp(tester, location: '/me', user: userJson(role: 'ADMIN', school: {'id': 3, 'name': '서울대'}));
      await tester.tap(find.text('비자 일정'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
      app.router.go('/me');
      await tester.pumpAndSettle();
      await tester.tap(find.text('신고 관리'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/admin/reports');
    });

    testWidgets('일반 회원(미인증)이면 바로가기 판이 없다', (tester) async {
      await pumpApp(tester, location: '/me', user: userJson());
      expect(find.text('비자 일정'), findsNothing);
      expect(find.text('신고 관리'), findsNothing);
      expect(find.text('인증 심사'), findsNothing);
    });
  });

  // S26-j1: 로그인·회원가입은 탭 선택 없음, 탭 첫 화면 머리말 30, 내 정보 바로가기 눌림 48
  group('S26-j1', () {
    testWidgets('비로그인이 쪽지 탭을 누르면 주소는 /login 이고 선택된 탭은 하나도 없다', (tester) async {
      final app = await pumpApp(tester);
      await tester.tap(_cell(3));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/login');
      for (var i = 0; i < 5; i++) {
        // 선택 안 된 칸은 muted 색 + 보통 굵기 + 테두리 아이콘
        final style = tester.widget<Text>(_label(i)).style!;
        expect(style.color, AppColors.muted, reason: '${_labels[i]} 글자 색');
        expect(style.fontWeight, FontWeight.w400, reason: '${_labels[i]} 굵기');
      }
    });

    // 머리말 글자를 크기(30)로 찾는다: 아래 탭·위 막대의 같은 글자와 구별하려고
    Finder boardTitle(String text) => find.byWidgetPredicate(
      (w) => w is Text && w.data == text && w.style?.fontSize == AppSizes.fsBoard,
    );

    for (final (path, title) in [('/messages', '쪽지함'), ('/me', '내 정보')]) {
      testWidgets('$path 머리말 "$title"은 30·굵게·heading 1 이다', (tester) async {
        final semantics = tester.ensureSemantics();
        await pumpApp(tester, location: path, user: userJson(), adapter: _listsAdapter());
        final text = tester.widget<Text>(boardTitle(title));
        expect(text.style?.fontWeight, FontWeight.w700);
        final data = tester.getSemantics(boardTitle(title)).getSemanticsData();
        expect(data.flagsCollection.isHeader, isTrue);
        expect(data.headingLevel, 1);
        semantics.dispose();
      });
    }

    // 바꾸기 전과 같은 모양: 30 · 굵게(700) · 잉크색 · 줄높이 1.3 · 아래 여백 12 · heading 1
    for (final (path, title) in [('/visa-reviews', '비자 후기'), ('/visa-reviews/stats', '비자 후기'), ('/market', '중고·나눔')]) {
      testWidgets('$path 머리말 "$title" 모양은 바뀌기 전과 같다(30·700·여백 12)', (tester) async {
        final semantics = tester.ensureSemantics();
        await pumpApp(tester, location: path, user: userJson(), adapter: _listsAdapter());
        final finder = boardTitle(title);
        expect(finder, findsOneWidget);
        final style = tester.widget<Text>(finder).style!;
        expect(style.fontSize, 30);
        expect(style.fontWeight, FontWeight.w700);
        expect(style.color, AppColors.ink);
        expect(style.height, 1.3);
        final padding = tester.widget<Padding>(find.ancestor(of: finder, matching: find.byType(Padding)).first);
        expect(padding.padding, const EdgeInsets.only(bottom: 12));
        final data = tester.getSemantics(finder).getSemanticsData();
        expect(data.headingLevel, 1);
        semantics.dispose();
      });
    }

    testWidgets('/me 바로가기(비자 일정·신고 관리·인증 심사) 눌림 높이는 각 48 이상이다', (tester) async {
      await pumpApp(
        tester,
        location: '/me',
        user: userJson(role: 'ADMIN', school: {'id': 3, 'name': '서울대'}),
      );
      for (final t in ['비자 일정', '신고 관리', '인증 심사']) {
        final inkWell = find.ancestor(of: find.text(t), matching: find.byType(InkWell));
        expect(tester.getSize(inkWell.first).height, greaterThanOrEqualTo(48), reason: t);
      }
    });
  });
}
