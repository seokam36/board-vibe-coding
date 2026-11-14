// 테스트용 글 JSON 한 건 (글 상세·라우터 테스트가 같이 쓴다). 값을 바꿔 가며 쓸 수 있다.
// author·school에 null을 넘기면 "작성자 없음"·"학교 없는 글"이 된다.
Map<String, dynamic> postJson({
  int id = 7,
  String title = '제목입니다',
  String content = '본문입니다',
  Map<String, dynamic>? author = const {'id': 1, 'nickname': '홍길동'},
  Map<String, dynamic>? school = const {'id': 1, 'name': '서울대'},
  Map<String, dynamic> category = const {'code': 'CHAT', 'name': '잡담'},
  String createdAt = '2020-11-08T06:19:00Z',
  int commentCount = 0,
  int likeCount = 3,
}) => {
  'id': id,
  'title': title,
  'content': content,
  'author': author,
  'school': school,
  'category': category,
  'createdAt': createdAt,
  'commentCount': commentCount,
  'likeCount': likeCount,
};
