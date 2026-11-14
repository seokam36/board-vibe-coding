// 서버의 UTC 시각 문자열을 한국 시간 표기로 바꾼다 (React의 formatDate.ts와 같은 결과).
// 화면에 시각을 보여 줄 땐 반드시 이 함수를 쓴다. intl 없이 직접 계산한다: 한국은 UTC+9 고정(서머타임 없음)이라 가능.
// 같은 해면 "11월 8일 15:19", 다른 해면 "2025년 11월 8일 15:19", 잘못된 값은 "".
// now: "올해" 판단에 쓰는 지금 시각. 테스트에서 고정하려고 받는다(없으면 진짜 지금).
String formatDate(String iso, {DateTime? now}) {
  final parsed = DateTime.tryParse(iso);
  if (parsed == null) return ''; // 읽을 수 없는 값이면 빈 글자
  // UTC에 9시간을 더한 값의 year/month/day/hour/minute 칸이 곧 한국 시각이다
  const kst = Duration(hours: 9);
  final seoul = parsed.toUtc().add(kst);
  final thisYear = (now ?? DateTime.now()).toUtc().add(kst).year;
  // 시·분은 두 자리(자정은 24:05가 아니라 00:05)
  final hh = seoul.hour.toString().padLeft(2, '0');
  final mm = seoul.minute.toString().padLeft(2, '0');
  final text = '${seoul.month}월 ${seoul.day}일 $hh:$mm';
  return seoul.year == thisYear ? text : '${seoul.year}년 $text';
}

// 날짜만 있는 "yyyy-MM-dd"를 "2026년 9월 1일"로 바꾼다 (React의 formatDay).
// DateTime을 거치지 않고 글자를 직접 쪼갠다: 시간대가 끼어들어 하루가 밀리는 일이 없다. 모양이 다르면 "".
String formatDay(String day) {
  final m = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(day);
  if (m == null) return '';
  return '${int.parse(m[1]!)}년 ${int.parse(m[2]!)}월 ${int.parse(m[3]!)}일';
}

// 서울 기준 오늘 "yyyy-MM-dd" (React의 seoulToday). 한국은 UTC+9 고정이라 9시간을 더해 읽는다.
// now: 테스트에서 지금을 고정하려고 받는다(없으면 진짜 지금).
String seoulToday({DateTime? now}) {
  final seoul = (now ?? DateTime.now()).toUtc().add(const Duration(hours: 9));
  final mm = seoul.month.toString().padLeft(2, '0');
  final dd = seoul.day.toString().padLeft(2, '0');
  return '${seoul.year.toString().padLeft(4, '0')}-$mm-$dd';
}
