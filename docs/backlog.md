# Backlog

할 일은 위에서부터 하나씩 진행한다. 끝나면 "할 일"에서 지우고 "끝난 일" 표에 한 줄 추가한다(기능 커밋과 같이).
자세한 이유·배운 점은 Notion 학습 기록에, 2026-11-03까지의 상세 변경 기록은 docs/archive/changelog-2026.md에 있다.

## 할 일
- [x] S4. 회원가입 + 로그인 (JWT 액세스 토큰 15분 + 리프레시 토큰 7일·Redis, 둘 다 HttpOnly 쿠키, CSRF 켬)
  - [x] S4-a. Spring Security + 회원가입 API
  - [x] S4-b. 로그인(액세스 토큰 쿠키) / 로그아웃 / 내 정보
  - [x] S4-c. Redis 도입 + 리프레시 토큰 발급·재발급·로그아웃 시 삭제
  - [x] S4-d. 글과 작성자 연결 + 권한 (글쓰기 로그인, 수정·삭제 본인만, 기존 글 작성자 없음)
  - [x] S4-e. 화면 (가입·로그인, 자동 재발급)
    - [x] S4-e-1. Vite 프록시 + CSRF + 가입·로그인·로그아웃 + 상단바
    - [x] S4-e-2. 자동 재발급 (401 → refresh → 재시도, 동시에 하나만)
    - [x] S4-e-3. 글 화면에 작성자·권한 반영 (본인 글에만 수정·삭제 버튼)
  - [x] S4-f. EC2 배포 (Redis 컨테이너, 비밀키)
    - [x] S4-f-1. 키 교체 (AWS 액세스 키, EC2 접속 키, 배포 SSH 키, EC2 DB 비밀번호) + deploy.md 키 이름 반영
    - [x] S4-f-2. DB 백업(서버+노트북) → .env·compose 갱신 → 배포 → 확인 (헬스체크에 Redis 포함 유지)
- [x] S5. 닉네임 + 회원정보 수정 + 회원 탈퇴 (아이디는 변경 불가)
  - [x] S5-a. 닉네임 칸 추가(가입에 닉네임, 응답에 닉네임, 글 작성자는 {id, nickname}) + /auth/me가 DB 확인
  - [x] S5-b. 사용자별 리프레시 토큰 목록(Redis) + 전부 끊기
  - [x] S5-c. 닉네임 변경 + 비밀번호 변경(현재 비밀번호 확인, 변경 후 모든 기기 로그아웃)
  - [x] S5-d. 회원 탈퇴(완전 삭제, 글은 작성자 null로 남김)
  - [x] S5-e. 화면
    - [x] S5-e-1. 가입에 닉네임 칸 + 글 작성자·상단바를 닉네임으로 표시
    - [x] S5-e-2. 내 정보 페이지 (닉네임·비밀번호 변경, 탈퇴)
  - [x] S5-f. EC2 배포 (DB 백업 → 기존 회원 삭제(글 작성자 null 먼저) → 배포)
- [x] S6. DB 변경을 안전하게 배포하기 (표 구조가 바뀌어도 Deploy 버튼 하나로)
  - [x] S6-a. Deploy 전 서버 DB 자동 백업 (pg_dump, 서버 ~/board/backups에 10개 보관, 실패하면 배포 중단)
  - [x] S6-b. Flyway 도입
    - [x] S6-b-1. Flyway 의존성 + V1(운영 구조 기준) + ddl-auto=validate + 테스트가 Flyway로 표 만들기
    - [x] S6-b-2. 표가 이미 있는 DB를 기준선으로 인정 (로컬 리허설 + 자동 테스트)
  - [x] S6-c. EC2 배포
  - [x] S6-c 후속. 운영·로컬 baseline이 끝나면 spring.flyway.baseline-on-migrate·baseline-version 삭제 (기록장 없는 이상한 DB를 V1으로 잘못 인정하지 않게). FlywayBaselineTests와 baseline 테스트 데이터도 같이 수정(시작 실패 확인으로 전환 또는 삭제)
- 컨셉: 같은 학교 유학생 선배들의 경험이 쌓이는 외국인 유학생 커뮤니티. 닉네임으로 시작(익명 없음), 화면은 한국어·영어부터, 사이트 이름은 나중에 정한다.
- [x] S7. 학교별 게시판과 말머리 (학교: 경희대·고려대·성균관대·연세대·한양대, 캠퍼스 구분 없음. 말머리: 잡담·질문·학교생활. 기존 글은 학교 미지정·잡담. 작성 후 말머리만 변경 가능. 첫 화면은 전체 글 + 드롭다운. 공용 게시판 없음)
  - [x] S7-a. V2(학교 표, 글에 학교·말머리)와 글 API, 학교·말머리 목록 API
  - [x] S7-b. 글 목록을 학교·말머리로 거르는 API
  - [x] S7-c. 목록·상세 화면
  - [x] S7-d. 글쓰기·수정 화면
  - [x] S7-e. EC2 배포 (deploy.md에 V2 후 옛 이미지 되돌리기 주의 추가)
- [x] S8. 댓글과 공감 (평댓글만, 공감은 글에만·한 사람 한 번·취소 가능. 글 삭제 시 댓글·공감은 DB ON DELETE CASCADE로 같이 삭제. 탈퇴 시 댓글은 작성자 null로 남기고 공감은 삭제. 댓글 1000자·오래된 순. 댓글 수·공감 수는 조회 때 센다)
  - [x] S8-a. V3(comments·post_likes 표) + 댓글 API (목록 공개, 작성 로그인, 수정·삭제 본인만, 탈퇴 시 댓글 작성자 null)
  - [x] S8-b. 공감 API (누르기·취소·내가 눌렀는지) + 글 응답에 commentCount·likeCount
  - [x] S8-c. 화면 (상세에 댓글·공감 버튼, 목록에 댓글 수·공감 수)
  - [x] S8-d. EC2 배포 (deploy.md에 V3는 되돌려도 문제없음 한 줄)
- [x] S9. 신고와 관리자 기능 (글·댓글 신고, 사유 5개(광고·스팸/욕설·비방/음란·선정적/개인정보 노출/기타) + 상세 선택 300자, 같은 대상 중복 신고 409, 자기 글 신고 400. 관리자는 users.role=ADMIN(DB SQL로만 지정), 신고 목록·기각, 남의 글·댓글 삭제(하드 삭제 유지, 신고도 CASCADE). 신고자 탈퇴 시 신고는 남고 신고자 null. 회원 정지·자동 숨김·관리자 수정은 안 함)
  - [x] S9-a. V4(users.role) + 토큰·응답에 role + 글·댓글 삭제 "본인 또는 관리자"(관리자 권한은 DB 재확인) + 공감 취소·조회 유령 토큰 401 통일
  - [x] S9-b. V5(reports 표) + 신고 API(글·댓글) + 사유 목록 API
  - [x] S9-c. 관리자 신고 API (목록·기각)
  - [x] S9-d. 화면 (신고 버튼, 관리자 삭제 버튼, 신고 관리 페이지)
  - [x] S9-e. EC2 배포 (관리자 지정 SQL·V4/V5 되돌리기 메모를 deploy.md에)
- [x] UI1. 화면 꾸미기 (순수 CSS + CSS 변수, 새 의존성 없음. 에브리타임처럼 깔끔한 목록형이되 따라 하지 않는 우리만의 색·글꼴. 375px 폭 대응, 항상 어두운 화면(밝은 화면·전환 버튼 없음). 백엔드·API 변경 없음)
  - [x] UI1-a. 공용화 리팩터링(모양·기능 변화 없음): 에러 문구 공용 함수, 안내 배너 컴포넌트, isAdmin, 인라인 style 제거, 로딩 표시 공용
  - [x] UI1-b. 디자인 토대: 색·글꼴·간격 변수, 버튼·입력·안내 배너, 상단바, 사이트 이름 상수
  - [x] UI1-c. 화면별 꾸미기: 목록·상세·댓글, 폼 화면·관리자
  - [x] UI1-d. 공용 확인 창(<dialog>)으로 window.confirm 4곳 교체
  - [x] UI1-e. EC2 배포 (S9 후속 리뷰 변경도 같이 나감)
- [x] S10. 학교 메일 인증 (표시는 "학교 인증 완료", 인증 전에는 읽기만 가능하고 학교 게시판 글쓰기·비자 후기는 인증 후 가능, 졸업 후에도 인증 유지. 여권번호·외국인등록번호는 받지 않는다. 유학생 여부 확인은 나중에 전문 업체 인증으로 확장 검토)
  - 결정: Gmail SMTP로 시작(나중에 SES), 인증한 학교 게시판에만 글쓰기, 메일은 소문자 SHA-256 해시만 UNIQUE 저장(원문 저장 안 함), 인증 여부는 토큰 말고 매번 DB 확인, 학교는 메일 도메인으로 자동 판별, 코드 6자리·10분·재발송 60초·5회 실패 폐기·하루 10회, 미인증은 읽기만(글쓰기·댓글·공감·신고 모두 인증 후, 2026-11-09 변경), 관리자도 인증 필요, 가입 때 메일은 받지 않음(인증은 내 정보에서)
  - [x] S10-a. V6(school_email_domains 표 + users 인증 3칸) + 응답에 verifiedSchool, /schools에 emailDomains
  - [x] S10-b. 인증 코드 보내기 API (가짜 발송: 로그에 코드)
  - [x] S10-c. 코드 확인 API → 인증 완료 저장
  - [x] S10-d. 인증 전 제한 (글쓰기·댓글·공감·신고 모두 인증 후, 인증한 학교 글에만. 학교 미지정 옛 글은 읽기만. 수정·삭제·공감 취소는 인증과 무관)
  - [x] S10-e. 실제 메일 발송 (SMTP)
  - [x] S10-f. 화면
  - [x] S10-g. 같은 메일 주소 발송 제한 (계정 여러 개로 남의 메일에 폭탄 방지: 받는 메일 주소 기준 하루 5회 + 같은 계정·같은 메일 하루 2회, 넘으면 429)
  - [x] S10-h. EC2 배포 (집 PC에서)
- [x] S11. 비자 후기와 학교별 통계 (공식 안내 링크와 작성일을 함께 표시, 신청 대행·개인별 법률 판단은 하지 않는다)
  - 결정(2026-11-09): 후기는 정해진 칸(비자 종류·신청 종류·접수일·결과일·결과) + 자유 본문. 통계는 학교별·비자 종류별·신청 종류별 평균 처리 기간(접수일~결과일). 읽기는 누구나(비로그인 포함), 쓰기는 학교 인증 회원만이고 후기의 학교는 작성 시점의 인증 학교를 복사해 저장(직접 고르지 않음, 탈퇴해도 통계 유지). 탈퇴 후 같은 메일 재인증은 지금처럼 허용.
  - 결정(planner 후): 별개 표 visa_reviews. 비자 D-2·D-4·D-10·E-7·기타 / 신청 신규 발급·외국인등록·연장·자격 변경·기타 / 결과 승인·불허(결과 난 후기만). 날짜는 접수일 ≤ 결과일 ≤ 오늘(서울 기준). 처리 일수는 저장하지 않고 계산. 통계는 숨기지 않고 건수와 함께. 공식 링크는 하이코리아 하나(화면 상수). 본문 선택·2000자. 수정 본인, 삭제 본인·관리자, 탈퇴 시 작성자 null(학교는 남음). 페이징·댓글·공감·신고 없음. 알려진 한계: 탈퇴·재가입으로 후기를 다시 써 통계를 부풀릴 수 있음. 한 회원이 같은 후기를 여러 번 써도 막지 않음(S11 파트 리뷰, 2026-11-10: 회원당 작성 수 제한 또는 같은 회원·비자·신청·접수일 중복 409는 나중에)
  - [x] S11-a. V7(visa_reviews 표 + 인덱스, users.verified_school_id 인덱스) + 엔티티·enum + 선택지 목록 API (동작 변화 없음)
  - [x] S11-b. 후기 작성(인증 회원, 학교 자동) + 단건 조회(공개) + 탈퇴 시 후기 작성자 null(작성자 FK에 ON DELETE가 없어 같이 해야 탈퇴가 안 깨짐)
  - [x] S11-c. 목록 거르기 + 수정·삭제
  - [x] S11-d. 통계 API (학교·비자·신청 종류별 건수·평균 처리 일수). S11-c 리뷰 메모: 엔티티를 읽지 말고 group by 집계 쿼리로(날짜 빼기는 PostgreSQL 네이티브가 간단), 거르기 조건은 getAll과 공용으로 빼기, api.md 19번 "id 숫자 아님 400"에 /stats 예외 한 마디. 알려진 한계: 수정 중 다른 쪽이 삭제하면 500(글 수정과 같음)
  - [x] S11-e. 화면 (목록·상세 / 작성·수정 / 통계). 결정: 상단바 "비자 후기" 링크(모두), 통계는 /visa-reviews/stats + 목록과 탭(필터 쿼리 유지), 하이코리아 메인 URL 상수 하나, 접수·결과일은 "2026년 9월 1일"(날짜 전용 formatDay), 결과 뱃지 승인=청자색·불허=회색(빨강 안 씀). 백엔드 변경 없음
    - [x] S11-e-1. 목록·상세(읽기 전용) + 상단바 링크 + 하이코리아 안내 배너 + 공용 필터
    - [x] S11-e-2. 작성·수정·삭제(공용 폼, 미인증 안내, 본인 수정·삭제 / 관리자 삭제만, 확인 창)
    - [x] S11-e-3. 통계 표(탭, 공용 필터, 건수와 평균 같이, 학교 필터 시 학교 열 숨김)
  - [x] S11-f. EC2 배포
- [x] UI2. 프론트 자동 테스트 (지금은 화면을 사람이 직접 눌러 확인한다. 로봇이 대신 확인하게 만든다. 백엔드·API 변경 없음)
  - [x] UI2-a. Vitest: 작은 부품(에러 문구 함수 describeError, 날짜 표시 등) 단위 테스트 + CI에서 같이 돌리기
  - [x] UI2-b. Playwright: 실제 브라우저로 핵심 흐름(로그인 → 글 목록 → 글 보기 등) 확인
    - [x] UI2-b-1. Playwright 설치 + 핵심 흐름 3개(가짜 응답, 로컬)
    - [x] UI2-b-2. CI에 e2e 작업 추가(실패하면 이미지 push 막기)
    - [x] UI2-b-3. 에러 문구 화면 테스트(UI3 지킴이)
- [x] UI3. 프론트 공통 에러 문구표 (404 같은 문구를 화면마다 따로 쓰는 중복을 한 곳으로 모은다. 모양·동작 변화 없음, UI2 테스트가 지켜 준다)
  - 결정(2026-11-10): errorMessage.ts에 문구 사전 객체 `ERROR_MESSAGES` 하나, 2곳 이상 중복된 문구만(1곳짜리는 화면에 둠), 글자 변경 0, 표 조립 헬퍼는 안 함, 기존 상수(CSRF·NETWORK·RELOGIN)는 그대로
  - [x] UI3-a. 지킴이 보강: 옮길 문구 e2e 장면 추가(삭제 403 두 갈래·404, 가입 403, 상세 500, 목록 400, 드롭다운 실패, 후기 목록 404·통계 400, 재발급 끊김) + mockApi 정리(가짜 응답 도구 정리). src 변경 0
  - [x] UI3-b-1. 글·댓글·공감·신고·목록 화면 문구를 사전으로
  - [x] UI3-b-2. 비자 후기·로그인·가입·내 정보 화면 문구를 사전으로
  - [x] UI3-c. EC2 배포 → S12 배포 때 같이 하기로(2026-11-10 결정). S11 리뷰 수정(1900년 접수일 등 날짜 하한)도 그때 운영 반영
  - 나중(문구 자체 변경): CSRF 403과 학교 인증 403 문구 섞임(서버가 구분 안 함), 어미 통일(~습니다/~어요), 학교 인증 403 변형 3종 통일, 수정 403의 CSRF 미고려(PostEditPage·CommentSection)
  - 나중(UI3 리뷰): "본인 글/댓글/후기만 수정할 수 있습니다" 3형제를 alreadyDeleted처럼 함수 하나로(라벨만 다른 문구는 함수형 사전 허용으로 원칙 정하기), errorMessage.ts 맨 위에 파일 안내 주석, SignupPage의 '가입하지 못했습니다' 2번을 지역 상수로
- [x] S12. 비자 할 일과 자동 알림 (배포 때 S11 리뷰 수정·UI2·UI3도 같이 나감)
  - 결정(2026-11-10): 학교 인증 회원이 체류 만료일 하나를 등록하면 할 일 목록(날짜 + 하이코리아 링크, 개인별 판단 없음)을 보여 주고, 알림은 **사이트 안 알림**(로그인하면 상단 띠)으로. 메일 알림은 안 함(사용자가 메일을 잘 안 봄, 메일 원문 저장·주인 확인·Gmail 한도 문제도 없앰). 브라우저 푸시는 HTTPS가 필요해서 나중
  - 결정(planner 후): 새 표 visa_plans(회원당 1개, 만료일만, 탈퇴 시 ON DELETE CASCADE). 만료일은 서울 오늘 ≤ 만료일 ≤ 오늘+5년. 시점 120·30·7일 전(숫자는 VisaMilestone enum 한 곳, 하이코리아 확인 후 조정 가능). 띠 단계 D120/D30/D7/EXPIRED는 서버가 서울 날짜로 계산. API는 /users/me/visa-plan GET·PUT·DELETE, 일정이 없으면 GET은 404가 아니라 200 + expiryDate null(띠 때문에 매번 부르므로, "내 일정 비어 있음"으로 봄), DELETE는 없으면 404. 띠는 120일 이내부터, 만료 후엔 중립 문구, X로 닫기(localStorage에 회원·만료일·단계, 단계가 바뀌면 다시 뜸, 7일 이내도 닫기 가능), 미등록 회원 권유 띠 없음, /visa-plan 안에서는 숨김, GET 실패 시 띠만 숨김. 띠는 role=status(alert 아님). 완료 체크 저장·일정 여러 개·자정 넘김 자동 갱신 없음
  - [x] S12-a. V8(visa_plans) + 일정 등록·조회·삭제 API
  - 결정(S12-b 리뷰 후, 2026-11-10): 상태값(stage)·할 일 code는 숫자 없는 이름 PREPARE(120일 이내)·CHECK(30일 이내)·URGENT(7일 이내)·EXPIRED(지남) — 기준 숫자를 바꿔도 이름이 안 어긋나게. 상태값은 화면이 띠를 띄울지·문구·닫은 뒤 다시 띄울지 판단하는 데만 쓰고, 사용자에게는 daysLeft로 D-111 / D-Day / D+3을 보여 준다(S12-c·d). 할 일 문구는 연장뿐 아니라 자격 변경도 포함, 기관 이름은 "출입국·외국인청"
  - [x] S12-b. 할 일 목록 + 띠 단계 계산(todos·daysLeft·stage)
  - [x] S12-c. 화면: /visa-plan 페이지 + 상단바 링크
  - 나중(S12-c 리뷰): 고정 안내문(PlanGuide·VisaDisclaimer)이 Notice info라 role=status(Notice에 role 없는 고정 안내 종류 두기) · e2e가 .visa-dday 등 클래스 셀렉터에 의존(region·listitem 역할로) · mockApi PUT 기본 응답이 "일정 없음"(현실적인 일정으로 또는 기본 빼기) · loginVerified가 두 spec에 중복(S12-d에서 mockApi로 옮겨 export) · hasPlan 뒤에도 ?? 필요(일정 있음/없음 타입 분리) · 만료된 일정은 지난 날짜가 폼에 채워져 그대로 누르면 브라우저 기본 말풍선
  - 결정(S12-d 리뷰 후, 2026-11-10): 띠 닫기 기록은 회원당 localStorage 키 하나(`visaBannerClosed:{회원id}`), 값은 단계 + 만료일을 알아볼 수 없게 바꾼 짧은 해시(공용 PC에서 만료일이 보이지 않고 키가 쌓이지 않게). 자정을 넘기면 /visa-plan 진입 때 다시 불러온다
  - [x] S12-d. 화면: 상단 띠
  - 나중(S12-d 리뷰): 띠 role=status를 문구 span으로만(링크·버튼 제외) · 닫기 버튼 이름 "띠 닫기" → "비자 일정 알림 닫기" + 닫은 뒤 포커스 · e2e .visa-banner-text 클래스 셀렉터 → getByText · visaBanner.test.ts 저장소 막힘 테스트를 vi.stubGlobal로(node localStorage 유무에 기대지 않게) · Provider context value useMemo · needLogin 실패 상태 주석 · 띠가 header·main 랜드마크 밖
  - [x] S12-e. EC2 배포(S11 리뷰 수정·UI2·UI3 묶음 확인)
  - [x] S12 리뷰. 파트 전체 리뷰 권장 반영(2026-11-11 사용자 결정: 1·2·4만): ① 할 일 줄의 D+n·"지남"·흐림이 "마감 지남"처럼 읽힘(운영 확인 때 사용자가 D+105를 헷갈림) → "만료 n일 전부터" 표기, 지금 단계 줄 강조 ② 모르는 stage면 띠가 예외로 화면 전체가 죽음 → 문구 없으면 띠 안 그림 ④ DELETE CSRF 403·미인증+범위 밖 날짜 403 순서 테스트. 다음 배포에 포함
  - 나중(S12 파트 리뷰): ③ 120·30·7을 바꿀 때 같이 볼 프론트 파일(띠 문구·types 주석·단계 클래스) 목록을 VisaMilestone 주석에 · ⑤ 띠를 닫으면 그 단계 끝날 때까지 안 뜸(D-7에 닫으면 만료까지 조용, URGENT만 하루 단위로 다시 띄울지 사용자 판단) · 인증 풀린 회원은 화면에서 일정 조회·삭제 불가(서버는 허용) · 자정 재조회 중 저장하면 늦은 GET이 덮을 수 있음 · 로그아웃 직후 같은 계정 재로그인 시 inFlight로 띠 안 뜰 수 있음 · 닫기 해시는 가리개일 뿐(주석 정확히), 로그아웃 후 키 남음 · 만료된 일정 자동 정리 없음 · api.md 28줄(일정을 "글에만" 문장에 묶음)·1262줄 검사 순서에 회원 없음 401 빠짐 · (수정 리뷰) 모르는 stage면 진짜 현재 줄이 "지난 단계"로 흐려짐("daysLeft ≤ 0인 마지막 줄 = 현재"로 판단하는 방법) · dueDate가 날짜 형식이 아니면 "만료 NaN일 전부터"
- [ ] S13. 중고거래·나눔 게시판 (2026-11-11 사용자 결정: 경매 대신. 졸업·귀국하는 선배 → 새로 온 후배 물건 넘기기. 학교 인증·신고 재사용, 결제 없이 직거래만. 연락은 S14 쪽지로(사용자 결정: S13·S14를 다 만든 뒤 한 번에 배포). 단계는 planner가 나눔)
  - 결정(planner 후, 2026-11-11): 새 표 market_items + market 패키지(posts에 칸 추가 안 함, 비자 후기와 같은 방식). 보기는 비로그인 포함 누구나(학교 필터), 올리기는 학교 인증 회원(학교는 인증 학교 복사), 수정·상태 변경은 본인, 삭제는 본인·관리자. 판매자 탈퇴 시 글도 삭제(CASCADE). 가격 판매 1~10,000,000원·나눔 0원, 제목 100자·본문 2000자. 완료는 되돌리지 않음(409, 화면 확인 창). 사진은 S13-g(research 먼저). 채팅·결제·찜·거래 후기·댓글·공감 안 함
  - [x] S13-a. V9(market_items) + 올리기·한 건 보기 (사진 없음)
  - 나중(S13-a 리뷰): 가격에 5000.9·"5000"(문자열)이 오면 조용히 받아질 수 있음(Jackson 기본 변환, 미확인) → 확인 후 400 또는 api.md에 명시 · UserService 탈퇴 잠금 주석의 "FK 위반을 401로 바꾸는 서비스" 목록에 MarketItemService 없음 · Flyway 기준선 테스트가 V9 성공 행·market_items 표를 직접 보지 않음(V6~V8과 같음)
  - [x] S13-b. 목록 + 학교·종류·상태 필터 + 수정·삭제
  - 나중(S13-b 리뷰): S13-c 때 같이 볼 것 — 수정 PUT이 상태 칸까지 UPDATE해서 "읽음 → 다른 요청이 DONE → PUT 커밋"이면 상태가 되돌아감(@DynamicUpdate 또는 @Version 검토), 완료(DONE) 글의 제목·가격·종류 수정을 막을지 사용자 결정(지금은 200) · 목록 쿼리 수 테스트를 비자 후기처럼 정확히(조건 없음 1번, schoolId 2번) · MarketItemRequest 주석 "올리기 요청"(수정에도 씀) · 수정 성공 테스트가 DB content·trade_type·created_at 유지를 안 봄 · "학교는 있는데 글이 없으면 200 []" 테스트 없음 · 비자 후기 수정(VisaReviewService)도 수정 중 삭제 경쟁 시 500 가능(같은 방식으로 고칠 후보)
  - [x] S13-c. 상태 전환 (판매중↔예약중→완료)
  - 결정(S13-c 전, 2026-11-11): 완료(DONE) 글은 수정 PUT 막음(409), 삭제만 가능. 전환은 ON_SALE↔RESERVED, ON_SALE·RESERVED→DONE, 같은 상태·DONE에서 나가기는 409. 판매자 본인만(관리자도 403)
  - 나중(S13-c 리뷰): 동시에 눌러 진 쪽도 "바꿀 수 없는 상태 전환" 문구 → "그 사이 상태가 바뀌었습니다, 새로고침"이 친절(S13-d 화면 때 결정) · 경쟁1 테스트 정리(Future 두 개로, LockRaceSupport에 대기자 수 인자) · updatable=false를 지키는 테스트 없음(서비스가 엔티티 저장을 안 써서 2중 장치)
  - [x] S13-d. 화면 (목록·상세 / 올리기·수정·상태 버튼)
  - 나중(S13-d 리뷰): 가격 굵기 700이 design.md에 없음(500으로 또는 예외 한 줄) · MARKET_DONE_NOT_EDITABLE·MARKET_STATUS_CONFLICT만 마침표로 끝남 · 학교를 골라도 머리말이 "중고·나눔" 그대로(비자 후기처럼 "고려대학교 중고·나눔") · 요청 중 다른 메뉴로 떠나면 응답 뒤 navigate로 끌려감(비자 후기도 같음) · MarketFilters의 Select가 VisaReviewFilters와 거의 같은 코드(세 번째 필터 화면 때 공용으로) · 목록 문구 2개가 ERROR_MESSAGES 밖, REVIEW_·VISA_ 이름 문구를 빌려 씀 · 가격에 "5,000"처럼 쉼표 넣으면 막힘 · 상태 409 뒤 자동 다시 불러오기 · 성공 알림 role=status가 처음 생길 때 일부 스크린리더가 안 읽을 수 있음(항상 있는 빈 영역 방식)
  - [x] S13-e. 신고 연결 (reports 제약 변경)
  - 결정(S13-e 전, 2026-11-11): 중고 글 신고는 같은 학교 인증 회원만(글·댓글 신고와 같은 규칙, 다른 학교 403). V10으로 reports에 market_item_id(FK CASCADE, UNIQUE(market_item_id, reporter_id)) + ck_reports_target "셋 중 정확히 하나". 화면: 상세 [신고] + 관리자 신고 관리에 중고 표시·링크
  - 나중(S13-e 리뷰): 미인증·다른 학교 회원에게 중고 글 신고 버튼이 이유 안내 없이 숨겨짐(게시글은 SchoolGateNotice) · 같은 학교 인증 관리자가 남의 중고 글을 보면 [삭제]·[신고]가 두 줄(.actions 두 묶음) · AdminReportsPage가 모르는 target.type이면 DELETE_PATH undefined로 화면이 죽음(서버 먼저 배포될 때) · Flyway 기준선 테스트가 "기존 신고 행이 V10 새 CHECK를 통과"·V10 성공 행을 직접 안 봄
  - [x] S13-f. 제목 검색
  - 나중(S13-f 리뷰): 화면 maxLength=50은 UTF-16 단위라 이모지는 25개까지(서버는 코드포인트 50, 더 엄격한 쪽이라 400 위험 없음, 붙여넣기는 말없이 잘림) · 주소를 ?q=%20%20으로 직접 열면 0건일 때 "검색 결과가 없습니다"(q를 trim해서 판단) · 검색 form에 바깥과 같은 className="filters" 중첩(툴바 밖 재사용 시 여백) · "50자 이하" 문구의 50이 MAX_SEARCH_LENGTH와 따로 적힘(가격 문구와 같은 관례) · 소문자 "istanbul"로 "İstanbul"이 찾히는지는 DB 로케일에 달림(테스트는 200만 단언)
  - 결정(S13-f 전, 2026-11-11): GET /market-items에 선택 파라미터 q(제목만, 대소문자 무시 부분 일치, %·_·\는 글자 그대로, 앞뒤 공백 자르고 비면 검색 안 함, 50자 넘으면 400), 기존 필터와 AND. 표 변경 없음(글 많아지면 pg_trgm 검토). 화면은 /market 검색창 + 주소 ?q=. 본문·게시판 글 검색·페이징은 안 함
  - [ ] S13-g. 사진 업로드 (research 먼저)
  - 결정(research 후, 2026-11-11): S3 비공개 버킷(Block Public Access 4개 켜기) + 서버가 받아 검사·재인코딩 후 올림(브라우저 직접 업로드 X). EC2는 인스턴스 역할로 접근(액세스 키 없음). 사진 보기는 서버 API로. 한 요청에 1장, 글당 최대 10장, 원본 장당 10MB, JPEG·PNG만(매직 바이트로 판별, HEIC·WebP는 나중), 저장 시 긴 변 2048px + EXIF(GPS) 제거(회전 먼저 반영), 저장 이름 UUID. 새 표 market_item_photos(V11). 글 삭제·탈퇴 전 사진 키를 모아 커밋 뒤 S3 삭제 + 실패분 주기 청소. 개발·테스트는 저장소 인터페이스 + 로컬 디스크 구현(LocalStack·MinIO 안 씀). 의존성 AWS SDK v2(S3) 추가 허용. nginx·Spring 멀티파트 한도(기본 1MB) 함께 올림
  - 결정(planner 후, 2026-11-11, 추천안 9개 그대로): 사진 추가·삭제는 수정 규칙(판매자 본인만, 관리자 403, 학교 인증 불필요, DONE 409) · 순서 바꾸기 안 함(올린 순, 첫 장 대표) · 올릴 때 320px 썸네일도 저장(GET ?size=thumb) · 재인코딩은 형식 유지(JPEG 품질 0.85, PNG는 PNG) · 삭제 대기 표 photo_delete_queue도 V11 · s3 모드 시작 때 HeadBucket 확인 · 실물 S3 확인은 S13-h 배포 직후(액세스 키 안 만듦) · 픽셀 상한 긴 변 10,000px·총 4천만 · 업로드 중간 실패는 글 남기고 수정 화면으로 안내. 이미지 처리 동시 2장(Semaphore), 처리·S3 전송은 트랜잭션 밖
  - 나중(S13-g1 리뷰): 잘린 JPEG는 ImageIO가 회색으로 채워 통과(알려진 한계, 고치면 "Premature end" 경고나 EOI(FF D9)만 보기) · PhotoStorage contentType 약속 모호(로컬은 버리고 get에서 추측, S3는 저장값 → g2 전에 "DB 값만 믿기"로 정하기, guessContentType이 detectFormat과 중복) · 저장 실패 공통 예외(PhotoStorageException) 없음(g2·g4 전에) · key 검증을 허용 문자 정규식으로(`a/../b`·Windows 끝 점·NUL 등 OS별 차이) · Files.write 반쪽 파일(임시 파일 + ATOMIC_MOVE) · key가 폴더면 get·delete 500(isRegularFile) · 인코딩이 ImageIO 캐시 임시 파일을 만듦(MemoryCacheImageOutputStream) · 테스트 빈 곳(CMYK·흑백·16비트·팔레트 tRNS·인터레이스 PNG, 프로그레시브 JPEG, PNG 메타데이터 제거, 회전값 0·9·깨진 EXIF, 회전 네 변 픽셀) · 고화소 모드(4800만) 사진이 4천만 상한에 걸림(서브샘플링 뒤 상한 올리기 결정) · null은 CORRUPTED·빈 배열은 UNSUPPORTED_FORMAT · g6에서 LocalPhotoStorage에 @ConditionalOnProperty, 운영 alpine 이미지에서 사진 처리·아이폰 P3 색 확인(S13-h 직후), @ExceptionHandler 관례 정하기(g2), 기본값 ./uploads 중복
  - 나중(S13-g2 리뷰): 창고 put 실패는 500 유지(@ExceptionHandler 없음, S3 일시 오류를 503으로 할지·관례는 g6에서) · g3 GET은 "행은 있는데 파일 없음"을 404로(커밋 예외 시 DB만 남는 드문 경우) · put 뒤 커밋 전 서버가 죽으면 고아 파일(대기 표에도 없음) → g4에서 "put 전에 대기 표에 먼저 적기" 등 검토, deleteQuietly 실패분도 · photo_delete_queue.storage_key UNIQUE·인덱스 없음(g4 정책 정하면 새 V 파일) · TransactionTemplate·락 타임아웃 없음(풀 3개, setTimeout+503 검토) · 처리 자리 대기 5초 동안 요청 스레드 점유, 회원당 업로드 빈도·저장 용량 제한 없음(g6·S13-h 전) · 4천만 픽셀 처리 메모리를 운영 힙과 S13-h에서 확인 · 503에 Retry-After 없음(g5-2) · photos를 목록에서 @JsonInclude(NON_NULL)로 뺌 → g5에서 목록 전용 DTO · 테스트가 서비스 final 필드를 리플렉션 교체(@Primary 테스트 빈이나 서비스 단위 테스트로), processWaitMillis를 @Value 생성자 주입으로 · 테스트 임시 폴더 고정 이름이라 병렬 JVM이면 서로 지움 · 정리 delete 실패 시 원래 예외 유지·단건 쿼리 2번·InterruptedException 경로 테스트 없음 · CSRF 없는 멀티파트도 CsrfFilter가 본문을 파싱한 뒤 403일 수 있음(추정) · max-swallow-size를 빼도 로컬 RANDOM_PORT 테스트가 통과(연결 끊김 재현 안 됨) → 브라우저·nginx 뒤 401·413은 g5·g6에서 실제로 확인
  - 나중(S13-g3 리뷰): **공개 사진 GET이 요청마다 파일 전체를 byte[]로 힙에 올림(동시 제한 없음, PNG는 수 MB 가능) → S13-h 배포 전 결정 필수**(g6에서 PhotoStorage 스트림 + Resource/StreamingResponseBody, 그 전엔 Semaphore나 nginx limit_conn·proxy_cache) · 공개 GET마다 DB 연결 1개(풀 3개, g5-1 썸네일 여러 장 동시 요청 때 확인) · immutable 1년 캐시라 지운 사진도 이미 본 브라우저엔 남음(숨기기·블라인드가 생기면 헤더·주소 다시 설계, api.md 35번에 한 줄) · 테스트 빈 곳: 사진 GET에 Set-Cookie 없음 단언, 끝 슬래시·대문자 경로 401, HEAD 401(api.md에도), DELETE 잠금 대기 중 DONE→409·글 삭제→404, 창고 읽기 오류 500, 글 id 숫자 아님 400 · 커밋 뒤 파일 삭제 실패·서버 다운 고아 파일은 로그에만(g4: 같은 트랜잭션에서 photo_delete_queue에 넣고 지우면서 빼기 등, Windows는 읽는 중 삭제 실패도) · DELETE 트랜잭션 타임아웃(g2 항목과 함께) · MarketItemPhotoService 머리 주석이 올리기만 말함, TransactionTemplate 요청마다 생성 · SecurityConfig 사진 GET 규칙 아래 한 칸짜리 로그인 전용 GET을 만들면 공개됨(주의 문구)
    - [x] S13-g1. 사진 처리기(매직 바이트·회전·축소·EXIF 제거) + 저장소 인터페이스·로컬 디스크 구현 (API·DB 변화 없음)
    - [x] S13-g2. V11(market_item_photos·photo_delete_queue) + POST /market-items/{id}/photos + 글 응답 photos + 멀티파트 한도
    - [x] S13-g3. GET …/photos/{photoId}(공개) + DELETE …/photos/{photoId}
    - [ ] S13-g4. 글 삭제·탈퇴 때 파일 정리(커밋 뒤) + 주기 청소(@Scheduled)
    - [ ] S13-g5-1. 화면: 상세·목록 사진 보기
    - [ ] S13-g5-2. 화면: 올리기·수정에서 사진 추가·삭제
    - [ ] S13-g6. S3 구현(AWS SDK v2) + nginx 한도 + compose/.env + deploy.md (build 전 사용자 콘솔: 버킷·IAM 역할)
  - [ ] S13-h. EC2 배포 → S14 쪽지까지 끝낸 뒤 한 번에
- [ ] S14. 쪽지 (중고 글의 "판매자에게 쪽지"부터. 실시간 채팅 아님)
- [ ] S15. 글 번역
- [ ] S16. 화면을 React에서 Flutter(웹)로 교체 (2026-11-11 사용자 결정: 앱 추가가 아니라 웹도 Flutter로 교체. 서버 API는 그대로. 시작은 S14 배포(S13-h) 뒤로 사용자 결정, S15와의 순서는 그때 정함. research 먼저: Flutter 웹 렌더러·첫 로딩 크기, 같은 주소 nginx 서빙이면 쿠키·CSRF 그대로 되는지, 브라우저 자동 테스트(지금 Playwright e2e 154개) 대체 방법, Docker·CI 빌드, 접근성·텍스트 선택. 단계는 planner가 나눔. React 코드는 교체 끝날 때까지 유지)
- [ ] (보류, 2026-11-11) 비자 일정을 내 캘린더에 추가(.ics). S12 알림이 사이트 안 띠뿐이라 사이트에 와야만 보이는 한계를 휴대폰 캘린더 알람으로 보완하는 안. planner 계획까지 나옴(서버 GET /users/me/visa-plan.ics, DB·의존성 변경 없음, 하루 종일 일정 + 당일 9시 알람, 지난 할 일 제외, 구독 주소는 http라 안 함). 사용자가 일단 빼기로 함. 다시 하면 research 먼저: 구글 캘린더가 파일 안 알람(VALARM)을 무시하는지, http 다운로드 경고, 아이폰 Safari·안드로이드의 .ics 처리
- [ ] (나중, 알려진 한계) 여러 탭이 동시에 재발급하면 같은 리프레시 토큰을 두 번 써서 모든 탭이 로그아웃될 수 있다. 탭 하나 안에서는 재발급을 하나로 모으지만 탭끼리는 못 모은다. 해결 후보: 백엔드에서 방금 회전한 토큰을 몇 초 봐주기, 또는 BroadcastChannel로 탭끼리 알리기 (Web Locks는 HTTPS 필요)

## 끝난 일
| 날짜 | 단계 | 한 줄 요약 | 커밋 |
|---|---|---|---|
| 2026-11-01 | S0 | 프로젝트 뼈대 + 헬스체크 API + 첫 EC2 배포 | 1efaddc |
| 2026-11-01 | S1-a | DB 연결 (PostgreSQL, 로컬 compose, Testcontainers 테스트 DB) | 8185951 |
| 2026-11-01 | S1-b | 게시글 작성 + 조회 API | 2868f00 |
| 2026-11-01 | S1-c-1 | 백엔드 CORS 전역 설정 | 63d4a1e |
| 2026-11-01 | R1 | 롬복 도입 (리팩터링, 기능 변화 없음) | 0662602 |
| 2026-11-01 | S1-c-2 | 게시판 화면 (React + TypeScript: 목록, 상세, 작성) | 9f5c26c |
| 2026-11-02 | S1-d | 프론트엔드 배포 (nginx 리버스 프록시) | ffb04a9 |
| 2026-11-02 | S2-a | 게시글 수정 + 삭제 API | bad651a |
| 2026-11-02 | S2-b | 게시글 수정 + 삭제 화면 | 1745a2f |
| 2026-11-03 | S2-c | 수정 + 삭제 EC2 배포 | f63f56c |
| 2026-11-03 | S3-a | CI: push/PR 때 백엔드 테스트 + 프론트 lint·빌드 | 8208f81 |
| 2026-11-03 | S3-b | main push 때 이미지 빌드 + Docker Hub push (latest + SHA) | c6ad16b |
| 2026-11-03 | S3-c | 버튼으로 EC2 배포 (IP 조회 + 러너 IP만 SSH 임시 허용) | b322a6b |
| 2026-11-04 | S4-a | Spring Security 도입 + 회원가입 API (BCrypt, CSRF, STATELESS) | 8955924 |
| 2026-11-04 | S4-b | 로그인·로그아웃·내 정보 (JWT 액세스 토큰 HttpOnly 쿠키, CSRF 유지) | 6f1c9af |
| 2026-11-04 | S4-c | Redis 도입 + 리프레시 토큰(7일, 해시 저장, GETDEL 회전, 로그아웃 시 삭제) | 898b9a9 |
| 2026-11-04 | S4-d | 글과 작성자 연결 + 권한 (조회 공개, 글쓰기 로그인, 수정·삭제 본인만, 기존 글 작성자 없음) | cfa6dd6 |
| 2026-11-04 | S4-e-1 | 화면: Vite 프록시(/api) + CSRF 자동 첨부 + 가입·로그인·로그아웃 + 상단바 | 48f7fa0 |
| 2026-11-04 | S4-e-2 | 화면: 자동 재발급 (401 → refresh → 1회 재시도, 탭 안에서 동시에 하나만, 만료된 화면은 내용 유지) | 0a89b6a |
| 2026-11-04 | S4-e-3 | 화면: 작성자 표시 + 본인 글에만 수정·삭제 버튼 + 403 문구 | 8cbda70 |
| 2026-11-05 | S4-f-1 | 키 교체: AWS 액세스 키·EC2 접속 키(board-ec2)·배포 SSH 키·EC2 DB 비밀번호 + deploy.md 반영 | 6d230e2 |
| 2026-11-05 | S4-f-2 | EC2 배포: DB 백업(서버+PC) → .env(JWT_SECRET·REDIS_PASSWORD)·compose 갱신 → Deploy 성공. 헬스 UP·글 수 유지·옛 글 작성자 없음·외부 포트 닫힘·자동 재발급 확인 (화면 가입·버튼·IAM/GitHub 세션 점검은 생략) | (이 커밋) |
| 2026-11-05 | S5-a | 닉네임 추가(가입 필수·중복 409·2~20자) + 응답에 nickname + 글 작성자는 {id, nickname} + /auth/me DB 확인(회원 없으면 401) | (이 커밋) |
| 2026-11-05 | S5-b | 회원별 리프레시 토큰 목록(Redis Set, TTL은 늘어날 때만 갱신) + revokeAll(그 회원 모든 기기 로그아웃 준비). API 변화 없음 | (이 커밋) |
| 2026-11-05 | S5-c | 닉네임 변경(PUT /users/me/nickname, 중복 409) + 비밀번호 변경(PUT /users/me/password, 현재 비번 틀리면 400, 성공 시 모든 기기 로그아웃). 한 칸만 UPDATE해 동시 요청 덮어쓰기 방지 | (이 커밋) |
| 2026-11-05 | S5-d | 회원 탈퇴(DELETE /users/me, 비밀번호 확인, 완전 삭제 + 글은 작성자 null, 모든 기기 로그아웃). 회원 행 잠금으로 글쓰기·탈퇴 경쟁 방지, 글 수정이 작성자 칸을 다시 쓰지 않게 함 | (이 커밋) |
| 2026-11-05 | S5-e-1 | 화면: 가입에 닉네임 칸(형식·길이 검사는 서버 400, 자음·모음만 불가 안내) + 글 작성자·상단바를 닉네임으로 표시 | 52844c5 |
| 2026-11-05 | S5-e-2 | 화면: 내 정보 페이지(/me) — 닉네임 변경(상단바 즉시 갱신), 비밀번호 변경·탈퇴(상태 비우기+이동을 한 transition으로 묶어 RequireLogin 끼어들기 방지, 500은 "이미 처리됐을 수 있음" 안내) | 8ce4770 |
| 2026-11-05 | S5-f | EC2 배포: 집 PC 접속 키 추가(보안그룹 SSH laptop·home-pc) → DB 백업(서버+PC) → 기존 회원 1명 삭제(글 3개는 작성자 없음으로 유지) → Deploy 성공. 헬스 UP, 화면에서 가입·닉네임·비밀번호 변경·탈퇴 이동 확인 | 61ba22a |
| 2026-11-05 | S6-a | Deploy 전 서버 DB 자동 백업(pg_dump → ~/board/backups, 600/700, 3가지 검사·디스크 50MB 검사 실패 시 배포 중단, 10개 보관, 3분 제한) + deploy.md 되살리기 절차 완성 | bbc3dc5 |
| 2026-11-05 | S6-b-1 | Flyway 도입(V1 = 운영 구조 그대로, 제약 이름까지 diff 0) + ddl-auto=validate + baseline-on-migrate. 테스트 267개 통과, 표 있는 DB에서 BASELINE 1행·데이터 유지 확인. CLAUDE.md에 V 파일 규칙 | 83b7a8a |
| 2026-11-05 | S6-b-2 | 표가 이미 있는 DB 기준선 인정: 로컬 DB를 운영 구조로 갈아끼워 bootRun 리허설(BASELINE 1행, 재시작해도 1행, health UP) + 자동 테스트 3개(첫 시작·데이터 보존·두 번째 시작, V1 파일 직접 실행). 테스트 270개 | 16c2989 |
| 2026-11-05 | S6-c | EC2 배포: Deploy 자동 백업 첫 실전(600 권한) → 운영 DB BASELINE 1행, 데이터 유지, 가입·글쓰기 확인. Deploy 재실행해도 백업만 1개 늘고 기록장 1행 그대로. deploy.md에 Flyway 문제·이전 이미지 되돌리기 절 추가 | 25bae86 |
| 2026-11-05 | S6-c 후속 | baseline-on-migrate·baseline-version 삭제(기록장 없는 DB는 거절). 테스트: 기록장 없는 DB 거절 + 운영 상태(BASELINE 있음) 정상, 총 269개. 로컬 bootRun health UP. deploy.md에 옛 백업용 일회성 baseline 절차(override 파일) | (이 커밋) |
| 2026-11-07 | S7-a | V2(학교 표 5곳, 글에 학교·말머리, 기존 글은 학교 빈칸·잡담, 말머리 기본값 CHAT) + 글 API에 학교·말머리 + GET /schools·/categories. 학교 순서는 DB 언어 설정과 무관하게 서버에서 가나다순 정렬. 테스트 285개 | 5d7acf3 |
| 2026-11-07 | S7-b | GET /posts에 schoolId·category 선택 조건(Specification으로 넘긴 조건만 AND). 없는 학교 404, 형식 오류 400, 빈 값은 조건 없음. 테스트 296개 | 52f3c02 |
| 2026-11-07 | S7-c | 화면: 목록에 학교·말머리 드롭다운(주소 기준, 새로고침·뒤로 가기 유지) + 목록·상세에 학교·말머리 표시(없으면 학교 미지정) + 잘못된 주소 안내와 임시 선택지 + 드롭다운 목록 실패 안내 | 2838214 |
| 2026-11-07 | S7-d | 화면: 글쓰기에 학교·말머리 선택(안 고르면 안내, 입력 유지) + 수정은 말머리만 변경, 학교는 글자로 표시(옛 글은 학교 미지정) + 목록 실패 안내 | 1eb242e |
| 2026-11-07 | S7-e | EC2 배포: Deploy 자동 백업 → V2 적용(기록장 1·2 성공, 학교 5곳) → 글 5개 유지·학교 미지정, 화면에서 글쓰기·거르기 확인. 임시 DB로 S6 코드 되돌리기 시험 후 deploy.md 반영(bbc4322) | (이 커밋) |
| 2026-11-07 | S8-a | V3(comments·post_likes 표, 글 삭제 시 CASCADE) + 댓글 API 4개(목록 공개, 작성 로그인 201+Location, 수정·삭제 본인만 403, 작성 중 글 삭제/탈퇴는 FK 이름으로 404/401 변환). 탈퇴 시 댓글은 작성자 null로 남김. 테스트 326개 | 12359e0 |
| 2026-11-07 | S8-b | 공감 API 3개(누르기 201, 중복 409는 UNIQUE로 동시 요청까지 방어, 취소는 DELETE 행 수로 404, 내가 눌렀는지) + 글 응답에 commentCount·likeCount(표 칸 없이 조회 때 group by로, 목록은 쿼리 3번 고정). 테스트 346개 | 87a8c36 |
| 2026-11-07 | S8-c | 화면: 상세에 댓글 영역(목록·작성·인라인 수정·삭제, 비로그인은 로그인 링크)과 공감 버튼(로그인일 때만 likes/me 조회, 응답 숫자로 갱신, 409/404는 상태 동기화) + 목록에 "댓글 N · 공감 N". 브라우저에서 확인 | 520e07a |
| 2026-11-07 | S8-d | EC2 배포: CI(346개·images) 초록 → Deploy 자동 백업 → V3 적용 → 글 6개 그대로, 목록에 댓글·공감 수, 상세에 댓글 영역·공감 버튼, 댓글 API 200 확인. deploy.md에 V3 후 S7 이미지 되돌리기 안내(78ff5d0) | e83886b |
| 2026-11-07 | S8 후속 | 전체 리뷰 반영: FK/UNIQUE 위반 해석을 common/ConstraintViolations 하나로(세 서비스 복붙 제거), ApiErrors(404·401 공장), 탈퇴·글 삭제 vs 댓글·공감 작성 경쟁 테스트 4개(LockRaceSupport) + 유령 토큰·CSRF·PUT 응답 숫자·post_likes CASCADE 테스트, api.md 절 순서·탈퇴 항목 정리, deploy.md에 DB 메시지 언어 전제. 테스트 357개 | (이 커밋) |
| 2026-11-08 | S9-a | V4(users.role USER/ADMIN, 기존 회원 USER, 지정은 DB SQL로만) + 토큰 role 클레임(없거나 이상하면 USER) + 로그인·재발급·내 정보 응답에 role + 글·댓글 삭제 "본인 또는 관리자"(관리자는 AccessGuard가 DB 재확인: 없음 401·강등 403, 수정은 관리자도 403) + 공감 취소·조회 유령 토큰 401 + 회원 재확인 AccessGuard로 통일. 테스트 399개 | f027b3b |
| 2026-11-08 | S9-b | V5(reports 표: 글·댓글 중 정확히 하나, 대상 삭제 시 CASCADE, 신고자 탈퇴 시 SET NULL, 같은 회원·같은 대상 UNIQUE) + 신고 API 2개(201, 사유 5개 + 상세 선택 300자, 자기 것 400, 중복 409는 동시 요청까지 방어, 저장 직전 삭제·탈퇴는 FK 이름으로 404/401) + GET /report-reasons 공개. ApiErrors.commentNotFound 공용화. 테스트 445개 | 3592d9e |
| 2026-11-08 | S9-c | 관리자 신고 API: /admin/**는 토큰 role 클레임으로 hasRole("ADMIN") + AccessGuard.requireAdmin이 DB 재확인(없음 401·강등 403). GET /admin/reports?status(기본 PENDING, 대상 미리보기·작성자·신고자 포함, left join fetch로 신고 수와 무관하게 쿼리 고정) + PUT /admin/reports/{id}/dismiss(조건부 UPDATE로 동시 기각 시 하나만 200·나머지 409, 없으면 404). 테스트 475개 | c68d2fe |
| 2026-11-08 | S9-d | 화면: 글·댓글 신고 양식(사유 목록·상세 300자, 201/409/404/400 안내, 로그인 풀리면 부모가 안내), 관리자는 남의 글·댓글 삭제 버튼, 상단바 "신고 관리"(관리자만), /admin/reports(미처리·기각 탭, 삭제·문제 없음, 처리 후 재조회까지 버튼 잠금). 본인 삭제 403은 새로고침 안내. 브라우저 확인 | e670d23 |
| 2026-11-08 | S9-e | EC2 배포: CI(475개·images) 초록 → Deploy 자동 백업 → V4·V5 적용(기록장 1~5 success, 기존 회원 1명 USER) → 운영 DB SQL로 관리자 1명 지정(UPDATE 1). deploy.md에 관리자 지정·해제, V4/V5 후 S8 이미지 되돌리기 메모(57c36d4) | 7041c4f |
| 2026-11-08 | S9 후속 | 전체 리뷰 반영: 글·댓글 삭제를 JPQL 한 문장 + 행 수로(본인·관리자 동시 삭제 시 늦은 쪽 500 → 404, 경쟁 테스트 4개), role 판단을 UserRole.fromClaim 하나로, JSON 숫자 enum({"reason":0}, {"category":0}) 400(spring.jackson.datatype.enum.fail-on-numbers-for-enums). 테스트 489개. 재배포 안 함(다음 Deploy에 포함) | 42bf050 |
| 2026-11-08 | UI1-a | 화면 공용화(모양·기능 변화 없음): 에러 문구 describeError/errorText 한 곳, 안내 Notice(error=alert, success·info=status), isAdmin, Loading, SITE_NAME, 인라인 style 0곳(CSS 클래스로), 디자인 기준 docs/design.md. 브라우저에서 문구 6장면 동일 확인 | 4535cc9 |
| 2026-11-08 | UI1-b·c | 디자인: 항상 어두운 화면(청자 계열 깊은 녹회색 바탕, color-scheme dark), 청자색 포인트·IBM Plex Sans KR·CSS 변수(hex는 :root만), 버튼 3종·안내 3색·뱃지, 학교 이름이 바뀌는 게시판 머리말, 흰 판 안 행 목록(그림자 없음), 로그인·가입 카드, 관리자 신고 표, 날짜 "11월 8일 15:19". 글자 대비 전 조합 4.5:1 이상, 375px 가로 스크롤 없음, 작은 버튼 36px | 557f49a |
| 2026-11-08 | UI1-d | 공용 확인 창: window.confirm 4곳(글·댓글 삭제, 탈퇴, 관리자 삭제)을 <dialog> 하나(useConfirm 훅)로. 처음 포커스는 취소, Esc=취소, 화면이 바뀌면 취소로 닫힘(뒤로가기 후 엉뚱한 삭제 방지). 브라우저 확인 | e94ca84 |
| 2026-11-08 | UI1-e | EC2 배포: CI(489개·frontend·images) 초록 → Deploy 자동 백업·헬스체크 성공. 운영에서 어두운 새 화면·확인 창 확인. S9 후속(42bf050: 동시 삭제 404, 숫자 enum 400)도 같이 나감. DB 변경 없음 | (이 커밋) |
| 2026-11-09 | S10-a | V6(school_email_domains: 5개 학교 도메인 6개, 소문자만 CHECK, 학교 id는 이름으로 찾음 / users에 verified_school_id·verified_at·school_email_hash UNIQUE, 기존 회원 NULL) + 로그인·재발급·내 정보·닉네임 변경 응답에 verifiedSchool(EntityGraph로 한 쿼리) + GET /schools에 emailDomains(새 SchoolDetailResponse, 글 응답 school은 그대로, 쿼리 2번 고정). 동작 변화 없음. 테스트 509개 | 94ac0cf |
| 2026-11-09 | S10-b | POST /users/me/school-verification/code(202): 메일 앞뒤 공백 제거·소문자 → 학교 도메인 없으면 400 → 이미 인증·다른 계정이 쓴 메일 409 → Redis Lua 한 번에 60초 쿨다운·하루 10회(429) 검사와 코드 저장(코드는 해시만, 10분, 옛 코드 무효) → 발송(지금은 로그). 발송 실패 시 내 코드일 때만 되돌려 바로 재요청 가능(500). 테스트 527개 | c0de131 |
| 2026-11-09 | S10-c | POST /users/me/school-verification(200 UserResponse): 이미 인증 409(코드 소모 안 함) → Redis Lua 한 번에 확인(틀림 400·attempts+1 / 없음·만료·5번째 틀림 폐기 410) → 일치하면 키 삭제 후 조건부 UPDATE(verified_school이 비었을 때만)로 학교·시각·메일 해시 저장, 같은 메일 다른 계정은 UNIQUE로 409. 화면이 상태코드로 "다시 입력"/"다시 받기"를 구분하게 410 분리. 테스트 548개 | 122bc7f |
| 2026-11-09 | S10-d | 인증 전 제한: AccessGuard.requireVerified(미인증 403)·requireSameSchool(다른 학교·학교 미지정 글 403)를 글쓰기·댓글·공감·글/댓글 신고 5곳에 적용(관리자도 예외 없음). 수정·삭제·공감 취소·GET·/admin은 그대로. 쿼리 수 그대로(댓글 신고는 글·학교 함께 읽기). 두 403은 본문으로 구분 불가 → 화면이 verifiedSchool로 먼저 막음(api.md). 기존 테스트는 인증 회원 + 같은 학교 글로 준비 변경. 테스트 568개 | b6d521f |
| 2026-11-09 | S10-e | Gmail SMTP 실제 발송: spring-boot-starter-mail 추가, MAIL_MODE=smtp일 때만 SmtpVerificationMailSender(한/영 본문), 필수값(MAIL_HOST·USERNAME·PASSWORD·FROM) 비면 시작 실패, 발송 실패 예외에 주소·코드 안 남김(원인 클래스명만), 메일 헬스체크 끔, 타임아웃 5·10초. compose·.env.example·deploy.md에 MAIL_*. 로컬에서 실제 메일 수신(첫 메일은 스팸함) → 코드 확인 200까지 확인. 테스트 584개 | f6c8dd5 |
| 2026-11-09 | S10-f | 화면: 내 정보 "학교 인증"(메일 → 6자리 코드 → 완료 뱃지, 60초 카운트다운은 끝나는 시각 기준, 스팸함·10분 안내, "이미 코드를 받았어요"와 429 시 코드 단계로, 410은 메일 단계로, 409는 /auth/me 재조회), 상단바 "인증" 뱃지(학교 이름은 화면 읽기용 글자), 글쓰기는 미인증이면 안내·인증이면 학교 고정, 글 상세는 인증 학교 글이 아니면 댓글·공감·신고 막고 안내(이미 누른 공감 취소는 가능). 상태코드로 문구. 브라우저 확인(400·410 아님·새로고침 후 이어서 인증·즉시 반영·다른 학교/미지정 글·공감 취소·375px) | 73bd1c1 |
| 2026-11-09 | S10-g | 코드 발송 제한 추가: 같은 계정·같은 메일 하루 2회(남의 메일을 막으려면 계정 3개 이상 필요) + 같은 메일 하루 5회(계정 합산, 메일 폭탄 방지). Redis Lua 한 번에 쿨다운 → 회원 하루 10회 → 계정·메일 2회 → 메일 5회 검사(걸리면 무변경, 429), 키는 메일 SHA-256만, 발송 실패 시 내 코드일 때만 되돌림. deploy.md에 막혔을 때 운영자가 키 지우는 법. 테스트 593개 | 31948d2 |
| 2026-11-09 | S10-h | EC2 배포(집 PC): 테스트 593개·CI(frontend·backend·images) 초록 → 운영 .env에 MAIL_* 6줄(smtp) + 바뀐 compose scp(백업 .bak-20261109) → Deploy 성공. V6 success, 글 6·댓글 2·회원 2 유지, 기존 회원 전원 미인증, 앱에 MAIL_* 6개 전달 확인. 운영 DB에 gmail.com을 잠깐 연세대 도메인으로 넣고 테스트 계정으로 실제 Gmail 발송 → 인증 → 학교 고정 글쓰기 확인 후 도메인 삭제·계정 탈퇴로 원상복구 | (이 커밋) |
| 2026-11-09 | S10 리뷰 | 파트 전체 리뷰(필수 없음) 중 S11 전 권장 반영: 발송 API로 받은 코드를 확인 API에 넣어 DB 해시(독립 계산)·/auth/me·Redis 키 삭제까지 잇는 흐름 테스트 1개, api.md 탈퇴에 "학교 인증 정보도 지워져 같은 메일로 새 계정 재인증 가능(S11 기획 때 재결정) · Redis 인증 키는 TTL로 사라짐" 명시. verified_school_id 인덱스는 S11 V7에서. 테스트 594개 | 16c9c7b |
| 2026-11-09 | S11-a | V7(visa_reviews: 작성자 null 허용·학교 NOT NULL 스냅샷·비자/신청/결과 CHECK·결과일≥접수일 CHECK, 인덱스 통계용(school_id, visa_type, application_type)·author_id·users.verified_school_id) + VisaReview 엔티티(author updatable=false)·enum 3개 + GET /visa-review-options(공개, 한글 이름, enum 순서). 동작 변화 없음. 테스트 609개 | 7e58d81 |
| 2026-11-09 | S11-b | POST /visa-reviews(201, 인증 회원만, 학교는 작성자 인증 학교 자동·본문 schoolId 무시, 검사 순서는 글쓰기와 같게 회원 401 → 미인증 403 → 날짜 400, 날짜는 접수일 ≤ 결과일 ≤ 서울 오늘(Clock 빈 도입), 본문 선택 2000자·NUL 금지, 저장 직전 탈퇴는 fk_visa_reviews_author → 401) + GET /visa-reviews/{id}(공개, 쿼리 1번, processingDays 계산) + 탈퇴 시 후기 작성자 null(학교 유지). {code,name}은 VisaOptionResponse 하나로. V7 주석의 "S11-c"는 S11-b에서 처리. 테스트 634개 | cce83c6 |
| 2026-11-09 | S11-c | GET /visa-reviews(공개, schoolId·visaType·applicationType 넘긴 것만 AND, 최신순, 없는 학교 404, 쿼리 1번 + 학교 확인 1번) + PUT /visa-reviews/{id}(본인만·관리자도 403, 입력 400 → 404 → 403, 학교·작성자·작성일 불변) + DELETE(본인·관리자 DB 재확인, 동시 삭제 늦은 쪽 404). 작성·수정 DTO는 VisaReviewRequest 하나로. 수정·삭제는 학교 인증 불필요. 테스트 674개 | (이 커밋) |
| 2026-11-09 | S11-d | GET /visa-reviews/stats(공개, 학교×비자×신청 조합별 count·avgDays 소수 1자리 HALF_UP, 승인·불허 안 나눔·불허도 평균 포함, 0건 조합 행 없음, 엔티티 안 읽는 네이티브 group by 1번 + schoolId면 학교 확인 1번, 정렬은 자바에서 학교 가나다 → id → 비자 enum 순 → 신청 enum 순). 거르기는 목록과 공용(VisaReviewFilter·checkedFilter), 평균은 BigDecimal 그대로 반올림, 학교 404는 ApiErrors.schoolNotFound로 모음. api.md 23번. 리뷰 필수 없음·권장 4개 반영. 테스트 699개 | (이 커밋) |
| 2026-11-09 | S11-e-1 | 비자 후기 화면(읽기): 상단바 "비자 후기" 링크(모두) + /visa-reviews 목록(학교·비자·신청 종류 필터는 주소 쿼리, 모르는 값 임시 항목, 학교 고르면 머리말·학교 뱃지 생략, 결과 뱃지 승인 청자·불허 회색) + /visa-reviews/:id 상세(결과·학교 뱃지, 접수→결과 "2026년 9월 1일"(formatDay, 문자열 직접 쪼갬)·처리 일수, 빈 본문 안내) + 하이코리아 안내 배너(URL 상수 하나, 새 탭). 필터·선택지 훅은 통계 화면과 공용(학교·선택지 실패를 따로 기억). design.md에 결과 뱃지·날짜 규칙. 백엔드 변경 없음, lint·build·브라우저 확인 | (이 커밋) |
| 2026-11-09 | S11-e-2 | 비자 후기 작성·수정·삭제 화면: 작성·수정 공용 폼 VisaReviewForm(학교 뱃지 고정·schoolId 안 보냄, 선택지 3개 + 접수·결과일(max=서울 오늘) + 본문 2000자, 빈 칸은 요청 없이 안내, 순서는 서버 400, 저장 문구는 부모가 errorTable로), /visa-reviews/new(미인증 안내, 세션 만료로 user가 비어도 lastSchool로 폼·입력 유지 + 재로그인 안내), /visa-reviews/:id/edit(숫자 id 비교, 선택지 실패 시 지금 값으로 저장, 403은 본인이면 새로고침 안내), 상세 수정=본인·삭제=본인·관리자(확인 창, 404 "이미 삭제된 후기"), 목록 "후기 쓰기". 선택지 훅 useVisaReviewOptions 분리(작성·수정은 /schools 안 부름). 연습 계정 4개로 브라우저 확인 | 08169dc |
| 2026-11-09 | fix 글쓰기 | S11-e-2 확인 중 찾은 같은 버그: 글쓰기 폼을 채운 뒤 세션 만료로 저장이 401이면 user가 비어 "미인증" 안내로 바뀌며 제목·내용이 사라졌다 → 마지막 인증 학교(lastSchool)를 기억해 폼 유지 + 재로그인 안내(비자 후기 작성과 같은 방식). 미인증 안내는 그대로. PostEditPage는 user를 안 써서 해당 없음. 나중: 두 화면의 같은 6줄을 useVerifiedSchool 훅으로 | dfcdc42 |
| 2026-11-09 | S11-e-3 | 비자 후기 통계 화면 /visa-reviews/stats(누구나): "후기 / 통계" 링크 탭(VisaReviewTabs, 주소 쿼리 그대로 들고 가 필터 유지, aria-current), 공용 필터 재사용, 표 학교|비자|신청|건수|평균(숫자 열 오른쪽 정렬, "3건"·"19.0일" toFixed(1), 학교 필터 시 학교 열 숨김, 375px는 표 안쪽만 가로 스크롤, 숨은 caption), 힌트 "건수가 적으면…승인·불허 모두 포함", 빈 결과·404·400 안내. design.md에 표·링크 탭 규칙. 나중: 목록·통계 요청/에러 문구 중복을 훅으로(세 번째 사용처 생길 때), 탭 쿼리는 필터 3개만 골라 붙이기 | (이 커밋) |
| 2026-11-10 | S11-f | EC2 배포: CI(699개·frontend·images) 초록 → Deploy 자동 백업·헬스체크 성공(V7 적용). 운영에서 선택지·목록·통계 API와 화면 확인, 기존 회원 wntjrdka를 SQL로 연세대 인증(운영 테스트 계정으로 계속 사용, gmail 도메인 열기 없이) → 후기 작성(학교 자동·처리 2일·통계 1건 평균 2.0) → 삭제 후 목록·통계 빈 상태·404 확인. EC2 재시작 후 앱 자동 실행 확인. deploy.md에 V7 후 S10 이미지 되돌리기(후기 쓴 회원 탈퇴 500 함정) | 1621d20 |
| 2026-11-10 | S11 리뷰 | 파트 전체 리뷰 필수 반영: 후기 날짜 하한(접수일은 서울 오늘 기준 최근 5년 이내, 처리 기간 365일 이하, 작성·수정 모두 400) — 1900년 접수일 한 건으로 통계 평균이 수만 일로 튀거나 음수 연도가 DB 범위를 넘어 500 나던 문제. 권장 반영: 탈퇴 후 통계 건수·평균 유지 테스트, users를 가리키는 FK가 ON DELETE 또는 탈퇴 시 비우기로 전부 처리되는지 감시 테스트. 중복 작성은 알려진 한계로. api.md 18·21번. 재배포 안 함(다음 Deploy에 포함). 테스트 708개 | (이 커밋) |
| 2026-11-10 | UI2-a | 프론트 단위 테스트 도입: Vitest 5.1.0(devDependency, Vite 설정 그대로 씀), `npm test`=vitest run(node 환경, src/**/*.test.ts만). 에러 문구(describeError 6분기·errorText 표 적중·빈 문자열 문구·공용 상수 3개), 날짜(formatDay·formatDate 가짜 시계, TZ=UTC도 같음), isAdmin·canActInSchool 고정 25개. 기존 src 코드 변경 0줄. CI frontend 작업에 린트→단위 테스트→빌드. 문구·401 분기·시간대를 일부러 망가뜨리면 빨간불 확인. 이 PC는 보안 정책으로 oxlint 로컬 실행 불가(CI에서 확인) | (이 커밋) |
| 2026-11-10 | UI2-b-1 | 브라우저 시운전 도입: @playwright/test 1.65(devDependency), `npm run test:e2e`(e2e/*.spec.ts만, Vite 서버를 5174 전용 포트로 직접 띄움). 서버 응답은 page.route 가짜 응답(mockApi: docs/api.md 모양·types.ts 타입, api.override로 상태코드 덮어쓰기, 준비 안 한 /api 요청은 진행 중 요청이 끝난 뒤 검사해 실패). 비로그인 목록·로그인·상세 3개, 백엔드 없이 약 6초. 이 PC는 보안 정책이 Playwright가 내려받은 Chromium을 막아 로컬은 정식 Edge(channel msedge), CI는 기본 Chromium. 일부러 문구·요청·작성자 표시를 망가뜨리면 빨간불 확인. 기존 src 변경 0줄 | (이 커밋) |
| 2026-11-10 | UI2-b-2 | CI에 e2e 작업 추가: Node 24, npm ci → playwright install --with-deps chromium → test:e2e(CI=true라 기본 Chromium·재시도 2), 실패 시 frontend/playwright-report 업로드, images의 needs에 e2e를 넣어 e2e가 실패하면 이미지 push 막음. YAML 파싱·로컬 일반/CI 모드 3개 통과·일부러 망가뜨리면 종료 코드 1 확인. 실제 러너 동작은 push 후 Actions에서 확인 | 390ec33 |
| 2026-11-10 | UI2-b-3 | 에러 문구 e2e 8개(error-messages.spec.ts): 글 404·목록 500/404(학교 999 쿼리 확인)·요청 끊김·로그인 401/403·본인 글 삭제 401+재발급 401 "다시 로그인"(주소 유지)·후기 404. 문구는 src import 없이 문자열 고정, role=alert 전체 일치(앞뒤 글자 붙어도 실패, alert 둘이면 실패). mockApi에 {abort: true}(연결 끊김), 본문 없는 응답, likes/me 기본 응답. core-flow 작성자 셀렉터를 article 안으로. 문구 4곳·접두어 붙이기·기본 응답 삭제로 빨간불 확인. e2e 11·단위 25. UI2 완료, CLAUDE.md 명령어에 npm test·test:e2e | 9db33a4 |
| 2026-11-10 | UI3-a | 옮길 에러 문구 지킴이 보강: 에러 문구 e2e 8→31장면(전체 e2e 11→34). 삭제 403 두 갈래·404·500, 가입 403, 글 상세 500, 글 목록 400, 드롭다운·선택지 실패, 비자 목록·통계 404/400, 후기 상세 500, 댓글 403(학교 인증)·삭제 404, 공감 409, 글 수정 500/400, 신고 404(글·댓글), 비자 작성 400, 재발급 끊김(로그인 유지). 모두 role=alert 전체 일치, src 변경 0. 도우미 minePost·loginVerified, mockApi 정리(provide 이름·abort 타입·비자 기본 응답). 문구·재발급 분기 8곳을 일부러 바꾸면 해당 장면만 실패 확인 | e46f96a |
| 2026-11-10 | UI3-b-1 | 글·댓글·공감·신고·목록 화면(8개 파일)의 중복 에러 문구를 errorMessage.ts의 ERROR_MESSAGES 사전으로: POST_NOT_FOUND·POST_LOAD_FAILED·POST_FIELDS_INVALID·SAVE_FAILED·SCHOOL_NOT_FOUND·SCHOOL_GATE_POST_ACTION·DELETE_RETRY_AFTER_RELOAD·DELETE_FORBIDDEN·DELETE_FAILED·POST_ALREADY_LIKED·ACTION_FAILED + 함수 alreadyDeleted(label). 글자 변경 0(지운 문구 18개 대조, e2e 34개 수정 없이 통과), 1곳짜리·글자가 다른 문구는 화면에 둠, 분기·동작 변경 0. errorMessage.test.ts가 사전 전체를 글자 그대로 고정(26개). 사전 값을 바꾸면 Vitest·e2e 빨간불 확인. build 보고에 입문자용 쉬운 설명 칸 추가 | 2adf68f |
| 2026-11-10 | UI3-b-2 | 비자 후기·로그인·가입 화면(8개 파일)의 중복 에러 문구를 사전으로: 새 키 REVIEW_NOT_FOUND·REVIEW_LOAD_FAILED·REVIEW_FIELDS_INVALID·REVIEW_FILTER_INVALID·REVIEW_OPTIONS_LOAD_FAILED, 기존 SCHOOL_NOT_FOUND·SAVE_FAILED·DELETE_*·alreadyDeleted('후기') 재사용, 로그인·가입 403 리터럴 → CSRF_MESSAGE. 내 정보·학교 인증 화면은 1곳짜리뿐이라 그대로. 글자 변경 0(지운 문구 13개 대조, e2e 34개 수정 없이 통과), 사전 값을 바꾸면 Vitest·e2e 빨간불 확인. 이제 2곳 이상 같은 글자인 에러 문구는 errorMessage.ts에만 있음 | 2d748d9 |
| 2026-11-10 | S12-a | V8(visa_plans: 회원당 1행 uk_visa_plans_user, FK ON DELETE CASCADE) + /users/me/visa-plan GET(없으면 200 null)·PUT(인증 회원, 서울 오늘~+5년, ON CONFLICT upsert로 동시 저장 500 없음, 저장 중 탈퇴 FK 위반 → 401)·DELETE(없으면 404). 탈퇴 시 일정 자동 삭제. api.md 24~26·공통 인증 규칙·탈퇴 절. VisaPlanApiTests 22개(서울 자정 경계·동시 PUT·탈퇴 경쟁 포함), 전체 729 통과. 알려진 한계: 배열·숫자 날짜도 받아짐(후기와 함께 나중에) | (이 커밋) |
| 2026-11-10 | S12-b | 비자 일정 응답에 daysLeft(서울 오늘 기준)·stage(PREPARE 120일 이내/CHECK 30/URGENT 7/EXPIRED 지남/121일 이상 null)·todos(만료 120·30·7일 전 할 일, dueDate 오름차순, 지난 것도 음수로 유지) 계산 추가, 저장 없이 VisaPlanResponse.of(만료일, 오늘)에서 계산. 기준 숫자·문구는 VisaMilestone 한 곳, VisaPlanStage는 마일스톤을 필드로 연결. 이름은 숫자 없이(사용자 결정: 화면은 daysLeft로 D-n/D-Day/D+n, stage는 띠 판단용). 문구는 연장·자격 변경 포함, 출입국·외국인청. 경계 121/120/31/30/8/7/0·서울 00:30·키 유지·리터럴 검사기 테스트, 전체 746 통과 | (이 커밋) |
| 2026-11-10 | S12-c | /visa-plan 화면 + 상단바 "비자 일정" 링크(학교 인증 회원만). 일정 없음 안내·만료일 입력(min 오늘·max 5년), 큰 D-n(daysLeft로 D-111/D-Day/D+3, formatDDay), 할 일 3줄(지난 것은 흐리게 + "지남" 글자), 만료일 변경·삭제(확인 창), EXPIRED 중립 안내, 일반 안내 + 하이코리아 링크. 미인증은 GET 없이 안내만, 불러오기 실패 시 폼 숨김, 저장·삭제 중 상대 버튼 잠금, 저장 시 이전 삭제 에러 지움. VISA_SCHOOL_REQUIRED_RETRY 사전 이동(글자 0 변경), seoulToday를 formatDate.ts로 합침, design.md에 D-n 규칙. tester 쓰기 허용에 frontend/e2e/ 추가. e2e visa-plan 24개(시계 고정 2026-11-10)·후기 403 회귀 1개, 전체 e2e 59·단위 32 통과, 실제 서버로 저장·375px 확인 | (이 커밋) |
| 2026-11-10 | S12-d | 로그인한 화면 상단 비자 일정 띠 + 일정 보관소(VisaPlanProvider). 인증 회원만 앱 시작·로그인 직후·학교 인증 직후 GET 1번, 띠와 /visa-plan이 공유(저장·삭제하면 띠가 새로고침 없이 바뀜), 로그아웃·회원 변경 시 비움. 띠는 stage 있을 때만(문구 상수 visaBanner.ts 한 곳), role=status, /visa-plan에선 숨김, GET 실패면 띠만 숨김. X 닫기 기록은 회원당 키 하나 visaBannerClosed:{id}=단계:만료일 해시(공용 PC에서 날짜 안 보임, 안 쌓임), 단계·만료일 바뀌면 다시 뜸. review 필수: 로그인 풀린 화면에서 다시 저장 성공 시 화면 반영. 진입 시 실패·자정 넘김이면 한 번 다시 불러옴. design.md 띠 규칙. e2e 띠 20·일정 27(전체 82), 단위 42 통과, 실제 서버로 띠·단계 변경·닫기 확인 | (이 커밋) |
| 2026-11-11 | S12-e | EC2 배포(S12 전체 + S11 리뷰 수정·UI2·UI3 묶음): main CI(frontend·backend·e2e·images) 초록 → Deploy 자동 백업·헬스체크 성공(V8 적용). 운영에서 health UP·글 목록·없는 글 404·비로그인 비자 일정 API 401·8080 닫힘 확인, wntjrdka로 만료일 등록 → D-15·할 일 3줄(지남 표시) → 글 목록 띠(CHECK D-29) → X 닫기 후 같은 날짜 재등록 시 계속 숨김·다른 날짜면 다시 뜸 → 삭제 후 일정 null·띠 없음 | 9c8b600 |
| 2026-11-11 | S12 리뷰 | 파트 전체 리뷰(필수 0) 권장 반영: 할 일 줄이 D+105·"지남"·흐림으로 "마감 지남"처럼 읽히던 것(운영 확인 때 사용자가 헷갈림) → 굵은 칸 "만료 N일 전부터"·날짜·상태 글자 "지금 확인할 때"(todo.code === stage, 청자색)/"n일 뒤 시작"/"지난 단계"(흐림)로 나눠 이어 읽히지 않게, 큰 D-n은 그대로. 서버가 모르는 stage를 보내면 띠 예외로 화면 전체가 죽던 것 → bannerMessage null이면 띠 안 그림. DELETE CSRF 403·미인증+범위 밖 날짜 403 테스트, daysBetween 단위 테스트(달·해 넘김·윤년). design.md·api.md(dueDate는 시작 시점) 갱신. 서버 코드 변경 0. 백엔드 748·단위 49·e2e 84 통과 | (이 커밋) |
| 2026-11-11 | S13-a | V9(market_items: seller FK ON DELETE CASCADE, CHECK 거래 방식·상태·가격(나눔 0 / 판매 1~10,000,000), 인덱스 school_id+status·seller_id) + market 패키지. POST /market-items(학교 인증 회원, 학교는 인증 학교 복사, ON_SALE로 시작, 본문의 schoolId·status·sellerId 무시, 제목 100·본문 2000·NUL 400, 가격 규칙은 서비스에서 400, 저장 직전 탈퇴 401, 201+Location)·GET /market-items/{id}(공개, 404, 쿼리 1번). enum 응답 {code,name}. api.md 27·28절·탈퇴 절. 리뷰 권장 1~4 반영(탈퇴 경쟁·NUL·나쁜 쿠키·쿼리 수 테스트, api.md 한 줄, SecurityConfig {id} 경고 주석). Flyway 기준선 테스트 V9 반영. MarketItemApiTests 22개, 전체 770 통과 | (이 커밋) |
| 2026-11-11 | S13-b | GET /market-items(공개, id 내림차순, schoolId·tradeType·status 필터 Specification, 없는 학교 404·잘못된 값 400, EntityGraph로 N+1 없음)·PUT /market-items/{id}(본인만, 학교 인증 무관, 관리자도 403, 판매↔나눔 허용이라 trade_type updatable, 검사 순서 CSRF→401→400→가격 400→404→403)·DELETE(본인·관리자, deleteByIdReturningCount). review 필수: 수정 중 글이 지워지면(다른 탭·관리자·탈퇴 CASCADE) 커밋 때 0행 UPDATE로 500 → saveAndFlush 후 ObjectOptimisticLockingFailureException만 404로(수정 전 코드로 경쟁 테스트가 실패하는 것 확인). 권장 2·3·4 반영(묵은 관리자·유령 회원, 동시 삭제, 400이 403·404보다 먼저). api.md 29~31절. MarketItemListUpdateDeleteTests 36개, 전체 806 통과 | (이 커밋) |
| 2026-11-11 | S13-c | PUT /market-items/{id}/status(판매자 본인만, 관리자도 403, 학교 인증 무관). 전환 규칙 ItemStatus.canChangeTo 한 곳(ON_SALE↔RESERVED, →DONE, DONE에서 나가기·같은 상태 409). 상태 변경은 조건부 UPDATE(where status=from), 0행이면 existsById로 404/409 → 동시에 두 번 눌러도 하나만 200. 사용자 결정: 완료 글은 내용 수정도 409(삭제만). S13-b 리뷰 문제(내용 수정이 상태를 옛 값으로 덮음) 해결: status updatable=false + 내용 수정을 where status<>DONE 조건부 UPDATE로(saveAndFlush 방식 제거, 엔티티 update 메서드 삭제). 조건을 빼면 경쟁 테스트가 실패하는 것 확인. api.md 28·30·32절. MarketItemStatusTests 28개, 전체 834 통과 | (이 커밋) |
| 2026-11-11 | S13-d | 중고·나눔 화면(프론트만): 상단바 "중고·나눔", /market 목록(가격 "5,000원"/"나눔", 상태 뱃지 판매중=청자·예약중=파랑·거래완료=회색, 학교·종류·상태 필터는 주소 쿼리), /market/:id 상세, /market/new·/:id/edit 공용 폼(나눔이면 가격 칸 숨김·price 안 보냄, 판매 1~10,000,000 화면 검사). 글쓰기는 학교 인증 회원만(미인증·비로그인 안내). 판매자 본인만 갈 수 있는 상태 버튼, [거래 완료]는 되돌릴 수 없다는 확인 창, DONE이면 [삭제]만, 관리자는 남의 글에 [삭제]만. 상태 409 "그 사이 상태가 바뀌었습니다", 수정 409 "완료 글 수정 불가". 리뷰 권장 2~6 반영(모르는 상태면 버튼 안 그림, 성공 시 role=status 알림, 새로고침 필터 검사 강화, 확인 창 문구, 실패 경우 테스트). 실제 서버로 올리기→예약중→거래 완료→수정 막힘 확인. design.md 뱃지 규칙. 단위 52·e2e 133 통과 | (이 커밋) |
| 2026-11-11 | S13-e | 중고 글 신고: V10(reports.market_item_id + fk_reports_market_item ON DELETE CASCADE + uk_reports_market_item_reporter, ck_reports_target을 num_nonnulls(글·댓글·중고 글)=1로 교체). POST /market-items/{id}/reports(사용자 결정: 같은 학교 인증 회원만, 검사 순서 401→403→404→다른 학교 403→자기 글 400→중복 409, 저장 직전 글 삭제는 FK 이름으로 404). 관리자 신고 목록·기각에 MARKET_ITEM(preview=제목, author=판매자, postId null, 쿼리 1번 유지). 화면: 상세 [신고](ReportForm 재사용), 관리자 "중고" 배지·/market/{id} 링크·DELETE /market-items/{id}. tester가 신고 주소 id 누락 버그(/market-items//reports) 발견 → 수정. 리뷰 권장 1~6 반영(postId 타입 null, api.md 신고·삭제·탈퇴 문구, 탈퇴 CASCADE·동시 중복·CSRF·관리자 삭제 주소·다른 학교 버튼 테스트, 삭제 주소를 /posts로 바꾸면 e2e가 실패하는 것 확인). MarketItemReportApiTests 24개, 전체 858·단위 52·e2e 142 통과 | (이 커밋) |
| 2026-11-11 | S13-f | 중고 글 제목 검색: GET /market-items?q=(공개, 제목만, 대소문자 무시 부분 일치, LIKE 이스케이프로 %·_·\ 글자 그대로, 앞뒤 공백 자르고 비면 조건 없음, 코드포인트 50자 초과·NUL 포함 400이 학교 404보다 먼저, 기존 필터와 AND, 정렬·쿼리 수 유지, 표 변경 없음). 화면: /market 검색창(role=search), 주소 ?q=(빈 값이면 제거·다른 필터 유지·새로고침 유지), 0건이면 "검색 결과가 없습니다". review 필수: q에 NUL이면 500(실제 서버로 재현) → 400. 권장 반영: Enter 뒤 포커스 유지(key 제거, ref로 값 동기화), 소문자 변환을 DB 한쪽으로(cb.literal은 SQL에 인라인돼 버리고 HibernateCriteriaBuilder.value로 바인딩 확인), api.md 모순 문구. 따옴표 주입 테스트. 실제 서버로 IPAD·50%·제목만·375px·포커스 확인. MarketItemSearchTests 23개, 전체 881·단위 52·e2e 154 통과 | (이 커밋) |
| 2026-11-11 | S13-g1 | 사진 처리기·저장소(API·DB 변화 없음): ImageProcessor(매직 바이트로 JPEG·PNG만 → 헤더로 긴 변 10,000·총 4천만 상한 먼저 → EXIF 회전 1~8 → 긴 변 2048 + 썸네일 320, 확대 안 함 → 형식 유지 재인코딩(JPEG 0.85·PNG 알파 유지)으로 GPS 제거, InvalidImageException UNSUPPORTED_FORMAT/TOO_LARGE/CORRUPTED). PhotoStorage 인터페이스 + LocalPhotoStorage(./uploads, app.photo.local-dir, 경로 탈출 키 거부). 의존성 metadata-extractor 2.19.0(EXIF 회전 읽기). review 필수: 4천만 픽셀 통째 디코딩이 운영 힙(512m×60%)을 넘음 → 디코딩 때 건너뛰며 읽기(n=올림(긴 변/4096)), 원본 크기로 최종 크기 계산. 메인이 그 줄을 지워도 테스트가 통과하는 걸 발견 → 디코딩 크기 테스트 추가. Semaphore는 g2 서비스에. ImageProcessorTests 57·LocalPhotoStorageTests 17, 전체 955 통과 | 3d6fa5c |
| 2026-11-11 | S13-g2 | 사진 올리기: V11(market_item_photos: 글 FK CASCADE·키 UNIQUE·content_type·크기 CHECK, photo_delete_queue 표만) + POST /market-items/{id}/photos(multipart file 1장, 판매자 본인만·관리자 403·학교 인증 불필요·DONE 409, 파일 400 → 404 → 403 → 409 → 10장 409 → 처리 자리(Semaphore 2, 5초 뒤 503) → 처리 400 → 창고 put(트랜잭션 밖) → TransactionTemplate 안 글 FOR UPDATE 재확인·10장 409·INSERT, 실패 시 창고 파일 정리). 단건·수정·상태변경 응답에 photos(올린 순), 목록엔 없음. PhotoStorage get은 바이트만(content-type은 DB), PhotoStorageException. multipart 10MB/11MB(초과 413), max-swallow-size 11MB. review 필수: 10MB 바이트를 줄 서기 전에 읽음 → 자리 얻은 뒤 읽기, V11 이름 관례(add_·uk_), api.md 옛 문장. 권장 반영: 10장 미리 세기. 테스트: 9장 동시 2요청 하나만 성공, 처리 중 DONE·삭제, 탈퇴 CASCADE, 창고 실패 정리, 바이트 안 읽음, 413·401·403 실제 포트. MarketItemPhotoApiTests 33·UploadLimitTests 6, 전체 995 통과 | ee7f6af |
| 2026-11-11 | S13-g3 | 사진 보기·지우기(표 변경 없음): GET /market-items/{id}/photos/{photoId}(공개, ?size=thumb, 그 외 400 → 사진+글 번호로 조회 404(readOnly 짧은 트랜잭션) → 창고 파일 없음 404, Content-Type은 DB 값, Cache-Control public max-age 1년 immutable, nosniff, SecurityConfig에 이 GET 한 주소만 공개). DELETE 같은 주소(판매자 본인, 글 FOR UPDATE 안에서 404 → 403(관리자도) → DONE 409 → 사진 404 → 행 삭제, 커밋 뒤 창고 파일 2개 삭제·실패는 로그만, 204, photo_delete_queue는 g4). review 필수 0, 나중: 공개 GET byte[] 메모리는 S13-h 전 결정. 테스트: Location 주소 GET 200, 10장 → 1장 지움 → 다시 올리기, 동시 삭제 204/404, 거래완료 글 사진 보기 200, 공개 범위. MarketItemPhotoViewDeleteTests 28, 전체 1023 통과 | (이 커밋) |
