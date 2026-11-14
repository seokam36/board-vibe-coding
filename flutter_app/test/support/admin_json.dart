// 테스트용 관리자 화면 JSON (신고 목록·신고된 대화 테스트가 같이 쓴다). 값을 바꿔 가며 쓸 수 있다.
import 'fake_adapter.dart';

// 관리자 로그인 사용자 (id 9)
Map<String, dynamic> adminUser() => userJson(id: 9, username: 'admin', nickname: '관리자', role: 'ADMIN');

// 신고 한 건. type에 따라 target을 채운다. author·reporter에 null을 넘기면 탈퇴한 회원.
Map<String, dynamic> reportJson({
  int id = 1,
  String type = 'POST',
  int targetId = 5,
  Object? postId = 5,
  String preview = '신고된 글 내용',
  Object? author = const {'id': 3, 'nickname': '글쓴이'},
  Object? reporter = const {'id': 2, 'nickname': '신고자'},
  String reasonName = '스팸',
  String? detail,
  String status = 'PENDING',
  String createdAt = '2026-11-12T00:00:00Z',
}) => {
  'id': id,
  'target': {'type': type, 'id': targetId, 'postId': postId, 'preview': preview, 'author': author},
  'reporter': reporter,
  'reason': {'code': 'SPAM', 'name': reasonName},
  'detail': detail,
  'status': status,
  'createdAt': createdAt,
};

Map<String, dynamic> postReport({int id = 1}) => reportJson(id: id);

Map<String, dynamic> commentReport({int id = 2}) =>
    reportJson(id: id, type: 'COMMENT', targetId: 9, preview: '신고된 댓글 내용');

Map<String, dynamic> marketReport({int id = 3}) => reportJson(
  id: id,
  type: 'MARKET_ITEM',
  targetId: 42,
  postId: null,
  preview: '신고된 중고 글',
  author: {'id': 5, 'nickname': '다른사람'},
);

// 신고 id 7 = 대화방 id 12 (일부러 다른 숫자: 링크는 신고 id를 써야 한다)
Map<String, dynamic> conversationReport({int id = 7}) => reportJson(
  id: id,
  type: 'CONVERSATION',
  targetId: 12,
  postId: null,
  preview: '중고 자전거 팝니다',
  author: {'id': 8, 'nickname': '판매자'},
  detail: '이상한 말을 해요',
);

Map<String, dynamic> adminMessageJson({
  int id = 30,
  Object? sender = const {'id': 2, 'nickname': '신고자'},
  String content = '아직 있나요?',
  String createdAt = '2026-11-13T03:00:00Z',
}) => {'id': id, 'sender': sender, 'content': content, 'createdAt': createdAt};

// 신고된 대화: 기본은 구매자 신고자(2), 판매자 판매자(8), 쪽지 두 통
Map<String, dynamic> adminConversationJson({
  Object? buyer = const {'id': 2, 'nickname': '신고자'},
  Object? seller = const {'id': 8, 'nickname': '판매자'},
  bool hasMore = false,
  List<Map<String, dynamic>>? messages,
}) => {
  'reportId': 7,
  'conversationId': 12,
  'itemTitle': '중고 자전거 팝니다',
  'buyer': buyer,
  'seller': seller,
  'hasMore': hasMore,
  'messages':
      messages ??
      [
        adminMessageJson(id: 30),
        adminMessageJson(id: 31, sender: {'id': 8, 'nickname': '판매자'}, content: '네 있어요', createdAt: '2026-11-13T03:05:00Z'),
      ],
};
