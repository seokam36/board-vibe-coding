// S16-h2 비자 후기 수정 화면 테스트: React VisaReviewEditPage와 같은 동작
// (불러오기 성공·실패, 폼 표시 조건, 처음 값, 현재 값 끼워 넣기, PUT 본문, 기록 교체, 실패 문구, 로그인 풀림)을 본다.
import 'dart:async';

import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';

const _path = '/visa-reviews/7/edit';

// 학교 인증을 마친 사용자 (id 1 = 기본 후기의 작성자)
Map<String, dynamic> _verified({int id = 1}) => userJson(id: id, school: visaSeoul);

// 후기·선택지를 성공으로 돌려주는 가짜 서버
FakeAdapter _adapter({
  FakeReply? review,
  FakeReply? options,
  String reviewPath = '/visa-reviews/7',
}) =>
    FakeAdapter()
      ..on('GET', reviewPath, review ?? FakeReply(200, reviewJson()))
      ..on('GET', '/visa-review-options', options ?? FakeReply(200, visaOptionsJson));

// 폼이 길어서 화면을 길게 만든 뒤 시험하는 testWidgets
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

final _visaTypeSelect = find.byKey(const ValueKey('visa-type-select'));
final _applicationSelect = find.byKey(const ValueKey('application-type-select'));
final _resultSelect = find.byKey(const ValueKey('result-select'));
final _receivedField = find.byKey(const ValueKey('received-date-field'));
final _resultDateField = find.byKey(const ValueKey('result-date-field'));
final _contentField = find.byKey(const ValueKey('content-field'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _savingButton = find.widgetWithText(FilledButton, '저장 중...');

// reviewJson 기본값 그대로의 PUT 본문
const _unchangedBody = {
  'visaType': 'D2',
  'applicationType': 'EXTENSION',
  'receivedDate': '2026-09-01',
  'resultDate': '2026-09-20',
  'result': 'APPROVED',
  'content': '서류는 빨리 냈어요',
};

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;

String _dropdownValue(WidgetTester tester, Finder select) => tester.widget<DropdownButton<String>>(select).value!;

List<String?> _dropdownItems(WidgetTester tester, Finder select) => [
      for (final i in tester.widget<DropdownButton<String>>(select).items!) i.value,
    ];

Future<void> _pick(WidgetTester tester, Finder select, String name) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester) async {
  await tester.tap(_saveButton);
  await tester.pumpAndSettle();
}

// 저장 요청 본문을 모아 두는 도우미
Object? _captureBody(FakeAdapter adapter, void Function(Object?) set) {
  adapter.onRequestBody = (d) {
    if (d is Map) set(d);
  };
  return null;
}

void main() {
  group('불러오기', () {
    _test('성공하면 제목·면책 문구·고정 학교 뱃지와 후기 값으로 채운 폼이 보인다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(find.text('비자 후기 수정'), findsOneWidget);
      expect(find.textContaining('후기는 개인 경험이라'), findsOneWidget);
      expect(adapter.count('GET', '/visa-reviews/7'), 1);
      expect(adapter.count('GET', '/visa-review-options'), 1);
      // 학교는 후기의 학교로 고정
      expect(find.text('서울대'), findsOneWidget);
      expect(find.bySemanticsLabel('학교: 서울대'), findsOneWidget);
      // 처음 값
      expect(_dropdownValue(tester, _visaTypeSelect), 'D2');
      expect(_dropdownValue(tester, _applicationSelect), 'EXTENSION');
      expect(_dropdownValue(tester, _resultSelect), 'APPROVED');
      expect(_text(tester, _receivedField), '2026-09-01');
      expect(_text(tester, _resultDateField), '2026-09-20');
      expect(_text(tester, _contentField), '서류는 빨리 냈어요');
      expect(find.text('${'서류는 빨리 냈어요'.length} / 2000'), findsOneWidget);
      expect(_saveButton, findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '취소'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '목록으로'), findsNothing); // 불러오기 실패 때만
      handle.dispose();
    });

    _test('불러오는 동안은 폼이 없고 "불러오는 중..."이 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(review: FakeReply(200, reviewJson(), gate));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_saveButton, findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(_saveButton, findsOneWidget);
    });

    // 실패 응답 → 보이는 문구. 폼은 숨기고 [목록으로]만 남긴다
    final failures = <(String, FakeReply, String)>[
      ('404', FakeReply(404), '후기를 찾을 수 없습니다'),
      ('400', FakeReply(400), '후기를 찾을 수 없습니다'),
      ('500', FakeReply(500), '후기를 불러오지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in failures) {
      _test('$name → "$message", 폼 없음, [목록으로]로 /visa-reviews', (tester) async {
        final adapter = _adapter(review: reply);
        final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_saveButton, findsNothing);
        expect(_contentField, findsNothing);
        await tester.tap(find.widgetWithText(OutlinedButton, '목록으로'));
        await tester.pumpAndSettle();
        expect(_uri(app), '/visa-reviews');
      });
    }

    _test('응답의 후기 id가 주소의 id와 다르면 폼을 보이지 않는다', (tester) async {
      final adapter = _adapter(review: FakeReply(200, reviewJson(id: 7)), reviewPath: '/visa-reviews/8');
      await pumpApp(tester, location: '/visa-reviews/8/edit', user: _verified(), adapter: adapter);
      expect(_saveButton, findsNothing);
      expect(find.byType(Notice), findsOneWidget); // 면책 문구만, 에러 안내도 없다
    });

    _test('/visa-reviews/01/edit 는 숫자로 비교해 후기 1번으로 통과한다 (요청 경로는 01 그대로)', (tester) async {
      final adapter = _adapter(review: FakeReply(200, reviewJson(id: 1)), reviewPath: '/visa-reviews/01');
      await pumpApp(tester, location: '/visa-reviews/01/edit', user: _verified(), adapter: adapter);
      expect(adapter.count('GET', '/visa-reviews/01'), 1);
      expect(_saveButton, findsOneWidget);
    });

    _test('본인 후기가 아니어도 주소로 들어오면 폼이 열린다 (막는 건 서버의 403)', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(id: 2), adapter: adapter);
      expect(_saveButton, findsOneWidget);
    });

    _test('[취소]를 누르면 상세 /visa-reviews/7', (tester) async {
      final adapter = _adapter()..on('GET', '/visa-reviews/7', FakeReply(200, reviewJson()));
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await tester.tap(find.widgetWithText(OutlinedButton, '취소'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/7');
    });
  });

  group('선택지 끼워 넣기 (withCurrent)', () {
    _test('받은 목록에 현재 값이 없으면 맨 앞에 끼워 넣는다', (tester) async {
      // 서버 선택지에 D2·EXTENSION·APPROVED 가 없다
      final adapter = _adapter(
        options: FakeReply(200, {
          'visaTypes': [
            {'code': 'D4', 'name': '어학연수(D-4)'},
          ],
          'applicationTypes': [
            {'code': 'CHANGE', 'name': '변경'},
          ],
          'results': [
            {'code': 'DENIED', 'name': '불허'},
          ],
        }),
      );
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(_dropdownItems(tester, _visaTypeSelect), ['', 'D2', 'D4']);
      expect(_dropdownItems(tester, _applicationSelect), ['', 'EXTENSION', 'CHANGE']);
      expect(_dropdownItems(tester, _resultSelect), ['', 'APPROVED', 'DENIED']);
      expect(_dropdownValue(tester, _visaTypeSelect), 'D2'); // 현재 값이 골라진 채
      expect(find.text('유학(D-2)'), findsOneWidget);
    });

    _test('이미 목록에 있으면 중복해서 끼워 넣지 않는다', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      expect(_dropdownItems(tester, _visaTypeSelect), ['', 'D2', 'D4']);
    });

    _test('선택지를 못 받아도 현재 값으로 저장할 수 있고, 안내 문구가 다르다', (tester) async {
      Object? body;
      final adapter = _adapter(options: FakeReply(500))..on('PUT', '/visa-reviews/7', FakeReply(200));
      _captureBody(adapter, (d) => body = d);
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다. 지금 값으로만 저장할 수 있습니다'), findsOneWidget);
      expect(_dropdownItems(tester, _visaTypeSelect), ['', 'D2']);
      await _save(tester);
      expect(adapter.count('PUT', '/visa-reviews/7'), 1);
      expect(body, _unchangedBody);
    });
  });

  group('저장 요청', () {
    _test('아무것도 안 바꾸고 저장하면 PUT /visa-reviews/7 에 처음 값 6개 키가 간다 (schoolId 없음)', (tester) async {
      Object? body;
      final adapter = _adapter()..on('PUT', '/visa-reviews/7', FakeReply(200));
      _captureBody(adapter, (d) => body = d);
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _save(tester);
      expect(adapter.count('PUT', '/visa-reviews/7'), 1);
      expect(body, _unchangedBody);
      expect((body as Map).containsKey('schoolId'), isFalse);
    });

    _test('드롭다운·본문을 바꾸면 바뀐 값이 간다 (본문은 trim하지 않는다)', (tester) async {
      Object? body;
      final adapter = _adapter()..on('PUT', '/visa-reviews/7', FakeReply(200));
      _captureBody(adapter, (d) => body = d);
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pick(tester, _visaTypeSelect, '어학연수(D-4)');
      await _pick(tester, _resultSelect, '불허');
      await tester.enterText(_contentField, ' 바꾼 메모 ');
      await _save(tester);
      expect(body, {
        ..._unchangedBody,
        'visaType': 'D4',
        'result': 'DENIED',
        'content': ' 바꾼 메모 ',
      });
    });

    _test('본문을 지우고 저장해도 된다 (빈 글자)', (tester) async {
      Object? body;
      final adapter = _adapter()..on('PUT', '/visa-reviews/7', FakeReply(200));
      _captureBody(adapter, (d) => body = d);
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await tester.enterText(_contentField, '');
      await _save(tester);
      expect((body as Map)['content'], '');
    });

    _test('날짜 칸을 다시 열면 기존 날짜가 처음 보이고, 새 날을 고르면 그 날이 간다', (tester) async {
      Object? body;
      final adapter = _adapter()..on('PUT', '/visa-reviews/7', FakeReply(200));
      _captureBody(adapter, (d) => body = d);
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await tester.tap(_receivedField);
      await tester.pumpAndSettle();
      // 값이 있으니 그 날(2026-09-01)이 처음 보인다
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(dialog.initialDate, DateTime(2026, 9, 1));
      await tester.tap(find.text('5')); // 열린 달(2026년 9월)의 5일
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      expect(_text(tester, _receivedField), '2026-09-05');
      await _save(tester);
      expect((body as Map)['receivedDate'], '2026-09-05');
    });

    _test('저장 중에는 "저장 중..."으로 잠기고, 성공하면 상세로 가며 기록을 교체한다', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()
        ..on('PUT', '/visa-reviews/7', FakeReply(200, null, gate))
        ..on('GET', '/visa-reviews/7', FakeReply(200, reviewJson())); // 이동한 상세가 부르는 요청
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);

      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull);
      expect(adapter.count('PUT', '/visa-reviews/7'), 1);
      expect(_uri(app), _path);

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/7');
      expect(adapter.count('PUT', '/visa-reviews/7'), 1);
      // 기록을 새로 쌓지 않고 교체 (React의 replace): 수정 폼이 뒤로 가기에 남지 않는다
      expect(nav.last.path, '/visa-reviews/7');
      expect(nav.last.replace, isTrue);
    });
  });

  group('저장 실패', () {
    // (이름, 응답, 로그인한 사용자 id, 보이는 문구). 후기 작성자는 id 1
    final cases = <(String, FakeReply, int, String)>[
      (
        '400',
        FakeReply(400),
        1,
        '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
      ),
      ('403 본인 후기', FakeReply(403), 1, '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      ('403 남의 후기', FakeReply(403), 2, '본인 후기만 수정할 수 있습니다'),
      ('404', FakeReply(404), 1, '후기를 찾을 수 없습니다'),
      ('500', FakeReply(500), 1, '저장하지 못했습니다'),
      ('네트워크', FakeReply.networkError(), 1, '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, userId, message) in cases) {
      _test('$name → "$message", 입력 유지·버튼 되살림', (tester) async {
        final adapter = _adapter()..on('PUT', '/visa-reviews/7', reply);
        final app = await pumpApp(tester, location: _path, user: _verified(id: userId), adapter: adapter);
        await tester.enterText(_contentField, '고친 메모');
        await _pick(tester, _resultSelect, '불허');
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_text(tester, _contentField), '고친 메모');
        expect(_dropdownValue(tester, _resultSelect), 'DENIED');
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
        expect(_uri(app), _path);
      });
    }

    _test('다시 저장하면 이전 에러 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/visa-reviews/7', FakeReply(500))
        ..on('PUT', '/visa-reviews/7', FakeReply(200));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsNothing);
    });
  });

  group('로그인이 풀렸을 때 (401 + 재발급 401)', () {
    _test('"다시 로그인해 주세요" + 로그인 링크가 뜨고, 입력과 폼은 그대로 남는다', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/visa-reviews/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await tester.enterText(_contentField, '고친 메모');
      await tester.tap(_saveButton);
      // 위젯 테스트의 가짜 시계로는 재발급 왕복이 안 흘러서 진짜 시계로 기다린다
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull);
      expect(_uri(app), _path); // 문지기가 이 화면은 그대로 둔다
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('저장하지 못했습니다'), findsNothing);
      expect(_text(tester, _contentField), '고친 메모');
      expect(_text(tester, _receivedField), '2026-09-01');
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);

      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });
  });
}
