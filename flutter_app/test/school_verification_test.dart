// S20-g1 유학생 인증 섹션 테스트: 상태 보기(GET)와 허가서 신청(POST multipart).
// 인증 회원은 서버에 묻지 않고 뱃지만, 미인증 회원은 상태를 1번 묻고 NONE·REJECTED면 신청 폼을 보여 준다.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/permit/permit_reject_reasons.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:board_web/widgets/school_verification_section.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _school = {'id': 1, 'name': '서울대'};
const _path = '/users/me/permit-verification';
const _requestedAt = '2026-11-16T05:12:34.123456Z'; // 한국 시간 11월 16일 14:12

Map<String, dynamic> _none() => {'status': 'NONE'};
Map<String, dynamic> _pending() => {'status': 'PENDING', 'requestedAt': _requestedAt};
Map<String, dynamic> _approved() => {'status': 'APPROVED', 'requestedAt': _requestedAt, 'decidedAt': _requestedAt};

final _submitButton = find.widgetWithText(FilledButton, '인증 신청');
final _pickButton = find.widgetWithText(OutlinedButton, '사진 고르기');
final _checkbox = find.byType(Checkbox);

LoadedAuthState? _lastAuth; // 마지막으로 띄운 화면의 로그인 상태 (테스트가 회원 정보를 바꿀 때 쓴다)

// 섹션 하나를 띄운다. adapter에 GET 응답 등을 미리 준비해서 넘긴다
Future<FakeAdapter> _pump(WidgetTester tester, FakeAdapter fake, {Map<String, dynamic>? user}) async {
  useTallScreen(tester);
  final auth = LoadedAuthState(adapter: fake, user: user ?? userJson());
  _lastAuth = auth;
  addTearDown(auth.dispose);
  await tester.pumpWidget(
    Provider<ApiClient>.value(
      value: auth.api,
      child: ChangeNotifierProvider<AuthState>.value(
        value: auth,
        child: MaterialApp(
          theme: buildAppTheme(),
          home: const Scaffold(body: SingleChildScrollView(child: SchoolVerificationSection())),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return fake;
}

// 상태 GET에 NONE을 한 번 주는 어댑터 (다시 확인하는 GET이 있는 테스트는 뒤에 응답을 더 붙인다)
FakeAdapter _noneAdapter() => FakeAdapter()..on('GET', _path, FakeReply(200, _none()));

// 동의에 체크하고 사진 한 장을 고른다
Future<void> _fill(WidgetTester tester, FakePhotoPicker picker, {PhotoLog? log}) async {
  await tester.tap(_checkbox);
  await tester.pump();
  await tester.tap(_pickButton);
  await tester.pump();
  picker.choose([fakePhoto('permit.png', type: 'image/png', log: log)]);
  await tester.pumpAndSettle();
}

void main() {
  group('처음 보이는 모습', () {
    testWidgets('미인증 회원: 상태 GET 1번, 제목·가림 안내·동의·[사진 고르기], 비활성 [인증 신청], 준비 중 문구 없음', (tester) async {
      final fake = await _pump(tester, _noneAdapter());
      expect(fake.count('GET', _path), 1);
      expect(find.text('유학생 인증'), findsOneWidget);
      expect(find.text(permitMaskHide), findsOneWidget);
      expect(find.text(permitMaskShow), findsOneWidget);
      expect(find.text(permitConsentText), findsOneWidget);
      expect(_checkbox, findsOneWidget);
      expect(tester.widget<Checkbox>(_checkbox).value, isFalse);
      expect(_pickButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);
      expect(find.textContaining('준비 중'), findsNothing);
      expect(find.text('학교 인증'), findsNothing);
    });

    testWidgets('동의 문구는 정해 둔 문장 그대로다', (tester) async {
      expect(
        permitConsentText,
        '표준입학허가서 사진을 유학생·학교 확인 목적으로 수집합니다. 사진은 심사가 끝나면 바로 지우고, 늦어도 14일 뒤 삭제합니다. '
        '허가서 번호는 중복 확인용 암호화 값(해시)만 보관합니다. 사진은 글자 확인을 위해 Google Cloud Vision으로 전송될 수 있습니다. 언제든 철회할 수 있습니다.',
      );
    });

    testWidgets('인증 회원: GET 0번, 뱃지 "유학생 인증 완료 · 학교명"과 "졸업 후에도 유지돼요"', (tester) async {
      final fake = await _pump(tester, FakeAdapter(), user: userJson(school: _school));
      expect(fake.calls, isEmpty);
      expect(find.text('유학생 인증'), findsOneWidget);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
      expect(find.ancestor(of: find.text('유학생 인증 완료 · 서울대'), matching: find.byType(AppBadge)), findsOneWidget);
      expect(find.text('졸업 후에도 유지돼요'), findsOneWidget);
      expect(_checkbox, findsNothing);
    });
  });

  group('상태 보기', () {
    testWidgets('PENDING: "심사 대기 중"과 신청 때 날짜, 폼은 없다', (tester) async {
      final fake = await _pump(tester, FakeAdapter()..on('GET', _path, FakeReply(200, _pending())));
      expect(fake.count('GET', _path), 1);
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(find.textContaining('11월 16일 14:12'), findsOneWidget);
      expect(_checkbox, findsNothing);
      expect(_submitButton, findsNothing);
      expect(_pickButton, findsNothing);
    });

    // 반려 사유 7개 코드 각각의 문구 + 신청 폼
    for (final entry in permitRejectReasons.entries) {
      testWidgets('REJECTED ${entry.key}: 문구 "${entry.value}" + 신청 폼', (tester) async {
        await _pump(
          tester,
          FakeAdapter()..on('GET', _path, FakeReply(200, {'status': 'REJECTED', 'rejectReason': entry.key})),
        );
        expect(find.text(entry.value), findsOneWidget);
        expect(_checkbox, findsOneWidget);
        expect(_submitButton, findsOneWidget);
      });
    }

    testWidgets('REJECTED인데 모르는 사유 코드면 OTHER 문구', (tester) async {
      await _pump(
        tester,
        FakeAdapter()..on('GET', _path, FakeReply(200, {'status': 'REJECTED', 'rejectReason': 'NEW_CODE'})),
      );
      expect(find.text(permitRejectReasons['OTHER']!), findsOneWidget);
    });

    testWidgets('불러오기 실패: 오류 문구 + [다시 시도] → GET 다시 → 성공하면 폼', (tester) async {
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(500))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pump(tester, fake);
      expect(find.text(permitLoadFailed), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsOneWidget);
      expect(_checkbox, findsNothing);

      await tester.tap(find.widgetWithText(OutlinedButton, '다시 시도'));
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 2);
      expect(find.text(permitLoadFailed), findsNothing);
      expect(_checkbox, findsOneWidget);
    });

    testWidgets('APPROVED인데 화면엔 학교가 없으면 /auth/me로 회원 정보를 다시 받는다', (tester) async {
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(200, _approved()))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));
      await _pump(tester, fake);
      expect(fake.count('GET', '/auth/me'), 1);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
    });
  });

  group('신청', () {
    testWidgets('동의와 사진이 둘 다 있어야 [인증 신청]이 켜진다. 사진은 한 장 전용 창으로 연다', (tester) async {
      final picker = installFakePicker();
      await _pump(tester, _noneAdapter());
      await tester.tap(_checkbox); // 동의만
      await tester.pump();
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);
      await tester.tap(_pickButton);
      await tester.pump();
      expect(picker.opened, 1);
      expect(picker.lastMultiple, isFalse);
      picker.choose([fakePhoto('permit.png', type: 'image/png')]);
      await tester.pumpAndSettle();
      expect(find.text('permit.png'), findsOneWidget); // 파일 이름 표시
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
      await tester.tap(_checkbox); // 동의를 풀면 다시 꺼진다
      await tester.pump();
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);
    });

    testWidgets('누르면 POST 1번(multipart: file 파트 + consent=true, 제한 시간 2분), 응답 전엔 컨트롤 잠금·진행 문구, 연타해도 1번', (tester) async {
      final picker = installFakePicker();
      final gate = Completer<void>();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(201, _pending(), gate));
      final forms = recordForms(fake);
      await _pump(tester, fake);
      await _fill(tester, picker);

      await tester.tap(_submitButton);
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(_submitButton, warnIfMissed: false); // 연타
      await tester.pump(const Duration(milliseconds: 50));
      expect(fake.count('POST', _path), 1);
      expect(find.text(permitProgressMessage), findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_pickButton).onPressed, isNull);
      expect(tester.widget<Checkbox>(_checkbox).onChanged, isNull);

      gate.complete();
      await tester.pumpAndSettle();
      expect(fake.count('POST', _path), 1);
      expect(forms, hasLength(1));
      expect(forms.first.body, contains('name="file"'));
      expect(forms.first.body, contains('filename="permit.png"'));
      expect(forms.first.body, contains('name="consent"'));
      expect(forms.first.body, contains('true'));
      expect(fake.callsTo('POST', _path).single.receiveTimeout, const Duration(minutes: 2));
    });

    testWidgets('201 PENDING: 폼이 사라지고 "심사 대기 중" + 날짜 (GET을 또 부르지 않는다)', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(201, _pending()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(find.textContaining('11월 16일 14:12'), findsOneWidget);
      expect(_checkbox, findsNothing);
      expect(_submitButton, findsNothing);
      expect(fake.count('GET', _path), 1);
    });

    testWidgets('201 APPROVED: /auth/me 1번 → 뱃지', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(201, _approved()))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('GET', '/auth/me'), 1);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
      expect(_submitButton, findsNothing);
    });

    testWidgets('201 APPROVED: 상단바에도 "인증" 뱃지가 나타난다', (tester) async {
      final picker = installFakePicker();
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(200, _none()))
        ..on('POST', _path, FakeReply(201, _approved()))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));
      await pumpApp(tester, location: '/me', user: userJson(), adapter: fake);
      expect(find.text('인증'), findsNothing); // 신청 전엔 상단바 뱃지가 없다
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('GET', '/auth/me'), 1);
      expect(find.text('유학생 인증 완료 · 서울대'), findsOneWidget);
      expect(find.text('인증'), findsOneWidget); // 상단바 뱃지
    });
  });

  group('사전 검사', () {
    testWidgets('JPEG·PNG가 아니면 사진을 받지 않고 문구, 요청은 0번', (tester) async {
      final picker = installFakePicker();
      final log = PhotoLog();
      final fake = await _pump(tester, _noneAdapter());
      await tester.tap(_checkbox);
      await tester.tap(_pickButton);
      await tester.pump();
      picker.choose([fakePhoto('a.gif', type: 'image/gif', log: log)]);
      await tester.pumpAndSettle();
      expect(find.text(permitTypeOnly), findsOneWidget);
      expect(find.text('a.gif'), findsNothing);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);
      expect(log.released, ['a.gif']); // 미리보기 주소 반납
      expect(fake.count('POST', _path), 0);
    });

    testWidgets('10MB 초과면 문구, 정확히 10MB는 통과', (tester) async {
      final picker = installFakePicker();
      final fake = await _pump(tester, _noneAdapter());
      await tester.tap(_checkbox);
      await tester.tap(_pickButton);
      await tester.pump();
      picker.choose([fakePhoto('big.jpg', size: 10 * 1024 * 1024 + 1)]);
      await tester.pumpAndSettle();
      expect(find.text(permitTooLarge), findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNull);

      picker.choose([fakePhoto('edge.jpg', size: 10 * 1024 * 1024)]);
      await tester.pumpAndSettle();
      expect(find.text(permitTooLarge), findsNothing);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
      expect(fake.count('POST', _path), 0);
    });
  });

  group('신청 실패', () {
    // 상태코드 → 문구, 끝나면 [인증 신청]이 다시 켜진다 (409는 아래에서 따로)
    for (final entry in {
      400: permitSubmitErrors[400]!,
      413: permitSubmitErrors[413]!,
      429: permitSubmitErrors[429]!,
      503: permitSubmitErrors[503]!,
      500: permitSubmitFailed,
    }.entries) {
      testWidgets('POST ${entry.key}: 문구 + 버튼 다시 활성, 상태 GET은 더 부르지 않는다', (tester) async {
        final picker = installFakePicker();
        final fake = _noneAdapter()..on('POST', _path, FakeReply(entry.key));
        await _pump(tester, fake);
        await _fill(tester, picker);
        await tester.tap(_submitButton);
        await tester.pumpAndSettle();
        expect(find.text(entry.value), findsOneWidget);
        expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
        expect(tester.widget<Checkbox>(_checkbox).onChanged, isNotNull);
        expect(find.text(permitProgressMessage), findsNothing);
        expect(fake.count('GET', _path), 1);
      });
    }

    testWidgets('POST 409: 문구 + 상태 GET 1번 더, 그 사이 대기가 됐으면 "심사 대기 중"', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(409))
        ..on('GET', _path, FakeReply(200, _pending()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 2);
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(_submitButton, findsNothing);
    });

    testWidgets('POST 409인데 다시 확인해도 NONE이면 문구가 남고 버튼이 켜진다', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(409))
        ..on('GET', _path, FakeReply(200, _none()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 2);
      expect(find.text(permitNotReceived), findsOneWidget); // 다시 확인해 보니 접수 안 됨
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('POST 409 뒤 다시 확인(GET)까지 실패해도 폼이 남고 확인 실패 문구, 버튼 재활성', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(409)); // 두 번째 GET은 준비 안 함 = 실패
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 2);
      expect(find.text(permitRecheckFailed), findsOneWidget);
      expect(_checkbox, findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('시간 초과 뒤 다시 확인(GET)까지 실패해도 폼이 남고 확인 실패 문구, 버튼 재활성', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()..on('POST', _path, FakeReply.timeout());
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text(permitRecheckFailed), findsOneWidget);
      expect(_checkbox, findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('POST 403: 새로고침 안내 문구', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(403));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text(permitSubmitErrors[403]!), findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('POST 401 → 재발급 200 → 다시 보내기: 요청 2번, 본문(FormData)은 서로 다른 새 객체', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, userJson()))
        ..on('POST', _path, FakeReply(201, _pending()));
      final forms = recordForms(fake);
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('POST', _path), 2);
      expect(forms, hasLength(2));
      expect(identical(forms[0].data, forms[1].data), isFalse);
      expect(forms[1].body, contains('name="file"'));
      expect(forms[1].body, contains('name="consent"'));
      expect(find.text('심사 대기 중'), findsOneWidget);
    });

    testWidgets('201 APPROVED인데 /auth/me가 실패하면 "새로고침" 안내가 보인다', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(201, _approved()))
        ..on('GET', '/auth/me', FakeReply(500));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text(permitApprovedReload), findsOneWidget);
    });

    testWidgets('동의 문구를 눌러도 체크가 바뀐다', (tester) async {
      await _pump(tester, _noneAdapter());
      await tester.tap(find.text(permitConsentText));
      await tester.pump();
      expect(tester.widget<Checkbox>(_checkbox).value, isTrue);
      await tester.tap(find.text(permitConsentText));
      await tester.pump();
      expect(tester.widget<Checkbox>(_checkbox).value, isFalse);
    });

    testWidgets('상태 GET이 401(재발급도 401)이면 다시 로그인 안내', (tester) async {
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pump(tester, fake);
      expect(find.byType(ReloginNotice), findsOneWidget);
    });

    testWidgets('인증 회원으로 시작했다가 학교가 사라지면 상태 GET 1번 → 신청 폼', (tester) async {
      final fake = FakeAdapter()..on('GET', _path, FakeReply(200, _none()));
      await _pump(tester, fake, user: userJson(school: _school));
      expect(fake.count('GET', _path), 0);
      _lastAuth!.updateUser(AuthUser.fromJson(userJson())); // 인증이 풀림
      await tester.pumpAndSettle();
      expect(fake.count('GET', _path), 1);
      expect(find.text('유학생 인증 완료 · 서울대'), findsNothing);
      expect(_checkbox, findsOneWidget);
      expect(_submitButton, findsOneWidget);
    });

    testWidgets('시간 초과: 전용 문구 + 상태 GET 1번 더, 버튼 다시 활성', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply.timeout())
        ..on('GET', _path, FakeReply(200, _none()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text(permitNotReceived), findsOneWidget); // 시간 초과 문구는 다시 확인한 결과 문구로 바뀐다
      expect(fake.count('GET', _path), 2);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('시간 초과 뒤 확인해 보니 접수돼 있으면 "심사 대기 중"', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply.timeout())
        ..on('GET', _path, FakeReply(200, _pending()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text('심사 대기 중'), findsOneWidget);
      expect(find.text(permitTimeoutMessage), findsNothing);
    });

    testWidgets('401: 다시 로그인 안내(링크 포함), 버튼 다시 활성', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.byType(ReloginNotice), findsOneWidget);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(tester.widget<FilledButton>(_submitButton).onPressed, isNotNull);
    });

    testWidgets('실패 뒤 다시 누르면 새 요청이 나가고 이전 오류 문구는 지워진다', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()
        ..on('POST', _path, FakeReply(503))
        ..on('POST', _path, FakeReply(201, _pending()));
      await _pump(tester, fake);
      await _fill(tester, picker);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(find.text(permitSubmitErrors[503]!), findsOneWidget);
      await tester.tap(_submitButton);
      await tester.pumpAndSettle();
      expect(fake.count('POST', _path), 2);
      expect(find.text(permitSubmitErrors[503]!), findsNothing);
      expect(find.text('심사 대기 중'), findsOneWidget);
    });
  });
}
