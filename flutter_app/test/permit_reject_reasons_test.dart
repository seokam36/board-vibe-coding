import 'package:flutter_test/flutter_test.dart';
import 'package:board_web/permit/permit_reject_reasons.dart';

void main() {
  group('반려 사유 공용 상수', () {
    test('관리자 반려 코드는 정확히 6개이고 순서가 그대로이며 EXPIRED가 없다', () {
      expect(adminRejectReasonCodes, [
        'BLURRY',
        'NOT_PERMIT',
        'NOT_MASKED',
        'UNSUPPORTED_SCHOOL',
        'DUPLICATE',
        'OTHER',
      ]);
      expect(adminRejectReasonCodes.contains('EXPIRED'), isFalse);
    });

    test('관리자 반려 코드는 모두 문구가 있다', () {
      for (final code in adminRejectReasonCodes) {
        expect(permitRejectReasons[code], isNotNull, reason: code);
        expect(permitRejectReasons[code]!.isNotEmpty, isTrue, reason: code);
      }
    });

    test('회원용 문구는 7개이고 EXPIRED를 포함한다', () {
      expect(permitRejectReasons.length, 7);
      expect(permitRejectReasons.containsKey('EXPIRED'), isTrue);
      expect(permitRejectReasons['EXPIRED'], '14일 안에 확인하지 못했어요. 다시 신청해 주세요');
    });
  });
}
