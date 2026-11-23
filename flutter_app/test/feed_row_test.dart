// FeedRow 단위 테스트: 링크 의미(linkUrl)·누르면 이동·노드 하나·높이·위 선을 확인한다.
// (웹 전용 Link 경로는 위젯 테스트로 볼 수 없어 e2e/feed-a11y.spec.ts가 맡는다. 여기는 앱(비웹) 경로.)
import 'package:board_web/widgets/feed_row.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

// 줄을 누르면 /detail로 가는지 보려고 진짜 라우터를 쓴다 (FeedRow가 context.go를 쓰므로)
Widget _wrap(Widget row) {
  final router = GoRouter(
    routes: [
      GoRoute(path: '/', builder: (_, _) => Scaffold(body: row)),
      GoRoute(path: '/detail', builder: (_, _) => const Scaffold(body: Text('상세 화면'))),
    ],
  );
  return MaterialApp.router(routerConfig: router);
}

Widget _row({required bool first}) => FeedRow(
      label: '제목, 닉네임',
      path: '/detail',
      first: first,
      child: const Text('제목'),
    );

// 화면이 새로 쌓인 횟수를 세는 관찰자
class _PushCounter extends NavigatorObserver {
  int pushes = 0;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) => pushes++;
}

void main() {
  testWidgets('줄을 누르면 path 주소로 이동한다', (tester) async {
    await tester.pumpWidget(_wrap(_row(first: true)));
    await tester.tap(find.text('제목'));
    await tester.pumpAndSettle();
    expect(find.text('상세 화면'), findsOneWidget);
  });

  testWidgets('스크린리더에는 label이 붙은 링크 하나로 읽히고 누를 수 있다', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(_wrap(_row(first: true)));
    final node = tester.getSemantics(find.byType(InkWell));
    expect(node.label, '제목, 닉네임');
    expect(node.flagsCollection.isLink, isTrue);
    expect(node.linkUrl, Uri.parse('/detail')); // 링크 주소 = 넘긴 경로
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    // 노드 하나: 안쪽 Text('제목')가 따로 읽히지 않는다
    expect(find.semantics.byLabel('제목'), findsNothing);
    handle.dispose();
  });

  // S28 tester 추가: "정확히 한 번" 이동하는지 (화면이 쌓이는 횟수를 센다)
  testWidgets('줄을 누르면 path로 정확히 한 번만 이동한다', (tester) async {
    final observer = _PushCounter();
    final router = GoRouter(
      observers: [observer],
      routes: [
        GoRoute(path: '/', builder: (_, _) => Scaffold(body: _row(first: true))),
        GoRoute(path: '/detail', builder: (_, _) => const Scaffold(body: Text('상세 화면'))),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    final before = observer.pushes; // 첫 화면이 쌓인 횟수
    await tester.tap(find.text('제목'));
    await tester.pumpAndSettle();
    expect(find.text('상세 화면'), findsOneWidget);
    expect(observer.pushes - before, 1);
    expect(router.state.uri.path, '/detail');
  });

  testWidgets('스크린리더의 누르기 동작으로도 path로 한 번 이동한다', (tester) async {
    final handle = tester.ensureSemantics();
    final observer = _PushCounter();
    final router = GoRouter(
      observers: [observer],
      routes: [
        GoRoute(path: '/', builder: (_, _) => Scaffold(body: _row(first: true))),
        GoRoute(path: '/detail', builder: (_, _) => const Scaffold(body: Text('상세 화면'))),
      ],
    );
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    final before = observer.pushes;
    tester.semantics.tap(find.semantics.byLabel('제목, 닉네임')); // 의미 트리의 tap 액션 실행
    await tester.pumpAndSettle();
    expect(find.text('상세 화면'), findsOneWidget);
    expect(observer.pushes - before, 1);
    handle.dispose();
  });

  testWidgets('"제목"이 들어간 읽기 노드는 줄 전체에 딱 하나뿐이다 (안쪽 글자가 따로 안 읽힘)', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(_wrap(_row(first: true)));
    // 정확히 같은 글자만 찾는 검사는 헛통과할 수 있어서, 포함 검색으로 노드 개수를 센다
    expect(find.semantics.byLabel(RegExp('제목')), findsOneWidget);
    handle.dispose();
  });

  testWidgets('높이는 48 이상', (tester) async {
    await tester.pumpWidget(_wrap(_row(first: true)));
    expect(tester.getSize(find.byType(FeedRow)).height, greaterThanOrEqualTo(48));
  });

  testWidgets('first면 위 선이 없고, 아니면 있다', (tester) async {
    BoxDecoration deco() {
      final box = tester.widget<Container>(
        find.descendant(
          of: find.byType(FeedRow),
          matching: find.byType(Container),
        ),
      );
      return box.decoration! as BoxDecoration;
    }

    await tester.pumpWidget(_wrap(_row(first: true)));
    expect(deco().border, isNull);

    await tester.pumpWidget(_wrap(_row(first: false)));
    expect(deco().border, isNotNull);
  });
}
