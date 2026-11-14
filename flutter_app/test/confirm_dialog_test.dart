// S16-d2b 확인 창(showConfirm) 테스트: React ConfirmDialog와 같은 동작을 본다.
// 쓰는 화면이 아직 없어서, 앱을 띄운 뒤 화면의 context로 직접 창을 연다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/confirm_dialog.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/loaded_auth.dart';

// 열린 창의 답을 담는 상자. null이면 아직 답이 안 나온 것.
class _Answer {
  bool? value;
}

// 확인 창을 열고 답 상자를 돌려준다
Future<_Answer> _open(
  WidgetTester tester, {
  String message = '이 글을 삭제할까요?',
  String confirmLabel = '삭제',
  bool danger = false,
}) async {
  final answer = _Answer();
  final context = tester.element(find.byType(Scaffold).first);
  showConfirm(context, message: message, confirmLabel: confirmLabel, danger: danger)
      .then((v) => answer.value = v);
  await tester.pumpAndSettle();
  return answer;
}

final _cancel = find.widgetWithText(OutlinedButton, '취소');

void main() {
  testWidgets('제목 "확인" · 문구(줄바꿈 유지) · [취소] [확인 라벨] 버튼이 보인다', (tester) async {
    await pumpApp(tester);
    await _open(tester, message: '첫 줄\n둘째 줄', confirmLabel: '삭제하기');
    expect(find.text('확인'), findsOneWidget);
    expect(find.text('첫 줄\n둘째 줄'), findsOneWidget);
    expect(_cancel, findsOneWidget);
    expect(find.widgetWithText(FilledButton, '삭제하기'), findsOneWidget);
    // 취소가 확인 라벨보다 왼쪽
    expect(
      tester.getTopLeft(_cancel).dx < tester.getTopLeft(find.text('삭제하기')).dx,
      isTrue,
    );
  });

  testWidgets('모양: 폭 최대 400 · 안쪽 24 · 모서리 8 · 바탕 paper · 테두리 line · 제목 17px 700', (tester) async {
    await pumpApp(tester);
    await _open(tester);
    final dialog = tester.widget<Dialog>(find.byType(Dialog));
    expect(dialog.backgroundColor, AppColors.paper);
    final shape = dialog.shape! as RoundedRectangleBorder;
    expect(shape.borderRadius, BorderRadius.circular(8));
    expect(shape.side.color, AppColors.line);
    // Dialog 위젯 자체는 화면 전체 크기라, 실제 판(Material)의 폭을 잰다
    final panel = find.descendant(of: find.byType(Dialog), matching: find.byType(Material)).first;
    expect(tester.getSize(panel).width, 400);
    // 안쪽 여백 24: 판 왼쪽 끝과 제목 글자 왼쪽 끝의 간격
    expect(tester.getTopLeft(find.text('확인')).dx - tester.getTopLeft(panel).dx, 24); // 테두리는 판 가장자리에 겹쳐 그려져 두께만큼 밀리지 않는다
    final title = tester.widget<Text>(find.text('확인'));
    final style = DefaultTextStyle.of(tester.element(find.text('확인'))).style.merge(title.style);
    expect(style.fontSize, 17);
    expect(style.fontWeight, FontWeight.w700);
    // 뒤 막: 검정 60%
    final barrier = tester.widget<ModalBarrier>(find.byType(ModalBarrier).last);
    expect(barrier.color, const Color.fromRGBO(0, 0, 0, 0.6));
  });

  testWidgets('열릴 때 [취소]가 첫 포커스', (tester) async {
    await pumpApp(tester);
    await _open(tester);
    // 포커스를 가진 위젯이 [취소] 버튼 안에 있다
    final focusedContext = FocusManager.instance.primaryFocus!.context!;
    expect(
      find.ancestor(
        of: find.byElementPredicate((e) => e == focusedContext),
        matching: _cancel,
      ),
      findsOneWidget,
    );
  });

  testWidgets('[확인 라벨]을 누르면 true', (tester) async {
    await pumpApp(tester);
    final answer = await _open(tester);
    await tester.tap(find.widgetWithText(FilledButton, '삭제'));
    await tester.pumpAndSettle();
    expect(answer.value, isTrue);
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('[취소]를 누르면 false', (tester) async {
    await pumpApp(tester);
    final answer = await _open(tester);
    await tester.tap(_cancel);
    await tester.pumpAndSettle();
    expect(answer.value, isFalse);
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('Esc를 누르면 false', (tester) async {
    await pumpApp(tester);
    final answer = await _open(tester);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(answer.value, isFalse);
    expect(find.byType(Dialog), findsNothing);
  });

  testWidgets('바깥(뒤 막)을 눌러도 닫히지 않는다', (tester) async {
    await pumpApp(tester);
    final answer = await _open(tester);
    await tester.tapAt(const Offset(2, 2));
    await tester.pumpAndSettle();
    expect(find.byType(Dialog), findsOneWidget);
    expect(answer.value, isNull);
  });

  testWidgets('danger면 확인 버튼이 danger 색, 아니면 primary(채운 청자)', (tester) async {
    await pumpApp(tester);
    await _open(tester, danger: true);
    final dangerButton = tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '삭제'));
    expect(dangerButton.style!.foregroundColor!.resolve({}), AppColors.danger);
    expect(dangerButton.style!.side!.resolve({})!.color, AppColors.danger);
    expect(find.widgetWithText(FilledButton, '삭제'), findsNothing);
    await tester.tap(_cancel);
    await tester.pumpAndSettle();

    await _open(tester);
    expect(find.widgetWithText(FilledButton, '삭제'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '삭제'), findsNothing);
  });

  testWidgets('창이 열린 채 주소가 바뀌면 false로 닫힌다', (tester) async {
    final app = await pumpApp(tester);
    final answer = await _open(tester);
    app.router.go('/market');
    await tester.pumpAndSettle();
    expect(answer.value, isFalse);
    expect(find.byType(Dialog), findsNothing);
    expect(find.text('중고·나눔 목록'), findsOneWidget);
  });

  testWidgets('창을 다 닫은 뒤 주소가 바뀌어도 아무 일 없다 (이전 창이 남지 않는다)', (tester) async {
    final app = await pumpApp(tester);
    final first = await _open(tester);
    await tester.tap(_cancel);
    await tester.pumpAndSettle();
    app.router.go('/market');
    await tester.pumpAndSettle();
    expect(first.value, isFalse);
    expect(tester.takeException(), isNull);
    // 새 창은 정상으로 열린다
    final second = await _open(tester);
    await tester.tap(find.widgetWithText(FilledButton, '삭제'));
    await tester.pumpAndSettle();
    expect(second.value, isTrue);
  });

  testWidgets('이미 떠 있는데 또 열면 앞의 창은 false, 새 창만 남는다', (tester) async {
    await pumpApp(tester);
    final first = await _open(tester, message: '첫째 창');
    final second = await _open(tester, message: '둘째 창');
    expect(first.value, isFalse);
    expect(second.value, isNull);
    expect(find.text('첫째 창'), findsNothing);
    expect(find.text('둘째 창'), findsOneWidget);
    expect(find.byType(Dialog), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, '삭제'));
    await tester.pumpAndSettle();
    expect(second.value, isTrue);
  });
}
