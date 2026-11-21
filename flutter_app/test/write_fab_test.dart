// WriteFabLayout 단위 테스트: 버튼이 있을 때·없을 때의 모습을 확인한다.
import 'package:board_web/widgets/write_fab.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  testWidgets('onPressed가 null이면 연필 버튼 없이 본문만 보인다', (tester) async {
    await tester.pumpWidget(
      _wrap(const WriteFabLayout(tooltip: '글쓰기', child: Text('본문'))),
    );
    expect(find.text('본문'), findsOneWidget);
    expect(find.byType(FloatingActionButton), findsNothing);
  });

  testWidgets('onPressed가 null에서 생겨도 본문 위젯은 그대로(상태 유지)', (tester) async {
    final key = GlobalKey();
    Widget build(VoidCallback? p) => _wrap(
      WriteFabLayout(tooltip: '글쓰기', onPressed: p, child: SizedBox.expand(child: Text('본문', key: key))),
    );
    await tester.pumpWidget(build(null));
    final before = tester.element(find.byKey(key));
    await tester.pumpWidget(build(() {}));
    expect(tester.element(find.byKey(key)), same(before)); // 다시 만들어지지 않았다
    expect(find.byType(FloatingActionButton), findsOneWidget);
  });

  testWidgets('onPressed가 있으면 동그란 연필 버튼 1개, tooltip, 누르면 호출', (tester) async {
    var pressed = 0;
    await tester.pumpWidget(
      _wrap(
        WriteFabLayout(
          tooltip: '글쓰기',
          onPressed: () => pressed++,
          // 실제 화면의 PageFrame처럼 본문이 화면을 꽉 채워야 Stack이 커진다
          child: const SizedBox.expand(child: Text('본문')),
        ),
      ),
    );
    expect(find.text('본문'), findsOneWidget);
    final fab = tester.widget<FloatingActionButton>(
      find.byType(FloatingActionButton),
    );
    expect(fab.shape, isA<CircleBorder>());
    expect(fab.tooltip, '글쓰기');

    await tester.tap(find.byType(FloatingActionButton));
    expect(pressed, 1);
  });

  test('목록 아래 비움 자리는 88', () {
    expect(writeFabSpace, 88.0);
  });
}
