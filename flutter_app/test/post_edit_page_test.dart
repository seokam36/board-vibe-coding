// S16-e2 글 수정 화면 테스트: React PostEditPage와 같은 동작(불러오기·실패, 말머리 옵션, 요청 본문, 기록 교체 이동, 실패 문구, 로그인 풀림)을 본다.
import 'dart:async';

import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';

const _categories = [
  {'code': 'CHAT', 'name': '잡담'},
  {'code': 'QUESTION', 'name': '질문'},
];

// 글 JSON 한 건 (말머리·학교를 바꿔 볼 수 있다)
Map<String, dynamic> _post({
  Map<String, dynamic> category = const {'code': 'CHAT', 'name': '잡담'},
  Map<String, dynamic>? school = const {'id': 1, 'name': '서울대'},
}) => {
  'id': 7,
  'title': '원래 제목',
  'content': '원래 내용\n둘째 줄',
  'author': {'id': 1, 'nickname': '홍길동'},
  'school': school,
  'category': category,
  'createdAt': '2020-11-08T06:19:00Z',
  'commentCount': 0,
  'likeCount': 0,
};

// 글 불러오기 응답 + 말머리 목록 응답을 정한 가짜 서버
FakeAdapter _adapter({FakeReply? post, FakeReply? categories}) => FakeAdapter()
  ..on('GET', '/posts/7', post ?? FakeReply(200, _post()))
  ..on('GET', '/categories', categories ?? FakeReply(200, _categories));

// 폼이 길어서 화면을 길게 만든 뒤 시험하는 testWidgets
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

final _titleField = find.byKey(const ValueKey('title-field'));
final _contentField = find.byKey(const ValueKey('content-field'));
final _categorySelect = find.byKey(const ValueKey('category-select'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _savingButton = find.widgetWithText(FilledButton, '저장 중...');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;

DropdownButton<String> _dropdown(WidgetTester tester) => tester.widget<DropdownButton<String>>(_categorySelect);

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter) =>
    pumpApp(tester, location: '/posts/7/edit', user: userJson(), adapter: adapter);

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

void main() {
  group('불러오기', () {
    _test('불러오는 동안은 제목 "글 수정"과 "불러오는 중..."만 보이고 폼은 없다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(post: FakeReply(200, _post(), gate));
      await _open(tester, adapter);
      expect(find.text('글 수정'), findsOneWidget);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_titleField, findsNothing);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(_titleField, findsOneWidget);
    });

    _test('성공하면 제목·내용·학교·말머리가 채워지고 저장·취소 버튼이 보인다', (tester) async {
      await _open(tester, _adapter());
      expect(find.text('글 수정'), findsOneWidget);
      expect(_text(tester, _titleField), '원래 제목');
      expect(_text(tester, _contentField), '원래 내용\n둘째 줄');
      expect(find.text('서울대'), findsOneWidget); // 학교는 글자 뱃지
      expect(_dropdown(tester).value, 'CHAT');
      expect(tester.widget<TextField>(_titleField).decoration!.hintText, '제목');
      expect(tester.widget<TextField>(_contentField).decoration!.hintText, '내용');
      expect(tester.widget<TextField>(_contentField).minLines, 10);
      expect(_saveButton, findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '취소'), findsOneWidget);
      expect(find.byType(Notice), findsNothing);
      // 불러오기 실패 때만 보이는 [목록으로]는 없다
      expect(find.text('목록으로'), findsNothing);
    });

    _test('학교 뱃지에는 스크린리더용 "학교:" 접두어가 없고, 학교 없는 글은 "학교 미지정"', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(post: FakeReply(200, _post(school: null))));
      expect(find.text('학교 미지정'), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('^학교:')), findsNothing);
      handle.dispose();
    });

    _test('글 id는 주소에서 풀린 값을 다시 인코딩해 요청한다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/posts/a%20b', FakeReply(200, _post()))
        ..on('GET', '/categories', FakeReply(200, _categories));
      await pumpApp(tester, location: '/posts/a%20b/edit', user: userJson(), adapter: adapter);
      expect(adapter.count('GET', '/posts/a%20b'), 1);
      expect(_titleField, findsOneWidget);
    });

    // 실패 3종 + 401: 폼은 숨고 문구와 [목록으로]만 보인다
    final failures = <(String, FakeReply, String)>[
      ('404', FakeReply(404), '글을 찾을 수 없습니다'),
      ('400(주소가 이상한 id)', FakeReply(400), '글을 찾을 수 없습니다'),
      ('500', FakeReply(500), '글을 불러오지 못했습니다'),
      ('401(로그인 안내 없이 기본 문구)', FakeReply(401), '글을 불러오지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in failures) {
      _test('불러오기 실패 $name → "$message", 폼 숨김, [목록으로] → "/"', (tester) async {
        final adapter = _adapter(post: reply);
        // 401은 인터셉터가 재발급을 먼저 시도한다: 재발급도 401이면 원래 401이 화면에 돌아온다
        if (name.startsWith('401')) adapter.on('POST', '/auth/refresh', FakeReply(401));
        final app = await _open(tester, adapter);
        if (name.startsWith('401')) {
          await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
          await tester.pumpAndSettle();
        }
        expect(find.text('글 수정'), findsOneWidget); // 제목은 그대로
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_titleField, findsNothing);
        expect(_saveButton, findsNothing);
        expect(find.text('불러오는 중...'), findsNothing);

        await tester.tap(find.widgetWithText(OutlinedButton, '목록으로'));
        await tester.pumpAndSettle();
        expect(_uri(app), '/');
      });
    }
  });

  group('말머리 옵션', () {
    _test('글의 말머리가 목록에 있으면 목록 그대로이고 "말머리 선택"은 없다', (tester) async {
      await _open(tester, _adapter());
      expect([for (final i in _dropdown(tester).items!) i.value], ['CHAT', 'QUESTION']);
      expect(_dropdown(tester).value, 'CHAT');
      expect(find.text('말머리 선택'), findsNothing);
    });

    _test('글의 말머리가 목록에 없으면 맨 앞에 끼워 넣고 그것이 선택돼 있다', (tester) async {
      final adapter = _adapter(
        post: FakeReply(200, _post(category: const {'code': 'CAMPUS_LIFE', 'name': '캠퍼스 생활'})),
      );
      await _open(tester, adapter);
      expect([for (final i in _dropdown(tester).items!) i.value], ['CAMPUS_LIFE', 'CHAT', 'QUESTION']);
      expect(_dropdown(tester).value, 'CAMPUS_LIFE');
      expect(find.text('캠퍼스 생활'), findsOneWidget);
    });

    _test('목록을 못 받으면 안내가 보이고 현재 말머리 하나로 저장할 수 있다', (tester) async {
      Object? body;
      final adapter = _adapter(categories: FakeReply(500))..on('PUT', '/posts/7', FakeReply(200, _post()));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await _open(tester, adapter);
      expect(find.text('말머리 목록을 불러오지 못했습니다. 지금 말머리로만 저장할 수 있습니다'), findsOneWidget);
      expect([for (final i in _dropdown(tester).items!) i.value], ['CHAT']);
      await _save(tester);
      expect(adapter.count('PUT', '/posts/7'), 1);
      expect((body as Map)['category'], 'CHAT');
    });
  });

  group('저장 요청', () {
    _test('본문은 title·content·category 3개뿐 (schoolId 없음, trim 안 함)', (tester) async {
      Object? body;
      final adapter = _adapter()..on('PUT', '/posts/7', FakeReply(200, _post()));
      adapter.onRequestBody = (d) {
        if (d is Map) body = d;
      };
      await _open(tester, adapter);
      await tester.enterText(_titleField, '  새 제목  ');
      await tester.enterText(_contentField, '새 내용 \n');
      await _pickCategory(tester, '질문');
      await _save(tester);
      expect(body, {'title': '  새 제목  ', 'content': '새 내용 \n', 'category': 'QUESTION'});
    });

    _test('제목은 100자까지만 입력된다', (tester) async {
      await _open(tester, _adapter());
      await tester.enterText(_titleField, 'a' * 105);
      expect(_text(tester, _titleField).length, 100);
    });

    _test('저장 중에는 "저장 중..."으로 잠기고 요청은 한 번, 성공하면 /posts/7 로 가며 기록을 교체한다', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()..on('PUT', '/posts/7', FakeReply(200, _post(), gate));
      final app = await _open(tester, adapter);

      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50)); // 요청이 가짜 서버까지 가도록 잠깐 흘려 보낸다
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull);
      expect(adapter.count('PUT', '/posts/7'), 1);
      // 제목 칸 Enter로 또 제출해도 한 번만
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('PUT', '/posts/7'), 1);
      expect(_uri(app), '/posts/7/edit'); // 아직 이동 전

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/7');
      // 기록 교체 (React의 navigate(..., { replace: true })): 새 칸을 쌓지 않고 수정 폼 칸을 덮어쓴다.
      // 엔진(브라우저)에 "replace = true"로 알렸는지로 확인한다. 진짜 뒤로 가기 버튼은 손 확인표에서 본다.
      expect(nav.last.path, '/posts/7');
      expect(nav.last.replace, isTrue);
    });

    _test('[취소]를 누르면 저장 없이 /posts/7', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter);
      await tester.tap(find.widgetWithText(OutlinedButton, '취소'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/7');
      expect(adapter.count('PUT', '/posts/7'), 0);
    });
  });

  group('저장 실패', () {
    final cases = <(String, FakeReply, String)>[
      ('403', FakeReply(403), '본인 글만 수정할 수 있습니다'),
      ('400', FakeReply(400), '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)'),
      ('404', FakeReply(404), '글을 찾을 수 없습니다'),
      ('500', FakeReply(500), '저장하지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in cases) {
      _test('$name → "$message", 입력 유지·버튼 되살림·폼 유지', (tester) async {
        final adapter = _adapter()..on('PUT', '/posts/7', reply);
        final app = await _open(tester, adapter);
        await tester.enterText(_titleField, '고친 제목');
        await tester.enterText(_contentField, '고친 내용');
        await _pickCategory(tester, '질문');
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
        expect(_text(tester, _titleField), '고친 제목');
        expect(_text(tester, _contentField), '고친 내용');
        expect(_dropdown(tester).value, 'QUESTION');
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
        expect(_uri(app), '/posts/7/edit');
      });
    }

    _test('다시 저장하면 이전 에러 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/posts/7', FakeReply(500))
        ..on('PUT', '/posts/7', FakeReply(200, _post()));
      await _open(tester, adapter);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsNothing);
    });

    _test('401(재발급도 401) → "다시 로그인해 주세요"+로그인 링크, 일반 실패 문구 없음, 입력·폼 유지', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/posts/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter);
      await tester.enterText(_titleField, '고친 제목');
      await tester.tap(_saveButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull);
      expect(_uri(app), '/posts/7/edit'); // 풀린 그 화면은 문지기가 그대로 둔다
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('저장하지 못했습니다'), findsNothing);
      expect(_text(tester, _titleField), '고친 제목');
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);

      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });
  });
}
