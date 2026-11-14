// S16-h2 비자 후기 쓰기 화면 테스트: React VisaReviewCreatePage·VisaReviewForm과 같은 동작
// (미인증 안내, 폼 구성, 날짜 달력, 빈 값 검사, 본문 2000자, 요청 본문, 저장 중 잠금, 실패 문구, 로그인 풀림)을 본다.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';

const _path = '/visa-reviews/new';

// 학교 인증을 마친 사용자
Map<String, dynamic> _verified() => userJson(school: visaSeoul);

// 선택지는 성공 (학교 목록은 이 화면이 부르지 않는다)
FakeAdapter _adapter({FakeReply? options}) =>
    FakeAdapter()..on('GET', '/visa-review-options', options ?? FakeReply(200, visaOptionsJson));

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

const _emptyMessage = '비자 종류·신청 종류·결과·날짜를 모두 입력해 주세요';
const _optionsFailedSaveMessage = '선택지를 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요';

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;

String _dropdownValue(WidgetTester tester, Finder select) => tester.widget<DropdownButton<String>>(select).value!;

String _ymd(int y, int m, int d) => '$y-${m.toString().padLeft(2, '0')}-${d.toString().padLeft(2, '0')}';

// 서울 오늘의 연·월
(int, int) _todayYm() {
  final p = seoulToday().split('-');
  return (int.parse(p[0]), int.parse(p[1]));
}

// 이번 달 1일 / 지난달 2일 (테스트가 달력에서 고를 날과, 칸에 적혀야 할 글자)
String _expectedReceived() {
  final (y, m) = _todayYm();
  return _ymd(y, m, 1);
}

String _expectedResultDay() {
  final (y, m) = _todayYm();
  return m == 1 ? _ymd(y - 1, 12, 2) : _ymd(y, m - 1, 2);
}

// 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pick(WidgetTester tester, Finder select, String name) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

// 날짜 칸을 눌러 달력을 열고 day일을 골라 [확인]. prevMonth면 지난달로 넘긴 뒤 고른다
Future<void> _pickDay(WidgetTester tester, Finder field, int day, {bool prevMonth = false}) async {
  await tester.tap(field);
  await tester.pumpAndSettle();
  if (prevMonth) {
    await tester.tap(find.byTooltip('Previous month'));
    await tester.pumpAndSettle();
  }
  await tester.tap(find.text('$day'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('확인'));
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester) async {
  await tester.tap(_saveButton);
  await tester.pumpAndSettle();
}

// 다섯 값과 본문을 채워 저장 직전 상태로 만든다
Future<void> _fill(WidgetTester tester, {String content = '서류는 빨리 냈어요'}) async {
  await _pick(tester, _visaTypeSelect, '유학(D-2)');
  await _pick(tester, _applicationSelect, '연장');
  await _pick(tester, _resultSelect, '승인');
  await _pickDay(tester, _receivedField, 1);
  await _pickDay(tester, _resultDateField, 2, prevMonth: true);
  await tester.enterText(_contentField, content);
}

void main() {
  group('학교 미인증', () {
    _test('폼·면책 문구 대신 안내 문구와 [내 정보에서 학교 인증하기]가 보인다', (tester) async {
      await pumpApp(tester, location: _path, user: userJson());
      expect(find.text('비자 후기 쓰기'), findsOneWidget); // 제목
      expect(find.text('비자 후기 작성은 학교 인증 후에 할 수 있어요'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'), findsOneWidget);
      expect(find.textContaining('후기는 개인 경험이라'), findsNothing); // 면책 문구는 폼일 때만
      expect(_visaTypeSelect, findsNothing);
      expect(_contentField, findsNothing);
      expect(_saveButton, findsNothing);
    });

    _test('[내 정보에서 학교 인증하기]를 누르면 /me', (tester) async {
      final app = await pumpApp(tester, location: _path, user: userJson());
      await tester.tap(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });
  });

  group('폼 구성', () {
    _test('제목, 면책 문구, 학교 뱃지, 드롭다운 3개, 날짜 2칸, 본문, 저장·목록으로', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(find.text('비자 후기 쓰기'), findsOneWidget);
      expect(find.textContaining('후기는 개인 경험이라'), findsOneWidget);
      // 학교 뱃지: 눈에는 이름만, 스크린리더에는 "학교: 이름"
      expect(find.text('서울대'), findsOneWidget);
      expect(find.bySemanticsLabel('학교: 서울대'), findsOneWidget);
      // 드롭다운 3개와 날짜 라벨
      // (드롭다운의 이름표는 보이는 글자와 합쳐 읽히므로 "포함"으로 확인한다)
      expect(tester.getSemantics(_visaTypeSelect).label, contains('비자 종류'));
      expect(tester.getSemantics(_applicationSelect).label, contains('신청 종류'));
      expect(tester.getSemantics(_resultSelect).label, contains('결과'));
      expect(find.text('접수일'), findsOneWidget);
      expect(find.text('결과일'), findsOneWidget);
      // 날짜 칸은 직접 쓰지 않는 읽기 전용 칸
      expect(tester.widget<TextField>(_receivedField).readOnly, isTrue);
      expect(tester.widget<TextField>(_resultDateField).readOnly, isTrue);
      expect(_text(tester, _receivedField), '');
      expect(_text(tester, _resultDateField), '');
      // 본문: 라벨, placeholder, 8줄, 카운터
      expect(find.text('본문 (선택)'), findsOneWidget);
      expect(tester.widget<TextField>(_contentField).decoration!.hintText, '준비한 서류, 팁 등을 남겨 주세요');
      expect(tester.widget<TextField>(_contentField).minLines, 8);
      expect(tester.widget<TextField>(_contentField).maxLines, 8);
      expect(find.text('0 / 2000'), findsOneWidget);
      expect(_saveButton, findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '목록으로'), findsOneWidget);
      expect(find.byType(Notice), findsNWidgets(1)); // 면책 문구(info)만. 에러 안내는 없다
      // 선택지만 한 번 불러온다 (학교 목록은 부르지 않는다)
      expect(adapter.count('GET', '/visa-review-options'), 1);
      expect(adapter.count('GET', '/schools'), 0);
      handle.dispose();
    });

    _test('드롭다운 첫 항목은 "○○ 선택"(빈 값), 이어서 서버 선택지', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      final cases = <(Finder, String, List<String>)>[
        (_visaTypeSelect, '비자 종류 선택', ['', 'D2', 'D4']),
        (_applicationSelect, '신청 종류 선택', ['', 'EXTENSION', 'CHANGE']),
        (_resultSelect, '결과 선택', ['', 'APPROVED', 'DENIED']),
      ];
      for (final (select, placeholder, values) in cases) {
        final dropdown = tester.widget<DropdownButton<String>>(select);
        expect(dropdown.value, '');
        expect([for (final i in dropdown.items!) i.value], values);
        expect(find.text(placeholder), findsOneWidget);
      }
    });

    _test('[목록으로]를 누르면 /visa-reviews', (tester) async {
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.tap(find.widgetWithText(OutlinedButton, '목록으로'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews');
    });

    _test('날짜 두 칸은 넓으면 나란히, 좁으면 세로', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      // 넓은 화면(800): 같은 높이에 나란히
      expect(tester.getTopLeft(_receivedField).dy, tester.getTopLeft(_resultDateField).dy);
      expect(tester.getTopLeft(_receivedField).dx, lessThan(tester.getTopLeft(_resultDateField).dx));
      // 좁은 화면(320 → 판 안쪽 폭이 두 칸에 모자람): 위아래
      tester.view.physicalSize = const Size(320, 1800);
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(_receivedField).dx, tester.getTopLeft(_resultDateField).dx);
      expect(tester.getTopLeft(_receivedField).dy, lessThan(tester.getTopLeft(_resultDateField).dy));
    });
  });

  group('선택지를 못 받았을 때', () {
    _test('안내가 보이고, 저장하면 "선택지를 불러오지 못해 저장할 수 없습니다..." (요청 없음)', (tester) async {
      final adapter = _adapter(options: FakeReply(500))..on('POST', '/visa-reviews', FakeReply(201, {'id': 1}));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(find.text(_optionsFailedSaveMessage), findsOneWidget);
      expect(adapter.count('POST', '/visa-reviews'), 0);
    });

    _test('드롭다운은 비었어도 날짜도 비어 있으면 같은 새로고침 안내', (tester) async {
      final adapter = _adapter(options: FakeReply(500));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pickDay(tester, _receivedField, 1);
      await _save(tester);
      expect(find.text(_optionsFailedSaveMessage), findsOneWidget); // 드롭다운이 빈 것이 이유
    });
  });

  group('빈 값 검사 (서버에 안 보낸다)', () {
    _test('아무것도 안 채우고 저장하면 "...모두 입력해 주세요", 요청 0, 버튼은 그대로', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _save(tester);
      expect(find.text(_emptyMessage), findsOneWidget);
      expect(adapter.count('POST', '/visa-reviews'), 0);
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
    });

    _test('드롭다운 셋만 채우고 날짜가 비면 같은 문구, 요청 0', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pick(tester, _visaTypeSelect, '유학(D-2)');
      await _pick(tester, _applicationSelect, '연장');
      await _pick(tester, _resultSelect, '승인');
      await _save(tester);
      expect(find.text(_emptyMessage), findsOneWidget);
      expect(adapter.count('POST', '/visa-reviews'), 0);
    });

    _test('날짜 둘만 채우고 드롭다운 하나가 비면 같은 문구(선택지는 받았으니 새로고침 안내는 아님), 입력은 남는다', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pick(tester, _visaTypeSelect, '유학(D-2)');
      await _pick(tester, _applicationSelect, '연장');
      await _pickDay(tester, _receivedField, 1);
      await _pickDay(tester, _resultDateField, 2, prevMonth: true);
      await tester.enterText(_contentField, '메모');
      await _save(tester);
      expect(find.text(_emptyMessage), findsOneWidget);
      expect(find.text(_optionsFailedSaveMessage), findsNothing);
      expect(adapter.count('POST', '/visa-reviews'), 0);
      expect(_text(tester, _receivedField), _expectedReceived());
      expect(_text(tester, _contentField), '메모');
    });

    _test('접수일만 있고 결과일이 비어도 막힌다', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pick(tester, _visaTypeSelect, '유학(D-2)');
      await _pick(tester, _applicationSelect, '연장');
      await _pick(tester, _resultSelect, '승인');
      await _pickDay(tester, _receivedField, 1);
      await _save(tester);
      expect(find.text(_emptyMessage), findsOneWidget);
      expect(adapter.count('POST', '/visa-reviews'), 0);
    });

    _test('다 채운 뒤 다시 저장하면 이전 안내 문구는 지워진다', (tester) async {
      final adapter = _adapter()..on('POST', '/visa-reviews', FakeReply(201, {'id': 9}));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _save(tester);
      expect(find.text(_emptyMessage), findsOneWidget);
      await _fill(tester);
      await _save(tester);
      expect(find.text(_emptyMessage), findsNothing);
      expect(adapter.count('POST', '/visa-reviews'), 1);
    });
  });

  group('날짜 달력', () {
    _test('칸을 누르면 달력이 열리고, 고르면 칸에 yyyy-MM-dd 가 그대로 적힌다 (하루 밀림 없음)', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      expect(find.byType(DatePickerDialog), findsNothing);
      await tester.tap(_receivedField);
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsOneWidget);
      await tester.tap(find.text('1'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsNothing);
      expect(_text(tester, _receivedField), _expectedReceived()); // 이번 달 01일
      expect(_text(tester, _resultDateField), ''); // 다른 칸은 그대로
    });

    _test('고를 수 있는 마지막 날은 서울 오늘이다 (미래 날짜 선택 불가)', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.tap(_resultDateField);
      await tester.pumpAndSettle();
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      final (y, m) = _todayYm();
      final d = int.parse(seoulToday().split('-')[2]);
      expect(dialog.lastDate, DateTime(y, m, d));
      expect(dialog.firstDate.isBefore(DateTime(2001, 1, 1)), isTrue); // 시작은 넉넉히
      expect(dialog.initialDate, DateTime(y, m, d)); // 값이 없으면 처음 보이는 날은 오늘
    });

    _test('달력은 입력 모드 전환 버튼이 있다 (직접 쓸 수도 있다)', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.tap(_receivedField);
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.edit_outlined), findsOneWidget);
    });

    _test('[취소]하면 칸은 그대로다', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.tap(_receivedField);
      await tester.pumpAndSettle();
      await tester.tap(find.text('1'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsNothing);
      expect(_text(tester, _receivedField), '');
    });

    _test('값이 있는 칸을 다시 열면 그 날이 처음 보인다', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await _pickDay(tester, _resultDateField, 2, prevMonth: true);
      await tester.tap(_resultDateField);
      await tester.pumpAndSettle();
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      final p = _expectedResultDay().split('-');
      expect(dialog.initialDate, DateTime(int.parse(p[0]), int.parse(p[1]), 2));
    });
  });

  group('본문', () {
    _test('2000자까지만 입력되고 카운터가 따라 바뀐다', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.enterText(_contentField, 'abc');
      await tester.pump();
      expect(find.text('3 / 2000'), findsOneWidget);
      await tester.enterText(_contentField, 'a' * 2005);
      await tester.pump();
      expect(_text(tester, _contentField).length, 2000);
      expect(find.text('2000 / 2000'), findsOneWidget);
    });

    _test('2000자 제한은 UTF-16 길이 기준: 이모지(2칸)가 섞이면 1000개에서 멈춘다', (tester) async {
      await pumpApp(tester, location: _path, user: _verified(), adapter: _adapter());
      await tester.enterText(_contentField, '😀' * 1100); // 길이 2200
      await tester.pump();
      expect(_text(tester, _contentField).length, 2000);
      expect(find.text('2000 / 2000'), findsOneWidget);
    });
  });

  group('저장 요청', () {
    _test('본문 키는 6개(schoolId 없음), 날짜는 yyyy-MM-dd, 본문은 trim하지 않는다', (tester) async {
      Object? body;
      final adapter = _adapter()..on('POST', '/visa-reviews', FakeReply(201, {'id': 9}));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _fill(tester, content: '  서류  \n');
      await _save(tester);
      expect(body, {
        'visaType': 'D2',
        'applicationType': 'EXTENSION',
        'receivedDate': _expectedReceived(),
        'resultDate': _expectedResultDay(),
        'result': 'APPROVED',
        'content': '  서류  \n',
      });
      expect((body as Map).containsKey('schoolId'), isFalse);
    });

    _test('본문을 비워도 저장된다 (선택 입력): content는 빈 글자', (tester) async {
      Object? body;
      final adapter = _adapter()..on('POST', '/visa-reviews', FakeReply(201, {'id': 9}));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _fill(tester, content: '');
      await _save(tester);
      expect((body as Map)['content'], '');
      expect(adapter.count('POST', '/visa-reviews'), 1);
    });

    _test('다른 값을 고르면 그 code가 본문에 간다 (D4·CHANGE·DENIED)', (tester) async {
      Object? body;
      final adapter = _adapter()..on('POST', '/visa-reviews', FakeReply(201, {'id': 9}));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _pick(tester, _visaTypeSelect, '어학연수(D-4)');
      await _pick(tester, _applicationSelect, '변경');
      await _pick(tester, _resultSelect, '불허');
      await _pickDay(tester, _receivedField, 1);
      await _pickDay(tester, _resultDateField, 2, prevMonth: true);
      await _save(tester);
      final map = body as Map;
      expect([map['visaType'], map['applicationType'], map['result']], ['D4', 'CHANGE', 'DENIED']);
    });

    _test('저장 중에는 "저장 중..."으로 잠기고, 성공하면 /visa-reviews/{id} 로 이동(기록 쌓임)', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', '/visa-reviews', FakeReply(201, {'id': 9}, gate));
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _fill(tester);

      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50)); // 요청이 가짜 서버까지 가도록 잠깐 흘려 보낸다
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull); // 잠김
      expect(adapter.count('POST', '/visa-reviews'), 1);
      expect(_uri(app), _path); // 아직 이동 전

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/9');
      expect(adapter.count('POST', '/visa-reviews'), 1);
      // 새 칸을 쌓는 이동 (React의 navigate = push): replace가 아니다
      expect(nav.last.path, '/visa-reviews/9');
      expect(nav.last.replace, isFalse);
    });
  });

  group('저장 실패', () {
    // 실패 응답 → 보이는 문구. 실패해도 입력은 남고 버튼은 되살아난다.
    final cases = <(String, FakeReply, String)>[
      (
        '400',
        FakeReply(400),
        '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
      ),
      ('403', FakeReply(403), '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요'),
      ('500', FakeReply(500), '저장하지 못했습니다'),
      ('404', FakeReply(404), '저장하지 못했습니다'), // 표에 없는 코드는 기본 문구
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in cases) {
      _test('$name → "$message", 입력 유지·버튼 되살림', (tester) async {
        final adapter = _adapter()..on('POST', '/visa-reviews', reply);
        final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
        await _fill(tester);
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_text(tester, _contentField), '서류는 빨리 냈어요');
        expect(_text(tester, _receivedField), _expectedReceived());
        expect(_text(tester, _resultDateField), _expectedResultDay());
        expect(_dropdownValue(tester, _visaTypeSelect), 'D2');
        expect(_dropdownValue(tester, _applicationSelect), 'EXTENSION');
        expect(_dropdownValue(tester, _resultSelect), 'APPROVED');
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
        expect(_uri(app), _path);
      });
    }

    _test('다시 저장하면 이전 에러 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/visa-reviews', FakeReply(500))
        ..on('POST', '/visa-reviews', FakeReply(201, {'id': 4}));
      await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _fill(tester);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsNothing);
    });
  });

  group('로그인이 풀렸을 때 (401 + 재발급 401)', () {
    _test('"다시 로그인해 주세요" + 로그인 링크가 뜨고, 입력과 폼(학교 뱃지)은 그대로 남는다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter()
        ..on('POST', '/visa-reviews', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: _path, user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_saveButton);
      // 위젯 테스트의 가짜 시계로는 재발급 왕복이 안 흘러서 진짜 시계로 기다린다
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull); // 로그인이 풀렸다
      expect(_uri(app), _path); // 문지기가 이 화면은 그대로 둔다
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('로그인'), findsWidgets);
      expect(find.text('저장하지 못했습니다'), findsNothing); // 일반 실패 문구가 아니다
      // 입력은 그대로
      expect(_text(tester, _contentField), '서류는 빨리 냈어요');
      expect(_text(tester, _receivedField), _expectedReceived());
      expect(_dropdownValue(tester, _visaTypeSelect), 'D2');
      // 기억해 둔 마지막 인증 학교로 폼이 유지된다 (미인증 안내로 바뀌지 않는다)
      expect(find.text('비자 후기 작성은 학교 인증 후에 할 수 있어요'), findsNothing);
      expect(find.bySemanticsLabel('학교: 서울대'), findsOneWidget);
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);

      // 안내의 [로그인] 링크 → /login
      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
      handle.dispose();
    });
  });
}
