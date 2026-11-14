import 'auth_user.dart';

// 댓글·공감·신고는 "학교 인증을 마친 회원이, 인증한 학교의 글에만" 할 수 있다 (React의 canActInSchool).
// 학교가 없는 옛 글(postSchoolId가 null)은 아무도 못 한다.
// 화면에서 막는 건 편의일 뿐, 진짜 보호는 서버의 403이다.
bool canActInSchool(AuthUser? user, int? postSchoolId) {
  final school = user?.verifiedSchool;
  return school != null && postSchoolId != null && school.id == postSchoolId;
}
