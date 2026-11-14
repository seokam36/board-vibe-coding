// S16-e2 글쓰기 화면 테스트: React PostCreatePage와 같은 동작(미인증 안내, 폼 구성, 말머리 검사, 요청 본문, 저장 중 잠금, 실패 문구, 로그인 풀림)을 본다.
import 'dart:async';

import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';

const _school = {'id': 1, 'name': '서울대'};
const _categories = [
  {'code': 'CHAT', 'name': '잡담'},
  {'code': 'QUESTION', 'name': '질문'},
];

// 학교 인증을 마친 사용자 / 인증 전 사용자
Map<String, dynamic> _verified() => userJson(school: _school);

FakeAdapter _adapter({FakeReply? categories}) =>
    FakeAdapter()..on('GET', '/categories', categories ?? FakeReply(200, _categories));

// 폼이 길어서 화면을 길게 만든 뒤 시험하는 testWidgets
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

final _titleField =find.byKey(const ValueKey('title-field'));
final _contentField = find.byKey(const ValueKey('content-field'));
final _categorySelect = find.byKey(const ValueKey('category-select'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _savingButton = find.widgetWithText(FilledButton, '저장 중...');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;

// 말머리 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pickCategory(WidgetTester tester, String name) async {
  await tester.tap(_categorySelect);
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester) async {
  await tester.tap(_saveButton);
  await tester.pumpAndSettle();
}

// 제목·내용·말머리를 채워 두고 저장 직전 상태로 만든다
Future<void> _fill(WidgetTester tester) async {
  await tester.enterText(_titleField, '제목입니다');
  await tester.enterText(_contentField, '내용입니다');
  await _pickCategory(tester, '질문');
}

void main() {
  group('학교 미인증', () {
    _test('폼 대신 안내 문구와 [내 정보에서 학교 인증하기]가 보인다', (tester) async {
      await pumpApp(tester, location: '/posts/new', user: userJson());
      expect(find.text('글쓰기'), findsOneWidget); // 제목
      expect(find.text('글쓰기는 학교 인증 후에 할 수 있어요'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'), findsOneWidget);
      // 폼은 없다
      expect(_titleField, findsNothing);
      expect(_contentField, findsNothing);
      expect(_saveButton, findsNothing);
    });

    _test('[내 정보에서 학교 인증하기]를 누르면 /me', (tester) async {
      final app = await pumpApp(tester, location: '/posts/new', user: userJson());
      await tester.tap(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });
  });

  group('폼 구성', () {
    _test('제목 "글쓰기"(가장 큰 제목), 학교 뱃지, 말머리, 제목·내용 칸, 저장·목록으로', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: _adapter());
      expect(find.text('글쓰기'), findsOneWidget);
      // 학교 뱃지: 눈에는 이름만, 스크린리더에는 "학교: 이름"
      expect(find.text('서울대'), findsOneWidget);
      expect(find.bySemanticsLabel('학교: 서울대'), findsOneWidget);
      // 제목·내용 칸: 라벨과 placeholder
      expect(find.text('제목'), findsWidgets);
      expect(find.text('내용'), findsWidgets);
      expect(tester.widget<TextField>(_titleField).decoration!.hintText, '제목');
      expect(tester.widget<TextField>(_contentField).decoration!.hintText, '내용');
      expect(tester.widget<TextField>(_contentField).minLines, 10); // 10줄
      expect(tester.widget<TextField>(_contentField).maxLines, 10);
      expect(_saveButton, findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '목록으로'), findsOneWidget);
      // 에러 안내는 없다
      expect(find.byType(Notice), findsNothing);
      handle.dispose();
    });

    _test('말머리 드롭다운: 첫 항목 "말머리 선택"(빈 값) 다음에 서버 목록', (tester) async {
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: _adapter());
      final dropdown = tester.widget<DropdownButton<String>>(_categorySelect);
      expect(dropdown.value, '');
      expect([for (final i in dropdown.items!) i.value], ['', 'CHAT', 'QUESTION']);
      expect(find.text('말머리 선택'), findsOneWidget); // 선택 전에는 버튼에 보인다
    });

    _test('제목은 100자까지만 입력된다 (내용은 제한 없음)', (tester) async {
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: _adapter());
      await tester.enterText(_titleField, 'a' * 105);
      await tester.enterText(_contentField, 'b' * 500);
      expect(_text(tester, _titleField).length, 100);
      expect(_text(tester, _contentField).length, 500);
    });

    _test('[목록으로]를 누르면 "/"', (tester) async {
      final app = await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: _adapter());
      await tester.tap(find.widgetWithText(OutlinedButton, '목록으로'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/');
    });
  });

  group('말머리 검사 (서버에 안 보낸다)', () {
    _test('말머리를 안 골랐으면 "말머리를 골라 주세요"이고 요청은 없고 입력은 남는다', (tester) async {
      final adapter = _adapter();
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await tester.enterText(_titleField, '제목입니다');
      await tester.enterText(_contentField, '내용입니다');
      await _save(tester);
      expect(find.text('말머리를 골라 주세요'), findsOneWidget);
      expect(adapter.count('POST', '/posts'), 0);
      expect(_text(tester, _titleField), '제목입니다');
      expect(_text(tester, _contentField), '내용입니다');
      expect(_saveButton, findsOneWidget); // 버튼은 그대로 눌 수 있다
    });

    _test('말머리 목록을 못 받았으면 안내가 보이고, 저장하면 "목록을 불러오지 못해 저장할 수 없습니다..."', (tester) async {
      final adapter = _adapter(categories: FakeReply(500));
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      expect(find.text('말머리 목록을 불러오지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(find.text('말머리 목록을 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요'), findsOneWidget);
      expect(adapter.count('POST', '/posts'), 0);
    });

    _test('말머리를 고른 뒤 다시 저장하면 이전 에러 문구는 지워진다', (tester) async {
      final adapter = _adapter()..on('POST', '/posts', FakeReply(201, {'id': 9}));
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _save(tester);
      expect(find.text('말머리를 골라 주세요'), findsOneWidget);
      await _pickCategory(tester, '잡담');
      await _save(tester);
      expect(find.text('말머리를 골라 주세요'), findsNothing);
      expect(adapter.count('POST', '/posts'), 1);
    });
  });

  group('저장 요청', () {
    _test('본문은 title·content·schoolId·category 4개이고 제목·내용은 trim하지 않는다', (tester) async {
      Object? body;
      final adapter = _adapter()..on('POST', '/posts', FakeReply(201, {'id': 9}));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await tester.enterText(_titleField, '  제목  ');
      await tester.enterText(_contentField, '\n내용 \n');
      await _pickCategory(tester, '질문');
      await _save(tester);
      expect(body, {'title': '  제목  ', 'content': '\n내용 \n', 'schoolId': 1, 'category': 'QUESTION'});
    });

    _test('빈 제목·내용도 클라이언트에서 막지 않고 서버로 보낸다', (tester) async {
      final adapter = _adapter()..on('POST', '/posts', FakeReply(400));
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _pickCategory(tester, '잡담');
      await _save(tester);
      expect(adapter.count('POST', '/posts'), 1);
      expect(find.text('제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)'), findsOneWidget);
    });

    _test('저장 중에는 버튼이 "저장 중..."으로 잠기고, 성공하면 응답의 id로 /posts/{id} 이동(기록 쌓임)', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', '/posts', FakeReply(201, {'id': 9}, gate));
      final app = await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _fill(tester);

      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50)); // 요청이 가짜 서버까지 가도록 잠깐 흘려 보낸다 (응답은 gate가 붙잡고 있다)
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull); // 잠김
      expect(adapter.count('POST', '/posts'), 1);
      // 제목 칸에서 Enter로 또 제출해도 요청은 그대로 한 번
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', '/posts'), 1);
      expect(_uri(app), '/posts/new'); // 아직 이동 전

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/9');
      expect(adapter.count('POST', '/posts'), 1);
      // 새 칸을 쌓는 이동 (React의 navigate = push): replace가 아니다
      expect(nav.last.path, '/posts/9');
      expect(nav.last.replace, isFalse);
    });

    _test('제목 칸에서 Enter를 누르면 저장된다 (폼 제출)', (tester) async {
      final adapter = _adapter()..on('POST', '/posts', FakeReply(201, {'id': 3}));
      final app = await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/posts'), 1);
      expect(_uri(app), '/posts/3');
    });
  });

  group('저장 실패', () {
    // 실패 응답 → 보이는 문구. 실패해도 입력은 남고 버튼은 되살아난다.
    final cases = <(String, FakeReply, String)>[
      ('400', FakeReply(400), '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)'),
      (
        '403',
        FakeReply(403),
        '학교 인증이 필요하거나 인증한 학교 게시판이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
      ),
      ('500', FakeReply(500), '저장하지 못했습니다'),
      ('404', FakeReply(404), '저장하지 못했습니다'), // 표에 없는 코드는 기본 문구
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in cases) {
      _test('$name → "$message", 입력 유지·버튼 되살림', (tester) async {
        final adapter = _adapter()..on('POST', '/posts', reply);
        final app = await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
        await _fill(tester);
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_text(tester, _titleField), '제목입니다');
        expect(_text(tester, _contentField), '내용입니다');
        expect(tester.widget<DropdownButton<String>>(_categorySelect).value, 'QUESTION');
        expect(_saveButton, findsOneWidget);
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
        expect(_uri(app), '/posts/new');
      });
    }

    _test('다시 저장하면 이전 에러 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts', FakeReply(500))
        ..on('POST', '/posts', FakeReply(201, {'id': 4}));
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
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
        ..on('POST', '/posts', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_saveButton);
      // 위젯 테스트의 가짜 시계로는 재발급 왕복이 안 흘러서 진짜 시계로 기다린다
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull); // 로그인이 풀렸다
      expect(_uri(app), '/posts/new'); // 문지기가 이 화면은 그대로 둔다
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('로그인'), findsWidgets);
      expect(find.text('저장하지 못했습니다'), findsNothing); // 일반 실패 문구가 아니다
      // 입력은 그대로
      expect(_text(tester, _titleField), '제목입니다');
      expect(_text(tester, _contentField), '내용입니다');
      // 기억해 둔 마지막 인증 학교로 폼이 유지된다 (미인증 안내로 바뀌지 않는다)
      expect(find.text('글쓰기는 학교 인증 후에 할 수 있어요'), findsNothing);
      expect(find.bySemanticsLabel('학교: 서울대'), findsOneWidget);
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);

      // 안내의 [로그인] 링크 → /login
      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
      handle.dispose();
    });

    _test('로그인이 풀린 뒤에도 마지막 인증 학교로 저장 요청의 schoolId가 유지된다', (tester) async {
      Object? body;
      final adapter = _adapter()
        ..on('POST', '/posts', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401))
        ..on('POST', '/posts', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await pumpApp(tester, location: '/posts/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_saveButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      // 한 번 더 저장
      await tester.tap(_saveButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect((body as Map)['schoolId'], 1);
    });
  });
}
