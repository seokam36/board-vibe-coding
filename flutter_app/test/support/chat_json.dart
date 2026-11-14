// 테스트용 챗봇 응답 JSON (docs/api.md 43번)

// 출처 한 줄. 값을 바꿔 가며 쓴다 (나눔은 tradeCode FREE + price 0)
Map<String, dynamic> chatSourceJson({
  String type = 'MARKET_ITEM',
  int id = 12,
  String title = '자전거 팝니다',
  String tradeCode = 'SALE',
  String tradeName = '판매',
  int price = 30000,
  String statusCode = 'ON_SALE',
  String statusName = '판매중',
}) => {
  'type': type,
  'id': id,
  'title': title,
  'tradeType': {'code': tradeCode, 'name': tradeName},
  'price': price,
  'status': {'code': statusCode, 'name': statusName},
};

// 응답 한 번: 답 + 출처 목록 (기본: 출처 없음)
Map<String, dynamic> chatJson({
  String answer = '#12번 글에서 자전거를 3만 원에 팔고 있어요.',
  List<Map<String, dynamic>> sources = const [],
}) => {
  'answer': answer,
  'sources': sources,
};
