import 'package:material_ui/material_ui.dart';

// 색 토큰 (docs/design.md, 역할 이름으로 쓰는 색 모음). 색 숫자는 여기서만 적고 다른 곳은 이름으로 쓴다.
// 밝은 화면(여권 남색 + 노랑). 모든 값이 서로 달라야 하고, 글자·바탕 짝의 대비는 test/color_contrast_test.dart가 지킨다.
abstract final class AppColors {
  static const canvas = Color(0xFFF4F6FA); // 판 바깥 바탕(연한 회청)
  static const paper = Color(0xFFFFFFFF); // 목록·글·폼이 놓이는 흰 판
  static const hover = Color(0xFFEBEFF6); // 마우스를 올렸을 때 배경
  static const ink = Color(0xFF1C2B4F); // 본문 글자(여권 남색), 주요 버튼 바탕(글자는 흰색)
  static const muted = Color(0xFF5B6782); // 보조 글자
  static const line = Color(0xFFE3E8F2); // 줄 사이 장식 선(입력칸 테두리엔 못 쓴다)
  static const fieldBorder = Color(0xFF7F8AA3); // 입력칸 테두리(흰 바탕과 3:1 이상이라 칸이 보인다)
  static const accent = Color(0xFF2450B8); // 포인트 파랑: 링크, 선택 표시, 포커스
  static const accentSoft = Color(0xFFE4EAF8); // 포인트의 연한 배경(눌린 공감, 인증·승인·판매중 뱃지, 내 말풍선)
  static const accentInk = Color(0xFF1D3F94); // accentSoft 배경 위에 올리는 진한 파랑 글자
  static const highlight = Color(0xFFF2B705); // 노랑 포인트: 바탕으로만 쓴다(글자색 금지). 노랑 위엔 ink 글자만
  static const highlightSoft = Color(0xFFFCEBB0); // 연한 노랑 배경(학교생활 말머리 뱃지)
  static const highlightInk = Color(0xFF5C4500); // highlightSoft 배경 위 진한 갈색 글자
  static const badgeChatBg = Color(0xFFEEF1F6); // 말머리 뱃지: 잡담·거래완료 배경(글자는 muted)
  static const badgeQuestionInk = Color(0xFF0F5E66); // 말머리 뱃지: 질문·예약중 글자(청록)
  static const badgeQuestionBg = Color(0xFFDDF1F2); // 말머리 뱃지: 질문·예약중 배경
  static const danger = Color(0xFFB3261E); // 삭제·에러 글자와 선
  static const dangerStrong = Color(0xFF7A1712); // 위험 버튼을 누르고 있을 때 바탕(글자는 흰색)
  static const dangerSoft = Color(0xFFFCE8E6); // 에러 안내의 배경
  static const successInk = Color(0xFF1E6B45); // 성공 안내 글자(초록)
  static const successSoft = Color(0xFFE3F3EA); // 성공 안내 배경
  static const infoSoft = Color(0xFFE8EEF6); // 일반 안내의 배경
  static const backdrop = Color.fromRGBO(0, 0, 0, 0.45); // 확인 창 뒤를 덮는 막 (검정 45%)
}

// 간격·모서리·글자 크기 (index.css의 --space-*, --radius-*, --fs-*)
abstract final class AppSizes {
  static const space1 = 4.0;
  static const space2 = 8.0;
  static const space3 = 12.0;
  static const space4 = 16.0;
  static const space5 = 24.0;

  static const radiusBtn = 6.0;
  static const radiusPanel = 8.0;

  static const fsSmall = 13.0;
  static const fsSmall2 = 14.0; // 메뉴·버튼·라벨·안내
  static const fsBody = 15.0;
  static const fsRow = 17.0;
  static const fsTitle = 22.0;
  static const fsBoard = 30.0; // 목록 머리말(가장 크고 굵은 글자)

  static const pageMaxWidth = 720.0; // 본문 폭
}
