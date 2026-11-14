// 테스트용 중고·나눔 JSON (목록 테스트가 같이 쓴다). 값을 바꿔 가며 쓸 수 있다.
import 'fake_adapter.dart';

const marketSeoul = {'id': 1, 'name': '서울대'};
const marketBusan = {'id': 2, 'name': '부산대'};
const marketSchools = [marketSeoul, marketBusan];

// 목록 한 건. coverPhoto에 null을 넘기면 "사진 없음". 작성자(seller)는 서버가 주지만 목록 화면은 쓰지 않는다
Map<String, dynamic> marketItemJson({
  int id = 7,
  String title = '전공 서적 팝니다',
  String tradeCode = 'SALE',
  String tradeName = '판매',
  int price = 15000,
  String statusCode = 'ON_SALE',
  String statusName = '판매중',
  Map<String, dynamic> school = marketSeoul,
  String createdAt = '2020-11-08T06:19:00Z',
  Map<String, dynamic>? coverPhoto,
}) => {
  'id': id,
  'title': title,
  'content': '거의 새 책이에요.',
  'tradeType': {'code': tradeCode, 'name': tradeName},
  'price': price,
  'status': {'code': statusCode, 'name': statusName},
  'school': school,
  'seller': {'id': 1, 'nickname': '길동이'},
  'createdAt': createdAt,
  'coverPhoto': coverPhoto,
};

// 대표 사진 JSON
Map<String, dynamic> coverJson({int id = 9}) => {
  'id': id,
  'width': 1365,
  'height': 2048,
  'thumbWidth': 213,
  'thumbHeight': 320,
  'contentType': 'image/jpeg',
};

// 상세(GET /market-items/{id}, 상태 변경 응답) 한 건. 사진 id 목록을 넘기면 그 순서로 photos가 채워진다. 판매자는 기본 id 1
Map<String, dynamic> marketDetailJson({
  int id = 7,
  String title = '전공 서적 팝니다',
  String content = '거의 새 책이에요.',
  String tradeCode = 'SALE',
  String tradeName = '판매',
  int price = 15000,
  String statusCode = 'ON_SALE',
  String statusName = '판매중',
  Map<String, dynamic> school = marketSeoul,
  int sellerId = 1,
  String sellerNickname = '길동이',
  String createdAt = '2020-11-08T06:19:00Z',
  List<int> photoIds = const [],
}) => {
  'id': id,
  'title': title,
  'content': content,
  'tradeType': {'code': tradeCode, 'name': tradeName},
  'price': price,
  'status': {'code': statusCode, 'name': statusName},
  'school': school,
  'seller': {'id': sellerId, 'nickname': sellerNickname},
  'createdAt': createdAt,
  'photos': [for (final p in photoIds) coverJson(id: p)],
};

// 학교 목록은 성공, 글 목록 응답은 호출하는 쪽이 정한다
FakeAdapter marketAdapter(List<FakeReply> items) {
  final a = FakeAdapter()..on('GET', '/schools', FakeReply(200, marketSchools));
  for (final r in items) {
    a.on('GET', '/market-items', r);
  }
  return a;
}
