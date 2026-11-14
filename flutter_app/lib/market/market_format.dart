// 중고·나눔 화면용 글자 만들기 (React의 marketFormat.ts, marketPhotoUrl.ts). intl 없이 직접 계산한다.

// 가격 표기: 나눔(FREE)은 "나눔", 판매는 천 단위 쉼표를 넣은 "5,000원"
String formatPrice(String tradeTypeCode, int price) {
  if (tradeTypeCode == 'FREE') return '나눔';
  // 뒤에서부터 3자리마다 쉼표: 앞에 숫자가 더 남아 있는 자리(?=\d)에서만 넣는다
  final digits = price.toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',');
  return '$digits원';
}

// 사진 파일 주소 (docs/api.md 34번). 같은 출처('/api')라 브라우저가 쿠키 없이 바로 가져온다.
// thumb이면 썸네일(긴 변 ≤ 320), 아니면 본 사진(긴 변 ≤ 2048)
String marketPhotoUrl(int itemId, int photoId, {bool thumb = false}) =>
    '/api/market-items/$itemId/photos/$photoId${thumb ? '?size=thumb' : ''}';
