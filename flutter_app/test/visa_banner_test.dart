// S16-i2 비자 띠 로직 테스트: React visaBanner.test.ts와 같은 시나리오 + 해시 값이 React와 같은지.
// (문구, 닫기 키·값, 저장·읽기, 저장소가 막힌 브라우저)
import 'package:board_web/common/local_store.dart';
import 'package:board_web/visa/visa_banner.dart';
import 'package:flutter_test/flutter_test.dart';

// 메모리에 저장하는 가짜 저장소
class _MemStore implements LocalStore {
  final Map<String, String> data = {};

  @override
  String? getItem(String key) => data[key];

  @override
  void setItem(String key, String value) => data[key] = value;
}

// 읽기·쓰기가 모두 예외를 던지는 저장소 (시크릿 모드·차단 브라우저)
class _BlockedStore implements LocalStore {
  @override
  String? getItem(String key) => throw StateError('blocked');

  @override
  void setItem(String key, String value) => throw StateError('blocked');
}

void main() {
  late LocalStore original;

  setUp(() => original = localStore);
  tearDown(() => localStore = original);

  group('shortHash: React(djb2 32비트 → 36진수)와 같은 값', () {
    // 값은 node로 React의 shortHash를 직접 돌려 얻은 것이다
    test('만료일 글자들', () {
      expect(shortHash('2027-03-01'), '18ay6v4');
      expect(shortHash('2026-12-08'), 'myt6qw');
      expect(shortHash('2026-12-09'), 'myt6qx');
      expect(shortHash('2026-11-07'), 'mysibo');
      expect(shortHash('2027-05-30'), '18awu3o');
    });

    test('빈 글자는 시작값(5381)의 36진수, 한글도 같은 값', () {
      expect(shortHash(''), '45h');
      expect(shortHash('가나다'), '2i3xy1');
    });
  });

  group('닫기 키·값', () {
    test('키는 회원당 하나고 만료일이 들어가지 않는다', () {
      expect(dismissKey(7), 'visaBannerClosed:7');
      expect(dismissKey(7), isNot(contains('2027')));
    });

    test('값에도 만료일 평문이 없고 단계로 시작한다', () {
      final value = dismissValue('CHECK', '2027-03-01');
      expect(value, 'CHECK:18ay6v4');
      expect(value, isNot(contains('2027')));
      expect(value, isNot(contains('03-01')));
    });

    test('같은 만료일·단계면 같은 값, 단계나 만료일이 다르면 다른 값', () {
      expect(dismissValue('URGENT', '2027-03-01'), dismissValue('URGENT', '2027-03-01'));
      expect(dismissValue('CHECK', '2027-03-01'), isNot(dismissValue('URGENT', '2027-03-01')));
      expect(dismissValue('CHECK', '2027-03-01'), isNot(dismissValue('CHECK', '2027-03-02')));
    });
  });

  group('bannerMessage', () {
    test('남은 일수가 D-n으로 들어간다', () {
      expect(bannerMessage('PREPARE', 111), '체류 만료 D-111 — 연장·자격 변경 준비를 확인하세요');
      expect(bannerMessage('CHECK', 30), '체류 만료 D-30 — 신청 방법을 확인하세요');
      expect(bannerMessage('URGENT', 0), '체류 만료 D-Day — 체류 상태를 확인하세요');
    });

    test('지난 일정은 일수 없이 중립 문구', () {
      expect(bannerMessage('EXPIRED', -3), '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요');
    });

    test('모르는 단계면 예외 없이 null (띠를 그리지 않게)', () {
      expect(bannerMessage('NEW_STAGE', 10), isNull);
    });
  });

  group('저장 → 읽기', () {
    test('저장한 값과 같으면 닫힘, 값이 바뀌면 다시 열림, 다른 회원은 따로', () {
      localStore = _MemStore();
      final v = dismissValue('CHECK', '2027-03-01');
      expect(isDismissed(1, v), isFalse);
      saveDismissed(1, v);
      expect(isDismissed(1, v), isTrue);
      expect(isDismissed(1, dismissValue('URGENT', '2027-03-01')), isFalse);
      expect(isDismissed(2, v), isFalse);
    });

    test('다시 닫으면 덮어써서 키가 하나만 남는다', () {
      final store = _MemStore();
      localStore = store;
      saveDismissed(1, dismissValue('CHECK', '2027-03-01'));
      saveDismissed(1, dismissValue('URGENT', '2027-03-01'));
      expect(store.data.length, 1);
    });
  });

  group('저장소가 막힌 브라우저', () {
    test('읽기·쓰기가 예외를 던져도 터지지 않고, 읽기는 "안 닫힘"', () {
      localStore = _BlockedStore();
      expect(isDismissed(1, 'CHECK:abc'), isFalse);
      expect(() => saveDismissed(1, 'CHECK:abc'), returnsNormally);
    });
  });
}
