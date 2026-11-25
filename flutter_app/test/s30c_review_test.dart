// S30-c 리뷰 반영 검사: 사진 올리기 실패 안내에서 "자동 검사 거절(422)"과 "그 밖의 실패"를 갈라서 보여 주는가
// 1~3) 글쓰기 → 상세 안내 (422만 / 섞임 / 일반만=회귀)  4~6) 수정 화면 문구 (섞임 두 순서 / 거절 2장 / 로그인 풀림 우선)
// 7) PhotoFailure.message 단위 + 라우터가 모르는 모양의 extra를 무시  (관리자 신고 목록 8번은 s30c_auto_check_test.dart)
import 'package:board_web/common/file_picker.dart';
import 'package:board_web/market/photo_upload.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _rejected = '자동 검사에서 올릴 수 없는 사진으로 판단되었습니다. 다른 사진을 골라 주세요';
const _busy = '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요';
const _relogin = '다시 로그인해 주세요';
const _p7 = '/market-items/7/photos';
const _p9 = '/market-items/9/photos';

// 상세 안내 문장 (PhotoFailure가 만드는 두 종류)
const _generalNote = '사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요';
const _rejectedNote1 = '자동 검사에서 올릴 수 없는 사진으로 판단된 사진이 1장 있습니다';

late FakePhotoPicker _picker;

void _photoTest(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    _picker = installFakePicker();
    await body(tester);
  });
}

final _addButton = find.byKey(const ValueKey('photo-add-button'));

Future<void> _pickFiles(WidgetTester tester, List<PickedPhoto> files) async {
  await tester.ensureVisible(_addButton);
  await tester.tap(_addButton);
  await tester.pump();
  _picker.choose(files);
  await tester.pumpAndSettle();
}

FakeAdapter _editAdapter() =>
    FakeAdapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(photoIds: const [])));

Future<TestApp> _openEdit(WidgetTester tester, FakeAdapter a) =>
    pumpApp(tester, location: '/market/7/edit', user: userJson(id: 1, school: marketSeoul), adapter: a);

// 글쓰기: 사진을 고르고 저장한다 (글 id 9). replies 순서대로 사진 응답을 준다
Future<TestApp> _createWith(WidgetTester tester, List<FakeReply> replies) async {
  final adapter = FakeAdapter()
    ..on('POST', '/market-items', FakeReply(201, marketDetailJson(id: 9)))
    ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9)));
  for (final r in replies) {
    adapter.on('POST', _p9, r);
  }
  final app = await pumpApp(
    tester,
    location: '/market/new',
    user: userJson(id: 1, school: marketSeoul),
    adapter: adapter,
  );
  await _pickFiles(tester, [for (var i = 0; i < replies.length; i++) fakePhoto('p$i.jpg')]);
  await tester.enterText(find.byKey(const ValueKey('title-field')), '새 자전거');
  await tester.enterText(find.byKey(const ValueKey('content-field')), '설명');
  await tester.enterText(find.byKey(const ValueKey('price-field')), '5000');
  await tester.pump();
  await tester.tap(find.widgetWithText(FilledButton, '저장'));
  await tester.pumpAndSettle();
  return app;
}

void main() {
  group('1~3. 글쓰기 → 상세 안내', () {
    _photoTest('1. 422만(3장 중 1장): 상세에 "판단된 사진이 1장 있습니다"가 있고 "다시 올려 주세요"는 없다', (tester) async {
      final app = await _createWith(tester, [
        FakeReply(201, photoJson(201)),
        FakeReply(422),
        FakeReply(201, photoJson(203)),
      ]);
      expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/market/9'); // 화면이 실제로 상세로 갔다
      expect(find.text(_rejectedNote1), findsOneWidget);
      expect(find.textContaining('다시 올려 주세요'), findsNothing);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
    });

    _photoTest('2. 섞임(422 1장 + 503 1장): 두 문장이 한 안내에 일반 → 거절 순서로 이어 붙는다', (tester) async {
      final app = await _createWith(tester, [FakeReply(422), FakeReply(503)]);
      expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/market/9');
      // 일반 먼저, 거절 나중, ". "로 연결 (한 위젯의 한 문자열이어야 순서까지 확인된다)
      expect(find.text('$_generalNote. $_rejectedNote1'), findsOneWidget);
    });

    _photoTest('3. 회귀: 일반 실패만(503 1장)이면 예전 문구 그대로 "다시 올려 주세요"가 있고 거절 문구는 없다', (tester) async {
      final app = await _createWith(tester, [FakeReply(201, photoJson(201)), FakeReply(503)]);
      expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/market/9');
      expect(find.text(_generalNote), findsOneWidget);
      expect(find.textContaining('자동 검사'), findsNothing);
    });
  });

  group('4~6. 수정 화면 문구', () {
    _photoTest('4-a. 섞임 순서 503 → 422: 서버 바쁨 이유와 거절 이유가 모두 보인다', (tester) async {
      final adapter = _editAdapter()
        ..on('POST', _p7, FakeReply(503))
        ..on('POST', _p7, FakeReply(422));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(adapter.count('POST', _p7), 2); // 둘 다 시도했다
      expect(find.text('$_busy. $_rejected'), findsOneWidget);
    });

    _photoTest('4-b. 섞임 순서 422 → 503: 같은 문장(다른 이유 먼저, 거절 나중)이 나온다', (tester) async {
      final adapter = _editAdapter()
        ..on('POST', _p7, FakeReply(422))
        ..on('POST', _p7, FakeReply(503));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(adapter.count('POST', _p7), 2);
      expect(find.text('$_busy. $_rejected'), findsOneWidget);
    });

    _photoTest('5. 거절 2장(모두 422)이면 "(2장 실패)"가 붙고 다른 이유 문장은 없다', (tester) async {
      final adapter = _editAdapter()
        ..on('POST', _p7, FakeReply(422))
        ..on('POST', _p7, FakeReply(422));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(find.text('$_rejected (2장 실패)'), findsOneWidget);
      expect(find.textContaining(_busy), findsNothing);
    });

    _photoTest('6. 422 뒤에 401(로그인 풀림)이면 로그인 안내가 우선이고 거절 문구는 안 보인다', (tester) async {
      final adapter = _editAdapter()
        ..on('POST', _p7, FakeReply(422))
        ..on('POST', _p7, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')]);
      expect(adapter.count('POST', _p7), 2); // 401에서 멈췄다 (세 번째는 안 간다)
      expect(find.text(_relogin), findsOneWidget);
      // ("자동 검사"만 찾으면 사진 칸 아래 구글 전송 안내가 걸리므로 거절 문구에만 있는 말로 찾는다)
      expect(find.textContaining('판단되었습니다'), findsNothing);
    });
  });

  group('7. PhotoFailure·라우터', () {
    test('PhotoFailure.message: 일반만 / 거절만 / 섞임 / 0·0은 빈 글자(안내 없음)', () {
      expect(const PhotoFailure(2, 0).message, '사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요');
      expect(const PhotoFailure(1, 1).message, _rejectedNote1);
      expect(const PhotoFailure(3, 2).message, '사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요. 자동 검사에서 올릴 수 없는 사진으로 판단된 사진이 2장 있습니다');
      expect(const PhotoFailure(0, 0).message, ''); // 0/0이면 보여 줄 문장이 없다
      expect(const PhotoFailure(3, 2).others, 1);
    });

    testWidgets('상세 라우트는 PhotoFailure가 아닌 extra(숫자 5)를 무시하고 안내 없이 글만 보여 준다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/market-items/8', FakeReply(200, marketDetailJson(id: 8, title: '시작 글')))
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson()));
      final app = await pumpApp(tester, location: '/market/8', user: userJson(id: 1, school: marketSeoul), adapter: adapter);
      expect(find.text('시작 글'), findsOneWidget); // 먼저 화면이 떠 있어야 다음 이동이 진짜다
      app.router.go('/market/7', extra: 5);
      await tester.pumpAndSettle();
      expect(find.text('전공 서적 팝니다'), findsOneWidget); // 상세가 실제로 열렸다
      expect(tester.takeException(), isNull);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
      expect(find.textContaining('판단된 사진'), findsNothing);
    });
  });
}
