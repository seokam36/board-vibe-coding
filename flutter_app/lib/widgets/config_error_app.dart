import 'package:material_ui/material_ui.dart';

// 앱 시작 때 설정(서버 주소)이 잘못됐을 때만 보이는 최소 안내 화면.
// 라우터·서버 통로 없이 글자 하나만 띄운다.
class ConfigErrorApp extends StatelessWidget {
  const ConfigErrorApp({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(message, textAlign: TextAlign.center),
            ),
          ),
        ),
      ),
    );
  }
}
