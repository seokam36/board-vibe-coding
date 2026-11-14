// S16-k1 중고·나눔 쓰기·수정 화면 테스트 (React의 market.spec 글쓰기·수정 시나리오 + MarketItemForm 단위):
// 접근 문지기, 폼 구성, 빈 값·가격 경계 검사, 요청 본문(나눔은 price 키 없음), 저장 중 잠금, 실패 문구, 로그인 풀림,
// 수정 화면의 불러오기·남의 글·거래완료·기록 교체. 사진은 k2에서 붙으니 여기서는 보지 않는다.
import 'dart:async';

import 'package:board_web/market/market_item_form.dart';
import 'package:board_web/market/models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';

const _priceRange = '판매 가격은 1원 이상 10,000,000원 이하의 숫자로 입력해 주세요';
const _fillAll = '제목과 본문을 입력해 주세요';
const _fieldsInvalid = '제목, 본문, 판매/나눔, 가격을 확인해 주세요 (판매 가격은 1~10,000,000원)';
const _doneNotEditable = '거래가 완료된 글은 수정할 수 없습니다.';
const _relogin = '다시 로그인해 주세요';

// 서울대 인증 회원 (id 1 = 기본 판매자)
Map<String, dynamic> _verified({int id = 1}) => userJson(id: id, school: marketSeoul);

// 폼이 길어서 화면을 길게 만든 뒤 시험하는 testWidgets
void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

final _titleField = find.byKey(const ValueKey('title-field'));
final _priceField = find.byKey(const ValueKey('price-field'));
final _contentField = find.byKey(const ValueKey('content-field'));
final _tradeSelect = find.byKey(const ValueKey('trade-type-select'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _savingButton = find.widgetWithText(FilledButton, '저장 중...');
final _cancelButton = find.widgetWithText(OutlinedButton, '취소');
final _toListButton = find.widgetWithText(OutlinedButton, '목록으로');

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();
String _text(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;
String _tradeValue(WidgetTester tester) => tester.widget<DropdownButton<String>>(_tradeSelect).value!;

Future<void> _enter(WidgetTester tester, Finder field, String text) async {
  await tester.enterText(field, text);
  await tester.pump();
}

Future<void> _pickTrade(WidgetTester tester, String name) async {
  await tester.tap(_tradeSelect);
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

Future<void> _save(WidgetTester tester) async {
  await tester.tap(_saveButton);
  await tester.pumpAndSettle();
}

// 쓰기 화면: 제목·본문·가격을 채운다 (price가 null이면 가격 칸은 건드리지 않는다)
Future<void> _fill(WidgetTester tester, {String title = '가짜 자전거', String content = '설명', String? price = '5000'}) async {
  await _enter(tester, _titleField, title);
  await _enter(tester, _contentField, content);
  if (price != null) await _enter(tester, _priceField, price);
}

// 저장 요청 본문(Map)만 모은다
List<Map> _bodies(FakeAdapter adapter) {
  final list = <Map>[];
  adapter.onRequestBody = (d) {
    if (d is Map) list.add(d);
  };
  return list;
}

// 글 쓰기용 가짜 서버: POST가 성공하면 글 9번 상세로 간다
FakeAdapter _createAdapter({FakeReply? post}) =>
    FakeAdapter()
      ..on('POST', '/market-items', post ?? FakeReply(201, marketDetailJson(id: 9)))
      ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9))); // 이동한 상세가 부르는 요청

// 글 수정용 가짜 서버: 7번 글을 불러온다. 저장이 성공하면 상세가 같은 글을 한 번 더 부른다
FakeAdapter _editAdapter({FakeReply? item, FakeReply? put, String path = '/market-items/7'}) =>
    FakeAdapter()
      ..on('GET', path, item ?? FakeReply(200, marketDetailJson()))
      ..on('PUT', path, put ?? FakeReply(200, marketDetailJson()))
      ..on('GET', path, FakeReply(200, marketDetailJson()));

void main() {
  group('쓰기 화면 접근', () {
    _test('학교 인증 회원이면 제목과 폼(제목·판매/나눔·가격·본문·저장·취소)이 보인다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: FakeAdapter());
      expect(find.text('중고·나눔 글쓰기'), findsOneWidget);
      expect(_titleField, findsOneWidget);
      expect(_priceField, findsOneWidget); // 처음은 판매라 가격 칸이 있다
      expect(_contentField, findsOneWidget);
      expect(_tradeValue(tester), 'SALE');
      expect(find.bySemanticsLabel(RegExp('판매/나눔')), findsOneWidget);
      expect(find.bySemanticsLabel('가격(원)'), findsOneWidget);
      expect(find.bySemanticsLabel('제목'), findsOneWidget);
      expect(find.bySemanticsLabel('본문'), findsOneWidget);
      expect(find.text('0 / 2000'), findsOneWidget);
      expect(_saveButton, findsOneWidget);
      expect(_cancelButton, findsOneWidget);
      handle.dispose();
    });

    _test('미인증 회원이면 학교 인증 안내와 내 정보 링크만 보이고 폼은 없다', (tester) async {
      final app = await pumpApp(tester, location: '/market/new', user: userJson(), adapter: FakeAdapter());
      expect(find.text('중고·나눔 글쓰기'), findsOneWidget);
      expect(find.text('중고·나눔 글은 학교 인증 후에 올릴 수 있어요'), findsOneWidget);
      expect(_saveButton, findsNothing);
      expect(_titleField, findsNothing);
      await tester.tap(find.text('내 정보에서 학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });

    _test('[취소]를 누르면 /market 으로 간다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await tester.tap(_cancelButton);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market');
    });
  });

  group('쓰기: 입력 검사 (요청이 나가지 않는다)', () {
    _test('제목이 공백뿐이면 "제목과 본문을 입력해 주세요"', (tester) async {
      final adapter = FakeAdapter();
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester, title: '   ');
      await _save(tester);
      expect(find.text(_fillAll), findsOneWidget);
      expect(adapter.calls.where((c) => c.method == 'POST'), isEmpty);
      expect(_uri(app), '/market/new');
      expect(_text(tester, _contentField), '설명'); // 입력은 그대로
    });

    _test('본문이 비었으면 "제목과 본문을 입력해 주세요" (가격이 틀려도 이 문구가 먼저)', (tester) async {
      final adapter = FakeAdapter();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester, content: '', price: 'abc');
      await _save(tester);
      expect(find.text(_fillAll), findsOneWidget);
      expect(find.text(_priceRange), findsNothing);
      expect(adapter.calls.where((c) => c.method == 'POST'), isEmpty);
    });

    // 막혀야 하는 가격 입력: (설명, 입력값)
    for (final (label, value) in const [
      ('0', '0'),
      ('10,000,001', '10000001'),
      ('빈 칸', ''),
      ('공백뿐', '   '),
      ('글자 abc', 'abc'),
      ('음수', '-5'),
      ('소수', '1.5'),
      ('쉼표', '1,000'),
      ('아주 큰 수', '99999999999999999999999'),
    ]) {
      _test('가격이 $label 이면 화면에서 막힌다 (POST 없음, 입력 유지)', (tester) async {
        final adapter = FakeAdapter();
        final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
        await _fill(tester, price: value);
        await _save(tester);
        expect(find.text(_priceRange), findsOneWidget);
        expect(adapter.calls.where((c) => c.method == 'POST'), isEmpty);
        expect(_uri(app), '/market/new');
        expect(_text(tester, _titleField), '가짜 자전거');
        expect(_text(tester, _priceField), value);
        expect(_saveButton, findsOneWidget); // 버튼은 그대로 (잠기지 않는다)
      });
    }

    // 통과하는 가격: (입력값, 보내는 숫자)
    for (final (value, sent) in const [('1', 1), ('10000000', 10000000), ('5000', 5000), (' 5000 ', 5000), ('0007', 7)]) {
      _test('가격 "$value" 은 통과해서 price가 숫자 $sent 로 나간다', (tester) async {
        final adapter = _createAdapter();
        final bodies = _bodies(adapter);
        await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
        await _fill(tester, price: value);
        await _save(tester);
        expect(bodies, hasLength(1));
        expect(bodies.single['price'], sent);
        expect(bodies.single['price'], isA<int>());
      });
    }

    _test('검사에 걸렸다가 고쳐서 다시 저장하면 이전 문구는 사라진다', (tester) async {
      final adapter = _createAdapter();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester, price: '0');
      await _save(tester);
      expect(find.text(_priceRange), findsOneWidget);
      await _enter(tester, _priceField, '100');
      await _save(tester);
      expect(find.text(_priceRange), findsNothing);
      expect(adapter.count('POST', '/market-items'), 1);
    });
  });

  group('쓰기: 요청 본문과 이동', () {
    _test('판매: {title, content, tradeType, price(숫자)} 를 POST하고 성공하면 /market/{id} 로 가며 기록이 쌓인다', (tester) async {
      final nav = NavLog()..install(tester);
      final adapter = _createAdapter();
      final bodies = _bodies(adapter);
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await _save(tester);
      expect(bodies.single, {'title': '가짜 자전거', 'content': '설명', 'tradeType': 'SALE', 'price': 5000});
      expect(_uri(app), '/market/9');
      expect(nav.last.path, '/market/9');
      expect(nav.last.replace, isFalse); // 새 칸을 쌓는 이동 (React의 navigate)
    });

    _test('나눔: 가격 칸이 사라지고 POST 본문에 price 키 자체가 없다', (tester) async {
      final adapter = _createAdapter();
      final bodies = _bodies(adapter);
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      expect(_priceField, findsOneWidget);
      await _pickTrade(tester, '나눔');
      expect(_priceField, findsNothing);
      expect(_tradeValue(tester), 'FREE');
      await _fill(tester, title: '새 나눔', content: '가져가세요', price: null);
      await _save(tester);
      expect(bodies.single, {'title': '새 나눔', 'content': '가져가세요', 'tradeType': 'FREE'});
      expect(bodies.single.containsKey('price'), isFalse);
      expect(_uri(app), '/market/9');
    });

    _test('판매에서 가격을 적은 뒤 나눔으로 바꿔 저장해도 옛 가격은 새지 않는다', (tester) async {
      final adapter = _createAdapter();
      final bodies = _bodies(adapter);
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester, price: '5000');
      await _pickTrade(tester, '나눔');
      await _save(tester);
      expect(bodies.single.containsKey('price'), isFalse);
      expect(bodies.single['tradeType'], 'FREE');
    });

    _test('나눔으로 갔다가 판매로 돌아오면 가격 칸이 다시 보이고 가격 검사가 다시 적용된다', (tester) async {
      final adapter = FakeAdapter();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _pickTrade(tester, '나눔');
      await _pickTrade(tester, '판매');
      expect(_priceField, findsOneWidget);
      await _fill(tester, price: '');
      await _save(tester);
      expect(find.text(_priceRange), findsOneWidget);
    });

    _test('제목 칸에서 Enter를 눌러도 저장된다 (POST 1번)', (tester) async {
      final adapter = _createAdapter();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/market-items'), 1);
    });

    _test('가격 칸에서 Enter를 눌러도 저장된다 (POST 1번)', (tester) async {
      final adapter = _createAdapter();
      await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_priceField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/market-items'), 1);
    });

    _test('저장 중에는 "저장 중..."으로 잠기고 [취소]도 비활성이며, 또 눌러도 POST는 1번이다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('POST', '/market-items', FakeReply(201, marketDetailJson(id: 9), gate))
        ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9)));
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_cancelButton).onPressed, isNull);
      // 저장 중 Enter 제출도 무시
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump(const Duration(milliseconds: 50));
      await tester.tap(_savingButton, warnIfMissed: false);
      await tester.pump();
      expect(adapter.count('POST', '/market-items'), 1);
      expect(_uri(app), '/market/new');

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/9');
      expect(adapter.count('POST', '/market-items'), 1);
    });

    _test('저장 요청이 오는 사이 화면을 떠났으면 상세로 이동하지 않는다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('POST', '/market-items', FakeReply(201, marketDetailJson(id: 9), gate));
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      app.router.go('/nope-page'); // 없는 주소 = 404 화면 (요청 없음)
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/nope-page');
    });
  });

  group('쓰기: 실패 문구', () {
    // 응답 → 보이는 문구. 입력은 남고 버튼은 되살아난다
    for (final (name, reply, message) in <(String, FakeReply, String)>[
      ('400', FakeReply(400), _fieldsInvalid),
      ('403', FakeReply(403), '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요'),
      ('500', FakeReply(500), '저장하지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ]) {
      _test('$name → "$message", 입력 유지, [저장] 되살아남', (tester) async {
        final adapter = FakeAdapter()..on('POST', '/market-items', reply);
        final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
        await _fill(tester);
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text(_relogin), findsNothing);
        expect(_uri(app), '/market/new');
        expect(_text(tester, _titleField), '가짜 자전거');
        expect(_text(tester, _contentField), '설명');
        expect(_text(tester, _priceField), '5000');
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
        expect(tester.widget<OutlinedButton>(_cancelButton).onPressed, isNotNull);
      });
    }

    _test('실패한 뒤 다시 저장하면 이전 실패 문구가 사라지고 성공하면 이동한다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/market-items', FakeReply(500))
        ..on('POST', '/market-items', FakeReply(201, marketDetailJson(id: 9)))
        ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9)));
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester);
      await _save(tester);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      await _save(tester);
      expect(_uri(app), '/market/9');
    });

    _test('401(재발급도 실패)이면 "다시 로그인해 주세요"가 뜨고 입력·폼은 남는다 (미인증 안내로 바뀌지 않는다)', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/market-items', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/market/new', user: _verified(), adapter: adapter);
      await _fill(tester, title: '쓰던 제목', content: '쓰던 본문');
      await tester.tap(_saveButton);
      // 위젯 테스트의 가짜 시계로는 재발급 왕복이 안 흘러서 진짜 시계로 기다린다
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(_uri(app), '/market/new');
      expect(find.text(_relogin), findsOneWidget);
      expect(find.text('저장하지 못했습니다'), findsNothing);
      expect(_text(tester, _titleField), '쓰던 제목');
      expect(_text(tester, _contentField), '쓰던 본문');
      expect(_text(tester, _priceField), '5000');
      expect(find.text('글을 올리려면 로그인이 필요해요'), findsNothing);
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
    });
  });

  group('수정 화면: 불러오기', () {
    _test('판매 글이면 제목·판매·가격·본문이 채워진 폼이 보인다', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(title: '자전거', content: '탈만해요', price: 5000)));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(find.text('중고·나눔 글 수정'), findsOneWidget);
      expect(adapter.count('GET', '/market-items/7'), 1);
      expect(_text(tester, _titleField), '자전거');
      expect(_text(tester, _contentField), '탈만해요');
      expect(_text(tester, _priceField), '5000');
      expect(_tradeValue(tester), 'SALE');
      expect(find.text('${'탈만해요'.length} / 2000'), findsOneWidget);
      expect(_saveButton, findsOneWidget);
      expect(_cancelButton, findsOneWidget);
      expect(_toListButton, findsNothing);
    });

    _test('나눔 글이면 가격 칸이 없다', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(tradeCode: 'FREE', tradeName: '나눔', price: 0)));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(_tradeValue(tester), 'FREE');
      expect(_priceField, findsNothing);
    });

    _test('불러오는 동안은 폼이 없고 "불러오는 중..."이 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(), gate));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_saveButton, findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(_saveButton, findsOneWidget);
    });

    for (final (name, reply, message) in <(String, FakeReply, String)>[
      ('404', FakeReply(404), '글을 찾을 수 없습니다'),
      ('400', FakeReply(400), '글을 찾을 수 없습니다'),
      ('500', FakeReply(500), '글을 불러오지 못했습니다'),
      ('네트워크', FakeReply.networkError(), '서버에 연결할 수 없습니다'),
    ]) {
      _test('불러오기 $name → "$message", 폼 없음, [목록으로]로 /market', (tester) async {
        final adapter = FakeAdapter()
          ..on('GET', '/market-items/7', reply)
          ..on('GET', '/schools', FakeReply(200, marketSchools))
          ..on('GET', '/market-items', FakeReply(200, []));
        final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
        expect(find.text(message), findsOneWidget);
        expect(_saveButton, findsNothing);
        expect(_titleField, findsNothing);
        await tester.tap(_toListButton);
        await tester.pumpAndSettle();
        expect(_uri(app), '/market');
      });
    }

    _test('응답의 글 id가 주소의 id와 다르면 폼을 보이지 않는다', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/market-items/8', FakeReply(200, marketDetailJson(id: 7)));
      await pumpApp(tester, location: '/market/8/edit', user: _verified(), adapter: adapter);
      expect(_saveButton, findsNothing);
      expect(_toListButton, findsNothing);
    });

    _test('/market/01/edit 는 숫자로 비교해 1번 글로 통과한다 (요청 경로는 01 그대로)', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/market-items/01', FakeReply(200, marketDetailJson(id: 1)));
      await pumpApp(tester, location: '/market/01/edit', user: _verified(), adapter: adapter);
      expect(adapter.count('GET', '/market-items/01'), 1);
      expect(_saveButton, findsOneWidget);
    });

    _test('남의 글이면 "본인 글만 수정할 수 있습니다"와 [목록으로]만 보이고 폼은 없다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(sellerId: 5)))
        ..on('GET', '/schools', FakeReply(200, marketSchools))
        ..on('GET', '/market-items', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(find.text('본인 글만 수정할 수 있습니다'), findsOneWidget);
      expect(_saveButton, findsNothing);
      expect(_titleField, findsNothing);
      await tester.tap(_toListButton);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market');
    });

    _test('거래 완료(DONE) 글이면 수정 불가 문구와 [목록으로]만 보이고 폼은 없다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(statusCode: 'DONE', statusName: '거래완료')))
        ..on('GET', '/schools', FakeReply(200, marketSchools))
        ..on('GET', '/market-items', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(find.text(_doneNotEditable), findsOneWidget);
      expect(_saveButton, findsNothing);
      expect(_titleField, findsNothing);
      await tester.tap(_toListButton);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market');
    });

    _test('남의 글이면서 DONE이면 "본인 글만" 문구만 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(sellerId: 5, statusCode: 'DONE', statusName: '거래완료')));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(find.text('본인 글만 수정할 수 있습니다'), findsOneWidget);
      expect(find.text(_doneNotEditable), findsNothing);
    });

    _test('예약중(RESERVED) 글은 수정할 수 있다', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중')));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(_saveButton, findsOneWidget);
    });

    _test('[취소]를 누르면 상세 /market/7', (tester) async {
      final adapter = _editAdapter();
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await tester.tap(_cancelButton);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/7');
    });
  });

  group('수정 화면: 저장', () {
    _test('바꾸지 않고 저장하면 처음 값 그대로 PUT하고, 기록을 교체해 상세로 간다', (tester) async {
      final nav = NavLog()..install(tester);
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(title: '자전거', content: '탈만해요', price: 5000)));
      final bodies = _bodies(adapter);
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await _save(tester);
      expect(bodies.single, {'title': '자전거', 'content': '탈만해요', 'tradeType': 'SALE', 'price': 5000});
      expect(adapter.count('PUT', '/market-items/7'), 1);
      expect(_uri(app), '/market/7');
      expect(nav.last.path, '/market/7');
      expect(nav.last.replace, isTrue); // 수정 폼이 뒤로 가기에 남지 않는다
    });

    _test('나눔 글을 저장하면 PUT 본문에 price 키가 없고 tradeType이 FREE다', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(tradeCode: 'FREE', tradeName: '나눔', price: 0)));
      final bodies = _bodies(adapter);
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await _save(tester);
      expect(bodies.single.containsKey('price'), isFalse);
      expect(bodies.single['tradeType'], 'FREE');
    });

    _test('판매 글을 나눔으로 바꿔 저장하면 price가 없고 tradeType이 FREE다 (옛 가격 5000이 새지 않는다)', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(price: 5000)));
      final bodies = _bodies(adapter);
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      expect(_text(tester, _priceField), '5000');
      await _pickTrade(tester, '나눔');
      expect(_priceField, findsNothing);
      await _save(tester);
      expect(bodies.single.containsKey('price'), isFalse);
      expect(bodies.single['tradeType'], 'FREE');
    });

    _test('나눔 글을 판매로 바꾸면 빈 가격 칸이 생기고, 비운 채 저장하면 가격 검사에 걸린다', (tester) async {
      final adapter = _editAdapter(item: FakeReply(200, marketDetailJson(tradeCode: 'FREE', tradeName: '나눔', price: 0)));
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await _pickTrade(tester, '판매');
      expect(_text(tester, _priceField), '');
      await _save(tester);
      expect(find.text(_priceRange), findsOneWidget);
      expect(adapter.count('PUT', '/market-items/7'), 0);
    });

    _test('고친 값이 PUT 본문에 담긴다', (tester) async {
      final adapter = _editAdapter();
      final bodies = _bodies(adapter);
      await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await _fill(tester, title: '새 제목', content: '새 본문', price: '12000');
      await _save(tester);
      expect(bodies.single, {'title': '새 제목', 'content': '새 본문', 'tradeType': 'SALE', 'price': 12000});
    });

    _test('저장 중에는 "저장 중..."으로 잠기고 PUT은 1번이다', (tester) async {
      final gate = Completer<void>();
      final adapter = _editAdapter(put: FakeReply(200, marketDetailJson(), gate));
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await tester.tap(_saveButton);
      await tester.pump(const Duration(milliseconds: 50));
      expect(_savingButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_savingButton).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_cancelButton).onPressed, isNull);
      expect(_uri(app), '/market/7/edit');
      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/7');
      expect(adapter.count('PUT', '/market-items/7'), 1);
    });

    // 응답 → 보이는 문구
    for (final (name, reply, message) in <(String, FakeReply, String)>[
      ('400', FakeReply(400), _fieldsInvalid),
      ('403', FakeReply(403), '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      ('404', FakeReply(404), '글을 찾을 수 없습니다'),
      ('409', FakeReply(409), _doneNotEditable),
      ('500', FakeReply(500), '저장하지 못했습니다'),
    ]) {
      _test('저장 $name → "$message", 입력 유지, [저장] 되살아남', (tester) async {
        final adapter = FakeAdapter()
          ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(title: '자전거')))
          ..on('PUT', '/market-items/7', reply);
        final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
        await _enter(tester, _titleField, '고친 제목');
        await _save(tester);
        expect(find.text(message), findsOneWidget);
        expect(find.text(_relogin), findsNothing);
        expect(_uri(app), '/market/7/edit');
        expect(_text(tester, _titleField), '고친 제목');
        expect(tester.widget<FilledButton>(_saveButton).onPressed, isNotNull);
      });
    }

    _test('저장 401(재발급도 실패)이면 로그인 안내가 뜨고 user가 null이어도 폼과 입력이 남는다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(title: '자전거')))
        ..on('PUT', '/market-items/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpApp(tester, location: '/market/7/edit', user: _verified(), adapter: adapter);
      await _enter(tester, _titleField, '고친 제목');
      await tester.tap(_saveButton);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(_uri(app), '/market/7/edit');
      expect(find.text(_relogin), findsOneWidget);
      expect(find.text('본인 글만 수정할 수 있습니다'), findsNothing);
      expect(_text(tester, _titleField), '고친 제목');
      expect(_saveButton, findsOneWidget);
    });
  });

  // ---- 폼 단위 (MarketItemForm만 따로 띄운다) ----
  group('MarketItemForm 단위', () {
    // 폼 하나만 띄운다. onSave 호출 본문·저장 중 알림을 기록한다
    Future<({List<Map<String, dynamic>> saved, List<bool> savingLog})> pumpForm(
      WidgetTester tester, {
      required Future<void> Function(Map<String, dynamic>) onSave,
      MarketItem? item,
      bool locked = false,
    }) async {
      useTallScreen(tester);
      final saved = <Map<String, dynamic>>[];
      final savingLog = <bool>[];
      Widget build(bool lockedNow) => MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: MarketItemForm(
              item: item,
              onSave: (b) {
                saved.add(b);
                return onSave(b);
              },
              errorTable: const {400: '고쳐 주세요'},
              cancelTo: '/market',
              locked: lockedNow,
              onSavingChange: savingLog.add,
            ),
          ),
        ),
      );
      await tester.pumpWidget(build(locked));
      return (saved: saved, savingLog: savingLog);
    }

    testWidgets('UTF-16 제한: 제목은 100, 본문은 2000 (이모지는 2칸이라 반쪽으로 잘리지 않는다)', (tester) async {
      await pumpForm(tester, onSave: (_) async {});
      await _enter(tester, _titleField, 'a' * 101);
      expect(_text(tester, _titleField).length, 100);
      await _enter(tester, _titleField, '😀' * 60); // 120칸 → 한도 100 = 이모지 50개
      expect(_text(tester, _titleField).length, 100);
      expect(_text(tester, _titleField), '😀' * 50);
      await _enter(tester, _titleField, '${'a' * 99}😀'); // 100칸째가 이모지 앞 반쪽이면 통째로 뺀다
      expect(_text(tester, _titleField), 'a' * 99);

      await _enter(tester, _contentField, 'b' * 2001);
      expect(_text(tester, _contentField).length, 2000);
      expect(find.text('2000 / 2000'), findsOneWidget);
      await _enter(tester, _contentField, '😀😀');
      expect(find.text('4 / 2000'), findsOneWidget); // 글자 수도 UTF-16 길이
    });

    testWidgets('locked 이면 [저장]이 비활성이고, Enter로도 제출되지 않는다', (tester) async {
      final r = await pumpForm(tester, onSave: (_) async {}, locked: true);
      await _enter(tester, _titleField, '제목');
      await _enter(tester, _contentField, '본문');
      await _enter(tester, _priceField, '100');
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNull);
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      await tester.tap(_priceField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      expect(r.saved, isEmpty);
      expect(r.savingLog, isEmpty);
      expect(find.text(_fillAll), findsNothing); // 검사 문구도 띄우지 않고 그냥 무시
    });

    testWidgets('locked 가 풀리면 다시 저장할 수 있다', (tester) async {
      final saved = <Map<String, dynamic>>[];
      final lockedNotifier = ValueNotifier(true);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: ValueListenableBuilder<bool>(
                valueListenable: lockedNotifier,
                builder: (_, locked, _) => MarketItemForm(
                  onSave: (b) async => saved.add(b),
                  errorTable: const {},
                  cancelTo: '/market',
                  locked: locked,
                ),
              ),
            ),
          ),
        ),
      );
      useTallScreen(tester);
      await _enter(tester, _titleField, '제목');
      await _enter(tester, _contentField, '본문');
      await _enter(tester, _priceField, '100');
      expect(tester.widget<FilledButton>(_saveButton).onPressed, isNull);
      lockedNotifier.value = false;
      await tester.pump();
      await tester.tap(_saveButton);
      await tester.pump();
      expect(saved, hasLength(1));
    });

    testWidgets('onSavingChange: 저장 시작 true → 실패하면 false, 검사에 걸리면 알리지 않는다', (tester) async {
      var fail = true;
      final r = await pumpForm(tester, onSave: (_) async {
        if (fail) throw Exception('실패');
      });
      await _enter(tester, _titleField, '제목');
      await _enter(tester, _contentField, '본문');
      // 가격이 비어 검사에 걸림: 알림 없음
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(r.savingLog, isEmpty);
      await _enter(tester, _priceField, '100');
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(r.savingLog, [true, false]);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      fail = false;
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      // 성공하면 false로 되돌리지 않는다 (화면이 바뀌는 것을 기다리는 중)
      expect(r.savingLog, [true, false, true]);
      expect(_savingButton, findsOneWidget);
    });

    testWidgets('요청 중에는 두 번 눌러도 onSave는 1번, 성공하면 버튼을 되살리지 않는다', (tester) async {
      final gate = Completer<void>();
      final r = await pumpForm(tester, onSave: (_) => gate.future);
      await _enter(tester, _titleField, '제목');
      await _enter(tester, _contentField, '본문');
      await _enter(tester, _priceField, '100');
      await tester.tap(_saveButton);
      await tester.pump();
      await tester.tap(_savingButton, warnIfMissed: false);
      await tester.pump();
      await tester.tap(_titleField);
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pump();
      expect(r.saved, hasLength(1));
      expect(tester.widget<OutlinedButton>(_cancelButton).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_savingButton, findsOneWidget);
      expect(r.saved, hasLength(1));
    });

    testWidgets('실패 문구는 화면이 넘긴 표로 고른다 (400은 표의 문구)', (tester) async {
      await pumpForm(tester, onSave: (_) async => throw Exception('x'));
      // 표에 없는 오류는 기본 문구
      await _enter(tester, _titleField, '제목');
      await _enter(tester, _contentField, '본문');
      await _enter(tester, _priceField, '100');
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text('고쳐 주세요'), findsNothing);
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
    });

    testWidgets('item 으로 채우면 처음 값이 들어가고, 나눔 item 은 가격 칸이 없다', (tester) async {
      final sale = MarketItem.fromJson(marketDetailJson(title: 'T', content: 'C', price: 300));
      await pumpForm(tester, onSave: (_) async {}, item: sale);
      expect(_text(tester, _titleField), 'T');
      expect(_text(tester, _contentField), 'C');
      expect(_text(tester, _priceField), '300');
      final free = MarketItem.fromJson(marketDetailJson(tradeCode: 'FREE', tradeName: '나눔', price: 0));
      await tester.pumpWidget(const SizedBox()); // 앞 폼의 상태를 버리고 새로 띄운다
      await pumpForm(tester, onSave: (_) async {}, item: free);
      expect(_priceField, findsNothing);
      expect(_tradeValue(tester), 'FREE');
    });
  });
}
