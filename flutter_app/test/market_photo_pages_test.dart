// S16-k2 중고 사진 올리기·지우기 화면 테스트 (React의 market-photo-upload.spec.ts 시나리오를 위젯 테스트로).
// 가짜 파일 선택기로 "파일을 골랐다"고 알리고, 가짜 서버로 201·401·400·403·404·409·413·503·네트워크·시간 초과를 흉내 낸다.
// 수정 화면: 즉시 올리기·삭제 확인·잠금 규칙·화면 떠나기, 글쓰기 화면: 미리보기·저장 순서·실패 안내·잠금.
import 'dart:async';

import 'package:board_web/common/file_picker.dart';
import 'package:board_web/market/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _relogin = '다시 로그인해 주세요';
const _noSlot = '사진은 10장까지 올릴 수 있습니다';
const _tooLarge = '사진 한 장은 10MB 이하여야 합니다';
const _photosPath = '/market-items/7/photos';

Map<String, dynamic> _verified() => userJson(id: 1, school: marketSeoul);

late FakePhotoPicker _picker;

// 길게 만든 화면 + 가짜 파일 선택기를 끼우고 시험하는 testWidgets
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    _picker = installFakePicker();
    await body(tester);
  });
}

final _addButton = find.byKey(const ValueKey('photo-add-button'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _savingButton = find.widgetWithText(FilledButton, '저장 중...');
final _cancelButton = find.widgetWithText(OutlinedButton, '취소');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();
Finder _heading(int n) => find.text('사진 ($n/10)');
bool _enabled(WidgetTester tester, Finder button) {
  final w = tester.widget(button);
  return w is FilledButton ? w.onPressed != null : (w as OutlinedButton).onPressed != null;
}

// 사진 칸의 [삭제]/[빼기] 버튼들 (확인 창 안의 버튼은 빼고 센다)
Finder _removeButtons(String label) => find.descendant(
  of: find.byType(Scaffold),
  matching: find.widgetWithText(OutlinedButton, label),
);

// [사진 추가]를 눌러 파일 창을 열고, 파일을 골랐다고 알린다
Future<void> _pickFiles(WidgetTester tester, List<PickedPhoto> files) async {
  await tester.ensureVisible(_addButton);
  await tester.tap(_addButton);
  await tester.pump();
  _picker.choose(files);
  await tester.pumpAndSettle();
}

// 확인 창의 버튼 (창 안에서 찾는다)
Finder _dialogButton(String label) =>
    find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, label));

// ---- 수정 화면 ----

FakeAdapter _editAdapter({List<int> photoIds = const [1, 2]}) =>
    FakeAdapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(photoIds: photoIds)));

Future<TestApp> _openEdit(WidgetTester tester, FakeAdapter adapter) =>
    pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);

// ---- 글쓰기 화면 ----

final _titleField = find.byKey(const ValueKey('title-field'));
final _priceField = find.byKey(const ValueKey('price-field'));
final _contentField = find.byKey(const ValueKey('content-field'));

Future<void> _fillAndSave(WidgetTester tester) async {
  await tester.enterText(_titleField, '새 자전거');
  await tester.enterText(_contentField, '설명');
  await tester.enterText(_priceField, '5000');
  await tester.pump();
  await tester.tap(_saveButton);
  await tester.pumpAndSettle();
}

// 글 만들기가 성공하면 9번 상세로 가는 가짜 서버
FakeAdapter _createAdapter({FakeReply? post}) =>
    FakeAdapter()
      ..on('POST', '/market-items', post ?? FakeReply(201, marketDetailJson(id: 9)))
      ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9)));

Future<TestApp> _openCreate(WidgetTester tester, FakeAdapter adapter) =>
    pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);

const _photos9 = '/market-items/9/photos';

void main() {
  group('수정: 사진 올리기', () {
    _test('기존 2장에 2장을 고르면 요청이 차례로 가고 파트 이름 file·파일 이름이 맞으며 "사진 (4/10)"이 된다', (tester) async {
      final adapter = _editAdapter()
        ..on('POST', _photosPath, FakeReply(201, photoJson(101)))
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      final forms = recordForms(adapter);
      await _openEdit(tester, adapter);
      expect(_heading(2), findsOneWidget);

      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);

      expect(_heading(4), findsOneWidget);
      expect(forms, hasLength(2));
      expect(forms[0].body, contains('name="file"'));
      expect(forms[0].body, contains('filename="a.jpg"'));
      expect(forms[1].body, contains('filename="b.jpg"'));
      expect(find.text('사진 올리는 중 2/2'), findsNothing); // 끝나면 진행 문구는 사라진다
      expect(find.byType(Image), findsNWidgets(4));
    });

    _test('올리는 중에는 "사진 올리는 중 n/m"이 보이고 [사진 추가]·삭제·글자 [저장]이 잠겼다가 끝나면 풀린다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter()
        ..on('POST', _photosPath, FakeReply(201, photoJson(101), gate))
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      await _openEdit(tester, adapter);
      expect(_enabled(tester, _saveButton), isTrue);

      await tester.ensureVisible(_addButton);
      await tester.tap(_addButton);
      await tester.pump();
      _picker.choose([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await tester.pump(const Duration(milliseconds: 50));

      expect(find.text('사진 올리는 중 1/2'), findsOneWidget);
      expect(_enabled(tester, _addButton), isFalse);
      expect(_enabled(tester, _saveButton), isFalse); // 글자 [저장] 잠금
      expect(_removeButtons('삭제'), findsNWidgets(2));
      for (final b in _removeButtons('삭제').evaluate()) {
        expect((b.widget as OutlinedButton).onPressed, isNull);
      }

      gate.complete();
      await tester.pumpAndSettle();
      expect(_heading(4), findsOneWidget);
      expect(find.textContaining('사진 올리는 중'), findsNothing);
      expect(_enabled(tester, _addButton), isTrue);
      expect(_enabled(tester, _saveButton), isTrue);
    });

    _test('올리는 동안 이미 열려 있던 파일 창에서 또 고르면 무시된다 (요청이 겹치지 않는다)', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter()..on('POST', _photosPath, FakeReply(201, photoJson(101), gate));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]); // 첫 장이 붙잡힌 채 진행 중 (pumpAndSettle은 요청 대기 중에도 끝난다)
      final log = PhotoLog();
      _picker.choose([fakePhoto('late.jpg', log: log)]); // 옛 파일 창의 답
      await tester.pump();
      expect(adapter.count('POST', _photosPath), 1);
      expect(log.released, ['late.jpg']); // 쓰지 않는 미리보기 주소는 반납
      gate.complete();
      await tester.pumpAndSettle();
      expect(_heading(3), findsOneWidget);
    });

    for (final (status, text) in const [
      (400, 'JPEG·PNG 사진만 올릴 수 있습니다 (손상되었거나 너무 큰 사진일 수 있습니다)'),
      (413, _tooLarge),
      (409, '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다'),
      (503, '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요'),
    ]) {
      _test('사진 올리기가 $status면 "$text"가 뜨고 목록은 그대로다', (tester) async {
        final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply(status));
        await _openEdit(tester, adapter);
        await _pickFiles(tester, [fakePhoto('a.jpg')]);
        expect(find.text(text), findsOneWidget);
        expect(_heading(1), findsOneWidget);
      });
    }

    _test('사진 올리기가 401(재발급도 401)이면 로그인 안내가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      expect(find.text(_relogin), findsOneWidget);
      expect(_heading(1), findsOneWidget);
      expect(find.byKey(const ValueKey('title-field')), findsOneWidget); // 글 입력칸은 그대로 남는다
    });

    _test('2장 중 첫 장이 413이면 성공한 1장은 붙고 문구 뒤에 장수 접미사는 붙지 않는다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(413))
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text(_tooLarge), findsOneWidget);
      expect(_heading(2), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 2);
    });

    _test('2장이 모두 413이면 "(2장 실패)"가 붙는다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(413))
        ..on('POST', _photosPath, FakeReply(413));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text('$_tooLarge (2장 실패)'), findsOneWidget);
      expect(_heading(1), findsOneWidget);
    });

    for (final (status, text) in const [
      (403, '사진을 올리지 못했습니다. 새로고침 후 다시 시도해 주세요 (2장 실패)'),
      (404, '글을 찾을 수 없습니다 (2장 실패)'),
      (409, '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다 (2장 실패)'),
    ]) {
      _test('2장 중 첫 장이 $status면 두 번째 요청은 가지 않고 "(2장 실패)"가 붙는다', (tester) async {
        final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply(status));
        await _openEdit(tester, adapter);
        await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
        expect(find.text(text), findsOneWidget);
        expect(adapter.count('POST', _photosPath), 1);
        expect(_heading(1), findsOneWidget);
      });
    }

    _test('2장 중 첫 장이 401이면 두 번째 요청은 가지 않고 로그인 안내가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text(_relogin), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 1);
    });

    _test('네트워크 오류(연결 끊김)면 두 번째 요청은 가지 않고 연결 안내 문구가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply.networkError());
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text('서버에 연결할 수 없습니다 (2장 실패)'), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 1);
      expect(_heading(1), findsOneWidget);
    });

    _test('시간 초과면 전용 문구(새로고침해서 확인)가 뜨고 남은 장은 보내지 않는다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply.timeout());
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text('시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요 (2장 실패)'), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 1);
    });

    _test('올리다 401을 받으면 재발급 뒤 같은 사진을 다시 보내 성공하고 에러가 없다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, _verified()))
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      final forms = recordForms(adapter);
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      expect(_heading(2), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 2); // 401 한 번 + 재시도 한 번
      expect(adapter.count('POST', '/auth/refresh'), 1);
      expect(forms, hasLength(2));
      expect(forms[1].body, contains('name="file"'));
      expect(forms[1].body, contains('filename="a.jpg"')); // 다시 만든 본문에도 파일이 들어 있다
      expect(find.text(_relogin), findsNothing);
    });

    _test('다음 올리기를 시작하면 이전 실패 문구는 지워진다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(413))
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      expect(find.text(_tooLarge), findsOneWidget);
      await _pickFiles(tester, [fakePhoto('b.jpg')]);
      expect(find.text(_tooLarge), findsNothing);
      expect(_heading(2), findsOneWidget);
    });
  });

  group('수정: 사전 검사 (서버 요청 없이 안내)', () {
    _test('gif는 요청 없이 안내 문구가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [1]);
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.gif', type: 'image/gif')]);
      expect(find.text('JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다.'), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 0);
      expect(_heading(1), findsOneWidget);
    });

    _test('10MB를 넘는 파일은 요청 없이 안내 문구가 뜨고, 정확히 10MB는 올라간다', (tester) async {
      final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('big.jpg', size: 10 * 1024 * 1024 + 1)]);
      expect(find.text('$_tooLarge. 1장은 받지 않았습니다.'), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 0);
      await _pickFiles(tester, [fakePhoto('edge.jpg', size: 10 * 1024 * 1024)]);
      expect(find.text('$_tooLarge. 1장은 받지 않았습니다.'), findsNothing); // 새 검사 결과로 바뀐다
      expect(adapter.count('POST', _photosPath), 1);
      expect(_heading(2), findsOneWidget);
    });

    _test('기존 1장에 11장을 고르면 9장만 올리고 "2장은 받지 않았습니다"가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [1]);
      for (var i = 0; i < 9; i++) {
        adapter.on('POST', _photosPath, FakeReply(201, photoJson(100 + i)));
      }
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [for (var i = 0; i < 11; i++) fakePhoto('p$i.jpg')]);
      expect(find.text('$_noSlot. 2장은 받지 않았습니다.'), findsOneWidget);
      expect(_heading(10), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 9);
    });

    _test('9장 있는 글에서 2장을 고르면 1장만 올리고 안내가 뜬다', (tester) async {
      final adapter = _editAdapter(photoIds: [for (var i = 1; i <= 9; i++) i])
        ..on('POST', _photosPath, FakeReply(201, photoJson(200)));
      await _openEdit(tester, adapter);
      expect(_heading(9), findsOneWidget);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(_heading(10), findsOneWidget);
      expect(find.text('$_noSlot. 1장은 받지 않았습니다.'), findsOneWidget);
      expect(adapter.count('POST', _photosPath), 1);
    });

    _test('10장이면 [사진 추가]가 비활성이고 안내 글이 보인다', (tester) async {
      await _openEdit(tester, _editAdapter(photoIds: [for (var i = 1; i <= 10; i++) i]));
      expect(_heading(10), findsOneWidget);
      expect(_enabled(tester, _addButton), isFalse);
      expect(find.text(_noSlot), findsOneWidget);
    });

    _test('같은 파일을 두 번 연달아 골라도 두 번 다 처리된다 (파일 창이 매번 열린다)', (tester) async {
      final adapter = _editAdapter(photoIds: [1])
        ..on('POST', _photosPath, FakeReply(201, photoJson(102)))
        ..on('POST', _photosPath, FakeReply(201, photoJson(103)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('same.jpg')]);
      await _pickFiles(tester, [fakePhoto('same.jpg')]);
      expect(_picker.opened, 2);
      expect(adapter.count('POST', _photosPath), 2);
      expect(_heading(3), findsOneWidget);
    });

    _test('올린 뒤에는 미리보기 주소를 반납하고, 받지 않은 파일도 반납한다', (tester) async {
      final log = PhotoLog();
      final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply(201, photoJson(102)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('ok.jpg', log: log), fakePhoto('no.gif', type: 'image/gif', log: log)]);
      expect(log.released.toSet(), {'ok.jpg', 'no.gif'});
      expect(log.reads, ['ok.jpg']); // 거른 파일은 내용도 읽지 않는다
    });
  });

  group('수정: 사진 삭제', () {
    _test('[삭제]를 누르면 "사진 2번을 삭제할까요?" 확인 창이 뜨고, 취소하면 요청이 없다', (tester) async {
      final adapter = _editAdapter();
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').at(1));
      await tester.pumpAndSettle();
      expect(find.text('사진 2번을 삭제할까요?'), findsOneWidget);
      await tester.tap(_dialogButton('취소'));
      await tester.pumpAndSettle();
      expect(find.text('사진 2번을 삭제할까요?'), findsNothing);
      expect(adapter.calls.where((c) => c.method == 'DELETE'), isEmpty);
      expect(_heading(2), findsOneWidget);
      expect(_enabled(tester, _saveButton), isTrue); // 취소하면 잠금이 풀린다
    });

    _test('확인하면 DELETE 주소가 맞고 목록에서 빠진다', (tester) async {
      final adapter = _editAdapter()..on('DELETE', '/market-items/7/photos/2', FakeReply(204));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').at(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/market-items/7/photos/2'), 1);
      expect(_heading(1), findsOneWidget);
      expect(_removeButtons('삭제'), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
    });

    _test('확인 창이 떠 있는 동안에는 글자 [저장]과 [사진 추가]가 잠겨 있다', (tester) async {
      await _openEdit(tester, _editAdapter());
      await tester.tap(_removeButtons('삭제').first);
      await tester.pumpAndSettle();
      expect(_enabled(tester, _saveButton), isFalse);
      expect(_enabled(tester, _addButton), isFalse);
    });

    _test('삭제 요청이 오는 동안 글자 [저장]이 비활성이고 끝나면 다시 활성이다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter()..on('DELETE', '/market-items/7/photos/2', FakeReply(204, null, gate));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').at(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(_enabled(tester, _saveButton), isFalse);
      expect(_heading(2), findsOneWidget); // 응답 전이라 아직 그대로
      gate.complete();
      await tester.pumpAndSettle();
      expect(_heading(1), findsOneWidget);
      expect(_enabled(tester, _saveButton), isTrue);
    });

    _test('지우기가 404면 "이미 삭제된 사진입니다"가 뜨고 목록에서도 빠진다', (tester) async {
      final adapter = _editAdapter()..on('DELETE', '/market-items/7/photos/2', FakeReply(404));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').at(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('이미 삭제된 사진입니다'), findsOneWidget);
      expect(_heading(1), findsOneWidget);
    });

    _test('지우기가 409면 "거래가 완료된 글의 사진은 지울 수 없습니다"가 뜨고 목록은 그대로다', (tester) async {
      final adapter = _editAdapter()..on('DELETE', '/market-items/7/photos/2', FakeReply(409));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').at(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('거래가 완료된 글의 사진은 지울 수 없습니다'), findsOneWidget);
      expect(_heading(2), findsOneWidget);
    });

    _test('지우기가 403이면 새로고침 안내가, 그 밖의 실패면 "삭제하지 못했습니다"가 뜬다', (tester) async {
      final adapter = _editAdapter()
        ..on('DELETE', '/market-items/7/photos/1', FakeReply(403))
        ..on('DELETE', '/market-items/7/photos/1', FakeReply(500));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').first);
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'), findsOneWidget);
      await tester.tap(_removeButtons('삭제').first);
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      expect(_heading(2), findsOneWidget);
    });

    _test('지우기가 401(재발급도 401)이면 로그인 안내가 뜬다', (tester) async {
      final adapter = _editAdapter()
        ..on('DELETE', '/market-items/7/photos/1', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _openEdit(tester, adapter);
      await tester.tap(_removeButtons('삭제').first);
      await tester.pumpAndSettle();
      await tester.tap(_dialogButton('삭제'));
      await tester.pumpAndSettle();
      expect(find.text(_relogin), findsOneWidget);
      expect(_heading(2), findsOneWidget);
    });
  });

  group('수정: 사진 ↔ 글자 저장 잠금, 화면 떠나기', () {
    _test('글자 저장 중(PUT 응답 지연)에는 [사진 추가]·삭제 버튼이 비활성이다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter()
        ..on('PUT', '/market-items/7', FakeReply(200, marketDetailJson(photoIds: [1, 2]), gate))
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(photoIds: [1, 2])));
      final app = await _openEdit(tester, adapter);
      expect(_enabled(tester, _addButton), isTrue);
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_savingButton, findsOneWidget);
      expect(_enabled(tester, _addButton), isFalse);
      for (final b in _removeButtons('삭제').evaluate()) {
        expect((b.widget as OutlinedButton).onPressed, isNull);
      }
      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/7'); // 저장이 끝나면 상세로 간다
    });

    _test('글자 저장이 실패하면 사진 영역이 다시 활성이 된다', (tester) async {
      final adapter = _editAdapter()..on('PUT', '/market-items/7', FakeReply(500));
      await _openEdit(tester, adapter);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      expect(_enabled(tester, _addButton), isTrue);
      expect(_removeButtons('삭제').evaluate().every((b) => (b.widget as OutlinedButton).onPressed != null), isTrue);
    });

    _test('사진을 올리는 중 다른 화면으로 이동하면 남은 사진 요청은 가지 않는다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter(photoIds: [1])..on('POST', _photosPath, FakeReply(201, photoJson(102), gate));
      final app = await _openEdit(tester, adapter);
      await tester.ensureVisible(_addButton);
      await tester.tap(_addButton);
      await tester.pump();
      _picker.choose([fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')]);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', _photosPath), 1);

      app.router.go('/nope-page'); // 화면이 사라진다
      await tester.pumpAndSettle();
      gate.complete(); // 첫 응답이 돌아온 뒤에도
      await tester.pumpAndSettle();

      expect(adapter.count('POST', _photosPath), 1);
      expect(_uri(app), '/nope-page');
    });

    _test('375px 폭에서도 10장이 줄바꿈되어 넘치지 않는다', (tester) async {
      tester.view.physicalSize = const Size(375, 1800);
      await _openEdit(tester, _editAdapter(photoIds: [for (var i = 1; i <= 10; i++) i]));
      expect(tester.takeException(), isNull);
      expect(_heading(10), findsOneWidget);
      expect(_removeButtons('삭제'), findsNWidgets(10));
      // 사진 칸이 화면 폭(375)을 넘지 않는다
      for (final e in _removeButtons('삭제').evaluate()) {
        final box = e.renderObject! as RenderBox;
        expect(box.localToGlobal(Offset.zero).dx + box.size.width, lessThanOrEqualTo(375));
      }
    });

    _test('스크린리더 이름은 "사진 n 삭제", 미리보기 그림은 "사진 n"', (tester) async {
      final handle = tester.ensureSemantics();
      await _openEdit(tester, _editAdapter());
      expect(find.bySemanticsLabel('사진 1 삭제'), findsOneWidget);
      expect(find.bySemanticsLabel('사진 2 삭제'), findsOneWidget);
      expect(find.bySemanticsLabel('사진 1'), findsOneWidget);
      handle.dispose();
    });
  });

  group('글쓰기: 사진 미리보기·저장 순서', () {
    _test('처음에는 "사진 (0/10)"과 [사진 추가]만 있다', (tester) async {
      await _openCreate(tester, FakeAdapter());
      expect(_heading(0), findsOneWidget);
      expect(_enabled(tester, _addButton), isTrue);
      expect(_removeButtons('빼기'), findsNothing);
    });

    _test('3장 고르고 2번을 빼면 미리보기 2개, 저장하면 글 POST 다음에 사진 POST 2번이 가고 상세로 간다', (tester) async {
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(201, photoJson(201)))
        ..on('POST', _photos9, FakeReply(201, photoJson(202)));
      final forms = recordForms(adapter);
      final log = PhotoLog();
      final app = await _openCreate(tester, adapter);

      await _pickFiles(tester, [fakePhoto('a.jpg', log: log), fakePhoto('b.jpg', log: log), fakePhoto('c.jpg', log: log)]);
      expect(find.byType(Image), findsNWidgets(3));
      expect(_heading(3), findsOneWidget);
      expect(_removeButtons('빼기'), findsNWidgets(3));
      await tester.tap(_removeButtons('빼기').at(1)); // 확인 창 없이 바로 빠진다
      await tester.pumpAndSettle();
      expect(find.byType(Image), findsNWidgets(2));
      expect(_heading(2), findsOneWidget);
      expect(log.released, ['b.jpg']); // 뺀 사진의 미리보기 주소는 반납
      expect(adapter.calls.where((c) => c.method == 'POST'), isEmpty); // 저장 전에는 글도 사진도 보내지 않는다

      await _fillAndSave(tester);

      expect(_uri(app), '/market/9');
      final order = adapter.calls.where((c) => c.method == 'POST').map((c) => c.path).toList();
      expect(order, ['/api/market-items', '/api/market-items/9/photos', '/api/market-items/9/photos']);
      expect(forms, hasLength(2));
      expect(forms[0].body, contains('filename="a.jpg"')); // 빼지 않은 1번·3번이 올라간다
      expect(forms[1].body, contains('filename="c.jpg"'));
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
      expect(log.reads, ['a.jpg', 'c.jpg']);
    });

    _test('글 만들기가 400이면 사진은 올리지 않고 폼 에러가 뜨며 미리보기가 그대로다', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/market-items', FakeReply(400));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await _fillAndSave(tester);

      expect(find.text('제목, 본문, 판매/나눔, 가격을 확인해 주세요 (판매 가격은 1~10,000,000원)'), findsOneWidget);
      expect(_uri(app), '/market/new');
      expect(find.byType(Image), findsNWidgets(2));
      expect(_enabled(tester, _addButton), isTrue);
      expect(adapter.calls.where((c) => c.path.contains('/photos')), isEmpty);
    });

    _test('사진 2장 중 1장이 실패하면 상세에 "사진 1장을 올리지 못했습니다" 안내가 뜬다', (tester) async {
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(201, photoJson(201)))
        ..on('POST', _photos9, FakeReply(413));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await _fillAndSave(tester);
      expect(_uri(app), '/market/9');
      expect(find.text('사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요'), findsOneWidget);
    });

    _test('사진이 전부 실패하면 상세에 "사진 2장을 올리지 못했습니다" 안내가 뜬다', (tester) async {
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(413))
        ..on('POST', _photos9, FakeReply(413));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await _fillAndSave(tester);
      expect(_uri(app), '/market/9');
      expect(find.text('사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요'), findsOneWidget);
    });

    _test('사진 단계에서 401이면 남은 사진은 안 보내고 실패 장수 안내로 상세에 간다', (tester) async {
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await _fillAndSave(tester);
      expect(_uri(app), '/market/9');
      expect(find.text('사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요'), findsOneWidget);
      expect(adapter.count('POST', _photos9), 1);
    });

    _test('사진 0장으로 저장하면 사진 요청 없이 기존처럼 상세로 가고 안내는 없다', (tester) async {
      final adapter = _createAdapter();
      final app = await _openCreate(tester, adapter);
      await _fillAndSave(tester);
      expect(_uri(app), '/market/9');
      expect(adapter.count('POST', _photos9), 0);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
    });

    _test('사진 올리기 중 401이 재발급으로 풀리면 같은 사진이 다시 올라가 안내 없이 상세로 간다', (tester) async {
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, _verified()))
        ..on('POST', _photos9, FakeReply(201, photoJson(201)));
      final forms = recordForms(adapter);
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      await _fillAndSave(tester);
      expect(_uri(app), '/market/9');
      expect(forms.where((f) => f.body.contains('filename="a.jpg"')), hasLength(2));
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
    });

    _test('글 뒤 사진을 올리는 동안 [저장]·[취소]·[사진 추가]·빼기가 잠기고 진행 문구가 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = _createAdapter()
        ..on('POST', _photos9, FakeReply(201, photoJson(201), gate))
        ..on('POST', _photos9, FakeReply(201, photoJson(202)));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      await tester.enterText(_titleField, '새 자전거');
      await tester.enterText(_contentField, '설명');
      await tester.enterText(_priceField, '5000');
      await tester.pump();
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pump(const Duration(milliseconds: 50));

      expect(find.text('사진 올리는 중 1/2'), findsOneWidget);
      expect(_savingButton, findsOneWidget);
      expect(_enabled(tester, _savingButton), isFalse);
      expect(_enabled(tester, _cancelButton), isFalse);
      expect(_enabled(tester, _addButton), isFalse);
      for (final b in _removeButtons('빼기').evaluate()) {
        expect((b.widget as OutlinedButton).onPressed, isNull);
      }
      expect(_uri(app), '/market/new');

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/9');
    });

    _test('사진을 올리는 중 다른 화면으로 이동하면 남은 사진은 안 보내고 상세로 끌려가지 않는다', (tester) async {
      final gate = Completer<void>();
      final log = PhotoLog();
      final adapter = _createAdapter()..on('POST', _photos9, FakeReply(201, photoJson(201), gate));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg', log: log), fakePhoto('b.jpg', log: log)]);
      await tester.enterText(_titleField, '새 자전거');
      await tester.enterText(_contentField, '설명');
      await tester.enterText(_priceField, '5000');
      await tester.pump();
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(adapter.count('POST', _photos9), 1);

      app.router.go('/nope-page');
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();

      expect(adapter.count('POST', _photos9), 1); // 두 번째 사진은 안 갔다
      expect(_uri(app), '/nope-page'); // 상세(/market/9)로 끌려가지 않았다
      expect(log.released.toSet(), {'a.jpg', 'b.jpg'}); // 화면을 떠나며 미리보기 주소를 모두 반납
    });

    _test('사전 검사에 걸리면 안내 문구가 뜨고 미리보기는 늘지 않는다 (받은 것만 담긴다)', (tester) async {
      await _openCreate(tester, FakeAdapter());
      await _pickFiles(tester, [fakePhoto('a.gif', type: 'image/gif'), fakePhoto('b.jpg')]);
      expect(find.text('JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다.'), findsOneWidget);
      expect(_heading(1), findsOneWidget);
      expect(find.byType(Image), findsOneWidget);
    });

    _test('10장을 담으면 [사진 추가]가 비활성이고, 하나 빼면 다시 활성이다', (tester) async {
      await _openCreate(tester, FakeAdapter());
      await _pickFiles(tester, [for (var i = 0; i < 10; i++) fakePhoto('p$i.jpg')]);
      expect(_heading(10), findsOneWidget);
      expect(_enabled(tester, _addButton), isFalse);
      expect(find.text(_noSlot), findsOneWidget);
      await tester.tap(_removeButtons('빼기').first);
      await tester.pumpAndSettle();
      expect(_heading(9), findsOneWidget);
      expect(_enabled(tester, _addButton), isTrue);
    });

    _test('사진을 뺀 뒤 같은 사진을 다시 골라도 담긴다 (번호는 새로 매겨진다)', (tester) async {
      await _openCreate(tester, FakeAdapter());
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      await tester.tap(_removeButtons('빼기').first);
      await tester.pumpAndSettle();
      expect(_heading(0), findsOneWidget);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      expect(_heading(1), findsOneWidget);
    });

    _test('375px 폭에서도 미리보기가 줄바꿈되어 넘치지 않는다', (tester) async {
      tester.view.physicalSize = const Size(375, 1800);
      await _openCreate(tester, FakeAdapter());
      await _pickFiles(tester, [for (var i = 0; i < 10; i++) fakePhoto('p$i.jpg')]);
      expect(tester.takeException(), isNull);
      for (final e in _removeButtons('빼기').evaluate()) {
        final box = e.renderObject! as RenderBox;
        expect(box.localToGlobal(Offset.zero).dx + box.size.width, lessThanOrEqualTo(375));
      }
    });
  });

  test('수정 화면이 쓰는 사진 모델은 id만 읽는다 (MarketPhoto)', () {
    expect(MarketPhoto.fromJson(photoJson(5)).id, 5);
  });
}
