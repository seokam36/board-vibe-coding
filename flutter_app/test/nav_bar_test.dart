// S16-d2a 상단바 테스트: 메뉴 순서·조건(비로그인/일반/인증/관리자), 로그아웃 성공·실패, 좁은 화면 넘침을 본다.
import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';

// 글자 위치(왼쪽 x 좌표): 메뉴 순서를 좌표로 확인하려고 쓴다 (넓은 화면에서는 한 줄에 놓인다)
double _x(WidgetTester tester, String text) => tester.getTopLeft(find.text(text)).dx;

final _school = {'id': 3, 'name': '서울대'};

void main() {
  group('메뉴 구성', () {
    testWidgets('비로그인: 비자 후기 · 중고·나눔 · 로그인 · 회원가입 순서, 로그인 전용 메뉴는 없다', (tester) async {
      await pumpApp(tester);
      expect(find.text('게시판'), findsOneWidget); // 사이트 이름
      final order = ['비자 후기', '중고·나눔', '로그인', '회원가입'];
      for (final t in order) {
        expect(find.text(t), findsOneWidget);
      }
      final xs = [for (final t in order) _x(tester, t)];
      expect(xs, [...xs]..sort()); // 왼쪽부터 같은 순서
      for (final t in ['쪽지', '내 정보', '비자 일정', '신고 관리', '로그아웃', '인증']) {
        expect(find.text(t), findsNothing);
      }
    });

    testWidgets('로그인(일반·미인증): 닉네임 · 쪽지 · 내 정보 · 로그아웃, 인증 뱃지·비자 일정·신고 관리는 없다', (tester) async {
      await pumpApp(tester, user: userJson());
      final order = ['비자 후기', '중고·나눔', '홍길동 님', '쪽지', '내 정보', '로그아웃'];
      for (final t in order) {
        expect(find.text(t), findsOneWidget);
      }
      final xs = [for (final t in order) _x(tester, t)];
      expect(xs, [...xs]..sort());
      for (final t in ['로그인', '회원가입', '인증', '비자 일정', '신고 관리']) {
        expect(find.text(t), findsNothing);
      }
    });

    testWidgets('인증 회원: 인증 뱃지(학교 이름 시맨틱·툴팁)와 비자 일정이 닉네임 뒤에 보인다', (tester) async {
      final semantics = tester.ensureSemantics();
      await pumpApp(tester, user: userJson(school: _school));
      final order = ['홍길동 님', '인증', '비자 일정', '쪽지', '내 정보', '로그아웃'];
      for (final t in order) {
        expect(find.text(t), findsOneWidget);
      }
      final xs = [for (final t in order) _x(tester, t)];
      expect(xs, [...xs]..sort());
      // 스크린리더는 "인증 (서울대 인증 회원)"으로 읽는다
      expect(find.bySemanticsLabel('인증 (서울대 인증 회원)'), findsOneWidget);
      // 툴팁에는 학교 이름
      final tooltip = tester.widget<Tooltip>(find.byType(Tooltip));
      expect(tooltip.message, '서울대');
      expect(find.text('신고 관리'), findsNothing);
      semantics.dispose();
    });

    testWidgets('관리자: 신고 관리가 내 정보 뒤·로그아웃 앞에 보인다', (tester) async {
      await pumpApp(tester, user: userJson(role: 'ADMIN'));
      final order = ['쪽지', '내 정보', '신고 관리', '로그아웃'];
      for (final t in order) {
        expect(find.text(t), findsOneWidget);
      }
      final xs = [for (final t in order) _x(tester, t)];
      expect(xs, [...xs]..sort());
    });

    testWidgets('링크를 누르면 그 주소로 간다 (비자 후기 → /visa-reviews)', (tester) async {
      // S16-h1: 비자 후기 목록은 진짜 화면이라 시작 화면(글 목록)과 후기 목록이 부르는 요청을 모두 준비한다
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, []))
        ..on('GET', '/categories', FakeReply(200, []))
        ..on('GET', '/posts', FakeReply(200, []))
        ..on('GET', '/schools', FakeReply(200, []))
        ..on('GET', '/visa-review-options', FakeReply(200, {'visaTypes': [], 'applicationTypes': [], 'results': []}))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      final app = await pumpApp(tester, adapter: adapter);
      await tester.tap(find.text('비자 후기'));
      await tester.pumpAndSettle();
      expect(find.text('아직 후기가 없습니다'), findsOneWidget);
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-reviews');
    });

    testWidgets('사이트 이름은 홈 링크: 링크 시맨틱이 있고 누르면 "/"로 간다', (tester) async {
      final semantics = tester.ensureSemantics();
      final app = await pumpApp(tester, location: '/visa-reviews');
      final data = tester.getSemantics(find.text('게시판')).getSemanticsData();
      expect(data.flagsCollection.isLink, isTrue);
      await tester.tap(find.text('게시판'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/');
      expect(find.text('전체 게시판'), findsOneWidget);
      semantics.dispose();
    });

    testWidgets('쪽지: 안 읽은 수가 0이면 숫자 없이 "쪽지"만 보이고, 로그인 상태라 unread-count를 부른다', (tester) async {
      // S16-l2: 상단바가 안 읽은 수를 붙인다(0이면 숫자 없음). 시작 때 한 번, 화면을 옮기면(글 목록 → 쪽지함) 또 한 번 부른다
      final adapter = FakeAdapter()
        ..on('GET', '/conversations/unread-count', FakeReply(200, {'count': 0}))
        ..on('GET', '/conversations/unread-count', FakeReply(200, {'count': 0}));
      final app = await pumpApp(tester, user: userJson(), adapter: adapter);
      await tester.tap(find.text('쪽지'));
      await tester.pumpAndSettle();
      expect(find.text('쪽지'), findsOneWidget);
      expect(find.textContaining('쪽지 ('), findsNothing);
      // S16-e1: 시작 화면이 글 목록이라 목록 요청(/schools·/categories·/posts)은 간다. S16-l1: 쪽지함(/conversations)도 한 번.
      // S16-l2: 안 읽은 수(/conversations/unread-count)가 더해진다. 그 밖의 요청은 없다
      const listPaths = {
        '/api/schools',
        '/api/categories',
        '/api/posts',
        '/api/conversations',
        '/api/conversations/unread-count',
      };
      expect(app.adapter.calls.where((c) => !listPaths.contains(c.path)), isEmpty);
      expect(app.adapter.count('GET', '/conversations/unread-count'), 2);
    });
  });

  group('로그아웃', () {
    testWidgets('성공: 로그인 링크로 바뀌고 공개 화면(글 목록)은 그대로', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/auth/logout', FakeReply(200));
      await pumpApp(tester, user: userJson(), adapter: adapter);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/auth/logout'), 1);
      expect(find.text('로그인'), findsOneWidget);
      expect(find.text('회원가입'), findsOneWidget);
      expect(find.text('로그아웃'), findsNothing);
      expect(find.text('홍길동 님'), findsNothing);
      expect(find.text('전체 게시판'), findsOneWidget);
    });

    testWidgets('실패: 로그인 상태 유지 + 상단바 아래에 안내, 다시 누르면 안내가 지워진다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('POST', '/auth/logout', FakeReply(500))
        ..on('POST', '/auth/logout', FakeReply(500, null, gate));
      await pumpApp(tester, user: userJson(), adapter: adapter);

      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      const msg = '로그아웃하지 못했습니다. 다시 시도해 주세요';
      expect(find.text(msg), findsOneWidget);
      expect(find.text('홍길동 님'), findsOneWidget); // 상태 유지
      expect(find.text('로그아웃'), findsOneWidget);
      // 안내는 메뉴 줄 아래
      expect(tester.getTopLeft(find.text(msg)).dy, greaterThan(tester.getBottomLeft(find.text('로그아웃')).dy));

      // 다시 누르면(두 번째 응답은 붙잡아 둠) 요청 중에 안내가 먼저 지워진다
      await tester.tap(find.text('로그아웃'));
      await tester.pump();
      expect(find.text(msg), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text(msg), findsOneWidget); // 또 실패하면 다시 뜬다
    });

    testWidgets('보호 화면(/me)에서 로그아웃하면 문지기가 /login 으로 보낸다', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/auth/logout', FakeReply(200));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      expect(find.text('학교 인증'), findsOneWidget); // S16-g1: 진짜 내 정보 화면 ("학교 인증" 제목으로 확인)
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/login');
      expect(find.text('아이디'), findsOneWidget);
    });
  });

  testWidgets('화면이 짧아 스크롤하면 상단바도 본문과 같이 올라간다 (고정되지 않는다)', (tester) async {
    tester.view.physicalSize = const Size(600, 300);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpApp(tester, location: '/login');
    final before = tester.getTopLeft(find.text('게시판')).dy;
    await tester.drag(find.byType(SingleChildScrollView), const Offset(0, -200));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.text('게시판')).dy, lessThan(before));
  });

  testWidgets('375px 좁은 화면: 메뉴가 줄바꿈되고 넘침 에러가 없다 (관리자·인증·긴 닉네임)', (tester) async {
    tester.view.physicalSize = const Size(375, 812);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await pumpApp(
      tester,
      user: userJson(role: 'ADMIN', nickname: '아주아주아주긴닉네임입니다', school: _school),
    );
    expect(tester.takeException(), isNull); // 레이아웃 넘침(overflow) 에러가 없다
    for (final t in ['비자 후기', '중고·나눔', '인증', '비자 일정', '쪽지', '내 정보', '신고 관리', '로그아웃']) {
      expect(find.text(t), findsOneWidget);
      // 모든 메뉴가 화면 폭 안에 있다
      expect(tester.getTopRight(find.text(t)).dx, lessThanOrEqualTo(375));
    }
  });
}
