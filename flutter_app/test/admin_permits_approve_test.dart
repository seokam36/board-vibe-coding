// S20-g4b 관리자 "인증 심사" 화면의 승인 기능 테스트:
// [승인] 버튼 노출, 승인 양식(학교 드롭다운 + 허가서 번호), 학교 목록 1회 조회, 확인 창, PUT 요청,
// 성공·실패 안내(양식 안 표시), 재조회 실패 처리, 연타 방지, 반려 회귀, 로그에 번호가 안 남는지.
import 'dart:async';

import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/permit/permit_approve_form.dart';
import 'package:board_web/permit/permit_reject_form.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _list = '/admin/permit-requests';
const _schoolsPath = '/schools';
const _myId = 9; // adminUser()의 id

const _okSchools = [
  {'id': 1, 'name': '가나대'},
  {'id': 2, 'name': '다라대'},
];

// 실패 문구들
const _need = '학교와 허가서 번호를 입력해 주세요';
const _msg400 = '허가서 번호를 확인해 주세요 (글자·숫자, 최대 50자)';
const _msg404 = '신청이나 학교를 찾을 수 없어요';
const _msgHandled = '이미 처리된 신청이에요';
const _msgDup = '승인하지 못했어요. 이미 인증된 회원이거나 이미 승인된 허가서 번호예요. 반려(중복)로 정리해 주세요';
const _msgTimeout = '처리됐는지 확인하지 못했어요. 목록에서 확인해 주세요';
const _msgRefreshFail = '목록을 다시 불러오지 못했어요';

Map<String, dynamic> _req({
  int id = 1,
  int userId = 100,
  String nickname = '민수',
  String status = 'PENDING',
  int? detectedSchoolId,
  String? schoolName,
  String? rejectReason,
  bool hasPhoto = false,
}) => {
  'id': id,
  'userId': userId,
  'nickname': nickname,
  'status': status,
  'createdAt': '2026-11-01T03:04:05Z',
  'schoolName': schoolName,
  'rejectReason': rejectReason,
  'detectedSchoolId': detectedSchoolId,
  'detectedSchoolName': null,
  'machineResult': 'UNREADABLE',
  'autoDecided': false,
  'hasPhoto': hasPhoto,
};

String _photoPath(int id) => '/admin/permit-requests/$id/photo';

String _approvePath(int id) => '/admin/permit-requests/$id/approve';

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
// 승인 확인 창의 [승인]은 위험 버튼이 아니라 FilledButton이다
Finder _confirmOk() => find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(FilledButton, '승인'));
Finder _formBtn(String text) =>
    find.descendant(of: find.byType(PermitApproveForm), matching: find.widgetWithText(OutlinedButton, text));
Finder _schoolField() => find.byKey(const ValueKey('permit-approve-school'));
Finder _numberField() => find.byKey(const ValueKey('permit-approve-number'));
Finder _noticeOf(String m) => find.ancestor(of: find.text(m), matching: find.byType(Notice));
Finder _inForm(String text) => find.descendant(of: find.byType(PermitApproveForm), matching: find.text(text));

// 드롭다운의 현재 선택 값 ('' = 미선택)
String _selected(WidgetTester tester) => tester.widget<DropdownButton<String>>(_schoolField()).value ?? '';

// 줄의 [승인]을 눌러 양식을 연다
Future<void> _openApprove(WidgetTester tester, {int id = 1}) async {
  await tester.tap(_btnIn(id, '승인'));
  await tester.pumpAndSettle();
}

// 드롭다운에서 학교 이름 고르기
Future<void> _pickSchool(WidgetTester tester, String name) async {
  await tester.tap(_schoolField());
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

// 학교 고르고 번호 입력
Future<void> _fill(WidgetTester tester, {String school = '가나대', String number = 'AB-123'}) async {
  await _pickSchool(tester, school);
  await tester.enterText(_numberField(), number);
  await tester.pump();
}

// [승인하기] → 확인 창의 [승인]
Future<void> _submitAndConfirm(WidgetTester tester) async {
  await tester.tap(_formBtn('승인하기'));
  await tester.pumpAndSettle();
  await tester.tap(_confirmOk());
  await tester.pumpAndSettle();
}

// 기본 준비: 대기 목록 + 학교 목록
FakeAdapter _base({List<Map<String, dynamic>>? items, FakeAdapter? adapter}) {
  final a = adapter ?? FakeAdapter();
  a.on('GET', _list, FakeReply(200, items ?? [_req(id: 1)]));
  a.on('GET', _schoolsPath, FakeReply(200, _okSchools));
  return a;
}

void main() {
  group('조건1 [승인] 버튼 노출', () {
    _test('대기 탭 남의 줄에만 [승인]이 있고 승인·반려 탭 줄과 본인 줄에는 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, userId: 100), _req(id: 2, userId: _myId)]))
        ..on('GET', _list, FakeReply(200, [_req(id: 3, status: 'APPROVED', schoolName: '가나대')]))
        ..on('GET', _list, FakeReply(200, [_req(id: 4, status: 'REJECTED', rejectReason: 'BLURRY')]));
      await _open(tester, a);
      expect(_btnIn(1, '승인'), findsOneWidget);
      expect(_btnIn(2, '승인'), findsNothing); // 본인 줄
      expect(_btnIn(2, '반려'), findsNothing);
      await tester.tap(find.text('승인').first); // 탭 글자
      await tester.pumpAndSettle();
      expect(_row(3), findsOneWidget);
      expect(_btnIn(3, '승인'), findsNothing);
      await tester.tap(find.text('반려').first);
      await tester.pumpAndSettle();
      expect(_row(4), findsOneWidget);
      expect(_btnIn(4, '승인'), findsNothing);
    });

    _test('대기 탭에서 .first로 찾은 "승인"은 탭 글자이고 버튼 안이 아니다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      expect(find.text('승인'), findsNWidgets(2)); // 탭 + 줄 버튼
      final first = find.text('승인').first;
      expect(find.ancestor(of: first, matching: find.byType(OutlinedButton)), findsNothing);
      expect(tester.getTopLeft(first).dy, lessThan(tester.getTopLeft(_row(1)).dy)); // 줄보다 위
    });
  });

  group('조건2 승인 양식', () {
    _test('[승인]을 누르면 학교 드롭다운·번호칸·[승인하기][취소]가 보인다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      expect(find.byType(PermitApproveForm), findsOneWidget);
      expect(_schoolField(), findsOneWidget);
      expect(_numberField(), findsOneWidget);
      expect(_formBtn('승인하기'), findsOneWidget);
      expect(_formBtn('취소'), findsOneWidget);
      // 선택지: 자리표시 + 학교 2개
      final dd = tester.widget<DropdownButton<String>>(_schoolField());
      expect(dd.items!.map((i) => i.value), ['', '1', '2']);
      expect(_selected(tester), '');
    });

    _test('detectedSchoolId가 목록에 있으면 그 학교가 미리 선택된다', (tester) async {
      final a = _base(items: [_req(id: 1, detectedSchoolId: 2)]);
      await _open(tester, a);
      await _openApprove(tester);
      expect(_selected(tester), '2');
    });

    _test('학교 목록 응답이 늦게 와도 도착하면 detectedSchoolId가 미리 선택된다', (tester) async {
      final gate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, detectedSchoolId: 1)]))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools, gate));
      await _open(tester, a);
      await tester.tap(_btnIn(1, '승인'));
      await tester.pump();
      expect(_schoolField(), findsNothing); // 아직 목록이 없다
      expect(find.text('학교 목록을 불러오는 중…'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_selected(tester), '1');
    });

    _test('detectedSchoolId가 없거나 목록에 없는 id면 미선택이다', (tester) async {
      final a = _base(items: [_req(id: 1, detectedSchoolId: null), _req(id: 2, detectedSchoolId: 99)]);
      await _open(tester, a);
      await _openApprove(tester, id: 1);
      expect(_selected(tester), '');
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      await _openApprove(tester, id: 2);
      expect(_selected(tester), '');
    });

    _test('[취소]를 누르면 양식이 닫히고 [승인] 버튼이 다시 보인다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(PermitApproveForm), findsNothing);
      expect(_btnIn(1, '승인'), findsOneWidget);
    });
  });

  group('조건3 승인·반려 양식은 하나만', () {
    _test('승인 양식을 열면 반려 양식이 닫히고, 반려를 열면 승인 양식이 닫힌다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await tester.tap(_btnIn(1, '반려'));
      await tester.pumpAndSettle();
      expect(find.byType(PermitRejectForm), findsOneWidget);
      await _openApprove(tester);
      expect(find.byType(PermitApproveForm), findsOneWidget);
      expect(find.byType(PermitRejectForm), findsNothing);
      await tester.tap(_btnIn(1, '반려'));
      await tester.pumpAndSettle();
      expect(find.byType(PermitRejectForm), findsOneWidget);
      expect(find.byType(PermitApproveForm), findsNothing);
    });
  });

  group('조건4 학교 목록 1번만 조회·실패 처리', () {
    _test('여러 줄·여러 번 열어도 GET /schools는 1건이다', (tester) async {
      final a = _base(items: [_req(id: 1), _req(id: 2, nickname: '영희')]);
      await _open(tester, a);
      await _openApprove(tester, id: 1);
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      await _openApprove(tester, id: 1);
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      await _openApprove(tester, id: 2);
      expect(a.count('GET', _schoolsPath), 1);
      expect(_selected(tester), '');
    });

    _test('학교 목록 실패면 안내와 [다시 시도]가 보이고 [승인하기]는 비활성이다. 다시 시도가 성공하면 정상이 된다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('GET', _schoolsPath, FakeReply(500, {}))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools));
      await _open(tester, a);
      await _openApprove(tester);
      expect(find.text('학교 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(_formBtn('다시 시도'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_formBtn('승인하기')).onPressed, isNull);
      expect(_schoolField(), findsNothing);
      await tester.tap(_formBtn('다시 시도'));
      await tester.pumpAndSettle();
      expect(a.count('GET', _schoolsPath), 2);
      expect(find.text('학교 목록을 불러오지 못했습니다'), findsNothing);
      expect(_schoolField(), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_formBtn('승인하기')).onPressed, isNotNull);
    });

    _test('학교 목록이 네트워크 오류로 실패해도 같은 안내가 뜬다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('GET', _schoolsPath, FakeReply.networkError());
      await _open(tester, a);
      await _openApprove(tester);
      expect(find.text('학교 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('조건5 입력 검사', () {
    _test('학교를 안 골랐으면 안내가 뜨고 확인 창·PUT은 없다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      await tester.enterText(_numberField(), 'AB-123');
      await tester.tap(_formBtn('승인하기'));
      await tester.pumpAndSettle();
      expect(find.text(_need), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
      expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
    });

    _test('번호가 공백뿐이면 안내가 뜨고 PUT은 0건이다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester, number: '   ');
      await tester.tap(_formBtn('승인하기'));
      await tester.pumpAndSettle();
      expect(find.text(_need), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
      expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
    });

    _test('번호칸에는 50자를 넘겨 입력할 수 없다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      await tester.enterText(_numberField(), 'A' * 60);
      await tester.pump();
      expect(tester.widget<TextField>(_numberField()).controller!.text.length, 50);
    });
  });

  group('조건6 확인 창·PUT', () {
    _test('확인 창 문구가 맞고 [취소]면 PUT이 없다', (tester) async {
      final a = _base(items: [_req(id: 1, nickname: '민수')]);
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester, school: '다라대');
      await tester.tap(_formBtn('승인하기'));
      await tester.pumpAndSettle();
      expect(find.text('민수 님을 다라대 유학생으로 승인할까요? 사진은 바로 지워져요'), findsOneWidget);
      await tester.tap(_dialogBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
      expect(find.byType(PermitApproveForm), findsOneWidget); // 양식은 그대로
      expect(_selected(tester), '2');
      expect(a.count('GET', _list), 1);
    });

    _test('확인하면 PUT이 1건, 본문은 고른 학교 id와 앞뒤 공백을 뺀 번호다', (tester) async {
      final a = _Recorder();
      _base(items: [_req(id: 4, nickname: '영희')], adapter: a)
        ..on('PUT', _approvePath(4), FakeReply(200, {}))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, a);
      await _openApprove(tester, id: 4);
      await _fill(tester, school: '다라대', number: '  AB-123  ');
      await _submitAndConfirm(tester);
      expect(a.callsTo('PUT', _approvePath(4)).length, 1);
      expect(a.calls.where((c) => c.method == 'PUT').length, 1);
      expect(a.putBodies, [
        {'schoolId': 2, 'permitNumber': 'AB-123'},
      ]);
      expect(a.callsTo('PUT', _approvePath(4)).single.receiveTimeout, isNotNull);
    });
  });

  group('조건7 성공', () {
    _test('200이면 "승인했어요"가 뜨고 PENDING 목록을 1번 다시 읽어 줄이 사라진다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools))
        ..on('PUT', _approvePath(1), FakeReply(200, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text('승인했어요'), findsOneWidget);
      expect(tester.widget<Notice>(_noticeOf('승인했어요')).kind, NoticeKind.success);
      expect(a.count('GET', _list), 2);
      expect(a.callsTo('GET', _list).last.query['status'], 'PENDING');
      expect(_row(1), findsNothing);
      expect(_row(2), findsOneWidget);
      expect(find.byType(PermitApproveForm), findsNothing);
    });
  });

  group('조건8·9 실패 안내', () {
    // (이름, PUT 응답, 기대 문구, 재조회 여부)
    final cases = <(String, FakeReply, String, bool)>[
      ('404', FakeReply(404, {}), _msg404, true),
      ('403', FakeReply(403, {}), '처리하지 못했어요. 새로고침 후 다시 시도해 주세요', false),
      ('응답 없음', FakeReply.networkError(), '서버에 연결할 수 없습니다', false),
      ('시간 초과', FakeReply.timeout(), _msgTimeout, true),
      ('500', FakeReply(500, {}), '처리하지 못했습니다', false),
    ];
    for (final (name, reply, message, refetch) in cases) {
      _test('PUT이 $name이면 "$message"가 양식 안에 한 번만 뜨고 재조회는 ${refetch ? "1건" : "없다"}', (tester) async {
        final a = _base();
        a.on('PUT', _approvePath(1), reply);
        if (refetch) a.on('GET', _list, FakeReply(200, [_req(id: 1)]));
        await _open(tester, a);
        await _openApprove(tester);
        await _fill(tester);
        await _submitAndConfirm(tester);
        expect(find.text(message), findsOneWidget); // 페이지 위쪽과 중복 없음
        expect(_inForm(message), findsOneWidget); // 줄이 남아 있으면 양식 안
        expect(tester.widget<Notice>(_noticeOf(message)).kind, NoticeKind.error);
        expect(a.count('PUT', _approvePath(1)), 1);
        expect(a.count('GET', _list), refetch ? 2 : 1);
        expect(find.text('승인했어요'), findsNothing);
        // 양식·입력값 유지, 버튼 다시 활성
        expect(find.byType(PermitApproveForm), findsOneWidget);
        expect(tester.widget<TextField>(_numberField()).controller!.text, 'AB-123');
        expect(_selected(tester), '1');
        expect(tester.widget<OutlinedButton>(_formBtn('승인하기')).onPressed, isNotNull);
      });
    }

    _test('400이면 번호 안내가 양식 안에 뜨고 입력값이 유지되며 재조회는 없다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(400, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester, school: '다라대', number: 'ZZ9');
      await _submitAndConfirm(tester);
      expect(find.text(_msg400), findsOneWidget);
      expect(_inForm(_msg400), findsOneWidget);
      expect(a.count('GET', _list), 1);
      expect(tester.widget<TextField>(_numberField()).controller!.text, 'ZZ9');
      expect(_selected(tester), '2');
    });

    _test('409 + 재조회 결과에 그 줄이 없으면 "이미 처리된 신청이에요"가 페이지 위쪽에 뜬다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools))
        ..on('PUT', _approvePath(1), FakeReply(409, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msgHandled), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(a.count('GET', _list), 2);
      expect(find.text(_msgDup), findsNothing);
    });

    _test('409 + 재조회 결과에 그 줄이 아직 있으면 중복 안내가 양식 안에 한 번만 뜬다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(409, {}));
      a.on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msgDup), findsOneWidget);
      expect(_inForm(_msgDup), findsOneWidget);
      expect(find.text(_msgHandled), findsNothing);
      expect(a.count('GET', _list), 2);
      expect(tester.widget<TextField>(_numberField()).controller!.text, 'AB-123');
    });

    _test('409 + 재조회 실패면 기본 문구와 "목록을 다시 불러오지 못했어요"가 뜨고 줄·양식·번호는 유지된다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(409, {}));
      a.on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msgHandled), findsOneWidget);
      expect(find.text(_msgDup), findsNothing);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(_row(1), findsOneWidget);
      expect(find.byType(PermitApproveForm), findsOneWidget);
      expect(tester.widget<TextField>(_numberField()).controller!.text, 'AB-123');
    });

    _test('PUT이 401(재발급도 실패)이면 "다시 로그인해 주세요" 안내가 보이고 PUT은 1건이다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(401, {}));
      a.on('POST', '/auth/refresh', FakeReply(401, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('승인했어요'), findsNothing);
      expect(a.count('PUT', _approvePath(1)), 1);
    });

    _test('줄이 사라진 404는 안내가 페이지 위쪽에 한 번 뜬다', (tester) async {
      final a = _base(items: [_req(id: 1), _req(id: 2, nickname: '영희')]);
      a.on('PUT', _approvePath(1), FakeReply(404, {}));
      a.on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msg404), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(find.byType(PermitApproveForm), findsNothing);
    });
  });

  group('조건10 재조회 실패', () {
    _test('PUT 시간 초과 뒤 재조회까지 실패해도 줄·양식·번호가 남고 두 안내가 같이 보인다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply.timeout());
      a.on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(tester.takeException(), isNull);
      expect(find.text(_msgTimeout), findsOneWidget);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(_row(1), findsOneWidget);
      expect(find.byType(PermitApproveForm), findsOneWidget);
      expect(tester.widget<TextField>(_numberField()).controller!.text, 'AB-123');
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsNothing);
    });

    _test('성공 뒤 재조회가 실패해도 줄은 그대로 남고 "목록을 다시 불러오지 못했어요"가 뜨며 오류 화면으로 바뀌지 않는다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(200, {}));
      a.on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(_row(1), findsOneWidget);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsNothing);
    });

    _test('첫 로딩 실패는 오류 화면과 [다시 시도]가 뜨고, 다시 시도하면 목록이 나온다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(500, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsOneWidget);
      await tester.tap(find.widgetWithText(OutlinedButton, '다시 시도'));
      await tester.pumpAndSettle();
      expect(_row(1), findsOneWidget);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsNothing);
    });
  });

  group('조건11 연타 방지', () {
    _test('요청 중에는 승인하기·취소·다른 줄 [승인]·탭이 막히고 연타해도 PUT은 1건이다', (tester) async {
      final gate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, nickname: '영희')]))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools))
        ..on('PUT', _approvePath(1), FakeReply(200, {}, gate))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await tester.tap(_formBtn('승인하기'));
      await tester.pumpAndSettle();
      await tester.tap(_confirmOk());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500)); // PUT이 붙잡힌 상태
      expect(a.count('PUT', _approvePath(1)), 1);
      expect(tester.widget<OutlinedButton>(_formBtn('승인하기')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_formBtn('취소')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_btnIn(2, '승인')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_btnIn(2, '반려')).onPressed, isNull);
      // 연타와 탭 누르기
      await tester.tap(_formBtn('승인하기'), warnIfMissed: false);
      await tester.tap(_formBtn('승인하기'), warnIfMissed: false);
      await tester.tap(find.text('반려').first, warnIfMissed: false); // 탭
      await tester.pump();
      expect(find.byType(Dialog), findsNothing);
      expect(a.count('PUT', _approvePath(1)), 1);
      expect(a.count('GET', _list), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(a.count('PUT', _approvePath(1)), 1);
      expect(a.count('GET', _list), 2);
      expect(_row(1), findsNothing);
      expect(tester.widget<OutlinedButton>(_btnIn(2, '승인')).onPressed, isNotNull);
    });
  });

  group('조건12 반려 회귀', () {
    _test('반려 409는 여전히 "이미 처리된 신청이에요"이고 줄이 남아 있으면 반려 양식 안에 한 번만 보인다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('PUT', '/admin/permit-requests/1/reject', FakeReply(409, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]));
      await _open(tester, a);
      await tester.tap(_btnIn(1, '반려'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('permit-reject-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('사진이 흐려서 글자를 읽을 수 없어요').last);
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(of: find.byType(PermitRejectForm), matching: find.widgetWithText(OutlinedButton, '반려하기')));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('반려'));
      await tester.pumpAndSettle();
      expect(find.text(_msgHandled), findsOneWidget);
      expect(find.descendant(of: find.byType(PermitRejectForm), matching: find.text(_msgHandled)), findsOneWidget);
      expect(find.text(_msgDup), findsNothing);
      expect(a.count('GET', _list), 2);
    });
  });

  group('조건13 로그에 허가서 번호가 안 남는다', () {
    for (final (name, reply) in <(String, FakeReply)>[
      ('400', FakeReply(400, {})),
      ('500', FakeReply(500, {})),
      ('시간 초과', FakeReply.timeout()),
    ]) {
      _test('PUT이 $name이어도 debugPrint 로그에 입력한 번호가 없다', (tester) async {
        final logs = <String>[];
        final original = debugPrint;
        debugPrint = (String? message, {int? wrapWidth}) => logs.add(message ?? '');
        addTearDown(() => debugPrint = original);
        final a = _base();
        a.on('PUT', _approvePath(1), reply);
        if (name == '시간 초과') a.on('GET', _list, FakeReply(200, [_req(id: 1)]));
        await _open(tester, a);
        await _openApprove(tester);
        await _fill(tester, number: 'SECRET-7788');
        await _submitAndConfirm(tester);
        expect(logs, isNotEmpty); // 실패 로그는 남는다
        expect(logs.where((l) => l.contains('SECRET-7788')), isEmpty);
      });
    }
  });

  group('보강 경쟁·시간 초과·안내 지우기', () {
    _test('a. 409 재조회가 사진 404 재조회에 밀려 버려지면 중복 안내가 아니라 "이미 처리된 신청이에요"가 뜬다', (tester) async {
      final gateA = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
        ..on('GET', _schoolsPath, FakeReply(200, _okSchools))
        ..on('PUT', _approvePath(1), FakeReply(409, {}))
        // 첫 재조회(A): 붙잡아 둔다. 풀리면 "줄이 아직 있다"는 옛 목록을 돌려준다
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')], gateA))
        // 둘째 재조회(B, 사진 404가 부름): 바로 응답하고 줄 1이 없다
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]))
        ..on('GET', _photoPath(1), FakeReply(404, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester); // 409 → 재조회 A가 붙잡힌 상태
      expect(a.count('GET', _list), 2);
      await tester.tap(_btnIn(1, '사진 보기'));
      await tester.pumpAndSettle(); // 사진 404 → 재조회 B 끝, 줄 1 사라짐
      expect(a.count('GET', _list), 3);
      expect(_row(1), findsNothing);
      gateA.complete(); // 이제 A가 도착하지만 더 새 요청에 밀려 버려진다
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing); // 옛 목록이 줄을 되살리지 않는다
      expect(find.text(_msgHandled), findsOneWidget);
      expect(find.text(_msgDup), findsNothing);
      expect(tester.takeException(), isNull);
    });

    _test('b. 학교 목록 요청이 시간 초과면 실패 안내와 [다시 시도]가 뜨고 [승인하기]는 비활성이다 (제한 시간 30초)', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('GET', _schoolsPath, FakeReply.timeout());
      await _open(tester, a);
      await _openApprove(tester);
      expect(a.callsTo('GET', _schoolsPath).single.receiveTimeout, const Duration(seconds: 30));
      expect(find.text('학교 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(_formBtn('다시 시도'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_formBtn('승인하기')).onPressed, isNull);
      expect(_schoolField(), findsNothing);
      expect(tester.takeException(), isNull);
    });

    _test('c. 실패 뒤 [취소]하고 그 줄이 사진 404 재조회로 사라져도 옛 실패 안내가 페이지 위에 튀어나오지 않는다', (tester) async {
      final a = _base(items: [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]);
      a.on('PUT', _approvePath(1), FakeReply(500, {}));
      a.on('GET', _photoPath(1), FakeReply(404, {}));
      a.on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text('처리하지 못했습니다'), findsOneWidget); // 양식 안
      await tester.tap(_formBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.text('처리하지 못했습니다'), findsNothing); // 취소하면 안내도 지워진다
      await tester.tap(_btnIn(1, '사진 보기'));
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing); // 줄이 사라졌다
      expect(find.text('처리하지 못했습니다'), findsNothing); // 옛 안내가 위쪽에 나오지 않는다
      expect(_row(2), findsOneWidget);
    });

    for (final openReject in [false, true]) {
      _test('d. 한 줄 실패 안내가 떠 있을 때 다른 줄의 ${openReject ? "반려" : "승인"} 양식을 열면 그 안내가 사라진다', (tester) async {
        final a = _base(items: [_req(id: 1), _req(id: 2, nickname: '영희')]);
        a.on('PUT', _approvePath(1), FakeReply(500, {}));
        await _open(tester, a);
        await _openApprove(tester, id: 1);
        await _fill(tester);
        await _submitAndConfirm(tester);
        expect(find.text('처리하지 못했습니다'), findsOneWidget);
        await tester.tap(_btnIn(2, openReject ? '반려' : '승인'));
        await tester.pumpAndSettle();
        expect(find.byType(openReject ? PermitRejectForm : PermitApproveForm), findsWidgets);
        expect(find.text('처리하지 못했습니다'), findsNothing);
      });
    }

    _test('e. 성공 뒤 재조회가 실패하면 양식은 닫히고 줄은 남는다. 그 줄을 다시 승인해 409 → 재조회 성공이면 줄이 사라지고 "이미 처리된 신청이에요"', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(200, {}));
      a.on('GET', _list, FakeReply(500, {})); // 성공 뒤 재조회 실패
      a.on('PUT', _approvePath(1), FakeReply(409, {}));
      a.on('GET', _list, FakeReply(200, [])); // 두 번째 재조회 성공: 줄이 없다
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text('승인했어요'), findsOneWidget);
      expect(find.byType(PermitApproveForm), findsNothing); // 성공했으니 양식은 닫힌다
      expect(_row(1), findsOneWidget);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      // 남은 줄을 다시 승인
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(a.count('PUT', _approvePath(1)), 2);
      expect(_row(1), findsNothing);
      expect(find.text(_msgHandled), findsOneWidget);
      expect(find.text(_msgDup), findsNothing);
      expect(find.text('승인했어요'), findsNothing);
      expect(find.text(_msgRefreshFail), findsNothing);
    });

    _test('f. 사진 404 재조회가 실패하면 기존 목록은 그대로 두고 "목록을 다시 불러오지 못했어요"만 뜬다 (오류 화면 아님)', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
        ..on('GET', _photoPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await tester.tap(_btnIn(1, '사진 보기'));
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 2);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(_row(1), findsOneWidget);
      expect(_row(2), findsOneWidget);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsNothing);
    });

    _test('g. 미리 선택된 학교를 "학교를 고르세요"로 되돌리면 화면이 다시 그려져도 미선택이 유지된다', (tester) async {
      final a = _base(items: [_req(id: 1, detectedSchoolId: 2), _req(id: 2, nickname: '영희', hasPhoto: true)]);
      a.on('GET', _photoPath(2), FakeReply(404, {}));
      // 사진 404가 부르는 재조회: 부모가 새 목록으로 다시 그려 줄 1의 양식이 didUpdateWidget을 탄다
      a.on('GET', _list, FakeReply(200, [_req(id: 1, detectedSchoolId: 2), _req(id: 2, nickname: '영희', hasPhoto: true)]));
      await _open(tester, a);
      await _openApprove(tester, id: 1);
      expect(_selected(tester), '2'); // 미리 선택
      await _pickSchool(tester, '학교를 고르세요');
      expect(_selected(tester), '');
      await tester.tap(_btnIn(2, '사진 보기'));
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 2); // 부모가 실제로 다시 그렸다
      expect(find.byType(PermitApproveForm), findsOneWidget);
      expect(_selected(tester), ''); // 되돌린 선택이 유지된다
    });
  });

  group('S20 정리: [다시 불러오기]', () {
    final reloadBtn = find.widgetWithText(OutlinedButton, '다시 불러오기');

    // 사진 404 → 재조회 500: "목록을 다시 불러오지 못했어요"가 뜬 상태까지 만든다 (줄 1·2 유지)
    FakeAdapter failedRefresh() => FakeAdapter()
      ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
      ..on('GET', _photoPath(1), FakeReply(404, {}))
      ..on('GET', _list, FakeReply(500, {}));

    Future<void> showRefreshFail(WidgetTester tester) async {
      await tester.tap(_btnIn(1, '사진 보기'));
      await tester.pumpAndSettle();
    }

    _test('재조회가 실패해 안내가 뜨면 [다시 불러오기]가 보이고, 안내가 없으면 버튼도 없다', (tester) async {
      final a = failedRefresh();
      await _open(tester, a);
      expect(reloadBtn, findsNothing); // 평소엔 없다
      await showRefreshFail(tester);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(reloadBtn, findsOneWidget);
      expect(tester.widget<OutlinedButton>(reloadBtn).onPressed, isNotNull);
    });

    _test('처리 성공 뒤 재조회가 실패해도 [다시 불러오기]가 보인다', (tester) async {
      final a = _base();
      a.on('PUT', _approvePath(1), FakeReply(200, {}));
      a.on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await _openApprove(tester);
      await _fill(tester);
      await _submitAndConfirm(tester);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(reloadBtn, findsOneWidget);
    });

    _test('누르면 GET이 1건 더 나가고, 성공하면 안내·버튼이 사라지고 새 목록이 반영된다', (tester) async {
      final a = failedRefresh();
      a.on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희'), _req(id: 3, nickname: '철수')]));
      await _open(tester, a);
      await showRefreshFail(tester);
      expect(a.count('GET', _list), 2);
      await tester.tap(reloadBtn);
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3); // 정확히 1건 추가
      expect(a.callsTo('GET', _list).last.query['status'], 'PENDING');
      expect(find.text(_msgRefreshFail), findsNothing);
      expect(reloadBtn, findsNothing);
      expect(_row(1), findsNothing);
      expect(_row(2), findsOneWidget);
      expect(_row(3), findsOneWidget);
    });

    _test('또 실패하면 안내와 버튼이 남고 기존 목록도 그대로이며 오류 화면으로 바뀌지 않는다', (tester) async {
      final a = failedRefresh();
      a.on('GET', _list, FakeReply(500, {}));
      await _open(tester, a);
      await showRefreshFail(tester);
      await tester.tap(reloadBtn);
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3);
      expect(find.text(_msgRefreshFail), findsOneWidget);
      expect(reloadBtn, findsOneWidget);
      expect(tester.widget<OutlinedButton>(reloadBtn).onPressed, isNotNull); // 끝나면 다시 누를 수 있다
      expect(_row(1), findsOneWidget);
      expect(_row(2), findsOneWidget);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    _test('요청 중에는 버튼이 비활성이고 연타해도 GET은 1건만 더 나간다', (tester) async {
      final gate = Completer<void>();
      final a = failedRefresh();
      a.on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')], gate));
      await _open(tester, a);
      await showRefreshFail(tester);
      await tester.tap(reloadBtn);
      await tester.pump();
      expect(tester.widget<OutlinedButton>(reloadBtn).onPressed, isNull); // 붙잡힌 동안 비활성
      await tester.tap(reloadBtn, warnIfMissed: false);
      await tester.tap(reloadBtn, warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 500));
      expect(a.count('GET', _list), 3); // 처음 2건 + 재조회 1건
      gate.complete();
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3);
      expect(reloadBtn, findsNothing);
      expect(_row(1), findsNothing);
    });

    // S20-j2: 권한이 없어진 403은 다시 눌러도 소용없으니 "관리자만 볼 수 있습니다"를 띄우고 버튼은 숨긴다(목록은 유지)
    _test('재조회가 403이면 "관리자만 볼 수 있습니다"가 한 번 뜨고 [다시 불러오기]는 없으며 기존 목록은 남는다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
        ..on('GET', _photoPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(403, {}));
      await _open(tester, a);
      await showRefreshFail(tester);
      expect(a.count('GET', _list), 2);
      expect(find.text(ErrorMessages.adminOnly), findsOneWidget);
      expect(find.text(_msgRefreshFail), findsNothing);
      expect(reloadBtn, findsNothing);
      expect(_row(1), findsOneWidget);
      expect(_row(2), findsOneWidget);
    });

    _test('[다시 불러오기]를 눌렀는데 403이면 버튼이 사라지고 "관리자만 볼 수 있습니다"로 바뀐다', (tester) async {
      final a = failedRefresh();
      a.on('GET', _list, FakeReply(403, {}));
      await _open(tester, a);
      await showRefreshFail(tester);
      expect(reloadBtn, findsOneWidget);
      await tester.tap(reloadBtn);
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3);
      expect(find.text(ErrorMessages.adminOnly), findsOneWidget);
      expect(find.text(_msgRefreshFail), findsNothing);
      expect(reloadBtn, findsNothing);
      expect(_row(1), findsOneWidget);
      expect(_row(2), findsOneWidget);
    });

    // S20-j2 review: 403 안내가 뜬 뒤 다른 재조회가 성공하면 안내가 사라진다(회복)
    _test('403 안내 뒤 다른 재조회가 성공하면 "관리자만 볼 수 있습니다"가 사라지고 새 목록이 보인다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희', hasPhoto: true)]))
        ..on('GET', _photoPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(403, {}))
        ..on('GET', _photoPath(2), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 3, nickname: '철수')]));
      await _open(tester, a);
      await showRefreshFail(tester); // 줄 1 사진 404 → 재조회 403
      expect(find.text(ErrorMessages.adminOnly), findsOneWidget);
      await tester.tap(_btnIn(2, '사진 보기')); // 줄 2 사진 404 → 재조회 200
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3);
      expect(find.text(ErrorMessages.adminOnly), findsNothing);
      expect(find.text(_msgRefreshFail), findsNothing);
      expect(reloadBtn, findsNothing);
      expect(_row(3), findsOneWidget);
      expect(_row(1), findsNothing);
    });

    // S20-j2 review: 403 안내 뒤 탭을 바꾸면 안내가 지워지고, 그 탭의 첫 조회 403은 첫 조회 안내 한 번만 뜬다(섞이지 않음)
    _test('403 안내 뒤 탭을 바꿔 첫 조회도 403이면 "관리자만 볼 수 있습니다"는 한 번만, 버튼은 둘 다 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
        ..on('GET', _photoPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(403, {}))
        ..on('GET', _list, FakeReply(403, {})); // 승인 탭 첫 조회
      await _open(tester, a);
      await showRefreshFail(tester);
      expect(find.text(ErrorMessages.adminOnly), findsOneWidget);
      await tester.tap(find.text('승인').first); // 탭 (줄 버튼보다 위에 있다)
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 3);
      expect(a.callsTo('GET', _list).last.query['status'], 'APPROVED');
      expect(find.text(ErrorMessages.adminOnly), findsOneWidget);
      expect(reloadBtn, findsNothing);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsNothing);
      expect(_row(1), findsNothing);
    });

    // 401(재발급도 실패)은 로그인 안내로 가고 "목록을 다시 불러오지 못했어요"는 뜨지 않는다(기존 동작 고정)
    _test('재조회가 401이고 재발급도 실패하면 로그인 안내가 보이고 재조회 실패 문구는 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: true), _req(id: 2, nickname: '영희')]))
        ..on('GET', _photoPath(1), FakeReply(404, {}))
        ..on('GET', _list, FakeReply(401, {}))
        ..on('POST', '/auth/refresh', FakeReply(401, {}));
      await _open(tester, a);
      await showRefreshFail(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text(_msgRefreshFail), findsNothing);
      expect(reloadBtn, findsNothing);
    });
  });

  group('S20 정리: 허가서 번호 칸 안내', () {
    _test('번호 칸 placeholder와 입력 방법 안내 문구가 보인다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      expect(find.text('예: 2026-0001'), findsOneWidget); // 비어 있을 때 placeholder
      expect(_inForm("허가서의 '표준입학허가서 번호' 중 영문·숫자 부분만 입력해 주세요 ('제', '호'는 빼고)"), findsOneWidget);
    });

    _test('번호 칸의 hintText 설정이 맞고, 번호를 입력해도 안내 문구는 남는다', (tester) async {
      final a = _base();
      await _open(tester, a);
      await _openApprove(tester);
      expect(tester.widget<TextField>(_numberField()).decoration!.hintText, '예: 2026-0001');
      await tester.enterText(_numberField(), 'AB-123');
      await tester.pump();
      expect(find.textContaining('영문·숫자 부분만 입력해 주세요'), findsOneWidget);
    });
  });
}
