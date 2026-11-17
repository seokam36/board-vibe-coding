// 관리자 허가서 심사 목록의 서버 응답 모양 (docs/api.md 47번). 값이 없는 칸은 null.

// 기계 판정 결과 코드 → 화면 문구 (모르는 코드는 그대로 보여 주고, null은 "판정 없음")
const permitMachineResultLabels = <String, String>{
  'UNREADABLE': '글자를 못 읽음',
  'NO_TITLE': '허가서 제목 없음',
  'NATIONALITY_UNCLEAR': '국적 불명확',
  'SCHOOL_NOT_MATCHED': '학교 불일치',
  'NUMBER_NOT_FOUND': '번호 못 찾음',
  'NUMBER_DUPLICATE': '이미 쓰인 번호',
  'ALL_PASSED': '4가지 모두 통과',
};

String machineResultText(String? code) =>
    code == null ? '판정 없음' : (permitMachineResultLabels[code] ?? code);

// 허가서 신청 한 건 (GET /admin/permit-requests)
class AdminPermitRequest {
  const AdminPermitRequest({
    required this.id,
    required this.userId,
    required this.nickname,
    required this.status,
    required this.createdAt,
    required this.schoolName,
    required this.rejectReason,
    required this.detectedSchoolId,
    required this.detectedSchoolName,
    required this.machineResult,
    required this.autoDecided,
    required this.hasPhoto,
  });

  final int id;
  final int userId; // 신청한 회원 (내 신청인지 가려내는 데 쓴다)
  final String nickname;
  final String status; // PENDING | APPROVED | REJECTED
  final String createdAt; // ISO-8601 UTC 문자열
  final String? schoolName; // 승인된 학교
  final String? rejectReason; // 반려 사유 코드
  final int? detectedSchoolId; // 기계가 찾은 학교 id (승인 단계에서 쓴다)
  final String? detectedSchoolName; // 기계가 글자에서 찾은 학교
  final String? machineResult;
  final bool autoDecided;
  final bool hasPhoto;

  factory AdminPermitRequest.fromJson(Map<String, dynamic> json) => AdminPermitRequest(
    id: json['id'] as int,
    userId: json['userId'] as int,
    nickname: json['nickname'] as String,
    status: json['status'] as String,
    createdAt: json['createdAt'] as String,
    schoolName: json['schoolName'] as String?,
    rejectReason: json['rejectReason'] as String?,
    detectedSchoolId: json['detectedSchoolId'] as int?,
    detectedSchoolName: json['detectedSchoolName'] as String?,
    machineResult: json['machineResult'] as String?,
    autoDecided: json['autoDecided'] as bool? ?? false,
    hasPhoto: json['hasPhoto'] as bool? ?? false,
  );
}
