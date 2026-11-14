import 'package:material_ui/material_ui.dart';

// 아직 내용이 없는 화면. 화면 이름(과 id)만 가운데에 보여 준다. 나중에 화면마다 진짜 위젯으로 바꾼다.
class PlaceholderPage extends StatelessWidget {
  const PlaceholderPage({super.key, required this.name, this.id});

  final String name;
  final String? id;

  @override
  Widget build(BuildContext context) {
    // id가 있으면 "글 상세 (id: 7)" 형식 글자 하나로 보여 준다.
    final label = id == null ? name : '$name (id: $id)';
    return Scaffold(
      appBar: AppBar(title: const Text('게시판')),
      body: Center(child: Text(label)),
    );
  }
}
