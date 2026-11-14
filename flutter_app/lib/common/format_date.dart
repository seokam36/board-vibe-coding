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
