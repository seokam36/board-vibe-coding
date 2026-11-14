// 테스트용 비자 일정 JSON (비자 일정 화면·보관소 테스트가 같이 쓴다). 값은 React e2e(visa-plan.spec.ts)와 같다.
// 시계는 서울 2026-11-10으로 고정해서 쓴다.

Map<String, dynamic> _todo(String code, String title, String dueDate, int daysLeft) => {
  'code': code,
  'title': title,
  'dueDate': dueDate,
  'daysLeft': daysLeft,
};

// 일정 없음 (서버도 이 모양으로 준다)
const emptyPlanJson = {'expiryDate': null, 'daysLeft': null, 'stage': null, 'todos': <dynamic>[]};

// 만료 111일 전 (단계 PREPARE: 첫 줄이 현재 단계, 나머지 둘은 아직 안 온 단계)
Map<String, dynamic> planJson() => {
  'expiryDate': '2027-03-01',
  'daysLeft': 111,
  'stage': 'PREPARE',
  'todos': [
    _todo('PREPARE', '연장 준비를 시작하세요', '2026-11-01', -9),
    _todo('CHECK', '서류를 확인하세요', '2027-01-30', 81),
    _todo('URGENT', '체류 상태를 확인하세요', '2027-02-22', 104),
  ],
};

// 만료 당일: D-Day, 단계 URGENT라 URGENT 줄이 현재 단계, 앞 두 줄은 지난 단계
Map<String, dynamic> dDayPlanJson() => {
  'expiryDate': '2026-11-10',
  'daysLeft': 0,
  'stage': 'URGENT',
  'todos': [
    _todo('PREPARE', '연장 준비를 시작하세요', '2026-07-13', -120),
    _todo('CHECK', '서류를 확인하세요', '2026-10-11', -30),
    _todo('URGENT', '체류 상태를 확인하세요', '2026-11-03', -7),
  ],
};

// 만료 3일 지남: D+3, 중립 안내
Map<String, dynamic> expiredPlanJson() => {
  'expiryDate': '2026-11-07',
  'daysLeft': -3,
  'stage': 'EXPIRED',
  'todos': [
    _todo('PREPARE', '연장 준비를 시작하세요', '2026-07-10', -123),
    _todo('CHECK', '서류를 확인하세요', '2026-10-08', -33),
    _todo('URGENT', '체류 상태를 확인하세요', '2026-10-31', -10),
  ],
};

// 만료 D-15 (단계 CHECK): CHECK 줄이 현재 단계, PREPARE는 지난 단계, URGENT는 아직 안 온 단계
Map<String, dynamic> d15PlanJson() => {
  'expiryDate': '2026-11-25',
  'daysLeft': 15,
  'stage': 'CHECK',
  'todos': [
    _todo('PREPARE', '연장 준비를 시작하세요', '2026-07-28', -105),
    _todo('CHECK', '서류를 확인하세요', '2026-10-26', -15),
    _todo('URGENT', '체류 상태를 확인하세요', '2026-11-18', 8),
  ],
};

// 121일 이상 남아 단계(stage)가 없는 일정
Map<String, dynamic> farPlanJson() => {
  'expiryDate': '2027-05-30',
  'daysLeft': 201,
  'stage': null,
  'todos': [
    _todo('PREPARE', '연장 준비를 시작하세요', '2027-01-30', 81),
    _todo('CHECK', '서류를 확인하세요', '2027-04-30', 171),
    _todo('URGENT', '체류 상태를 확인하세요', '2027-05-23', 194),
  ],
};

// 저장으로 바뀐 일정 (만료 2027-03-31, 141일 → 단계 없음)
Map<String, dynamic> laterPlanJson() => {...planJson(), 'expiryDate': '2027-03-31', 'daysLeft': 141, 'stage': null};
