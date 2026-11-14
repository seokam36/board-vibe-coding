// 테스트용 비자 후기 JSON (목록·통계·상세 테스트가 같이 쓴다). 값을 바꿔 가며 쓸 수 있다.
import 'fake_adapter.dart';

const visaSeoul = {'id': 1, 'name': '서울대'};
const visaBusan = {'id': 2, 'name': '부산대'};
const visaSchools = [visaSeoul, visaBusan];

const visaOptionsJson = {
  'visaTypes': [
    {'code': 'D2', 'name': '유학(D-2)'},
    {'code': 'D4', 'name': '어학연수(D-4)'},
  ],
  'applicationTypes': [
    {'code': 'EXTENSION', 'name': '연장'},
    {'code': 'CHANGE', 'name': '변경'},
  ],
  'results': [
    {'code': 'APPROVED', 'name': '승인'},
    {'code': 'DENIED', 'name': '불허'},
  ],
};

// 후기 한 건. author에 null을 넘기면 "작성자 없음"
Map<String, dynamic> reviewJson({
  int id = 7,
  Map<String, dynamic>? author = const {'id': 1, 'nickname': '홍길동'},
  Map<String, dynamic> school = visaSeoul,
  Map<String, dynamic> visaType = const {'code': 'D2', 'name': '유학(D-2)'},
  Map<String, dynamic> applicationType = const {'code': 'EXTENSION', 'name': '연장'},
  String receivedDate = '2026-09-01',
  String resultDate = '2026-09-20',
  Map<String, dynamic> result = const {'code': 'APPROVED', 'name': '승인'},
  int processingDays = 19,
  String content = '서류는 빨리 냈어요',
  String createdAt = '2020-11-08T06:19:00Z',
}) => {
  'id': id,
  'author': author,
  'school': school,
  'visaType': visaType,
  'applicationType': applicationType,
  'receivedDate': receivedDate,
  'resultDate': resultDate,
  'result': result,
  'processingDays': processingDays,
  'content': content,
  'createdAt': createdAt,
};

// 통계 한 줄
Map<String, dynamic> statJson({
  Map<String, dynamic> school = visaSeoul,
  Map<String, dynamic> visaType = const {'code': 'D2', 'name': '유학(D-2)'},
  Map<String, dynamic> applicationType = const {'code': 'EXTENSION', 'name': '연장'},
  int count = 3,
  num avgDays = 19.0,
}) => {
  'school': school,
  'visaType': visaType,
  'applicationType': applicationType,
  'count': count,
  'avgDays': avgDays,
};

// 선택지(학교·선택지)는 성공, 본문 응답은 호출하는 쪽이 정한다
FakeAdapter visaFilterAdapter() => FakeAdapter()
  ..on('GET', '/schools', FakeReply(200, visaSchools))
  ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson));
