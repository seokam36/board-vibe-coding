import 'package:material_ui/material_ui.dart';

// 색 토큰 (docs/design.md, React의 index.css :root 와 같은 값). 색 숫자는 여기서만 적고 다른 곳은 이름으로 쓴다.
abstract final class AppColors {
  static const paper = Color(0xFF1C2421); // 목록·글·폼이 놓이는 판
  static const canvas = Color(0xFF141A18); // 판 바깥 바탕
  static const hover = Color(0xFF26302C); // 마우스를 올렸을 때 배경
  static const ink = Color(0xFFE4EBE8); // 본문 글자
  static const muted = Color(0xFF9AA7A1); // 보조 글자
  static const line = Color(0xFF2F3A36); // 선, 입력칸 테두리
  static const celadon = Color(0xFF63BBA8); // 포인트: 링크, 주요 버튼 바탕, 포커스
  static const celadonSoft = Color(0xFF1E3A33); // 포인트의 어두운 배경
  static const celadonInk = Color(0xFF8FD5C4); // 청자 배경 위에 올리는 글자
  static const badgeChatBg = Color(0xFF262F2C); // 말머리 뱃지: 잡담 배경(글자는 muted)
  static const badgeQuestionInk = Color(0xFFA9C2F0); // 말머리 뱃지: 질문 글자
  static const badgeQuestionBg = Color(0xFF1F2B42); // 말머리 뱃지: 질문 배경
  static const danger =Color(0xFFF0907A); // 삭제·에러 글자
  static const dangerStrong = Color(0xFFA13A27); // 위험 버튼을 누르고 있을 때
  static const dangerSoft = Color(0xFF3A2521); // 에러 안내의 배경
  static const infoSoft = Color(0xFF222D2A); // 일반 안내의 배경
  static const backdrop = Color.fromRGBO(0, 0, 0, 0.6); // 확인 창 뒤를 덮는 어두운 막 (rgb(0 0 0 / 60%))
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
