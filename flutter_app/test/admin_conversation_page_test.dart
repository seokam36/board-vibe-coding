// S16-m 신고된 대화 보기 화면 테스트 (React의 admin-conversation.spec / admin-conversation-extra.spec의 대화 보기 부분):
// 권한(요청 0건), 표시(안내·제목·구매자/판매자·말풍선 방향·보낸 사람 표시·시각), 탈퇴한 사람, 0통·hasMore,
// 실패 분기와 [다시 시도] 규칙, 늦은 응답 무시, 주소 인코딩, 읽기 전용(GET 외 요청 0), 375px.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _path = '/admin/reports/7/conversation';
const _info = '관리자가 신고된 대화를 열람 중입니다. 읽기만 할 수 있어요';

FakeAdapter _adapter({FakeReply? reply}) =>
    FakeAdapter()..on('GET', _path, reply ?? FakeReply(200, adminConversationJson()));

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user, String location = _path}) =>
    pumpApp(tester, location: location, user: user ?? adminUser(), adapter: adapter);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

Finder _notice(String message) => find.byWidgetPredicate((w) => w is Notice && w.message == message);
Finder _retry() => find.widgetWithText(OutlinedButton, '다시 시도');

// 말풍선 한 통을 감싼 정렬 위젯의 방향
Alignment _align(WidgetTester tester, int id) => tester.widget<Align>(find.byKey(ValueKey('msg-$id'))).alignment as Alignment;

Color? _bubbleColor(WidgetTester tester, int id) {
  final box = tester.widget<Container>(find.byKey(ValueKey('msg-bubble-$id'))).decoration as BoxDecoration;
  return box.color;
}

void main() {
  group('권한', () {
    for (final (name, user) in <(String, Map<String, dynamic>?)>[('일반 회원', userJson()), ('비로그인', null)]) {
      testWidgets('$name은 "관리자만 볼 수 있습니다"(error)만 보이고 요청은 한 건도 없다', (tester) async {
        final adapter = FakeAdapter();
        await pumpApp(tester, location: _path, user: user, adapter: adapter);
        expect(find.text('관리자만 볼 수 있습니다'), findsOneWidget);
        expect(tester.widget<Notice>(_notice('관리자만 볼 수 있습니다')).kind, NoticeKind.error);
        expect(adapter.calls.where((c) => c.path.contains('/admin')), isEmpty); // 관리자 API는 0건 (상단바의 다른 요청은 상관없다)
        expect(find.text('신고된 대화'), findsNothing);
      });
    }
  });

  group('표시', () {
    _test('안내(info)·제목·구매자·판매자·쪽지 내용·시각이 보이고, GET 한 번만 나간다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter);
      expect(find.text('신고된 대화'), findsOneWidget);
      expect(_notice(_info), findsOneWidget);
      expect(tester.widget<Notice>(_notice(_info)).kind, NoticeKind.info);
      expect(find.text('중고 자전거 팝니다'), findsOneWidget);
      expect(find.text('구매자'), findsOneWidget);
      expect(find.text('판매자'), findsNWidgets(2)); // 메타 라벨 + 판매자 닉네임(이 테스트 데이터는 닉네임도 "판매자")
      expect(find.text('아직 있나요?'), findsOneWidget);
      expect(find.text('네 있어요'), findsOneWidget);
      expect(find.text(formatDate('2026-11-13T03:00:00Z')), findsOneWidget);
      expect(find.text(formatDate('2026-11-13T03:05:00Z')), findsOneWidget);
      expect(adapter.calls.where((c) => c.path.contains('/admin')).length, 1);
      expect(adapter.count('GET', _path), 1);
      expect(find.text('불러오는 중...'), findsNothing);
    });

    _test('보낸 사람 표시: 구매자는 "닉네임 · 구매자", 판매자는 "닉네임 · 판매자"', (tester) async {
      await _open(tester, _adapter());
      expect(find.text('신고자 · 구매자'), findsOneWidget);
      expect(find.text('판매자 · 판매자'), findsOneWidget);
    });

    _test('말풍선 방향: 판매자는 오른쪽(청자 배경), 구매자는 왼쪽(hover 배경)', (tester) async {
      await _open(tester, _adapter());
      expect(_align(tester, 31), Alignment.centerRight);
      expect(_bubbleColor(tester, 31), AppColors.celadonSoft);
      expect(_align(tester, 30), Alignment.centerLeft);
      expect(_bubbleColor(tester, 30), AppColors.hover);
    });

    _test('보낸 사람이 탈퇴(null)면 "탈퇴한 회원"만(역할 없음), 왼쪽 말풍선', (tester) async {
      final json = adminConversationJson(messages: [adminMessageJson(id: 40, sender: null, content: '탈퇴 전 쪽지')]);
      await _open(tester, _adapter(reply: FakeReply(200, json)));
      // 메타의 "판매자" 라벨과 겹치지 않게: 말풍선 위 이름은 "탈퇴한 회원" 한 글자열
      expect(find.text('탈퇴한 회원'), findsOneWidget);
      expect(find.text('탈퇴 전 쪽지'), findsOneWidget);
      expect(_align(tester, 40), Alignment.centerLeft);
    });

    _test('구매자·판매자가 탈퇴(null)면 메타에 "탈퇴한 회원", 보낸 사람 역할은 알 수 없어 이름만 + 왼쪽', (tester) async {
      final json = adminConversationJson(buyer: null, seller: null);
      await _open(tester, _adapter(reply: FakeReply(200, json)));
      expect(find.text('탈퇴한 회원'), findsNWidgets(2)); // 구매자·판매자 칸
      expect(find.text('신고자'), findsOneWidget); // 역할 없이 이름만
      expect(find.text('신고자 · 구매자'), findsNothing);
      expect(_align(tester, 31), Alignment.centerLeft); // 판매자 여부를 알 수 없으면 왼쪽
    });

    _test('쪽지 0통이면 "아직 쪽지가 없습니다"', (tester) async {
      await _open(tester, _adapter(reply: FakeReply(200, adminConversationJson(messages: []))));
      expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
      expect(find.text('최근 200통만 보여요. 이전 쪽지는 표시되지 않아요'), findsNothing);
    });

    _test('hasMore면 "최근 200통만 보여요. 이전 쪽지는 표시되지 않아요"', (tester) async {
      await _open(tester, _adapter(reply: FakeReply(200, adminConversationJson(hasMore: true))));
      expect(find.text('최근 200통만 보여요. 이전 쪽지는 표시되지 않아요'), findsOneWidget);
    });

    _test('hasMore가 false면 안내가 없다', (tester) async {
      await _open(tester, _adapter());
      expect(find.text('최근 200통만 보여요. 이전 쪽지는 표시되지 않아요'), findsNothing);
    });

    _test('본문은 줄바꿈 그대로 보이고, HTML은 실행되지 않고 글자 그대로다', (tester) async {
      const html = '<b>굵게</b><script>alert(1)</script>';
      final json = adminConversationJson(messages: [adminMessageJson(content: '첫 줄\n둘째 줄\n$html')]);
      await _open(tester, _adapter(reply: FakeReply(200, json)));
      expect(find.text('첫 줄\n둘째 줄\n$html'), findsOneWidget);
    });

    _test('읽기 전용: 입력칸·답장·신고 버튼이 없고 GET 외 요청이 0건이다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter);
      expect(find.byType(TextField), findsNothing);
      expect(find.text('답장 보내기'), findsNothing);
      expect(find.text('신고'), findsNothing);
      expect(adapter.calls.every((c) => c.method == 'GET'), isTrue);
    });

    _test('[신고 목록으로]를 누르면 /admin/reports 로 간다', (tester) async {
      final adapter = _adapter()..on('GET', '/admin/reports', FakeReply(200, []));
      final app = await _open(tester, adapter);
      await tester.tap(find.widgetWithText(OutlinedButton, '신고 목록으로'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/admin/reports');
      expect(find.text('신고 관리'), findsWidgets);
    });

    _test('주소의 신고 번호는 인코딩해서 요청한다 (? 같은 글자가 다른 API로 바뀌지 않는다)', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/admin/reports/a%3Fb/conversation', FakeReply(400));
      await _open(tester, adapter, location: '/admin/reports/a%3Fb/conversation');
      expect(adapter.count('GET', '/admin/reports/a%3Fb/conversation'), 1);
      expect(find.text('없는 신고이거나 대화 신고가 아닙니다'), findsOneWidget);
    });

    _test('불러오는 동안 "불러오는 중..."이 보이고 안내(열람 중)는 아직 없다. [신고 목록으로]는 항상 있다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(reply: FakeReply(200, adminConversationJson(), gate));
      await _open(tester, adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_notice(_info), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '신고 목록으로'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(_notice(_info), findsOneWidget);
    });
  });

  group('실패', () {
    for (final (status, message) in const [
      (400, '없는 신고이거나 대화 신고가 아닙니다'),
      (404, '없는 신고이거나 대화 신고가 아닙니다'),
      (403, '관리자만 볼 수 있습니다'),
    ]) {
      _test('$status → "$message", [다시 시도]는 없고 안내(열람 중)도 없다', (tester) async {
        final adapter = _adapter(reply: FakeReply(status));
        await _open(tester, adapter);
        expect(find.text(message), findsOneWidget);
        expect(tester.widget<Notice>(find.ancestor(of: find.text(message), matching: find.byType(Notice))).kind, NoticeKind.error);
        expect(_retry(), findsNothing);
        expect(_notice(_info), findsNothing);
        expect(find.widgetWithText(OutlinedButton, '신고 목록으로'), findsOneWidget);
      });
    }

    _test('500 → 기본 문구 "대화를 불러오지 못했습니다" + [다시 시도], 누르면 다시 불러와 성공한다', (tester) async {
      final adapter = _adapter(reply: FakeReply(500))..on('GET', _path, FakeReply(200, adminConversationJson()));
      await _open(tester, adapter);
      expect(find.text('대화를 불러오지 못했습니다'), findsOneWidget);
      expect(_retry(), findsOneWidget);
      await tester.tap(_retry());
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _path), 2);
      expect(find.text('대화를 불러오지 못했습니다'), findsNothing);
      expect(find.text('아직 있나요?'), findsOneWidget);
      expect(_retry(), findsNothing);
    });

    _test('[다시 시도]를 누르면 앞의 오류가 지워지고 "불러오는 중..."이 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(reply: FakeReply(500))..on('GET', _path, FakeReply(200, adminConversationJson(), gate));
      await _open(tester, adapter);
      await tester.tap(_retry());
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('대화를 불러오지 못했습니다'), findsNothing);
      expect(find.text('불러오는 중...'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('아직 있나요?'), findsOneWidget);
    });

    _test('네트워크 오류 → "서버에 연결할 수 없습니다" + [다시 시도]', (tester) async {
      await _open(tester, _adapter(reply: FakeReply.networkError()));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_retry(), findsOneWidget);
    });

    _test('401 + 재발급 401 → "다시 로그인해 주세요"와 로그인 링크만 (관리자가 아니게 되어도 유지)', (tester) async {
      final adapter = _adapter(reply: FakeReply(401))..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('관리자만 볼 수 있습니다'), findsNothing);
      expect(find.text('로그인'), findsWidgets);
    });
  });

  group('늦은 응답', () {
    _test('신고 7의 응답이 늦게 와도 이미 옮겨 간 신고 8의 화면에는 반영되지 않는다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(200, adminConversationJson(messages: [adminMessageJson(content: '7번 쪽지')]), gate))
        ..on(
          'GET',
          '/admin/reports/8/conversation',
          FakeReply(200, adminConversationJson(messages: [adminMessageJson(id: 50, content: '8번 쪽지')])),
        );
      final app = await _open(tester, adapter);
      app.router.go('/admin/reports/8/conversation');
      await tester.pumpAndSettle();
      expect(find.text('8번 쪽지'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('7번 쪽지'), findsNothing);
      expect(find.text('8번 쪽지'), findsOneWidget);
    });
  });

  testWidgets('375px 좁은 화면에서도 넘치지 않고 쪽지가 보인다', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(375, 1800);
    addTearDown(tester.view.reset);
    final json = adminConversationJson(
      messages: [adminMessageJson(content: '아주 긴 쪽지 ${'가나다라마바사 ' * 30}')],
    );
    await _open(tester, _adapter(reply: FakeReply(200, json)));
    expect(tester.takeException(), isNull);
    expect(find.textContaining('아주 긴 쪽지'), findsOneWidget);
  });
}
