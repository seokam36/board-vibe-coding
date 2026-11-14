// 관리자 화면이 쓰는 서버 응답 모양 (React의 types.ts Report·AdminConversationView, docs/api.md 5-14~5-15).
// 서버 JSON(Map)을 Dart 객체로 바꾼다. 탈퇴한 사람은 응답에서 null이다.

// 사람 한 명 (id·닉네임만)
class AdminUser {
  const AdminUser({required this.id, required this.nickname});

  final int id;
  final String nickname;

  // null이면 null 그대로 (탈퇴한 회원)
  static AdminUser? fromJsonOrNull(Object? json) {
    if (json == null) return null;
    final map = json as Map<String, dynamic>;
    return AdminUser(id: map['id'] as int, nickname: map['nickname'] as String);
  }
}

// 신고 대상: 무엇(type)의 신고인지와 이동·삭제에 쓰는 번호들
class AdminReportTarget {
  const AdminReportTarget({
    required this.type,
    required this.id,
    required this.postId,
    required this.preview,
    required this.author,
  });

  final String type; // POST | COMMENT | MARKET_ITEM | CONVERSATION
  final int id; // 글·댓글·중고 글 id 또는 대화방 id (대화 보기 주소에는 이게 아니라 신고 id를 쓴다)
  final int? postId; // 글·댓글이면 이동할 글 id, 중고 글·대화면 null
  final String preview; // 내용 미리보기 (대화는 글 제목)
  final AdminUser? author; // 대화 신고에서는 신고자가 아닌 쪽 참가자

  factory AdminReportTarget.fromJson(Map<String, dynamic> json) => AdminReportTarget(
    type: json['type'] as String,
    id: json['id'] as int,
    postId: json['postId'] as int?,
    preview: json['preview'] as String,
    author: AdminUser.fromJsonOrNull(json['author']),
  );
}

// 신고 사유 (code는 서버용 값, name은 화면에 보여 줄 한글)
class AdminReportReason {
  const AdminReportReason({required this.code, required this.name});

  final String code;
  final String name;

  factory AdminReportReason.fromJson(Map<String, dynamic> json) =>
      AdminReportReason(code: json['code'] as String, name: json['name'] as String);
}

// 신고 한 건 (GET /admin/reports)
class AdminReport {
  const AdminReport({
    required this.id,
    required this.target,
    required this.reporter,
    required this.reason,
    required this.detail,
    required this.status,
    required this.createdAt,
  });

  final int id;
  final AdminReportTarget target;
  final AdminUser? reporter; // 탈퇴했으면 null
  final AdminReportReason reason;
  final String? detail;
  final String status; // PENDING | DISMISSED
  final String createdAt; // ISO-8601 UTC 문자열 (formatDate로 표시)

  factory AdminReport.fromJson(Map<String, dynamic> json) => AdminReport(
    id: json['id'] as int,
    target: AdminReportTarget.fromJson(json['target'] as Map<String, dynamic>),
    reporter: AdminUser.fromJsonOrNull(json['reporter']),
    reason: AdminReportReason.fromJson(json['reason'] as Map<String, dynamic>),
    detail: json['detail'] as String?,
    status: json['status'] as String,
    createdAt: json['createdAt'] as String,
  );
}

// 관리자가 보는 신고된 대화 속 쪽지 한 통
class AdminConversationMessage {
  const AdminConversationMessage({
    required this.id,
    required this.sender,
    required this.content,
    required this.createdAt,
  });

  final int id;
  final AdminUser? sender; // 보낸 사람이 탈퇴했으면 null
  final String content;
  final String createdAt;

  factory AdminConversationMessage.fromJson(Map<String, dynamic> json) => AdminConversationMessage(
    id: json['id'] as int,
    sender: AdminUser.fromJsonOrNull(json['sender']),
    content: json['content'] as String,
    createdAt: json['createdAt'] as String,
  );
}

// 신고된 대화 (GET /admin/reports/{신고 id}/conversation)
class AdminConversationView {
  const AdminConversationView({
    required this.reportId,
    required this.conversationId,
    required this.itemTitle,
    required this.buyer,
    required this.seller,
    required this.messages,
    required this.hasMore,
  });

  final int reportId;
  final int conversationId;
  final String itemTitle; // 방을 만들 때 복사해 둔 글 제목
  final AdminUser? buyer;
  final AdminUser? seller;
  final List<AdminConversationMessage> messages; // 오래된 것부터, 최근 200통
  final bool hasMore; // 200통보다 이전 쪽지가 더 있으면 true

  factory AdminConversationView.fromJson(Map<String, dynamic> json) => AdminConversationView(
    reportId: json['reportId'] as int,
    conversationId: json['conversationId'] as int,
    itemTitle: json['itemTitle'] as String,
    buyer: AdminUser.fromJsonOrNull(json['buyer']),
    seller: AdminUser.fromJsonOrNull(json['seller']),
    messages: [
      for (final m in json['messages'] as List<dynamic>) AdminConversationMessage.fromJson(m as Map<String, dynamic>),
    ],
    hasMore: json['hasMore'] as bool,
  );
}
