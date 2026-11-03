# Changelog

기능별 변경 기록. recorder가 작성한다. 최신 기록이 위에 온다.

## [2026-11-03] S3-c. CD 2: EC2 배포 (수동 실행)

**무엇을**
- .github/workflows/deploy.yml 추가(workflow_dispatch만). AWS 상태 조회 → 러너 IP를 보안그룹 22번에 임시 허용 → SSH로 compose pull·up -d·image prune → /actuator/health 18회 재시도 → always()로 규칙 revoke
- 조회 결과를 먼저 변수에 받아 set -e로 멈춤(이번 커밋: 조회 실패가 "실행 중 아님"으로 잘못 보이던 문제 수정). frontend/README.md 배포 절차를 자동 기준으로 재작성

**왜**
- 탄력적 IP 없이(비용) IP가 바뀌는 EC2와 내 PC IP로만 열린 22번 문제를 AWS API 조회 + 러너 IP 몇 분 허용으로 해결. EC2가 평소 꺼져 있어 자동 트리거 대신 수동 버튼. IAM은 DescribeInstances + 해당 보안그룹 Authorize/Revoke만

**완료 조건**
- [x] tester 로컬 통과(bash -n, 값 노출 grep 0, 가짜 aws/curl로 판정 시뮬레이션), review 필수 0·권장 반영
- [x] Deploy #1 실패(Variables에 안내 문구 → InvalidInstanceID.Malformed) → 값 수정 후 Deploy #2 성공(39초), 규칙 원상복구, 브라우저 글 유지·작성 확인
- [x] S3 전체 배포까지 끝: 이 S3-c의 배포가 Deploy #2(b322a6b 이미지)
- [ ] 미확인: EC2 중지 상태 실행 시 실패·규칙 잔존 여부(로컬 시뮬레이션만). 남은 과제: SHA 태그 배포, OIDC, Environments 검토

**변경 파일**
- .github/workflows/deploy.yml, frontend/README.md

**배운 점**
- 자리표시자(예시 값)는 실제 값으로 바꿔야 하고, 에러 메시지가 원인을 가리면 조회 실패를 따로 분리해 본다

## [2026-11-03] S3-b. CD 1: main push 때 이미지 빌드 + Docker Hub push

**무엇을**
- .github/workflows/ci.yml에 images job 추가. needs [backend, frontend], main push일 때만 실행. seokam36/demo(컨텍스트 .), seokam36/demo-web(컨텍스트 frontend)을 latest + 커밋 SHA 태그로 push
- gha 캐시 scope backend/frontend 분리, timeout 20분. EC2 변화·배포 없음

**왜**
- 테스트를 통과한 커밋만 이미지가 되게 하고, 손으로 하던 build·push와 s1-d 백업을 자동화. SHA 태그가 버전별 백업. 계정명 seokam36은 비밀이 아니라 고정(secret이면 로그에서 가려져 불편), 토큰은 GitHub 전용 Read & Write로 새로 발급. EC2가 t3.micro amd64라 platform 미지정
- main에서 concurrency cancel-in-progress를 끔(push 도중 취소로 이미지가 반쪽 갱신되지 않게). 남은 과제 S3-c: backend 성공 뒤 frontend 실패 시 한쪽만 latest가 바뀔 수 있고, 옛 실행 Re-run 시 옛 이미지가 latest로 올라갈 수 있음 → EC2가 SHA 태그로 두 이미지를 같이 받는 방식 검토

**완료 조건**
- [x] tester 로컬 통과: 조건식 3경우 판정, 태그 4개, 토큰 grep 0건, 로컬 docker build 두 이미지 성공
- [x] review 필수 0, 권장 2건(concurrency 주석 정확화, 한쪽만 갱신될 수 있다는 주석) 반영. GitHub main 실행 backend·frontend·images success, Docker Hub 두 이미지에 latest와 c6ad16b 태그 확인
- [x] 임시 브랜치(빈 커밋) push 시 images skipped 확인 후 브랜치 삭제

**변경 파일**
- .github/workflows/ci.yml

**배운 점**
- job에 if 조건을 걸면 해당 조건이 아닌 push에서는 그 job이 실행되지 않고 skipped로 표시된다

## [2026-11-03] S3-a. CI: push/PR 때 백엔드 테스트 + 프론트 lint·빌드

**무엇을**
- .github/workflows/ci.yml 추가. 모든 브랜치 push(태그 제외)와 main행 PR에서 backend(./gradlew test, 실패 시 test-reports artifact 업로드)·frontend(npm ci → lint → build) 실행
- gradlew 실행 권한 100644→100755 (Windows 커밋으로 빠져 있었음). backlog S3를 S3-a/b/c로 나눔

**왜**
- push 순간 테스트 누락·깨진 코드를 자동으로 잡기 위함. CD(S3-b, S3-c)의 토대. 테스트 DB는 Testcontainers가 러너 Docker로 띄워 서비스 컨테이너 없음

**완료 조건**
- [x] 로컬 ./gradlew test 50개, npm ci·lint·build 통과
- [x] main 실행 backend·frontend 성공, 깨뜨린 임시 브랜치 2개(테스트 실패 → backend 실패 + artifact, 타입 오류 → frontend 실패) 확인 후 로컬·원격 삭제, 머지 안 함

**변경 파일**
- .github/workflows/ci.yml, gradlew, docs/backlog.md, docs/changelog.md

**배운 점**
- Windows에서 만든 파일은 git에 실행 권한이 안 실릴 수 있어 리눅스 러너에서 Permission denied가 난다 → git 파일 모드를 100755로 고쳐 커밋

## [2026-11-03] S2-c. 수정 + 삭제 배포 (백엔드 S2-a + 프론트 S2-b)

**무엇을**
- 코드·설정 변경 없이 배포만 함(docker-compose.yml, nginx.conf, Dockerfile 2개의 마지막 변경이 S1-d 커밋임을 확인). 배포 전 ./gradlew test, npm run build 통과
- 옛 이미지 latest(S1-d)에 s1-d 태그를 붙여 push → 새 이미지 push(백엔드 먼저, 그다음 web) → EC2에서 compose --profile prod pull / up -d. postgres는 그대로라 기존 글 유지

**왜**
- 설정 변경 불필요: nginx /api/에 메서드 제한이 없고, try_files로 /posts/:id/edit 직접 진입 가능, 같은 출처라 CORS 무관, 컬럼 변경 없음(ddl-auto=update). 그래서 deploy 단계 생략
- s1-d 백업: latest는 push하면 덮어써져 되돌릴 이미지가 사라짐. 백엔드 먼저: 화면만 새 버전이면 수정·삭제가 405로 실패
- 서버 확인은 배포에서만 생기는 문제만(nginx 메서드 전달, SPA 경로, 8080 차단). 실패 케이스는 S2-b 로컬에서 끝냄

**완료 조건**
- [x] ./gradlew test, npm run build 통과
- [x] docker ps 3개 Up, /actuator/health UP, 기존 글 유지, 새 글 작성
- [x] 수정 화면 채움·저장·새로고침 유지, 공백 제목 400 문구·입력 유지, /posts/{id}/edit 새로고침
- [x] 삭제 취소/확인 → 목록에서 사라짐(테스트용 새 글), curl -X DELETE /api/posts/999999 → 404, :8080 직접 접속 불가

**변경 파일**
- 없음(코드·설정). docs/changelog.md, docs/backlog.md(S2-c 체크)

**남은 일**
- 이미지 태그는 latest + s1-d만 사용(기능별 버전 태그 체계는 나중에). s1-d 백업은 문제없으면 정리 가능. S2-b 리뷰 참고 사항 그대로

**배운 점**
- latest는 "가장 최근에 그 이름으로 push한 것"일 뿐이라 덮어쓰기 전에 다른 태그로 백업한다
- Docker 명령이 daemon 연결 에러를 내면 Docker Desktop이 꺼져 있는 것

## [2026-11-02] S2-b. 게시글 수정 + 삭제 화면 (프론트)

**무엇을**
- 상세: 글을 불러온 경우에만 [수정] 링크·[삭제] 버튼. 삭제는 window.confirm → DELETE → navigate('/', {replace: true}). 삭제 에러는 불러오기 에러와 별도 state
- 수정 화면(/posts/:id/edit): 글을 불러와 채우고, 불러오기 실패 시 폼 숨김 + "목록으로". PUT 저장(저장 중 disabled), 성공 시 상세로 replace 이동. 400/404/연결 실패 문구, 입력값 유지, [취소]
- App.tsx 라우트 추가, types.ts에 PostUpdateRequest. 통신은 기존 api.ts 그대로

**왜**
- 작성/수정 폼 분리: 잘 동작하는 작성 화면을 건드리지 않고(자동 테스트 없음), 수정만의 불러오기·404 처리를 넣기 위함. 입력창 약 25줄 중복은 감수
- window.confirm: 한 줄, 라이브러리 불필요, 접근성 기본 지원
- 삭제·수정 후 replace: 뒤로가기로 지워진 글이나 이미 저장한 수정 폼으로 돌아가지 않게. 배포는 S2-c로 분리(S2-a 백엔드도 미배포, 완료 조건을 작게 유지)

**완료 조건**
- [x] npm run build·lint 통과, 백엔드 테스트 통과
- [x] 브라우저 수동 체크리스트 8개 전부 확인 (버튼 표시/없는 글, 수정 화면 채움, 저장·새로고침 유지, 400 문구·입력 유지, 없는 글 edit 주소, 삭제 확인·취소·목록 이동, 서버 꺼짐·다른 탭 선삭제)
- 수정 후 replace는 build·lint로만 확인(뒤로가기 재확인 안 함)

**변경 파일**
- frontend/src/App.tsx, frontend/src/pages/PostDetailPage.tsx, frontend/src/pages/PostEditPage.tsx(신규), frontend/src/types.ts, docs/backlog.md

**남은 일**
- 상세의 encodeURIComponent 3회 반복(스타일), 삭제 중 [수정] 클릭 시 드물게 목록으로 이동, 저장 중 [취소]해도 요청 계속(작성 화면과 동일). 동작 영향 미미. 배포·커밋 전

**배운 점**
- navigate(..., {replace: true})는 현재 기록을 바꿔서, 뒤로가기로 지워진 글이나 저장 전 폼이 되돌아오지 않게 한다

## [2026-11-02] S2-a. 게시글 수정 + 삭제 API (백엔드)

**무엇을**
- PUT /posts/{id}: 제목·본문 전체 수정, 200 + 수정된 글. 검증은 작성과 동일(PostUpdateRequest). createdAt 불변. DELETE /posts/{id}: DB에서 실제 삭제, 204 빈 본문. 없는 id 404, 숫자 아닌 id 400
- CorsConfig allowedMethods에 GET, HEAD, POST, PUT, DELETE 지정. Post에 update(title, content), PostService에 update/delete(@Transactional, 수정은 dirty checking)
- docs/api.md에 4. 수정, 5. 삭제 명세 추가. docs/backlog.md의 S2를 S2-a(백엔드)/S2-b(프론트)로 분리

**왜**
- PATCH 대신 PUT: 바꿀 필드가 2개뿐이고 작성과 같은 검증 규칙을 그대로 쓸 수 있음
- 삭제는 204: 지운 뒤 돌려줄 내용이 없음. S2를 쪼갬: S1처럼 API(자동 테스트)와 화면(직접 확인)을 나눠 완료 조건을 작게 유지
- 기본 CORS 메서드에 PUT/DELETE가 없어 브라우저가 막음. 메서드를 명시하면 기본값이 덮어써져서 GET/HEAD/POST도 함께 적음
- setter 대신 update(title, content)로 의도를 드러냄

**완료 조건**
- [x] PUT 수정 200·createdAt 불변(수정 후 GET으로도 확인), 없는 id 404, 잘못된 입력·숫자 아닌 id 400
- [x] DELETE 204 빈 본문 후 조회 불가, 없는 id 404, 숫자 아닌 id 400
- [x] CorsConfig PUT/DELETE preflight 2개(Allow-Origin 확인 포함)
- [x] 신규 PostUpdateDeleteApiTests 17개 포함 전체 50개 통과

**변경 파일**
- config/CorsConfig.java, post/controller/PostController.java, post/domain/Post.java, post/service/PostService.java, post/dto/PostUpdateRequest.java, docs/api.md, docs/backlog.md, src/test/.../CorsTests.java, src/test/.../post/PostUpdateDeleteApiTests.java

**남은 일**
- 수정 중 동시 삭제 시 500 가능(드묾, 보류). Post.java:44 주석 표현 부정확. api.md 표의 실패 코드 순서 불일치 (동작 영향 없음)
- 권한 체크, updatedAt, 소프트 삭제는 하지 않음. 배포·커밋 전

**배운 점**
- 엔티티 상태 변경은 setter 대신 의도가 드러나는 메서드로, 트랜잭션 안에서 바꾸면 dirty checking이 UPDATE를 만든다
- allowedMethods를 명시하면 기본값이 대체된다(추가가 아님)

## [2026-11-02] S1-d. 프론트엔드 배포

**무엇을**
- frontend를 nginx 컨테이너로 서빙(Dockerfile 2단계 빌드). /api 요청은 nginx가 app:8080으로 넘기고, compose prod에 web 추가, app 8080 외부 공개 제거. EC2 배포 완료: 배포 전 재시작 후 postgres Exited·app Restarting 반복이던 것이 이번 배포(restart 정책 포함)로 정상화

**왜**
- 프론트는 API 주소를 빌드 때 번들에 박고, 화면 경로와 API 경로가 /posts/1로 겹침 → API만 /api 아래로 분리. nginx를 고른 이유: 백엔드 코드 변경 0, 같은 주소라 CORS 불필요, 메모리 약 21MiB
- nginx는 호스트명을 시작 때 한 번만 IP로 바꿔 app 재생성 시 502가 날 수 있음 → Docker DNS(127.0.0.11)로 요청마다 조회. postgres에 restart 추가는 EC2를 껐다 켰을 때 postgres만 Exited로 남는 것을 확인해서
- 배포는 git pull 대신 scp로 compose 파일만 복사: EC2에는 git 저장소·git이 없고 ~/board에 docker-compose.yml·.env만 있음(EC2는 이미지만 받아 실행). 배포 전 옛 파일은 docker-compose.yml.bak-20261102로 백업. 되돌리기는 백업 복원 후 `up -d --remove-orphans`(옛 파일에 없는 board-web이 80번을 붙잡지 않게), latest 태그라 이미지는 새것 그대로

**완료 조건**
- [x] 로컬 검증: 화면·새로고침 200, /api 작성 201·조회 200·404·400, app 재생성 후 web 재시작 없이 200, 8080 외부 차단
- [x] ./gradlew test --rerun-tasks 통과 (백엔드 코드 변경 없음)
- [x] EC2 실제 배포와 배포 후 브라우저 확인: EC2에서 컨테이너 3개(board-web 0.0.0.0:80, board-app 내부 8080만, board-postgres healthy), 외부에서 / 200, /posts/1 새로고침 200, /api/posts 200(기존 글 유지), /api/posts/999999 404, /actuator/health UP, /api/actuator 404, :8080 연결 실패, 사용자가 브라우저에서 한글 글 작성·새로고침 확인
- 남은 일: 부팅 중 잠깐 502, 퍼블릭 IP가 시작마다 바뀜(탄력적 IP 없음), 이미지 버전 태그 없음(latest)

**변경 파일**
- frontend/Dockerfile, frontend/nginx.conf, frontend/.dockerignore, frontend/README.md(배포 절차를 scp 방식으로 재작성), docker-compose.yml, docs/api.md

**배운 점**
- nginx는 고정 호스트명을 시작 때 한 번만 풀어서, 컨테이너 IP가 바뀌면 resolver와 변수 proxy_pass로 요청마다 다시 찾아야 한다

## [2026-11-01] S1-c-2. 게시판 화면 (React + TypeScript)

**무엇을**
- frontend/에 Vite react-ts 프로젝트. 화면: 목록 /, 작성 /posts/new, 상세 /posts/:id, 없는 경로 *. react-router(화면 이동), axios(인스턴스 하나, src/api.ts — baseURL = VITE_API_BASE_URL || http://localhost:8080). 로컬 실행까지, 배포는 S1-d
- 리뷰: 필수 1건 수정 — 상세 id를 그대로 경로에 넣어 /posts/%3F가 목록 API(GET /posts/?)를 호출 → encodeURIComponent. 권장 반영: 성공 시 에러 초기화, 입력 label, role="alert", tsconfig strict 명시, README에 VITE_API_BASE_URL 빌드 시 고정 안내
- 나중에 챙길 것: S1-d 배포 빌드 때 VITE_API_BASE_URL 필수(빠뜨리면 사용자 PC에서 localhost:8080 호출). 상세→상세 이동(이전/다음 글)이 생기면 loading/error/post 초기화 추가

**왜**
- 백엔드 주소·axios 설정은 api.ts 한 곳, 시각 변환은 formatDate 한 곳(ko-KR, Asia/Seoul): 바꿀 때 한 곳만
- 상태는 useState/useEffect만(사용자 경험 범위), Redux·React Query·UI 라이브러리 없음. useEffect에 ignore 플래그로 늦게 온 응답 무시
- 에러 구분: 응답 없음 → "서버에 연결할 수 없습니다", 400 → 입력 확인 문구(입력값 유지), 404/400(상세) → "글을 찾을 수 없습니다"
- 프론트 빈 값 검사는 하지 않고 제목 maxLength=100만(서버 400을 확인하려고). 작성 후 응답 body id로 상세 이동
- || 사용: .env의 빈 값도 기본 주소로

**완료 조건**
- [x] npm run build(tsc 포함), npm run lint(oxlint) 통과
- [x] 주소는 api.ts에만, 라우트 3개 + BrowserRouter, 의존성은 react-router·axios만, 템플릿 정리, 시각 변환 단일화
- [x] 브라우저 수동 확인: 목록·작성·상세·공백 제목·없는 글·서버 꺼짐·특수문자 주소, CORS 에러 없음

**변경 파일**
- frontend/ (src/main.tsx, App.tsx, api.ts, types.ts, formatDate.ts, errorMessage.ts, pages/*, index.html, README.md, .env.example 등)

**배운 점**
- 사용자 입력을 URL에 넣을 땐 encodeURIComponent / Vite 환경변수는 빌드 시점에 번들에 박힌다

## [2026-11-01] R1. 롬복 도입 (리팩터링, 기능 변화 없음)

**무엇을**
- build.gradle에 lombok(compileOnly + annotationProcessor, 버전은 Boot BOM). Post의 getter 4개·protected 기본 생성자 → @Getter, @NoArgsConstructor(access = PROTECTED). PostService·PostController 생성자 → @RequiredArgsConstructor
- 리뷰: 필수·권장 없음

**왜**
- 반복 코드 감소. 사용 규칙(사용자 합의): @Getter, @NoArgsConstructor(PROTECTED), @RequiredArgsConstructor만. @Setter·@Data·@ToString·@EqualsAndHashCode·@AllArgsConstructor·@Builder 금지(엔티티 보호, 연관관계 무한 참조 예방)
- DTO는 record라 불필요. CorsConfig는 @Value 필드 주입 유지(롬복 생성자는 @Value를 복사하지 않아 실패)

**완료 조건**
- [x] 기존 테스트 31개 그대로 통과 (동작 변화 없음)

**변경 파일**
- build.gradle, Post.java, PostService.java, PostController.java

**배운 점**
- 롬복은 컴파일 때 코드를 만들어 주고 빠지는 도구(compileOnly) / 엔티티에는 필요한 것만 골라 쓴다

## [2026-11-01] S1-c-1. 백엔드 CORS 전역 설정

**무엇을**
- WebMvcConfigurer(config/CorsConfig)로 /**에 허용 origin 적용. app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:http://localhost:5173} (쉼표 구분)
- 다음 기능(React, localhost:5173)이 axios로 API(8080)를 부르기 위함. docs/api.md에 CORS 안내, .dockerignore에 frontend 추가

**왜**
- 컨트롤러별 @CrossOrigin 대신 전역 한 곳: 컨트롤러가 늘어도 빠뜨리지 않음. Vite 프록시 대신 백엔드 CORS: 프론트를 따로 배포하면 어차피 필요
- 허용 origin은 환경변수로 받아 배포 시 코드 수정 없이 바꿈. origin은 비밀이 아니라 로컬 기본값만 파일에 둠(DB 접속 정보와 다름)
- 허용 메서드는 기본값(GET, HEAD, POST), credentials·exposedHeaders 없음

**완료 조건**
- [x] 허용 origin의 preflight·GET·POST와 에러 응답(400/404)에 Allow-Origin 포함
- [x] 허용 안 된 origin은 403, Allow-Origin 없음
- [x] 프로퍼티 변경 시 허용 origin이 바뀜(쉼표 뒤 공백 처리)
- [x] 기존 테스트 포함 전체 통과 (31/31)

**변경 파일**
- config/CorsConfig.java, application.properties, docs/api.md, .dockerignore, src/test/.../CorsTests.java, CorsPropertyTests.java

**나중에 챙길 것**
- S1-d: compose에서 CORS_ALLOWED_ORIGINS를 빈 값으로 넘기면 허용 origin 0개 → 모든 교차 출처 요청 403. 필수값으로 강제 → S1-d에서 해당 없음: nginx 같은 주소 방식이라 CORS 불필요, compose가 값을 넘기지 않음
- S2: PUT/DELETE 추가 시 allowedMethods를 명시하면 기본값이 덮어써지므로 GET/HEAD/POST도 함께 적기
- 테스트 컨텍스트마다 PostgreSQL 컨테이너가 떠서(현재 3개) 늘어나면 싱글턴 컨테이너로

**배운 점**
- CORS는 브라우저의 보안 규칙이라 curl은 막지 않는다 / 에러 응답에도 Allow-Origin이 있어야 프론트가 상태코드를 읽을 수 있다

## [2026-11-01] S1-b. 게시글 작성 + 조회 API

**무엇을**
- POST /posts(201 + Location), GET /posts/{id}, GET /posts(최신순, 페이징 없음). controller→service→repository, 응답은 PostResponse DTO
- API 명세 docs/api.md 신설(build가 코드와 함께 갱신). 리뷰: 필수 1건(POST/GET createdAt 정밀도 불일치) 수정, 권장 5건 반영

**왜**
- 검증은 요청 DTO에 @NotBlank, @Size(max=100), NUL 금지 @Pattern → 잘못된 입력 400(Spring 기본 처리). 없는 글은 ResponseStatusException 404(공통 에러 처리는 나중)
- createdAt은 Instant(UTC) + 마이크로초 절삭: KST/UTC 혼동 제거, POST 응답과 DB 저장값 일치. open-in-view=false로 응답 끝까지 DB 연결을 잡지 않고 서비스 안에서 DTO 변환(풀 3인 작은 서버)
- 의존성 추가: spring-boot-starter-validation(입력 검증)

**완료 조건**
- [x] 작성 201 + 필드 + Location, 단건 200, 목록 최신순
- [x] 없는 id 404, 숫자 아닌 id 400, 빈값·공백·누락·101자·NUL·깨진 JSON 400 + 저장 안 됨
- [x] ./gradlew test 전체 통과 (21개)
- [x] EC2에서 작성 후 조회 200 확인

**변경 파일**
- build.gradle, application.properties, post/{controller,service,repository,domain,dto}/*.java, docs/api.md, src/test/.../post/PostApiTests.java, TestcontainersConfiguration.java(public)

**배운 점**
- 테스트는 검사한 것만 보장한다 — 리뷰가 찾은 시각 불일치를 테스트로 추가해 재발을 막았다. 시각은 Instant(UTC)로 저장하고 보여줄 때 변환한다

## [2026-11-01] S1-a. DB 연결 (PostgreSQL)

**무엇을**
- Spring Data JPA + PostgreSQL 17 연결. 접속 정보는 환경변수(DB_URL/DB_USERNAME/DB_PASSWORD), ddl-auto=update
- 로컬은 docker compose(postgres만), EC2는 같은 compose의 prod 프로필로 앱+DB 함께 실행. 테스트는 Testcontainers로 테스트마다 임시 PostgreSQL 사용

**왜**
- 같은 EC2에 DB 컨테이너(추가 비용 없음). 1GB라 app 512m / postgres 256m 상한, shared_buffers 64MB, max_connections 20, Hikari 풀 3 (실측 합계 약 257MB)
- 접속 값에 빈 기본값 없음(${DB_URL}): 누락 시 에러에 변수명이 보임(테스트 확인). 로컬 DB 포트는 127.0.0.1만, 비밀번호는 .env(gitignore)

**완료 조건**
- [x] docker compose up -d → 로컬 PostgreSQL 기동, 앱이 DB 연결 오류 없이 시작
- [x] DB 접속 정보가 설정 파일에 값으로 없고 환경변수로 들어옴
- [x] ./gradlew test 전체 통과 (4/4, Testcontainers)
- [x] EC2에서 앱+DB 실행 후 /actuator/health 200 {"status":"UP"} (외부에서 DB 포트 닫힘 확인)

**변경 파일**
- build.gradle, application.properties, docker-compose.yml, .env.example, .gitignore, src/test/.../TestcontainersConfiguration.java, DemoApplicationTests.java, HealthCheckTests.java

**배운 점**
- Boot 4 + Testcontainers 2.x는 artifact(testcontainers-postgresql)와 패키지(org.testcontainers.postgresql)가 1.x 예제와 다름 / Amazon Linux 2023의 docker 패키지에는 compose 플러그인이 없어 따로 설치 필요

## [2026-11-01] S0. 프로젝트 뼈대 + 헬스체크 API + 첫 배포

**무엇을**
- actuator로 GET /actuator/health 제공(응답 {"status":"UP"}), Docker Hub(seokam36/demo) 경유로 AWS EC2(t3.micro, AL2023, 서울)에 수동 배포 (CI/CD 제외)

**왜**
- actuator 사용: 표준 방식이며 S1에서 DB 상태가 자동 반영됨. 웹 노출은 health만 허용해 env 등 차단
- probes.enabled=false: Boot 4.1.2 기본값 true라 응답에 "groups"가 붙음. 응답을 {"status":"UP"}로 고정하는 대신 liveness/readiness 없음
- 멀티 스테이지 Dockerfile, 비루트 사용자, 힙 60% 제한(1GB EC2). 빌드는 로컬에서 하고 Hub로 전달해 EC2 메모리 부족 회피

**완료 조건**
- [x] ./gradlew build 성공(테스트 포함), /actuator/health 200·정확히 {"status":"UP"}, 없는 경로와 /actuator/env 404
- [x] docker build 및 로컬 컨테이너 헬스체크 200, 커밋 대상에 비밀 값 없음
- [x] EC2 공인 IP에서 200·{"status":"UP"} 확인

**변경 파일**
- build.gradle, src/main/resources/application.properties, src/test/java/com/example/demo/HealthCheckTests.java, Dockerfile, .dockerignore, .gitignore

**배운 점**
- Boot 4.1.2에서 health probes 기본값이 true로 바뀌어 /actuator/health 응답에 groups가 포함됨
