// S20-g2 유학생 인증 철회(대기 중) · 해제(인증 완료) 테스트.
// DELETE /users/me/permit-verification: 204 / 404 / 403 / 401 / 500 / 네트워크 오류를 가짜 서버로 흉내 낸다.
// 확인 창(showConfirm)은 라우터가 필요해서, 앱 전체를 /me 로 띄우는 pumpApp 을 쓴다.
import 'dart:async';

import 'package:board_web/errors/error_messages.dart' show networkErrorMessage;
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:board_web/permit/permit_texts.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _school = {'id': 1, 'name': '서울대'};
const _path = '/users/me/permit-verification';
const _requestedAt = '2026-11-16T05:12:34.123456Z';

Map<String, dynamic> _none() => {'status': 'NONE'};
Map<String, dynamic> _pending() => {'status': 'PENDING', 'requestedAt': _requestedAt};

final _withdrawButton = find.byKey(const ValueKey('permit-withdraw-button'));
final _unlinkButton = find.byKey(const ValueKey('permit-unlink-button'));
final _submitButton = find.byKey(const ValueKey('permit-submit-button'));
final _checkbox = find.byType(Checkbox);

// 대기 중 회원으로 /me 를 띄운다 (처음 상태 GET = PENDING)
FakeAdapter _pendingAdapter() => FakeAdapter()..on('GET', _path, FakeReply(200, _pending()));

// 인증 회원으로 /me 를 띄우면 화면이 회원 정보(/auth/me)를 1번 다시 받는다 (관리자가 취소했을 수 있어서).
// 그 첫 응답은 "아직 인증 회원"
FakeAdapter _approvedAdapter() => FakeAdapter()..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));

// (큐는 먼저 넣은 순서대로 나가므로, PENDING 응답이 든 어댑터를 넘겨야 한다)
Future<TestApp> _pumpPending(WidgetTester tester, FakeAdapter fake) async {
  useTallScreen(tester);
  return pumpApp(tester, location: '/me', user: userJson(), adapter: fake);
}

// 인증 완료 회원으로 /me 를 띄운다 (처음엔 상태 GET 없음)
Future<TestApp> _pumpApproved(WidgetTester tester, FakeAdapter fake) async {
  useTallScreen(tester);
  return pumpApp(tester, location: '/me', user: userJson(school: _school), adapter: fake);
}

// 확인 창의 [확인] 버튼 (대기: '철회', 인증: '해제'). 정확히 같은 글자만 찾아 화면의 '신청 철회'/'인증 해제'와 구분한다
Finder _confirmBtn(String label) => find.widgetWithText(OutlinedButton, label);

// 신청 폼에서 동의에 체크하고 사진 한 장을 고른다 (신청 버튼을 누를 수 있는 상태로 만든다)
Future<void> _fill(WidgetTester tester, FakePhotoPicker picker) async {
  await tester.tap(_checkbox);
  await tester.pump();
  await tester.tap(find.byKey(const ValueKey('permit-pick-button')));
  await tester.pump();
  picker.choose([fakePhoto('permit.png', type: 'image/png')]);
  await tester.pumpAndSettle();
}

void main() {
  group('신청 철회 (PENDING)', () {
    testWidgets('PENDING이면 [신청 철회] 버튼이 보이고 누르면 확인 창 "신청을 철회할까요?"가 뜬다', (tester) async {
      await _pumpPending(tester, _pendingAdapter());
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(_withdrawButton, findsOneWidget);
      expect(_unlinkButton, findsNothing);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      expect(find.text('신청을 철회할까요?'), findsOneWidget);
    });

    testWidgets('확인 창에서 취소하면 DELETE가 나가지 않고 대기 화면이 그대로다', (tester) async {
      final fake = _pendingAdapter();
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, '취소'));
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 0);
      expect(find.text('신청을 철회할까요?'), findsNothing);
      expect(find.text('심사 대기 중'), findsOneWidget);
    });

    testWidgets('철회 확인 → DELETE 1번(제한 시간 30초) → 204 뒤 상태 재조회(NONE) → 신청 폼이 보인다', (tester) async {
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(204))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 1);
      expect(fake.callsTo('DELETE', _path).single.receiveTimeout, const Duration(seconds: 30));
      expect(fake.count('GET', _path), 2); // 처음 1번 + 철회 뒤 1번
      expect(find.text('심사 대기 중'), findsNothing);
      expect(_withdrawButton, findsNothing);
      expect(_checkbox, findsOneWidget);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('대기 중 철회(뱃지 없음)는 204 뒤에 회원 정보(/auth/me)를 다시 부르지 않는다', (tester) async {
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(204))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpPending(tester, fake);
      final meBefore = fake.count('GET', '/auth/me'); // 화면을 띄울 때 이미 나간 횟수
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 1);
      expect(fake.count('GET', '/auth/me'), meBefore);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('404: "이미 철회되었거나 인증이 없어요" 안내 + 상태 재조회 → 신청 폼', (tester) async {
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(404))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(find.text('이미 철회되었거나 인증이 없어요'), findsOneWidget);
      expect(fake.count('GET', _path), 2);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('404 안내는 다시 신청하면 사라진다 (대기 화면에 옛 문구가 남지 않는다)', (tester) async {
      final picker = installFakePicker();
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(404))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()))
        ..on('POST', _path, FakeReply(201, _pending()));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(find.text('이미 철회되었거나 인증이 없어요'), findsOneWidget); // 404 직후 재조회에서는 남아 있다
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(find.text('이미 철회되었거나 인증이 없어요'), findsNothing);
    });

    testWidgets('403: "요청이 거절됐어요. 새로고침 후 다시 시도해 주세요", 대기 화면 유지·버튼 다시 활성, 재조회 없음', (tester) async {
      final fake = _pendingAdapter()..on('DELETE', _path, FakeReply(403));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(find.text('요청이 거절됐어요. 새로고침 후 다시 시도해 주세요'), findsOneWidget);
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNotNull);
      expect(fake.count('GET', _path), 1);
    });

    testWidgets('401(재발급도 401): "다시 로그인해 주세요" + 로그인 링크', (tester) async {
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
    });

    testWidgets('500: 일반 실패 문구, 버튼 다시 활성', (tester) async {
      final fake = _pendingAdapter()..on('DELETE', _path, FakeReply(500));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      expect(find.text(permitRemoveFailed), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNotNull);
      expect(fake.count('GET', _path), 1);
    });

    testWidgets('네트워크 오류: 연결 실패 문구, 버튼 다시 활성', (tester) async {
      final fake = _pendingAdapter()..on('DELETE', _path, FakeReply.networkError());
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pumpAndSettle();
      // 연결 실패 문구는 공용 describeError 가 정한다 (일반 실패 문구가 아니어야 한다)
      expect(find.text(permitRemoveFailed), findsNothing);
      expect(find.text(networkErrorMessage), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNotNull);
    });

    testWidgets('요청 중에는 버튼이 비활성이고 연타해도 DELETE는 1번', (tester) async {
      final gate = Completer<void>();
      final fake = _pendingAdapter()
        ..on('DELETE', _path, FakeReply(204, null, gate))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpPending(tester, fake);
      await tester.tap(_withdrawButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('철회'));
      await tester.pump(const Duration(milliseconds: 500)); // 확인 창이 닫히는 시간
      expect(tester.widget<OutlinedButton>(_withdrawButton).onPressed, isNull);
      await tester.tap(_withdrawButton, warnIfMissed: false); // 연타
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('신청을 철회할까요?'), findsNothing); // 확인 창이 또 뜨지 않는다
      expect(fake.count('DELETE', _path), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 1);
      expect(_submitButton, findsOneWidget);
    });
  });

  group('인증 해제 (APPROVED)', () {
    const exactText =
        '인증을 해제하면 글쓰기·댓글·공감·중고·쪽지를 쓸 수 없어요. 다시 쓰려면 허가서를 다시 올려 심사를 받아야 해요. 해제할까요?';

    testWidgets('인증 회원이면 [인증 해제] 버튼이 보이고, 확인 창 문구가 정해 둔 문장과 정확히 같다', (tester) async {
      final fake = _approvedAdapter();
      await _pumpApproved(tester, fake);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
      expect(_unlinkButton, findsOneWidget);
      expect(fake.count('GET', _path), 0);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      expect(find.text(exactText), findsOneWidget);
      expect(permitUnlinkConfirm, exactText); // 상수도 같은 문장
    });

    testWidgets('해제 확인 창에서 취소하면 요청이 없고 뱃지가 그대로다', (tester) async {
      final fake = _approvedAdapter();
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, '취소'));
      await tester.pumpAndSettle();
      // /me 화면의 다른 요청은 있을 수 있어서, 인증 경로로 간 요청만 센다
      expect(fake.calls.where((c) => c.path == '/api$_path'), isEmpty);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
    });

    testWidgets('해제 확인 → 204 → 회원 정보 재조회로 뱃지가 사라지고 신청 폼이 바로 보인다 (상태 GET 중복 없음)', (tester) async {
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply(204))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 1);
      expect(fake.count('GET', '/auth/me'), 2); // 화면 열 때 1번 + 해제 뒤 1번
      expect(fake.count('GET', _path), 1); // 두 번 나가면 안 된다
      expect(find.text('유학생 인증 완료 · 서울대'), findsNothing);
      expect(find.text('인증'), findsNothing); // 상단바 뱃지도 사라진다
      expect(_unlinkButton, findsNothing);
      expect(find.text(permitApprovedReload), findsNothing); // 옛 APPROVED 안내가 남지 않는다
      expect(_checkbox, findsOneWidget);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('해제 404: 안내 문구 + 회원 정보·상태 재조회 → 신청 폼', (tester) async {
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply(404))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.text('이미 철회되었거나 인증이 없어요'), findsOneWidget);
      expect(fake.count('GET', _path), 1);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('해제 403: 거절 문구, 뱃지 유지·버튼 다시 활성, 재조회 없음', (tester) async {
      final fake = _approvedAdapter()..on('DELETE', _path, FakeReply(403));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.text('요청이 거절됐어요. 새로고침 후 다시 시도해 주세요'), findsOneWidget);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_unlinkButton).onPressed, isNotNull);
      expect(fake.count('GET', '/auth/me'), 1); // 화면 열 때 1번뿐 (해제 실패라 재조회 없음)
    });

    testWidgets('해제 500: 일반 실패 문구', (tester) async {
      final fake = _approvedAdapter()..on('DELETE', _path, FakeReply(500));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.text(permitRemoveFailed), findsOneWidget);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
    });

    testWidgets('해제 네트워크 오류: 연결 실패 문구', (tester) async {
      final fake = _approvedAdapter()..on('DELETE', _path, FakeReply.networkError());
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.text(permitRemoveFailed), findsNothing);
      expect(find.text(networkErrorMessage), findsOneWidget);
    });

    testWidgets('해제 401(재발급도 401): 다시 로그인 안내', (tester) async {
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(fake.count('GET', _path), 0); // 로그인이 풀렸으니 상태를 다시 묻지 않는다
      expect(find.text('유학생 인증 완료 · 서울대'), findsNothing);
      expect(_unlinkButton, findsNothing);
    });

    testWidgets('신청 승인(201 APPROVED) 뒤 해제: 재조회를 붙잡은 동안 옛 승인 안내가 없다', (tester) async {
      final picker = installFakePicker();
      final gate = Completer<void>();
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(200, _none())) // 처음 상태
        ..on('POST', _path, FakeReply(201, {'status': 'APPROVED', 'requestedAt': _requestedAt}))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school))) // 승인 뒤 회원 정보
        ..on('DELETE', _path, FakeReply(204))
        ..on('GET', '/auth/me', FakeReply(200, userJson())) // 해제 뒤 회원 정보
        ..on('GET', _path, FakeReply(200, _none(), gate)); // 해제 뒤 상태 재조회 (붙잡는다)
      await _pumpPending(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget); // 이제 _status가 APPROVED
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pump(const Duration(milliseconds: 50));
      // 재조회가 아직 안 끝난 중간 시점
      expect(find.text(permitApprovedReload), findsNothing);
      expect(_submitButton, findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text(permitApprovedReload), findsNothing);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('204 뒤 회원 정보 갱신이 실패해 뱃지가 남으면 "해제됐어요. 새로고침해 주세요."', (tester) async {
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply(204))
        ..on('GET', '/auth/me', FakeReply(500))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(find.text(permitUnlinkReload), findsOneWidget);
      expect(permitUnlinkReload, '해제됐어요. 새로고침해 주세요.');
    });

    testWidgets('해제 요청이 시간 초과면 상태를 다시 조회한다', (tester) async {
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply.timeout())
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 1); // 재조회가 나갔다
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('해제 요청 중에는 버튼 비활성, 연타해도 DELETE는 1번', (tester) async {
      final gate = Completer<void>();
      final fake = _approvedAdapter()
        ..on('DELETE', _path, FakeReply(204, null, gate))
        ..on('GET', '/auth/me', FakeReply(200, userJson()))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pumpApproved(tester, fake);
      await tester.tap(_unlinkButton);
      await tester.pumpAndSettle();
      await tester.tap(_confirmBtn('해제'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(tester.widget<OutlinedButton>(_unlinkButton).onPressed, isNull);
      await tester.tap(_unlinkButton, warnIfMissed: false);
      await tester.pump(const Duration(milliseconds: 50));
      expect(fake.count('DELETE', _path), 1);
      gate.complete();
      await tester.pumpAndSettle();
      expect(fake.count('DELETE', _path), 1);
      expect(_submitButton, findsOneWidget);
    });
  });
}
