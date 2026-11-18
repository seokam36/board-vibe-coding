// S21-c 인증 취소 보강 테스트: 실패 응답(400·403·404), 연타 방지, 사유 목록에 EXPIRED 없음,
// 승인 탭 줄의 승인 학교, 취소당한(REVOKED) 회원의 재신청 POST.
import 'dart:async';

import 'package:board_web/permit/permit_reject_form.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _list = '/admin/permit-requests';
const _revoke1 = '/admin/permit-requests/1/revoke';

Map<String, dynamic> _req({int id = 1, String nickname = '민수', String status = 'APPROVED'}) => {
  'id': id,
  'userId': 100 + id,
  'nickname': nickname,
  'status': status,
  'createdAt': '2026-11-01T03:04:05Z',
  'schoolName': '가나대',
  'rejectReason': null,
  'detectedSchoolId': null,
  'detectedSchoolName': null,
  'machineResult': 'ALL_PASSED',
  'autoDecided': false,
  'hasPhoto': false,
};

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

Finder _row(int id) => find.byKey(ValueKey('permit-$id'));
Finder _btnIn(int id, String text) => find.descendant(of: _row(id), matching: find.widgetWithText(OutlinedButton, text));
Finder _dialogBtn(String text) =>
    find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, text));
Finder _formBtn(String text) =>
    find.descendant(of: find.byType(PermitRejectForm), matching: find.widgetWithText(OutlinedButton, text));

// 승인 탭에서 1번 줄 [인증 취소] → 사유(흐려서) 고르기까지
Future<void> _openAndPick(WidgetTester tester, FakeAdapter a) async {
  await pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: a);
  await tester.tap(find.text('승인').first);
  await tester.pumpAndSettle();
  await tester.tap(_btnIn(1, '인증 취소'));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey('permit-revoke-reason')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('사진이 흐려서 글자를 읽을 수 없어요').last);
  await tester.pumpAndSettle();
}

Future<void> _submitAndConfirm(WidgetTester tester) async {
  await tester.tap(_formBtn('인증 취소하기'));
  await tester.pumpAndSettle();
  await tester.tap(_dialogBtn('인증 취소'));
  await tester.pumpAndSettle();
}

void main() {
  // (이름, 응답, 기대 문구, 재조회 여부)
  final cases = <(String, FakeReply, String, bool)>[
    ('400', FakeReply(400, {}), '취소 사유를 확인해 주세요', false),
    ('403', FakeReply(403, {}), '처리하지 못했어요. 새로고침 후 다시 시도해 주세요', false),
    ('404', FakeReply(404, {}), '이미 사라진 신청이에요 (철회했거나 탈퇴했을 수 있어요)', true),
  ];
  for (final (name, reply, message, refetch) in cases) {
    _test('인증 취소 PUT이 $name이면 "$message" 오류 안내가 뜨고 화면은 유지된다 (재조회 ${refetch ? "함" : "안 함"})', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, [_req()]))
        ..on('PUT', _revoke1, reply);
      if (refetch) a.on('GET', _list, FakeReply(200, [_req()]));
      await _openAndPick(tester, a);
      await _submitAndConfirm(tester);
      expect(find.text(message), findsOneWidget);
      expect(tester.widget<Notice>(find.widgetWithText(Notice, message)).kind, NoticeKind.error);
      expect(a.count('PUT', _revoke1), 1);
      expect(a.count('GET', _list), refetch ? 3 : 2);
      expect(find.text('인증을 취소했어요'), findsNothing); // 완료 안내는 없다
      expect(find.byType(PermitRejectForm), findsOneWidget); // 양식이 남아 다시 시도 가능
      expect(tester.widget<OutlinedButton>(_formBtn('인증 취소하기')).onPressed, isNotNull);
    });
  }

  _test('처리 중 [인증 취소하기]를 연타해도 PUT은 1번이고 버튼이 막힌다', (tester) async {
    final gate = Completer<void>();
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req(), _req(id: 2, nickname: '영희')]))
      ..on('PUT', _revoke1, FakeReply(200, {}, gate))
      ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '영희')]));
    await _openAndPick(tester, a);
    await tester.tap(_formBtn('인증 취소하기'));
    await tester.pumpAndSettle();
    await tester.tap(_dialogBtn('인증 취소'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500)); // PUT이 붙잡힌 상태
    expect(a.count('PUT', _revoke1), 1);
    expect(tester.widget<OutlinedButton>(_formBtn('인증 취소하기')).onPressed, isNull);
    expect(tester.widget<OutlinedButton>(_btnIn(2, '인증 취소')).onPressed, isNull); // 다른 줄도 막힘
    await tester.tap(_formBtn('인증 취소하기'), warnIfMissed: false);
    await tester.tap(_formBtn('인증 취소하기'), warnIfMissed: false);
    await tester.pump();
    expect(find.byType(Dialog), findsNothing);
    expect(a.count('PUT', _revoke1), 1);
    gate.complete();
    await tester.pumpAndSettle();
    expect(a.count('PUT', _revoke1), 1);
    expect(_row(1), findsNothing);
  });

  _test('인증 취소 사유 목록은 6개이고 EXPIRED(만료) 문구는 없다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req()]));
    await pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: a);
    await tester.tap(find.text('승인').first);
    await tester.pumpAndSettle();
    await tester.tap(_btnIn(1, '인증 취소'));
    await tester.pumpAndSettle();
    final dd = tester.widget<DropdownButton<String>>(find.byKey(const ValueKey('permit-revoke-reason')));
    expect(dd.items!.map((i) => i.value), ['', 'BLURRY', 'NOT_PERMIT', 'NOT_MASKED', 'UNSUPPORTED_SCHOOL', 'DUPLICATE', 'OTHER']);
    expect(dd.items!.any((i) => i.value == 'EXPIRED'), isFalse);
    await tester.tap(find.byKey(const ValueKey('permit-revoke-reason')));
    await tester.pumpAndSettle();
    expect(find.text('14일 안에 확인하지 못했어요. 다시 신청해 주세요'), findsNothing);
  });

  _test('사유를 안 고르고 [인증 취소하기]를 누르면 안내만 뜨고 확인 창·PUT은 없다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req()]));
    await pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: a);
    await tester.tap(find.text('승인').first);
    await tester.pumpAndSettle();
    await tester.tap(_btnIn(1, '인증 취소'));
    await tester.pumpAndSettle();
    await tester.tap(_formBtn('인증 취소하기'));
    await tester.pumpAndSettle();
    expect(find.text('취소 사유를 골라 주세요'), findsOneWidget);
    expect(find.byType(Dialog), findsNothing);
    expect(a.calls.where((c) => c.method == 'PUT'), isEmpty);
  });

  _test('승인 탭 줄에는 승인 학교가 보이고 [인증 취소] 외 승인·반려 버튼은 없다', (tester) async {
    final a = FakeAdapter()
      ..on('GET', _list, FakeReply(200, []))
      ..on('GET', _list, FakeReply(200, [_req()]));
    await pumpApp(tester, location: '/admin/permits', user: adminUser(), adapter: a);
    await tester.tap(find.text('승인').first);
    await tester.pumpAndSettle();
    expect(find.text('승인 학교'), findsOneWidget);
    expect(find.text('가나대'), findsOneWidget);
    expect(a.calls.last.query['status'], 'APPROVED');
    expect(_btnIn(1, '반려'), findsNothing);
    expect(find.widgetWithText(OutlinedButton, '승인'), findsNothing); // 승인 탭 줄엔 [승인] 없음
    expect(_btnIn(1, '인증 취소'), findsOneWidget);
  });
}
