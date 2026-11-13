import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/semantics.dart';
import 'package:material_ui/material_ui.dart';

// S16-a 실험 앱: 한글 입력, 접근성(semantics), 파일 크기를 재 보기 위한 빈 화면.
void main() {
  // 엔진이 준비된 뒤에야 SemanticsBinding을 쓸 수 있어서 먼저 초기화한다.
  WidgetsFlutterBinding.ensureInitialized();
  if (kIsWeb) {
    // Flutter 웹은 화면을 통째로 그림(canvas)으로 그려서, 평소엔 버튼·입력 칸이
    // 브라우저(스크린리더, Playwright)에게 보이지 않는다.
    // 접근성 트리를 미리 켜 두면 getByRole('button') 같은 검색이 되는지 실험할 수 있다.
    SemanticsBinding.instance.ensureSemantics();
  }
  runApp(const ExperimentApp());
}

class ExperimentApp extends StatelessWidget {
  const ExperimentApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '게시판 Flutter 실험',
      // Material 3 테마 (씨앗 색 하나로 전체 색을 만든다)
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
      ),
      home: const ExperimentPage(),
    );
  }
}

class ExperimentPage extends StatefulWidget {
  const ExperimentPage({super.key});

  @override
  State<ExperimentPage> createState() => _ExperimentPageState();
}

class _ExperimentPageState extends State<ExperimentPage> {
  // 입력 칸의 글자를 읽기 위한 컨트롤러
  final _titleController = TextEditingController();
  final _bodyController = TextEditingController();

  // "확인"을 누른 뒤 화면 아래에 보여 줄 글자 (처음엔 비어 있음)
  String _shownTitle = '';
  String _shownBody = '';
  bool _confirmed = false;

  // 스크롤·Ctrl+F 검사용 한글 문단 20개 (모두 다른 문장)
  static const List<String> _paragraphs = [
    '가나다라마바사 아자차카타파하, 한글의 자음과 모음이 화면에 또렷하게 보이는지 확인합니다.',
    '오늘은 맑고 바람이 선선해서 산책하기 좋은 날씨입니다.',
    '게시판에 글을 쓰고 댓글을 달면 다른 사람이 읽을 수 있습니다.',
    '비자 연장 서류를 준비할 때는 여권과 사진을 함께 챙겨야 합니다.',
    '커피 한 잔과 함께 읽는 짧은 글은 하루의 작은 쉼표가 됩니다.',
    '서울에서 부산까지 기차로 두 시간 반 정도 걸립니다.',
    '프로그래밍을 배우는 가장 좋은 방법은 작은 것부터 직접 만들어 보는 것입니다.',
    '겨울이 지나면 봄이 오고, 봄이 지나면 여름이 옵니다.',
    '도서관 창가 자리에서 책을 읽으면 시간이 금방 지나갑니다.',
    '쪽지를 보내면 상대방의 쪽지함에 안 읽은 수가 하나 늘어납니다.',
    '된소리와 거센소리, 예를 들어 뿌리, 꽃, 똑똑, 쌀 같은 낱말도 잘 나와야 합니다.',
    '받침이 겹치는 닭, 삶, 읊다, 흙 같은 글자도 깨지지 않는지 봅니다.',
    '점심 메뉴로 김치찌개와 비빔밥 중에서 무엇을 고를지 고민했습니다.',
    '지하철 안에서 이어폰을 끼고 좋아하는 노래를 들었습니다.',
    '새로운 기능을 만들 때는 먼저 작게 시험해 보고 계속할지 판단합니다.',
    '한강 둔치에서 자전거를 타는 사람들이 노을 속으로 지나갑니다.',
    '긴 문장이 화면 폭보다 길어지면 자동으로 다음 줄로 넘어가야 합니다. 줄 바꿈이 자연스러운지 확인하세요.',
    '어제 배운 내용을 오늘 다시 복습하면 기억에 훨씬 오래 남습니다.',
    '작은 습관이 모여 큰 변화를 만든다는 말을 자주 떠올립니다.',
    '이 문단이 마지막입니다. 여기까지 스크롤이 부드럽게 되는지 확인해 주세요.',
  ];

  @override
  void dispose() {
    // 컨트롤러는 쓰고 나면 직접 정리해 주어야 메모리가 새지 않는다.
    _titleController.dispose();
    _bodyController.dispose();
    super.dispose();
  }

  // "확인" 버튼: 두 칸의 내용을 그대로 아래에 보여 준다.
  void _onConfirm() {
    setState(() {
      _shownTitle = _titleController.text;
      _shownBody = _bodyController.text;
      _confirmed = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Flutter 실험 화면')),
      body: SingleChildScrollView(
        // 화면이 넓어도 내용은 최대 720px, 가운데 정렬
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 720),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    '입력·접근성 실험',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 16),
                  // 한 줄 입력 칸
                  TextField(
                    controller: _titleController,
                    decoration: const InputDecoration(
                      labelText: '제목',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  // 여러 줄 입력 칸
                  TextField(
                    controller: _bodyController,
                    minLines: 4,
                    maxLines: 8,
                    decoration: const InputDecoration(
                      labelText: '본문',
                      border: OutlineInputBorder(),
                      alignLabelWithHint: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: FilledButton(
                      onPressed: _onConfirm,
                      child: const Text('확인'),
                    ),
                  ),
                  // 확인을 누른 뒤에만 입력한 내용을 그대로 보여 준다 (한글 깨짐 눈으로 확인)
                  if (_confirmed) ...[
                    const SizedBox(height: 16),
                    Text('입력한 제목: $_shownTitle'),
                    const SizedBox(height: 8),
                    Text('입력한 본문: $_shownBody'),
                  ],
                  const Divider(height: 32),
                  // 한글 문단 20개 (spread로 펼쳐서 Column 자식에 넣는다)
                  for (final paragraph in _paragraphs) ...[
                    Text(paragraph),
                    const SizedBox(height: 12),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
