import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/semantics.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'package:material_ui/material_ui.dart';

import 'app.dart';

void main() {
  // 엔진이 준비된 뒤에야 SemanticsBinding을 쓸 수 있어서 먼저 초기화한다.
  WidgetsFlutterBinding.ensureInitialized();
  // 주소를 /#/posts/1 이 아니라 /posts/1 처럼 깔끔하게 쓴다.
  usePathUrlStrategy();
  if (kIsWeb) {
    // Flutter 웹은 화면을 통째로 그림(canvas)으로 그려서, 평소엔 버튼·입력 칸이
    // 브라우저(스크린리더, Playwright)에게 보이지 않는다. 접근성 트리를 미리 켜 둔다.
    SemanticsBinding.instance.ensureSemantics();
  }
  runApp(const BoardApp());
}
