# 게시판 프로젝트

학습용. 게시판 CRUD에서 시작해 애자일로 기능을 하나씩 추가한다. 배포는 큰 기능(S번호) 하나가 끝날 때 한 번만 하고, 그 안의 작은 단계(S3-a 등)는 로컬에서만 진행한다.

## 스택
- Java 21, Spring Boot 4.1.2, Gradle
- Spring Data JPA, PostgreSQL (로컬은 docker compose)
- Flyway (표 구조 변경 관리, src/main/resources/db/migration)
- 테스트: JUnit 5, @DataJpaTest, @SpringBootTest + MockMvc
- 배포 대상: AWS EC2 (Docker)

## 명령어
- 빌드 `./gradlew build` / 테스트 `./gradlew test` / 실행 `./gradlew bootRun` / DB `docker compose up -d`

## 코드 규칙
- 패키지: com.example.demo.{도메인}.{controller|service|repository|domain|dto}
- 엔티티를 응답으로 직접 반환하지 않는다. DTO를 쓴다.
- 요청 DTO에 @Valid 검증. 없는 리소스는 404, 잘못된 입력은 400.
- 조회 전용 서비스 메서드에 @Transactional(readOnly = true).
- DB 표를 바꾸면 엔티티와 함께 새 V 파일(db/migration/V{다음 번호}__설명.sql)을 추가한다. 기존 데이터가 있을 때의 처리(채우기 등)도 같은 파일에 쓴다.
- 커밋된 V 파일은 고치지 않는다(체크섬이 바뀌어 앱이 안 뜬다). 바꿀 게 있으면 새 V 파일로.
- spring.jpa.hibernate.ddl-auto는 validate로 둔다(update·create 금지).
- 한 번에 한 기능만. 요청받지 않은 기능은 추가하지 않는다.
- 비밀 값은 코드·설정 파일에 쓰지 않는다. 환경변수로 받는다.
- build.gradle에 의존성을 추가하면 보고에 "추가한 의존성과 이유"를 포함한다.

## 작업 흐름 (메인 대화용)
planner → (필요시 research) → build → tester → review → (필요시 deploy)
- 사용자가 새 기능을 요청하면 planner 전에 메인 대화가 docs/backlog.md에 항목(다음 S번호)을 추가하고 사용자에게 보여준다.
- 서브에이전트는 이전 대화를 모른다. 완료 조건·변경 파일·실패 내용을 요약해서 넘긴다.
- planner 결과와 review [필수 수정]이 나오면 멈추고 사용자 승인을 받는다(실패·커밋 때 멈추는 규칙은 아래). 그 밖에는 build → tester → review를 이어서 돌리고, 끝나면 단계별로 무엇을 왜 했는지 한 번에 정리해 보여준다.
- tester 실패 → build 수정 (2회 넘게 실패하면 멈추고 보고).
- review에는 변경 파일 목록을 넘긴다. [필수 수정]은 build가 고친 뒤 tester를 다시 돌린다.
- 커밋·push·배포는 사용자 승인 없이 하지 않는다. 커밋 형식: feat/fix/test/chore: 내용
- docs/backlog.md = 할 일 + 끝난 일 표. 단계가 끝나면 메인 대화가 "끝난 일"에 한 줄(날짜 | 단계 | 한 줄 요약 | 커밋)을 추가해 기능 커밋에 같이 넣는다. 자세한 기록은 Notion에 남긴다.
- docs/deploy.md = 배포 방법(자동 버튼 + 비상용 수동). 배포 절차가 바뀌면 deploy가 같이 고친다.
- docs/api.md = API 명세. API를 추가·변경하면 build가 코드와 함께 고친다. review는 명세와 코드가 일치하는지 확인한다.

## 학습용
- 단계가 끝나면 무엇을, 왜 했는지 짧게 설명한다.
- 답변은 무조건 한국어 반말로 한다. 영어로 답하지 않는다.
- 사용자는 개발 입문자다. 쉬운 말과 비유로 설명하고, 어려운 용어만 늘어놓지 않는다.
- 코드를 쓰는 에이전트(build, tester, deploy)에게 무엇을·왜 하는지 짧은 한국어 주석을 달게 한다.
- 단계가 끝나면 docs/notion-guide.md대로 Notion에 학습 기록을 남긴다.

## 컴퓨터 바꿔 가며 작업
- 집 PC와 노트북을 오가며 작업한다. 세션을 시작하면 docs/handoff.md부터 읽고 이어서 한다.
- 작업을 끝낼 때 /handoff 로 docs/handoff.md를 갱신하고, 승인을 받아 커밋·push한다.
