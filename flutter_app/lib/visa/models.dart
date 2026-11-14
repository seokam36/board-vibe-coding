import '../posts/models.dart' show School;

// 비자 후기 화면이 쓰는 서버 응답 모양 (React types.ts의 VisaOption·VisaReview·VisaReviewStat).

// 선택지 한 개 (GET /visa-review-options). code는 요청·주소에 쓰는 값, name은 화면에 보여 줄 한글 이름
class VisaOption {
  const VisaOption({required this.code, required this.name});

  final String code;
  final String name;

  factory VisaOption.fromJson(Map<String, dynamic> json) =>
      VisaOption(code: json['code'] as String, name: json['name'] as String);
}

// 선택지 묶음 (GET /visa-review-options)
class VisaReviewOptions {
  const VisaReviewOptions({required this.visaTypes, required this.applicationTypes, required this.results});

  final List<VisaOption> visaTypes;
  final List<VisaOption> applicationTypes;
  final List<VisaOption> results;

  factory VisaReviewOptions.fromJson(Map<String, dynamic> json) {
    List<VisaOption> list(String key) => [
          for (final e in json[key] as List<dynamic>) VisaOption.fromJson(e as Map<String, dynamic>),
        ];
    return VisaReviewOptions(
      visaTypes: list('visaTypes'),
      applicationTypes: list('applicationTypes'),
      results: list('results'),
    );
  }
}

// 작성자 (id, 닉네임). 탈퇴했으면 후기의 author가 null
class VisaAuthor {
  const VisaAuthor({required this.id, required this.nickname});

  final int id;
  final String nickname;

  factory VisaAuthor.fromJson(Map<String, dynamic> json) =>
      VisaAuthor(id: json['id'] as int, nickname: json['nickname'] as String);
}

// 비자 후기 한 건 (GET /visa-reviews, /visa-reviews/{id})
class VisaReview {
  const VisaReview({
    required this.id,
    required this.author,
    required this.school,
    required this.visaType,
    required this.applicationType,
    required this.receivedDate,
    required this.resultDate,
    required this.result,
    required this.processingDays,
    required this.content,
    required this.createdAt,
  });

  final int id;
  final VisaAuthor? author;
  final School school;
  final VisaOption visaType;
  final VisaOption applicationType;
  final String receivedDate; // yyyy-MM-dd. 화면에는 formatDay를 거쳐서만 보여 준다
  final String resultDate; // yyyy-MM-dd
  final VisaOption result; // 승인(APPROVED) / 불허(DENIED)
  final int processingDays; // 접수일~결과일 일수 (서버가 계산)
  final String content; // 본문. 안 썼으면 ""
  final String createdAt; // ISO-8601 UTC 문자열. 화면에는 formatDate를 거쳐서만 보여 준다

  factory VisaReview.fromJson(Map<String, dynamic> json) {
    final author = json['author'];
    return VisaReview(
      id: json['id'] as int,
      author: author == null ? null : VisaAuthor.fromJson(author as Map<String, dynamic>),
      school: School.fromJson(json['school'] as Map<String, dynamic>),
      visaType: VisaOption.fromJson(json['visaType'] as Map<String, dynamic>),
      applicationType: VisaOption.fromJson(json['applicationType'] as Map<String, dynamic>),
      receivedDate: json['receivedDate'] as String,
      resultDate: json['resultDate'] as String,
      result: VisaOption.fromJson(json['result'] as Map<String, dynamic>),
      processingDays: json['processingDays'] as int,
      // 본문을 안 썼으면 null로 올 수 있다 → ""로 (화면이 "남긴 메모가 없어요"를 보여 준다)
      content: (json['content'] as String?) ?? '',
      createdAt: json['createdAt'] as String,
    );
  }
}

// 통계 한 줄 (GET /visa-reviews/stats): 학교 x 비자 종류 x 신청 종류 조합
class VisaReviewStat {
  const VisaReviewStat({
    required this.school,
    required this.visaType,
    required this.applicationType,
    required this.count,
    required this.avgDays,
  });

  final School school;
  final VisaOption visaType;
  final VisaOption applicationType;
  final int count; // 후기 수
  final double avgDays; // 평균 처리 일수 (소수 1자리)

  factory VisaReviewStat.fromJson(Map<String, dynamic> json) => VisaReviewStat(
        school: School.fromJson(json['school'] as Map<String, dynamic>),
        visaType: VisaOption.fromJson(json['visaType'] as Map<String, dynamic>),
        applicationType: VisaOption.fromJson(json['applicationType'] as Map<String, dynamic>),
        count: json['count'] as int,
        // 서버가 19.0을 JSON에서 19로 줄여 보내도 읽히게 num으로 받는다
        avgDays: (json['avgDays'] as num).toDouble(),
      );
}
