// 내 비자 일정 (GET·PUT /users/me/visa-plan 응답). React의 VisaPlan·VisaTodo.

// 할 일 한 줄
class VisaTodo {
  const VisaTodo({required this.code, required this.title, required this.dueDate, required this.daysLeft});

  final String code; // PREPARE / CHECK / URGENT (만료 며칠 전 시점의 이름)
  final String title; // 화면에 보여 줄 문구
  final String dueDate; // yyyy-MM-dd. 확인을 "시작할 시점"이지 마감이 아니다
  final int daysLeft; // 오늘(서울)부터 남은 일수. 지났으면 음수

  factory VisaTodo.fromJson(Map<String, dynamic> json) => VisaTodo(
    code: json['code'] as String,
    title: json['title'] as String,
    dueDate: json['dueDate'] as String,
    daysLeft: (json['daysLeft'] as num).toInt(),
  );
}

// 일정이 없으면 expiryDate·daysLeft·stage가 null, todos는 빈 목록
class VisaPlan {
  const VisaPlan({this.expiryDate, this.daysLeft, this.stage, this.todos = const []});

  // 일정이 없는 상태. 서버도 일정이 없으면 이 모양으로 준다
  static const empty = VisaPlan();

  final String? expiryDate; // yyyy-MM-dd
  final int? daysLeft; // 만료일까지 남은 일수 (지났으면 음수)
  final String? stage; // PREPARE / CHECK / URGENT / EXPIRED / null. 모르는 값이 와도 문자열이라 죽지 않는다
  final List<VisaTodo> todos;

  // 만료일과 남은 일수가 둘 다 있어야 "일정 있음"
  bool get hasPlan => expiryDate != null && daysLeft != null;

  factory VisaPlan.fromJson(Map<String, dynamic> json) => VisaPlan(
    expiryDate: json['expiryDate'] as String?,
    daysLeft: (json['daysLeft'] as num?)?.toInt(),
    stage: json['stage'] as String?,
    todos: [
      for (final t in (json['todos'] as List<dynamic>? ?? const [])) VisaTodo.fromJson(t as Map<String, dynamic>),
    ],
  );
}
