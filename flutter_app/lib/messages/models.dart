// 서버가 돌려주는 쪽지 모양 (React의 types.ts 쪽지 부분, docs/api.md 36~41). 서버 JSON(Map)을 Dart 객체로 바꾼다.

// 상대가 탈퇴해 partner가 비었을 때 화면에 쓰는 이름
const withdrawnPartner = '탈퇴한 회원';

// 대화에 붙은 물건 글 정보. 글이 지워졌으면 id가 null, deleted가 true (title은 방을 만들 때 복사해 둔 제목)
class ConversationItemRef {
  const ConversationItemRef({required this.id, required this.title, required this.deleted});

  final int? id;
  final String title;
  final bool deleted;

  factory ConversationItemRef.fromJson(Map<String, dynamic> json) => ConversationItemRef(
    id: json['id'] as int?,
    title: json['title'] as String,
    deleted: json['deleted'] as bool,
  );
}

// 대화 상대. 상대가 탈퇴했으면 응답에서 partner 자체가 null
class ConversationPartner {
  const ConversationPartner({required this.id, required this.nickname});

  final int id;
  final String nickname;

  factory ConversationPartner.fromJson(Map<String, dynamic> json) =>
      ConversationPartner(id: json['id'] as int, nickname: json['nickname'] as String);
}

// 쪽지 한 통. mine = 내가 보낸 쪽지인가 (보낸 사람이 탈퇴해 비었으면 false)
class ConversationMessage {
  const ConversationMessage({required this.id, required this.content, required this.createdAt, required this.mine});

  final int id;
  final String content;
  final String createdAt; // ISO-8601 UTC 문자열 (formatDate로 표시)
  final bool mine;

  factory ConversationMessage.fromJson(Map<String, dynamic> json) => ConversationMessage(
    id: json['id'] as int,
    content: json['content'] as String,
    createdAt: json['createdAt'] as String,
    mine: json['mine'] as bool,
  );
}

// 쪽지함 줄에 보이는 마지막 쪽지 (id는 응답에 없다)
class LastMessage {
  const LastMessage({required this.content, required this.createdAt, required this.mine});

  final String content;
  final String createdAt;
  final bool mine;

  factory LastMessage.fromJson(Map<String, dynamic> json) => LastMessage(
    content: json['content'] as String,
    createdAt: json['createdAt'] as String,
    mine: json['mine'] as bool,
  );
}

// 쪽지함 한 줄 (GET /conversations). 최근 쪽지가 있는 방부터 온다
class ConversationSummary {
  const ConversationSummary({
    required this.id,
    required this.item,
    required this.partner,
    required this.lastMessage,
    required this.unreadCount,
  });

  final int id;
  final ConversationItemRef item;
  final ConversationPartner? partner; // null이면 상대가 탈퇴
  final LastMessage lastMessage;
  final int unreadCount; // 안 읽은 쪽지 수 (배지는 S16-l2에서 그린다)

  factory ConversationSummary.fromJson(Map<String, dynamic> json) {
    final partner = json['partner'];
    return ConversationSummary(
      id: json['id'] as int,
      item: ConversationItemRef.fromJson(json['item'] as Map<String, dynamic>),
      partner: partner == null ? null : ConversationPartner.fromJson(partner as Map<String, dynamic>),
      lastMessage: LastMessage.fromJson(json['lastMessage'] as Map<String, dynamic>),
      unreadCount: json['unreadCount'] as int? ?? 0,
    );
  }
}

// 대화 보기 (GET /conversations/{id}). messages는 오래된 것부터, 최근 200통
class ConversationDetail {
  const ConversationDetail({
    required this.id,
    required this.item,
    required this.partner,
    required this.hasMore,
    required this.messages,
  });

  final int id;
  final ConversationItemRef item;
  final ConversationPartner? partner; // null이면 상대가 탈퇴
  final bool hasMore; // 200통보다 이전 쪽지가 더 있으면 true (안내는 S16-l2에서 보여 준다)
  final List<ConversationMessage> messages;

  factory ConversationDetail.fromJson(Map<String, dynamic> json) {
    final partner = json['partner'];
    return ConversationDetail(
      id: json['id'] as int,
      item: ConversationItemRef.fromJson(json['item'] as Map<String, dynamic>),
      partner: partner == null ? null : ConversationPartner.fromJson(partner as Map<String, dynamic>),
      hasMore: json['hasMore'] as bool? ?? false,
      messages: [
        for (final m in (json['messages'] as List<dynamic>? ?? const []))
          ConversationMessage.fromJson(m as Map<String, dynamic>),
      ],
    );
  }

  // 답장을 끝에 붙인 새 대화 (원본은 바꾸지 않는다)
  ConversationDetail withMessage(ConversationMessage m) =>
      ConversationDetail(id: id, item: item, partner: partner, hasMore: hasMore, messages: [...messages, m]);
}
