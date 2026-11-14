// S16-h 바깥 링크 열기 테스트: 새 창을 열 때 쓰는 보안 옵션 값을 고정한다 (실수로 바뀌면 여기서 걸린다).
import 'package:board_web/common/external_link.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('새 창 옵션은 noopener,noreferrer 그대로', () {
    expect(externalLinkWindowFeatures, 'noopener,noreferrer');
  });
}
