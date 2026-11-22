# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-22, 집 PC(영문 사용자 폴더 C:\Users\seoka) (**S27 운영 부하 테스트 시작 — S27-a 스크립트 만들고 리뷰 남음**)

## 지금 어디까지
- **S26(화면 새 단장, Flutter 웹·앱)** 끝·**운영 배포됨**(2026-11-22 Deploy 버튼, 커밋 a04b7ab, 서버·compose·nginx 변경 0). 운영 사이트에서 목록·글 보기·중고·비자·쪽지 탭→로그인·404 새 모습 확인. backlog S26·S26-g 체크 + "끝난 일" 배포 줄(f15aa1a). 결정·나중 메모는 backlog S26 항목, 디자인 기준 `docs/design.md`. 사용자 폰 앱도 S26 새 모습(2026-11-22 릴리스 APK, 커밋 772e40d 기준 — 비자 띠 × 접근성 수정 포함, `adb install -r`로 덮어 설치, 운영 목록 확인). 웹 운영에는 772e40d가 아직 안 올라감(다음 Deploy 때).
  - S26-i(중고 목록·쪽지함 피드형), S26-j(위 막대 링크 48 `AppLink tall`·`AppSizes.tapMin`, 탭 첫 화면 머리말 `BoardTitle` 30, 로그인·회원가입은 탭 선택 없음, 날짜 힌트, 빈 썸네일·빈 이니셜 흰 칸+테두리), S26-k1~k8(공용 머리 `lib/widgets/author_head.dart` AuthorHead — 게시판·중고·비자 후기 글 보기, 쪽지 대화·관리자 대화 말풍선 흰 판 안, 날짜 칸 3곳 공용 `lib/widgets/date_field.dart` DateField, 내 정보 이니셜·바로가기 FeedRow(프로필 아래), 쓰기 폼 본문 15·1.65 LabeledField `below`, 로그인·가입·404 Panel·PageTitle, 챗봇 참고한 글 FeedRow, row_link.dart·placeholder_page.dart 삭제).
  - 밝은 "여권 남색+노랑": 토큰 `lib/theme/app_colors.dart`(canvas·paper·ink #1C2B4F·accent 파랑 #2450B8(선택·링크)·highlight 노랑(바탕 전용, 위엔 ink)…), `ColorScheme.light`, 대비 계산 테스트 `test/color_contrast_test.dart`(글자 4.5·테두리/비활성 3, 토큰 값 전부 다름).
  - 아래 탭 막대 `lib/widgets/bottom_tabs.dart`(게시판·비자·중고·쪽지·내 정보, 주소 첫 마디로 선택, 쪽지 노랑 배지, 키보드 열리면 숨김), 위 막대는 사이트 이름 + 로그인·회원가입 또는 챗봇·닉네임·로그아웃. 비자 일정·관리 메뉴는 내 정보 바로가기.
  - 피드형: 게시판 목록(이니셜 `initial_avatar.dart`, 말머리 칩, 줄 전체 링크), 글 보기·댓글(조용한 신고), 글쓰기 줄 간격 1.65, **비자 후기 목록도 피드형**. 공용 부품 `feed_row.dart`(FeedRow)·`panel.dart` FeedPanel·`write_fab.dart`(WriteFabLayout 노랑 연필 — 게시판·비자 후기·중고(인증 회원만)).
  - 필요 없는 안내 문구 삭제(HTTP 비밀번호 경고 3곳 등), 웹 앱 이름 "게시판", 안드로이드 아래 띠 흰색.
- **S0~S14 + UI1~UI3 + S16~S18 + S20 + S21 + S22 + S23 + S24 + S26 완료·배포됨.** 운영 주소 **`https://kampus.<도메인>`**(Cloudflare에서 산 도메인, 실제 이름은 사용자가 앎 — 저장소·메모에 안 씀). 운영 vision·gemini·**push(fcm)** 켜짐. 마지막 운영 배포 2026-11-22 커밋 a04b7ab(S26, Deploy 버튼만. 그 전 a5d48fd S24-g, eebac61 S24 첫 배포).
- **방향(2026-11-16)**: "외국인 유학생 통합 에타", 유학생만 글쓰기(표준입학허가서 인증).
- **S24(비자 일정 푸시 알림, FCM)** 끝(2026-11-21, 결정·한계는 backlog S24 항목과 그 아래 줄들):
  - 서버: V16 device_tokens(회원당 10, 비밀번호 변경 때 삭제, PUT/DELETE /users/me/device-tokens), FcmPushSender(google-auth-library로 출입증만, HTTP v1 직접, PUSH_MODE off/fcm, 결과 SENT/TOKEN_INVALID/REJECTED/FAILED), VisaPushJob(켜지고 5분 뒤 + 1시간마다, 서울 9~21시, 단계 들어갈 때 1회, V17 보낸 기록, 쓰기 잠금은 회원 행 먼저).
  - 앱: lib/push/(app_push 3개 묶음, push_registrar, push_permission_panel). 팝업은 비자 일정 화면 [알림 켜기]에서만, 앱이 화면에 떠 있으면 알림 표시 안 함. google-services.json은 커밋 안 함(아래 주의).
  - 운영 확인 끝: V16·V17 적용, "푸시 fcm 켜짐", 테스트 계정 wntjrdka로 알림 도착, 로그아웃하면 토큰 0(DELETE 본문이 nginx 통과). 사용자 폰에 새 릴리스 APK(운영용) 설치됨.
- **S23(안드로이드 앱)** 끝(2026-11-20, 결정·나중 메모는 backlog S23 항목):
  - 같은 Flutter 코드로 앱. 앱 전용 부품은 전부 조건부 import 3개 묶음(입구·stub·io) + `main.dart` 앱(비웹) 갈래에서 바꿔 끼움: `lib/api/app_cookies*.dart`(PersistCookieJar + CookieManager + XSRF 옮기기, ApiClient `extraInterceptors`), `lib/common/app_external_link*.dart`(url_launcher), `lib/common/app_photo_picker*.dart`(image_picker, 최대 10장, 맨 앞 바이트로 종류, 임시 사본 finally 삭제), `lib/common/app_history.dart`(뒤로 가기 방문 기록, AppShell PopScope, BoardApp `enableAppBack`).
  - 404는 ShellRoute 안 catch-all `/:rest(.*)`(errorPageBuilder는 안전망). AndroidManifest: allowBackup=false + data_extraction_rules, enableOnBackInvokedCallback.
  - 릴리스 APK: 디버그 키 서명, **집 PC에서만 빌드**, 설치는 `adb install -r` 또는 파일 복사(deploy.md "안드로이드 앱 릴리스 빌드·설치 (S23)"). 사용자 폰에 릴리스 앱 설치됨(운영 서버용).
  - 운영 확인 끝: 웹(목록·로그인 유지·404), 앱 LTE(목록·사진·로그인 유지·16분 뒤 재발급·바깥 링크·뒤로 가기·사진 업로드). EC2 켤 때 DNS 자동 고침도 첫 실제 재부팅에서 정상.
- **S22(도메인 + HTTPS)** — 결정은 backlog S22 항목:
  - nginx: 80은 acme-challenge·`= /actuator/health`만 직접, 나머지 https로 301 / 443은 Let's Encrypt 인증서(`--cert-name board`, 경로에 도메인 없음) + 공용 규칙 `flutter_app/board-locations.conf` include. HSTS 없음.
  - compose app에 `SERVER_FORWARD_HEADERS_STRATEGY: native`(없으면 https 뒤 쓰기 요청 403 Invalid CORS request).
  - **Elastic IP 안 씀** → 서버 crontab `@reboot sleep 20`으로 `~/board/cloudflare-ddns.sh`(저장소 `deploy/cloudflare-ddns.sh`)가 공인 IP를 Cloudflare A 레코드에 적음. 인증서 갱신은 `@reboot sleep 600` + 매일 4:17(서버 시계). 서버에 cronie 설치함.
  - 저장소 Variables `SITE_DOMAIN` 등록 → Deploy가 https 헬스체크도 함(도메인 이름을 잊으면 여기서 확인).
  - Cloudflare: 도메인 자동 갱신(만료 2027-11-19), DNS A `kampus` 프록시 끔·TTL 1분, API 토큰 `kampus-ddns`(그 영역 DNS 편집만, 만료 없음). 인증서 만료 2027-02-17(자동 갱신, 이메일 등록 안 함).
- **S21(유학생 인증 보강)** 끝: 관리자 인증 취소·취소 번호 자동 승인 막기·Vision 하루 상한 30. 알려진 한계: 탈퇴→재가입으로 취소 번호 우회, 허가서 번호 전국 유일 여부 미확인(backlog S21).
- 테스트: 백엔드 2001개(건너뜀 13), Flutter **1962개**, e2e 16개 통과. DB는 **V17까지**(S24-c).
- Notion: **S26 카드(🎨 화면 새 단장) + 단계 페이지 a~k + 🧭 파트 전체 정리(단계 리뷰 요약 표, 필수 12·권장 76) 끝**, 메인 진행 현황 S26 체크(2026-11-22). 2026-11-22 메인 페이지(소개·기술 스택·아키텍처 그림·컨테이너 표·바뀐 것 S26·진행 현황 S26 미체크·학습 리뷰 설명)와 하위 "전체 흐름 한눈에 보기"(S26 기준으로 제목·내용) 갱신. S24 카드(🔔 비자 일정 푸시 알림) + 단계 페이지 a~f + 도구 사전 "Firebase · FCM". S23 카드(📱 휴대폰 앱) + 페이지 5장(앱 설정 정리 a~c, d1, d2, e, f)·상위 페이지 진행 현황 S23 체크·아키텍처 표 S23 줄 끝. **notion-guide 규칙 바뀜(2026-11-20): 프론트 단계도 "프론트 단계 페이지 틀"로 가볍게 씀.** S21 🧭 파트 전체 정리는 아직.

## 다음에 할 일
- **바로 다음: S27(운영 부하 테스트 + AWS 모니터링으로 병목 찾기) 이어서.** 2026-11-22 시작, 계획 승인됨(사용자 "추천안대로 시작"). 백로그 S27 항목 참고.
  - 단계: a 측정 준비(k6 스크립트·문서) → b 로컬 시험(tester) → c 운영 기준 측정(사용자 손, 1분 상세 모니터링 켜기) → d 원인 분석 → e 원인 하나만 고치기·배포 → f 같은 조건 재측정·전후 표, 상세 모니터링 끄기.
  - 승인된 추천안: 목표 숫자는 기준 측정 뒤 정함(5→10→20→40명, 단계당 1분, 1초 쉼), 자동 멈춤 실패율 10%·p95 5초(스모크 1%·1초), CloudWatch 에이전트 안 깔고 `docker stats`·`free -m` 기록, 앱 내부 지표(actuator metrics)는 1차엔 안 열고 Hikari 타임아웃 로그·`pg_stat_activity`로, 부하 대상 주력 `GET /posts` + 보조 글 하나·댓글·중고 목록·비자 후기 통계(+`WEB_URL` 있을 때 `/`), 운영 DB에 가짜 글 안 넣음(데이터 많을 때는 로컬 재현), 기록은 `loadtest/README.md`(실행법)·`docs/loadtest.md`(전후 표).
  - **S27-a 끝·커밋함**(2026-11-22): `loadtest/lib/common.js`(BASE_URL 검사·GET 목록·글 id·단계 꼬리표·요약 저장), `loadtest/read-smoke.js`, `loadtest/read-ramp.js`, `loadtest/README.md`, `docs/loadtest.md`, `.gitignore`에 `loadtest/results/`. 리뷰(메인이 직접, review 에이전트는 auto mode에 막힘) 필수 1 반영: 단계별 숫자가 안 나오던 것 → 요청에 `stage` 꼬리표(유지 구간만, 전환은 `ramp`) + 항상 통과하는 하위 지표 thresholds로 요약에 단계별 줄. 준비 요청은 `phase:setup`으로 기준·숫자에서 뺌. 진짜 k6 실행은 아직(S27-b). **다음: S27-b.**
  - BASE_URL: 운영 `https://<사이트주소>/api`, 로컬 `http://localhost:8080`(bootRun은 /api 접두사 없음). 사이트 주소는 코드·문서에 쓰지 않음.
  - S27-b 전에: k6가 이 PC에 없음 → `winget install k6 --source winget` 실행 전에 사용자 확인. 운영 측정 전에 콘솔에서 t3 크레딧 모드(무제한/표준) 확인, 현재 운영 이미지 SHA 적기(deploy.md 7줄은 S24 기준이라 낡음).
  - planner가 찾은 병목 후보(측정 뒤 결정): ① Hikari 풀 3(`docker-compose.yml:80`) vs Tomcat 기본 200, ② 앱 512m·힙 60%·SerialGC, ③ t3 CPU 크레딧, ④ 목록 페이지 나눔 없음, ⑤ nginx gzip·keepalive 없음, ⑥ 인덱스, ⑦ 서버 전체 메모리. N+1·429는 후보에서 뺌.
- 포트폴리오 이미지: 저장소 루트 `portfolio-shots/`(커밋 안 함, 표지 + 화면 7장 + 원본 + 사진 출처). 다시 찍는 법: e2e mockApi로 임시 spec(412x915, deviceScaleFactor 3) → 찍고 지우기. 찍다 발견: 390폭에서 비자 통계 표 오른쪽(평균 처리일) 잘림(나중 후보).
- 후보(사용자 정하기): backlog S26 "나중"(320폭 20자 닉네임이면 위 막대 3줄, **웹 접근성 트리에서 FeedRow가 link 역할이 아니라 텍스트로 나옴 — 모든 피드형 목록 확인 필요**, 목록 페이지 나눔(서버 API 필요)). 그 밖 backlog 보류·나중(S25 로그인 평범한 코드, S19 챗봇 일반 답변, 욕설·스팸 검사, 챗봇 뜻 검색, S24 "나중").
- (선택) S23 🧭 파트 전체 정리·파트 전체 리뷰: 서버 코드가 안 바뀐 앱 파트라 할지 말지 사용자가 정하기로 함.
- 미뤄 둔 정리(운영): 되돌릴 일 없으면 서버 `~/board/docker-compose.yml.bak-s21`·`.bak-s22` 정리. S18로 되돌릴 일 없으면 `~/board/.env.bak-s20` 삭제, `.env`의 MAIL_* 6줄 삭제, **Gmail 앱 비밀번호 폐기**, deploy.md 메일 절 삭제, backlog S20 체크·Notion 진행 현황 S20 체크.
- 작은 일: **집 PC 로컬 `.env`의 `VISION_API_KEY` 줄 삭제**(키는 이미 폐기), 자동 승인 시험 계정 정리, Notion S21 🧭 파트 전체 정리, (선택) 구글 503 빈도, 운영 테스트 중고 글 정리. 사용자 폰 "자동 차단" 다시 켜기(2026-11-20 S23 끝에 안내함).

## 진행 중인 단계
- **S27-b**(로컬 시험, k6 설치 전 사용자 확인). 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함), `portfolio-shots/`(포트폴리오용, 커밋 안 함). push 안 함.
- 운영 서버 메모: `~/board/.env`(권한 600)에 `PHOTO_S3_BUCKET`·`LLM_MODE=gemini`·`GEMINI_API_KEY`·MAIL_*(되돌리기용)·**`PERMIT_HASH_SECRET`(64자, 절대 바꾸지 않음, 사용자가 서버 밖에도 보관)**·`PERMIT_OCR_MODE=vision`·`VISION_API_KEY`·**`COOKIE_SECURE=true`·`CF_API_TOKEN`·`CF_ZONE_ID`·`CF_RECORD_NAME`**. 서버 파일: `~/board/cloudflare-ddns.sh`(700), `~/board/certbot/conf`(인증서, root 700)·`www`, 로그 `~/board/ddns.log`·`~/board/certbot/renew.log`. **S24: `.env`에 `PUSH_MODE=fcm`, 서비스 계정 키 `~/board/fcm/service-account.json`(주인 100:101 = 컨테이너 app 사용자, 폴더 700·파일 400, sudo로만 보임), compose가 `./fcm`을 `/app/fcm`에 읽기 전용 마운트.** 푸시 끄기 = `.env` `PUSH_MODE=off` 후 `up -d app`. 백업: `docker-compose.yml.bak-s20`(S18)·`.bak-s21`(S20)·`.bak-s22`(S21)·`.bak-s24`(S23), `.env.bak-s20`. IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. vision 끄기 = `.env` `PERMIT_OCR_MODE=off` 후 `up -d app`. **되돌리기 = deploy.md "S24 되돌리기"/"S23 되돌리기"(web만 bd6c70b로 고정)/"S22 되돌리기"/"S21 되돌리기"**.
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- **Flutter 코드에 `dart format` 돌리지 않는다**(S26): 이 저장소는 긴 줄 스타일이라 파일 전체 줄바꿈이 바뀐다. `post_list_page.dart` 아랫부분만 80칸으로 포맷된 채 남아 있음(동작 무관). 서브에이전트 프롬프트에 "dart format 금지".
- **Semantics 함정**(S26): `excludeSemantics: true`는 안쪽 InkWell의 tap까지 지워 스크린리더로 못 누른다 → `onTap`을 Semantics에 같이 준다(FeedRow·bottom_tabs 방식). FeedRow는 라벨 하나로 합쳐져 e2e에서 `getByText('제목', {exact:true})`가 안 잡힌다 → `getByText(/^제목, 가격, 상태/)`처럼 합친 이름으로(chat.spec).
- **위젯 테스트 함정**(S26): 한 테스트에서 `pumpApp`을 두 번 부르면 두 번째 화면이 안 나와 "높이 비교" 테스트가 가짜로 통과한다 → 테스트당 한 번만, 또는 먼저 `pumpWidget(SizedBox.shrink())`. "넘침 예외 없음"만 보면 4~5줄로 접히는 걸 못 잡는다 → "한 줄"은 높이로 잰다. 리뷰에 "가짜 통과/실패할 수 없는 테스트인지"를 꼭 묻는다.
- **달력 직접 입력 형식**(S26-j2): 한국어 로케일 showDatePicker 입력 모드는 "2026. 12. 01."(공백·끝 점)만 받는다("2026-12-01"·"2026.12.01" 거절) → fieldHintText를 '2026. 12. 01.'로. DateField의 lastDay·today는 누르는 순간 계산(`String Function()`).
- **서브에이전트 병렬**(S26-k5~k8): 건드리는 파일이 안 겹치는 단계는 build 둘을 동시에, tester(스크린샷)와 review도 동시에 돌리면 빠르다. 프롬프트에 "상대 파일 금지, design.md는 해당 줄만 Edit"을 쓰고 커밋은 묶는다. tester가 웹 빌드 중일 때 lib을 고치면 꼬일 수 있으니 반영 build는 tester가 끝난 뒤.
- **화면 스크린샷(백엔드 없이)**: tester가 e2e의 Playwright + `mockApi.ts`로 임시 spec(`e2e/tmp-*.spec.ts`, 끝나면 삭제)을 만들어 375/1200 PNG를 scratchpad에 찍는 방식이 잘 됨. Flutter 웹은 자동 조작으로 키보드 포커스 표시가 안 켜져 포커스 모양은 못 찍음.
- **에뮬레이터 확인(집 PC)**: 8080은 사용자의 다른 프로젝트가 쓰고 있어 게시판 백엔드를 `SERVER_PORT=8081 SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`(+ `.env` 읽기, 임시 `PERMIT_HASH_SECRET`, `APP_COOKIE_REFRESH_PATH=/auth`, `PUSH_MODE=off LLM_MODE=off`)로 띄우고, `flutter build apk --debug --dart-define=API_BASE_URL=http://10.0.2.2:8081` → `adb install -r` → `adb shell monkey -p com.kampus.board …`로 실행, `adb exec-out screencap -p`로 찍고 `adb shell input tap/keyevent`로 조작(MSYS_NO_PATHCONV=1). AVD 이름 `Medium_Phone_API_37.0`. 끝나면 `adb emu kill` + 8081 프로세스 종료.
- **집 PC의 `gh`는 로그인 안 돼 있음**: CI 결과·Deploy 실행은 사용자가 GitHub Actions 화면에서(또는 `gh auth login` 뒤).
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **Deploy 버튼은 평소처럼 써도 된다(S22 이후).** 단 web 이미지는 서버에 인증서(`~/board/certbot/conf/live/board/`)가 있어야 뜬다. **서버를 새로 만들거나 certbot 폴더를 지웠다면** Deploy 전에 deploy.md "S22 HTTPS 전환"의 인증서 첫 발급(standalone, board-web 멈춤, `--dry-run` 먼저)부터. 같은 이름 발급 실패·재발급을 반복하면 Let's Encrypt 발급 제한.
- **EC2 켠 뒤 1~5분은 도메인으로 안 열릴 수 있다**(DNS 자동 고침 대기, 2026-11-20엔 약 1분). IP로 https를 열면 인증서 이름 경고, IP는 `http://<IP>/actuator/health`(Deploy 헬스체크)만. SSH는 DNS가 맞은 뒤 `ec2-user@kampus.<도메인>`로도 가능.
- **Cloudflare 프록시(주황 구름)는 켜지 않는다**: 100초에 끊겨 허가서 신청(nginx 150초)과 충돌, 인증서·자물쇠가 두 겹이 됨. 토큰이 새면 Cloudflare에서 Roll/삭제 후 서버 `.env` 교체.
- **릴리스 APK 빌드 전에 도메인부터 확인**(2026-11-20 비슷한 남의 도메인을 한 번 받아서 빌드함, 설치 전에 잡음): `https://kampus.<도메인>/actuator/health`가 `{"status":"UP"}`이고 네임서버가 `*.ns.cloudflare.com`인지(`https://dns.google/resolve?name=<도메인>&type=NS`). 아니면 빌드·설치 금지. 도메인은 GitHub Variables `SITE_DOMAIN`이나 Cloudflare에서 확인.
- **커밋 전 비밀 값 검사는 "추가된 줄"만**: `git diff --cached | grep '^+'`(추가된 줄)에서 제미나이 키 모양(구글 키 헤더 이름, "AI"+"za"로 시작하는 키, "A"+"Q"+마침표로 시작하는 토큰)과 실제 도메인 글자를 grep -c 해서 0이어야 커밋. 전체 diff로 보면 문맥 줄이 걸리고(2026-11-20), 1이 나와도 멈추지 않은 실수가 있었음 → 0이 아니면 반드시 멈추고 무엇이 걸렸는지 본다. (이 문장에 패턴 글자를 그대로 쓰면 검사에 걸리므로 풀어서 적음) **S24부터 서비스 계정 키 모양도 같이 본다**: 추가된 줄에서 "BEGIN" + " PRIVATE KEY"와 따옴표로 감싼 "private" + "_key" 글자도 0이어야 한다(테스트가 지어낸 키를 만드는 `PushConfigTests.java` 줄은 예외로 보고 무엇인지 확인).
- **Firebase 서비스 계정 키 JSON은 저장소 폴더 밖에 둔다**(S24). .gitignore는 `*firebase-adminsdk*.json`·`fcm-service-account*.json` 이름만 막아서, 이름을 바꿔 저장소 안에 두면 커밋될 수 있다.
- **안드로이드 빌드에는 `flutter_app/android/app/google-services.json`이 필요하다**(S24-d, Firebase 콘솔 프로젝트 `kampus`의 안드로이드 앱 com.kampus.board에서 받은 명찰 파일). 비밀은 아니지만 안의 API 키가 커밋 전 비밀 검사에 걸려서 **커밋하지 않는다**(.gitignore). 사용자가 저장소 밖에 사본을 둠. 없으면 `flutter run`/`flutter build apk`가 "File google-services.json is missing"으로 실패한다(웹 빌드·flutter test·CI는 무관). 노트북에서 앱을 빌드하려면 사본을 복사하거나 콘솔에서 다시 받는다.
- **로컬 푸시 실기기 시험(S24-e, 2026-11-21 성공)**: 서비스 계정 키는 집 PC `C:\Users\seoka\Documents\` 안의 `...-firebase-adminsdk-....json`(저장소 밖). 백엔드를 `PUSH_MODE=fcm`, `FCM_CREDENTIALS_FILE=<그 경로>`, 시험용 `APP_PUSH_VISA_INITIAL_DELAY=PT20S APP_PUSH_VISA_INTERVAL=PT1M APP_PUSH_VISA_WINDOW_START_HOUR=0 APP_PUSH_VISA_WINDOW_END_HOUR=24`(+ `APP_COOKIE_REFRESH_PATH=/auth`, 임시 `PERMIT_HASH_SECRET`)로 띄우고, 앱에서 [알림 켜기] 허용 뒤 **앱을 홈으로 보내야** 알림이 보인다(앱이 떠 있으면 표시 안 함). 다시 보내려면 로컬 DB `visa_plan_notifications`에서 그 회원 행을 지운다. 로컬 시험 계정 `seokam`(유학생 인증됨, 로컬 전용).
- **로컬 DB 연결 20개 제한**: compose의 `max_connections=20`이라, 같은 board-postgres를 쓰는 다른 앱(사용자 IntelliJ의 login-practice가 `login_practice` DB로 10개)과 게시판 백엔드(기본 10개)가 같이 뜨면 꽉 차서 psql이 "too many clients"로 거절된다. 그럴 땐 게시판 백엔드를 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5`로 띄운다. 8080을 login-practice가 쓰고 있으면 게시판은 `SERVER_PORT=8081` + `adb reverse tcp:8081 tcp:8081` + 앱 `API_BASE_URL=http://localhost:8081`.
- 사용자 폰의 "게시판" 앱은 2026-11-22 릴리스 APK(운영용, 772e40d 기준)다. 로컬 시험용 디버그 앱을 깔면 덮어써지니 시험 뒤엔 릴리스를 다시 깐다. 앱 이름은 "게시판"(kampus는 Firebase 프로젝트 이름). ssh 키(`board-ec2`)에는 비밀번호가 있어서 메인 대화(Claude)가 ssh를 직접 돌리면 입력 대기로 멈춘다 → 서버 명령은 사용자가 친다.
- **실기기(갤럭시) 개발**: 삼성 "자동 차단"(설정 → 보안 및 개인정보 보호)이 켜져 있으면 USB 디버깅 스위치가 회색으로 잠김 → 끄고 켜기(개발 끝나면 다시 켜기). 사용자 USB(어댑터)가 자주 끊기고, 끊기면 `adb reverse` 터널도 사라져 앱이 "서버에 연결할 수 없음" → 다시 꽂으면 터널 재설정(뒤에서 3초마다 `reverse --list` 확인해 다시 잇는 루프를 돌려 두면 편함, 끝나면 끄기). PC가 폰을 "모뎀"으로만 보면 USB 테더링 끄기·기본 USB 설정 "파일 전송". 설치 직후 첫 요청이 `Failed host lookup: 'localhost'`로 한 번 실패한 적 있음(일시적, 껐다 켜면 됨).
- **로컬 앱 시험 백엔드**: `PERMIT_HASH_SECRET`이 이 PC `.env`에 없어 `export PERMIT_HASH_SECRET="$(openssl rand -base64 48)"`로 임시값, 앱 로그인 시험이면 `APP_COOKIE_REFRESH_PATH=/auth`도(CLAUDE.md). `flutter run`을 입력 없이 뒤에서 돌리면 설치·실행 뒤 분리돼 핫 리로드 없음(고치면 다시 `flutter run`). 로컬 DB 계정 `fjfjd`는 2026-11-20 SQL로 연세대 유학생 인증 처리됨(로컬 전용).
- **세션을 폴더 없이 시작했다가 프로젝트로 옮겨 오면 서브에이전트 쓰기 훅(`scripts/allow-only.sh`)이 tester의 `src/test/` 쓰기를 막는다**(2026-11-18, 추정·미확인). 훅을 고쳐 우회하지 말 것.
- **서버 `.env`를 고친 뒤 `up -d app` 전에 반드시 확인**: `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env`가 1. `.env` 값은 `cat` 금지, 확인은 `grep -c`나 `grep -o '^[A-Z_]*='`(이름만). 토큰·키는 nano 화면에서만 붙여넣기(셸 프롬프트에 붙이면 `.bash_history`).
- **사용자 PowerShell은 `C:\Windows\System32`에서 열려 있다**: scp에 상대 경로를 주면 "No such file". 명령은 저장소 파일 **전체 경로**로 준다. ssh·scp는 `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<IP>`(노트북은 PATH에 ssh 없음). `<IP>` 같은 자리표시는 쓰지 말고 사용자에게 IP를 받아 완성 명령을 준다.
- **사용자의 이메일을 외부(Let's Encrypt 등)에 넣지 않는다**(certbot은 `--register-unsafely-without-email`로 함).
- **사용자 폴더가 한글인 PC(C:\Users\주석암)에서 Flutter**: `%USERPROFILE%\flutter` 3.50.0. `flutter test`는 한글 경로로 impellerc가 죽는다 → PowerShell `subst R: "$env:USERPROFILE"` 뒤 `$env:PUB_CACHE="R:\AppData\Local\Pub\Cache"`, PATH 앞에 `R:\flutter\bin;C:\Windows\System32\WindowsPowerShell\v1.0`, `R:\OneDrive\Desktop\board-vibe-coding\flutter_app`에서 실행. 서브에이전트 프롬프트에도 넣는다. **안드로이드 빌드(특히 릴리스)는 영문 폴더 집 PC에서만**(디버그 키가 PC마다 달라 덮어 설치 안 됨).
- **안드로이드 빌드 환경(집 PC)**: Android Studio(Standard), SDK `C:\Users\seoka\AppData\Local\Android\Sdk`(platform 35·36·37, build-tools 36, CMake 3.22.1, 에뮬레이터, adb `platform-tools\adb.exe`), **cmdline-tools는 SDK Manager → SDK Tools에서 따로 설치**, **NDK 28.2는 첫 빌드 때 `sdkmanager.bat --install "ndk/28.2.13676358"`**. 플러그인(path_provider 등)이 들어간 뒤 첫 빌드 때 Flutter가 SDK Platform 35·CMake를 자동 설치함(노트북도 같을 것). `android/.gitignore`(Flutter 기본)가 `gradlew`·wrapper jar·`local.properties`를 빼므로 다른 PC에선 `flutter build`/`flutter run`으로 빌드. 빌드 뒤 `flutter_app/android/gradlew.bat --stop`. 디버그 APK 약 160MB, 릴리스 54.8MB. 노트북(메모리 작으면) `android/gradle.properties` -Xmx8G를 4G로.
- **영문 사용자 폴더 PC(C:\Users\seoka, 집 PC)에서는 `flutter test`·e2e가 그냥 돈다**(Git Bash `export PATH="/c/Users/seoka/flutter/bin:$PATH"`). bootRun은 `.env`를 자동으로 안 읽음(`set -a; . ./.env; set +a`). Docker Desktop이 꺼져 있으면 켜야 Testcontainers·docker 명령이 됨.
- **로컬 nginx 확인**(include 파일·인증서 필요): 자체 서명 인증서(`alpine/openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj /CN=localhost`)를 저장소 밖 `<le>/live/board/`에 만들고 `MSYS_NO_PATHCONV=1 docker run --rm -v "<nginx.conf>:/etc/nginx/conf.d/default.conf:ro" -v "<board-locations.conf>:/etc/nginx/snippets/board-locations.conf:ro" -v "<le>:/etc/letsencrypt:ro" --add-host app:127.0.0.1 nginx:1.30-alpine nginx -t`. 백엔드까지 붙인 리허설은 사용자 정의 네트워크 + `alpine/socat`(별칭 app → host.docker.internal:8080)으로. **리허설은 Origin 붙인 쓰기 요청까지** 볼 것.
- **deploy 에이전트는 훅으로 Dockerfile·compose·workflows·application-prod.yml·deploy.md만 고칠 수 있다.** nginx 설정·api.md·`deploy/` 스크립트·.gitignore·build.gradle.kts는 메인 대화나 build가 고친다. deploy 에이전트가 이미지 이름을 지어낸 적 있음(`demo-app` — 실제는 `seokam36/demo`·`seokam36/demo-web`).
- **테스트가 nginx를 안 거친다**(MockMvc·e2e): 업로드 한도·시간 제한·https는 배포 뒤 실제로 확인.
- **`PERMIT_OCR_MODE`**: 기본 off. vision이면 `VISION_API_KEY` 필수, 이상한 값이면 앱 시작 실패. 테스트 설정은 off. 실제 구글 호출 테스트는 `VISION_LIVE_TEST=true` + 키. **운영에서 허가서 신청 시험은 하지 않는다**(진짜 OCR·개인정보·하루 30).
- **허가서(S20) 개인정보 규칙**: 로그에 사진 key·`permits/`·번호·해시·판독 글자·키 금지. 테스트 값은 지어낸 것만(실기기 시험도 아무 풍경·스크린샷 사진).
- **판정 규칙(S20-d1)**: 국적은 허용 목록 방식 유지. 위조는 못 막음(알려진 한계).
- **자동 승인(S20-d2)**: 판독·판정은 PENDING 커밋 뒤 트랜잭션 밖. 승인은 `AdminPermitService.lockPending` 재사용.
- **앱 안 주기 작업은 cron 대신 fixedDelay + initialDelay**(EC2를 평소 꺼 두므로). 서버 crontab은 DNS·인증서용만.
- **실물 시험 도구 `PermitSampleLiveTests`**: `VISION_LIVE_TEST=true` + 키 + 저장소 밖 `PERMIT_SAMPLE_DIR`. 끝나면 `build/test-results`·`build/reports` 정리.
- **서브에이전트가 대화형 명령이나 뒤에서 도는 gradle을 남긴 적 있음** → 프롬프트에 "python 등 대화형 명령 금지, Edit 도구로, 뒤 작업 남기지 말 것". 서브에이전트가 두 번째 보고를 못 보내 결과가 안 올 때가 있음 → 남긴 시험 도구로 메인이 직접 확인.
- **review 에이전트는 git을 못 돌린다** → 메인이 diff를 scratchpad 파일로 저장해 경로를 넘긴다.
- **Notion**: 표 칸에 `|` 금지. 표 줄만 따로 update_content로 넣으면 표 밖 글자가 됨(머리 줄과 붙여서 넣기). 토글은 `<toggle>`(`<details>`는 변환 경고). ASCII 그림은 ```text. update_content의 old_str은 fetch 결과 그대로(`_`가 `\_`로 이스케이프돼 있음).
- **IDE(VS Code Java)의 "getId() undefined"·"log cannot be resolved" 진단은 무시**(Lombok).
- **Flutter 작업 메모**: 이동은 `context.go`만, FormData는 `Options(extra: {ApiClient.formDataFactoryKey: make})`, 파일 고르기는 `photoPicker`(앱은 `AppPhotoPicker`), 미리보기는 `lib/common/picked_photo_image.dart`, 허가서 화면은 `lib/permit/`, 상단바는 `lib/widgets/nav_bar.dart`(메뉴 순서 테스트 `test/nav_bar_test.dart`, '비자 일정'은 학교 인증 회원만), 앱 뒤로 가기는 `lib/common/app_history.dart` 방문 기록(BoardApp 앱 모드, AppShell PopScope). e2e는 `flutter build web --web-content-hash` 뒤 `flutter_app/e2e`에서 `npm test`. 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`. 프로젝트 린트가 private 필드 초기화에 `this._x` 형식(prefer_initializing_formals)을 요구함.
- **제미나이(S18)**: 로컬은 `.env`에 `GEMINI_API_KEY` + `LLM_MODE=gemini`.
- **compose가 바뀌는 배포는 Deploy 버튼만으로 안 됨**: compose 백업·scp 먼저(deploy.md). 새 비밀 값은 서버 `.env`에 nano로. 바뀌었는지는 `git diff --stat <직전 배포 SHA>..HEAD -- src build.gradle docker-compose.yml flutter_app/nginx.conf flutter_app/board-locations.conf flutter_app/Dockerfile Dockerfile`로.
- **로컬 LLM(S17)**: 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`. 백엔드 테스트는 Docker Desktop 필요. Spring AI 재시도 함정 주의.
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.**
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs?head_sha=<40자리 SHA>` → run id로 `/jobs`(JSON은 파일로 저장해 `node -e`로 읽으면 편함). Deploy 전에 images까지 초록 확인.
- **docs 파일을 Git Bash `sed -i`로 고치지 말 것**(CRLF가 LF로 바뀜). Edit 도구로. backlog는 작업 폴더에서 LF로 바뀌어 있음(2026-11-22 확인, git이 CRLF 경고만 냄) → 끝에 줄을 붙이기 전에 `grep -c $'\r' docs/backlog.md`로 확인하고 0이면 `printf '%s\n'`.
- **표 바꾸는 기능은 새 V 파일**(다음은 `V18__...sql`). 커밋된 V 파일은 고치지 않는다. ddl-auto validate.
- **FK·UNIQUE·CHECK 제약에는 반드시 이름.**
- **잠금 순서**: 탈퇴는 회원 행 FOR UPDATE 먼저, 쪽지는 회원 행 FOR KEY SHARE 먼저, 허가서 승인·취소·반려·만료·철회·탈퇴는 신청자 회원 행 FOR UPDATE → 신청 행 → 상태 재확인(`lockPending`/`lockWithStatus`).
- **사진 대기 표(photo_delete_queue)**: 허가서 사진은 "저장 전에 적고 INSERT 트랜잭션에서 떼기", "결정·삭제 트랜잭션 안에서 적고 커밋 뒤 지우기".
- **DB 오류 메시지 언어는 영어 유지.**
- 테스트 도우미: `LockRaceSupport`, `TestLogin.행없는_회원_토큰`, `TestLogin.인증된_사용자`·`학교_인증_처리`, `permit/AdminPermitTestBase`, `permit/FakePermitTextReader`, `AdminPermitLogTests`의 `삭제실패창고`. Flutter: `test/support/fake_adapter.dart`(FakeReply `headers`), `test/support/photo_support.dart`.
- 관리자는 운영 DB SQL로만 지정, 지정 후 재로그인. 관리자는 자기 허가서를 심사할 수 없다(403).
- **Jackson 3**: JsonNode는 `asString()`·`isString()`.
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`.
- **운영 테스트 계정 = `wntjrdka`(연세대 인증, SQL로).** [인증 해제]를 누르지 말 것. 자동 승인 시험은 다른 계정으로.
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글과 S23 시험 계정 `fjfjd`(유학생 인증됨)가 남아 있다(로컬 전용).
