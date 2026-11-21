// S26-d 이니셜 동그라미(InitialAvatar) 테스트: 첫 글자 뽑기, 색, 스크린리더 제외를 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

// 동그라미 하나만 띄우는 도우미
Future<void> _pump(WidgetTester tester, String? nickname) =>
    tester.pumpWidget(MaterialApp(home: Scaffold(body: InitialAvatar(nickname: nickname))));

// 안쪽 글자
String _letter(WidgetTester tester) => tester.widget<Text>(find.byType(Text)).data!;

// 동그라미의 바탕색
Color? _bg(WidgetTester tester) {
  final box = tester.widget<Container>(find.descendant(of: find.byType(InitialAvatar), matching: find.byType(Container)));
  return (box.decoration as BoxDecoration).color;
}

void main() {
  testWidgets('한글 닉네임은 첫 글자', (tester) async {
    await _pump(tester, '철수');
    expect(_letter(tester), '철');
  });

  testWidgets('영문은 바꾸지 않고 그대로 (소문자는 소문자)', (tester) async {
    await _pump(tester, 'kim');
    expect(_letter(tester), 'k');
  });

  testWidgets('이모지는 반쪽으로 잘리지 않고 한 글자 전체', (tester) async {
    await _pump(tester, '😀웃음');
    expect(_letter(tester), '😀');
  });

  testWidgets('앞 공백은 건너뛴다', (tester) async {
    await _pump(tester, '  영희');
    expect(_letter(tester), '영');
  });

  testWidgets('글자가 있으면 연노랑 바탕 + 진한 갈색 글자', (tester) async {
    await _pump(tester, '철수');
    expect(_bg(tester), AppColors.highlightSoft);
    expect(tester.widget<Text>(find.byType(Text)).style?.color, AppColors.highlightInk);
  });

  testWidgets('빈 닉네임·공백뿐인 닉네임·작성자 없음(null)은 글자 없는 hover 회색 칸', (tester) async {
    for (final name in <String?>['', '   ', null]) {
      await _pump(tester, name);
      expect(_letter(tester), '', reason: '닉네임 $name');
      expect(_bg(tester), AppColors.hover, reason: '닉네임 $name');
    }
  });

  testWidgets('크기 기본 36 동그라미, 스크린리더에서는 제외', (tester) async {
    await _pump(tester, '철수');
    expect(tester.getSize(find.byType(InitialAvatar)), const Size(36, 36));
    expect(find.descendant(of: find.byType(InitialAvatar), matching: find.byType(ExcludeSemantics)), findsWidgets);
  });
}
