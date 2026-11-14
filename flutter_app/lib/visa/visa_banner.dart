// 상단 비자 일정 띠의 문구·닫기 기록을 정하는 순수 함수 모음 (React의 visaBanner.ts).
// 화면과 분리해 단위 테스트한다. 닫기 기록 값은 React와 같아야 한다(교체 뒤에도 닫은 기록이 유지되도록).
import '../common/local_store.dart';
import 'dday.dart';
import 'visa_banner_text.dart';

const bannerLinkText = '비자 일정 보기';
const bannerCloseLabel = '띠 닫기';

// 단계와 남은 일수로 띠 문구를 만든다. 모르는 stage(서버만 먼저 배포됐을 때 등)면 null: 띠를 그리지 않아 화면이 죽지 않게
String? bannerMessage(String stage, int daysLeft) {
  final text = visaBannerText[stage];
  return text?.replaceFirst('{D}', formatDDay(daysLeft));
}

// 닫은 상태를 저장하는 키: 회원당 하나. 값을 덮어쓰니 키가 쌓이지 않는다
String dismissKey(int userId) => 'visaBannerClosed:$userId';

// 만료일을 알아볼 수 없게 바꾸는 짧은 비암호 해시 (djb2 32비트 → 36진수). 공용 PC에서 만료일이 보이지 않게 하려는 용도.
// React의 shortHash와 같은 결과여야 해서 같은 연산을 쓴다: h = ((h * 33) ^ 글자코드) 를 32비트로 자른다.
String shortHash(String text) {
  var h = 5381;
  for (final c in text.codeUnits) {
    h = ((h * 33) ^ c) & 0xFFFFFFFF;
  }
  return h.toRadixString(36);
}

// 닫은 값: 단계 + 만료일 해시. 단계가 바뀌거나 만료일을 바꾸면 값이 달라져 다시 뜬다
String dismissValue(String stage, String expiryDate) => '$stage:${shortHash(expiryDate)}';

// 저장된 값이 지금 값과 같으면 닫힌 상태. 저장소가 막혀 예외가 나도 "안 닫힘"으로 본다
bool isDismissed(int userId, String value) {
  try {
    return localStore.getItem(dismissKey(userId)) == value;
  } catch (_) {
    return false;
  }
}

// 닫은 값을 저장한다(덮어쓰기). 막혀 있으면 조용히 넘어간다 (이번 화면에서만 닫힘)
void saveDismissed(int userId, String value) {
  try {
    localStore.setItem(dismissKey(userId), value);
  } catch (_) {}
}
