// S16-g2 학교 메일 인증 섹션 테스트: React SchoolVerificationForm과 같은 동작
// (완료 표시, 도메인 안내, 1·2단계 버튼, 발송 성공·실패, 60초 쿨다운, 코드 칸 규칙, 확인 성공·실패, 요청 중 잠금, 타이머 정리)을 본다.
// 쿨다운은 "지금 시각"을 바꿔 끼울 수 있는 섹션(now 인자)으로 시계를 직접 돌려 확인한다.
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/school_verification_section.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _csrf = '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요';
const _intro = '학교 메일로 인증하면 글쓰기·댓글·공감·신고를 할 수 있어요';
const _sentInfo = '인증 코드를 보냈어요. 코드는 10분 동안 유효해요';
const _spam = '메일이 안 보이면 스팸함도 확인해 주세요';
const _sixHint = '6자리를 입력하면 확인할 수 있어요';
const _school = {'id': 1, 'name': '서울대'};

const _codeUrl = '/users/me/school-verification/code';
const _verifyUrl = '/users/me/school-verification';

final _emailField = find.widgetWithText(TextField, '학교 메일');
final _codeField = find.widgetWithText(TextField, '인증 코드 (6자리)');
final _sendButton = find.widgetWithText(FilledButton, '코드 보내기');
final _haveCodeButton = find.widgetWithText(OutlinedButton, '이미 코드를 받았어요');
final _confirmButton = find.widgetWithText(FilledButton, '확인');
final _resendButton = find.widgetWithText(OutlinedButton, '다시 보내기');
final _changeButton = find.widgetWithText(OutlinedButton, '메일 바꾸기');

List<Map<String, Object>> _schools(List<List<String>> domainLists) => [
  for (var i = 0; i < domainLists.length; i++) {'id': i + 1, 'name': '학교${i + 1}', 'emailDomains': domainLists[i]},
];

// 섹션 하나를 띄운 결과. clock을 바꾸면 섹션이 보는 "지금"이 바뀐다
class _Harness {
  _Harness(this.auth, this.adapter);
  final LoadedAuthState auth;
  final FakeAdapter adapter;
  DateTime clock = DateTime(2026, 1, 1, 12);
  final List<Map<dynamic, dynamic>> bodies = [];

  // 시계를 앞으로 돌리고 타이머(0.5초마다)가 돌게 가짜 시간도 같이 흐르게 한다
  Future<void> advance(WidgetTester tester, Duration d) async {
    clock = clock.add(d);
    await tester.pump(d);
  }
}

// 요청 한 번이 끝나도록 몇 번 그린다 (쿨다운 타이머가 있으면 pumpAndSettle이 끝나지 않아 쓰지 않는다)
Future<void> _flush(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.pump(const Duration(milliseconds: 1));
  }
}

Future<_Harness> _pump(
  WidgetTester tester, {
  FakeAdapter? adapter,
  Map<String, dynamic>? user,
  FakeReply? schools,
}) async {
  useTallScreen(tester);
  final fake = adapter ?? FakeAdapter();
  // /schools 는 기본으로 학교 없음(200 [])
  fake.on('GET', '/schools', schools ?? FakeReply(200, []));
  final auth = LoadedAuthState(adapter: fake, user: user ?? userJson());
  addTearDown(auth.dispose);
  final h = _Harness(auth, fake);
  fake.onRequestBody = (d) {
    if (d is Map) h.bodies.add(d);
  };
  await tester.pumpWidget(
    Provider<ApiClient>.value(
      value: auth.api,
      child: ChangeNotifierProvider<AuthState>.value(
        value: auth,
        child: MaterialApp(
          theme: buildAppTheme(),
          home: Scaffold(
            body: SingleChildScrollView(child: SchoolVerificationSection(now: () => h.clock)),
          ),
        ),
      ),
    ),
  );
  await _flush(tester);
  return h;
}

// 1단계에서 메일을 적고 [코드 보내기]
Future<void> _send(WidgetTester tester, {String email = 'a@snu.ac.kr'}) async {
  await tester.enterText(_emailField, email);
  await tester.tap(_sendButton);
  await _flush(tester);
}

// 2단계로 가서 6자리를 적고 [확인]
Future<void> _goCodeAndConfirm(WidgetTester tester, {String code = '123456'}) async {
  await tester.tap(_haveCodeButton);
  await tester.pump();
  await tester.enterText(_codeField, code);
  await tester.pump();
  await tester.tap(_confirmButton);
  await _flush(tester);
}

bool _enabled(WidgetTester tester, Finder f) {
  final w = tester.widget(f);
  if (w is FilledButton) return w.onPressed != null;
  if (w is OutlinedButton) return w.onPressed != null;
  throw StateError('버튼이 아님');
}

String _text(WidgetTester tester, Finder f) => tester.widget<TextField>(f).controller!.text;

bool _hasNotice(WidgetTester tester, NoticeKind kind, String message) =>
    tester.widgetList<Notice>(find.byType(Notice)).any((n) => n.kind == kind && n.message == message);

void main() {
  group('표시', () {
    testWidgets('인증한 회원: 뱃지 + "졸업 후에도 유지돼요", 소개·폼은 없다', (tester) async {
      await _pump(tester, user: userJson(school: _school));
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(find.ancestor(of: find.text('학교 인증 완료 · 서울대'), matching: find.byType(AppBadge)), findsOneWidget);
      expect(find.text('졸업 후에도 유지돼요'), findsOneWidget);
      expect(find.text(_intro), findsNothing);
      expect(_emailField, findsNothing);
      expect(_sendButton, findsNothing);
    });

    testWidgets('미인증 회원: 소개 + 1단계 칸·버튼 둘, 이메일 키보드·autofill', (tester) async {
      await _pump(tester);
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text(_intro), findsOneWidget);
      expect(find.textContaining('학교 인증 완료'), findsNothing);
      final field = tester.widget<TextField>(_emailField);
      expect(field.keyboardType, TextInputType.emailAddress);
      expect(field.autofillHints, [AutofillHints.email]);
      expect(_enabled(tester, _sendButton), isTrue);
      expect(_enabled(tester, _haveCodeButton), isTrue);
      expect(_codeField, findsNothing);
    });

    testWidgets('/schools 는 처음 한 번만 받는다', (tester) async {
      final h = await _pump(tester);
      expect(h.adapter.count('GET', '/schools'), 1);
    });
  });

  group('도메인 안내', () {
    testWidgets('도메인이 3개를 넘으면 앞 3개 + " 등"', (tester) async {
      await _pump(
        tester,
        schools: FakeReply(
          200,
          _schools([
            ['a.kr', 'b.kr'],
            ['c.kr', 'd.kr'],
          ]),
        ),
      );
      expect(find.text('@a.kr, @b.kr, @c.kr 등 학교 메일만 쓸 수 있어요'), findsOneWidget);
    });

    testWidgets('정확히 3개면 " 등" 없이', (tester) async {
      await _pump(
        tester,
        schools: FakeReply(
          200,
          _schools([
            ['a.kr'],
            ['b.kr', 'c.kr'],
          ]),
        ),
      );
      expect(find.text('@a.kr, @b.kr, @c.kr 학교 메일만 쓸 수 있어요'), findsOneWidget);
    });

    testWidgets('도메인이 없으면 안내를 숨긴다', (tester) async {
      await _pump(tester, schools: FakeReply(200, _schools([[], []])));
      expect(find.textContaining('학교 메일만 쓸 수 있어요'), findsNothing);
    });

    testWidgets('/schools 가 실패해도 조용히 넘어가고 인증은 된다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      await _pump(tester, adapter: adapter, schools: FakeReply(500));
      expect(find.textContaining('학교 메일만 쓸 수 있어요'), findsNothing);
      expect(find.byType(Notice), findsNothing);
      await _send(tester);
      expect(_codeField, findsOneWidget);
    });
  });

  group('1단계 → 2단계', () {
    testWidgets('[이미 코드를 받았어요]: 안내를 지우고 2단계로, 메일 칸은 사라진다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(400));
      await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(find.text('학교 메일 주소를 확인해 주세요'), findsOneWidget);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      expect(find.text('학교 메일 주소를 확인해 주세요'), findsNothing);
      expect(_codeField, findsOneWidget);
      expect(_emailField, findsNothing);
    });
  });

  group('코드 보내기', () {
    testWidgets('성공: 메일을 trim해 보내고 2단계 · 코드 칸 비움 · 안내 · 쿨다운 시작', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester, email: '  a@snu.ac.kr  ');
      expect(h.bodies, [
        {'email': 'a@snu.ac.kr'},
      ]);
      expect(_codeField, findsOneWidget);
      expect(_text(tester, _codeField), '');
      expect(_hasNotice(tester, NoticeKind.info, _sentInfo), isTrue);
      expect(find.text('다시 보내기 (60초)'), findsOneWidget);
      expect(_enabled(tester, find.widgetWithText(OutlinedButton, '다시 보내기 (60초)')), isFalse);
    });

    testWidgets('쿨다운: 남은 초가 줄고(올림), 끝나면 [다시 보내기]가 다시 켜진다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      await h.advance(tester, const Duration(seconds: 10));
      expect(find.text('다시 보내기 (50초)'), findsOneWidget);
      await h.advance(tester, const Duration(milliseconds: 49500)); // 59.5초 지남 → 0.5초 남음 → 올림 1
      expect(find.text('다시 보내기 (1초)'), findsOneWidget);
      await h.advance(tester, const Duration(milliseconds: 500)); // 60초
      expect(find.text('다시 보내기'), findsOneWidget);
      expect(_enabled(tester, _resendButton), isTrue);
    });

    testWidgets('쿨다운 중엔 1단계의 [코드 보내기 (N초)]도 꺼져 있고, 끝나면 켜진다 ([이미 코드를 받았어요]는 켜짐)', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      await tester.tap(_changeButton);
      await tester.pump();
      expect(_emailField, findsOneWidget);
      expect(find.text('코드 보내기 (60초)'), findsOneWidget);
      expect(_enabled(tester, find.widgetWithText(FilledButton, '코드 보내기 (60초)')), isFalse);
      expect(_enabled(tester, _haveCodeButton), isTrue);
      await h.advance(tester, const Duration(seconds: 30));
      expect(find.text('코드 보내기 (30초)'), findsOneWidget);
      await h.advance(tester, const Duration(seconds: 30));
      expect(_enabled(tester, _sendButton), isTrue);
    });

    testWidgets('[다시 보내기]도 같은 요청: 코드 칸 비우고 쿨다운 다시 60초', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(202))
        ..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      await h.advance(tester, const Duration(seconds: 60));
      await tester.enterText(_codeField, '123');
      await tester.tap(_resendButton);
      await _flush(tester);
      expect(adapter.count('POST', _codeUrl), 2);
      expect(_text(tester, _codeField), '');
      expect(find.text('다시 보내기 (60초)'), findsOneWidget);
    });

    testWidgets('요청 중: 보내는 중... · 두 버튼 모두 잠김, 끝나면 풀린다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(400, null, gate));
      await _pump(tester, adapter: adapter);
      await tester.enterText(_emailField, 'a@snu.ac.kr');
      await tester.tap(_sendButton);
      await tester.pump();
      expect(find.text('보내는 중...'), findsOneWidget);
      expect(_enabled(tester, find.widgetWithText(FilledButton, '보내는 중...')), isFalse);
      expect(_enabled(tester, _haveCodeButton), isFalse);
      gate.complete();
      await _flush(tester);
      expect(_enabled(tester, _sendButton), isTrue);
      expect(_enabled(tester, _haveCodeButton), isTrue);
    });

    // 오류 문구: 1단계에 남고 메일 칸 내용도 그대로
    final failures = <(String, FakeReply, String)>[
      ('400', FakeReply(400), '학교 메일 주소를 확인해 주세요'),
      ('403', FakeReply(403), _csrf),
      ('500', FakeReply(500), '메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요'),
      ('그 밖(502)', FakeReply(502), '코드를 보내지 못했어요'),
      ('네트워크 오류', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (label, reply, message) in failures) {
      testWidgets('실패 $label: "$message", 1단계 유지·쿨다운 없음', (tester) async {
        final adapter = FakeAdapter()..on('POST', _codeUrl, reply);
        await _pump(tester, adapter: adapter);
        await _send(tester);
        expect(_hasNotice(tester, NoticeKind.error, message), isTrue);
        expect(_text(tester, _emailField), 'a@snu.ac.kr');
        expect(_enabled(tester, _sendButton), isTrue);
        expect(_codeField, findsNothing);
      });
    }

    testWidgets('실패 400: 학교 목록이 있으면 "(지원 학교: A, B)"를 덧붙인다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(400));
      await _pump(
        tester,
        adapter: adapter,
        schools: FakeReply(
          200,
          _schools([
            ['a.kr'],
            ['b.kr'],
          ]),
        ),
      );
      await _send(tester);
      expect(find.text('학교 메일 주소를 확인해 주세요 (지원 학교: 학교1, 학교2)'), findsOneWidget);
    });

    testWidgets('실패 401(재발급도 401): 로그인 안내, 입력 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pump(tester, adapter: adapter);
      await tester.enterText(_emailField, 'a@snu.ac.kr');
      await tester.tap(_sendButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await _flush(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('코드를 보내지 못했어요'), findsNothing);
      expect(_text(tester, _emailField), 'a@snu.ac.kr');
    });

    testWidgets('실패 409: 오류 문구 + /auth/me 를 다시 받는다 (아직 미인증이면 폼 유지)', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(409))
        ..on('GET', '/auth/me', FakeReply(200, userJson()));
      await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(_hasNotice(tester, NoticeKind.error, '이미 인증했거나 다른 계정이 인증에 쓴 메일이에요'), isTrue);
      expect(adapter.count('GET', '/auth/me'), 1);
      expect(_emailField, findsOneWidget);
    });

    testWidgets('실패 409: 다른 기기에서 이미 인증됐으면 /auth/me 로 완료 화면이 된다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(409))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(h.auth.user!.verifiedSchool!.name, '서울대');
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(_emailField, findsNothing);
    });

    testWidgets('실패 409 + /auth/me 도 실패: 조용히 넘어가고 오류 문구는 남는다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(409))
        ..on('GET', '/auth/me', FakeReply(500));
      await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(_hasNotice(tester, NoticeKind.error, '이미 인증했거나 다른 계정이 인증에 쓴 메일이에요'), isTrue);
      expect(_enabled(tester, _sendButton), isTrue);
    });

    testWidgets('실패 429: 오류가 아닌 안내(info) + 2단계로 + 쿨다운 시작', (tester) async {
      const msg = '이미 보낸 코드가 있으면 메일의 코드를 입력해 주세요 (1분에 한 번, 하루 10번까지 보낼 수 있어요)';
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(429));
      await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(_hasNotice(tester, NoticeKind.info, msg), isTrue);
      expect(tester.widgetList<Notice>(find.byType(Notice)).any((n) => n.kind == NoticeKind.error), isFalse);
      expect(_codeField, findsOneWidget);
      expect(find.text('다시 보내기 (60초)'), findsOneWidget);
    });
  });

  group('코드 칸', () {
    testWidgets('숫자만 받고 6자리까지만, 숫자 키보드·oneTimeCode', (tester) async {
      await _pump(tester);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      final field = tester.widget<TextField>(_codeField);
      expect(field.keyboardType, TextInputType.number);
      expect(field.autofillHints, [AutofillHints.oneTimeCode]);
      await tester.enterText(_codeField, '12a3-4567890');
      await tester.pump();
      expect(_text(tester, _codeField), '123456');
    });

    testWidgets('hint: 스팸함 안내는 항상, "6자리를…"는 6자리가 아닐 때만. [확인]도 6자리에서만 켜짐', (tester) async {
      await _pump(tester);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      expect(find.text(_spam), findsOneWidget);
      expect(find.text(_sixHint), findsOneWidget);
      expect(_enabled(tester, _confirmButton), isFalse);
      await tester.enterText(_codeField, '12345');
      await tester.pump();
      expect(find.text(_sixHint), findsOneWidget);
      expect(_enabled(tester, _confirmButton), isFalse);
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      expect(find.text(_spam), findsOneWidget);
      expect(find.text(_sixHint), findsNothing);
      expect(_enabled(tester, _confirmButton), isTrue);
    });

    testWidgets('[메일 바꾸기]: 1단계로 돌아가고 메일은 그대로, 안내는 지워진다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(find.text(_sentInfo), findsOneWidget);
      await tester.tap(_changeButton);
      await tester.pump();
      expect(_codeField, findsNothing);
      expect(_text(tester, _emailField), 'a@snu.ac.kr');
      expect(find.text(_sentInfo), findsNothing);
    });
  });

  group('코드 확인', () {
    testWidgets('성공: {code}를 보내고 회원 정보 갱신 → 완료 화면 (성공 문구 없음)', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(200, userJson(school: _school)));
      final h = await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(h.bodies, [
        {'code': '123456'},
      ]);
      expect(h.auth.user!.verifiedSchool!.name, '서울대');
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(find.text('졸업 후에도 유지돼요'), findsOneWidget);
      expect(find.byType(Notice), findsNothing);
      expect(_codeField, findsNothing);
    });

    testWidgets('요청 중: 확인 중... · 모든 버튼 잠김', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(400, null, gate));
      await _pump(tester, adapter: adapter);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      await tester.tap(_confirmButton);
      await tester.pump();
      expect(find.text('확인 중...'), findsOneWidget);
      expect(_enabled(tester, find.widgetWithText(FilledButton, '확인 중...')), isFalse);
      expect(_enabled(tester, _resendButton), isFalse);
      expect(_enabled(tester, _changeButton), isFalse);
      gate.complete();
      await _flush(tester);
      expect(_enabled(tester, _confirmButton), isTrue);
      expect(_enabled(tester, _resendButton), isTrue);
      expect(_enabled(tester, _changeButton), isTrue);
    });

    testWidgets('실패 400: "코드가 맞지 않아요…", 2단계·코드 유지', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(400));
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(_hasNotice(tester, NoticeKind.error, '코드가 맞지 않아요. 다시 입력해 주세요'), isTrue);
      expect(_text(tester, _codeField), '123456');
      expect(_emailField, findsNothing);
    });

    testWidgets('실패 403: 보안 토큰 문구, 2단계 유지', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(403));
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(_hasNotice(tester, NoticeKind.error, _csrf), isTrue);
      expect(_codeField, findsOneWidget);
    });

    testWidgets('실패 그 밖(500): "인증하지 못했어요", 2단계 유지', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(500));
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(_hasNotice(tester, NoticeKind.error, '인증하지 못했어요'), isTrue);
      expect(_codeField, findsOneWidget);
    });

    testWidgets('실패 네트워크 오류: 연결 문구, 2단계 유지', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply.networkError());
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(_hasNotice(tester, NoticeKind.error, '서버에 연결할 수 없습니다'), isTrue);
      expect(_codeField, findsOneWidget);
    });

    testWidgets('실패 401(재발급도 401): 로그인 안내, 2단계·코드 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _verifyUrl, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _pump(tester, adapter: adapter);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      await tester.tap(_confirmButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await _flush(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('인증하지 못했어요'), findsNothing);
      expect(_text(tester, _codeField), '123456');
    });

    testWidgets('실패 410: 1단계로·코드 비움·메일 유지 (쿨다운 없으면 문구만)', (tester) async {
      final adapter = FakeAdapter()..on('POST', _verifyUrl, FakeReply(410));
      await _pump(tester, adapter: adapter);
      await tester.tap(_haveCodeButton);
      await tester.pump();
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      await tester.tap(_confirmButton);
      await _flush(tester);
      expect(_hasNotice(tester, NoticeKind.error, '코드가 만료됐거나 너무 많이 틀렸어요. 코드를 다시 받아 주세요'), isTrue);
      expect(_emailField, findsOneWidget);
      expect(_codeField, findsNothing);
    });

    testWidgets('실패 410: 쿨다운이 남아 있으면 "(N초 뒤에 다시 받을 수 있어요)"를 덧붙이고 메일은 유지', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _codeUrl, FakeReply(202))
        ..on('POST', _verifyUrl, FakeReply(410));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      await h.advance(tester, const Duration(seconds: 20));
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      await tester.tap(_confirmButton);
      await _flush(tester);
      expect(
        _hasNotice(tester, NoticeKind.error, '코드가 만료됐거나 너무 많이 틀렸어요. 코드를 다시 받아 주세요 (40초 뒤에 다시 받을 수 있어요)'),
        isTrue,
      );
      expect(_text(tester, _emailField), 'a@snu.ac.kr');
      expect(_codeField, findsNothing);
    });

    testWidgets('실패 409: 1단계로·코드 비움 + /auth/me 다시 받기 (미인증이면 폼 유지)', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _verifyUrl, FakeReply(409))
        ..on('GET', '/auth/me', FakeReply(200, userJson()));
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(_hasNotice(tester, NoticeKind.error, '이미 인증됐거나 다른 계정이 쓴 메일이에요'), isTrue);
      expect(adapter.count('GET', '/auth/me'), 1);
      expect(_emailField, findsOneWidget);
      expect(_codeField, findsNothing);
    });

    testWidgets('실패 409: 이미 인증된 회원이면 /auth/me 로 완료 화면', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', _verifyUrl, FakeReply(409))
        ..on('GET', '/auth/me', FakeReply(200, userJson(school: _school)));
      await _pump(tester, adapter: adapter);
      await _goCodeAndConfirm(tester);
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(_emailField, findsNothing);
    });
  });

  group('타이머 정리', () {
    testWidgets('쿨다운 중에 화면이 사라져도 타이머가 남지 않는다 (남으면 테스트가 실패한다)', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      expect(find.text('다시 보내기 (60초)'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink()); // 섹션 제거 → dispose
      await h.advance(tester, const Duration(seconds: 5)); // 타이머가 남아 있으면 setState 에러
      expect(tester.takeException(), isNull);
    });

    testWidgets('쿨다운이 끝나면 타이머가 스스로 멈춘다', (tester) async {
      final adapter = FakeAdapter()..on('POST', _codeUrl, FakeReply(202));
      final h = await _pump(tester, adapter: adapter);
      await _send(tester);
      await h.advance(tester, const Duration(seconds: 60));
      // 타이머가 멈췄으면 더 이상 다음 그림이 예약되지 않는다
      await tester.pump(const Duration(seconds: 1));
      expect(tester.binding.hasScheduledFrame, isFalse);
    });
  });

  group('내 정보 화면 안에서', () {
    testWidgets('확인 성공 → 섹션이 완료 모양, 상단바에 "인증" 뱃지', (tester) async {
      useTallScreen(tester);
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, []))
        ..on('POST', _verifyUrl, FakeReply(200, userJson(school: _school)));
      await pumpApp(tester, location: '/me', user: userJson(), adapter: adapter);
      expect(find.text('인증'), findsNothing);
      await tester.tap(_haveCodeButton);
      await tester.pumpAndSettle();
      await tester.enterText(_codeField, '123456');
      await tester.pump();
      await tester.tap(_confirmButton);
      await tester.pumpAndSettle();
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(find.text('인증'), findsOneWidget); // 상단바 뱃지
      expect(_emailField, findsNothing);
    });
  });
}
