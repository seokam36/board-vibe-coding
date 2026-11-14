// 비자 일정 화면용 날짜 계산 모음 (React의 dday.ts). 서버가 준 daysLeft를 사람이 읽는 글자로 바꾸는 순수 함수들.

// 남은 일수 → 화면 글자. 111 → "D-111", 0 → "D-Day", -3 → "D+3" (지난 날은 + 로)
String formatDDay(int daysLeft) {
  if (daysLeft == 0) return 'D-Day';
  return daysLeft > 0 ? 'D-$daysLeft' : 'D+${-daysLeft}';
}

// "yyyy-MM-dd" 글자 → 그 날의 DateTime(UTC 자정). 모양이 다르면 null.
// 시간대·서머타임이 끼면 하루가 밀릴 수 있어서 글자를 직접 쪼개 UTC로 만든다.
DateTime? parseYmd(String day) {
  final m = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(day);
  if (m == null) return null;
  return DateTime.utc(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
}

// DateTime의 연·월·일 칸을 "yyyy-MM-dd"로 이어 붙인다 (toIso8601String은 시간대로 하루가 밀릴 수 있어 쓰지 않는다)
String formatYmd(DateTime d) {
  final mm = d.month.toString().padLeft(2, '0');
  final dd = d.day.toString().padLeft(2, '0');
  return '${d.year.toString().padLeft(4, '0')}-$mm-$dd';
}

// 두 날짜(yyyy-MM-dd) 사이의 일수 (to - from). UTC 자정끼리 빼서 시간대 영향이 없다
int daysBetween(String from, String to) {
  final f = parseYmd(from)!;
  final t = parseYmd(to)!;
  return t.difference(f).inDays;
}

// "yyyy-MM-dd"에 연도를 더한다. 2월 29일이 없는 해로 넘어가면 그 달 마지막 날(2월 28일)로 맞춘다.
// 날짜 칸이 고를 수 있는 마지막 날(달력의 lastDate)용이다. 진짜 범위 검사는 서버가 한다.
String addYears(String day, int years) {
  final d = parseYmd(day)!;
  var out = DateTime.utc(d.year + years, d.month, d.day);
  // 없는 날(2/29)이라 다음 달로 넘쳤으면 그 달 말일(다음 달 0일)로 되돌린다
  if (out.month != d.month) out = DateTime.utc(d.year + years, d.month + 1, 0);
  return formatYmd(out);
}
