// 챗봇 응답의 모양 (docs/api.md 43번 POST /chat). 서버 JSON(Map)을 Dart 객체로 바꾼다.
import '../market/models.dart' show MarketCode;

// 답 한 번: AI의 글자 답 + 서버가 AI에게 실제로 준 글 목록(출처)
class ChatAnswer {
  const ChatAnswer({required this.answer, required this.sources});

  final String answer; // 순수 글자로만 보여 준다 (링크·마크다운으로 바꾸지 않는다)
  final List<ChatSource> sources; // 응답 순서 그대로. 맞는 글이 없으면 빈 목록

  factory ChatAnswer.fromJson(Map<String, dynamic> json) => ChatAnswer(
    answer: json['answer'] as String,
    sources: [for (final s in json['sources'] as List<dynamic>) ChatSource.fromJson(s as Map<String, dynamic>)],
  );
}

// 출처 한 줄. type은 글 종류(지금은 MARKET_ITEM뿐, 모르는 종류는 화면이 그리지 않는다)
class ChatSource {
  const ChatSource({
    required this.type,
    required this.id,
    required this.title,
    required this.tradeType,
    required this.price,
    required this.status,
  });

  final String type;
  final int id;
  final String title;
  final MarketCode tradeType; // SALE 판매 / FREE 나눔
  final int price; // 원 단위. 나눔은 0
  final MarketCode status; // ON_SALE / RESERVED (모르는 코드도 올 수 있다)

  factory ChatSource.fromJson(Map<String, dynamic> json) => ChatSource(
    type: json['type'] as String,
    id: json['id'] as int,
    title: json['title'] as String,
    tradeType: MarketCode.fromJson(json['tradeType'] as Map<String, dynamic>),
    price: json['price'] as int,
    status: MarketCode.fromJson(json['status'] as Map<String, dynamic>),
  );
}
