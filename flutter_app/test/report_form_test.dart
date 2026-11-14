// S16-f1 공용 신고 양식(ReportForm) 테스트: React ReportForm과 같은 동작(열기·사유 불러오기, 사유 미선택,
// 요청 본문, 보내는 중 잠금, 성공, label별 실패 문구와 닫힘/유지, 401, 300자 제한)을 본다.
// 양식은 글·댓글·중고 글·대화에서 같이 쓰므로 부품 하나만 띄워(harness) label을 바꿔 가며 본다.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/report_form.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/screen.dart';

const _reasons = [
  {'code': 'SPAM', 'name': '광고·스팸'},
  {'code': 'ABUSE', 'name': '욕설·비방'},
];

// 띄운 양식과 가짜 서버, 부모에게 "로그인 안내를 띄워 달라"고 알린 횟수, 서버가 받은 신고 본문들
class _Form {
  _Form(this.adapter);
  final FakeAdapter adapter;
  int needLogin = 0;
  final List<Map<dynamic, dynamic>> bodies = [];
}

const _url = '/posts/7/reports';

// 사유 목록 응답은 기본으로 성공. 신고(POST) 응답은 테스트가 .on 으로 더한다
FakeAdapter _adapter({FakeReply? reasons}) => FakeAdapter()..on('GET', '/report-reasons', reasons ?? FakeReply(200, _reasons));

Future<_Form> _pump(WidgetTester tester, FakeAdapter adapter, {String label = '글'}) async {
  useTallScreen(tester);
  final form = _Form(adapter);
  adapter.onRequestBody = (data) {
    if (data is Map) form.bodies.add(data);
  };
  final api = ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl);
  await tester.pumpWidget(
    Provider<ApiClient>.value(
      value: api,
      child: MaterialApp(
        theme: buildAppTheme(),
        home: Scaffold(
          body: SingleChildScrollView(
            child: ReportForm(url: _url, label: label, onNeedLogin: () => form.needLogin++),
          ),
        ),
      ),
    ),
  );
  return form;
}

final _openButton = find.widgetWithText(OutlinedButton, '신고');
final _sendButton = find.widgetWithText(OutlinedButton, '신고 보내기');
final _sendingButton = find.widgetWithText(OutlinedButton, '보내는 중...');
final _cancelButton = find.widgetWithText(OutlinedButton, '취소');
final _retryButton = find.widgetWithText(OutlinedButton, '다시 시도');
final _detailField = find.byKey(const ValueKey('report-detail'));
final _reasonSelect = find.byKey(const ValueKey('report-reason'));

Future<void> _open(WidgetTester tester) async {
  await tester.tap(_openButton);
  await tester.pumpAndSettle();
}

Future<void> _pickReason(WidgetTester tester, String name) async {
  await tester.tap(_reasonSelect);
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

Future<void> _send(WidgetTester tester) async {
  await tester.tap(_sendButton);
  await tester.pumpAndSettle();
}

String _detailText(WidgetTester tester) => tester.widget<TextField>(_detailField).controller!.text;

// 열고 사유(SPAM)를 골라 둔 상태까지 간다
Future<void> _ready(WidgetTester tester) async {
  await _open(tester);
  await _pickReason(tester, '광고·스팸');
}

void main() {
  group('열기·닫기', () {
    testWidgets('처음엔 [신고] 버튼만 있고 사유 목록도 아직 안 받는다', (tester) async {
      final adapter = _adapter();
      await _pump(tester, adapter);
      expect(_openButton, findsOneWidget);
      expect(_sendButton, findsNothing);
      expect(_detailField, findsNothing);
      expect(adapter.count('GET', '/report-reasons'), 0);
    });

    for (final label in const ['글', '댓글', '중고 글', '대화']) {
      testWidgets('[신고] 버튼의 읽어 주는 이름은 "$label 신고"', (tester) async {
        final handle = tester.ensureSemantics();
        await _pump(tester, _adapter(), label: label);
        final data = tester.getSemantics(find.bySemanticsLabel('$label 신고')).getSemanticsData();
        expect(data.flagsCollection.isButton, isTrue);
        handle.dispose();
      });
    }

    testWidgets('열면 [신고] 버튼은 사라지고 양식이 나온다: 사유 드롭다운("사유를 고르세요"), 상세 칸, 0/300, 보내기, 취소', (tester) async {
      final handle = tester.ensureSemantics();
      await _pump(tester, _adapter());
      await _open(tester);
      expect(_openButton, findsNothing);
      expect(find.text('사유를 고르세요'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('^신고 사유')), findsOneWidget); // 드롭다운 이름 (고른 값 글자가 뒤에 붙을 수 있다)
      expect(find.bySemanticsLabel(RegExp(r'^신고 상세 내용 \(선택\)')), findsOneWidget); // 입력칸 이름 (placeholder가 뒤에 붙을 수 있다)
      expect(find.text('자세한 내용 (선택)'), findsOneWidget); // placeholder
      expect(tester.widget<TextField>(_detailField).minLines, 2);
      expect(tester.widget<TextField>(_detailField).maxLines, 2);
      expect(find.text('0/300'), findsOneWidget);
      expect(_sendButton, findsOneWidget);
      expect(_cancelButton, findsOneWidget);
      handle.dispose();
    });

    testWidgets('사유 목록은 처음 열 때 한 번만 받는다: 취소하고 다시 열어도 요청 없음, 목록 유지', (tester) async {
      final adapter = _adapter();
      await _pump(tester, adapter);
      await _open(tester);
      await tester.tap(_cancelButton);
      await tester.pumpAndSettle();
      expect(_openButton, findsOneWidget);
      await _open(tester);
      expect(adapter.count('GET', '/report-reasons'), 1);
      expect(find.text('사유를 고르세요'), findsOneWidget);
    });

    testWidgets('[취소]는 닫기만 한다: 고른 사유·쓴 내용은 다시 열면 그대로, 요청 없음', (tester) async {
      final adapter = _adapter();
      await _pump(tester, adapter);
      await _ready(tester);
      await tester.enterText(_detailField, '쓰던 내용');
      await tester.tap(_cancelButton);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', _url), 0);
      await _open(tester);
      expect(_detailText(tester), '쓰던 내용');
      expect(find.text('광고·스팸'), findsOneWidget); // 고른 사유가 그대로
      expect(find.text('5/300'), findsOneWidget);
    });
  });

  group('사유 목록 불러오기', () {
    testWidgets('불러오는 동안 "사유 불러오는 중..."이 보이고 보내기는 비활성, 끝나면 드롭다운', (tester) async {
      final gate = Completer<void>();
      await _pump(tester, _adapter(reasons: FakeReply(200, _reasons, gate)));
      await tester.tap(_openButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('사유 불러오는 중...'), findsOneWidget);
      expect(_reasonSelect, findsNothing);
      expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('사유 불러오는 중...'), findsNothing);
      expect(_reasonSelect, findsOneWidget);
      expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNotNull);
    });

    testWidgets('드롭다운에는 "사유를 고르세요" 뒤에 사유들이 순서대로 있다', (tester) async {
      await _pump(tester, _adapter());
      await _open(tester);
      final items = tester.widget<DropdownButton<String>>(_reasonSelect).items!;
      expect([for (final i in items) (i.value, (i.child as Text).data)], [
        ('', '사유를 고르세요'),
        ('SPAM', '광고·스팸'),
        ('ABUSE', '욕설·비방'),
      ]);
    });

    testWidgets('실패하면 "신고 사유를 불러오지 못했습니다"+[다시 시도], 드롭다운 없음, 보내기 비활성', (tester) async {
      await _pump(tester, _adapter(reasons: FakeReply(500)));
      await _open(tester);
      expect(find.text('신고 사유를 불러오지 못했습니다'), findsOneWidget);
      expect(tester.widget<Notice>(find.widgetWithText(Notice, '신고 사유를 불러오지 못했습니다')).kind, NoticeKind.error);
      expect(_retryButton, findsOneWidget);
      expect(_reasonSelect, findsNothing);
      expect(find.text('사유 불러오는 중...'), findsNothing);
      expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNull);
    });

    testWidgets('실패 중에 [보내기]를 눌러도 요청이 없다', (tester) async {
      final adapter = _adapter(reasons: FakeReply(500));
      await _pump(tester, adapter);
      await _open(tester);
      await tester.tap(_sendButton, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', _url), 0);
    });

    testWidgets('[다시 시도]가 성공하면 실패 문구가 사라지고 드롭다운·보내기 활성', (tester) async {
      final adapter = _adapter(reasons: FakeReply(500))..on('GET', '/report-reasons', FakeReply(200, _reasons));
      await _pump(tester, adapter);
      await _open(tester);
      await tester.tap(_retryButton);
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/report-reasons'), 2);
      expect(find.text('신고 사유를 불러오지 못했습니다'), findsNothing);
      expect(_reasonSelect, findsOneWidget);
      expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNotNull);
    });

    testWidgets('네트워크 오류도 같은 실패 화면 (문구는 서버 연결 문구)', (tester) async {
      await _pump(tester, _adapter(reasons: FakeReply.networkError()));
      await _open(tester);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_retryButton, findsOneWidget);
    });
  });

  group('보내기', () {
    testWidgets('사유를 안 골랐으면 "신고 사유를 골라 주세요"만 보이고 요청은 없다, 양식은 그대로', (tester) async {
      final adapter = _adapter();
      await _pump(tester, adapter);
      await _open(tester);
      await _send(tester);
      expect(find.text('신고 사유를 골라 주세요'), findsOneWidget);
      expect(adapter.count('POST', _url), 0);
      expect(_sendButton, findsOneWidget);
    });

    testWidgets('사유만 고르면 본문은 {reason}뿐 (detail 키 없음)', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply(201));
      final form = await _pump(tester, adapter);
      await _ready(tester);
      await _send(tester);
      expect(form.bodies, [
        {'reason': 'SPAM'},
      ]);
    });

    testWidgets('상세 내용은 앞뒤 공백을 자르고 보낸다', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply(201));
      final form = await _pump(tester, adapter);
      await _ready(tester);
      await tester.enterText(_detailField, '  광고 같아요  ');
      await _send(tester);
      expect(form.bodies, [
        {'reason': 'SPAM', 'detail': '광고 같아요'},
      ]);
    });

    testWidgets('공백뿐인 상세 내용은 detail 키를 생략한다', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply(201));
      final form = await _pump(tester, adapter);
      await _ready(tester);
      await tester.enterText(_detailField, '    ');
      await _send(tester);
      expect(form.bodies, [
        {'reason': 'SPAM'},
      ]);
    });

    testWidgets('다른 사유(욕설·비방)를 고르면 그 code를 보낸다', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply(201));
      final form = await _pump(tester, adapter);
      await _open(tester);
      await _pickReason(tester, '욕설·비방');
      await _send(tester);
      expect(form.bodies.single['reason'], 'ABUSE');
    });

    testWidgets('보내는 동안 "보내는 중..."으로 잠기고 취소도 비활성, 요청은 한 번뿐', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', _url, FakeReply(201, null, gate));
      await _pump(tester, adapter);
      await _ready(tester);
      await tester.tap(_sendButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_sendingButton, findsOneWidget);
      expect(tester.widget<OutlinedButton>(_sendingButton).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_cancelButton).onPressed, isNull);
      await tester.tap(_sendingButton, warnIfMissed: false); // 또 눌러도
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', _url), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(adapter.count('POST', _url), 1);
    });

    testWidgets('성공: "신고가 접수됐습니다"(error 스타일 그대로), 양식이 닫히고 [신고] 버튼은 다시 안 나온다', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply(201));
      await _pump(tester, adapter);
      await _ready(tester);
      await _send(tester);
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
      expect(tester.widget<Notice>(find.widgetWithText(Notice, '신고가 접수됐습니다')).kind, NoticeKind.error);
      expect(_openButton, findsNothing);
      expect(_sendButton, findsNothing);
      expect(_detailField, findsNothing);
    });

    testWidgets('실패 뒤 다시 보내면 이전 안내 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('POST', _url, FakeReply(500))
        ..on('POST', _url, FakeReply(201));
      await _pump(tester, adapter);
      await _ready(tester);
      await _send(tester);
      expect(find.text('신고하지 못했습니다'), findsOneWidget);
      await _send(tester);
      expect(find.text('신고하지 못했습니다'), findsNothing);
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
    });

    testWidgets('사유를 안 골라 생긴 안내는 다시 열면 지워진다', (tester) async {
      await _pump(tester, _adapter());
      await _open(tester);
      await _send(tester);
      expect(find.text('신고 사유를 골라 주세요'), findsOneWidget);
      await tester.tap(_cancelButton);
      await tester.pumpAndSettle();
      await _open(tester);
      expect(find.text('신고 사유를 골라 주세요'), findsNothing);
    });
  });

  group('실패 문구 (label별)', () {
    // (label, 상태, 기대 문구, 양식이 닫히는지(done))
    final cases = <(String, int, String, bool)>[
      ('글', 409, '이미 신고한 글입니다', true),
      ('글', 404, '이미 삭제된 글입니다', true),
      ('글', 403, ErrorMessages.schoolGatePostAction, false),
      ('글', 400, '내용을 확인해 주세요 (자기 것은 신고할 수 없고, 사유는 필수입니다)', false),
      ('글', 500, '신고하지 못했습니다', false),
      ('댓글', 409, '이미 신고한 댓글입니다', true),
      ('댓글', 404, '이미 삭제된 댓글입니다', true),
      ('댓글', 403, ErrorMessages.schoolGatePostAction, false),
      ('댓글', 400, '내용을 확인해 주세요 (자기 것은 신고할 수 없고, 사유는 필수입니다)', false),
      ('중고 글', 409, '이미 신고한 중고 글입니다', true),
      ('중고 글', 404, '이미 삭제된 중고 글입니다', true),
      ('중고 글', 403, ErrorMessages.schoolGatePostAction, false),
      ('대화', 409, '이미 신고한 대화입니다', true),
      ('대화', 404, '대화를 찾을 수 없습니다', true),
      ('대화', 403, ErrorMessages.visaSchoolRequiredRetry, false),
      ('대화', 400, '신고 사유를 확인해 주세요', false),
      ('대화', 500, '신고하지 못했습니다', false),
    ];
    for (final (label, status, message, closes) in cases) {
      testWidgets('$label $status → "$message" (${closes ? '닫힘, 다시 못 엶' : '양식 유지'})', (tester) async {
        final adapter = _adapter()..on('POST', _url, FakeReply(status));
        await _pump(tester, adapter, label: label);
        await _ready(tester);
        await tester.enterText(_detailField, '내용 유지');
        await _send(tester);

        expect(find.text(message), findsOneWidget);
        expect(tester.widget<Notice>(find.widgetWithText(Notice, message)).kind, NoticeKind.error);
        if (closes) {
          expect(_sendButton, findsNothing);
          expect(_openButton, findsNothing); // 다시 못 연다
        } else {
          expect(_sendButton, findsOneWidget);
          expect(_detailText(tester), '내용 유지'); // 입력 유지
          expect(find.text('광고·스팸'), findsOneWidget); // 고른 사유 유지
          expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNotNull); // 다시 보낼 수 있다
        }
      });
    }

    testWidgets('네트워크 오류 → "서버에 연결할 수 없습니다", 양식 유지', (tester) async {
      final adapter = _adapter()..on('POST', _url, FakeReply.networkError());
      await _pump(tester, adapter);
      await _ready(tester);
      await _send(tester);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_sendButton, findsOneWidget);
    });

    testWidgets('401(재발급도 401) → 일반 실패 문구 없이 부모에게 알리고(onNeedLogin 1번), 양식·입력 유지', (tester) async {
      final adapter = _adapter()
        ..on('POST', _url, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final form = await _pump(tester, adapter);
      await _ready(tester);
      await tester.enterText(_detailField, '내용 유지');
      await tester.tap(_sendButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(form.needLogin, 1);
      expect(find.text('신고하지 못했습니다'), findsNothing);
      expect(find.text('다시 로그인해 주세요'), findsNothing); // 안내는 부모(글 상세)의 몫
      expect(_sendButton, findsOneWidget);
      expect(_detailText(tester), '내용 유지');
      expect(tester.widget<OutlinedButton>(_sendButton).onPressed, isNotNull);
    });
  });

  group('상세 내용 300자', () {
    testWidgets('300자까지만 들어가고 카운터는 N/300', (tester) async {
      await _pump(tester, _adapter());
      await _open(tester);
      await tester.enterText(_detailField, '가' * 350);
      await tester.pump();
      expect(_detailText(tester).length, 300);
      expect(find.text('300/300'), findsOneWidget);
    });

    testWidgets('이모지(UTF-16으로 2칸)가 섞여도 UTF-16 길이로 300까지, 반쪽 이모지는 남지 않는다', (tester) async {
      await _pump(tester, _adapter());
      await _open(tester);
      await tester.enterText(_detailField, '😀' * 200); // 길이 400
      await tester.pump();
      expect(_detailText(tester).length, 300);
      expect(find.text('300/300'), findsOneWidget); // 카운터도 text.length 기준
      // 한도 경계(300번째 칸)에서 이모지가 쪼개지는 경우: 299까지만 남긴다
      await tester.enterText(_detailField, 'a${'😀' * 200}');
      await tester.pump();
      expect(_detailText(tester).length, 299);
      expect(find.text('299/300'), findsOneWidget);
    });

    testWidgets('글자를 쓸 때마다 카운터가 바뀐다 (공백도 센다)', (tester) async {
      await _pump(tester, _adapter());
      await _open(tester);
      expect(find.text('0/300'), findsOneWidget);
      await tester.enterText(_detailField, '안녕 하세요');
      await tester.pump();
      expect(find.text('6/300'), findsOneWidget);
    });
  });

  group('글 상세 안에서', () {
    // 상세 화면과 이어 붙었는지는 post_detail_page_test의 조건 표가 보고, 여기서는 URL만 한 번 더 확인한다
    testWidgets('label이 같아도 url이 다르면 그 url로 보낸다', (tester) async {
      final adapter = _adapter()..on('POST', '/comments/9/reports', FakeReply(201));
      useTallScreen(tester);
      final api = ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl);
      await tester.pumpWidget(
        Provider<ApiClient>.value(
          value: api,
          child: MaterialApp(
            theme: buildAppTheme(),
            home: Scaffold(body: ReportForm(url: '/comments/9/reports', label: '댓글', onNeedLogin: () {})),
          ),
        ),
      );
      await _ready(tester);
      await _send(tester);
      expect(adapter.count('POST', '/comments/9/reports'), 1);
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
    });
  });
}
