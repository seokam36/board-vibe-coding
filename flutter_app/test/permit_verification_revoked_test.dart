// S21-c 회원 "유학생 인증" 섹션: 관리자가 인증을 취소한 회원(REVOKED) 화면, 로그인한 채로 취소당한 회원의 뱃지.
import 'package:board_web/permit/permit_texts.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _path = '/users/me/permit-verification';
const _school = {'id': 1, 'name': '서울대'};

void main() {
  testWidgets('상태가 REVOKED면 취소 안내 + 사유 + 신청 폼이 보이고 [철회]·[인증 해제]는 없다', (tester) async {
    useTallScreen(tester);
    final fake = FakeAdapter()..on('GET', _path, FakeReply(200, {'status': 'REVOKED', 'rejectReason': 'NOT_MASKED'}));
    await pumpApp(tester, location: '/me', user: userJson(), adapter: fake);
    expect(find.text('인증이 취소됐어요. 허가서를 다시 올려 심사를 받아 주세요'), findsOneWidget);
    expect(permitRevokedMessage, '인증이 취소됐어요. 허가서를 다시 올려 심사를 받아 주세요');
    expect(find.text('가려야 할 정보가 보여요. 가리고 다시 올려 주세요'), findsOneWidget); // 취소 사유
    expect(find.byKey(const ValueKey('permit-submit-button')), findsOneWidget); // 신청 폼
    expect(find.byKey(const ValueKey('permit-withdraw-button')), findsNothing);
    expect(find.byKey(const ValueKey('permit-unlink-button')), findsNothing);
  });

  testWidgets('로그인한 채로 취소당한 회원: /me를 열면 회원 정보를 다시 받아 뱃지가 사라지고 취소 안내가 뜬다', (tester) async {
    useTallScreen(tester);
    final fake = FakeAdapter()
      ..on('GET', '/auth/me', FakeReply(200, userJson())) // 서버는 이미 학교를 지웠다
      ..on('GET', _path, FakeReply(200, {'status': 'REVOKED', 'rejectReason': 'OTHER'}));
    await pumpApp(tester, location: '/me', user: userJson(school: _school), adapter: fake);
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
    expect(find.text('유학생 인증 완료 · 서울대'), findsNothing);
    expect(find.text(permitRevokedMessage), findsOneWidget);
    expect(find.byKey(const ValueKey('permit-submit-button')), findsOneWidget);
  });

  testWidgets('인증 회원: /auth/me가 500이면 뱃지와 [인증 해제]는 그대로이고 상태 GET은 0번이다', (tester) async {
    useTallScreen(tester);
    final fake = FakeAdapter()..on('GET', '/auth/me', FakeReply(500, {}));
    await pumpApp(tester, location: '/me', user: userJson(school: _school), adapter: fake);
    await tester.pumpAndSettle();
    expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget); // 실패해도 뱃지 유지
    expect(find.byKey(const ValueKey('permit-unlink-button')), findsOneWidget);
    expect(fake.count('GET', _path), 0);
  });

  testWidgets('인증 회원: /auth/me가 401(재발급도 401)이면 페이지의 로그아웃돼 상태 GET은 0번, [다시 시도]는 없다', (tester) async {
    useTallScreen(tester);
    final fake = FakeAdapter()
      ..on('GET', '/auth/me', FakeReply(401, {}))
      ..on('POST', '/auth/refresh', FakeReply(401, {}));
    await pumpApp(tester, location: '/me', user: userJson(school: _school), adapter: fake);
    await tester.pumpAndSettle();
    expect(fake.count('GET', _path), 0);
    // 실제 화면: 로그아웃돼 상단바에 [로그인]이 보이고, 섹션은 아무것도 그리지 않는다 (페이지 로그인 안내 문구는 안 뜸)
    expect(find.text('유학생 인증 완료 · 서울대'), findsNothing);
    expect(find.text('로그인'), findsWidgets);
    expect(find.byType(ReloginNotice), findsNothing);
    expect(find.text('다시 시도'), findsNothing);
  });

  testWidgets('취소당한(REVOKED) 회원이 허가서를 올려 신청하면 POST가 나간다', (tester) async {
    useTallScreen(tester);
    final picker = installFakePicker();
    final fake = FakeAdapter()
      ..on('GET', _path, FakeReply(200, {'status': 'REVOKED', 'rejectReason': 'OTHER'}))
      ..on('POST', _path, FakeReply(201, {'status': 'PENDING', 'requestedAt': '2026-11-16T05:12:34Z'}));
    await pumpApp(tester, location: '/me', user: userJson(), adapter: fake);
    await tester.tap(find.byType(Checkbox));
    await tester.pump();
    await tester.tap(find.widgetWithText(OutlinedButton, '사진 고르기'));
    await tester.pump();
    picker.choose([fakePhoto('permit.png', type: 'image/png')]);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '인증 신청'));
    await tester.pumpAndSettle();
    expect(fake.count('POST', _path), 1);
    expect(find.byKey(const ValueKey('permit-submit-button')), findsNothing); // 대기 상태로 바뀜
  });
}
