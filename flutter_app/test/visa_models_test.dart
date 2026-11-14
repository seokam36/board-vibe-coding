// S16-h 비자 후기 모델 테스트: 서버가 content를 null로 줘도 ""로 읽히는지 본다.
import 'package:board_web/visa/models.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/visa_json.dart';

void main() {
  test('content가 null이면 ""로 읽는다 (화면은 "남긴 메모가 없어요")', () {
    final json = reviewJson()..['content'] = null;
    expect(VisaReview.fromJson(json).content, '');
  });
}
