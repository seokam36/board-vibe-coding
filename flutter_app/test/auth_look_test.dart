// S26-k6 로그인·회원가입·404 모양 테스트: 제목 22·700·heading 1, 카드 폭·가운데·좌우 여백, 닉네임 안내 13·muted, 홈으로 눌림 48.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:board_web/widgets/section_parts.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/loaded_auth.dart';

const _nickHint = '한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가';

// 화면 폭을 정하고(무엇을) 화면을 띄운다(왜: 폭에 따른 카드 위치를 보려고). pumpApp은 한 테스트에서 한 번만 부른다.
Future<TestApp> _open(WidgetTester tester, String path, double width) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 1600);
  addTearDown(tester.view.reset);
  return pumpApp(tester, location: path);
}

// 카드 = 제목 글자를 품은 판(Panel). 22 크기 제목만 골라 위 막대·탭의 같은 글자와 섞이지 않게 한다
Finder _card(String title) => find.ancestor(
  of: find.byWidgetPredicate((w) => w is Text && w.data == title && w.style?.fontSize == AppSizes.fsTitle),
  matching: find.byType(Panel),
);

void main() {
  for (final (path, title) in [('/login', '로그인'), ('/signup', '회원가입')]) {
    // 위 막대·아래 탭에도 같은 글자가 있어서 크기 22로 제목만 찾는다
    Finder titleFinder() => find.byWidgetPredicate(
      (w) => w is Text && w.data == title && w.style?.fontSize == AppSizes.fsTitle,
    );

    testWidgets('$path 제목 "$title"은 22·굵게·heading 1 이다', (tester) async {
      final semantics = tester.ensureSemantics();
      await _open(tester, path, 800);
      final text = tester.widget<Text>(titleFinder());
      expect(text.style?.fontWeight, FontWeight.w700);
      // 제목은 공용 PageTitle 하나가 그린다
      expect(find.ancestor(of: titleFinder(), matching: find.byType(PageTitle)), findsOneWidget);
      final data = tester.getSemantics(titleFinder()).getSemanticsData();
      expect(data.flagsCollection.isHeader, isTrue);
      expect(data.headingLevel, 1);
      semantics.dispose();
    });

    testWidgets('$path 카드 폭은 400 이하이고 1200폭에서 가로 가운데다', (tester) async {
      await _open(tester, path, 1200);
      final card = _card(title);
      final rect = tester.getRect(card);
      expect(rect.width, lessThanOrEqualTo(400));
      expect(rect.center.dx, closeTo(600, 0.5));
    });

    testWidgets('$path 375폭에서 카드 좌우 여백은 16이다', (tester) async {
      await _open(tester, path, 375);
      final rect = tester.getRect(_card(title));
      expect(rect.left, 16);
      expect(375 - rect.right, 16);
    });
  }

  testWidgets('/signup 닉네임 안내는 13·muted이고 문구 그대로다', (tester) async {
    await _open(tester, '/signup', 800);
    final hint = tester.widget<Text>(find.text(_nickHint));
    expect(hint.style?.fontSize, AppSizes.fsSmall);
    expect(hint.style?.fontSize, 13);
    expect(hint.style?.color, AppColors.muted);
    // 안내는 공용 FieldHint 안에 있다
    expect(find.ancestor(of: find.text(_nickHint), matching: find.byType(FieldHint)), findsOneWidget);
  });

  testWidgets('/signup 닉네임 안내는 닉네임 칸 아래 4, 비밀번호 칸은 안내 아래 16이다', (tester) async {
    await _open(tester, '/signup', 800);
    Finder field(String label) =>
        find.byWidgetPredicate((w) => w is TextField && w.decoration?.labelText == label);
    final hint = tester.getRect(find.text(_nickHint));
    expect(hint.top - tester.getRect(field('닉네임')).bottom, AppSizes.space1);
    expect(tester.getRect(field('비밀번호')).top - hint.bottom, AppSizes.space4);
  });

  testWidgets('404 제목은 heading 1이고 "홈으로" 눌림은 48 이상이며 누르면 /', (tester) async {
    final semantics = tester.ensureSemantics();
    final app = await _open(tester, '/nope', 800);
    final title = find.text('페이지를 찾을 수 없습니다');
    // 제목은 판(Panel) 안에 있고 22·굵게다
    expect(find.ancestor(of: title, matching: find.byType(Panel)), findsOneWidget);
    expect(tester.widget<Text>(title).style?.fontSize, AppSizes.fsTitle);
    expect(tester.widget<Text>(title).style?.fontWeight, FontWeight.w700);
    final data = tester.getSemantics(title).getSemanticsData();
    expect(data.flagsCollection.isHeader, isTrue);
    expect(data.headingLevel, 1);

    // 눌리는 영역 = 글자를 감싼 InkWell
    final tap = find.ancestor(of: find.text('홈으로'), matching: find.byType(InkWell)).first;
    expect(tester.getSize(tap).height, greaterThanOrEqualTo(AppSizes.tapMin));

    await tester.tap(find.text('홈으로'));
    await tester.pumpAndSettle();
    expect(app.router.routerDelegate.currentConfiguration.uri.path, '/');
    semantics.dispose();
  });
}
