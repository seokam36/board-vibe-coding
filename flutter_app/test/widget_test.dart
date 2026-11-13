import 'package:board_web/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  testWidgets('화면이 뜨고 입력 칸·버튼이 보인다', (tester) async {
    await tester.pumpWidget(const ExperimentApp());

    // 입력 칸 라벨과 버튼이 있는지
    expect(find.text('제목'), findsOneWidget);
    expect(find.text('본문'), findsOneWidget);
    expect(find.text('확인'), findsOneWidget);
    // 확인을 누르기 전에는 결과가 없다
    expect(find.textContaining('입력한 제목'), findsNothing);
  });

  testWidgets('한글을 입력하고 확인을 누르면 그대로 화면에 보인다', (tester) async {
    await tester.pumpWidget(const ExperimentApp());

    // 첫 번째 TextField = 제목, 두 번째 = 본문
    await tester.enterText(find.byType(TextField).at(0), '안녕하세요 제목');
    await tester.enterText(find.byType(TextField).at(1), '여러 줄\n본문 테스트');
    await tester.tap(find.text('확인'));
    await tester.pump();

    expect(find.text('입력한 제목: 안녕하세요 제목'), findsOneWidget);
    expect(find.text('입력한 본문: 여러 줄\n본문 테스트'), findsOneWidget);
  });
}
