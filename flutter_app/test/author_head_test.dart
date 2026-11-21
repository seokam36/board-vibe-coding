// S26-k1 AuthorHead(글 보기 작성자 머리) 부품 테스트: 이니셜·닉네임 15·700·시각 위치, 닉네임 없음, 긴 닉네임 + 뱃지 320폭 넘침 없음.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/widgets/author_head.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

// 부품 하나만 정한 폭으로 띄운다 (무엇을: 폭 고정 / 왜: 좁은 화면 넘침을 보려고)
Future<void> _pump(WidgetTester tester, Widget head, {double width = 375}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 800);
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: Padding(padding: const EdgeInsets.all(16), child: head))));
}

void main() {
  testWidgets('이니셜(36) + 닉네임(15·700·ink), 시각은 닉네임 아래(muted)', (tester) async {
    await _pump(tester, const AuthorHead(nickname: '길동이', time: '2020. 11. 8. 15:19'));
    final avatar = tester.widget<InitialAvatar>(find.byType(InitialAvatar));
    expect(avatar.size, 36);
    expect(find.descendant(of: find.byType(InitialAvatar), matching: find.text('길')), findsOneWidget);
    final nick = tester.widget<Text>(find.text('길동이')).style!;
    expect(nick.fontSize, AppSizes.fsBody);
    expect(nick.fontWeight, FontWeight.w700);
    expect(nick.color, AppColors.ink);
    final time = tester.widget<Text>(find.text('2020. 11. 8. 15:19')).style!;
    expect(time.color, AppColors.muted);
    expect(tester.getTopLeft(find.text('2020. 11. 8. 15:19')).dy, greaterThan(tester.getBottomLeft(find.text('길동이')).dy - 1));
  });

  testWidgets('닉네임이 null이면 빈 동그라미 + 대체 글자 (바꿔 줄 수도 있다)', (tester) async {
    await _pump(tester, const AuthorHead(nickname: null, time: '시각'));
    expect(tester.widget<InitialAvatar>(find.byType(InitialAvatar)).initial, '');
    expect(find.text('(작성자 없음)'), findsOneWidget);
    await _pump(tester, const AuthorHead(nickname: null, time: '시각', emptyName: '알 수 없음'));
    expect(find.text('알 수 없음'), findsOneWidget);
  });

  testWidgets('뱃지는 닉네임 옆에 놓인다', (tester) async {
    await _pump(tester, const AuthorHead(nickname: '길동이', time: '시각', badges: [AppBadge(text: '서울대', school: true)]));
    final nick = tester.getRect(find.text('길동이'));
    final badge = tester.getRect(find.text('서울대'));
    expect(badge.left, greaterThan(nick.right));
    expect(badge.center.dy, closeTo(nick.center.dy, 8));
  });

  for (final width in [375.0, 320.0]) {
    testWidgets('${width.toInt()}px: 20자 닉네임 + 뱃지 2개도 넘치지 않는다', (tester) async {
      await _pump(
        tester,
        const AuthorHead(
          nickname: '가나다라마바사아자차카타파하가나다라마바',
          time: '시각',
          badges: [AppBadge(text: '판매중'), AppBadge(text: '아주 긴 대학교 이름 대학교', school: true)],
        ),
        width: width,
      );
      expect(tester.takeException(), isNull);
      // 안쪽 글자(닉네임·긴 학교 뱃지 글자)가 화면 폭 - 바깥 여백(16) 안에 있다
      expect(tester.getRect(find.text('가나다라마바사아자차카타파하가나다라마바')).right, lessThanOrEqualTo(width - 16));
      expect(tester.getRect(find.text('아주 긴 대학교 이름 대학교')).right, lessThanOrEqualTo(width - 16));
    });
  }
}
