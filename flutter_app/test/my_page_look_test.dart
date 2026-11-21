// S26-k4 내 정보(/me) 새 모양 테스트: 프로필 판 이니셜, 바로가기 판(FeedPanel 안 FeedRow),
// 판 세로 순서, 판 사이 간격, 긴 닉네임 375·320 넘침 없음.
// 기능(닉네임 변경 요청·비밀번호 변경·탈퇴)은 my_page_test 등이 본다.
import 'package:board_web/widgets/feed_row.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/panel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

const _school = {'id': 3, 'name': '서울대'};

// 내 정보 화면을 정한 폭으로 연다
Future<TestApp> _open(
  WidgetTester tester, {
  Map<String, dynamic>? user,
  double width = 375,
  FakeAdapter? adapter,
}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 3000);
  addTearDown(tester.view.reset);
  return pumpApp(tester, location: '/me', user: user ?? userJson(), adapter: adapter);
}

// 화면 안 InitialAvatar의 글자
String _initialOf(WidgetTester tester) {
  final avatar = find.byType(InitialAvatar);
  return tester.widget<Text>(find.descendant(of: avatar, matching: find.byType(Text))).data!;
}

void main() {
  group('프로필 판', () {
    testWidgets('이니셜(36) 1개와 아이디·닉네임 문구가 그대로 있다', (tester) async {
      await _open(tester, user: userJson(username: 'user01', nickname: '홍길동'));
      expect(find.byType(InitialAvatar), findsOneWidget);
      expect(tester.widget<InitialAvatar>(find.byType(InitialAvatar)).size, 36);
      expect(tester.getSize(find.byType(InitialAvatar)), const Size(36, 36));
      expect(find.text('아이디: user01'), findsOneWidget);
      expect(find.text('닉네임: 홍길동'), findsOneWidget);
      expect(_initialOf(tester), '홍');
      // 이니셜은 문구 왼쪽에 있다
      expect(tester.getTopLeft(find.byType(InitialAvatar)).dx, lessThan(tester.getTopLeft(find.text('아이디: user01')).dx));
    });

    testWidgets('닉네임 변경에 성공하면 이니셜도 새 닉네임 첫 글자로 바뀐다', (tester) async {
      final adapter = FakeAdapter()..on('PUT', '/users/me/nickname', FakeReply(200, userJson(nickname: '철수')));
      await _open(tester, user: userJson(nickname: '홍길동'), adapter: adapter);
      expect(_initialOf(tester), '홍');
      await tester.enterText(find.widgetWithText(TextField, '새 닉네임'), '철수');
      await tester.tap(find.widgetWithText(FilledButton, '닉네임 변경'));
      await tester.pumpAndSettle();
      expect(find.text('닉네임: 철수'), findsOneWidget);
      expect(_initialOf(tester), '철');
    });
  });

  group('바로가기 판', () {
    testWidgets('관리자(미인증): FeedPanel 안 FeedRow 2줄, 글자 그대로, 줄 높이 48 이상', (tester) async {
      await _open(tester, user: userJson(role: 'ADMIN'));
      final panel = find.byType(FeedPanel);
      expect(panel, findsOneWidget);
      final rows = find.descendant(of: panel, matching: find.byType(FeedRow));
      expect(rows, findsNWidgets(2)); // 줄 하나 = 링크 하나
      for (final t in ['신고 관리', '인증 심사']) {
        expect(find.descendant(of: panel, matching: find.text(t)), findsOneWidget);
      }
      expect(find.text('비자 일정'), findsNothing);
      for (var i = 0; i < 2; i++) {
        expect(tester.getSize(rows.at(i)).height, greaterThanOrEqualTo(48));
      }
      // 오른쪽 chevron
      expect(find.descendant(of: panel, matching: find.byIcon(Icons.chevron_right)), findsNWidgets(2));
    });

    testWidgets('인증 회원: 줄 하나(비자 일정)뿐이고 누르면 /visa-plan 으로 간다', (tester) async {
      final app = await _open(tester, user: userJson(school: _school));
      expect(find.descendant(of: find.byType(FeedPanel), matching: find.byType(FeedRow)), findsOneWidget);
      await tester.tap(find.text('비자 일정'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
    });

    testWidgets('관리자: 신고 관리 → /admin/reports, 인증 심사 → /admin/permits', (tester) async {
      final app = await _open(tester, user: userJson(role: 'ADMIN'));
      // 신고 관리를 눌러 /admin/reports 로 간다
      await tester.tap(find.text('신고 관리'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/admin/reports');
      // 다시 내 정보로 돌아와 인증 심사를 눌러 /admin/permits 로 간다
      app.router.go('/me');
      await tester.pumpAndSettle();
      await tester.tap(find.text('인증 심사'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/admin/permits');
    });

    testWidgets('스크린리더: 줄은 링크이고 "비자 일정" 하나로 읽히며 chevron은 따로 읽히지 않는다', (tester) async {
      final handle = tester.ensureSemantics(); // 접근성 정보를 켠다
      await _open(tester, user: userJson(school: _school));
      final row = find.descendant(of: find.byType(FeedPanel), matching: find.byType(FeedRow));
      final node = tester.getSemantics(row);
      expect(node.label, '비자 일정'); // 글자·chevron이 섞이지 않고 라벨 하나
      expect(node.flagsCollection.isLink, isTrue);
      expect(find.bySemanticsLabel('비자 일정'), findsOneWidget);
      // chevron 아이콘은 읽을 말이 따로 없다
      expect(
        tester.getSemantics(find.descendant(of: row, matching: find.byIcon(Icons.chevron_right))).label,
        '비자 일정',
      ); // 줄 노드로 합쳐져 자기 라벨이 없다
      handle.dispose();
    });

    testWidgets('일반 미인증 회원: 바로가기 판이 아예 없다', (tester) async {
      await _open(tester);
      expect(find.byType(FeedPanel), findsNothing);
      expect(find.byType(FeedRow), findsNothing);
    });
  });

  group('판 순서·간격', () {
    testWidgets('프로필 → 바로가기 → 유학생 인증 → 닉네임 → 비밀번호 → 탈퇴 → 목록으로 순서로 세로로 놓인다', (tester) async {
      await _open(tester, user: userJson(role: 'ADMIN', school: _school));
      final ys = [
        tester.getTopLeft(find.text('아이디: user01')).dy,
        tester.getTopLeft(find.text('비자 일정')).dy,
        tester.getTopLeft(find.text('유학생 인증')).dy,
        tester.getTopLeft(find.widgetWithText(TextField, '새 닉네임')).dy,
        tester.getTopLeft(find.widgetWithText(TextField, '현재 비밀번호')).dy,
        tester.getTopLeft(find.widgetWithText(OutlinedButton, '회원 탈퇴')).dy,
        tester.getTopLeft(find.widgetWithText(OutlinedButton, '목록으로')).dy,
      ];
      for (var i = 1; i < ys.length; i++) {
        expect(ys[i], greaterThan(ys[i - 1]), reason: '$i번째 자리가 앞보다 아래여야 한다');
      }
    });

    testWidgets('프로필 판과 바로가기 판 사이는 16', (tester) async {
      await _open(tester, user: userJson(role: 'ADMIN'));
      final profile = find.ancestor(of: find.byType(InitialAvatar), matching: find.byType(Panel)).first;
      // 눈에 보이는 판 상자(테두리 있는 Container)의 아래 ~ 바로가기 판 위
      final box = find.descendant(of: profile, matching: find.byType(DecoratedBox)).first; // 바깥 여백은 이 상자 밖
      final gap = tester.getTopLeft(find.byType(FeedPanel)).dy - tester.getBottomLeft(box).dy;
      expect(gap, 16);
    });

    testWidgets('바로가기 판과 유학생 인증 판 사이는 16', (tester) async {
      await _open(tester, user: userJson(role: 'ADMIN'));
      final cert = find.ancestor(of: find.text('유학생 인증'), matching: find.byType(Panel)).first;
      final box = find.descendant(of: cert, matching: find.byType(DecoratedBox)).first; // 눈에 보이는 판 상자
      final gap = tester.getTopLeft(box).dy - tester.getBottomLeft(find.byType(FeedPanel)).dy;
      expect(gap, 16);
    });

    testWidgets('일반 회원(바로가기 0개): 프로필 판 아래 16 뒤에 바로 유학생 인증 판이 온다(빈 틈 없음)', (tester) async {
      await _open(tester);
      expect(find.byType(FeedPanel), findsNothing);
      final profile = find.ancestor(of: find.byType(InitialAvatar), matching: find.byType(Panel)).first;
      final profileBox = find.descendant(of: profile, matching: find.byType(DecoratedBox)).first;
      final cert = find.ancestor(of: find.text('유학생 인증'), matching: find.byType(Panel)).first;
      final certBox = find.descendant(of: cert, matching: find.byType(DecoratedBox)).first;
      final gap = tester.getTopLeft(certBox).dy - tester.getBottomLeft(profileBox).dy;
      expect(gap, 16);
    });
  });

  group('긴 닉네임 넘침 없음', () {
    for (final width in [375.0, 320.0]) {
      testWidgets('닉네임 20자, 폭 ${width.toInt()}: 넘침 오류가 없다', (tester) async {
        await _open(
          tester,
          width: width,
          user: userJson(nickname: '가나다라마바사아자차카타파하가나다라마바', role: 'ADMIN', school: _school),
        );
        expect(tester.takeException(), isNull);
        expect(find.byType(InitialAvatar), findsOneWidget);
      });
    }
  });
}
