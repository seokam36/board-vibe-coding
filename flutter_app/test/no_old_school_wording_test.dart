import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

// 인증 방식이 학교 메일 → 표준입학허가서(유학생 인증)로 바뀌었다(S20-g5).
// 화면 코드에 옛 문구("학교 인증", "학교인증", "학교 미인증")가 다시 들어오면 실패해서 알려 준다.
// 재발 방지용 검사이고, 코드 주석 속 옛 말은 화면에 안 보이니 검사하지 않는다.
void main() {
  test('lib 화면 코드(주석 제외)에 옛 "학교 인증" 문구가 없다', () {
    final oldWording = RegExp(r'학교\s*(미)?인증');
    // 줄 끝 주석의 시작 "//". 단 "https://"처럼 ":" 바로 뒤의 "//"는 문자열 속 주소라 주석으로 보지 않는다
    final commentStart = RegExp(r'(?<!:)//');
    final found = <String>[];
    var checkedFiles = 0;
    for (final f in Directory('lib').listSync(recursive: true)) {
      if (f is! File || !f.path.endsWith('.dart')) continue;
      checkedFiles++;
      final lines = f.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        final m = commentStart.firstMatch(lines[i]);
        final code = m == null ? lines[i] : lines[i].substring(0, m.start);
        if (oldWording.hasMatch(code)) found.add('${f.path}:${i + 1}');
      }
    }
    // 실행 위치가 달라 파일을 하나도 못 읽으면 검사 없이 통과해 버리므로 막는다
    expect(checkedFiles, greaterThan(0), reason: 'flutter_app 폴더에서 실행해야 lib을 찾는다');
    expect(found, isEmpty);
  });
}
