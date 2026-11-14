// 서버가 돌려주는 중고·나눔 글의 모양 (React의 types.ts MarketListItem). 서버 JSON(Map)을 Dart 객체로 바꾼다.
import '../posts/models.dart' show School;

// {code, name} 한 쌍 (판매/나눔, 상태). code는 요청·분기용 값, name은 서버가 준 화면 글자
class MarketCode {
  const MarketCode({required this.code, required this.name});

  final String code;
  final String name;

  factory MarketCode.fromJson(Map<String, dynamic> json) =>
      MarketCode(code: json['code'] as String, name: json['name'] as String);
}

// 사진 한 장의 정보 (목록에서는 대표 사진 id만 쓴다. 나머지 크기 값은 상세 화면용)
class MarketPhoto {
  const MarketPhoto({required this.id});

  final int id;

  factory MarketPhoto.fromJson(Map<String, dynamic> json) => MarketPhoto(id: json['id'] as int);
}

// 글 목록의 한 건 (GET /market-items). 목록에는 작성자 표시가 없어 seller는 읽지 않는다.
// 사진 목록(photos) 대신 대표 사진 한 장(coverPhoto)이 있고, 사진이 없으면 null
class MarketListItem {
  const MarketListItem({
    required this.id,
    required this.title,
    required this.tradeType,
    required this.price,
    required this.status,
    required this.school,
    required this.createdAt,
    required this.coverPhoto,
  });

  final int id;
  final String title;
  final MarketCode tradeType; // SALE 판매 / FREE 나눔
  final int price; // 원 단위. 나눔은 0
  final MarketCode status; // ON_SALE / RESERVED / DONE
  final School school;
  final String createdAt;
  final MarketPhoto? coverPhoto;

  factory MarketListItem.fromJson(Map<String, dynamic> json) {
    final cover = json['coverPhoto'];
    return MarketListItem(
      id: json['id'] as int,
      title: json['title'] as String,
      tradeType: MarketCode.fromJson(json['tradeType'] as Map<String, dynamic>),
      price: json['price'] as int,
      status: MarketCode.fromJson(json['status'] as Map<String, dynamic>),
      school: School.fromJson(json['school'] as Map<String, dynamic>),
      createdAt: json['createdAt'] as String,
      coverPhoto: cover == null ? null : MarketPhoto.fromJson(cover as Map<String, dynamic>),
    );
  }
}
