// S26-e 보호 테스트: 댓글만 조용한 [신고]로 바꿨으므로, 중고 글 상세·쪽지 대화의 [신고]는
// 예전 그대로(테두리 있는 기본 모양, quiet 꺼짐)인지 지킨다.
import 'package:board_web/widgets/report_form.dart';
import 'package:board_web/widgets/small_button_style.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/message_json.dart';
import 'support/screen.dart';

void _expectDefaultLook(WidgetTester tester) {
  expect(tester.widget<ReportForm>(find.byType(ReportForm)).quiet, isFalse);
  final b = tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '신고'));
  expect(identical(b.style, smallButtonStyle), isTrue);
  expect(identical(b.style, quietSmallButtonStyle), isFalse);
}

void main() {
  testWidgets('중고 글 상세의 [신고]는 테두리 있는 기본 모양이다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson()));
    await pumpApp(tester, location: '/market/7', user: userJson(id: 5, school: marketSeoul), adapter: adapter);
    await tester.pumpAndSettle();
    _expectDefaultLook(tester);
  });

  testWidgets('쪽지 대화의 [신고]는 테두리 있는 기본 모양이다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', '/conversations/3', FakeReply(200, conversationJson()))
      ..on('PUT', '/conversations/3/read', FakeReply(204));
    await pumpApp(tester, location: '/messages/3', user: userJson(school: marketSeoul), adapter: adapter);
    await tester.pumpAndSettle();
    _expectDefaultLook(tester);
  });
}
