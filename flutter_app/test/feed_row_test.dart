// FeedRow 단위 테스트: 링크 의미·누르기·높이·위 선을 확인한다.
import 'package:board_web/widgets/feed_row.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

Widget _row({required bool first, VoidCallback? onTap}) => FeedRow(
      label: '제목, 닉네임',
      onTap: onTap ?? () {},
      first: first,
      child: const Text('제목'),
    );

void main() {
  testWidgets('줄을 누르면 onTap이 불린다', (tester) async {
    var tapped = 0;
    await tester.pumpWidget(_wrap(_row(first: true, onTap: () => tapped++)));
    await tester.tap(find.text('제목'));
    expect(tapped, 1);
  });

  testWidgets('스크린리더에는 label이 붙은 링크 하나로 읽히고 누를 수 있다', (tester) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(_wrap(_row(first: true)));
    final node = tester.getSemantics(find.byType(InkWell));
    expect(node.label, '제목, 닉네임');
    expect(node.flagsCollection.isLink, isTrue);
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
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
