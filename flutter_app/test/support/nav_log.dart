// 테스트용 "브라우저 기록 기록장". 앱(라우터)이 주소를 바꿀 때 엔진(=브라우저)에 알리는 메시지를 가로채 적어 둔다.
// replace가 true면 "기록을 새로 쌓지 않고 지금 칸을 교체"(React의 replace), false면 "새 칸을 쌓음"(React의 push)이다.
// 진짜 브라우저 없이도 "뒤로 가기 기록이 교체됐는지"를 확인할 수 있다.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class NavEntry {
  NavEntry(this.path, this.replace);
  final String path;
  final bool replace;
}

class NavLog {
  final List<NavEntry> entries = [];

  // 앱을 띄우기 "전에" 부른다 (시작 주소 보고부터 받으려고)
  void install(WidgetTester tester) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, (call) async {
      if (call.method == 'routeInformationUpdated') {
        final args = call.arguments as Map<dynamic, dynamic>;
        final uri = Uri.parse((args['uri'] ?? args['location']) as String);
        entries.add(NavEntry(uri.path, args['replace'] == true));
      }
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.navigation, null));
  }

  // 마지막으로 알린 주소의 기록
  NavEntry get last => entries.last;
}
