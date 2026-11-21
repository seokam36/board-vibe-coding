// S26-k3 비자 후기 보기 새 모양 테스트: 머리(이니셜·닉네임 15·700·뱃지·시각), 제목 22·700·heading,
// 접수/결과 줄, 메모 없음 문구, 관리 버튼 줄, 375·320px 넘침을 본다. 기능 동작은 visa_review_detail_page_test가 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';

// 정한 폭으로 후기 보기를 연다 (user가 null이면 비로그인)
Future<void> _open(
  WidgetTester tester,
  Map<String, dynamic> review, {
  double width = 375,
  Map<String, dynamic>? user,
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 2000);
  addTearDown(tester.view.reset);
  final adapter = FakeAdapter()..on('GET', '/visa-reviews/7', FakeReply(200, review));
  await pumpApp(tester, location: '/visa-reviews/7', user: user, adapter: adapter);
  await tester.pumpAndSettle();
}

TextStyle _style(WidgetTester tester, String text) => tester.widget<Text>(find.text(text)).style!;

void main() {
  testWidgets('머리: 이니셜 동그라미 1개 + 닉네임(15·700·ink)', (tester) async {
    await _open(tester, reviewJson(author: {'id': 2, 'nickname': '김철수'}));
    expect(find.byType(InitialAvatar), findsOneWidget);
    expect(find.descendant(of: find.byType(InitialAvatar), matching: find.text('김')), findsOneWidget);
    final nick = _style(tester, '김철수');
    expect(nick.fontSize, AppSizes.fsBody);
    expect(nick.fontWeight, FontWeight.w700);
    expect(nick.color, AppColors.ink);
  });

  testWidgets('작성자가 없으면 빈 동그라미 + "(작성자 없음)"', (tester) async {
    await _open(tester, reviewJson(author: null));
    expect(tester.widget<InitialAvatar>(find.byType(InitialAvatar)).initial, '');
    expect(find.text('(작성자 없음)'), findsOneWidget);
  });

  testWidgets('머리 순서: 닉네임 → 뱃지는 옆, 시각은 아래, 그 아래 제목', (tester) async {
    await _open(tester, reviewJson());
    final nick = tester.getRect(find.text('홍길동'));
    final badge = tester.getRect(find.text('승인'));
    final time = tester.getRect(find.textContaining('2020'));
    final title = tester.getRect(find.text('유학(D-2) · 연장'));
    expect(badge.left, greaterThan(nick.right));
    expect(time.top, greaterThanOrEqualTo(nick.bottom - 1));
    expect(title.top, greaterThan(time.bottom - 1));
    expect(tester.widget<Text>(find.textContaining('2020')).style!.color, AppColors.muted);
  });

  testWidgets('제목은 22·700·heading 1, 접수/결과 줄은 그대로', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester, reviewJson());
    final title = _style(tester, '유학(D-2) · 연장');
    expect(title.fontSize, AppSizes.fsTitle);
    expect(title.fontWeight, FontWeight.w700);
    expect(tester.getSemantics(find.text('유학(D-2) · 연장')).flagsCollection.isHeader, isTrue);
    expect(find.text('접수 2026년 9월 1일 → 결과 2026년 9월 20일 (19일)'), findsOneWidget);
    handle.dispose();
  });

  testWidgets('메모가 없으면 "남긴 메모가 없어요"가 muted 색', (tester) async {
    await _open(tester, reviewJson(content: ''));
    expect(_style(tester, '남긴 메모가 없어요').color, AppColors.muted);
  });

  testWidgets('내 후기의 [수정]·[삭제]는 오른쪽 끝에 놓인다', (tester) async {
    await _open(tester, reviewJson(), user: userJson(id: 1, school: visaSeoul), width: 800);
    final delete = tester.getRect(find.widgetWithText(OutlinedButton, '삭제'));
    final edit = tester.getRect(find.widgetWithText(OutlinedButton, '수정'));
    expect(edit.right, lessThan(delete.left));
    // 판(폭 800 - 바깥 여백) 오른쪽 가까이에 붙는다
    expect(delete.right, greaterThan(800 - 60));
  });

  for (final width in [375.0, 320.0]) {
    testWidgets('${width.toInt()}px: 20자 닉네임 + 긴 학교 뱃지도 넘치지 않는다', (tester) async {
      const nick = '가나다라마바사아자차카타파하가나다라마바';
      await _open(
        tester,
        reviewJson(author: {'id': 2, 'nickname': nick}, school: {'id': 3, 'name': '아주 긴 대학교 이름 대학교'}),
        width: width,
        user: userJson(id: 1, role: 'ADMIN', school: visaSeoul),
      );
      expect(tester.takeException(), isNull);
      expect(tester.getRect(find.text(nick)).right, lessThanOrEqualTo(width - 16));
      expect(tester.getRect(find.text('아주 긴 대학교 이름 대학교')).right, lessThanOrEqualTo(width - 16));
    });
  }
}
