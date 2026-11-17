// S20-g4a 관리자 "인증 심사" 화면의 반려 기능 테스트:
// [반려] 버튼 노출 규칙, 사유 양식, 확인 창, PUT 요청, 성공·실패 안내, 연타 방지.
import 'dart:async';

import 'package:board_web/admin/permit_decision_form.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _list = '/admin/permit-requests';
const _myId = 9; // adminUser()의 id

// 코드 → 드롭다운에 보이는 문구 (EXPIRED는 관리자 선택지에 없다)
const _reasonText = {
  'BLURRY': '사진이 흐려서 글자를 읽을 수 없어요',
  'NOT_PERMIT': '표준입학허가서가 아니에요',
  'NOT_MASKED': '가려야 할 정보가 보여요. 가리고 다시 올려 주세요',
  'UNSUPPORTED_SCHOOL': '아직 지원하지 않는 학교예요',
  'DUPLICATE': '이미 다른 계정에서 인증한 허가서예요',
  'OTHER': '확인할 수 없었어요. 다시 신청해 주세요',
};
const _expiredText = '14일 안에 확인하지 못했어요. 다시 신청해 주세요';

Map<String, dynamic> _req({
  int id = 1,
  int userId = 100,
  String nickname = '민수',
  String status = 'PENDING',
  String? schoolName,
  String? rejectReason,
  bool hasPhoto = true,
}) => {
  'id': id,
  'userId': userId,
  'nickname': nickname,
  'status': status,
  'createdAt': '2026-11-01T03:04:05Z',
  'schoolName': schoolName,
  'rejectReason': rejectReason,
  'detectedSchoolId': null,
  'detectedSchoolName': null,
  'machineResult': 'UNREADABLE',
  'autoDecided': false,
  'hasPhoto': hasPhoto,
};

String _rejectPath(int id) => '/admin/permit-requests/$id/reject';

// PUT 요청 본문을 모아 두는 가짜 서버
class _Recorder extends FakeAdapter {
  final List<Object?> putBodies = [];
  _Recorder() {
    onRequestBody = (data) {
      if (calls.isNotEmpty && calls.last.method == 'PUT') putBodies.add(data);
    };
  }
}

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

Future<TestApp> _open(WidgetTester tester, FakeAdapter a) =>
    pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: a);

Finder _row(int id) => find.byKey(ValueKey('permit-$id'));
Finder _btnIn(int id, String text) => find.descendant(of: _row(id), matching: find.widgetWithText(OutlinedButton, text));
Finder _dialogBtn(String text) =>
    find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, text));
Finder _formBtn(String text) =>
    find.descendant(of: find.byType(PermitRejectForm), matching: find.widgetWithText(OutlinedButton, text));
Finder _reasonField() => find.byKey(const ValueKey('permit-reject-reason'));
Finder _noticeOf(String m) => find.ancestor(of: find.text(m), matching: find.byType(Notice));

// 1번 줄의 [반려] → 사유 고르기 (code가 null이면 안 고름)
Future<void> _openForm(WidgetTester tester, {int id = 1, String? code}) async {
  await tester.tap(_btnIn(id, '반려'));
  await tester.pumpAndSettle();
  if (code != null) {
    await tester.tap(_reasonField());
    await tester.pumpAndSettle();
    await tester.tap(find.text(_reasonText[code]!).last);
    await tester.pumpAndSettle();
  }
}

// [반려하기] → 확인 창의 [반려]까지 누른다
Future<void> _submitAndConfirm(WidgetTester tester) async {
  await tester.tap(_formBtn('반려하기'));
  await tester.pumpAndSettle();
  await tester.tap(_dialogBtn('반려'));
  await tester.pumpAndSettle();
}

void main() {
  group('조건1·2 [반려] 버튼 노출', () {
    _test('대기 탭에서 남의 신청 줄에만 [반려]가 있고, 승인·반려 탭 줄에는 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, userId: 100)]))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, userId: 100, status: 'APPROVED', schoolName: '가나대')]))
        ..on('GET', _list, FakeReply(200, [_req(id: 3, userId: 100, status: 'REJECTED', rejectReason: 'BLURRY')]));
      await _open(tester, a);
      expect(_btnIn(1, '반려'), findsOneWidget);
      await tester.tap(find.text('승인'));
      await tester.pumpAndSettle();
      expect(_row(2), findsOneWidget);
      expect(_btnIn(2, '반려'), findsNothing);
      await tester.tap(find.text('반려').first); // 탭 글자(아래 별도 테스트로 .first가 탭임을 확인)
      await tester.pumpAndSettle();
      expect(_row(3), findsOneWidget);
      expect(_btnIn(3, '반려'), findsNothing);
    });

    _test('대기 탭에서 .first로 찾은 "반려"는 탭 글자이고 버튼 글자가 아니다(기존 탭 색 헬퍼가 맞는 곳을 본다)', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      expect(find.text('반려'), findsNWidgets(2)); // 탭 + 줄 버튼
      final first = find.text('반려').first;
      expect(find.ancestor(of: first, matching: find.byType(OutlinedButton)), findsNothing); // 버튼 안이 아니다
      expect(tester.getTopLeft(first).dy, lessThan(tester.getTopLeft(_row(1)).dy)); // 줄보다 위(탭 줄)
    });

    _test('내 신청(userId == 내 id) 줄은 [반려] 없이 안내만 보이고 [사진 보기]는 그대로 있다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, userId: _myId), _req(id: 2, userId: 100)]));
      await _open(tester, a);
      expect(find.descendant(of: _row(1), matching: find.text('본인 신청은 직접 심사할 수 없어요')), findsOneWidget);
      expect(_btnIn(1, '반려'), findsNothing);
      expect(_btnIn(1, '사진 보기'), findsOneWidget);
      // 남의 신청 줄에는 안내가 없다
      expect(find.descendant(of: _row(2), matching: find.text('본인 신청은 직접 심사할 수 없어요')), findsNothing);
      expect(_btnIn(2, '반려'), findsOneWidget);
    });
  });

  group('조건3 반려 양식', () {
    _test('[반려]를 누르면 사유 드롭다운에 6개 선택지가 있고 EXPIRED 문구는 없다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      await _openForm(tester);
      expect(find.byType(PermitRejectForm), findsOneWidget);
      // 자리표시 1개 + 사유 6개 = 7개 항목
      final dd = tester.widget<DropdownButton<String>>(_reasonField());
      expect(dd.items!.length, 7);
      expect(dd.items!.map((i) => i.value), ['', ..._reasonText.keys]);
      await tester.tap(_reasonField());
      await tester.pumpAndSettle();
      for (final t in _reasonText.values) {
        expect(find.text(t), findsWidgets, reason: t);
      }
      expect(find.text(_expiredText), findsNothing);
    });

    _test('사유 없이 [반려하기]를 누르면 "반려 사유를 골라 주세요"가 뜨고 확인 창·PUT은 없다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      await _openForm(tester);
      await tester.tap(_formBtn('반려하기'));
      await tester.pumpAndSettle();
      expect(find.text('반려 사유를 골라 주세요'), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
      expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
    });

    _test('[취소]를 누르면 양식이 닫히고 다시 [반려] 버튼이 보인다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      await _openForm(tester);
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(PermitRejectForm), findsNothing);
      expect(_btnIn(1, '반려'), findsOneWidget);
    });
  });

  group('조건4 확인 창', () {
    _test('사유를 고르고 [반려하기]를 누르면 "{닉네임} 님의 신청을 반려할까요?" 확인 창이 뜬다. 취소면 PUT이 없다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1, nickname: '민수')]));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await tester.tap(_formBtn('반려하기'));
      await tester.pumpAndSettle();
      expect(find.text('민수 님의 신청을 반려할까요?'), findsOneWidget);
      await tester.tap(_dialogBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
      expect(find.byType(PermitRejectForm), findsOneWidget); // 양식은 그대로
      expect(a.count('GET', _list), 1);
    });

    _test('확인하면 PUT /admin/permit-requests/{id}/reject가 1건, 본문은 {"reason":"<코드>"}', (tester) async {
      final a = _Recorder()
        ..on('GET', _list, FakeReply(200, [_req(id: 4, nickname: '영희')]))
        ..on('PUT', _rejectPath(4), FakeReply(200, {}))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, a);
      await _openForm(tester, id: 4, code: 'NOT_MASKED');
      await _submitAndConfirm(tester);
      expect(a.callsTo('PUT', _rejectPath(4)).length, 1);
      expect(a.calls.where((c) => c.method == 'PUT').length, 1);
      expect(a.putBodies, [
        {'reason': 'NOT_MASKED'},
      ]);
      expect(a.callsTo('PUT', _rejectPath(4)).single.receiveTimeout, isNotNull); // 시간 제한이 걸린다
    });
  });

  group('조건5 성공', () {
    _test('200이면 "반려했어요"가 뜨고 대기 목록을 한 번 더 읽어 그 줄이 사라진다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, nickname: '민수'), _req(id: 2, nickname: '영희')]))
        ..on('PUT', _rejectPath(1), FakeReply(200, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openForm(tester, code: 'OTHER');
      await _submitAndConfirm(tester);
      expect(find.text('반려했어요'), findsOneWidget);
      expect(tester.widget<Notice>(_noticeOf('반려했어요')).kind, NoticeKind.success);
      expect(a.count('GET', _list), 2);
      expect(a.callsTo('GET', _list).last.query['status'], 'PENDING');
      expect(_row(1), findsNothing);
      expect(_row(2), findsOneWidget);
      expect(find.byType(PermitRejectForm), findsNothing);
    });

    _test('성공 안내는 탭을 바꾸면 사라진다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('PUT', _rejectPath(1), FakeReply(200, {}))
        ..on('GET', _list, FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await _submitAndConfirm(tester);
      expect(find.text('반려했어요'), findsOneWidget);
      await tester.tap(find.text('승인'));
      await tester.pumpAndSettle();
      expect(find.text('반려했어요'), findsNothing);
    });
  });

  group('조건6 연타 방지', () {
    _test('요청 중에는 반려하기·취소·다른 줄 [반려]·탭이 막히고, 연타해도 PUT은 1건이다', (tester) async {
      final gate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('PUT', _rejectPath(1), FakeReply(200, {}, gate))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await tester.tap(_formBtn('반려하기'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('반려'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500)); // 확인 창이 닫히고 PUT이 붙잡힌 상태
      expect(a.count('PUT', _rejectPath(1)), 1);
      // 버튼 비활성
      expect(tester.widget<OutlinedButton>(_formBtn('반려하기')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_formBtn('취소')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_btnIn(2, '반려')).onPressed, isNull);
      // 연타·탭 누르기: 아무 일도 없다
      await tester.tap(_formBtn('반려하기'), warnIfMissed: false);
      await tester.tap(_formBtn('반려하기'), warnIfMissed: false);
      await tester.tap(find.text('승인'), warnIfMissed: false);
      await tester.pump();
      expect(find.byType(Dialog), findsNothing);
      expect(a.count('PUT', _rejectPath(1)), 1);
      expect(a.count('GET', _list), 1); // 탭이 안 눌려 목록 조회도 늘지 않았다
      gate.complete();
      await tester.pumpAndSettle();
      expect(a.count('PUT', _rejectPath(1)), 1);
      expect(a.count('GET', _list), 2);
      expect(_row(1), findsNothing);
      // 끝나면 다시 눌 수 있다
      expect(tester.widget<OutlinedButton>(_btnIn(2, '반려')).onPressed, isNotNull);
    });
  });

  group('조건7 실패 안내', () {
    // (이름, 응답, 기대 문구, 목록 재조회 여부) 4칸
    final cases = <(String, FakeReply, String, bool)>[
      ('400', FakeReply(400, {}), '반려 사유를 확인해 주세요', false),
      ('403', FakeReply(403, {}), '처리하지 못했어요. 새로고침 후 다시 시도해 주세요', false),
      ('404', FakeReply(404, {}), '이미 사라진 신청이에요 (철회했거나 탈퇴했을 수 있어요)', true),
      ('409', FakeReply(409, {}), '이미 처리된 신청이에요', true),
      ('응답 없음', FakeReply.networkError(), '서버에 연결할 수 없습니다', false),
      ('시간 초과', FakeReply.timeout(), '처리됐는지 확인하지 못했어요. 목록에서 확인해 주세요', true),
      ('500', FakeReply(500, {}), '처리하지 못했습니다', false),
    ];
    for (final (name, reply, message, refetch) in cases) {
      _test('PUT이 $name이면 "$message"가 뜨고 목록 재조회는 ${refetch ? "1건 더 간다" : "가지 않는다"}', (tester) async {
        final a = FakeAdapter()
          ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
          ..on('PUT', _rejectPath(1), reply);
        if (refetch) a.on('GET', _list, FakeReply(200, [_req(id: 1)]));
        await _open(tester, a);
        await _openForm(tester, code: 'BLURRY');
        await _submitAndConfirm(tester);
        expect(find.text(message), findsOneWidget);
        expect(tester.widget<Notice>(_noticeOf(message)).kind, NoticeKind.error);
        expect(a.count('PUT', _rejectPath(1)), 1);
        expect(a.count('GET', _list), refetch ? 2 : 1);
        expect(find.text('반려했어요'), findsNothing);
        // 실패하면 양식은 남아 있어 다시 시도할 수 있고, 버튼은 다시 눌린다
        expect(find.byType(PermitRejectForm), findsOneWidget);
        expect(tester.widget<OutlinedButton>(_formBtn('반려하기')).onPressed, isNotNull);
      });
    }

    _test('PUT이 401(재발급도 실패)이면 "다시 로그인해 주세요" 안내가 보인다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('PUT', _rejectPath(1), FakeReply(401, {}))
        ..on('POST', '/auth/refresh', FakeReply(401, {}));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await _submitAndConfirm(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('반려했어요'), findsNothing);
      expect(a.count('PUT', _rejectPath(1)), 1);
    });

    _test('404로 재조회한 새 목록에는 사라진 줄이 반영된다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('PUT', _rejectPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await _submitAndConfirm(tester);
      expect(_row(1), findsNothing);
      expect(_row(2), findsOneWidget);
      expect(find.text('이미 사라진 신청이에요 (철회했거나 탈퇴했을 수 있어요)'), findsOneWidget);
    });
  });

  group('경계: 늦은 응답·겹치는 상황', () {
    // 화면을 떠난 뒤 PUT 응답이 와도 예외가 없다 (dispose 뒤 setState 없음)
    for (final (name, reply) in <(String, FakeReply Function(Completer<void>))>[
      ('성공(200)', (g) => FakeReply(200, {}, g)),
      ('실패(500)', (g) => FakeReply(500, {}, g)),
    ]) {
      _test('PUT 응답이 붙잡힌 동안 다른 화면으로 가도 늦은 $name 응답이 예외를 내지 않고 재조회도 없다', (tester) async {
        final gate = Completer<void>();
        final a = FakeAdapter()
          ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
          ..on('PUT', _rejectPath(1), reply(gate));
        final app = await _open(tester, a);
        await _openForm(tester, code: 'BLURRY');
        await tester.tap(_formBtn('반려하기'));
        await tester.pumpAndSettle();
        await tester.tap(_dialogBtn('반려'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 500)); // PUT이 붙잡힌 상태
        expect(a.count('PUT', _rejectPath(1)), 1);
        app.router.go('/login'); // 화면을 떠난다 (준비 안 된 요청이 없는 가벼운 화면)
        await tester.pumpAndSettle();
        expect(find.byType(PermitRejectForm), findsNothing);
        gate.complete();
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(a.count('GET', _list), 1); // 떠난 화면은 목록을 다시 읽지 않는다
        expect(find.text('반려했어요'), findsNothing);
      });
    }

    _test('PUT 시간 초과 뒤 재조회까지 실패하면 시간 초과 문구와 목록 실패 안내가 같이 보이고 앱은 죽지 않는다', (tester) async {
      const timeoutMsg = '처리됐는지 확인하지 못했어요. 목록에서 확인해 주세요';
      const listFail = '인증 신청 목록을 불러오지 못했습니다';
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('PUT', _rejectPath(1), FakeReply.timeout())
        ..on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await _openForm(tester, code: 'BLURRY');
      await _submitAndConfirm(tester);
      expect(tester.takeException(), isNull);
      expect(a.count('PUT', _rejectPath(1)), 1);
      expect(a.count('GET', _list), 2);
      // 두 안내가 한 번씩, 둘 다 오류 모양으로 보인다
      expect(find.text(timeoutMsg), findsOneWidget);
      expect(tester.widget<Notice>(_noticeOf(timeoutMsg)).kind, NoticeKind.error);
      expect(find.text(listFail), findsOneWidget);
      // 성공으로 오해할 문구는 없다
      expect(find.text('반려했어요'), findsNothing);
      // 목록을 못 읽었으니 줄은 없고, [다시 시도]가 있다
      expect(_row(1), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsOneWidget);
      // 버튼은 다시 눌린다(_busy가 풀렸다): 다시 시도하면 목록이 돌아오고 시간 초과 안내는 남는다
      a.on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await tester.tap(find.widgetWithText(OutlinedButton, '다시 시도'));
      await tester.pumpAndSettle();
      expect(find.text(listFail), findsNothing);
      expect(_row(1), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    // 확인 창이 떠 있는 동안 사진 404 재조회로 그 줄이 사라지고, 그 뒤 [반려]를 누르는 경우
    _test('확인 창이 떠 있는 동안 줄이 사라져도 [반려]를 누르면 예외 없이 PUT이 가고 409면 재조회한다', (tester) async {
      final photoGate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('GET', '/admin/permit-requests/1/photo', FakeReply(404, {}, photoGate))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')])) // 사진 404 재조회: 1번 줄 없음
        ..on('PUT', _rejectPath(1), FakeReply(409, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await tester.tap(_btnIn(1, '사진 보기')); // 사진 요청이 붙잡힌다
      await tester.pump();
      await tester.tap(_btnIn(1, '반려'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('permit-reject-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text(_reasonText['BLURRY']!).last);
      await tester.pumpAndSettle();
      await tester.tap(_formBtn('반려하기'));
      await tester.pumpAndSettle();
      expect(find.text('민수 님의 신청을 반려할까요?'), findsOneWidget); // 확인 창이 떠 있다
      // 창이 떠 있는 동안 사진이 404로 돌아와 목록이 다시 읽힌다
      photoGate.complete();
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing); // 그 줄은 목록에서 사라졌다
      expect(find.byType(Dialog), findsOneWidget); // 확인 창은 그대로
      expect(a.count('GET', _list), 2);
      // 이제 확인을 누른다
      await tester.tap(_dialogBtn('반려'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      // 줄이 없어져도 PUT은 그대로 한 번 나가고, 서버가 409로 답하면 처리됨 안내와 재조회
      expect(a.count('PUT', _rejectPath(1)), 1);
      expect(find.text('이미 처리된 신청이에요'), findsOneWidget);
      expect(a.count('GET', _list), 3);
      expect(find.text('반려했어요'), findsNothing);
      expect(_row(2), findsOneWidget);
    });
  });

  group('조건8 사진 보기 404', () {
    _test('사진이 404면 안내가 뜨고 목록을 1번 다시 읽는다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 3)]))
        ..on('GET', '/admin/permit-requests/3/photo', FakeReply(404, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 3)]));
      await _open(tester, a);
      await tester.tap(_btnIn(3, '사진 보기'));
      await tester.pumpAndSettle();
      expect(find.text('사진이 없어요(이미 처리되었을 수 있어요)'), findsOneWidget);
      expect(a.count('GET', _list), 2);
    });
  });
}
