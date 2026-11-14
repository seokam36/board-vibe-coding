import 'dart:math' as math;

import 'package:flutter/services.dart';

// 입력 글자를 UTF-16 길이(text.length)로 제한하는 입력 규칙.
// 왜: React의 <input maxLength>와 서버의 글자 수 검사가 모두 UTF-16 단위(이모지 하나 = 2)로 세는데,
// Flutter 기본 LengthLimitingTextInputFormatter는 "눈에 보이는 글자(자소)" 단위로 세서 이모지가 섞이면 서로 어긋난다.
// 한도 경계에서 이모지(두 칸짜리)가 반쪽으로 잘리지 않게, 반쪽이 남을 것 같으면 그 글자째 뺀다.
class Utf16LengthFormatter extends TextInputFormatter {
  const Utf16LengthFormatter(this.maxLength);

  final int maxLength;

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = newValue.text;
    if (text.length <= maxLength) return newValue;

    var end = maxLength;
    // 자르는 자리 바로 앞이 두 칸짜리 글자의 앞 반쪽이면 한 칸 더 물러난다
    if (end > 0 && _isHighSurrogate(text.codeUnitAt(end - 1))) end--;

    final selection = newValue.selection;
    return TextEditingValue(
      text: text.substring(0, end),
      // 커서·선택 범위가 잘린 글자 밖을 가리키지 않게 줄인다
      selection: selection.isValid
          ? TextSelection(
              baseOffset: math.min(selection.baseOffset, end),
              extentOffset: math.min(selection.extentOffset, end),
              affinity: selection.affinity,
            )
          : selection,
      composing: TextRange.empty, // 조합 중이던 글자(한글 등)도 잘렸을 수 있어 조합 표시는 끈다
    );
  }

  static bool _isHighSurrogate(int unit) => unit >= 0xD800 && unit <= 0xDBFF;
}
