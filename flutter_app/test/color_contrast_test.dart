import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/theme/app_theme.dart';

// 색 한 칸(0~1)을 빛의 양(선형 값)으로 바꾼다 (WCAG 공식)
double _linear(double c) => c <= 0.03928 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();

// 상대 휘도: 사람 눈에 얼마나 밝게 보이는가 (0 검정 ~ 1 흰색)
double _luminance(Color c) => 0.2126 * _linear(c.r) + 0.7152 * _linear(c.g) + 0.0722 * _linear(c.b);

// 대비 비율: 밝은 쪽과 어두운 쪽의 밝기 비 (1:1 ~ 21:1). 글자는 4.5 이상이 읽기 좋다.
double contrast(Color a, Color b) {
  final la = _luminance(a);
  final lb = _luminance(b);
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

void main() {
  // (이름, 글자색, 바탕색): 전부 4.5:1 이상이어야 한다
  final textPairs = <(String, Color, Color)>[
    ('ink/paper', AppColors.ink, AppColors.paper),
    ('ink/canvas', AppColors.ink, AppColors.canvas),
    ('muted/paper', AppColors.muted, AppColors.paper),
    ('muted/canvas', AppColors.muted, AppColors.canvas),
    ('흰 글자/ink(주요 버튼)', AppColors.paper, AppColors.ink),
    ('accent/paper', AppColors.accent, AppColors.paper),
    ('accent/canvas', AppColors.accent, AppColors.canvas),
    ('accentInk/accentSoft', AppColors.accentInk, AppColors.accentSoft),
    ('accentInk/hover(신고 쪽지 뱃지)', AppColors.accentInk, AppColors.hover),
    ('ink/highlight', AppColors.ink, AppColors.highlight),
    ('highlightInk/highlightSoft', AppColors.highlightInk, AppColors.highlightSoft),
    ('muted/badgeChatBg', AppColors.muted, AppColors.badgeChatBg),
    ('badgeQuestionInk/badgeQuestionBg', AppColors.badgeQuestionInk, AppColors.badgeQuestionBg),
    ('danger/paper', AppColors.danger, AppColors.paper),
    ('danger/dangerSoft', AppColors.danger, AppColors.dangerSoft),
    ('흰 글자/dangerStrong(위험 버튼 누름)', AppColors.paper, AppColors.dangerStrong),
    ('successInk/successSoft', AppColors.successInk, AppColors.successSoft),
    ('ink/infoSoft', AppColors.ink, AppColors.infoSoft),
    ('ink/hover', AppColors.ink, AppColors.hover),
    ('muted/hover(쪽지함 hover 줄)', AppColors.muted, AppColors.hover),
    ('paper/accent(onPrimary)', AppColors.paper, AppColors.accent),
    ('accent/infoSoft(비자 띠 링크)', AppColors.accent, AppColors.infoSoft),
    ('ink/accentSoft(챗봇 질문 말풍선)', AppColors.ink, AppColors.accentSoft),
    ('danger/canvas', AppColors.danger, AppColors.canvas),
    ('accentInk/paper(떠 있는 라벨)', AppColors.accentInk, AppColors.paper),
    ('흰 글자/툴팁 바탕(ink)', AppColors.paper, AppColors.ink),
  ];

  group('글자 대비 (4.5:1 이상)', () {
    for (final (name, fg, bg) in textPairs) {
      test(name, () {
        final ratio = contrast(fg, bg);
        // ignore: avoid_print
        print('$name = ${ratio.toStringAsFixed(2)}');
        expect(ratio, greaterThanOrEqualTo(4.5));
      });
    }
  });

  test('입력칸 테두리(fieldBorder)는 흰 바탕과 3:1 이상', () {
    final ratio = contrast(AppColors.fieldBorder, AppColors.paper);
    // ignore: avoid_print
    print('fieldBorder/paper = ${ratio.toStringAsFixed(2)}');
    expect(ratio, greaterThanOrEqualTo(3.0));
  });

  test('모든 AppColors 값이 서로 다르다 (어느 토큰을 썼는지 테스트가 구분하려면 필요)', () {
    final all = <String, Color>{
      'canvas': AppColors.canvas,
      'paper': AppColors.paper,
      'hover': AppColors.hover,
      'ink': AppColors.ink,
      'muted': AppColors.muted,
      'line': AppColors.line,
      'fieldBorder': AppColors.fieldBorder,
      'accent': AppColors.accent,
      'accentSoft': AppColors.accentSoft,
      'accentInk': AppColors.accentInk,
      'highlight': AppColors.highlight,
      'highlightSoft': AppColors.highlightSoft,
      'highlightInk': AppColors.highlightInk,
      'badgeChatBg': AppColors.badgeChatBg,
      'badgeQuestionInk': AppColors.badgeQuestionInk,
      'badgeQuestionBg': AppColors.badgeQuestionBg,
      'danger': AppColors.danger,
      'dangerStrong': AppColors.dangerStrong,
      'dangerSoft': AppColors.dangerSoft,
      'successInk': AppColors.successInk,
      'successSoft': AppColors.successSoft,
      'infoSoft': AppColors.infoSoft,
      'backdrop': AppColors.backdrop,
    };
    final byValue = <int, String>{};
    for (final e in all.entries) {
      final v = e.value.toARGB32();
      expect(byValue.containsKey(v), isFalse, reason: '${e.key}와 ${byValue[v]}의 값이 같다');
      byValue[v] = e.key;
    }
  });

  test('fieldBorder는 canvas 위에서도 3:1 이상', () {
    final ratio = contrast(AppColors.fieldBorder, AppColors.canvas);
    // ignore: avoid_print
    print('fieldBorder/canvas = ${ratio.toStringAsFixed(2)}');
    expect(ratio, greaterThanOrEqualTo(3.0));
  });

  test('주요 버튼은 ink 바탕 + 흰(paper) 글자이고, 비활성도 글자는 흰색 그대로', () {
    final style = buildAppTheme().filledButtonTheme.style!;
    expect(style.backgroundColor!.resolve({}), AppColors.ink);
    expect(style.foregroundColor!.resolve({}), AppColors.paper);
    expect(style.foregroundColor!.resolve({WidgetState.disabled}), AppColors.paper);
  });

  test('비활성 주요 버튼(ink 반투명 바탕 + 흰 글자)은 paper·canvas 위에서 3:1 이상', () {
    final disabledBg = buildAppTheme().filledButtonTheme.style!.backgroundColor!.resolve({WidgetState.disabled})!;
    for (final under in [AppColors.paper, AppColors.canvas]) {
      final blended = Color.alphaBlend(disabledBg, under); // 바탕 위에 반투명 남색을 얹은 실제 색
      final ratio = contrast(AppColors.paper, blended);
      // ignore: avoid_print
      print('비활성 버튼 흰 글자 / ${under == AppColors.paper ? 'paper' : 'canvas'} 위 = ${ratio.toStringAsFixed(2)}');
      expect(ratio, greaterThanOrEqualTo(3.0));
    }
  });
}
