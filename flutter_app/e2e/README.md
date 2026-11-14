# Flutter 웹 e2e (Playwright)

진짜 브라우저로 Flutter 웹 앱을 눌러 보는 테스트. 백엔드 없이 `/api`를 `page.route`로 가로채 가짜 응답을 준다.

## 이 테스트의 역할

- 테스트는 **`flutter test` 위젯 테스트(flutter_app/test, 화면·문구·요청 본문을 세세하게)가 주력**이다.
- e2e는 위젯 테스트가 못 보는 것만 맡는다: 진짜 브라우저·진짜 라우팅(주소 이동)·진짜 입력·진짜 파일 창.
- 그래서 **핵심 흐름과 대표 실패만** 옮겼다(아래 "옮김/안 옮김 표"). 나머지는 위젯 테스트가 덮는다.
- React(`frontend/e2e`)는 나중에 지워지므로 이 폴더는 frontend를 import하지 않고 node_modules도 따로 쓴다.

## 실행 방법

```
cd flutter_app
flutter build web --web-content-hash   # 1) e2e는 "미리 빌드해 둔 build/web"을 보여 주므로 코드를 바꿨으면 먼저 다시 빌드한다
cd e2e
npm ci                                 # 2) 이 폴더 전용 Playwright를 package-lock.json 그대로 설치한다 (처음 한 번)
npx playwright test                    # 3) serve.mjs가 build/web을 포트 5176에 띄우고(자동) 테스트를 돈다
```

- 로컬은 이 PC 보안 정책 때문에 설치된 Edge(`msedge`)를 쓴다. 다른 브라우저는 `PW_CHANNEL=chrome npx playwright test`.
- CI(`CI=1`)에서는 `chromium` 채널, 실패 시 2번 재시도, 1개씩 실행, html 리포트를 만든다.
- 일부 테스트만: `npx playwright test core-flow` / 화면을 보며: `--headed`.
- `build/web`이 없거나 오래됐으면 화면이 안 뜨거나 옛 화면을 시험하게 된다. 헷갈리면 빌드부터.

## 파일

| 파일 | 하는 일 |
|------|---------|
| `serve.mjs` | 의존성 없는 정적 서버. `../build/web`을 5176에서 제공, 확장자 없는 주소는 `index.html`(SPA), `/api`는 404 |
| `playwright.config.ts` | 설정. 테스트 60초, expect 15초(Flutter 첫 로딩이 느림) |
| `mockApi.ts` | 가짜 /api 응답, 요청 기록(`api.sent`), 준비 안 한 요청이 있으면 테스트 실패, `typeInto`·`makePng` 도우미 |
| `core-flow.spec.ts` | 목록 / 로그인 / 글 상세 (3개) |
| `write-and-guard.spec.ts` | 글쓰기 → 상세, 보호 주소 문지기 (2개) |
| `error-messages.spec.ts` | 404·네트워크 끊김·로그인 401 문구 (3개) |
| `market-photo-upload.spec.ts` | 중고 글쓰기 + 사진 2장 올리기 (1개) |
| `messages.spec.ts` | 판매자에게 첫 쪽지 → 대화 이동 (1개) |

## Flutter 웹에서 테스트 쓸 때 알아 둘 것

- 앱이 `ensureSemantics()`로 접근성 트리를 켜 둬서 `getByRole`·`getByText`가 동작한다. 단 **링크가 `link` 역할이 아니라 글자(text)로** 잡히니 목록 글·상단바 메뉴는 `getByText`로 찾는다.
- 에러 안내는 `role="alert"`지만 글자가 `aria-label`로 나오므로 `toHaveText`가 아니라 `toHaveAccessibleName`으로 확인한다.
- 글 입력칸은 `locator.fill()`이 안 먹는 경우가 있다. `typeInto()`(칸 클릭 → 0.3초 대기 → 키보드로 입력)를 쓴다.
- 이동 직후 바로 누르지 말고 새 화면 내용이 보일 때까지 기다린 뒤 행동한다(CI 불안정의 원인이었다).

## 옮김 / 안 옮김 표 (React `frontend/e2e` 스펙 16개 전부)

테스트 수는 React 스펙의 `test(` 개수다(합 324개). Flutter 쪽 e2e는 10개.
"옮김"은 그 스펙 중 핵심 흐름·대표 실패만 골라 옮겼다는 뜻이다.

| React 스펙 (테스트 수) | 결과 | Flutter에서는 |
|---|---|---|
| `core-flow.spec.ts` (3) | **옮김 (3/3)** | `core-flow.spec.ts` 3개 (같은 흐름) |
| `error-messages.spec.ts` (32) | **일부 옮김 (3/32)** | `error-messages.spec.ts`: 글 404, 네트워크 끊김, 로그인 401. 나머지 29개 문구는 `test/error_messages_test.dart`와 각 화면 테스트(`login_page_test`, `post_detail_page_test`, `post_list_page_test` 등)가 덮는다 |
| `market-photo-upload.spec.ts` (36) | **일부 옮김 (1/36)** | `market-photo-upload.spec.ts`: 글쓰기에서 진짜 파일 창으로 사진 2장 → 글 올리기 → 사진 2번 → 상세 이동. 나머지(수정 화면 추가·삭제·사전 검사·10장 한도·실패 문구·잠금)는 `test/market_photo_pages_test.dart`, `test/photo_upload_test.dart`, `test/market_write_pages_test.dart`가 덮는다(파일 창만 가짜 선택기로 대체) |
| `messages.spec.ts` (26) | **일부 옮김 (1/26)** | `messages.spec.ts`: 상세에서 첫 쪽지 → 요청 본문 → 대화 화면 이동. 나머지(못 보내는 이유·쪽지함·답장·실패·401)는 `test/message_pages_test.dart` |
| `messages-extra.spec.ts` (31) | 안 옮김 | 예약중·관리자·1000자·연타·시각·401·주소 같은 세부 규칙 → `test/message_pages_test.dart`, `test/nav_bar_test.dart` |
| `messages-report.spec.ts` (27) | 안 옮김 | 대화 신고 폼의 성공·실패 안내 → `test/report_form_test.dart`, `test/unread_pages_test.dart`(대화 신고 그룹) |
| `unread-badge-hasmore.spec.ts` (6) | 안 옮김 | 안 읽은 수 배지·"이전 쪽지" 안내 → `test/unread_pages_test.dart`, `test/nav_bar_test.dart` |
| `unread-badge-hasmore-extra.spec.ts` (7) | 안 옮김 | 재조회·0통·긴 닉네임·모바일 폭 → `test/unread_pages_test.dart`, `test/admin_conversation_page_test.dart` |
| `market.spec.ts` (46) | 안 옮김 | 목록·상세·글쓰기·수정 화면 → `test/market_list_page_test.dart`, `test/market_detail_page_test.dart`, `test/market_write_pages_test.dart` (글쓰기 요청 본문은 핵심 흐름 e2e의 사진 올리기 테스트가 한 번 더 본다) |
| `market-photos.spec.ts` (9) | 안 옮김 | 목록 썸네일·상세 사진 보기 → `test/market_list_page_test.dart`, `test/market_detail_page_test.dart` |
| `market-search.spec.ts` (12) | 안 옮김 | 제목 검색(요청 쿼리) → `test/market_list_page_test.dart`. 한글 조합 입력은 손 확인표 |
| `market-report.spec.ts` (9) | 안 옮김 | 중고 글 신고 폼 → `test/report_form_test.dart`, `test/market_detail_page_test.dart`, `test/admin_reports_page_test.dart` |
| `admin-conversation.spec.ts` (20) | 안 옮김 | 관리자 신고 목록의 쪽지 배지·신고된 대화 보기 → `test/admin_reports_page_test.dart`, `test/admin_conversation_page_test.dart` |
| `admin-conversation-extra.spec.ts` (11) | 안 옮김 | 기각 실패·번호 이동·읽기 전용 확인 → `test/admin_conversation_page_test.dart`, `test/admin_reports_page_test.dart`. 단 "쪽지 500개 렌더링"과 "Tab→Enter 목록 이동"은 **안 덮음(손 확인표로)** |
| `visa-banner.spec.ts` (21) | 안 옮김 | 상단 비자 일정 띠 문구·단계 → `test/visa_banner_test.dart`, `test/visa_plan_banner_test.dart`, `test/dday_test.dart` |
| `visa-plan.spec.ts` (28) | 안 옮김 | /visa-plan 화면·저장·삭제 → `test/visa_plan_page_test.dart`, `test/visa_plan_store_test.dart` |

안 옮긴 이유 공통: 위 화면들은 서버 응답만 바꿔 가며 화면 문구·요청 본문을 보는 테스트라 위젯 테스트로 같은 내용을 더 빠르고 안정적으로 볼 수 있다.
옮긴 것은 "진짜 브라우저가 있어야 확인되는 것"(주소 이동, 키보드 입력, 파일 창)이다.
(참고: Flutter 위젯 테스트는 React 스펙 한 줄과 1:1이 아니라 묶어서 쓴 곳이 많다. 같은 규칙인지는 파일 머리 주석의 "React의 xxx.spec" 표시로 확인한다.)

## 손 확인표 (자동 테스트로 못 덮어서 사람이 실제 브라우저로 볼 것)

배포 전이나 화면·입력 코드를 크게 바꾼 뒤에 훑어본다. 배포 주소(또는 `flutter run -d chrome`)를 실제 브라우저로 연다.

| 확인할 것 | 왜 자동으로 못 보나 | 확인 방법 한 줄 |
|---|---|---|
| 한글 입력 조합(IME): 검색칸·글쓰기에서 "안녕"을 한 글자씩 조합하며 쓰기 | Playwright의 키 입력은 조합 중 상태를 만들지 못한다 | 글쓰기 제목에 한글로 문장을 치고, 중고 검색칸에 한글을 치며 조합 중 글자가 사라지거나 중복되지 않는지, Enter로 확정 후 검색되는지 본다 |
| Safari/iOS 파일 선택 | 테스트 브라우저는 Chromium뿐이라 iOS의 사진 보관함·카메라 선택은 다르다 | iPhone Safari에서 중고 글쓰기 [사진 추가] → 보관함에서 2장 고르기, HEIC 사진이 안내 문구로 걸러지는지 |
| 키보드 Enter/Space로 파일 창 열기 | 키보드로 누른 클릭은 브라우저가 "사용자 동작"으로 인정하는 방식이 달라 파일 창이 안 열릴 수 있다 | [사진 추가]에 Tab으로 가서 Enter와 Space를 각각 눌러 파일 창이 열리는지 |
| 스크린리더(VoiceOver·NVDA) | 접근성 트리는 보이지만 실제 읽는 순서·표현은 사람이 들어야 안다 | 로그인·글쓰기·쪽지 화면을 스크린리더로 한 바퀴 돌며 칸 이름·오류 안내(alert)가 읽히는지 |
| 큰 사진 10장 메모리 | 가짜 8x8 PNG로는 메모리 부담이 안 생긴다 | 휴대폰 사진(5MB 이상)을 10장 골라 올려 보며 앱이 멈추거나 새로고침되지 않는지 |
| 느린 모바일 첫 로딩 | 로컬 서버는 너무 빠르다 | 크롬 개발자 도구 네트워크를 "Slow 4G"로 두고 첫 화면까지 걸리는 시간·로딩 표시가 나오는지 |
| 이모지·한자 글꼴 폴백 | 빌드에 포함된 글꼴에 없는 글자는 기기마다 다르게 보인다 | 글·쪽지에 😀 漢字 ㉠ 같은 글자를 쓰고 네모(두부)로 깨지지 않는지 PC·휴대폰에서 |
| 뒤로가기/새로고침 딥링크 | 가짜 서버에서는 실제 배포 서버(nginx)의 주소 처리가 검증되지 않는다 | 배포 주소에서 `/posts/1`, `/market/1`을 직접 열기·새로고침, 글 상세 → 뒤로가기가 목록으로 가는지 |
| Ctrl+F 찾기·글자 선택·복사 (알려진 한계) | Flutter는 화면을 그림으로 그려서 브라우저 찾기가 글자를 못 찾을 수 있다 | Ctrl+F로 글 제목을 찾아 보고, 글 본문을 드래그해 복사되는지 확인한다(안 되면 알려진 한계로 기록) |
| 쪽지가 아주 많은 대화(500개) 렌더링 | 위젯 테스트·e2e 모두 안 덮는다 | 신고된 대화(관리자 화면)에 쪽지 500개짜리 가짜 데이터를 넣고 스크롤이 버벅이거나 멈추지 않는지 |
| 관리자 신고 목록에서 Tab → Enter로 대화 열기 | 키보드 포커스 이동은 위젯 테스트·e2e 모두 안 덮는다 | 신고 관리 목록에서 Tab으로 쪽지 줄에 가서 Enter로 신고된 대화가 열리는지 |
| 자동완성·비밀번호 관리자 | 가짜 환경에서는 브라우저의 비밀번호 저장 흐름이 안 일어난다 | 로그인 후 브라우저가 비밀번호 저장을 묻는지, 다음에 아이디·비밀번호가 채워지는지 |
