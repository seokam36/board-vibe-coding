# 게시판 프로젝트

학습용. 게시판 CRUD에서 시작해 애자일로 기능을 하나씩 추가하고, 기능마다 배포한다.

## 스택
- Java 21, Spring Boot 4.1.2, Gradle
- Spring Data JPA, PostgreSQL (로컬은 docker compose)
- 테스트: JUnit 5, @DataJpaTest, @SpringBootTest + MockMvc
- 배포 대상: (정해지면 적는다)

## 명령어
- 빌드 `./gradlew build` / 테스트 `./gradlew test` / 실행 `./gradlew bootRun` / DB `docker compose up -d`

## 코드 규칙
- 패키지: com.example.demo.{도메인}.{controller|service|repository|domain|dto}
- 엔티티를 응답으로 직접 반환하지 않는다. DTO를 쓴다.
- 요청 DTO에 @Valid 검증. 없는 리소스는 404, 잘못된 입력은 400.
- 조회 전용 서비스 메서드에 @Transactional(readOnly = true).
- 한 번에 한 기능만. 요청받지 않은 기능은 추가하지 않는다.
- 비밀 값은 코드·설정 파일에 쓰지 않는다. 환경변수로 받는다.
- build.gradle에 의존성을 추가하면 보고에 "추가한 의존성과 이유"를 포함한다.

## 작업 흐름 (메인 대화용)
planner → (필요시 research) → build → tester → review → recorder → (필요시 deploy)
- 서브에이전트는 이전 대화를 모른다. 완료 조건·변경 파일·실패 내용을 요약해서 넘긴다.
- planner 결과가 나오면 멈추고 사용자 승인을 받는다.
- tester 실패 → build 수정 (2회 넘게 실패하면 멈추고 보고).
- review에는 변경 파일 목록을 넘긴다. [필수 수정]은 build가 고친 뒤 tester를 다시 돌린다.
- 커밋·push·배포는 사용자 승인 없이 하지 않는다. 커밋 형식: feat/fix/test/chore: 내용
- docs/backlog.md = 기능 목록, docs/changelog.md = 변경 기록(recorder만 작성)

## 학습용
- 단계가 끝나면 무엇을, 왜 했는지 짧게 설명한다. 사용자에게는 존댓말로 답한다.
