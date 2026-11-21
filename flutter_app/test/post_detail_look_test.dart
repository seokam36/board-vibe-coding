// S26-e 글 보기·댓글 새 모양 테스트: 글 머리(이니셜·닉네임·시각), 제목·본문 글자, 공감/관리 줄 배치,
// 신고 양식 폭, 댓글 줄, 남의 댓글의 조용한 [신고], 375px 넘침을 본다. 기능 동작은 post_detail_page_test 등이 본다.
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/small_button_style.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

const _seoul = {'id': 1, 'name': '서울대'};

Map<String, dynamic> _comment(int id, String content, {int? authorId, String nick = '댓글러'}) => {
  'id': id,
  'postId': 7,
  'content': content,
  'createdAt': '2020-11-08T07:02:00Z',
  'author': authorId == null ? null : {'id': authorId, 'nickname': '$nick$id'},
};

FakeAdapter _adapter({Map<String, dynamic>? post, List<Map<String, dynamic>>? comments}) => FakeAdapter()
  ..on('GET', '/posts/7', FakeReply(200, post ?? postJson()))
  ..on('GET', '/posts/7/comments', FakeReply(200, comments ?? []))
  ..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}))
  ..on('GET', '/report-reasons', FakeReply(200, [{'code': 'ABUSE', 'name': '욕설'}]));

// 회원 id 5(서울대). 글쓴이(id 1)·댓글 1번(id 5, 내 댓글)이 아니면 남의 것
Future<void> _open(WidgetTester tester, FakeAdapter adapter,
    {double width = 1200, int userId = 5, String role = 'USER'}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 2400);
  addTearDown(tester.view.reset);
  await pumpApp(tester, location: '/posts/7', user: userJson(id: userId, role: role, school: _seoul), adapter: adapter);
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
}

TextStyle _style(WidgetTester tester, String text) => tester.widget<Text>(find.text(text)).style!;

void main() {
  testWidgets('글 머리: 이니셜 + 닉네임(굵게 ink) + 시각(muted)', (tester) async {
    await _open(tester, _adapter());
    expect(find.descendant(of: find.byType(InitialAvatar), matching: find.text('홍')), findsOneWidget);
    final nick = _style(tester, '홍길동');
    expect(nick.fontWeight, FontWeight.w700);
    expect(nick.color, AppColors.ink);
    final date = tester.widget<Text>(find.textContaining('2020').first).style!;
    expect(date.color, AppColors.muted);
  });

  testWidgets('제목은 22·700·heading, 본문 줄 간격은 1.65', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester, _adapter());
    final title = _style(tester, '제목입니다');
    expect(title.fontSize, AppSizes.fsTitle);
    expect(title.fontWeight, FontWeight.w700);
    expect(tester.getSemantics(find.text('제목입니다')).flagsCollection.isHeader, isTrue);
    expect(_style(tester, '본문입니다').height, 1.65);
    handle.dispose();
  });

  testWidgets('1200px: 공감은 왼쪽, 신고(관리)는 오른쪽 끝', (tester) async {
    await _open(tester, _adapter());
    final like = tester.getRect(find.widgetWithText(OutlinedButton, '공감 3'));
    final report = tester.getRect(find.widgetWithText(OutlinedButton, '신고'));
    expect(like.left, lessThan(report.left));
    expect(report.center.dy, closeTo(like.center.dy, 2)); // 같은 줄
    final panel = tester.getRect(find.byType(Panel).first); // 글 판의 가로 범위와 같은 열
    expect(like.left, closeTo(panel.left, 2)); // 공감은 왼쪽 끝
    expect(report.right, closeTo(panel.right, 2)); // 신고는 오른쪽 끝
  });

  testWidgets('신고 양식을 열면 공감 줄 아래 한 줄 전체 폭이다', (tester) async {
    await _open(tester, _adapter());
    await tester.tap(find.widgetWithText(OutlinedButton, '신고'));
    await tester.pumpAndSettle();
    final box = tester.getRect(find.byKey(const ValueKey('report-detail')));
    final like = tester.getRect(find.widgetWithText(OutlinedButton, '공감 3'));
    final panel = tester.getRect(find.byType(Panel).first);
    expect(box.top, greaterThan(like.bottom)); // 공감 줄 아래로 내려온다
    // 양식 칸은 양식 상자의 안쪽 여백(12)만큼만 좁다 = 상자가 판 폭 전체를 쓴다
    expect(box.width, closeTo(panel.width - 2 * AppSizes.space3, 4)); // 한 줄 전체 폭
    expect(tester.takeException(), isNull);
  });

  testWidgets('글 본문 작업 줄의 [신고]는 전과 같은 테두리 있는 작은 버튼', (tester) async {
    await _open(tester, _adapter());
    final b = tester.widget<OutlinedButton>(find.widgetWithText(OutlinedButton, '신고'));
    expect(identical(b.style, smallButtonStyle), isTrue);
  });

  testWidgets('댓글 줄: 이니셜 + 닉네임 굵게 ink + 시각 muted', (tester) async {
    await _open(tester, _adapter(comments: [_comment(2, '답글이에요', authorId: 6)]));
    final row = find.byKey(const ValueKey('comment-2'));
    expect(find.descendant(of: row, matching: find.byType(InitialAvatar)), findsOneWidget);
    final nick = tester.widget<Text>(find.descendant(of: row, matching: find.text('댓글러2'))).style!;
    expect(nick.fontWeight, FontWeight.w700);
    expect(nick.color, AppColors.ink);
    final date = tester.widget<Text>(find.descendant(of: row, matching: find.textContaining('2020'))).style!;
    expect(date.color, AppColors.muted);
  });

  testWidgets('남의 댓글 [신고]는 테두리 없는 OutlinedButton, 높이 36 이상', (tester) async {
    await _open(tester, _adapter(comments: [_comment(2, '답글이에요', authorId: 6)]));
    final finder = find.descendant(
      of: find.byKey(const ValueKey('comment-2')),
      matching: find.widgetWithText(OutlinedButton, '신고'),
    );
    final b = tester.widget<OutlinedButton>(finder);
    expect(b.style!.side!.resolve({}), BorderSide.none);
    expect(b.style!.minimumSize!.resolve({})!.height, greaterThanOrEqualTo(36));
    expect(tester.getSize(finder).height, greaterThanOrEqualTo(36));
  });

  testWidgets('작성자 없는 글·댓글은 "(작성자 없음)"과 빈 이니셜 칸', (tester) async {
    await _open(tester, _adapter(post: postJson(author: null), comments: [_comment(3, '탈퇴자 댓글')]));
    expect(find.text('(작성자 없음)'), findsNWidgets(2));
    expect(find.byType(InitialAvatar), findsNWidgets(2));
    for (final t in tester.widgetList<Text>(find.descendant(of: find.byType(InitialAvatar), matching: find.byType(Text)))) {
      expect(t.data, ''); // 글자 없는 빈 칸
    }
  });

  testWidgets('375px: 긴 닉네임·긴 제목·이모지·열린 신고 양식도 넘치지 않는다', (tester) async {
    final longNick = '아주아주긴닉네임' * 6;
    await _open(
      tester,
      _adapter(
        post: postJson(
          title: '아주 긴 제목 ${'가나다라마바사 ' * 12}😀',
          author: {'id': 1, 'nickname': longNick},
        ),
        comments: [_comment(2, '😀' * 80, authorId: 6, nick: longNick)],
      ),
      width: 375,
    );
    expect(tester.takeException(), isNull);
    await tester.tap(find.widgetWithText(OutlinedButton, '신고').first);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('댓글 안 조용한 신고 양식은 바깥 테두리가 없고 375px에서도 넘치지 않는다', (tester) async {
    await _open(tester, _adapter(comments: [_comment(2, '답글', authorId: 6)]), width: 375);
    final row = find.byKey(const ValueKey('comment-2'));
    await tester.tap(find.descendant(of: row, matching: find.widgetWithText(OutlinedButton, '신고')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    // 기본 모양의 양식 상자(테두리 + 안쪽 여백 12)가 댓글 줄 안에 없어야 한다
    final bordered = find.descendant(
      of: row,
      matching: find.byWidgetPredicate((w) => w is Container &&
            w.padding == const EdgeInsets.all(AppSizes.space3) &&
            w.decoration is BoxDecoration &&
            (w.decoration as BoxDecoration).border != null),
    );
    expect(bordered, findsNothing);
  });

  testWidgets('내 글: [수정][삭제]는 오른쪽 끝, 신고는 없다', (tester) async {
    await _open(tester, _adapter(), userId: 1);
    final panel = tester.getRect(find.byType(Panel).first);
    expect(tester.getRect(find.widgetWithText(OutlinedButton, '삭제')).right, closeTo(panel.right, 2));
    expect(find.widgetWithText(OutlinedButton, '수정'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '신고'), findsNothing);
  });

  testWidgets('관리자가 남의 글에서 신고 양식을 열어도 [삭제]는 오른쪽 끝에 남는다', (tester) async {
    await _open(tester, _adapter(), role: 'ADMIN');
    final panel = tester.getRect(find.byType(Panel).first);
    await tester.tap(find.widgetWithText(OutlinedButton, '신고'));
    await tester.pumpAndSettle();
    expect(tester.getRect(find.widgetWithText(OutlinedButton, '삭제')).right, closeTo(panel.right, 2));
  });

  testWidgets('이니셜 동그라미는 스크린리더에서 빠진다', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester, _adapter());
    expect(find.bySemanticsLabel('홍'), findsNothing);
    handle.dispose();
  });
}
