// S26-k5 중고·나눔 글쓰기·수정 화면 새 모양 테스트: 본문칸 글자(15·줄 간격 1.65),
// 폼 안 간격(16·4), 가격 라벨이 칸 위, 375px에서 저장 버튼이 탭 막대에 안 가려짐, 320px 넘침 없음.
// 기능 동작은 market_write_pages_test가 본다.
// (한 테스트에서 앱을 두 번 열면 라우터가 첫 주소로 남아서, 화면마다 테스트를 나눈다.)
import 'package:board_web/theme/app_colors.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';

const _create = '/market/new';
const _edit = '/market/7/edit';

FakeAdapter _adapter() => FakeAdapter()
  ..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(content: '원래 내용')));

final _title = find.byKey(const ValueKey('title-field'));
final _price = find.byKey(const ValueKey('price-field'));
final _content = find.byKey(const ValueKey('content-field'));
final _select = find.byKey(const ValueKey('trade-type-select'));
final _save = find.widgetWithText(FilledButton, '저장');
final _cancel = find.widgetWithText(OutlinedButton, '취소');

// 화면 크기를 정하고 쓰기 또는 수정을 연다. 어느 화면인지(수정이면 원래 본문이 채워짐)도 바로 확인한다
Future<void> _open(WidgetTester tester, String location, {Size size = const Size(375, 812)}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = size;
  addTearDown(tester.view.reset);
  await pumpApp(tester, location: location, user: userJson(school: marketSeoul), adapter: _adapter());
  await tester.pumpAndSettle();
  expect(_content, findsOneWidget);
  final text = tester.widget<TextField>(_content).controller!.text;
  expect(text, location == _edit ? '원래 내용' : '');
}

// 라벨 글자의 위치. 라벨 글자 크기(14)로 가려 찾는다
Rect _label(WidgetTester tester, String text) => tester.getRect(
    find.byWidgetPredicate((w) => w is Text && w.data == text && w.style?.fontSize == AppSizes.fsSmall2));

void main() {
  for (final location in [_create, _edit]) {
    testWidgets('$location 본문칸은 실제로 글자 15·줄 간격 1.65·ink로 그려진다', (tester) async {
      await _open(tester, location);
      final editable = tester.widget<EditableText>(find.descendant(of: _content, matching: find.byType(EditableText)));
      expect(editable.style.fontSize, 15);
      expect(editable.style.height, 1.65);
      expect(editable.style.height, AppSizes.bodyLineHeight);
      expect(editable.style.color, AppColors.ink);
    });

    testWidgets('$location 폼 안 간격: 선택 상자 줄 아래 16 → 가격 라벨, 가격칸 아래 16 → 본문 라벨, 본문칸 아래 4 → 글자 수, 글자 수 아래 16 → 버튼', (tester) async {
      await _open(tester, location);
      final box = tester.getRect(find.ancestor(of: _select, matching: find.byType(Container)).first); // 높이 40 상자
      expect(box.height, 40);
      expect(_label(tester, '가격(원)').top - box.bottom, AppSizes.space4);
      expect(_label(tester, '본문').top - tester.getRect(_price).bottom, AppSizes.space4);
      final count = tester.getRect(find.textContaining(' / 2000'));
      expect(count.top - tester.getRect(_content).bottom, AppSizes.space1);
      expect(tester.getRect(_save).top - count.bottom, AppSizes.space4);
      // 제목칸 아래 16 → 선택 상자 줄
      expect(box.top - tester.getRect(_title).bottom, AppSizes.space4);
    });

    testWidgets('$location 나눔으로 바꾸면 가격 칸이 없고 본문 라벨은 선택 상자 아래 16에 붙는다', (tester) async {
      await _open(tester, location);
      final box = tester.getRect(find.ancestor(of: _select, matching: find.byType(Container)).first);
      // 선택 상자를 눌러 "나눔"을 고른다 (market_write_pages_test의 _pickTrade와 같은 방법)
      await tester.tap(_select);
      await tester.pumpAndSettle();
      await tester.tap(find.text('나눔').last);
      await tester.pumpAndSettle();
      expect(_price, findsNothing);
      expect(_label(tester, '본문').top - box.bottom, AppSizes.space4);
    });

    testWidgets('$location 가격 라벨은 칸 위에 있고 칸 폭은 160이다', (tester) async {
      await _open(tester, location);
      final label = _label(tester, '가격(원)');
      expect(label.bottom, lessThanOrEqualTo(tester.getRect(_price).top));
      expect(label.left, tester.getRect(_price).left);
      expect(tester.getRect(_price).width, 160);
    });

    testWidgets('$location 375x667: 스크롤 끝에서 저장 버튼 줄이 탭 막대 위에 다 보인다', (tester) async {
      await _open(tester, location, size: const Size(375, 667));
      await tester.ensureVisible(_cancel);
      await tester.pumpAndSettle();
      final save = tester.getRect(_save);
      final tabs = tester.getRect(find.byKey(const ValueKey('bottom-tab-0')));
      expect(save.bottom, lessThanOrEqualTo(tabs.top));
      expect(save.top, greaterThanOrEqualTo(0));
    });

    testWidgets('$location 320px: 가격 칸이 줄을 넘치지 않고 화면 안에 있다', (tester) async {
      await _open(tester, location, size: const Size(320, 640));
      expect(tester.takeException(), isNull);
      expect(tester.getRect(_price).right, lessThanOrEqualTo(320));
      expect(tester.getRect(_content).right, lessThanOrEqualTo(320));
    });
  }
}
