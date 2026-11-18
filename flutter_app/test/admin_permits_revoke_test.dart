// S21-c 관리자 "인증 심사" 화면의 인증 취소 기능(기본 흐름):
// 승인 탭 줄의 [인증 취소] 노출, 사유 고르기 → 확인 창 → PUT revoke, 성공·409·확인 창 취소, 취소 탭.
import 'package:board_web/permit/permit_reject_form.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _list = '/admin/permit-requests';
const _myId = 9; // adminUser()의 id

Map<String, dynamic> _req({
  int id = 1,
  int userId = 100,
  String nickname = '민수',
  String status = 'APPROVED',
  String? schoolName = '가나대',
  String? rejectReason,
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
  'machineResult': 'ALL_PASSED',
  'autoDecided': false,
  'hasPhoto': false,
};

String _revokePath(int id) => '/admin/permit-requests/$id/revoke';

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

// 승인 탭으로 옮긴다 (탭 글자는 화면 위쪽에 있어 .first)
Future<void> _goApprovedTab(WidgetTester tester) async {
  await tester.tap(find.text('승인').first);
  await tester.pumpAndSettle();
}

// 1번 줄의 [인증 취소] → 사유(흐려서) 고르기
Future<void> _openFormAndPick(WidgetTester tester) async {
  await tester.tap(_btnIn(1, '인증 취소'));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('permit-revoke-reason')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('사진이 흐려서 글자를 읽을 수 없어요').last);
  await tester.pumpAndSettle();
}

void main() {
  _test('[인증 취소]는 승인 탭의 남의 줄에만 있고, 본인 줄·대기·반려·취소 탭 줄에는 없다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, [_req(id: 5, status: 'PENDING', schoolName: null)])) // 대기
      ..on('GET', _list, FakeReply(200, [_req(id: 1), _req(id: 2, userId: _myId)])) // 승인
      ..on('GET', _list, FakeReply(200, [_req(id: 3, status: 'REJECTED', rejectReason: 'BLURRY')])) // 반려
      ..on('GET', _list, FakeReply(200, [_req(id: 4, status: 'REVOKED', rejectReason: 'NOT_PERMIT')])); // 취소
    await _open(tester, a);
    expect(_btnIn(5, '인증 취소'), findsNothing);
    await _goApprovedTab(tester);
    expect(_btnIn(1, '인증 취소'), findsOneWidget);
    expect(_btnIn(2, '인증 취소'), findsNothing); // 본인 줄
    await tester.tap(find.text('반려').first);
    await tester.pumpAndSettle();
    expect(_btnIn(3, '인증 취소'), findsNothing);
    await tester.tap(find.text('취소').first); // 새로 생긴 "취소" 탭
    await tester.pumpAndSettle();
    expect(_btnIn(4, '인증 취소'), findsNothing);
    // 취소 탭은 status=REVOKED로 불러오고, 줄에 취소 사유가 보인다
    expect(a.calls.last.query['status'], 'REVOKED');
    expect(find.text('취소 사유'), findsOneWidget);
    expect(find.text('표준입학허가서가 아니에요'), findsOneWidget);
    expect(find.text('승인 학교'), findsOneWidget);
    expect(find.text('가나대'), findsOneWidget);
  });

  _test('사유 고르고 확인 → PUT revoke 1번(고른 사유), 성공하면 목록을 다시 읽어 그 줄이 사라진다', (tester) async {
    final a = _Recorder()
      ..on('GET', _list, FakeReply(200, [_req(id: 1)])) // 대기 탭 (빈 줄 대신 아무거나)
      ..on('GET', _list, FakeReply(200, [_req(id: 1)])) // 승인 탭
      ..on('PUT', _revokePath(1), FakeReply(200, _req(id: 1, status: 'REVOKED', rejectReason: 'BLURRY')))
      ..on('GET', _list, FakeReply(200, [])); // 취소 뒤 재조회
    await _open(tester, a);
    await _goApprovedTab(tester);
    await _openFormAndPick(tester);
    await tester.tap(_formBtn('인증 취소하기'));
    await tester.pumpAndSettle();
    expect(find.textContaining('민수 님의 유학생 인증을 취소할까요?'), findsOneWidget);
    expect(a.count('PUT', _revokePath(1)), 0); // 확인 전엔 요청 없음
    await tester.tap(_dialogBtn('인증 취소'));
    await tester.pumpAndSettle();
    expect(a.count('PUT', _revokePath(1)), 1);
    expect(a.putBodies.single, {'reason': 'BLURRY'});
    expect(_row(1), findsNothing);
    expect(find.text('인증을 취소했어요'), findsOneWidget);
  });

  _test('확인 창에서 취소하면 요청이 나가지 않는다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req(id: 1)]));
    await _open(tester, a);
    await _goApprovedTab(tester);
    await _openFormAndPick(tester);
    await tester.tap(_formBtn('인증 취소하기'));
    await tester.pumpAndSettle();
    await tester.tap(_dialogBtn('취소'));
    await tester.pumpAndSettle();
    expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
    expect(_row(1), findsOneWidget);
  });

  _test('409면 안내 문구를 보여 주고 목록을 다시 읽는다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
      ..on('PUT', _revokePath(1), FakeReply(409, {'message': '승인된 신청만 취소할 수 있습니다.'}))
      ..on('GET', _list, FakeReply(200, [])); // 이미 취소돼 승인 탭에서 사라짐
    await _open(tester, a);
    await _goApprovedTab(tester);
    await _openFormAndPick(tester);
    await tester.tap(_formBtn('인증 취소하기'));
    await tester.pumpAndSettle();
    await tester.tap(_dialogBtn('인증 취소'));
    await tester.pumpAndSettle();
    expect(find.text('승인된 신청만 취소할 수 있어요. 이미 취소됐거나 상태가 바뀌었어요'), findsOneWidget);
    expect(a.count('GET', _list), 3); // 대기 + 승인 + 다시 읽기
    expect(_row(1), findsNothing);
  });
}
