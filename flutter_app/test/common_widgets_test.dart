// S16-d2a 공용 부품 테스트: Notice(종류별 시맨틱·색), Loading(문구·prefix), ReloginNotice(링크)를 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/loading.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

// 부품 하나만 띄우는 도우미
Future<void> _pump(WidgetTester tester, Widget child) =>
    tester.pumpWidget(MaterialApp(home: Scaffold(body: child)));

void main() {
  group('Notice', () {
    testWidgets('error: 경고(alert) 역할, 위험색', (tester) async {
      final handle = tester.ensureSemantics();
      await _pump(tester, const Notice(kind: NoticeKind.error, message: '실패했어요'));
      final data = tester.getSemantics(find.byType(Notice)).getSemanticsData();
      expect(data.role, SemanticsRole.alert); // alert 자체가 바로 읽어 주는 영역(liveRegion과는 함께 못 씀)
      expect(find.text('실패했어요'), findsOneWidget);
      final box = tester.widget<Container>(find.descendant(of: find.byType(Notice), matching: find.byType(Container)));
      expect((box.decoration as BoxDecoration).color, AppColors.dangerSoft);
      expect(tester.widget<DefaultTextStyle>(find.descendant(of: find.byType(Notice), matching: find.byType(DefaultTextStyle)).last).style.color, AppColors.danger);
      handle.dispose();
    });

    testWidgets('info: 상태(status), 일반 안내색', (tester) async {
      final handle = tester.ensureSemantics();
      await _pump(tester, const Notice(kind: NoticeKind.info, message: '안내'));
      final data = tester.getSemantics(find.byType(Notice)).getSemanticsData();
      expect(data.role, SemanticsRole.status);
      final box = tester.widget<Container>(find.descendant(of: find.byType(Notice), matching: find.byType(Container)));
      expect((box.decoration as BoxDecoration).color, AppColors.infoSoft);
      handle.dispose();
    });

    testWidgets('success: 상태(status), 청자 배경', (tester) async {
      final handle = tester.ensureSemantics();
      await _pump(tester, const Notice(kind: NoticeKind.success, message: '완료'));
      final data = tester.getSemantics(find.byType(Notice)).getSemanticsData();
      expect(data.role, SemanticsRole.status);
      final box = tester.widget<Container>(find.descendant(of: find.byType(Notice), matching: find.byType(Container)));
      expect((box.decoration as BoxDecoration).color, AppColors.celadonSoft);
      handle.dispose();
    });

    testWidgets('child를 주면 그 위젯이 그대로 들어간다', (tester) async {
      await _pump(tester, const Notice(kind: NoticeKind.info, child: Text('위젯 안내')));
      expect(find.text('위젯 안내'), findsOneWidget);
    });
  });

  group('Loading', () {
    testWidgets('기본 문구는 "불러오는 중..."', (tester) async {
      await _pump(tester, const Loading());
      expect(find.text('불러오는 중...'), findsOneWidget);
    });

    testWidgets('prefix를 주면 "댓글 불러오는 중..."', (tester) async {
      await _pump(tester, const Loading(prefix: '댓글'));
      expect(find.text('댓글 불러오는 중...'), findsOneWidget);
      expect(find.text('불러오는 중...'), findsNothing);
    });
  });

  group('ReloginNotice', () {
    testWidgets('"다시 로그인해 주세요" 문구와 [로그인] 링크, 누르면 /login', (tester) async {
      final router = GoRouter(
        routes: [
          GoRoute(path: '/', builder: (_, _) => const Scaffold(body: ReloginNotice())),
          GoRoute(path: '/login', builder: (_, _) => const Scaffold(body: Text('로그인 화면'))),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pumpAndSettle();

      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('로그인'), findsOneWidget);
      await tester.tap(find.text('로그인'));
      await tester.pumpAndSettle();
      expect(find.text('로그인 화면'), findsOneWidget);
    });
  });
}
