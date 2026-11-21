// S26-k8 관리자 화면 모양 테스트: 신고된 대화 말풍선이 흰 판 안에 있는지,
// 신고 관리 링크의 눌림 높이가 48 이상인지, 인증 심사 줄 닉네임이 굵은(700) 글씨인지 확인한다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

void main() {
  testWidgets('신고된 대화: 모든 말풍선이 흰 판(Panel) 안에 있고 색·85%·좌우는 그대로다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('GET', '/admin/reports/7/conversation', FakeReply(200, adminConversationJson()));
    await pumpApp(tester, location: '/admin/reports/7/conversation', user: adminUser(), adapter: adapter);

    // 말풍선 조상에 Panel이 있다
    for (final id in [30, 31]) {
      expect(find.ancestor(of: find.byKey(ValueKey('msg-bubble-$id')), matching: find.byType(Panel)), findsWidgets);
    }
    // 방향·색은 그대로: 판매자(31)는 오른쪽 포인트색, 구매자(30)는 왼쪽 hover색
    expect(tester.widget<Align>(find.byKey(const ValueKey('msg-31'))).alignment, Alignment.centerRight);
    expect(tester.widget<Align>(find.byKey(const ValueKey('msg-30'))).alignment, Alignment.centerLeft);
    Color? color(int id) => (tester.widget<Container>(find.byKey(ValueKey('msg-bubble-$id'))).decoration as BoxDecoration).color;
    expect(color(31), AppColors.accentSoft);
    expect(color(30), AppColors.hover);
    // 폭은 판 안쪽 폭의 85% 이하 (말풍선 칸의 최대 폭 제약)
    final listBox = tester.getSize(find.bySemanticsLabel('쪽지 목록'));
    final bubbleWidth = tester.getSize(find.byKey(const ValueKey('msg-bubble-30'))).width;
    expect(bubbleWidth, lessThanOrEqualTo(listBox.width * 0.85 + 0.5));
  });

  testWidgets('신고된 대화: 긴 쪽지의 말풍선 폭은 판 안쪽 폭의 80~85%다', (tester) async {
    useTallScreen(tester);
    final longText = '길게 쓴 쪽지입니다 ' * 20; // 200자쯤
    final adapter = FakeAdapter()
      ..on('GET', '/admin/reports/7/conversation', FakeReply(200, adminConversationJson(messages: [adminMessageJson(id: 30, content: longText)])));
    await pumpApp(tester, location: '/admin/reports/7/conversation', user: adminUser(), adapter: adapter);

    final listWidth = tester.getSize(find.bySemanticsLabel('쪽지 목록')).width;
    final bubbleWidth = tester.getSize(find.byKey(const ValueKey('msg-bubble-30'))).width;
    expect(bubbleWidth, lessThanOrEqualTo(listWidth * 0.85 + 0.5));
    expect(bubbleWidth, greaterThanOrEqualTo(listWidth * 0.8)); // 긴 글이면 85% 근처까지 찬다
  });

  testWidgets('신고된 대화: 쪽지가 0통이면 머리 판 하나뿐이고 쪽지 목록 라벨은 없다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', '/admin/reports/7/conversation', FakeReply(200, adminConversationJson(messages: [])));
    await pumpApp(tester, location: '/admin/reports/7/conversation', user: adminUser(), adapter: adapter);

    expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
    expect(find.byType(Panel), findsOneWidget);
    expect(find.bySemanticsLabel('쪽지 목록'), findsNothing);
  });

  testWidgets('신고된 대화: hasMore 안내는 말풍선 판 안 맨 위에 있고 첫 말풍선과 떨어져 있다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', '/admin/reports/7/conversation', FakeReply(200, adminConversationJson(hasMore: true)));
    await pumpApp(tester, location: '/admin/reports/7/conversation', user: adminUser(), adapter: adapter);

    final notice = find.byKey(const ValueKey('msg-truncated'));
    // 안내의 조상 Panel 중 말풍선도 품은 판이 있다 (= 말풍선 판 안)
    final bubblePanel = find.ancestor(of: find.byKey(const ValueKey('msg-bubble-30')), matching: find.byType(Panel));
    expect(find.descendant(of: bubblePanel.first, matching: notice), findsOneWidget);
    // 안내 아래 끝 < 첫 말풍선 위 끝 (간격이 있다)
    final noticeBottom = tester.getBottomLeft(notice).dy;
    final bubbleTop = tester.getTopLeft(find.byKey(const ValueKey('msg-30'))).dy;
    expect(noticeBottom, lessThan(bubbleTop));
  });

  testWidgets('신고 관리: "글로 가기"·"대화 보기" 눌림 높이가 48 이상이고 주소는 그대로다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', '/admin/reports', FakeReply(200, [postReport(), conversationReport()]));
    final app = await pumpApp(tester, location: '/admin/reports', user: adminUser(), adapter: adapter);

    for (final label in ['글로 가기', '대화 보기']) {
      final link = find.ancestor(of: find.text(label), matching: find.byType(InkWell)).first;
      expect(tester.getSize(link).height, greaterThanOrEqualTo(AppSizes.tapMin), reason: label);
    }
    // 눌러서 가는 주소 확인
    await tester.tap(find.text('대화 보기'));
    await tester.pumpAndSettle();
    expect(app.router.routerDelegate.currentConfiguration.uri.path, '/admin/reports/7/conversation');
  });

  testWidgets('신고 관리: "글로 가기"를 누르면 /posts/5로 간다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()..on('GET', '/admin/reports', FakeReply(200, [postReport()]));
    final app = await pumpApp(tester, location: '/admin/reports', user: adminUser(), adapter: adapter);
    await tester.tap(find.text('글로 가기'));
    await tester.pumpAndSettle();
    expect(app.router.routerDelegate.currentConfiguration.uri.path, '/posts/5');
  });

  testWidgets('인증 심사: 줄의 닉네임 굵기는 w700이다', (tester) async {
    useTallScreen(tester);
    final adapter = FakeAdapter()
      ..on('GET', '/admin/permit-requests', FakeReply(200, [
        {
          'id': 1,
          'userId': 100,
          'nickname': '민수',
          'status': 'PENDING',
          'createdAt': '2026-11-01T03:04:05Z',
          'schoolName': null,
          'rejectReason': null,
          'detectedSchoolId': null,
          'detectedSchoolName': null,
          'machineResult': 'UNREADABLE',
          'autoDecided': false,
          'hasPhoto': true,
        },
      ]));
    await pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: adapter);
    final row = find.byKey(const ValueKey('permit-1'));
    final nick = find.descendant(of: row, matching: find.text('민수'));
    expect(tester.widget<Text>(nick).style?.fontWeight, FontWeight.w700);
  });
}
