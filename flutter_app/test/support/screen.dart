// 테스트 화면을 세로로 길게 만든다. 글쓰기·수정 폼은 기본 테스트 화면(800x600)보다 길어서
// 저장 버튼이 화면 밖에 있으면 탭이 닿지 않기 때문이다. 앱을 띄우기 전에 부른다.
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void useTallScreen(WidgetTester tester) {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = const Size(800, 1800);
  addTearDown(tester.view.reset);
}
