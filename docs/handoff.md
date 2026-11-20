# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-19 밤, 집 PC(영문 사용자 폴더 C:\Users\seoka) (**S21 운영 배포 + S22 도메인·HTTPS 운영 전환 끝, S23 안드로이드 앱 a 끝·b 코드 끝**)

## 지금 어디까지
- **S0~S14 + UI1~UI3 + S16~S18 + S20 + S21 + S22 완료·배포됨.** 운영 주소 **`https://kampus.<도메인>`**(Cloudflare에서 산 도메인, 실제 이름은 사용자가 앎). 2026-11-19: S21 배포(이미지 0dc513f, V15) → S22 HTTPS 전환(이미지 bd6c70b). 운영 vision·gemini 켜짐.
- **방향(2026-11-16)**: "외국인 유학생 통합 에타", 유학생만 글쓰기(표준입학허가서 인증).
- **S22(도메인 + HTTPS)** — 결정은 backlog S22 항목:
  - nginx: 80은 acme-challenge·`= /actuator/health`만 직접, 나머지 https로 301 / 443은 Let's Encrypt 인증서(`--cert-name board`, 경로에 도메인 없음) + 공용 규칙 `flutter_app/board-locations.conf` include. HSTS 없음.
  - compose app에 `SERVER_FORWARD_HEADERS_STRATEGY: native`(없으면 https 뒤 쓰기 요청 403 Invalid CORS request).
  - **Elastic IP 안 씀**(사용자 결정, 꺼 둔 동안 월 약 $3.65 아까움) → 서버 crontab `@reboot sleep 20`으로 `~/board/cloudflare-ddns.sh`(저장소 `deploy/cloudflare-ddns.sh`)가 공인 IP를 Cloudflare A 레코드에 적음. 인증서 갱신은 `@reboot sleep 600` + 매일 4:17(서버 시계). 서버에 cron이 없어서 cronie 설치함.
  - 저장소 Variables `SITE_DOMAIN` 등록 → Deploy가 https 헬스체크도 함.
  - Cloudflare: 도메인 자동 갱신(만료 2027-11-19), DNS A `kampus` 프록시 끔·TTL 1분, API 토큰 `kampus-ddns`(그 영역 DNS 편집만, 만료 없음). 인증서 만료 2027-02-17(자동 갱신, 이메일 등록 안 함).
- **S21(유학생 인증 보강)** 끝: 관리자 인증 취소·취소 번호 자동 승인 막기·Vision 하루 상한 30. 알려진 한계: 탈퇴→재가입으로 취소 번호 우회, 허가서 번호 전국 유일 여부 미확인(backlog S21).
- 화면: 상단바 닉네임·인증 뱃지를 로그아웃 바로 왼쪽으로(3b8feaf, 운영 반영됨).
- **S23(안드로이드 앱, 진행 중, 로컬만 — 운영 배포는 S23 끝에 한 번)**: S23-a `lib/api/api_base_url.dart`의 `apiBaseUrl`(빌드 때 `--dart-define=API_BASE_URL`, 웹 기본 `/api`) 끝(1be63a6). S23-b 코드 끝(f276c31): `flutter_app/android/`(applicationId `com.kampus.board`, label `게시판`), main 매니페스트 INTERNET, **debug 소스셋에만** `network_security_config`(10.0.2.2·localhost http), `apiBaseUrlProblem(isWeb, isRelease)`가 문제면 `ConfigErrorApp` 안내(앱은 `http(s)://` 시작·끝 `/` 금지·**릴리스는 https만**), 비웹이면 `ColoredBox+SafeArea`. **실기기 첫 실행 확인만 남음.**
- 테스트: 백엔드 1907개(건너뜀 13), Flutter 1523개, e2e 15개 통과. DB는 **V15까지**.
- Notion: S22 카드·단계 3장·도구 사전 3장(HTTPS·인증서 / 도메인·DNS / cron)·상위 페이지 아키텍처·진행 현황 갱신 끝. S21 카드는 f·g·배포 채움, **S21 🧭 파트 전체 정리는 아직**. notion-guide에 "운영 단계 페이지 틀" 추가(백엔드는 코드 위주, 운영은 전/후·구성·순서·비용·되돌리기, 프론트는 안 씀).

## 다음에 할 일
- **다음에 EC2를 켤 때 꼭 확인**: 5분쯤 뒤 `https://kampus.<도메인>`이 열리는지(DNS 자동 고침의 첫 실제 재부팅 시험). 안 열리면 서버 `tail ~/board/ddns.log`, 10분 뒤 `tail ~/board/certbot/renew.log`.
- **바로 다음: S23-b 실기기 확인**(사용자 USB 케이블이 2026-11-20 도착 예정. 사용자 PC는 유선만, 폰은 와이파이 안 씀 → 무선 디버깅 불가). 순서: 사용자가 폰 개발자 옵션(빌드번호 7번)·**USB 디버깅** 켜고 꽂기·"항상 허용" → 로컬 백엔드 켜기(Docker Desktop, `docker compose up -d`, `.env`에 `PERMIT_HASH_SECRET` 필요 — 이 PC엔 없음, 임시 env로 가능) → `adb reverse tcp:8080 tcp:8080`(adb는 `C:\Users\seoka\AppData\Local\Android\Sdk\platform-tools\adb.exe`) → `flutter devices`로 폰 확인 → `flutter run -d <폰> --dart-define=API_BASE_URL=http://localhost:8080` → 앱이 뜨고 글 목록·중고 사진이 보이는지, 상단바가 상태 표시줄에 안 가리는지·위아래 띠 색 → 사용자 확인 받으면 backlog S23-b 체크. 케이블이 안 되면 에뮬레이터(Android Studio Virtual Device Manager, `API_BASE_URL=http://10.0.2.2:8080`, RAM 32GB라 가능).
- 그다음 S23-c 앱 로그인(cookie_jar + dio_cookie_manager + path_provider, ApiClient "추가 인터셉터" 인자, CookieReader는 동기 그대로, 로컬 시험 땐 bootRun에 `APP_COOKIE_REFRESH_PATH=/auth`) → d 사진(image_picker)·외부 링크(url_launcher) → e 뒤로 가기 → f 릴리스 APK(영문 폴더 PC에서만, 디버그 키) + 웹 Deploy. 단계 설명은 backlog S23 항목.
- 그 뒤 S24 비자 일정 푸시 알림(FCM).
- 미뤄 둔 정리(운영): 며칠 써 보고 되돌릴 일 없으면 서버 `~/board/docker-compose.yml.bak-s21`·`.bak-s22` 정리. S18로 되돌릴 일 없으면 `~/board/.env.bak-s20` 삭제, `.env`의 MAIL_* 6줄 삭제, **Gmail 앱 비밀번호 폐기**, deploy.md 메일 절 삭제, backlog S20 체크·Notion 진행 현황 S20 체크.
- 작은 일: **집 PC 로컬 `.env`의 `VISION_API_KEY` 줄 삭제**(키는 이미 폐기, 2026-11-19에도 아직 있음), 자동 승인 시험 계정 정리, Notion S21 🧭 파트 전체 정리, (선택) 구글 503 빈도, 운영 테스트 중고 글 15개 정리.

## 진행 중인 단계
- 없음. 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함). push 안 된 커밋 없음.
- 운영 서버 메모: `~/board/.env`(권한 600)에 `PHOTO_S3_BUCKET`·`LLM_MODE=gemini`·`GEMINI_API_KEY`·MAIL_*(되돌리기용)·**`PERMIT_HASH_SECRET`(64자, 절대 바꾸지 않음, 사용자가 서버 밖에도 보관)**·`PERMIT_OCR_MODE=vision`·`VISION_API_KEY`·**`COOKIE_SECURE=true`·`CF_API_TOKEN`·`CF_ZONE_ID`·`CF_RECORD_NAME`**(2026-11-19). 서버 파일: `~/board/cloudflare-ddns.sh`(700), `~/board/certbot/conf`(인증서, root 700)·`www`, 로그 `~/board/ddns.log`·`~/board/certbot/renew.log`. 백업: `docker-compose.yml.bak-s20`(S18)·`.bak-s21`(S20)·`.bak-s22`(S21), `.env.bak-s20`. IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. vision 끄기 = `.env` `PERMIT_OCR_MODE=off` 후 `up -d app`. **되돌리기 = deploy.md "S22 되돌리기"/"S21 되돌리기"**.
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **Deploy 버튼은 평소처럼 써도 된다(S22 이후).** 단 web 이미지는 서버에 인증서(`~/board/certbot/conf/live/board/`)가 있어야 뜬다. **서버를 새로 만들거나 certbot 폴더를 지웠다면** Deploy 전에 deploy.md "S22 HTTPS 전환"의 인증서 첫 발급(standalone, board-web 멈춤, `--dry-run` 먼저)부터. 같은 이름 발급 실패·재발급을 반복하면 Let's Encrypt 발급 제한.
- **EC2 켠 뒤 1~5분은 도메인으로 안 열릴 수 있다**(DNS 자동 고침 대기). IP로 https를 열면 인증서 이름 경고, IP는 `http://<IP>/actuator/health`(Deploy 헬스체크)만. SSH는 DNS가 맞은 뒤 `ec2-user@kampus.<도메인>`로도 가능(known_hosts에 이름으로 한 줄 더 생김).
- **Cloudflare 프록시(주황 구름)는 켜지 않는다**: 100초에 끊겨 허가서 신청(nginx 150초)과 충돌, 인증서·자물쇠가 두 겹이 됨. 토큰이 새면 Cloudflare에서 Roll/삭제 후 서버 `.env` 교체.
- **세션을 폴더 없이 시작했다가 프로젝트로 옮겨 오면 서브에이전트 쓰기 훅(`scripts/allow-only.sh`)이 tester의 `src/test/` 쓰기를 막는다**(2026-11-18, 추정·미확인). 훅을 고쳐 우회하지 말 것.
- **서버 `.env`를 고친 뒤 `up -d app` 전에 반드시 확인**: `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env`가 1. `.env` 값은 `cat` 금지, 확인은 `grep -c`나 `grep -o '^[A-Z_]*='`(이름만). 토큰·키는 nano 화면에서만 붙여넣기(셸 프롬프트에 붙이면 `.bash_history`).
- **사용자 PowerShell은 `C:\Windows\System32`에서 열려 있다**: scp에 상대 경로(`docker-compose.yml`)를 주면 "No such file". 명령은 저장소 파일 **전체 경로**로 준다. ssh·scp는 `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<IP>`(노트북은 PATH에 ssh 없음). 서버 사용자 `ec2-user`. `<IP>` 같은 자리표시는 쓰지 말고 사용자에게 IP를 받아 완성 명령을 준다.
- **사용자의 이메일을 외부(Let's Encrypt 등)에 넣지 않는다**(certbot은 `--register-unsafely-without-email`로 함).
- **사용자 폴더가 한글인 PC(C:\Users\주석암)에서 Flutter**: `%USERPROFILE%\flutter` 3.50.0. `flutter test`는 한글 경로로 impellerc가 죽는다 → PowerShell `subst R: "$env:USERPROFILE"` 뒤 `$env:PUB_CACHE="R:\AppData\Local\Pub\Cache"`, PATH 앞에 `R:\flutter\bin;C:\Windows\System32\WindowsPowerShell\v1.0`, `R:\OneDrive\Desktop\board-vibe-coding\flutter_app`에서 실행. 서브에이전트 프롬프트에도 넣는다. **안드로이드 빌드는 영문 폴더 PC에서.**
- **안드로이드 빌드 환경(2026-11-19 집 PC에 설치)**: Android Studio(Standard), SDK `C:\Users\seoka\AppData\Local\Android\Sdk`(platform 36·37, build-tools 36, 에뮬레이터), **cmdline-tools는 SDK Manager → SDK Tools에서 따로 설치**, **NDK 28.2는 첫 빌드 때 `sdkmanager.bat --install "ndk/28.2.13676358"`로 설치**(패키지 id가 `ndk/...`). `flutter doctor --android-licenses`는 이미 동의돼 있으면 "no longer needed" 경고만. Android Studio 설치 파일을 두 번 실행하면 "Uninstall old version"이 뜨는데 Cancel(방금 깐 걸 지움). `android/.gitignore`(Flutter 기본)가 `gradlew`·wrapper jar·`local.properties`를 빼므로 다른 PC에선 `flutter build`/`flutter run`으로 빌드(`./gradlew` 직접 X). 빌드 뒤 `flutter_app/android/gradlew.bat --stop`으로 데몬 정리. 디버그 APK 약 160MB, 릴리스 약 53MB. 노트북(메모리 작으면) `android/gradle.properties` -Xmx8G를 4G로.
- **영문 사용자 폴더 PC(C:\Users\seoka, 집 PC)에서는 `flutter test`·e2e가 그냥 돈다**(Git Bash `export PATH="/c/Users/seoka/flutter/bin:$PATH"`). **이 PC 로컬 `.env`엔 `PERMIT_HASH_SECRET`이 없어 `bootRun`이 시작 실패**(만드는 법 `.env.example`). bootRun은 `.env`를 자동으로 안 읽음(`set -a; . ./.env; set +a`). Docker Desktop이 꺼져 있으면 켜야 Testcontainers·docker 명령이 됨.
- **로컬 nginx 확인**(include 파일·인증서 필요): 자체 서명 인증서(`alpine/openssl req -x509 -nodes -newkey rsa:2048 -days 1 -subj /CN=localhost`)를 저장소 밖 `<le>/live/board/`에 만들고 `MSYS_NO_PATHCONV=1 docker run --rm -v "<nginx.conf>:/etc/nginx/conf.d/default.conf:ro" -v "<board-locations.conf>:/etc/nginx/snippets/board-locations.conf:ro" -v "<le>:/etc/letsencrypt:ro" --add-host app:127.0.0.1 nginx:1.30-alpine nginx -t`. 백엔드까지 붙인 리허설은 nginx `resolver 127.0.0.11` 때문에 `--add-host`로 안 되고, 사용자 정의 네트워크 + `alpine/socat`(별칭 app → host.docker.internal:8080)으로. **리허설은 Origin 붙인 쓰기 요청까지** 볼 것(GET만 보면 CORS 403을 놓침).
- **deploy 에이전트는 훅으로 Dockerfile·compose·workflows·application-prod.yml·deploy.md만 고칠 수 있다.** nginx 설정·api.md·`deploy/` 스크립트·.gitignore는 메인 대화가 고친다.
- **테스트가 nginx를 안 거친다**(MockMvc·e2e): 업로드 한도·시간 제한·https는 배포 뒤 실제로 확인.
- **`PERMIT_OCR_MODE`**: 기본 off. vision이면 `VISION_API_KEY` 필수, 이상한 값이면 앱 시작 실패. 테스트 설정은 off. 실제 구글 호출 테스트는 `VISION_LIVE_TEST=true` + 키.
- **허가서(S20) 개인정보 규칙**: 로그에 사진 key·`permits/`·번호·해시·판독 글자·키 금지. 테스트 값은 지어낸 것만.
- **판정 규칙(S20-d1)**: 국적은 허용 목록 방식 유지. 위조는 못 막음(알려진 한계).
- **자동 승인(S20-d2)**: 판독·판정은 PENDING 커밋 뒤 트랜잭션 밖. 승인은 `AdminPermitService.lockPending` 재사용.
- **앱 안 주기 작업은 cron 대신 fixedDelay + initialDelay**(EC2를 평소 꺼 두므로). 서버 crontab은 DNS·인증서용만.
- **실물 시험 도구 `PermitSampleLiveTests`**: `VISION_LIVE_TEST=true` + 키 + 저장소 밖 `PERMIT_SAMPLE_DIR`. 끝나면 `build/test-results`·`build/reports` 정리.
- **서브에이전트가 대화형 명령이나 뒤에서 도는 gradle을 남긴 적 있음** → 프롬프트에 "python 등 대화형 명령 금지, Edit 도구로, 뒤 작업 남기지 말 것". 서브에이전트가 두 번째 보고를 못 보내 결과가 안 올 때가 있음(2026-11-19) → 남긴 시험 도구로 메인이 직접 확인.
- **review 에이전트는 git을 못 돌린다** → 비교는 메인이 `git show HEAD:경로`로.
- **Notion**: 표 칸에 `|` 금지. 표 줄만 따로 update_content로 넣으면 표 밖 글자가 됨(머리 줄과 붙여서 넣기). 토글은 `<toggle>`(`<details>`는 변환 경고). ASCII 그림은 ```text.
- **IDE(VS Code Java)의 "getId() undefined"·"log cannot be resolved" 진단은 무시**(Lombok).
- **Flutter 작업 메모**: 이동은 `context.go`만, FormData는 `Options(extra: {ApiClient.formDataFactoryKey: make})`, 파일 고르기는 `photoPicker`, 허가서 화면은 `lib/permit/`, 상단바는 `lib/widgets/nav_bar.dart`(메뉴 순서 테스트 `test/nav_bar_test.dart`), 앱 뒤로 가기는 `lib/common/app_history.dart` 방문 기록(BoardApp 앱 모드, AppShell PopScope). e2e는 `flutter build web --web-content-hash` 뒤 `flutter_app/e2e`에서 `npm test`. 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`.
- **제미나이(S18)**: 로컬은 `.env`에 `GEMINI_API_KEY` + `LLM_MODE=gemini`. 커밋 전 `git diff --cached | grep -c 'x-goog-api-key\|AIza\|AQ\.'`가 0.
- **compose가 바뀌는 배포는 Deploy 버튼만으로 안 됨**: compose 백업·scp 먼저(deploy.md). 새 비밀 값은 서버 `.env`에 nano로.
- **로컬 LLM(S17)**: 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`. 백엔드 테스트는 Docker Desktop 필요. Spring AI 재시도 함정 주의.
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.**
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs?head_sha=<40자리 SHA>` → run id로 `/jobs`. Deploy 전에 images까지 초록 확인.
- **docs 파일을 Git Bash `sed -i`로 고치지 말 것**(CRLF가 LF로 바뀜, 2026-11-19에도 겪음 → `sed -i 's/$/\r/'`로 되돌림). Edit 도구로. backlog는 CRLF라 끝에 줄을 붙일 땐 `printf '%s\r\n'`.
- **표 바꾸는 기능은 새 V 파일**(다음은 `V16__...sql`). 커밋된 V 파일은 고치지 않는다. ddl-auto validate.
- **FK·UNIQUE·CHECK 제약에는 반드시 이름.**
- **잠금 순서**: 탈퇴는 회원 행 FOR UPDATE 먼저, 쪽지는 회원 행 FOR KEY SHARE 먼저, 허가서 승인·취소·반려·만료·철회·탈퇴는 신청자 회원 행 FOR UPDATE → 신청 행 → 상태 재확인(`lockPending`/`lockWithStatus`).
- **사진 대기 표(photo_delete_queue)**: 허가서 사진은 "저장 전에 적고 INSERT 트랜잭션에서 떼기", "결정·삭제 트랜잭션 안에서 적고 커밋 뒤 지우기".
- **DB 오류 메시지 언어는 영어 유지.**
- 테스트 도우미: `LockRaceSupport`, `TestLogin.행없는_회원_토큰`, `TestLogin.인증된_사용자`·`학교_인증_처리`, `permit/AdminPermitTestBase`, `permit/FakePermitTextReader`, `AdminPermitLogTests`의 `삭제실패창고`.
- 관리자는 운영 DB SQL로만 지정, 지정 후 재로그인. 관리자는 자기 허가서를 심사할 수 없다(403).
- **Jackson 3**: JsonNode는 `asString()`·`isString()`.
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`.
- **운영 테스트 계정 = `wntjrdka`(연세대 인증, SQL로).** [인증 해제]를 누르지 말 것. 자동 승인 시험은 다른 계정으로.
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글이 남아 있다(로컬 전용).
