// S20-g1 유학생 인증 추가 검사: 문구 글자 비교, 미리보기 반납, 화면 떠난 뒤 응답, 키보드·접근성, 좁은 화면, 중고 사진 선택기 영향.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/common/file_picker.dart';
import 'package:board_web/permit/permit_reject_reasons.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:board_web/permit/permit_texts.dart';
import 'package:board_web/permit/permit_verification_section.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _path = '/users/me/permit-verification';
final _submitButton = find.widgetWithText(FilledButton, '인증 신청');
final _pickButton = find.widgetWithText(OutlinedButton, '사진 고르기');
final _checkbox = find.byType(Checkbox);

FakeAdapter _noneAdapter() => FakeAdapter()..on('GET', _path, FakeReply(200, {'status': 'NONE'}));

// 섹션만 띄운다. 폭을 바꾸고 싶으면 width, 중간에 치울 수 있게 보이기 스위치(show)를 받는다
Future<LoadedAuthState> _pump(WidgetTester tester, FakeAdapter fake, {double? width, ValueNotifier<bool>? show}) async {
  useTallScreen(tester);
  if (width != null) tester.view.physicalSize = Size(width, 1800);
  final auth = LoadedAuthState(adapter: fake, user: userJson());
  addTearDown(auth.dispose);
  await tester.pumpWidget(
    Provider<ApiClient>.value(
      value: auth.api,
      child: ChangeNotifierProvider<AuthState>.value(
        value: auth,
        child: MaterialApp(
          theme: buildAppTheme(),
          home: Scaffold(
            body: SingleChildScrollView(
              child: ValueListenableBuilder<bool>(
                valueListenable: show ?? ValueNotifier(true),
                builder: (_, on, _) => on ? const PermitVerificationSection() : const SizedBox(),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return auth;
}

Future<void> _choose(WidgetTester tester, FakePhotoPicker picker, List<PickedPhoto> files) async {
  await tester.tap(_pickButton);
  await tester.pump();
  picker.choose(files);
  await tester.pumpAndSettle();
}

void main() {
  group('문구 글자 그대로 (backlog S20-g 결정 줄)', () {
    testWidgets('가림 안내 두 줄과 반려 사유 7개가 정해 둔 글자와 같다', (tester) async {
      expect(permitMaskHide, '가려 주세요: 여권번호, 생년월일, 이메일, 재정 금액');
      expect(permitMaskShow, '보이게 두세요: 국적, 학교명, 표준입학허가서 번호');
      expect(permitRejectReasons, {
        'BLURRY': '사진이 흐려서 글자를 읽을 수 없어요',
        'NOT_PERMIT': '표준입학허가서가 아니에요',
        'NOT_MASKED': '가려야 할 정보가 보여요. 가리고 다시 올려 주세요',
        'UNSUPPORTED_SCHOOL': '아직 지원하지 않는 학교예요',
        'DUPLICATE': '이미 다른 계정에서 인증한 허가서예요',
        'EXPIRED': '14일 안에 확인하지 못했어요. 다시 신청해 주세요',
        'OTHER': '확인할 수 없었어요. 다시 신청해 주세요',
      });
    });

    testWidgets('화면에 실제로 보이는 동의 글자도 정해 둔 문장 그대로다', (tester) async {
      await _pump(tester, _noneAdapter());
      final shown = tester.widget<Text>(find.textContaining('표준입학허가서 사진을 유학생·학교 확인 목적으로')).data;
      expect(
        shown,
        '표준입학허가서 사진을 유학생·학교 확인 목적으로 수집합니다. 사진은 심사가 끝나면 바로 지우고, 늦어도 14일 뒤 삭제합니다. '
        '허가서 번호는 중복 확인용 암호화 값(해시)만 보관합니다. 사진은 글자 확인을 위해 Google Cloud Vision으로 전송될 수 있습니다. '
        '언제든 철회할 수 있습니다.',
      );
    });
  });

  group('미리보기 주소 반납', () {
    testWidgets('사진을 바꿔 고르면 이전 사진 주소가 반납되고 새 사진만 남는다', (tester) async {
      final picker = installFakePicker();
      final log = PhotoLog();
      await _pump(tester, _noneAdapter());
      await _choose(tester, picker, [fakePhoto('a.png', type: 'image/png', log: log)]);
      expect(log.released, isEmpty);
      await _choose(tester, picker, [fakePhoto('b.png', type: 'image/png', log: log)]);
      expect(log.released, ['a.png']);
      expect(find.text('b.png'), findsOneWidget);
      expect(find.text('a.png'), findsNothing);
    });

    testWidgets('화면이 사라지면 고른 사진 주소가 반납된다', (tester) async {
      final picker = installFakePicker();
      final log = PhotoLog();
      final show = ValueNotifier(true);
      await _pump(tester, _noneAdapter(), show: show);
      await _choose(tester, picker, [fakePhoto('a.png', type: 'image/png', log: log)]);
      show.value = false;
      await tester.pumpAndSettle();
      expect(log.released, ['a.png']);
    });

    testWidgets('화면이 사라진 뒤에 고른 사진은 받지 않고 바로 반납한다 (오류 없음)', (tester) async {
      final picker = installFakePicker();
      final log = PhotoLog();
      final show = ValueNotifier(true);
      await _pump(tester, _noneAdapter(), show: show);
      await tester.tap(_pickButton);
      await tester.pump();
      show.value = false;
      await tester.pumpAndSettle();
      picker.choose([fakePhoto('late.png', type: 'image/png', log: log)]);
      await tester.pump();
      expect(log.released, ['late.png']);
      expect(tester.takeException(), isNull);
    });

    testWidgets('여러 장이 한꺼번에 들어와도 첫 장만 쓰고 나머지는 반납한다', (tester) async {
      final picker = installFakePicker();
      final log = PhotoLog();
      await _pump(tester, _noneAdapter());
      await _choose(tester, picker, [
        fakePhoto('one.png', type: 'image/png', log: log),
        fakePhoto('two.png', type: 'image/png', log: log),
      ]);
      expect(find.text('one.png'), findsOneWidget);
      expect(log.released, ['two.png']);
    });
  });

  group('신청 중에 화면을 떠났다 돌아올 때', () {
    testWidgets('섹션이 사라진 뒤 응답(201 PENDING)이 와도 예외가 없다', (tester) async {
      final picker = installFakePicker();
      final gate = Completer<void>();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(201, {'status': 'PENDING'}, gate));
      final show = ValueNotifier(true);
      await _pump(tester, fake, show: show);
      await tester.tap(_checkbox);
      await tester.pump();
      await _choose(tester, picker, [fakePhoto('p.png', type: 'image/png')]);
      await tester.tap(_submitButton);
      await tester.pump(const Duration(milliseconds: 50));
      show.value = false; // 신청 중에 화면을 떠난다
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('섹션이 사라진 뒤 실패 응답(409)이 와도 예외가 없고 상태 GET을 더 부르지 않는다', (tester) async {
      final picker = installFakePicker();
      final gate = Completer<void>();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(409, null, gate));
      final show = ValueNotifier(true);
      await _pump(tester, fake, show: show);
      await tester.tap(_checkbox);
      await tester.pump();
      await _choose(tester, picker, [fakePhoto('p.png', type: 'image/png')]);
      await tester.tap(_submitButton);
      await tester.pump(const Duration(milliseconds: 50));
      show.value = false;
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(fake.count('GET', _path), 1);
    });

    testWidgets('상태 GET 중에 떠나도 예외가 없다', (tester) async {
      final gate = Completer<void>();
      final fake = FakeAdapter()..on('GET', _path, FakeReply(200, {'status': 'NONE'}, gate));
      useTallScreen(tester);
      final show = ValueNotifier(true);
      final auth = LoadedAuthState(adapter: fake, user: userJson());
      addTearDown(auth.dispose);
      await tester.pumpWidget(
        Provider<ApiClient>.value(
          value: auth.api,
          child: ChangeNotifierProvider<AuthState>.value(
            value: auth,
            child: MaterialApp(
              theme: buildAppTheme(),
              home: Scaffold(
                body: ValueListenableBuilder<bool>(
                  valueListenable: show,
                  builder: (_, on, _) => on ? const PermitVerificationSection() : const SizedBox(),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      show.value = false;
      await tester.pump();
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('내 정보에서 신청 중에 홈으로 갔다가 돌아오면 폼이 다시 뜨고 예외가 없다', (tester) async {
      final picker = installFakePicker();
      final gate = Completer<void>();
      final fake = FakeAdapter()
        ..on('GET', _path, FakeReply(200, {'status': 'NONE'}))
        ..on('POST', _path, FakeReply(201, {'status': 'PENDING'}, gate))
        ..on('GET', _path, FakeReply(200, {'status': 'PENDING'}));
      final app = await pumpApp(tester, location: '/me', user: userJson(), adapter: fake);
      await tester.tap(_checkbox);
      await tester.pump();
      await _choose(tester, picker, [fakePhoto('p.png', type: 'image/png')]);
      await tester.tap(_submitButton);
      await tester.pump(const Duration(milliseconds: 50));
      app.router.go('/'); // 신청 중에 다른 페이지로
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      app.router.go('/me'); // 돌아온다 → 새로 상태를 물어 "심사 대기 중"
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('심사 대기 중'), findsOneWidget);
    });
  });

  group('키보드·접근성', () {
    testWidgets('Tab으로 체크박스에 가서 스페이스로 동의하고, 사진 뒤 버튼을 키보드로 누른다', (tester) async {
      final picker = installFakePicker();
      final fake = _noneAdapter()..on('POST', _path, FakeReply(201, {'status': 'PENDING'}));
      await _pump(tester, fake);
      await tester.sendKeyEvent(LogicalKeyboardKey.tab); // 첫 포커스 = 체크박스
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.space);
      await tester.pump();
      expect(tester.widget<Checkbox>(_checkbox).value, isTrue);

      await tester.sendKeyEvent(LogicalKeyboardKey.tab); // 다음 = [사진 고르기]
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pump();
      expect(picker.opened, 1);
      picker.choose([fakePhoto('k.png', type: 'image/png')]);
      await tester.pumpAndSettle();

      await tester.sendKeyEvent(LogicalKeyboardKey.tab); // 다음 = [인증 신청]
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(fake.count('POST', _path), 1);
      expect(find.text('심사 대기 중'), findsOneWidget);
    });

    testWidgets('접근성 트리: 체크박스가 동의 문구와 한 덩어리 라벨이고, 버튼 이름이 읽힌다', (tester) async {
      final semantics = tester.ensureSemantics();
      await _pump(tester, _noneAdapter());
      final data = tester.getSemantics(_checkbox).getSemanticsData();
      expect(data.label, contains('표준입학허가서 사진을 유학생·학교 확인 목적으로'));
      expect(tester.getSemantics(_checkbox), matchesSemantics(hasCheckedState: true, isChecked: false, hasEnabledState: true, isEnabled: true, isFocusable: true, hasTapAction: true, hasFocusAction: true, label: data.label, textDirection: TextDirection.ltr));
      expect(find.bySemanticsLabel('사진 고르기'), findsOneWidget);
      expect(find.bySemanticsLabel('인증 신청'), findsOneWidget);
      semantics.dispose();
    });
  });

  group('좁은 화면', () {
    for (final w in [320.0, 375.0]) {
      testWidgets('폭 ${w.toInt()}px: 반려 폼 + 미리보기 + 오류 문구가 모두 떠도 넘치지 않는다', (tester) async {
        final picker = installFakePicker();
        final fake = FakeAdapter()
          ..on('GET', _path, FakeReply(200, {'status': 'REJECTED', 'rejectReason': 'NOT_MASKED'}))
          ..on('POST', _path, FakeReply(429));
        await _pump(tester, fake, width: w);
        await tester.tap(_checkbox);
        await tester.pump();
        await _choose(tester, picker, [fakePhoto('아주아주아주아주아주아주긴파일이름입니다.png', type: 'image/png')]);
        await tester.tap(_submitButton);
        await tester.pumpAndSettle();
        expect(find.text(permitSubmitErrors[429]!), findsOneWidget);
        expect(tester.takeException(), isNull); // 오버플로는 예외로 잡힌다
        expect(tester.getSize(find.text(permitConsentText)).width, lessThanOrEqualTo(w));
      });
    }
  });

  group('중고 사진 올리기는 그대로 여러 장', () {
    testWidgets('중고 글쓰기의 [사진 추가]는 여러 장 허용(multiple=true)으로 창을 연다', (tester) async {
      useTallScreen(tester);
      final picker = installFakePicker();
      await pumpApp(tester, location: '/market/new', user: userJson(id: 1, school: marketSeoul), adapter: FakeAdapter());
      final add = find.byKey(const ValueKey('photo-add-button'));
      await tester.ensureVisible(add);
      await tester.tap(add);
      await tester.pump();
      expect(picker.opened, 1);
      expect(picker.lastMultiple, isTrue);
    });
  });
}
