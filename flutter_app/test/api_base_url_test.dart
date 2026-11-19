import 'package:board_web/api/api_base_url.dart';
import 'package:board_web/market/market_format.dart';
import 'package:flutter_test/flutter_test.dart';

// 서버 주소를 한 곳(apiBaseUrl)에서 정하는지 검사한다.
// 주의: 이 파일은 --dart-define 없이 돌린다(기본값 '/api'를 검사하므로).
void main() {
  test('dart-define이_없으면_apiBaseUrl은_/api이다', () {
    expect(apiBaseUrl, '/api');
  });

  test('사진_주소는_기본으로_/api를_앞에_붙인다', () {
    expect(marketPhotoUrl(3, 7), '/api/market-items/3/photos/7');
  });

  test('썸네일이면_끝에_size_thumb가_붙는다', () {
    expect(marketPhotoUrl(3, 7, thumb: true), '/api/market-items/3/photos/7?size=thumb');
  });

  test('base를_넘기면_그_주소로_사진_주소를_만든다', () {
    const base = 'https://x.test/api';
    expect(marketPhotoUrl(3, 7, base: base), 'https://x.test/api/market-items/3/photos/7');
    expect(marketPhotoUrl(3, 7, thumb: true, base: base),
        'https://x.test/api/market-items/3/photos/7?size=thumb');
  });

  // ApiClient 기본값(baseUrl = apiBaseUrl)은 여기서 검사하지 않는다:
  // 일반 flutter test(VM)에서는 dio가 '/api' 같은 상대 주소를 거부한다(웹 전용).
  // 대신 e2e(진짜 브라우저로 빌드된 웹)가 /api로 요청이 가는 걸 확인한다.
}
