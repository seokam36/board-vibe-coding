// /auth/login, /auth/me, /auth/refresh 가 돌려주는 로그인 사용자 (React의 AuthUser)

enum UserRole { user, admin }

// 학교 인증을 마친 학교
class VerifiedSchool {
  const VerifiedSchool({required this.id, required this.name});

  final int id;
  final String name;

  factory VerifiedSchool.fromJson(Map<String, dynamic> json) =>
      VerifiedSchool(id: json['id'] as int, name: json['name'] as String);
}

class AuthUser {
  const AuthUser({
    required this.id,
    required this.username,
    required this.nickname,
    required this.role,
    this.verifiedSchool,
  });

  final int id;
  final String username;
  // 화면에 보여 주는 이름. 아이디(username)는 내 정보 화면에서만 보여 준다
  final String nickname;
  final UserRole role;
  // null이면 학교 미인증 → 읽기만 가능
  final VerifiedSchool? verifiedSchool;

  // 관리자인지 판단하는 유일한 곳(React의 isAdmin). 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버 검사
  bool get isAdmin => role == UserRole.admin;

  factory AuthUser.fromJson(Map<String, dynamic> json) {
    final school = json['verifiedSchool'];
    return AuthUser(
      id: json['id'] as int,
      username: json['username'] as String,
      nickname: json['nickname'] as String,
      role: json['role'] == 'ADMIN' ? UserRole.admin : UserRole.user,
      verifiedSchool: school == null
          ? null
          : VerifiedSchool.fromJson(school as Map<String, dynamic>),
    );
  }
}
