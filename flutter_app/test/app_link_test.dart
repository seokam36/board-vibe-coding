// AppLink 단위 테스트(S29-a): 앱(비웹) 경로의 링크 의미·이동 횟수·눌림 높이, 바깥 링크(external)의 열기·링크 의미를 확인한다(S29-b).
// (웹 전용 Link 경로는 위젯 테스트로 볼 수 없어 e2e/link-a11y.spec.ts가 맡고, 바깥 링크의 웹 경로는 e2e/visa-link.spec.ts가 맡는다.)
import 'package:board_web/common/external_link.dart';
import 'package:board_web/widgets/app_link.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter/semantics.dart' show SemanticsAction;
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

// 화면이 새로 쌓인 횟수를 세는 관찰자
class _PushCounter extends NavigatorObserver {
  int pushes = 0;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) => pushes++;
}

// 열려는 주소만 기록하는 가짜 열기 도구
class _FakeOpener implements ExternalLinkOpener {
  final List<String> opened = [];

  @override
  void open(String url) => opened.add(url);
}

// link를 첫 화면에 놓은 진짜 라우터 (AppLink가 context.go를 쓰므로)
GoRouter _router(Widget link, _PushCounter observer) => GoRouter(
      observers: [observer],
      routes: [
        GoRoute(path: '/', builder: (_, _) => Scaffold(body: Center(child: link))),
        GoRoute(path: '/login', builder: (_, _) => const Scaffold(body: Text('로그인 화면'))),
      ],
    );

void main() {
  const link = AppLink(label: '로그인', path: '/login');

  testWidgets('스크린리더에는 label·링크 주소가 붙은 링크 노드 하나로 읽힌다', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(link, _PushCounter())));
    // 정확 일치 byLabel은 합쳐진 노드를 놓쳐 '1개' 검사가 거짓 통과할 수 있어, 부분 일치(RegExp)로 센다
    expect(find.semantics.byLabel(RegExp('로그인')), findsOneWidget); // 안쪽 글자가 따로 읽히지 않는다
    final node = tester.getSemantics(find.byType(InkWell));
    expect(node.label, '로그인');
    expect(node.flagsCollection.isLink, isTrue);
    expect(node.linkUrl, Uri.parse('/login'));
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue); // 스크린리더용 누르기 동작이 있다
    handle.dispose();
  });

  // 안내 문구(Notice) 안의 링크가 옆 문구와 한 노드로 합쳐지지 않고 따로 링크로 잡히는지 (TalkBack이 문장 전체를 링크로 읽는 것 방지)
  testWidgets('안내(Notice) 안에서도 링크는 옆 문구와 따로 label "로그인"인 링크 노드다', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp.router(
        routerConfig: _router(
          const ReloginNotice(), // 실제 안내 위젯(Notice 안에 문구 + 로그인 링크)
          _PushCounter(),
        ),
      ),
    );
    final node = tester.getSemantics(find.byType(InkWell));
    expect(node.label, '로그인'); // 문구가 섞이지 않았다
    expect(node.flagsCollection.isLink, isTrue);
    handle.dispose();
  });

  testWidgets('눌러서 path로 정확히 한 번 이동한다', (tester) async {
    final observer = _PushCounter();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(link, observer)));
    final before = observer.pushes;
    await tester.tap(find.text('로그인'));
    await tester.pumpAndSettle();
    expect(find.text('로그인 화면'), findsOneWidget);
    expect(observer.pushes - before, 1);
  });

  testWidgets('스크린리더의 누르기 동작으로도 정확히 한 번 이동한다', (tester) async {
    final handle = tester.ensureSemantics();
    final observer = _PushCounter();
    await tester.pumpWidget(MaterialApp.router(routerConfig: _router(link, observer)));
    final before = observer.pushes;
    tester.semantics.tap(find.semantics.byLabel(RegExp('로그인')));
    await tester.pumpAndSettle();
    expect(find.text('로그인 화면'), findsOneWidget);
    expect(observer.pushes - before, 1);
    handle.dispose();
  });

  testWidgets('tall이면 눌림 높이 48 이상, 아니면 48 미만', (tester) async {
    Future<double> heightOf(bool tall) async {
      await tester.pumpWidget(
        MaterialApp(home: Scaffold(body: Center(child: AppLink(label: '링크', path: '/', tall: tall)))),
      );
      return tester.getSize(find.byType(AppLink)).height;
    }

    expect(await heightOf(true), greaterThanOrEqualTo(48));
    expect(await heightOf(false), lessThan(48));
  });

  group('AppLink.external', () {
    late _FakeOpener opener;
    late ExternalLinkOpener original;

    setUp(() {
      original = externalLinkOpener;
      opener = _FakeOpener();
      externalLinkOpener = opener;
    });
    tearDown(() => externalLinkOpener = original);

    testWidgets('누르면 열기 도구가 그 주소로 정확히 한 번 불린다', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: Center(child: AppLink.external(label: '하이코리아', url: 'https://example.com/')))),
      );
      await tester.tap(find.text('하이코리아'));
      await tester.pump();
      expect(opener.opened, ['https://example.com/']);
    });

    // 앱 경로: 스크린리더에 링크로 읽히고(isLink·linkUrl·label), 누르기 동작도 있다
    testWidgets('스크린리더에는 label·바깥 주소가 붙은 링크 노드 하나이고, 누르기 동작으로도 열기 도구가 한 번 불린다', (tester) async {
      final handle = tester.ensureSemantics();
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: Center(child: AppLink.external(label: '하이코리아', url: 'https://example.com/')))),
      );
      expect(find.semantics.byLabel(RegExp('하이코리아')), findsOneWidget);
      final node = tester.getSemantics(find.byType(InkWell));
      expect(node.label, '하이코리아');
      expect(node.flagsCollection.isLink, isTrue);
      expect(node.linkUrl, Uri.parse('https://example.com/'));
      tester.semantics.tap(find.semantics.byLabel(RegExp('하이코리아')));
      await tester.pump();
      expect(opener.opened, ['https://example.com/']);
      handle.dispose();
    });
  });
}
