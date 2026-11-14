// S16-l1 쪽지 화면 테스트 (React의 messages.spec / messages-extra.spec 중 l1 범위):
// 상세의 "판매자에게 쪽지"(보내기 흐름·못 보내는 이유 4가지·관리자 규칙·실패 5종·1000자·연타·401),
// 쪽지함(행·빈·실패·401·375px), 대화(헤더·말풍선·답장·실패 5종·탈퇴·삭제된 글·미인증·id 인코딩·건너뛰기).
// 안 읽은 수·읽음 PUT·신고·hasMore 안내는 l2라 여기서 보지 않는다.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/messages/models.dart';
import 'package:board_web/messages/seller_message_box.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/message_json.dart';
import 'support/screen.dart';

// 서울대 인증 회원 (id 1)
Map<String, dynamic> _verified({String role = 'USER'}) => userJson(role: role, school: marketSeoul);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 401은 자동 재발급을 먼저 시도한다(실패하면 로그인이 풀린다). 그 비동기 요청이 끝나길 기다린다
Future<void> _waitRefresh(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
}

// 서버가 받은 요청 본문들 (본문 없는 GET은 뺀다)
List<Object?> _bodies(FakeAdapter adapter) {
  final bodies = <Object?>[];
  adapter.onRequestBody = (data) {
    if (data != null) bodies.add(data);
  };
  return bodies;
}

final _field = find.byKey(const ValueKey('message-composer-field'));
String _text(WidgetTester tester) => tester.widget<TextField>(_field).controller!.text;
Finder _filled(String label) => find.widgetWithText(FilledButton, label);
bool _enabled(WidgetTester tester, Finder button) => tester.widget<FilledButton>(button).onPressed != null;
bool _focused(WidgetTester tester) => tester.widget<TextField>(_field).focusNode!.hasFocus;
// 안내(Notice) 하나: 글자가 같은 것
Finder _notice(String message) => find.byWidgetPredicate((w) => w is Notice && w.message == message);

// ---------------------------------------------------------------- 상세: 판매자에게 쪽지

// 상세 글 응답을 정한 가짜 서버 (기본: 판매자 id 5 = 남의 글, 서울대, 판매중)
FakeAdapter _detailAdapter({
  String statusCode = 'ON_SALE',
  String statusName = '판매중',
  Map<String, dynamic> school = marketSeoul,
  int sellerId = 5,
}) => FakeAdapter()
  ..on(
    'GET',
    '/market-items/7',
    FakeReply(
      200,
      marketDetailJson(
        statusCode: statusCode,
        statusName: statusName,
        school: school,
        sellerId: sellerId,
        sellerNickname: '판매자닉',
      ),
    ),
  );

Future<TestApp> _openDetail(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) =>
    pumpApp(tester, location: '/market/7', user: user, adapter: adapter);

final _sellerBox = find.byType(SellerMessageBox);
final _send = _filled('쪽지 보내기');
// 상세의 쪽지 영역 안에서 글자 찾기 (상단바의 같은 글자와 섞이지 않게)
Finder _inBox(String text) => find.descendant(of: _sellerBox, matching: find.text(text));

const _doneNotice = '거래가 완료된 글이라 새 대화를 시작할 수 없어요. 이미 나눈 대화는 쪽지함에서 이어 갈 수 있어요';

void main() {
  group('상세: 보내기 흐름', () {
    _test('인증 회원이 남의 글에서 쪽지를 보내면 POST가 나가고 /messages/3 대화 화면으로 간다', (tester) async {
      final adapter = _detailAdapter()
        ..on('POST', '/market-items/7/conversations', FakeReply(201, {'conversationId': 3, 'message': messageJson(id: 10)}))
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()));
      final bodies = _bodies(adapter);
      final app = await _openDetail(tester, adapter, user: _verified());
      expect(find.text('판매자에게 쪽지'), findsOneWidget);
      expect(find.text('판매자닉 님에게 보낼 쪽지를 입력하세요'), findsOneWidget); // placeholder
      await tester.enterText(_field, '안녕하세요, 아직 있나요?');
      await tester.pump();
      expect(find.text('14 / 1000'), findsOneWidget);
      await tester.tap(_send);
      await tester.pumpAndSettle();

      expect(_uri(app), '/messages/3');
      expect(adapter.count('POST', '/market-items/7/conversations'), 1);
      // S16-l2: 대화가 열리면 읽음 PUT({lastMessageId})도 나가므로 본문은 쪽지 → 읽음 순서로 두 개
      expect(bodies, [
        {'content': '안녕하세요, 아직 있나요?'},
        {'lastMessageId': 2},
      ]);
      // 대화 화면이 열렸다 (상대 닉네임 제목)
      expect(find.text('판매자닉'), findsOneWidget);
    });

    _test('비었거나 공백뿐이면 보내기가 비활성이고 글자 수는 0 / 1000이다', (tester) async {
      await _openDetail(tester, _detailAdapter(), user: _verified());
      expect(find.text('0 / 1000'), findsOneWidget);
      expect(_enabled(tester, _send), isFalse);
      await tester.enterText(_field, '   \n  ');
      await tester.pump();
      expect(_enabled(tester, _send), isFalse);
      await tester.enterText(_field, '안녕');
      await tester.pump();
      expect(_enabled(tester, _send), isTrue);
    });

    _test('보내는 중에는 버튼이 잠기고 "보내는 중..."이 보이며 요청은 한 번뿐이다', (tester) async {
      final gate = Completer<void>();
      final adapter = _detailAdapter()
        ..on(
          'POST',
          '/market-items/7/conversations',
          FakeReply(201, {'conversationId': 3, 'message': messageJson(id: 10)}, gate),
        )
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()));
      final app = await _openDetail(tester, adapter, user: _verified());
      await tester.enterText(_field, '안녕');
      await tester.pump();
      await tester.tap(_send);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_filled('보내는 중...'), findsOneWidget);
      expect(_enabled(tester, _filled('보내는 중...')), isFalse);
      expect(adapter.count('POST', '/market-items/7/conversations'), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/messages/3');
    });

    _test('보내기를 연달아 두 번 눌러도 요청은 한 번만 나간다', (tester) async {
      final gate = Completer<void>();
      final adapter = _detailAdapter()
        ..on(
          'POST',
          '/market-items/7/conversations',
          FakeReply(201, {'conversationId': 3, 'message': messageJson(id: 10)}, gate),
        )
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()));
      await _openDetail(tester, adapter, user: _verified());
      await tester.enterText(_field, '안녕');
      await tester.pump();
      // 화면이 다시 그려지기 전에 연달아 두 번 누른다
      await tester.tap(_send);
      await tester.tap(_send);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', '/market-items/7/conversations'), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/market-items/7/conversations'), 1);
    });

    _test('1000자를 넘게 쓰려 하면 1000자까지만 들어가고 글자 수는 1000 / 1000이다', (tester) async {
      await _openDetail(tester, _detailAdapter(), user: _verified());
      await tester.enterText(_field, '가' * 1100);
      await tester.pump();
      expect(_text(tester).length, 1000);
      expect(find.text('1000 / 1000'), findsOneWidget);
      expect(_enabled(tester, _send), isTrue);
    });

    _test('글자 수는 UTF-16 길이로 센다: 이모지(2칸) 600개를 넣으면 500개(1000칸)까지만', (tester) async {
      await _openDetail(tester, _detailAdapter(), user: _verified());
      await tester.enterText(_field, '😀' * 600);
      await tester.pump();
      expect(_text(tester).length, 1000);
      expect(find.text('1000 / 1000'), findsOneWidget);
    });

    _test('예약중인 글에도 입력칸이 있다', (tester) async {
      await _openDetail(tester, _detailAdapter(statusCode: 'RESERVED', statusName: '예약중'), user: _verified());
      expect(_field, findsOneWidget);
      expect(_send, findsOneWidget);
      expect(find.textContaining('새 대화를 시작할 수 없어요'), findsNothing);
    });
  });

  group('상세: 칸이 없거나 이유만 보이는 경우', () {
    for (final role in ['USER', 'ADMIN']) {
      _test('내 글에는 칸도 안내도 없다 ($role)', (tester) async {
        await _openDetail(tester, _detailAdapter(sellerId: 1), user: _verified(role: role));
        expect(find.text('판매자에게 쪽지'), findsNothing);
        expect(_field, findsNothing);
        expect(find.textContaining('쪽지를 보낼 수'), findsNothing);
        expect(find.textContaining('새 대화를 시작할 수 없어요'), findsNothing);
        expect(find.text('전공 서적 팝니다'), findsOneWidget); // 글은 떴다
      });
    }

    _test('관리자도 남의 글이면 일반 회원과 같이 입력칸이 있다', (tester) async {
      await _openDetail(tester, _detailAdapter(), user: _verified(role: 'ADMIN'));
      expect(_field, findsOneWidget);
      expect(_send, findsOneWidget);
    });

    _test('관리자가 미인증이면 일반 회원과 같이 학교 인증 안내가 보인다', (tester) async {
      await _openDetail(tester, _detailAdapter(), user: userJson(role: 'ADMIN'));
      expect(_inBox('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요'), findsOneWidget);
      expect(_field, findsNothing);
    });

    _test('비로그인이면 입력칸 대신 로그인 안내가 보이고 [로그인]은 /login으로 간다', (tester) async {
      final app = await _openDetail(tester, _detailAdapter());
      expect(_inBox('로그인하면 판매자에게 쪽지를 보낼 수 있습니다'), findsOneWidget);
      expect(_field, findsNothing);
      await tester.tap(_inBox('로그인'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    _test('학교 인증 전이면 학교 인증 안내가 보이고 [학교 인증하기]는 /me로 간다', (tester) async {
      final adapter = _detailAdapter()..on('GET', '/schools', FakeReply(200, []));
      final app = await _openDetail(tester, adapter, user: userJson());
      expect(_inBox('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요'), findsOneWidget);
      expect(_field, findsNothing);
      await tester.tap(_inBox('학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });

    _test('다른 학교 글이면 인증한 학교 안내가 보인다', (tester) async {
      await _openDetail(tester, _detailAdapter(school: marketBusan), user: _verified());
      expect(_inBox('인증한 학교(서울대) 글에만 쪽지를 보낼 수 있어요'), findsOneWidget);
      expect(_field, findsNothing);
    });

    _test('거래완료 글이면 안내와 [쪽지함 가기]가 보이고 누르면 /messages로 간다', (tester) async {
      final adapter = _detailAdapter(statusCode: 'DONE', statusName: '거래완료')..on('GET', '/conversations', FakeReply(200, []));
      final app = await _openDetail(tester, adapter, user: _verified());
      expect(_inBox(_doneNotice), findsOneWidget);
      expect(_field, findsNothing);
      await tester.tap(_inBox('쪽지함 가기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/messages');
      expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
    });

    _test('이유는 비로그인 → 미인증 → 다른 학교 → 거래완료 순으로 하나만 고른다', (tester) async {
      // 미인증이면서 다른 학교·거래완료 글 → 미인증 안내만
      await _openDetail(tester, _detailAdapter(statusCode: 'DONE', statusName: '거래완료', school: marketBusan), user: userJson());
      expect(_inBox('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요'), findsOneWidget);
      expect(find.textContaining('인증한 학교('), findsNothing);
      expect(find.textContaining('새 대화를 시작할 수 없어요'), findsNothing);
    });
  });

  group('상세: 실패와 401', () {
    final failures = <(int, String)>[
      (400, '쪽지 내용을 입력해 주세요 (최대 1000자)'),
      (403, '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요'),
      (404, '이미 삭제된 글입니다'),
      (409, _doneNotice),
      (500, '쪽지를 보내지 못했습니다'),
    ];
    for (final (status, text) in failures) {
      _test('보내기가 $status이면 "$text"가 칸 아래에 보이고 쓴 내용은 남는다', (tester) async {
        final adapter = _detailAdapter()..on('POST', '/market-items/7/conversations', FakeReply(status));
        final app = await _openDetail(tester, adapter, user: _verified());
        await tester.enterText(_field, '안녕하세요');
        await tester.pump();
        await tester.tap(_send);
        await tester.pumpAndSettle();
        expect(_notice(text), findsOneWidget);
        expect(_text(tester), '안녕하세요');
        expect(_enabled(tester, _send), isTrue);
        expect(_uri(app), '/market/7'); // 화면은 그대로
      });
    }

    _test('네트워크가 끊기면 "서버에 연결할 수 없습니다"가 보인다', (tester) async {
      final adapter = _detailAdapter()..on('POST', '/market-items/7/conversations', FakeReply.networkError());
      await _openDetail(tester, adapter, user: _verified());
      await tester.enterText(_field, '안녕하세요');
      await tester.pump();
      await tester.tap(_send);
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('보내기가 401(재발급도 401)이면 다시 로그인 안내가 보이고 쓴 내용·칸이 그대로다', (tester) async {
      final adapter = _detailAdapter()
        ..on('POST', '/market-items/7/conversations', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _openDetail(tester, adapter, user: _verified());
      await tester.enterText(_field, '안녕하세요');
      await tester.pump();
      await tester.tap(_send);
      await _waitRefresh(tester);
      expect(find.descendant(of: _sellerBox, matching: find.byType(ReloginNotice)), findsOneWidget);
      expect(find.text('쪽지를 보내지 못했습니다'), findsNothing); // 일반 실패 문구는 없다
      // 로그인이 풀렸어도(user 비워짐) 한 번 보냈으니 칸이 사라지지 않는다
      expect(_field, findsOneWidget);
      expect(_text(tester), '안녕하세요');
      expect(_inBox('로그인하면 판매자에게 쪽지를 보낼 수 있습니다'), findsNothing);
      expect(_uri(app), '/market/7');
    });
  });

  // ---------------------------------------------------------------- 쪽지함

  group('쪽지함', () {
    Future<TestApp> openList(WidgetTester tester, FakeAdapter adapter) =>
        pumpApp(tester, location: '/messages', user: _verified(), adapter: adapter);

    _test('불러오는 동안은 "쪽지함 불러오는 중..."이 보이고 제목은 "쪽지함"이다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', '/conversations', FakeReply(200, [summaryJson()], gate));
      await openList(tester, adapter);
      expect(find.text('쪽지함 불러오는 중...'), findsOneWidget);
      expect(find.text('쪽지함'), findsWidgets); // 제목 (상단바에도 같은 글자가 있을 수 있다)
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('쪽지함 불러오는 중...'), findsNothing);
      expect(adapter.count('GET', '/conversations'), 1); // 마운트 때 한 번만
    });

    _test('비어 있으면 "아직 쪽지가 없습니다"', (tester) async {
      await openList(tester, FakeAdapter()..on('GET', '/conversations', FakeReply(200, [])));
      expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
      expect(find.byKey(const ValueKey('conv-row-3')), findsNothing);
    });

    _test('행: 상대 닉네임·글 제목·마지막 쪽지("나: ")·시각, 탈퇴한 상대·삭제된 글', (tester) async {
      final adapter = FakeAdapter()
        ..on(
          'GET',
          '/conversations',
          FakeReply(200, [
            summaryJson(id: 3, content: '내일 3시에 만나요', mine: true, createdAt: '2026-01-02T03:05:00Z'),
            summaryJson(
              id: 2,
              item: itemRefJson(id: null, title: '지워진 책상', deleted: true),
              partner: null,
              content: '감사합니다',
              createdAt: '2026-01-01T03:05:00Z',
            ),
          ]),
        );
      await openList(tester, adapter);
      Finder inRow(int id, Finder f) => find.descendant(of: find.byKey(ValueKey('conv-row-$id')), matching: f);
      // 첫 줄
      expect(inRow(3, find.text('판매자닉')), findsOneWidget);
      expect(inRow(3, find.text('가짜 자전거')), findsOneWidget);
      expect(inRow(3, find.text('나: 내일 3시에 만나요')), findsOneWidget);
      expect(inRow(3, find.text(formatDate('2026-01-02T03:05:00Z'))), findsOneWidget);
      expect(inRow(3, find.text('삭제된 글')), findsNothing);
      // 둘째 줄: 탈퇴한 상대 + 삭제된 글 표시, "나:" 없음
      expect(inRow(2, find.text('탈퇴한 회원')), findsOneWidget);
      expect(inRow(2, find.text('삭제된 글')), findsOneWidget);
      expect(inRow(2, find.text('지워진 책상')), findsOneWidget);
      expect(inRow(2, find.text('감사합니다')), findsOneWidget);
    });

    _test('긴 마지막 쪽지는 한 줄 말줄임이고, 줄바꿈은 공백으로 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on(
          'GET',
          '/conversations',
          FakeReply(200, [summaryJson(content: '첫 줄\n둘째 줄')]),
        );
      await openList(tester, adapter);
      final last = tester.widget<Text>(find.text('첫 줄 둘째 줄'));
      expect(last.maxLines, 1);
      expect(last.overflow, TextOverflow.ellipsis);
    });

    _test('줄을 누르면 /messages/3 대화로 간다 (줄 전체가 링크)', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/conversations', FakeReply(200, [summaryJson(id: 3)]))
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()));
      final app = await openList(tester, adapter);
      // 글자가 없는 오른쪽 아래 빈 곳을 눌러도 이동한다
      final row = find.byKey(const ValueKey('conv-row-3'));
      await tester.tapAt(tester.getBottomRight(row) - const Offset(8, 8));
      await tester.pumpAndSettle();
      expect(_uri(app), '/messages/3');
      expect(find.text('아직 있어요'), findsOneWidget);
    });

    _test('불러오기가 500이면 "쪽지함을 불러오지 못했습니다"', (tester) async {
      await openList(tester, FakeAdapter()..on('GET', '/conversations', FakeReply(500)));
      expect(find.text('쪽지함을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('쪽지함 불러오는 중...'), findsNothing);
      expect(find.text('아직 쪽지가 없습니다'), findsNothing);
    });

    _test('불러오기가 401(재발급도 401)이면 다시 로그인 안내만 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/conversations', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await openList(tester, adapter);
      await _waitRefresh(tester);
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(find.text('쪽지함을 불러오지 못했습니다'), findsNothing);
      expect(find.text('쪽지함 불러오는 중...'), findsNothing);
    });

    _test('375px에서 긴 마지막 쪽지·긴 글 제목이 있어도 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      final adapter = FakeAdapter()
        ..on(
          'GET',
          '/conversations',
          FakeReply(200, [
            summaryJson(content: '아주 긴 쪽지 ' * 60, item: itemRefJson(title: '아주 긴 글 제목 ' * 20), partner: {'id': 7, 'nickname': '아주긴닉네임' * 8}),
          ]),
        );
      await openList(tester, adapter);
      expect(find.byKey(const ValueKey('conv-row-3')), findsOneWidget);
      expect(tester.takeException(), isNull);
      expect(tester.getSize(find.byKey(const ValueKey('conv-row-3'))).width, lessThanOrEqualTo(375));
    });
  });

  // ---------------------------------------------------------------- 대화

  group('대화', () {
    FakeAdapter convAdapter({Map<String, dynamic>? conv}) =>
        FakeAdapter()
          ..on('GET', '/conversations/3', FakeReply(200, conv ?? conversationJson()))
          ..on('PUT', '/conversations/3/read', FakeReply(204)); // S16-l2: 열면 나가는 읽음 처리

    Future<TestApp> openConv(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user, String id = '3'}) =>
        pumpApp(tester, location: '/messages/$id', user: user ?? _verified(), adapter: adapter);

    final bubbleMine = find.byKey(const ValueKey('msg-bubble-1'));
    final bubbleTheirs = find.byKey(const ValueKey('msg-bubble-2'));
    final replyButton = _filled('답장 보내기');

    _test('불러오는 동안은 "불러오는 중..."과 [쪽지함으로]만 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', '/conversations/3', FakeReply(200, conversationJson(), gate));
      await openConv(tester, adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '쪽지함으로'), findsOneWidget);
      expect(find.text('판매자닉'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('판매자닉'), findsOneWidget);
    });

    _test('머리: 상대 닉네임(제목)과 글 링크, 링크를 누르면 /market/7로 간다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = convAdapter()
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(sellerId: 5)));
      final app = await openConv(tester, adapter);
      expect(tester.getSemantics(find.text('판매자닉')).flagsCollection.isHeader, isTrue);
      expect(find.text('삭제된 글'), findsNothing);
      await tester.tap(find.text('가짜 자전거'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/7');
      handle.dispose();
    });

    _test('말풍선: 오래된 순, 내 쪽지는 오른쪽·상대 쪽지는 왼쪽, 시각이 붙고 스크린리더 문구가 앞에 붙는다', (tester) async {
      final handle = tester.ensureSemantics();
      await openConv(tester, convAdapter());
      expect(find.byKey(const ValueKey('msg-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
      expect(tester.getTopLeft(find.byKey(const ValueKey('msg-1'))).dy, lessThan(tester.getTopLeft(find.byKey(const ValueKey('msg-2'))).dy));
      expect(find.descendant(of: bubbleMine, matching: find.text('안녕하세요, 아직 있나요?')), findsOneWidget);
      expect(find.descendant(of: bubbleTheirs, matching: find.text('아직 있어요')), findsOneWidget);
      // 내 말풍선이 상대 말풍선보다 오른쪽에 있다
      expect(tester.getTopLeft(bubbleMine).dx, greaterThan(tester.getTopLeft(bubbleTheirs).dx));
      // 배경: 내 것 청자색, 상대 것 hover 색(서로 다르다)
      Color bg(Finder f) => (tester.widget<Container>(f).decoration! as BoxDecoration).color!;
      expect(bg(bubbleMine), isNot(bg(bubbleTheirs)));
      // 시각(서울 시간)과 시맨틱 문구
      expect(find.text(formatDate('2026-01-02T03:00:00Z')), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('내가 보냄 ${formatDate('2026-01-02T03:00:00Z')}')), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('받은 쪽지 ${formatDate('2026-01-02T03:05:00Z')}')), findsOneWidget);
      handle.dispose();
    });

    _test('쪽지의 줄바꿈이 그대로 보이고, 말풍선 폭은 최대 85%다', (tester) async {
      final adapter = convAdapter(
        conv: conversationJson(messages: [messageJson(id: 1, content: '첫 줄\n둘째 줄', mine: false), messageJson(id: 2, content: '긴쪽지' * 200, mine: true)]),
      );
      await openConv(tester, adapter);
      expect(find.text('첫 줄\n둘째 줄'), findsOneWidget);
      final listWidth = tester.getSize(find.byKey(const ValueKey('msg-1'))).width;
      expect(tester.getSize(find.byKey(const ValueKey('msg-bubble-2'))).width, lessThanOrEqualTo(listWidth * 0.85 + 0.5));
    });

    _test('쪽지가 0통이면 "아직 쪽지가 없습니다"', (tester) async {
      await openConv(tester, convAdapter(conv: conversationJson(messages: [])));
      expect(find.text('아직 쪽지가 없습니다'), findsOneWidget);
    });

    _test('쪽지가 많으면 열 때 맨 아래로 내려가 있다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(800, 600);
      addTearDown(tester.view.reset);
      final many = [for (var i = 1; i <= 40; i++) messageJson(id: i, content: '쪽지 $i', mine: i.isEven)];
      await openConv(tester, convAdapter(conv: conversationJson(messages: many)));
      final position = tester.state<ScrollableState>(find.byType(Scrollable).first).position;
      expect(position.maxScrollExtent, greaterThan(0));
      expect(position.pixels, greaterThan(0));
      // 답장 칸 끝이 화면 아래에 닿아 있고(마지막 쪽지와 입력칸이 보임), 맨 처음 쪽지는 위로 밀려났다
      expect(tester.getBottomLeft(_field).dy, lessThanOrEqualTo(600));
      expect(tester.getBottomLeft(find.byKey(const ValueKey('msg-40'))).dy, inInclusiveRange(0, 600));
      expect(tester.getTopLeft(find.byKey(const ValueKey('msg-1'))).dy, lessThan(0));
    });

    group('답장', () {
      _test('답장을 보내면 목록 끝에 붙고 입력칸이 비워지며 포커스가 돌아온다', (tester) async {
        final adapter = convAdapter()..on('POST', '/conversations/3/messages', FakeReply(201, messageJson(id: 12, content: '답장입니다', createdAt: '2026-01-02T03:10:00Z')));
        final bodies = _bodies(adapter);
        await openConv(tester, adapter);
        expect(find.text('답장을 입력하세요'), findsOneWidget); // placeholder
        await tester.enterText(_field, '답장입니다');
        await tester.pump();
        await tester.tap(replyButton);
        await tester.pumpAndSettle();
        expect(find.byKey(const ValueKey('msg-12')), findsOneWidget);
        expect(find.descendant(of: find.byKey(const ValueKey('msg-bubble-12')), matching: find.text('답장입니다')), findsOneWidget);
        // 마지막 말풍선 = 방금 보낸 것, 내 것이라 가장 오른쪽
        expect(tester.getTopLeft(find.byKey(const ValueKey('msg-12'))).dy, greaterThan(tester.getTopLeft(find.byKey(const ValueKey('msg-2'))).dy));
        expect(_text(tester), '');
        expect(_focused(tester), isTrue);
        // S16-l2: 대화를 열 때 읽음 PUT이 한 번 나갔고, 답장 뒤에는 다시 나가지 않는다
        expect(bodies, [
          {'lastMessageId': 2},
          {'content': '답장입니다'},
        ]);
      });

      _test('비었거나 공백뿐이면 비활성, 글자 수 표시가 있다', (tester) async {
        await openConv(tester, convAdapter());
        expect(find.text('0 / 1000'), findsOneWidget);
        expect(_enabled(tester, replyButton), isFalse);
        await tester.enterText(_field, '  ');
        await tester.pump();
        expect(_enabled(tester, replyButton), isFalse);
        await tester.enterText(_field, '네');
        await tester.pump();
        expect(_enabled(tester, replyButton), isTrue);
        expect(find.text('1 / 1000'), findsOneWidget);
      });

      _test('연달아 두 번 눌러도 요청은 한 번이고 쪽지는 한 통만 붙는다', (tester) async {
        final gate = Completer<void>();
        final adapter = convAdapter()..on('POST', '/conversations/3/messages', FakeReply(201, messageJson(id: 12, content: '답장입니다'), gate));
        await openConv(tester, adapter);
        await tester.enterText(_field, '답장입니다');
        await tester.pump();
        await tester.tap(replyButton);
        await tester.tap(replyButton);
        await tester.pump(const Duration(milliseconds: 50));
        expect(_filled('보내는 중...'), findsOneWidget);
        expect(adapter.count('POST', '/conversations/3/messages'), 1);
        gate.complete();
        await tester.pumpAndSettle();
        expect(adapter.count('POST', '/conversations/3/messages'), 1);
        expect(find.byKey(const ValueKey('msg-12')), findsOneWidget);
      });

      final failures = <(int, String)>[
        (400, '쪽지 내용을 입력해 주세요 (최대 1000자)'),
        (403, '쪽지를 보내지 못했습니다. 새로고침 후 다시 시도해 주세요'),
        (404, '대화를 찾을 수 없습니다'),
        (409, '상대가 탈퇴해 쪽지를 보낼 수 없습니다'),
        (500, '쪽지를 보내지 못했습니다'),
      ];
      for (final (status, text) in failures) {
        _test('답장이 $status이면 "$text"가 보이고 쓴 내용은 남고 목록엔 안 붙는다', (tester) async {
          final adapter = convAdapter()..on('POST', '/conversations/3/messages', FakeReply(status));
          await openConv(tester, adapter);
          await tester.enterText(_field, '답장입니다');
          await tester.pump();
          await tester.tap(replyButton);
          await tester.pumpAndSettle();
          expect(_notice(text), findsOneWidget);
          expect(_text(tester), '답장입니다');
          expect(find.byKey(const ValueKey('msg-12')), findsNothing);
          expect(find.byKey(const ValueKey('msg-1')), findsOneWidget);
          expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
        });
      }

      _test('답장이 401(재발급도 401)이면 다시 로그인 안내가 보이고 쓴 내용·쪽지 목록·주소가 그대로다', (tester) async {
        final adapter = convAdapter()
          ..on('POST', '/conversations/3/messages', FakeReply(401))
          ..on('POST', '/auth/refresh', FakeReply(401));
        final app = await openConv(tester, adapter);
        await tester.enterText(_field, '답장입니다');
        await tester.pump();
        await tester.tap(replyButton);
        await _waitRefresh(tester);
        expect(find.byType(ReloginNotice), findsOneWidget);
        expect(find.text('쪽지를 보내지 못했습니다'), findsNothing);
        // 로그인이 풀려도(user null) 미인증 안내로 바뀌지 않고 칸이 남는다
        expect(_field, findsOneWidget);
        expect(_text(tester), '답장입니다');
        expect(find.text('학교 인증 후 답장할 수 있어요'), findsNothing);
        expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
        expect(_uri(app), '/messages/3');
      });
    });

    _test('상대가 탈퇴한 대화는 "탈퇴한 회원"과 안내가 보이고 답장 칸이 없다', (tester) async {
      await openConv(tester, convAdapter(conv: conversationJson(partner: null)));
      expect(find.text('탈퇴한 회원'), findsOneWidget);
      expect(_notice('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요'), findsOneWidget);
      expect(tester.widget<Notice>(_notice('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요')).kind, NoticeKind.info);
      expect(_field, findsNothing);
      // 지난 쪽지는 그대로 읽을 수 있다
      expect(find.byKey(const ValueKey('msg-1')), findsOneWidget);
      expect(find.byKey(const ValueKey('msg-2')), findsOneWidget);
    });

    _test('글이 지워진 대화는 글 제목에 링크가 없고 "삭제된 글" 표시가 붙는다', (tester) async {
      await openConv(tester, convAdapter(conv: conversationJson(item: itemRefJson(id: null, title: '지워진 책상', deleted: true))));
      expect(find.text('지워진 책상'), findsOneWidget);
      expect(find.text('삭제된 글'), findsOneWidget);
      // 제목을 눌러도 아무 데도 가지 않는다
      await tester.tap(find.text('지워진 책상'));
      await tester.pumpAndSettle();
      expect(find.text('지워진 책상'), findsOneWidget);
    });

    _test('학교 미인증 회원은 대화는 읽을 수 있고 답장 칸 대신 안내가 보인다 ([학교 인증하기] → /me)', (tester) async {
      final app = await openConv(tester, convAdapter()..on('GET', '/schools', FakeReply(200, [])), user: userJson());
      expect(find.byKey(const ValueKey('msg-1')), findsOneWidget);
      expect(find.text('학교 인증 후 답장할 수 있어요'), findsOneWidget);
      expect(_field, findsNothing);
      await tester.tap(find.text('학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });

    _test('탈퇴한 상대 안내가 미인증 안내보다 먼저다', (tester) async {
      await openConv(tester, convAdapter(conv: conversationJson(partner: null)), user: userJson());
      expect(find.text('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요'), findsOneWidget);
      expect(find.text('학교 인증 후 답장할 수 있어요'), findsNothing);
    });

    _test('[쪽지함으로]를 누르면 /messages로 간다', (tester) async {
      final adapter = convAdapter()..on('GET', '/conversations', FakeReply(200, []));
      final app = await openConv(tester, adapter);
      await tester.tap(find.widgetWithText(OutlinedButton, '쪽지함으로'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/messages');
    });

    group('불러오기 실패', () {
      _test('404 → "대화를 찾을 수 없습니다"와 [쪽지함으로], 답장 칸은 없다', (tester) async {
        final adapter = FakeAdapter()..on('GET', '/conversations/9', FakeReply(404));
        await openConv(tester, adapter, id: '9');
        expect(find.text('대화를 찾을 수 없습니다'), findsOneWidget);
        expect(find.widgetWithText(OutlinedButton, '쪽지함으로'), findsOneWidget);
        expect(_field, findsNothing);
        expect(find.text('불러오는 중...'), findsNothing);
      });

      _test('숫자가 아닌 id(abc)가 400이어도 같은 문구', (tester) async {
        final adapter = FakeAdapter()..on('GET', '/conversations/abc', FakeReply(400));
        await openConv(tester, adapter, id: 'abc');
        expect(find.text('대화를 찾을 수 없습니다'), findsOneWidget);
        expect(_field, findsNothing);
      });

      _test('500 → "대화를 불러오지 못했습니다"', (tester) async {
        await openConv(tester, FakeAdapter()..on('GET', '/conversations/3', FakeReply(500)));
        expect(find.text('대화를 불러오지 못했습니다'), findsOneWidget);
      });

      _test('401(재발급도 401) → 다시 로그인 안내만 보인다', (tester) async {
        final adapter = FakeAdapter()
          ..on('GET', '/conversations/3', FakeReply(401))
          ..on('POST', '/auth/refresh', FakeReply(401));
        await openConv(tester, adapter);
        await _waitRefresh(tester);
        expect(find.byType(ReloginNotice), findsOneWidget);
        expect(find.text('대화를 불러오지 못했습니다'), findsNothing);
        expect(_field, findsNothing);
      });

      _test('"?"가 든 id는 경로에 인코딩되어 다른 API로 바뀌지 않는다', (tester) async {
        final adapter = FakeAdapter()..on('GET', '/conversations/1%3Fx%3D1', FakeReply(404));
        await openConv(tester, adapter, id: '1%3Fx=1');
        expect(adapter.count('GET', '/conversations/1%3Fx%3D1'), 1);
        expect(find.text('대화를 찾을 수 없습니다'), findsOneWidget);
      });
    });

    _test('대화 5에서 쓰던 답장은 대화 3으로 건너뛰면 사라지고, 답장은 화면에 보이는 대화로 간다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/conversations/3', FakeReply(200, conversationJson()))
        ..on('GET', '/conversations/5', FakeReply(200, conversationJson(id: 5, partner: {'id': 8, 'nickname': '다른닉'})))
        ..on('POST', '/conversations/3/messages', FakeReply(201, messageJson(id: 20, content: '3번에게 쓰는 글')));
      final app = await openConv(tester, adapter);
      expect(find.text('판매자닉'), findsOneWidget);

      // 대화 5로 건너뛰어 답장을 쓰다가
      app.router.go('/messages/5');
      await tester.pumpAndSettle();
      expect(find.text('다른닉'), findsOneWidget);
      await tester.enterText(_field, '5번에게 쓰던 글');
      await tester.pump();

      // 대화 3의 응답을 붙잡아 두고 3번으로 돌아간다
      adapter.on('GET', '/conversations/3', FakeReply(200, conversationJson(), gate));
      app.router.go('/messages/3');
      await tester.pump();
      await tester.pump();
      // 새 대화가 오기 전에 옛 대화(다른닉)가 남아 있으면 안 된다
      expect(find.text('다른닉'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();

      // 3번이 보이면 입력칸은 비어 있고, 이어서 쓴 답장은 3번으로만 간다
      expect(find.text('판매자닉'), findsOneWidget);
      expect(_text(tester), '');
      await tester.enterText(_field, '3번에게 쓰는 글');
      await tester.pump();
      await tester.tap(replyButton);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/conversations/3/messages'), 1);
      expect(adapter.count('POST', '/conversations/5/messages'), 0);
      expect(find.byKey(const ValueKey('msg-20')), findsOneWidget);
    });

    _test('375px에서 긴 쪽지·긴 닉네임·긴 글 제목이 있어도 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      final adapter = convAdapter(
        conv: conversationJson(
          partner: {'id': 7, 'nickname': '아주긴닉네임' * 8},
          item: itemRefJson(title: '아주 긴 글 제목 ' * 20),
          messages: [messageJson(id: 1, content: '긴쪽지' * 200, mine: false), messageJson(id: 2, content: '내 긴쪽지' * 200, mine: true)],
        ),
      );
      await openConv(tester, adapter);
      expect(find.byKey(const ValueKey('msg-1')), findsOneWidget);
      expect(tester.takeException(), isNull);
      expect(tester.getSize(find.byKey(const ValueKey('msg-bubble-1'))).width, lessThanOrEqualTo(375));
    });

    test('모델: partner가 null이어도 읽히고 withMessage는 원본을 바꾸지 않는다', () {
      final conv = ConversationDetail.fromJson(conversationJson(partner: null));
      expect(conv.partner, isNull);
      final added = conv.withMessage(ConversationMessage.fromJson(messageJson(id: 9)));
      expect(added.messages.length, 3);
      expect(conv.messages.length, 2);
      final summary = ConversationSummary.fromJson(summaryJson(partner: null, unreadCount: 4));
      expect(summary.partner, isNull);
      expect(summary.unreadCount, 4);
    });
  });
}
