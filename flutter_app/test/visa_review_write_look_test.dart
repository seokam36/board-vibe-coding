// S26-k5 비자 후기 쓰기·수정 화면 새 모양 테스트: 본문칸 글자(15·줄 간격 1.65),
// 폼 안 간격(16·4), 375px에서 저장 버튼이 탭 막대에 안 가려짐, 320px 넘침 없음.
// 기능 동작은 visa_review_create_page_test·visa_review_edit_page_test가 본다.
// (한 테스트에서 앱을 두 번 열면 라우터가 첫 주소로 남아서, 화면마다 테스트를 나눈다.)
import 'package:board_web/theme/app_colors.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';

const _create = '/visa-reviews/new';
const _edit = '/visa-reviews/7/edit';

FakeAdapter _adapter() => FakeAdapter()
  ..on('GET', '/visa-reviews/7', FakeReply(200, reviewJson(content: '원래 후기')))
  ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson));

final _content = find.byKey(const ValueKey('content-field'));
final _selects = [
  find.byKey(const ValueKey('visa-type-select')),
  find.byKey(const ValueKey('application-type-select')),
  find.byKey(const ValueKey('result-select')),
];
final _save = find.widgetWithText(FilledButton, '저장');
// 쓰기는 목록으로, 수정은 취소
Finder _cancel(String location) => find.widgetWithText(OutlinedButton, location == _edit ? '취소' : '목록으로');

// 화면 크기를 정하고 쓰기 또는 수정을 연다. 어느 화면인지(수정이면 원래 본문이 채워짐)도 바로 확인한다
Future<void> _open(WidgetTester tester, String location, {Size size = const Size(375, 812)}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = size;
  addTearDown(tester.view.reset);
  await pumpApp(tester, location: location, user: userJson(school: visaSeoul), adapter: _adapter());
  await tester.pumpAndSettle();
  expect(_content, findsOneWidget);
  final text = tester.widget<TextField>(_content).controller!.text;
  expect(text, location == _edit ? '원래 후기' : '');
}

// 라벨 글자의 위치. 글자 크기(14)로 가려 찾는다
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

    testWidgets('$location 폼 안 간격: 선택 상자 줄 아래 16 → 접수일, 날짜 아래 16 → 본문 라벨, 본문칸 아래 4 → 글자 수, 글자 수 아래 16 → 버튼', (tester) async {
      await _open(tester, location);
      // 선택 상자(높이 40) 셋 중 가장 아래 끝
      var boxBottom = 0.0;
      for (final s in _selects) {
        final box = tester.getRect(find.ancestor(of: s, matching: find.byType(Container)).first);
        expect(box.height, 40);
        if (box.bottom > boxBottom) boxBottom = box.bottom;
      }
      expect(tester.getRect(find.text('접수일')).top - boxBottom, AppSizes.space4);
      // 날짜 칸 아래 16 → 본문 라벨 (좁으면 두 칸이 세로라 아래쪽인 결과일 칸 기준)
      final dateBox = tester.getRect(find.byKey(const ValueKey('result-date-field')));
      expect(_label(tester, '본문 (선택)').top - dateBox.bottom, AppSizes.space4);
      final count = tester.getRect(find.textContaining(' / 2000'));
      expect(count.top - tester.getRect(_content).bottom, AppSizes.space1);
      expect(tester.getRect(_save).top - count.bottom, AppSizes.space4);
    });

    testWidgets('$location 375x667: 스크롤 끝에서 저장 버튼 줄이 탭 막대 위에 다 보인다', (tester) async {
      await _open(tester, location, size: const Size(375, 667));
      await tester.ensureVisible(_cancel(location));
      await tester.pumpAndSettle();
      final save = tester.getRect(_save);
      final tabs = tester.getRect(find.byKey(const ValueKey('bottom-tab-0')));
      expect(save.bottom, lessThanOrEqualTo(tabs.top));
      expect(save.top, greaterThanOrEqualTo(0));
    });

    testWidgets('$location 320px 넘침 없음', (tester) async {
      await _open(tester, location, size: const Size(320, 640));
      expect(tester.takeException(), isNull);
    });
  }
}
