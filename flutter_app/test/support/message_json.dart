// 테스트용 쪽지 JSON (쪽지 화면 테스트가 같이 쓴다). 값을 바꿔 가며 쓸 수 있다.

// 쪽지 한 통
Map<String, dynamic> messageJson({
  int id = 1,
  String content = '안녕하세요, 아직 있나요?',
  String createdAt = '2026-01-02T03:00:00Z',
  bool mine = true,
}) => {'id': id, 'content': content, 'createdAt': createdAt, 'mine': mine};

// 대화에 붙은 글 (기본: 살아 있는 글 7)
Map<String, dynamic> itemRefJson({int? id = 7, String title = '가짜 자전거', bool deleted = false}) => {
  'id': id,
  'title': title,
  'deleted': deleted,
};

const _defaultPartner = {'id': 7, 'nickname': '판매자닉'};

// 대화 보기 응답. partner에 null을 넘기면 탈퇴한 상대. 기본 쪽지는 내 것 → 상대 것 두 통
Map<String, dynamic> conversationJson({
  int id = 3,
  Map<String, dynamic>? item,
  Object? partner = _defaultPartner,
  bool hasMore = false,
  List<Map<String, dynamic>>? messages,
}) => {
  'id': id,
  'item': item ?? itemRefJson(),
  'partner': partner,
  'hasMore': hasMore,
  'messages':
      messages ??
      [
        messageJson(id: 1, content: '안녕하세요, 아직 있나요?', createdAt: '2026-01-02T03:00:00Z', mine: true),
        messageJson(id: 2, content: '아직 있어요', createdAt: '2026-01-02T03:05:00Z', mine: false),
      ],
};

// 쪽지함 한 줄
Map<String, dynamic> summaryJson({
  int id = 3,
  Map<String, dynamic>? item,
  Object? partner = _defaultPartner,
  String content = '아직 있어요',
  String createdAt = '2026-01-02T03:05:00Z',
  bool mine = false,
  int unreadCount = 1,
}) => {
  'id': id,
  'item': item ?? itemRefJson(),
  'partner': partner,
  'lastMessage': {'content': content, 'createdAt': createdAt, 'mine': mine},
  'unreadCount': unreadCount,
};
