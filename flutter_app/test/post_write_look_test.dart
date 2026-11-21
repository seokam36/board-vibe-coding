// S26-f 글쓰기·수정 화면 새 모양 테스트: 내용칸 글자(15·줄 간격 1.65), 폼 안 간격 16,
// 학교 뱃지·말머리 상자 세로 중심, 375px에서 저장 버튼이 탭 막대에 안 가려짐, 키보드, 넘침 없음.
// 기능 동작은 post_create_page_test·post_edit_page_test가 본다.
// (한 테스트에서 앱을 두 번 열면 라우터가 첫 주소로 남아서, 화면마다 테스트를 나눈다.)
import 'package:board_web/theme/app_colors.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

const _categories = [
  {'code': 'CHAT', 'name': '잡담'},
  {'code': 'QUESTION', 'name': '질문'},
];
const _create = '/posts/new';
const _edit = '/posts/7/edit';

FakeAdapter _adapter() => FakeAdapter()
  ..on('GET', '/categories', FakeReply(200, _categories))
  ..on('GET', '/posts/7', FakeReply(200, postJson(content: '원래 내용')));

final _title = find.byKey(const ValueKey('title-field'));
final _content = find.byKey(const ValueKey('content-field'));
final _select = find.byKey(const ValueKey('category-select'));
final _save = find.widgetWithText(FilledButton, '저장');

// 화면 크기를 정하고 글쓰기 또는 수정을 연다. 어느 화면인지(목록으로/취소 버튼)도 바로 확인한다
Future<void> _open(WidgetTester tester, String location,
    {Size size = const Size(375, 812), String school = '서울대'}) async {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = size;
  addTearDown(tester.view.reset);
  await pumpApp(tester, location: location, user: userJson(school: {'id': 1, 'name': school}), adapter: _adapter());
  await tester.pumpAndSettle();
  expect(find.widgetWithText(OutlinedButton, location == _create ? '목록으로' : '취소'), findsOneWidget);
}

// 라벨 글자(제목·내용)의 위치. 입력칸 힌트와 같은 글자라 라벨 글자 크기(14)로 가려 찾는다
Rect _label(WidgetTester tester, String text) => tester.getRect(find.byWidgetPredicate(
    (w) => w is Text && w.data == text && w.style?.fontSize == AppSizes.fsSmall2));

void main() {
  for (final location in [_create, _edit]) {
    testWidgets('$location 내용칸은 실제로 글자 15·줄 간격 1.65·ink로 그려진다', (tester) async {
      await _open(tester, location);
      final editable = tester.widget<EditableText>(find.descendant(of: _content, matching: find.byType(EditableText)));
      expect(editable.style.fontSize, 15);
      expect(editable.style.height, 1.65);
      expect(editable.style.height, AppSizes.bodyLineHeight);
      expect(editable.style.color, AppColors.ink);
    });

    testWidgets('$location 폼 안 간격은 space4(16)다', (tester) async {
      await _open(tester, location);
      // 말머리 줄 아래 16 → 제목 라벨
      final box = tester.getRect(find.ancestor(of: _select, matching: find.byType(Container)).first); // 높이 40 상자
      expect(_label(tester, '제목').top - box.bottom, AppSizes.space4);
      // 제목칸 아래 16 → 내용 라벨
      expect(_label(tester, '내용').top - tester.getRect(_title).bottom, AppSizes.space4);
      // 내용칸 아래 16 → 버튼 줄
      expect(tester.getRect(_save).top - tester.getRect(_content).bottom, AppSizes.space4);
    });

    testWidgets('$location 학교 뱃지와 말머리 상자의 세로 중심이 같다', (tester) async {
      await _open(tester, location);
      // 뱃지는 학교 이름 글자를 감싼 AppBadge 바탕 상자, 말머리 상자는 높이 40인 바깥 Container
      final badge = tester.getRect(find.ancestor(of: find.text('서울대'), matching: find.byType(Container)).first);
      final box = tester.getRect(find.ancestor(of: _select, matching: find.byType(Container)).first);
      expect((badge.center.dy - box.center.dy).abs(), lessThanOrEqualTo(0.5));
    });

    testWidgets('$location 375px: 스크롤 끝에서 버튼 줄이 탭 막대 위에 다 보인다', (tester) async {
      await _open(tester, location);
      await tester.ensureVisible(find.widgetWithText(OutlinedButton, location == _create ? '목록으로' : '취소'));
      await tester.pumpAndSettle();
      final save = tester.getRect(_save);
      final tabs = tester.getRect(find.byKey(const ValueKey('bottom-tab-0')));
      expect(save.bottom, lessThanOrEqualTo(tabs.top));
      expect(save.top, greaterThanOrEqualTo(0));
    });

    testWidgets('$location 375px 넘침 없음 (긴 학교 이름도)', (tester) async {
      await _open(tester, location, school: '아주아주아주아주 긴 이름의 국립 종합 대학교 글로벌 캠퍼스 대학원');
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('키보드가 열리면(아래 여백 300) 탭 막대는 숨고 저장 버튼이 키보드 위까지 스크롤된다', (tester) async {
    await _open(tester, _create);
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetViewInsets);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('bottom-tab-0')), findsNothing);
    await tester.ensureVisible(_save);
    await tester.pumpAndSettle();
    expect(tester.getRect(_save).bottom, lessThanOrEqualTo(812 - 300)); // 키보드 위
    expect(tester.takeException(), isNull);
  });
}
