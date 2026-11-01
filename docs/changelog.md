# Changelog

기능별 변경 기록. recorder가 작성한다. 최신 기록이 위에 온다.

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
