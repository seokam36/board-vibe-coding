// 서버가 알려 주는 "내 인증 상태" 모델 (GET·POST 응답 공통)

// 서버가 알려 주는 "내 인증 상태" (GET·POST 응답 공통). status: NONE / PENDING / REJECTED / APPROVED
class PermitStatus {
  const PermitStatus({
    required this.status,
    this.rejectReason,
    this.requestedAt,
  });

  final String status;
  final String? rejectReason;
  final String? requestedAt;

  factory PermitStatus.fromJson(Map<String, dynamic> json) => PermitStatus(
    status: json['status'] as String,
    rejectReason: json['rejectReason'] as String?,
    requestedAt: json['requestedAt'] as String?,
  );
}
