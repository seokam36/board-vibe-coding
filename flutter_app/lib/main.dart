import 'package:flutter/foundation.dart' show kIsWeb, kReleaseMode;
import 'package:flutter/semantics.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'package:material_ui/material_ui.dart';

import 'api/api_base_url.dart';
import 'app.dart';
import 'widgets/config_error_app.dart';

void main() {
  // 엔진이 준비된 뒤에야 SemanticsBinding을 쓸 수 있어서 먼저 초기화한다.
  WidgetsFlutterBinding.ensureInitialized();
  // 앱(비웹)에서 서버 주소 설정이 잘못됐으면 알아보기 힘든 오류로 죽지 않게 안내 화면만 띄우고 멈춘다.
  final problem = apiBaseUrlProblem(apiBaseUrl, isWeb: kIsWeb, isRelease: kReleaseMode);
  if (problem != null) {
    runApp(ConfigErrorApp(message: problem));
    return;
  }
  if (kIsWeb) {
    // 주소를 /#/posts/1 이 아니라 /posts/1 처럼 깔끔하게 쓴다. (웹 전용이라 앱에서는 부르지 않는다)
    usePathUrlStrategy();
    // Flutter 웹은 화면을 통째로 그림(canvas)으로 그려서, 평소엔 버튼·입력 칸이
    // 브라우저(스크린리더, Playwright)에게 보이지 않는다. 접근성 트리를 미리 켜 둔다.
    SemanticsBinding.instance.ensureSemantics();
  }
  runApp(const BoardApp());
}
