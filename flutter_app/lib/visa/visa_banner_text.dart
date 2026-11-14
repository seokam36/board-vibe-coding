// 비자 일정 안내 문구 (React의 visaBanner.ts BANNER_TEXT). 문구를 고칠 때는 여기만 바꾼다.
// {D}는 formatDDay 결과(D-30 등)로 바꿔 쓴다. 상단 띠(i2)와 /visa-plan 화면이 같이 쓴다.
const visaBannerText = <String, String>{
  'PREPARE': '체류 만료 {D} — 연장·자격 변경 준비를 확인하세요',
  'CHECK': '체류 만료 {D} — 신청 방법을 확인하세요',
  'URGENT': '체류 만료 {D} — 체류 상태를 확인하세요',
  'EXPIRED': '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요',
};
