// S16-l2 테스트 (React의 messages-extra / messages-report / unread-badge-hasmore 회원 시나리오):
// 안 읽은 수 보관소(상단바 "쪽지 (n)", 경로가 바뀔 때만 다시 받기, 로그인·로그아웃·회원 교체, 실패·늦은 응답),
// 쪽지함 행 배지, 대화를 열 때 읽음 PUT, 대화 신고, hasMore 안내.
import 'dart:async';

import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/messages/message_text.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/message_json.dart';
import 'support/screen.dart';

// 서울대 인증 회원 (id 1) / 학교 미인증 회원
Map<String, dynamic> _verified({int id = 1}) => userJson(id: id, school: marketSeoul);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

// 안 읽은 수 응답을 한 개 줄 세운다 (gate가 있으면 풀 때까지 붙잡아 둔다)
void _count(FakeAdapter a, int n, [Completer<void>? gate]) =>
    a.on('GET', '/conversations/unread-count', FakeReply(200, {'count': n}, gate));

// n개의 안 읽은 수 응답이 차례로 나가는 가짜 서버
FakeAdapter _counts(List<int> counts) {
  final a = FakeAdapter();
  for (final n in counts) {
    _count(a, n);
  }
  return a;
}

int _unreadCalls(FakeAdapter a) => a.count('GET', '/conversations/unread-count');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 대화 3을 불러오는 가짜 서버 (열면 나가는 읽음 PUT 포함)
FakeAdapter _convAdapter({Map<String, dynamic>? conv, int id = 3, List<int> counts = const [0, 0, 0, 0]}) {
  final a = _counts(counts)
    ..on('GET', '/conversations/$id', FakeReply(200, conv ?? conversationJson(id: id)))
    ..on('PUT', '/conversations/$id/read', FakeReply(204));
  return a;
}

// 서버가 받은 요청 본문들 (본문 없는 GET은 뺀다)
List<Object?> _bodies(FakeAdapter adapter) {
  final bodies = <Object?>[];
  adapter.onRequestBody = (data) {
    if (data != null) bodies.add(data);
  };
  return bodies;
}

// 401은 자동 재발급을 먼저 시도한다(실패하면 로그인이 풀린다). 그 비동기 요청이 끝나길 기다린다
Future<void> _waitRefresh(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
}

void main() {
  group('상단바 숫자', () {
    _test('안 읽은 쪽지가 있으면 "쪽지 (3)"이고 숫자 없이 "쪽지"는 보이지 않는다', (tester) async {
      await pumpApp(tester, location: '/zzz', user: userJson(), adapter: _counts([3]));
      expect(find.text('쪽지 (3)'), findsOneWidget);
      expect(find.text('쪽지'), findsNothing);
    });

    _test('0이면 숫자 없이 "쪽지"만 보인다', (tester) async {
      await pumpApp(tester, location: '/zzz', user: userJson(), adapter: _counts([0]));
      expect(find.text('쪽지'), findsOneWidget);
      expect(find.textContaining('쪽지 ('), findsNothing);
    });

    _test('100개 이상이어도 99+로 줄이지 않고 숫자 그대로 보인다', (tester) async {
      await pumpApp(tester, location: '/zzz', user: userJson(), adapter: _counts([120]));
      expect(find.text('쪽지 (120)'), findsOneWidget);
      expect(find.textContaining('99+'), findsNothing);
    });

    _test('비로그인이면 unread-count를 한 번도 부르지 않고 쪽지 메뉴도 없다', (tester) async {
      final adapter = FakeAdapter();
      final app = await pumpApp(tester, location: '/zzz', adapter: adapter);
      app.router.go('/yyy');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 0);
      expect(find.textContaining('쪽지'), findsNothing);
    });

    _test('쪽지 링크를 누르면 /messages로 간다 (숫자가 붙어도 링크)', (tester) async {
      final adapter = _counts([2, 2])..on('GET', '/conversations', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      await tester.tap(find.text('쪽지 (2)'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/messages');
    });
  });

  group('다시 받는 때', () {
    _test('화면(경로)을 옮길 때마다 다시 부른다', (tester) async {
      final adapter = _counts([1, 2, 3]);
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      expect(_unreadCalls(adapter), 1);
      expect(find.text('쪽지 (1)'), findsOneWidget);

      app.router.go('/yyy');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 2);
      expect(find.text('쪽지 (2)'), findsOneWidget);

      app.router.go('/xxx');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 3);
      expect(find.text('쪽지 (3)'), findsOneWidget);
    });

    _test('경로는 그대로이고 쿼리만 바뀌면 다시 부르지 않는다', (tester) async {
      final adapter = _counts([1, 2, 3]);
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      app.router.go('/yyy');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 2);

      app.router.go('/yyy?page=2');
      await tester.pumpAndSettle();
      app.router.go('/yyy?page=3&q=a');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 2);
      expect(find.text('쪽지 (2)'), findsOneWidget);
    });

    _test('로그인한 직후 숫자를 받아 온다', (tester) async {
      final adapter = _counts([5]);
      final app = await pumpApp(tester, location: '/zzz', adapter: adapter);
      expect(_unreadCalls(adapter), 0);
      app.auth.updateUser(AuthUser.fromJson(userJson()));
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 1);
      expect(find.text('쪽지 (5)'), findsOneWidget);
    });

    _test('로그아웃하면 숫자가 사라지고 더 부르지 않는다', (tester) async {
      final adapter = _counts([4, 4])..on('POST', '/auth/logout', FakeReply(200));
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      expect(find.text('쪽지 (4)'), findsOneWidget);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(find.textContaining('쪽지'), findsNothing);
      app.router.go('/yyy');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 1);
      expect(find.textContaining('쪽지'), findsNothing);
    });

    _test('다른 회원으로 바뀌면 이전 회원의 숫자는 새 응답이 오기 전에 바로 비워진다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter();
      _count(adapter, 7);
      _count(adapter, 2, gate);
      final app = await pumpApp(tester, location: '/zzz', user: userJson(id: 1), adapter: adapter);
      expect(find.text('쪽지 (7)'), findsOneWidget);

      app.auth.updateUser(AuthUser.fromJson(userJson(id: 2)));
      await tester.pump();
      expect(find.text('쪽지 (7)'), findsNothing); // 이전 회원 숫자는 안 보인다
      expect(find.text('쪽지'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('쪽지 (2)'), findsOneWidget);
    });

    _test('같은 회원의 닉네임만 바뀌면 다시 부르지 않는다', (tester) async {
      final adapter = _counts([1, 1]);
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      app.auth.updateUser(AuthUser.fromJson(userJson(nickname: '새닉')));
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 1);
      expect(find.text('쪽지 (1)'), findsOneWidget);
    });
  });

  group('실패와 늦은 응답', () {
    _test('불러오기가 실패하면 이전 숫자를 그대로 둔다', (tester) async {
      final adapter = _counts([5])..on('GET', '/conversations/unread-count', FakeReply(500));
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      app.router.go('/yyy');
      await tester.pumpAndSettle();
      expect(_unreadCalls(adapter), 2);
      expect(find.text('쪽지 (5)'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    _test('시작 때 실패하면 숫자 없이 "쪽지"이고 화면은 멀쩡하다', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/conversations/unread-count', FakeReply.networkError());
      await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      expect(find.text('쪽지'), findsOneWidget);
    });

    _test('먼저 보낸 요청의 응답이 늦게 와도 가장 마지막 응답이 이긴다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter();
      _count(adapter, 1);
      _count(adapter, 9, gate); // 첫 이동의 응답은 붙잡아 둔다
      _count(adapter, 2); // 두 번째 이동의 응답은 먼저 온다
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      app.router.go('/yyy');
      await tester.pump();
      app.router.go('/xxx');
      await tester.pumpAndSettle();
      expect(find.text('쪽지 (2)'), findsOneWidget);
      gate.complete(); // 옛 응답(9)이 이제 도착
      await tester.pumpAndSettle();
      expect(find.text('쪽지 (2)'), findsOneWidget);
      expect(find.text('쪽지 (9)'), findsNothing);
    });

    _test('로그아웃 뒤에 도착한 응답은 버린다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter();
      _count(adapter, 1);
      _count(adapter, 8, gate);
      adapter.on('POST', '/auth/logout', FakeReply(200));
      final app = await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      app.router.go('/yyy');
      await tester.pumpAndSettle(); // 화면 전환이 끝나야 상단바 버튼이 눌린다 (붙잡아 둔 응답은 화면을 안 멈춘다)
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.textContaining('쪽지'), findsNothing);
    });

    _test('unread-count가 401(재발급도 401)이면 로그인이 풀려 비로그인 상단바가 된다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/conversations/unread-count', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await pumpApp(tester, location: '/zzz', user: userJson(), adapter: adapter);
      await _waitRefresh(tester);
      expect(find.text('로그인'), findsOneWidget);
      expect(find.textContaining('쪽지'), findsNothing);
    });
  });

  group('쪽지함 배지', () {
    Future<TestApp> openList(WidgetTester tester, List<Map<String, dynamic>> rows, {FakeAdapter? adapter}) {
      final a = adapter ?? _counts([0, 0]);
      a.on('GET', '/conversations', FakeReply(200, rows));
      return pumpApp(tester, location: '/messages', user: _verified(), adapter: a);
    }

    Finder badge(int id) => find.byKey(ValueKey('conv-unread-$id'));

    _test('안 읽은 쪽지가 있는 행에만 배지가 있고 글자는 숫자다 (1·99는 그대로)', (tester) async {
      await openList(tester, [
        summaryJson(id: 1, unreadCount: 1),
        summaryJson(id: 2, unreadCount: 99),
        summaryJson(id: 3, unreadCount: 0),
      ]);
      expect(find.descendant(of: badge(1), matching: find.text('1')), findsOneWidget);
      expect(find.descendant(of: badge(2), matching: find.text('99')), findsOneWidget);
      expect(badge(3), findsNothing);
    });

    _test('100 이상은 "99+"로 보이고 스크린리더는 실제 개수 문장을 읽는다', (tester) async {
      final semantics = tester.ensureSemantics();
      await openList(tester, [summaryJson(id: 1, unreadCount: 100), summaryJson(id: 2, unreadCount: 3)]);
      expect(find.descendant(of: badge(1), matching: find.text('99+')), findsOneWidget);
      // 줄 전체가 링크라 줄의 다른 글자와 한 항목으로 묶여 읽힌다 → 문장이 들어 있는지(부분 일치)로 본다
      expect(find.bySemanticsLabel(RegExp('안 읽은 쪽지 100개')), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('안 읽은 쪽지 3개')), findsOneWidget);
      // 눈에 보이는 "99+"는 따로 읽히지 않는다 (문장으로 대신)
      expect(find.bySemanticsLabel(RegExp(r'99\+')), findsNothing);
      semantics.dispose();
    });

    _test('배지는 청자색이고 닉네임 바로 옆(같은 줄)에 있다', (tester) async {
      await openList(tester, [summaryJson(id: 1, unreadCount: 2)]);
      final deco = tester.widget<Container>(badge(1)).decoration! as BoxDecoration;
      expect(deco.color, isNotNull);
      final nick = tester.getTopRight(find.text('판매자닉'));
      final b = tester.getTopLeft(badge(1));
      expect(b.dx, greaterThan(nick.dx));
      expect((b.dy - nick.dy).abs(), lessThan(12)); // 같은 줄
    });

    _test('탈퇴한 상대의 행에도 안 읽은 배지가 붙는다', (tester) async {
      await openList(tester, [summaryJson(id: 1, partner: null, unreadCount: 2)]);
      expect(find.text('탈퇴한 회원'), findsOneWidget);
      expect(badge(1), findsOneWidget);
    });

    _test('375px에서 아주 긴 닉네임이어도 배지가 줄 밖으로 밀리지 않고 넘침이 없다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      await openList(tester, [
        summaryJson(id: 1, partner: {'id': 7, 'nickname': '아주긴닉네임' * 10}, unreadCount: 100),
      ]);
      expect(tester.takeException(), isNull);
      expect(badge(1), findsOneWidget);
      expect(tester.getTopRight(badge(1)).dx, lessThanOrEqualTo(375));
      expect(find.text('99+'), findsOneWidget);
    });

    test('formatUnreadBadge: 0 이하는 빈 글자, 1~99는 그대로, 100 이상은 99+', () {
      expect(formatUnreadBadge(0), '');
      expect(formatUnreadBadge(-1), '');
      expect(formatUnreadBadge(1), '1');
      expect(formatUnreadBadge(99), '99');
      expect(formatUnreadBadge(100), '99+');
      expect(formatUnreadBadge(1234), '99+');
    });
  });

  group('읽음 처리', () {
    Future<TestApp> openConv(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user, String id = '3'}) =>
        pumpApp(tester, location: '/messages/$id', user: user ?? _verified(), adapter: adapter);

    _test('대화를 열면 PUT /conversations/3/read에 마지막 쪽지 번호가 한 번 나간다', (tester) async {
      final adapter = _convAdapter();
      final bodies = _bodies(adapter);
      await openConv(tester, adapter);
      expect(adapter.count('PUT', '/conversations/3/read'), 1);
      expect(bodies, [
        {'lastMessageId': 2},
      ]);
    });

    _test('성공하면 상단바 숫자를 다시 받아 줄어든다', (tester) async {
      final adapter = _convAdapter(counts: [3, 0]);
      await openConv(tester, adapter);
      expect(adapter.count('PUT', '/conversations/3/read'), 1);
      expect(_unreadCalls(adapter), 2); // 시작 때 + 읽음 처리 뒤
      expect(find.text('쪽지 (3)'), findsNothing);
      expect(find.text('쪽지'), findsOneWidget);
    });

    _test('쪽지가 0통이면 PUT을 보내지 않는다', (tester) async {
      final adapter = _convAdapter(conv: conversationJson(messages: []));
      await openConv(tester, adapter);
      expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
      expect(adapter.count('PUT', '/conversations/3/read'), 0);
    });

    _test('읽음 처리가 실패해도 대화 화면은 그대로이고 숫자도 그대로다', (tester) async {
      final adapter = _counts([3, 0, 0])
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()))
        ..on('PUT', '/conversations/3/read', FakeReply(500));
      await openConv(tester, adapter);
      expect(find.text('판매자닉'), findsOneWidget);
      expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
      expect(_unreadCalls(adapter), 1); // 실패하면 다시 받지 않는다
      expect(find.text('쪽지 (3)'), findsOneWidget);
      expect(find.text('쪽지를 보내지 못했습니다'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    _test('읽음 처리가 네트워크 오류로 실패해도 조용하다', (tester) async {
      final adapter = _counts([1, 1])
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()))
        ..on('PUT', '/conversations/3/read', FakeReply.networkError());
      await openConv(tester, adapter);
      expect(find.text('판매자닉'), findsOneWidget);
      expect(find.byType(ReloginNotice), findsNothing);
    });

    _test('대화를 못 불러오면(404) PUT을 보내지 않는다', (tester) async {
      final adapter = _counts([0, 0])..on('GET', '/conversations/3', FakeReply(404));
      await openConv(tester, adapter);
      expect(find.text('대화를 찾을 수 없습니다'), findsOneWidget);
      expect(adapter.count('PUT', '/conversations/3/read'), 0);
    });

    _test('대화 3에서 5로 옮기면 5의 마지막 쪽지 번호로 PUT이 나간다', (tester) async {
      final adapter = _convAdapter();
      adapter
        ..on(
          'GET',
          '/conversations/5',
          FakeReply(
            200,
            conversationJson(
              id: 5,
              partner: {'id': 8, 'nickname': '다른닉'},
              messages: [messageJson(id: 7, mine: false), messageJson(id: 9, mine: false)],
            ),
          ),
        )
        ..on('PUT', '/conversations/5/read', FakeReply(204));
      final bodies = _bodies(adapter);
      final app = await openConv(tester, adapter);
      app.router.go('/messages/5');
      await tester.pumpAndSettle();
      expect(find.text('다른닉'), findsOneWidget);
      expect(adapter.count('PUT', '/conversations/3/read'), 1);
      expect(adapter.count('PUT', '/conversations/5/read'), 1);
      expect(bodies, [
        {'lastMessageId': 2},
        {'lastMessageId': 9},
      ]);
    });

    _test('답장을 보내도 읽음 PUT은 다시 나가지 않는다', (tester) async {
      final adapter = _convAdapter()
        ..on('POST', '/conversations/3/messages', FakeReply(201, messageJson(id: 12, content: '답장입니다')));
      await openConv(tester, adapter);
      await tester.enterText(find.byKey(const ValueKey('message-composer-field')), '답장입니다');
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, '답장 보내기'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('msg-12')), findsOneWidget);
      expect(adapter.count('PUT', '/conversations/3/read'), 1);
    });
  });

  group('대화 신고', () {
    const reasons = [
      {'code': 'SPAM', 'name': '광고·스팸'},
      {'code': 'ABUSE', 'name': '욕설·비방'},
    ];
    final openButton = find.widgetWithText(OutlinedButton, '신고');
    final sendButton = find.widgetWithText(OutlinedButton, '신고 보내기');

    Future<TestApp> openConv(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) =>
        pumpApp(tester, location: '/messages/3', user: user ?? _verified(), adapter: adapter);

    Future<void> pickAndSend(WidgetTester tester, {String name = '광고·스팸'}) async {
      await tester.tap(openButton);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('report-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text(name).last);
      await tester.pumpAndSettle();
      await tester.tap(sendButton);
      await tester.pumpAndSettle();
    }

    _test('학교 인증 회원이면 헤더 아래·쪽지 목록 위에 [신고]가 보인다', (tester) async {
      await openConv(tester, _convAdapter());
      expect(openButton, findsOneWidget);
      final head = tester.getBottomLeft(find.text('가짜 자전거')).dy;
      final btn = tester.getTopLeft(openButton).dy;
      final firstMsg = tester.getTopLeft(find.byKey(const ValueKey('msg-1'))).dy;
      expect(btn, greaterThan(head));
      expect(btn, lessThan(firstMsg));
    });

    _test('학교 미인증 회원에게는 [신고]가 없다', (tester) async {
      await openConv(tester, _convAdapter(), user: userJson());
      expect(find.text('판매자닉'), findsOneWidget);
      expect(openButton, findsNothing);
    });

    _test('신고하면 POST /conversations/3/reports가 {reason}으로 나가고 성공 문구가 보이며 양식·버튼이 사라진다', (tester) async {
      final adapter = _convAdapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/conversations/3/reports', FakeReply(201));
      final bodies = _bodies(adapter);
      await openConv(tester, adapter);
      await pickAndSend(tester);
      expect(adapter.count('POST', '/conversations/3/reports'), 1);
      expect(bodies.whereType<Map>().where((b) => b.containsKey('reason')), [
        {'reason': 'SPAM'},
      ]);
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
      expect(sendButton, findsNothing);
      expect(openButton, findsNothing);
      // 신고해도 대화는 그대로
      expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
    });

    _test('상대가 탈퇴한 대화·삭제된 글의 대화도 신고할 수 있다', (tester) async {
      final adapter = _convAdapter(conv: conversationJson(partner: null, item: itemRefJson(id: null, deleted: true)));
      await openConv(tester, adapter);
      expect(find.text('탈퇴한 회원'), findsOneWidget);
      expect(openButton, findsOneWidget);
    });

    _test('이미 신고한 대화(409)면 "이미 신고한 대화입니다"가 보이고 양식이 닫힌다', (tester) async {
      final adapter = _convAdapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/conversations/3/reports', FakeReply(409));
      await openConv(tester, adapter);
      await pickAndSend(tester);
      expect(find.text('이미 신고한 대화입니다'), findsOneWidget);
      expect(sendButton, findsNothing);
      expect(openButton, findsNothing);
    });

    _test('신고가 401(재발급도 401)이면 화면에 다시 로그인 안내가 보인다', (tester) async {
      final adapter = _convAdapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/conversations/3/reports', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await openConv(tester, adapter);
      await tester.tap(openButton);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('report-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('광고·스팸').last);
      await tester.pumpAndSettle();
      await tester.tap(sendButton);
      await _waitRefresh(tester);
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(_uri(app), '/messages/3');
    });

    _test('사유를 안 골라 보내면 안내만 뜨고 요청은 안 나간다', (tester) async {
      final adapter = _convAdapter()..on('GET', '/report-reasons', FakeReply(200, reasons));
      await openConv(tester, adapter);
      await tester.tap(openButton);
      await tester.pumpAndSettle();
      await tester.tap(sendButton);
      await tester.pumpAndSettle();
      expect(find.text('신고 사유를 골라 주세요'), findsOneWidget);
      expect(adapter.count('POST', '/conversations/3/reports'), 0);
    });

    _test('열어 둔 양식은 다른 대화로 건너뛰면 사라지고, 신고는 화면에 보이는 대화로 간다', (tester) async {
      final adapter = _convAdapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('GET', '/conversations/5', FakeReply(200, conversationJson(id: 5, partner: {'id': 8, 'nickname': '다른닉'})))
        ..on('PUT', '/conversations/5/read', FakeReply(204))
        ..on('POST', '/conversations/5/reports', FakeReply(201));
      final app = await openConv(tester, adapter);
      await tester.tap(openButton); // 대화 3에서 양식을 열어 둔 채
      await tester.pumpAndSettle();
      expect(sendButton, findsOneWidget);

      app.router.go('/messages/5');
      await tester.pumpAndSettle();
      expect(find.text('다른닉'), findsOneWidget);
      expect(sendButton, findsNothing); // 양식은 닫힌 채로 새로 시작
      expect(openButton, findsOneWidget);

      await pickAndSend(tester);
      expect(adapter.count('POST', '/conversations/5/reports'), 1);
      expect(adapter.count('POST', '/conversations/3/reports'), 0);
    });
  });

  group('hasMore 안내', () {
    Future<TestApp> openConv(WidgetTester tester, Map<String, dynamic> conv) =>
        pumpApp(tester, location: '/messages/3', user: _verified(), adapter: _convAdapter(conv: conv));

    _test('hasMore가 true면 목록 위에 "이전 쪽지는 표시되지 않아요"가 보인다 (회색 작은 글씨)', (tester) async {
      await openConv(tester, conversationJson(hasMore: true));
      final hint = find.text('이전 쪽지는 표시되지 않아요');
      expect(hint, findsOneWidget);
      expect(tester.getTopLeft(hint).dy, lessThan(tester.getTopLeft(find.byKey(const ValueKey('msg-1'))).dy));
      final style = tester.widget<Text>(hint).style!;
      expect(style.fontSize, lessThan(16));
    });

    _test('hasMore가 false면 안내가 없다', (tester) async {
      await openConv(tester, conversationJson());
      expect(find.text('이전 쪽지는 표시되지 않아요'), findsNothing);
    });

    _test('쪽지가 0통이어도 hasMore면 안내는 보인다', (tester) async {
      await openConv(tester, conversationJson(hasMore: true, messages: []));
      expect(find.text('이전 쪽지는 표시되지 않아요'), findsOneWidget);
    });

    _test('375px에서도 안내가 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      await openConv(tester, conversationJson(hasMore: true));
      expect(tester.takeException(), isNull);
      expect(tester.getTopRight(find.text('이전 쪽지는 표시되지 않아요')).dx, lessThanOrEqualTo(375));
    });
  });
}
