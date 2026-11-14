// S16-i1 날짜 계산 테스트 (React의 dday.ts와 같은 값이 나오는지).
import 'package:board_web/visa/dday.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('formatDDay', () {
    test('양수는 D-n, 0은 D-Day, 음수는 D+n', () {
      expect(formatDDay(111), 'D-111');
      expect(formatDDay(1), 'D-1');
      expect(formatDDay(0), 'D-Day');
      expect(formatDDay(-1), 'D+1');
      expect(formatDDay(-3), 'D+3');
    });
  });

  group('daysBetween', () {
    test('to - from 일수 (같은 날 0, 거꾸로면 음수)', () {
      expect(daysBetween('2026-11-10', '2027-03-01'), 111);
      expect(daysBetween('2027-03-01', '2026-11-10'), -111);
      expect(daysBetween('2026-11-10', '2026-11-10'), 0);
    });

    test('연·달 경계와 윤년을 지난다', () {
      expect(daysBetween('2026-12-31', '2027-01-01'), 1);
      expect(daysBetween('2028-02-28', '2028-03-01'), 2); // 2028은 윤년
      expect(daysBetween('2027-02-28', '2027-03-01'), 1);
    });

    test('할 일 줄의 "만료 N일 전부터" 값: 만료일 - dueDate', () {
      expect(daysBetween('2026-11-01', '2027-03-01'), 120);
      expect(daysBetween('2027-01-30', '2027-03-01'), 30);
      expect(daysBetween('2027-02-22', '2027-03-01'), 7);
    });
  });

  group('addYears', () {
    test('같은 월·일에 연도만 더한다', () {
      expect(addYears('2026-11-10', 5), '2031-11-10');
      expect(addYears('2026-01-01', 1), '2027-01-01');
    });

    test('2월 29일이 없는 해로 넘어가면 그 달 말일(2월 28일)', () {
      expect(addYears('2024-02-29', 5), '2029-02-28');
      expect(addYears('2024-02-29', 1), '2025-02-28');
    });

    test('2월 29일이 있는 해로 가면 그대로', () {
      expect(addYears('2024-02-29', 4), '2028-02-29');
    });
  });

  group('parseYmd·formatYmd', () {
    test('왕복하면 같은 글자, 모양이 다르면 null', () {
      expect(formatYmd(parseYmd('2026-09-01')!), '2026-09-01');
      expect(parseYmd('2026-9-1'), isNull);
      expect(parseYmd(''), isNull);
    });
  });
}
