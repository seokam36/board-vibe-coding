// S16-e1 날짜 표기 테스트: React formatDate.test.ts와 같은 기대값 + 자정·연말 경계를 본다.
import 'package:board_web/common/format_date.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // "올해" 판단이 지금 시각에 달려 있으니 지금을 2026-11-10으로 고정해서 넘긴다 (React 테스트와 같음)
  final now = DateTime.utc(2026, 11, 10);

  test('올해 시각은 연도 없이 한국 시간으로', () {
    expect(formatDate('2026-11-08T06:19:00Z', now: now), '11월 8일 15:19');
  });

  test('한국 시간 기준 해가 바뀌면 새해로 계산하고 자정은 00시', () {
    expect(formatDate('2025-12-31T15:05:00Z', now: now), '1월 1일 00:05');
  });

  test('올해가 아니면 연도를 앞에 붙인다', () {
    expect(formatDate('2025-11-08T06:19:00Z', now: now), '2025년 11월 8일 15:19');
  });

  test('잘못된 날짜는 빈 문자열', () {
    expect(formatDate('abc', now: now), '');
    expect(formatDate('', now: now), '');
  });

  // 경계
  test('자정(한국 00:00)은 24:00이 아니라 00:00', () {
    expect(formatDate('2026-11-08T15:00:00Z', now: now), '11월 9일 00:00');
  });

  test('한국 23:59는 그 날 안', () {
    expect(formatDate('2026-11-08T14:59:00Z', now: now), '11월 8일 23:59');
  });

  test('시·분은 두 자리로 채운다', () {
    expect(formatDate('2026-11-07T20:05:00Z', now: now), '11월 8일 05:05');
  });

  test('"지금"도 한국 시간으로 본다: UTC로는 아직 작년 말이어도 한국은 새해', () {
    // 지금 = 2026-12-31 15:30 UTC = 한국 2027-01-01 00:30 → 올해는 2027
    final newYearKst = DateTime.utc(2026, 12, 31, 15, 30);
    expect(formatDate('2026-12-31T14:00:00Z', now: newYearKst), '2026년 12월 31일 23:00');
    expect(formatDate('2026-12-31T15:10:00Z', now: newYearKst), '1월 1일 00:10');
  });

  test('밀리초·소수 초가 붙어도 읽는다', () {
    expect(formatDate('2026-11-08T06:19:00.123456Z', now: now), '11월 8일 15:19');
  });
}
