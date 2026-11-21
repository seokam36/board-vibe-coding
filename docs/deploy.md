# 배포 방법 (EC2, nginx 컨테이너)
브라우저 -> `https://<사이트주소>`(Cloudflare DNS가 지금 EC2 IP를 가리킴) -> EC2 443번(80번은 https로 301 안내·인증서 확인·헬스체크만) -> nginx(`web` 컨테이너). (S22, 2026-11-19 운영 전환) 화면은 **Flutter 웹**(S16-n2부터, 아래 "웹 화면: Flutter로 교체 (S16-n2)" 참고)이고, nginx가 화면 파일을 주고,
`/api/...` 는 `/api`를 떼고 백엔드(`app:8080`)로, `/actuator/health` 는 그대로 넘깁니다.
화면과 API 주소가 같아서 CORS 허용 목록(`CORS_ALLOWED_ORIGINS`)은 EC2 `.env`에 넣지 않아도 됩니다. 단 https 전환(S22) 뒤에도 같은 출처로 인정받으려면 compose app의 `SERVER_FORWARD_HEADERS_STRATEGY: native`(nginx가 넘긴 `X-Forwarded-Proto`를 믿게 함)가 있어야 합니다. 없으면 쓰기 요청이 403(`Invalid CORS request`)입니다.

> **주의 (S22 이후)**: web 이미지에는 443(https) 블록이 있어 서버에 인증서(`~/board/certbot/conf/live/board/`)가 있어야 nginx가 뜹니다. 운영 서버는 2026-11-19에 발급을 마쳐 Deploy 버튼을 평소처럼 써도 됩니다. **서버를 새로 만들거나 `~/board/certbot`을 지웠다면** Deploy 전에 아래 "S22 HTTPS 전환"의 인증서 첫 발급부터 다시 합니다(안 그러면 사이트 전체가 내려감).
> 지금 운영(S24-g, 2026-11-21) 이미지는 `a5d48fd`(S24 첫 배포 `eebac61` + 파트 리뷰 정리, Deploy 버튼만). S24 이전(S23) app·web 이미지: `seokam36/demo:fe3871c95db20febb3eeb374e84d7e366909f318`, `seokam36/demo-web:fe3871c95db20febb3eeb374e84d7e366909f318` — 되돌리기는 아래 "S24 되돌리기"(푸시만 끄기가 먼저). S24-g만 되돌리려면 이미지를 `eebac61`(전체 SHA는 git log)로 고정.

## 자동 배포 (GitHub Actions 버튼)
이미지는 main에 push하면 CI가 Docker Hub에 올려 둡니다(`latest`). 배포는 아래처럼 버튼으로 합니다.
1. (AWS 콘솔) EC2 인스턴스를 켭니다(시작). 이 워크플로는 EC2를 켜거나 끄지 않습니다. 켜져 있지 않으면 실패합니다.
2. 버튼을 누르기 전에 확인할 것 두 가지입니다.
   - (GitHub) Actions 탭에서 배포할 커밋의 CI(images 포함)가 초록색인지 확인합니다.
   - 자동 배포는 EC2의 `docker-compose.yml`을 바꾸지 않습니다. compose 파일을 바꿨다면 먼저 아래 비상용의 4~5단계(compose 백업·scp)로 EC2에 복사합니다.

   준비가 되면 Actions 탭 -> 왼쪽 Deploy -> Run workflow -> main 선택 후 실행.
3. 워크플로가 하는 일: 켜진 EC2의 퍼블릭 IP 조회 -> 보안 그룹에 이 러너 IP의 SSH(22) 허용을 잠깐 추가
   -> **DB 자동 백업**(배포 직전에 서버 DB를 파일로 저장. 실패하면 여기서 중단, 자세한 내용은 아래 "배포 전 DB 백업") -> SSH로 `docker compose pull`/`up -d` -> `/actuator/health`가 UP일 때까지 최대 약 3~4분 확인 -> (성공/실패 상관없이) SSH 허용 규칙 삭제.
   앱이 켜질 때 Flyway가 표 변경(V 파일)을 적용합니다. 실패하면 아래 'Flyway(표 변경) 문제와 되돌리기'를 참고합니다.
4. 초록색이면 끝입니다(저장소 Variables `SITE_DOMAIN`이 있어 https 헬스체크도 함께 확인). 확인용 주소는 `https://<사이트주소>/`(IP로 열면 https로 넘어가 인증서 이름 경고가 납니다. IP는 Deploy 헬스체크용 `http://<퍼블릭IP>/actuator/health`만). 다 쓰고 나면 EC2를 콘솔에서 중지합니다. 켤 때는 1~5분 뒤 DNS가 새 IP를 따라옵니다(아래 "S22 DNS 자동 고침").

만약 실행이 중간에 강제로 끊겨 `github-actions-deploy` 설명이 붙은 22번 규칙이 보안 그룹에 남았다면 콘솔에서 직접 지웁니다.

### 배포 전 DB 백업 (자동)
Deploy 버튼을 누르면 워크플로가 배포 "직전에" 서버 DB를 알아서 백업합니다. 손으로 할 일은 없습니다.
- 위치: EC2의 `~/board/backups/db-<날짜>-<시각>.sql` (UTC 기준, 예: `db-20260101-093000.sql`). 폴더는 나만(700), 파일도 나만(600) 읽을 수 있습니다.
- 보관: 최신 10개만 남기고 오래된 것은 자동으로 지웁니다.
- 안전장치: 파일이 비어 있거나, `posts` 표가 없거나, 끝까지 쓰이지 않았으면 백업 실패로 보고 **배포를 멈춥니다**(새 이미지를 받지 않음). 이때 Actions 로그의 빨간 오류 줄을 확인합니다. SSH 허용 규칙은 실패해도 닫힙니다.
- 디스크 부족: 서버 디스크 여유 공간이 50MB 미만이면 백업하지 않고 멈춥니다. EC2에서 `~/board/backups`의 오래된 백업을 지운 뒤(PC에 복사해 둔 것만) 다시 실행합니다.
- 처음 세팅한 서버(컨테이너·`posts` 표가 아직 없음)는 백업 단계에서 막힙니다. 비상용 수동 절차로 한 번 띄운 뒤부터 버튼을 씁니다.
- 한계: 백업도 같은 서버 디스크에 있어서 서버 디스크가 망가지면 같이 사라집니다. 중요한 배포 전에는 아래 선택 단계로 PC에도 복사해 두세요.
- 확인(선택, EC2에서): `ls -l ~/board/backups`

**PC로 복사 (선택)** 저장소 폴더 밖에 두고 커밋하지 않습니다. 받을 폴더를 먼저 만듭니다.
- Git Bash/맥/리눅스: `mkdir -p ~/board-backups` 후 `scp -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>:~/board/backups/db-<날짜>-<시각>.sql ~/board-backups/`
- Windows PowerShell: `New-Item -ItemType Directory -Force "$HOME\board-backups"` 후 `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<퍼블릭IP>:~/board/backups/db-<날짜>-<시각>.sql "$HOME\board-backups\"`

**되살리기 (필요할 때만)** 앱을 멈추고, DB를 싹 비우고, 백업을 넣습니다. EC2 `~/board`에서 순서대로:
- 반드시 컨테이너 안 psql(`docker exec`)로 합니다. 백업을 만든 pg_dump 17이 넣는 `\restrict` 줄을 PC에 깔린 옛 psql은 몰라서 오류가 납니다.
- 비밀번호를 셸에 올리지 않습니다(컨테이너 안에서 `$POSTGRES_USER`/`$POSTGRES_DB`를 읽고, 컨테이너 안 접속은 비밀번호가 필요 없음). 그래서 `.env`를 source하거나 unset할 일이 없습니다.
1. `docker compose --profile prod stop app` — 앱을 잠깐 멈춥니다(DB를 갈아엎는 동안 앱이 쓰지 못하게).
2. `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'` — **경고: 지금 DB의 데이터와 표가 전부 삭제됩니다.** 백업을 만들었고 올바른 파일인지 확인한 뒤에만 실행하세요. (백업에는 "표 지우기" 명령이 없어서, 비우지 않으면 `CREATE TABLE`이 "이미 있음" 오류로 실패합니다.)
3. `docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 --single-transaction' < backups/db-<날짜>-<시각>.sql` — 백업 파일을 DB에 넣습니다. `ON_ERROR_STOP`은 오류가 나면 바로 멈추고 실패(종료 코드 0이 아님)로 알려 주고, `--single-transaction`은 중간에 실패하면 반쯤 들어간 채로 두지 않고 전부 취소합니다.
4. `docker compose --profile prod up -d` — 멈춰 둔 앱을 다시 띄웁니다. 이어서 `/actuator/health`가 UP인지 확인합니다.
   (기록장이 없는 옛 백업이라면 4단계 대신 아래 (e)의 "일회성 baseline 절차"를 따릅니다.)

**손으로 백업 (비상용)** Actions를 못 쓰는 비상 수동 배포 때만 씁니다. 워크플로와 같은 방식(컨테이너 안 pg_dump)입니다. EC2 `~/board`에서:
- `umask 077 && mkdir -p backups && chmod 700 backups && docker exec board-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > backups/db-<날짜>.sql && chmod 600 backups/db-<날짜>.sql`
- 확인: `ls -l backups/db-<날짜>.sql`(0바이트가 아님), `grep -c "CREATE TABLE public.posts" backups/db-<날짜>.sql`(1), `grep -c "PostgreSQL database dump complete" backups/db-<날짜>.sql`(1)

### 처음 한 번 준비할 것
**1) IAM 사용자 + 액세스 키** (AWS 콘솔 -> IAM)
- 배포 전용 사용자를 만들고(콘솔 로그인 불필요) 아래 정책을 붙입니다. `<...>` 자리는 본인 값으로 바꿉니다. 실제 값은 이 파일에 적지 마세요.
- `DescribeInstances`는 AWS 규칙상 대상을 제한할 수 없어 `"*"`입니다. 보안 그룹 규칙 추가/삭제는 해당 보안 그룹 하나로만 제한합니다.
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ec2:DescribeInstances",
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupIngress"
      ],
      "Resource": "arn:aws:ec2:<리전>:<계정ID>:security-group/<보안그룹ID>"
    }
  ]
}
```
- 그 사용자의 "보안 자격 증명" 탭에서 액세스 키를 만들고, Access key ID와 Secret access key를 따로 복사해 둡니다(비밀키는 다시 볼 수 없음).

**2) 배포 전용 SSH 키 쌍** (개인 PC 터미널)
- `ssh-keygen -t ed25519 -f ~/.ssh/deploy-actions -C github-actions-deploy -N ""`
- 공개키(`~/.ssh/deploy-actions.pub`) 한 줄을 EC2의 `~/.ssh/authorized_keys` 맨 아래에 추가합니다(기존 줄은 지우지 않음).
  예: 기존 키로 접속해 `echo '<공개키 한 줄>' >> ~/.ssh/authorized_keys`
- 개인키(`~/.ssh/deploy-actions`) 파일 내용 전체(`-----BEGIN` 줄부터 `END` 줄까지)를 아래 Secret `EC2_SSH_KEY`에 넣습니다. 개인키는 다른 곳에 붙여넣거나 커밋하지 않습니다.

**3) GitHub 저장소 설정** (Settings -> Secrets and variables -> Actions)
- Secrets 탭 (비밀)
  - `AWS_ACCESS_KEY_ID`: 1)에서 만든 Access key ID
  - `AWS_SECRET_ACCESS_KEY`: 1)에서 만든 Secret access key
  - `EC2_SSH_KEY`: 2)의 개인키 파일 내용 전체
- Variables 탭 (비밀 아님)
  - `AWS_REGION`: EC2가 있는 리전 코드
  - `EC2_INSTANCE_ID`: 인스턴스 ID (`i-`로 시작)
  - `EC2_SECURITY_GROUP_ID`: EC2에 붙은 보안 그룹 ID (`sg-`로 시작)

## 비상용 (수동)
Actions를 쓸 수 없을 때 개발 PC에서 손으로 하는 방법입니다.

EC2는 이미지를 받아 실행만 합니다. 저장소를 clone할 필요 없이, EC2에는 `docker-compose.yml` 하나만 복사해 두면 됩니다.

전제:
- EC2의 `~/board` 폴더에 `.env`가 있고 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` 값이 들어 있어야 합니다.
  `.env`는 서버에만 두고, 복사하거나 커밋하지 않습니다.
- S4(로그인)부터 같은 `.env`에 아래 값도 필요합니다.
  - `JWT_SECRET` (필수): 로그인 토큰 서명용 비밀키. Base64 문자열(디코딩 후 32바이트 이상)이며 서버에서 직접 만듭니다: `openssl rand -base64 32`. 없으면 서버가 시작되지 않습니다.
  - `REDIS_PASSWORD` (필수): 리프레시 토큰 저장소(Redis) 비밀번호. 서버에서 직접 만듭니다: `openssl rand -hex 24`. 없으면 `docker compose`가 바로 실패합니다.
  - `COOKIE_SECURE` (선택): 로그인 쿠키의 Secure 속성. 기본 `false`이고, HTTPS를 도입하면 `true`로 바꿉니다.
  - `PHOTO_S3_BUCKET` (S13부터 필수): 사진을 저장하는 S3 버킷 이름. 없으면 app이 시작되지 않습니다. 자세한 내용은 아래 "사진 저장 (S13-g6)"을 봅니다.
  - `PERMIT_HASH_SECRET` (S20부터 필수): 허가서 번호 해시용 비밀 값(32바이트 이상, 한 번 정하면 바꾸지 않음). 없거나 짧으면 app이 시작되지 않습니다. `PERMIT_OCR_MODE`(선택, 기본 `off`)와 함께 위 "S20 배포"를 봅니다.
- DB 비밀번호를 바꿀 때: `.env`의 `POSTGRES_PASSWORD`만 바꾸면 이미 만들어진 DB에는 적용되지 않습니다(처음 만들 때만 읽음).
  EC2 `~/board`에서 DB 안의 비밀번호와 `.env`를 같은 값으로 함께 바꿉니다. 값은 서버에서 만들고 화면에 출력하지 않습니다.
  - `set -a && . ./.env && set +a && NEWPW=$(openssl rand -hex 24)`
  - `docker exec board-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "ALTER USER \"$POSTGRES_USER\" PASSWORD '$NEWPW';"`
  - `sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEWPW/" .env && unset NEWPW POSTGRES_PASSWORD`
  - 떠 있는 app은 기존 연결로 계속 돌지만, 새로 연결할 때는 새 값이 필요하므로 이어서 `docker compose --profile prod up -d`로 다시 띄웁니다.
- 주의: S4부터 `docker-compose.yml`이 바뀌었습니다(위 환경변수 전달). S4를 처음 배포할 때는 변경된 `docker-compose.yml`을 EC2의 `~/board`로 먼저 `scp`해야 합니다.
- 백엔드 이미지 `seokam36/demo:latest`가 Docker Hub에 올라가 있어야 합니다.
- 퍼블릭 IP는 인스턴스를 시작할 때마다 바뀝니다 (탄력적 IP를 쓰지 않는 경우). 아래 `<퍼블릭IP>`는 그때그때 확인한 값으로 바꿉니다.
  - S22-c 이후: 이 서버는 탄력적 IP를 쓰지 않는 게 맞고, 켤 때마다 `cloudflare-ddns.sh`가 도메인(DNS)을 새 IP로 자동으로 고칩니다("S22 DNS 자동 고침" 절). 그래서 평소엔 `https://<사이트주소>`로 들어가면 되고, `<퍼블릭IP>`는 켠 직후 DNS가 퍼지기 전이나 비상용(Deploy 헬스체크 등)일 때만 콘솔에서 확인해 씁니다.

1. (개발 PC) 이미지 빌드 (S16-n2부터 web 이미지는 Flutter. 빌드 컨텍스트는 `flutter_app/`이고, 이미지 안에서 빌드하지 않으니 **먼저 `build/web`을 만듭니다**)
   - `cd flutter_app && flutter build web --web-content-hash && cd ..`
   - `docker build -t seokam36/demo-web:latest flutter_app` (옛 `build/web`이 남아 있으면 그게 조용히 담기니 항상 빌드 직후에 합니다)
   - 백엔드 코드도 바뀌었다면 저장소 루트에서 `docker build -t seokam36/demo:latest .`
   - 개발 PC와 EC2(t3.micro, x86_64)의 아키텍처가 다르면(예: Apple Silicon) `--platform linux/amd64`를 붙입니다.
   - API 주소는 화면과 같은 주소의 `/api`로 고정이라 바꿀 설정이 없습니다(nginx가 app으로 넘김).
2. (개발 PC) Docker Hub에 올리기: `docker login` 후 `docker push seokam36/demo-web:latest`
   (백엔드도 바꿨다면 `docker push seokam36/demo:latest`도)
3. (AWS 콘솔) 보안 그룹 인바운드에 80(HTTP)이 열려 있는지 확인합니다. 예전에 8080을 열었다면 이제 닫아도 됩니다 (app은 외부 접근 안 함).
   - SSH(22)는 내 PC IP만 허용합니다. PC 터미널에서 접속 키 `~/.ssh/board-ec2`(비밀번호 걸린 ed25519 키)로 `ssh` 합니다.
   - 와이파이·장소가 바뀌어 IP가 바뀌면 보안 그룹의 SSH 소스를 "내 IP"로 다시 저장합니다.
   - 접속 키는 PC마다 따로 만듭니다(`ssh-keygen -t ed25519 -f ~/.ssh/board-ec2 -C board-ec2`). 개인키 파일을 다른 PC로 복사하지 않고, 새 PC의 공개키 한 줄을 EC2 `~/.ssh/authorized_keys`에 추가합니다.
   - 키가 없거나 막혔을 때(비상): 보안 그룹에 SSH 규칙을 하나 추가합니다(소스: 접두사 목록 `com.amazonaws.<리전>.ec2-instance-connect`, IPv4용).
     그다음 콘솔 -> 인스턴스 -> 연결 -> "EC2 인스턴스 연결"로 브라우저 터미널을 엽니다. 작업이 끝나면 그 규칙을 바로 지웁니다.
   - Windows PowerShell에서 `ssh`를 못 찾으면 `& "C:\Windows\System32\OpenSSH\ssh.exe"`처럼 전체 경로로 실행하거나 Git Bash를 씁니다. PowerShell 창 안의 ssh에서는 붙여넣기가 마우스 오른쪽 클릭입니다(Ctrl+V 아님).
4. (개발 PC) EC2의 기존 compose 파일을 백업합니다 (되돌리기용, `<날짜>`는 예: 20260101)
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> 'cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-<날짜>'`
5. (개발 PC, 저장소 루트에서) 새 compose 파일을 EC2로 복사합니다.
   - `scp -i ~/.ssh/board-ec2 docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
6. (EC2) 접속해서 새 이미지로 실행합니다.
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>`
   - `cd ~/board && docker compose --profile prod pull && docker compose --profile prod up -d`
   - `docker ps`로 `board-web`, `board-app`, `board-postgres`, `board-redis` 네 컨테이너가 떠 있는지 확인합니다.
7. 브라우저에서 아래를 확인합니다.
   - `http://<퍼블릭IP>/`: 목록 화면이 뜨는지
   - `http://<퍼블릭IP>/posts/1`: 새로고침해도 JSON이 아니라 화면이 뜨는지
   - `http://<퍼블릭IP>/api/posts`: JSON 배열이 오는지 (health는 app만 살아 있어도 UP이라 `/api` 전달까지는 확인하지 못합니다)
   - `http://<퍼블릭IP>/actuator/health`: UP인지 (DB와 Redis 상태도 포함됩니다. 둘 중 하나가 죽거나 `REDIS_PASSWORD`가 틀리면 503이 되고, 자동 배포도 실패로 끝납니다. 로그인이 실제로 안 되는 상태이므로 의도된 동작입니다)
   - `http://<퍼블릭IP>:8080`: 접속되지 않는지 (app은 외부에 열지 않습니다)
   - S9 배포 후: **[서버]** `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'` 에서 4, 5가 `t`(success)인지, 같은 방식의 `SELECT role, count(*) FROM users GROUP BY role;` 에서 기존 회원이 모두 `USER`인지 봅니다. 브라우저에서는 글·댓글에 신고 버튼이 보이는지 확인하고, 위 "관리자 지정·해제"로 관리자를 지정한 뒤 다시 로그인해 관리자 페이지(신고 목록)가 열리는지 봅니다.

   `/api/posts`가 502이면 app이 부팅 중일 수 있으니 잠시 후 다시 시도하고,
   계속되면 `docker compose --profile prod ps`로 app 상태를 보고 `docker compose --profile prod logs app`으로 오류를 확인합니다.

### S13·S14 배포 후 확인
S13(중고거래·사진)과 S14(쪽지)를 한 번에 배포한 뒤에 봅니다. 앞에서부터 순서대로 합니다.
1. **[서버]** 표 변경 확인: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'` 에서 9, 10, 11, 12, 13이 모두 `t`(success)인지 봅니다. 하나라도 `f`이면 위 (c)를 봅니다.
2. **[서버]** 앱 상태: `http://<퍼블릭IP>/actuator/health`가 UP이고, `docker compose --profile prod logs --tail 100 app`에 시작 실패 메시지가 없는지 봅니다.
3. **[브라우저]** 중고거래: 글 쓰기 -> 사진 올리기 -> 상세에서 사진 보이는지 -> 사진 한 장 지우기. 사진 관련 확인(compose `PHOTO_*`, 삭제 로그 등)은 위 "사진 저장 (S13-g6)"의 "운영에 적용하기" 5번을 따릅니다(여기에 다시 적지 않습니다).
4. **[브라우저]** 쪽지: 계정 두 개가 필요합니다. 다른 계정(같은 학교 인증)으로 그 중고 글에서 "판매자에게 쪽지" 보내기 -> 판매자 계정으로 상단바 `쪽지 (n)`의 안 읽은 수 확인 -> 쪽지함에서 열어 답장 -> 처음 계정에서 답장이 보이고 안 읽은 수가 줄어드는지 봅니다.
5. **[브라우저]** 신고: 쪽지 대화를 신고 -> 관리자 계정(위 "관리자 지정·해제")으로 로그인해 관리자 페이지에서 대화 신고를 열어 대화 내용이 보이는지, 기각이 되는지 봅니다. 확인한 테스트 글·쪽지·신고는 정리합니다(회원 탈퇴 등).

되돌리기: 문제가 생기면 EC2에서 백업 파일을 되돌리고 다시 실행합니다.
- `cd ~/board && cp docker-compose.yml.bak-<날짜> docker-compose.yml && docker compose --profile prod up -d --remove-orphans`
- `--remove-orphans`: 백업 파일에 없는 컨테이너(예: `board-web`)를 같이 내립니다. 없으면 남은 컨테이너가 80번 포트를 붙잡아 app이 시작하지 못할 수 있습니다.
- **S13 이후 주의**: compose만 옛 파일로 되돌리면 사진 저장이 조용히 `local`로 바뀝니다. 아래 "사진 저장 (S13-g6)"의 "되돌리기 주의"를 먼저 읽습니다.
- 되돌리는 것은 compose 설정뿐입니다. 이미지는 `latest` 태그라 push할 때 Docker Hub의 옛 이미지가 덮어써지므로, 되돌려도 새 이미지가 그대로 쓰입니다.
  이미지까지 되돌리고 싶다면 아래 "Flyway(표 변경) 문제와 되돌리기"의 (a) "이전 이미지로 되돌리기"를 따릅니다(CI가 커밋마다 40자리 SHA 태그를 남겨 두므로 미리 태그를 붙여 둘 필요가 없습니다).

### S17 배포 후 확인
S17(AI 챗봇: 백엔드 `POST /chat` + Flutter `/chat` 화면, 횟수 제한 Redis, 동시 상한)을 처음 배포한 뒤에 봅니다. 앞에서부터 순서대로 합니다.

메모:
- **S17 배포(Deploy #19) 때 운영 AI는 off였습니다.** 그때 compose가 app에 `LLM_MODE`를 넘기지 않아 `application.properties`의 `${LLM_MODE:off}` 기본값이 쓰였고, `/chat`은 항상 503이었습니다(횟수도 세지 않아 Redis 키가 안 생김). **S18-e부터는 compose가 `LLM_MODE`를 넘기고(서버 `.env`에 안 적으면 여전히 `off`), 서버 `.env`에 `LLM_MODE=gemini`를 적으면 켜집니다.** 켜는 절차는 아래 "S18 배포 후 확인"을 봅니다.
- **V 파일 변경 없음**(flyway 최신 version은 13 그대로). 새 의존성은 S17-b에서 들어간 `spring-ai-ollama`뿐입니다. 그래서 문제가 생겨 옛 이미지로 돌아가도 DB 때문에 깨질 일이 없어 안전합니다.
- nginx(`web` 이미지)는 `/api/chat`만 대기 75초가 추가돼 새 web 이미지가 나갑니다.
- Deploy 버튼을 누르기 전에 **그 커밋의 CI 세 작업(backend, flutter, images)이 모두 초록**인지 Actions 탭에서 봅니다.

1. **[브라우저]** GitHub Actions의 Deploy 실행이 초록(성공)인지 봅니다. DB 백업과 헬스체크 단계도 모두 초록이어야 합니다.
2. **[브라우저]** `http://<퍼블릭IP>/actuator/health`가 UP인지 봅니다(`<퍼블릭IP>`는 그때그때 EC2 콘솔에서 확인한 값으로 바꿉니다).
3. **[서버]** AI가 꺼진 채로 떴는지: `cd ~/board && docker compose --profile prod logs app | grep -c 'AI 꺼짐(LLM_MODE=off)'` 가 `1` 이상인지 봅니다(시작 로그 `AI 꺼짐(LLM_MODE=off): /chat은 항상 503을 돌려줍니다.`). 0이면 새 이미지가 안 뜬 것입니다.
4. **[서버]** 표 변경이 없었는지: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'` 에서 가장 마지막 줄이 `13 | t`인지 봅니다(14 이상이 보이면 안 됩니다).
5. **[브라우저]** 운영 테스트 계정으로 로그인 -> 상단바 "챗봇" -> 아무 질문 -> "지금은 AI 챗봇을 쓸 수 없어요. 잠시 뒤 다시 시도해 주세요"가 나오는지 봅니다. **네트워크 오류 문구가 나오면 실패**입니다(nginx나 app 연결 문제).
6. **[브라우저]** 기존 기능 3가지: 글 목록이 보이는지, 중고 글 상세가 열리는지, 새로고침해도 로그인이 유지되는지 봅니다.
7. **[서버]** 메모리와 재시작: `docker stats --no-stream board-app` 의 메모리 사용량이 `512MiB` 미만인지, `docker ps` 에서 `board-app`의 STATUS가 `Restarting`이 아니고 `Up` 시간이 계속 늘어나는지 봅니다(1분쯤 뒤 한 번 더).

**S17 되돌리기** (문제가 있을 때만). 위 "(a) 이전 이미지로 되돌리기"와 같은 방식이고, app과 web 두 줄을 직전 운영 배포(Deploy #18) 커밋의 이미지로 바꿉니다. V 파일이 없어서 DB는 그대로 둡니다.
- 되돌릴 이미지: `seokam36/demo:dbef29bbea7145af21b0adf7aa027633f4adc375`, `seokam36/demo-web:dbef29bbea7145af21b0adf7aa027633f4adc375`
1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-latest` — 지금 설정을 복사해 둡니다(이미 있으면 덮어쓰지 말고 다른 이름으로).
2. **[서버]** `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:dbef29bbea7145af21b0adf7aa027633f4adc375#; s#image: seokam36/demo-web:latest#image: seokam36/demo-web:dbef29bbea7145af21b0adf7aa027633f4adc375#' docker-compose.yml`
3. **[서버]** `grep 'image: seokam36' docker-compose.yml` — 두 줄 모두 `dbef29b...` 태그인지 확인합니다.
4. **[서버]** `docker compose --profile prod pull app web && docker compose --profile prod up -d app web`
5. **[브라우저]** `/actuator/health`가 UP이고 글 목록이 뜨는지 봅니다.
- **주의**: (a)의 주의 박스와 같습니다. 고쳐서 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-latest docker-compose.yml`로 원복하고 `grep`으로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다. 안 하면 Deploy가 계속 옛 이미지를 받습니다.

### S18 배포 후 확인 (운영 챗봇을 제미나이로 켜기)
S18(제미나이 연결 `LLM_MODE=gemini`, 서버 전체 질문 상한, 챗봇 안내문)을 배포하면서 운영 챗봇을 처음 켭니다. 앞에서부터 순서대로 합니다. **순서가 중요합니다: CI 초록 확인 -> compose 백업·복사 -> `.env` 수정 -> Deploy 버튼.**

메모:
- compose가 app에 넘기는 값이 늘었습니다: `LLM_MODE`(기본 `off`), `GEMINI_API_KEY`, `GEMINI_MODEL`(기본 `gemini-3.5-flash-lite`), `GEMINI_TIMEOUT`(기본 `30s`), `CHAT_GLOBAL_PER_MINUTE`(기본 `7`), `CHAT_GLOBAL_PER_DAY`(기본 `200`). 서버 `.env`에는 **`LLM_MODE`와 `GEMINI_API_KEY` 두 줄만** 넣으면 됩니다. 나머지는 기본값이 운영 값이라 안 적어도 됩니다(적은 줄이 비어 있어도 기본값이 채워집니다).
- **compose가 바뀌었으니 EC2로 scp가 꼭 필요합니다.** 자동 배포(Deploy 버튼)는 서버의 compose를 바꾸지 않습니다. 빼먹으면 `.env`에 적어도 app에 전달되지 않아 챗봇은 계속 503입니다.
- 키는 AI Studio의 SpringStudy 프로젝트 무료 키입니다(분당 15호출·하루 500호출). 질문 하나가 호출을 최대 2번 쓰므로 서버 전체 상한을 분 7질문·하루 200질문으로 둡니다. 무료 등급이라 보낸 질문이 구글 제품 개선에 쓰일 수 있습니다.
- **V 파일 변경 없음**(flyway 최신 13 그대로). 새 이미지가 문제여도 DB 때문에 깨질 일은 없습니다.
- 시간 예산: 서버 `GEMINI_TIMEOUT` 30초 < nginx `/api/chat` 75초 < 화면 90초(그대로).
- 키가 비었거나 이상한데 `LLM_MODE=gemini`이면 앱이 **시작할 때 실패**합니다(Deploy 헬스체크도 빨간불). 로그의 오류 메시지에는 변수 이름만 나오고 키 값은 나오지 않습니다.
- gemini 모드는 시작 로그를 따로 남기지 않습니다(off일 때만 "AI 꺼짐" 로그). 그래서 켜졌는지는 아래 3번 `printenv`와 4번 챗봇 질문으로 확인합니다.

준비 (Deploy 버튼 누르기 전)
- 아래 명령의 `<퍼블릭IP>`는 **꺾쇠(`<` `>`)까지 지우고 숫자만** 넣습니다(예: `ec2-user@1.2.3.4`).
- PowerShell이면 `ssh`·`scp` 대신 `& "C:\Windows\System32\OpenSSH\ssh.exe"`·`& "C:\Windows\System32\OpenSSH\scp.exe"`를 쓰고, `~/.ssh/board-ec2` 자리에 `"$HOME\.ssh\board-ec2"`를 씁니다(위 비상용 3번 안내와 같음. 예: `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" docker-compose.yml ec2-user@1.2.3.4:~/board/docker-compose.yml`). Git Bash는 그대로 씁니다.
1. **[PC]** 이번 커밋의 CI 세 작업(backend, flutter, images)이 모두 초록인지 GitHub Actions 탭에서 봅니다(images가 초록이어야 새 이미지가 올라가 있습니다).
2. **[PC]** (EC2를 켠 뒤) 서버의 현재 compose를 백업합니다: `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> 'cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-s18'`
3. **[PC, 저장소 루트에서]** 새 compose를 서버로 복사합니다: `scp -i ~/.ssh/board-ec2 docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
4. **[서버]** `.env`에 두 줄을 넣습니다. **키가 화면·터미널 기록에 남지 않게 `cat .env`나 `echo 키 >> .env`는 쓰지 않고 편집기로 직접 고칩니다.**
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>` 로 접속한 뒤 `nano ~/board/.env` 를 엽니다. **nano 화면이 뜬 것을 확인한 뒤에** 붙여 넣습니다. 셸 프롬프트에 붙여 넣었다면 키가 화면과 `~/.bash_history`에 남으니 그 키는 교체합니다.
   - 맨 아래에 아래 두 줄을 적습니다(이미 같은 이름의 줄이 있으면 새로 만들지 말고 그 줄을 고칩니다).
     ```
     LLM_MODE=gemini
     GEMINI_API_KEY=여기에키를붙여넣기
     ```
   - **`여기에키를붙여넣기` 글자를 전부 지우고 그 자리에 내 진짜 키를 붙여 넣습니다.** `=` 앞뒤에 띄어쓰기·따옴표·`<` `>` 를 넣지 않고, 줄바꿈이 끼어도 안 됩니다.
   - 저장하고 닫습니다(nano는 `Ctrl+O` 엔터 -> `Ctrl+X`). nano가 비정상으로 끝났다면 `ls -la ~/board`에 `.env.save` 같은 파일이 남았는지 봅니다(키가 들어 있을 수 있으니 열어 보지 말고 `rm`으로 지웁니다).
   - 키를 화면에 띄우지 않고 확인합니다: `grep -c '^LLM_MODE=gemini$' ~/board/.env` 가 `1`, `grep -c '^GEMINI_API_KEY=여기에' ~/board/.env` 가 `0`(자리 글자가 남지 않음), `grep -c '^GEMINI_API_KEY=' ~/board/.env` 가 `1`.
   - 파일 권한을 나만 읽게 합니다: `chmod 600 ~/board/.env` 그리고 `ls -l ~/board/.env` 가 `-rw-------` 로 시작하는지 봅니다.
5. **[브라우저]** Actions 탭 -> Deploy -> Run workflow (위 "자동 배포"와 같음). 초록(DB 백업·헬스체크 포함)인지 봅니다.

확인 (배포 뒤)
1. **[브라우저]** `http://<퍼블릭IP>/actuator/health`가 UP인지 봅니다.
2. **[서버]** 앱이 안 죽고 떴는지: `cd ~/board && docker compose --profile prod logs --tail 100 app` 에 시작 실패(`APPLICATION FAILED`, `Application run failed`, `IllegalStateException`) 메시지가 없는지 봅니다.
3. **[서버]** 챗봇이 켜졌는지: `docker exec board-app printenv LLM_MODE` 가 `gemini`인지 봅니다. **키는 `printenv GEMINI_API_KEY`로 확인하지 않습니다**(화면에 키가 나옵니다). 키가 들어갔는지는 4번 질문이 답을 주면 알 수 있습니다.
4. **[브라우저]** 운영 테스트 계정으로 로그인 -> 상단바 "챗봇" -> 질문칸 위에 안내 한 줄이 보이는지 -> **"3만 원 이하 자전거 있어?"** 를 보냅니다. 답이 오고 아래에 참고한 글이 붙으면 성공입니다. 운영에 자전거 글이 없으면 "관련 글을 찾지 못했어요" 같은 문구도 **정상**입니다. "지금은 AI 챗봇을 쓸 수 없어요"(503)나 네트워크 오류 문구가 나오면 실패입니다(아래 문제 해결).
5. **[브라우저]** 같은 챗봇에 **"안녕"** 을 보냅니다. "저는 중고·나눔 글만 찾아 드려요. 찾는 물건 이름을 넣어 다시 물어봐 주세요" 범위 안내가 나오는지 봅니다.
6. **[서버]** 챗봇 질문까지 한 뒤의 메모리와 재시작: `docker stats --no-stream board-app` 의 메모리가 `512MiB` 미만인지(S17 배포 때는 약 368MiB/512MiB였습니다. 이보다 크게 늘면 지켜봅니다), `docker ps` 에서 `board-app`의 STATUS가 `Restarting`이 아니고 `Up` 시간이 계속 늘어나는지 봅니다(1분쯤 뒤 한 번 더).
7. **[서버]** 로그에 키가 새지 않았는지: `docker compose --profile prod logs app | grep -c 'x-goog-api-key\|AIza\|AQ\.'` 가 `0`인지 봅니다(구글 키 모양의 앞글자로 찾는 것이라 명령에 내 키를 쓸 필요가 없습니다). 0이 아니면 `grep -c 'x-goog-api-key'` 로 한 번 더 좁혀 봅니다(`AQ.`는 우연히 맞을 수 있습니다). 진짜 키가 샌 것 같으면 로그를 화면에 펴 보지 말고 바로 아래 "제미나이 키 교체"를 합니다. 참고: 컨테이너를 새로 만들면 옛 로그는 없어지므로 이 검사는 새로 뜬 뒤 로그만 봅니다.
8. **[서버]** 표 변경이 없었는지: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'` 에서 가장 마지막 줄이 `13 | t`인지 봅니다.
9. **[브라우저]** 기존 기능 3가지(글 목록, 중고 글 상세, 새로고침해도 로그인 유지)를 봅니다.

문제 해결
- 앱이 시작 때 실패: 로그의 `IllegalStateException` 메시지에 나온 **변수 이름**을 봅니다. `GEMINI_API_KEY가 비어 있습니다` -> `.env`에 줄이 없거나 값이 비었습니다. `공백이나 줄바꿈` -> 키 앞뒤에 공백·줄바꿈이 붙었습니다. 고친 뒤 `docker compose --profile prod up -d app`.
- 로그에 `LLM_MODE는 off 또는 ollama여야 합니다`가 보임: 새 이미지가 아니라 옛 이미지가 뜬 것입니다. Actions에서 CI images가 초록인지 확인하고 Deploy를 다시 누릅니다. 급하면 `.env`의 `LLM_MODE`를 `off`로 바꾸고 `docker compose --profile prod up -d app`으로 먼저 살립니다.
- 챗봇이 503: `printenv LLM_MODE`가 `off`이면 compose를 scp하지 않았거나 `.env`에 `LLM_MODE=gemini`가 없는 것입니다(위 준비 3·4번). `gemini`인데 503이면 서버 전체 상한이나 구글 쪽 문제입니다(아래 "전체 상한과 503 구분"). 구글 쪽인지 보려면 `docker compose --profile prod logs app | grep 'gemini 호출 실패'` (로그에는 상태 숫자만 남고 키는 없습니다): 400·401·403이면 키 오류라 `.env`를 다시 확인, 429면 구글 한도, 404면 모델 이름(`GEMINI_MODEL`)을 봅니다.
- 챗봇이 네트워크 오류: nginx나 app 연결 문제입니다. `docker compose --profile prod ps`로 app이 떠 있는지 봅니다.

**전체 상한과 503 구분** (**[서버]**)
- 상한 때문이면: `docker compose --profile prod logs app | grep -c '전체 상한'` 이 1 이상(로그 "챗봇 서버 전체 상한(분|하루) 넘음 -> 503")입니다. 정상 동작이고 분 상한은 1분, 하루 상한은 태평양 자정(한국 오후 4~5시) 뒤 풀립니다. 푸는 법은 위 "챗봇이 '서버 전체 상한' 때문에 막혔다면" 절을 봅니다.
- 0이면 상한이 아니라 구글 쪽(키 오류·구글 한도 소진·구글 장애) 문제입니다.
- **Redis를 재시작하면 카운터가 모두 0으로 돌아갑니다**(디스크에 저장하지 않는 설정). 그 순간 상한이 초기화되니 구글 무료 한도에 가깝게 쓰는 날에는 Redis를 재시작하지 않습니다.

**운영 챗봇 끄기** (문제가 생기거나 한도를 아끼고 싶을 때. 이미지를 되돌릴 필요가 없습니다)
1. **[서버]** `nano ~/board/.env` 에서 `LLM_MODE=gemini` 줄을 `LLM_MODE=off` 로 바꿉니다(줄을 지워도 같습니다. **빈 값으로 두지는 않습니다**: 빈 값이어도 compose 기본값이 `off`로 채우지만, 헷갈리지 않게 `off`라고 씁니다). `GEMINI_API_KEY` 줄은 그대로 둬도 됩니다.
2. **[서버]** `cd ~/board && docker compose --profile prod up -d app` — 환경변수가 바뀌었으므로 app 컨테이너가 새로 만들어집니다(단순 `restart`는 `.env`를 다시 읽지 않습니다).
3. 확인: `docker exec board-app printenv LLM_MODE` 가 `off`, 브라우저 챗봇이 "지금은 AI 챗봇을 쓸 수 없어요"(503)를 줍니다.

**제미나이 키 교체** (유출 의심, 정기 교체)
1. AI Studio에서 새 키를 만들고 옛 키는 삭제(또는 제한)합니다.
2. **[서버]** `nano ~/board/.env` 에서 `GEMINI_API_KEY=` 줄의 값만 새 키로 고칩니다(`cat .env`로 화면에 띄우지 않습니다. 앞뒤 공백·줄바꿈 금지).
3. **[서버]** `cd ~/board && docker compose --profile prod up -d app` — app이 새로 만들어집니다.
4. 위 "확인" 3·4번으로 켜졌는지, 7번으로 로그에 키가 없는지 봅니다.

**키가 보이는 명령 주의** (아래는 키가 화면·로그·기록에 그대로 나옵니다. 치지 않습니다)
- `docker inspect board-app` (환경변수 목록에 키가 나옵니다)
- `docker compose config` / `docker compose --profile prod config` (`.env` 값을 채워 보여 줍니다. 필요하면 위 메일 절처럼 `| grep -c '이름'` 으로 개수만 봅니다)
- `docker exec board-app printenv GEMINI_API_KEY` / `printenv`(이름 없이) / `env`
- `cat ~/board/.env`
- 앱 실행 옵션 `-Djdk.httpclient.HttpClient.log=headers` (HTTP 요청 헤더를 로그에 찍어 `x-goog-api-key` 헤더에 담긴 키가 남습니다)
- `GEMINI_TIMEOUT`·`CHAT_GLOBAL_PER_MINUTE`·`CHAT_GLOBAL_PER_DAY` 같은 칸에 값을 잘못 넣으면(예: 숫자·시간이 아닌 글자) 스프링이 "읽을 수 없는 값"이라며 **그 값을 오류 로그에 그대로 찍습니다.** 키를 엉뚱한 칸에 붙여 넣지 않습니다(키는 `GEMINI_API_KEY=` 줄에만). 실수로 그랬다면 로그에 키가 남은 것이니 키를 교체합니다.
- 키를 **nano 화면이 아닌 셸 프롬프트에 붙여 넣는 것**(nano가 뜬 걸 확인한 뒤에 붙여 넣습니다). 셸에 붙여 넣었다면 키가 화면과 `~/.bash_history`에 남으니 키를 교체합니다.
- Actions 로그·화면 공유·메신저에 `.env` 내용을 붙여 넣지 않습니다.

**S18 되돌리기** (문제가 있을 때만). 챗봇만 끄면 되는 문제는 위 "운영 챗봇 끄기"로 충분합니다. 새 이미지 자체를 되돌려야 하면 위 "(a) 이전 이미지로 되돌리기"와 같은 방식으로 app과 web 두 줄을 직전 운영 배포(Deploy #19, 커밋 8d8c14d)의 이미지로 바꿉니다. V 파일이 없어서 DB는 그대로 둡니다.
- 되돌릴 이미지: `seokam36/demo:8d8c14ddc220ce92d8c13647c28d3421f371e12e`, `seokam36/demo-web:8d8c14ddc220ce92d8c13647c28d3421f371e12e` (S18 배포 사이에 다른 main 배포가 있었다면 그 직전 운영 배포 커밋의 SHA로 바꿉니다)
0. **[서버] 먼저 `~/board/.env`의 `LLM_MODE`를 `off`로 바꿉니다**(위 "운영 챗봇 끄기" 1번 참고). 옛 이미지(S17)의 시작 검사는 `off`와 `ollama`만 받아서, `.env`에 `LLM_MODE=gemini`가 남아 있으면 **시작에 실패합니다.** 이때 로그에 `LLM_MODE는 off 또는 ollama여야 합니다 (현재 값은 출력하지 않음).`가 뜹니다. 옛 이미지는 `GEMINI_*`·`CHAT_GLOBAL_*` 환경변수를 그냥 무시하므로 `.env`의 키 줄과 S18 compose는 그대로 둬도 됩니다.
1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-latest` — 지금 설정을 복사해 둡니다(이미 있으면 덮어쓰지 말고 다른 이름으로).
2. **[서버]** `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:8d8c14ddc220ce92d8c13647c28d3421f371e12e#; s#image: seokam36/demo-web:latest#image: seokam36/demo-web:8d8c14ddc220ce92d8c13647c28d3421f371e12e#' docker-compose.yml`
3. **[서버]** `grep 'image: seokam36' docker-compose.yml` — 두 줄 모두 `8d8c14d...` 태그인지 확인합니다.
4. **[서버]** `docker compose --profile prod pull app web && docker compose --profile prod up -d app web`
5. **[브라우저]** `/actuator/health`가 UP이고 글 목록이 뜨는지 봅니다.
- **주의**: (a)의 주의 박스와 같습니다. 고쳐서 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-latest docker-compose.yml`로 원복하고 `grep`으로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다. 안 하면 Deploy가 계속 옛 이미지를 받습니다.

### S20 배포 (유학생 인증: 표준입학허가서 신청·심사·판독)
S18 다음 첫 운영 배포입니다. S18 배포 뒤 쌓인 S20 전체(허가서 신청·관리자 심사·자동 판정·14일 만료, 메일 인증 제거)가 한 번에 나갑니다. **순서가 중요합니다: CI 초록 -> 서버 백업 -> `.env`에 새 비밀 값 -> 새 compose scp -> Deploy 버튼.** 순서를 어기면(특히 `.env`·compose 없이 Deploy) 앱이 시작하지 못하고 사이트가 내려갑니다.

메모:
- **`<퍼블릭IP>` 칸**: 배포를 실제로 진행할 때 메인 대화(Claude)가 사용자에게 IP를 받아 완성된 명령으로 바꿔 줍니다. 직접 채울 때는 꺾쇠(`<` `>`)까지 지우고 숫자만 넣습니다(예: `ec2-user@1.2.3.4`). PowerShell이면 `ssh`·`scp` 대신 `& "C:\Windows\System32\OpenSSH\ssh.exe"`·`& "C:\Windows\System32\OpenSSH\scp.exe"`를 쓰고 키 경로는 `"$HOME\.ssh\board-ec2"`입니다(아래 명령은 Git Bash 모양, 위 S18 "준비" 설명과 같음).
- **새 환경변수 4개**(compose가 `.env`에서 읽어 app에 넘김): `PERMIT_HASH_SECRET`(**필수**, 허가서 번호 해시용 비밀 값, 32바이트 이상), `PERMIT_OCR_MODE`(기본 `off`, 값은 `off` 또는 `vision`), `VISION_API_KEY`(`vision`일 때만 필요, 처음엔 적지 않음), `VISION_TIMEOUT`(기본 `10s`, 적지 않음). 서버 `.env`에는 **처음엔 `PERMIT_HASH_SECRET`과 `PERMIT_OCR_MODE=off` 두 줄만** 넣습니다.
- **`PERMIT_HASH_SECRET`은 한 번 정하면 바꾸지 않습니다.** 바꾸면 이미 저장된 허가서 번호 해시와 달라져 "같은 허가서를 다른 계정에서 또 쓰는 것"을 못 막게 됩니다. 서버가 망가져도 되살릴 수 있게 **서버 밖 안전한 곳(비밀번호 관리자 등)에도 따로 보관**합니다. 저장소·메신저·Notion에는 적지 않습니다.
- 처음엔 **`PERMIT_OCR_MODE=off`**: 글자 판독기가 항상 "못 읽음"이라 모든 신청이 관리자 대기로 남습니다(자동 승인 없음). 화면이 잘 도는 걸 확인한 뒤 아래 "vision 전환"을 따로 합니다.
- **V14 새 표 2개**(`permit_requests`, `school_name_aliases`)가 앱이 켜질 때 Flyway로 적용됩니다. 기존 표·칸은 바뀌지 않습니다. Deploy가 직전에 DB를 자동 백업합니다.
- 메일 인증(S10)이 없어졌습니다. 새 compose에는 `MAIL_*` 줄이 없습니다. 서버 `.env`의 `MAIL_*` 줄과 Gmail 앱 비밀번호는 **배포 확인이 끝날 때까지 그대로 둡니다**(S18로 되돌릴 때 필요, 아래 "S20 되돌리기"와 "확인 끝난 뒤 정리").
- nginx(`web` 이미지)에 허가서 신청 주소(`/api/users/me/permit-verification`)의 11MB·150초 예외가 들어 있습니다. 새 web 이미지와 함께 Deploy가 반영합니다.

**1) 배포 전 (내 PC·GitHub)**
1. **[PC]** 배포할 커밋을 push한 뒤(push는 직접) GitHub Actions 탭에서 그 커밋의 CI 세 작업(backend, flutter, images)이 모두 초록인지 봅니다. images가 초록이어야 새 이미지가 올라가 있습니다. Deploy는 항상 마지막 `latest`를 받으므로, 배포할 커밋이 main의 마지막 커밋인지도 확인합니다.
2. **[PC]** 배포할 커밋 SHA를 적어 둡니다(되돌리기·기록용): `git rev-parse HEAD`

**2) 서버 준비 (EC2를 켠 뒤 Deploy 버튼 전)**
1. **[콘솔]** EC2 인스턴스를 켜고(시작) 퍼블릭 IP를 확인합니다.
2. **[PC]** 현재 compose를 백업합니다. 이 파일이 S18 되돌리기의 열쇠입니다(MAIL_* 줄이 들어 있음). 같은 이름이 이미 있으면 덮어쓰지 않고 멈춥니다:
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> 'test ! -e ~/board/docker-compose.yml.bak-s20 && cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-s20 && ls -l ~/board/docker-compose.yml.bak-s20'`
   - 확인: `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> "grep -c '^ *MAIL_' ~/board/docker-compose.yml.bak-s20"` 가 `6`인지 봅니다.
3. **[PC]** 현재 `.env`도 백업합니다(권한 600으로 만들어짐. 안에 비밀이 있으니 열어 보지 않고, 배포 확인이 끝나면 지웁니다):
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> 'umask 077 && cp ~/board/.env ~/board/.env.bak-s20 && ls -l ~/board/.env.bak-s20'` (`-rw-------`인지 봅니다)
4. **[PC, PowerShell]** `PERMIT_HASH_SECRET` 값을 만듭니다(48바이트 무작위 -> 64글자). 화면에 나온 값을 **바로 비밀번호 관리자에 저장**하고, 복사해 둡니다(클립보드):
   - `$b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`
   - (서버에서 `openssl rand -base64 48`로 만들 수도 있지만, 서버 밖에도 보관해야 하므로 PC에서 만드는 쪽을 권합니다. 값이 터미널 기록에 남으니 만든 뒤 창을 닫습니다.)
5. **[서버]** `.env`에 두 줄을 넣습니다. **키가 화면·터미널 기록에 남지 않게 `cat .env`나 `echo 값 >> .env`는 쓰지 않고 편집기로 직접 고칩니다.**
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>` 로 접속한 뒤 `nano ~/board/.env` 를 엽니다. **nano 화면이 뜬 것을 확인한 뒤에** 붙여 넣습니다(셸 프롬프트에 붙여 넣었다면 값이 `~/.bash_history`에 남으니 그 값은 버리고 새로 만듭니다. 아직 한 번도 쓰지 않았을 때만 가능).
   - 맨 아래에 아래 두 줄을 적습니다(같은 이름의 줄이 이미 있으면 새로 만들지 말고 그 줄을 고칩니다).
     ```
     PERMIT_HASH_SECRET=여기에값을붙여넣기
     PERMIT_OCR_MODE=off
     ```
   - **`여기에값을붙여넣기` 글자를 전부 지우고 4번에서 만든 값을 붙여 넣습니다.** `=` 앞뒤에 띄어쓰기·따옴표·`<` `>` 를 넣지 않고 줄바꿈이 끼면 안 됩니다. `PERMIT_OCR_MODE`는 빈 값으로 두면 앱이 시작하지 못하니 `off`라고 씁니다.
   - 저장하고 닫습니다(nano는 `Ctrl+O` 엔터 -> `Ctrl+X`). nano가 비정상으로 끝나 `.env.save`가 `~/board`에 남았다면 열어 보지 말고 `rm`으로 지웁니다.
   - 값을 화면에 띄우지 않고 확인합니다(**[서버]**): `grep -c '^PERMIT_HASH_SECRET=여기에' ~/board/.env` 가 `0`, `grep -c '^PERMIT_HASH_SECRET=' ~/board/.env` 가 `1`, `grep -c '^PERMIT_OCR_MODE=off$' ~/board/.env` 가 `1`.
   - 길이만 봅니다(값은 안 나옴): `grep '^PERMIT_HASH_SECRET=' ~/board/.env | awk '{print length($0)-19}'` 가 `64`(32 이상이면 됩니다).
   - 권한: `chmod 600 ~/board/.env` 그리고 `ls -l ~/board/.env` 가 `-rw-------` 로 시작하는지 봅니다. 다 끝나면 `exit`로 나옵니다.
6. **[PC, 저장소 루트에서]** 새 compose를 서버로 복사합니다: `scp -i ~/.ssh/board-ec2 docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
7. **[서버]** 새 compose가 `.env` 값을 제대로 읽는지 봅니다. **`docker compose config`를 그대로 출력하지 않습니다**(비밀 값이 화면에 나옴). 아래처럼 개수만 봅니다:
   - `cd ~/board && docker compose --profile prod config -q && echo 문법OK` — 오류 없이 `문법OK`가 나와야 합니다.
   - `docker compose --profile prod config | grep -c '^ *PERMIT_HASH_SECRET: ""'` 가 `0`(값이 채워짐)
   - `docker compose --profile prod config | grep -E '^ *(PERMIT_OCR_MODE|VISION_TIMEOUT):'` — 비밀이 아니라서 그대로 나옵니다. `off`와 `10s`인지 봅니다.
   - `docker compose --profile prod config | grep -c '^ *MAIL_'` 가 `0`(새 compose에는 메일 줄이 없음)
8. **[브라우저]** Actions 탭 -> Deploy -> Run workflow (위 "자동 배포"와 같음). DB 백업·헬스체크가 모두 초록인지 봅니다. 이 워크플로가 하는 일은 위 "자동 배포"와 같습니다: EC2 IP 조회 -> SSH 문 잠깐 열기 -> **DB 자동 백업**(실패하면 여기서 중단) -> `docker compose pull`/`up -d` -> `/actuator/health` UP 확인(최대 약 3~4분) -> SSH 문 닫기.

**3) AWS 콘솔 작업** (Deploy 전후 어느 쪽이든 되지만, **S3 권한은 첫 신청 시험 전에** 끝냅니다)
1. **IAM 역할 `board-ec2-role`의 S3 정책 확인**: 콘솔 -> IAM -> 역할 -> `board-ec2-role` -> 연결된 정책을 열어 JSON을 봅니다. 사진 객체(`Resource`가 `.../*` 끝)에 `s3:PutObject`·`s3:GetObject`·`s3:DeleteObject` 세 가지가 **`permits/` 아래까지** 허용돼야 합니다.
   - `arn:aws:s3:::<버킷 이름>/*` 처럼 버킷 전체면 이미 됩니다(고칠 것 없음).
   - `arn:aws:s3:::<버킷 이름>/market/*` 처럼 좁혀져 있으면 **`arn:aws:s3:::<버킷 이름>/permits/*` 한 줄을 Resource에 추가**하고 저장합니다. 안 하면 허가서 신청이 500이 됩니다.
   - 버킷 자체에 대한 `s3:ListBucket`(앱이 시작할 때 버킷을 확인함)은 그대로 둡니다.
2. **S3 수명 주기 규칙 `permits/` 접두사 14일**(안전망): 서버를 14일 넘게 꺼 두면 앱의 14일 만료 작업이 못 돌기 때문에, S3가 직접 지웁니다. 콘솔 -> S3 -> 사진 버킷 -> "관리" 탭 -> "수명 주기 규칙 생성".
   - 규칙 이름: `permits-expire-14d`
   - **규칙 범위: "하나 이상의 필터를 사용하여 이 규칙의 범위 제한"을 고르고, 접두사에 `permits/` 를 적습니다.**
   - **경고: "버킷의 모든 객체에 적용"을 고르면 중고 사진(`market/`)까지 14일 뒤에 전부 지워집니다.** 고르면 안 됩니다.
   - 규칙 작업: "현재 버전의 객체 만료"를 체크하고 객체 생성 후 일수 `14`. (버전 관리가 켜져 있다면 "이전 버전의 객체 영구 삭제"도 체크하고 `1`일로 둡니다.)
   - 저장한 뒤 규칙 목록에서 상태가 "사용", 범위가 `permits/` 접두사인지 다시 확인합니다.
3. **기본 암호화 확인**: S3 -> 사진 버킷 -> "속성" 탭 -> "기본 암호화"가 켜져 있는지(SSE-S3면 충분) 봅니다. 새 버킷은 기본으로 켜져 있습니다.

**4) 배포 뒤 확인** (앞에서부터 순서대로)
1. **[브라우저]** Deploy 실행이 초록(DB 백업·헬스체크 포함)인지, `http://<퍼블릭IP>/actuator/health`가 UP인지 봅니다.
2. **[서버]** 앱이 안 죽고 떴는지: `cd ~/board && docker compose --profile prod logs --tail 100 app` 에 시작 실패(`APPLICATION FAILED`, `Application run failed`, `IllegalStateException`)가 없는지 봅니다. app이 계속 재시작하면 `--tail 100`에는 마지막만 보이니 `docker compose --profile prod logs app | grep -E 'IllegalStateException|APPLICATION FAILED'` 로 찾습니다. 실패 글자와 뜻은 아래 표.
3. **[서버]** 판독기가 꺼진 채로 떴는지: `docker compose --profile prod logs app | grep -c '허가서 글자 판독 꺼짐'` 가 `1`(시작 로그 `허가서 글자 판독 꺼짐(PERMIT_OCR_MODE=off): 모든 신청은 관리자 대기로 남습니다.`). 0이면 새 이미지가 안 뜬 것이거나 `PERMIT_OCR_MODE`가 `off`가 아닙니다.
4. **[서버]** 표 변경 확인: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"'` 에서 가장 위가 `14 | t`인지 봅니다.
5. **[서버]** 학교 별칭 24건: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM school_name_aliases;"'` 가 `24`인지 봅니다.
6. **[브라우저]** 화면 확인(계정이 둘 필요합니다: 인증 안 된 새 테스트 계정 A·B, 관리자 계정. 운영 테스트 계정 `wntjrdka`는 이미 연세대 인증이라 신청에는 쓰지 않습니다). 사진은 **지어낸 값만 보이게 가린 견본**이나 아무 2~5MB 사진을 씁니다(실제 허가서를 쓰지 않습니다. `off` 모드라 판독은 안 합니다):
   1. 계정 A로 가입 -> 내 정보에서 "유학생 인증" 신청 -> **2~5MB 사진**으로 신청 -> **201(접수됨)** 이 되는지(1MB 넘는 사진이라 nginx 11MB 예외까지 같이 확인됨. 413이면 새 web 이미지가 안 뜬 것입니다).
   2. 관리자 계정으로 로그인 -> "인증 심사" -> 계정 A의 줄에서 **사진 보기**가 되는지 -> 학교와 허가서 번호(지어낸 값, 영문·숫자 부분만)로 **승인**.
   3. 계정 A 다시 로그인(또는 새로고침) -> 인증 완료 표시, **글쓰기가 되는지**.
   4. 계정 B로 신청 -> 관리자가 **반려**(사유 하나) -> 계정 B 화면에 반려 사유 문구가 보이고 다시 신청할 수 있는지.
   5. 계정 B로 다시 신청한 뒤 **대기 중에 철회**합니다. 이 직전에 **[콘솔]** S3 -> 사진 버킷 -> `permits/` 폴더에 B의 파일이 1개 보이고, 철회 뒤 새로고침하면 **없어지는지**(대기 중 철회 = 사진 즉시 삭제). 승인한 A의 파일도 `permits/`에 남지 않아야 합니다.
   6. 화면에 학교 메일 입력칸이 더 이상 보이지 않는지, 글 목록·중고 글 상세·새로고침해도 로그인 유지(기존 기능 3가지)도 봅니다. `wntjrdka`로 로그인해 글쓰기가 그대로 되는지도 봅니다.
   - 시험이 끝나면 테스트 계정 A·B는 탈퇴로 정리합니다(탈퇴하면 신청·사진도 같이 지워집니다).
7. **[서버]** 로그 확인(0이어야 정상): `docker compose --profile prod logs app | grep -c '사진 파일 삭제에 실패'` 가 `0`, `docker compose --profile prod logs app | grep -c 'permits/'` 가 `0`(허가서 사진 이름은 로그에 남지 않게 만들었습니다. 0이 아니면 개인정보가 샌 것이니 로그를 펴 보지 말고 알려 주세요).
8. **[서버]** 메모리와 재시작: `docker stats --no-stream board-app` 의 메모리가 `512MiB` 미만인지(S18 때 약 375MiB), `docker ps` 에서 `board-app`이 `Restarting`이 아니고 `Up` 시간이 늘어나는지 봅니다(1분쯤 뒤 한 번 더).
9. **[서버]** (관리자 본인 학교) 관리자는 **자기 신청을 심사할 수 없습니다**(403). 관리자 계정을 인증 상태로 만들려면 SQL로 합니다(이미 인증된 `wntjrdka`는 할 필요 없음). `<아이디>`는 그 회원의 로그인 아이디:
```sh
docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
UPDATE users SET verified_school_id = (SELECT id FROM schools WHERE name = '연세대학교'), verified_at = now() WHERE username = '<아이디>';
SQL
```
   `UPDATE 1`이 나오면 성공입니다.

**시작 실패 메시지 표** (**[서버]** `docker compose --profile prod logs app | grep -E 'PERMIT_|VISION_'` 로 찾습니다. 메시지에는 변수 이름만 나오고 값은 나오지 않습니다)

| 로그에 보이는 글자 | 뜻 | 할 일 |
|---|---|---|
| `PERMIT_HASH_SECRET이 비어 있습니다` | `.env`에 줄이 없거나 값이 빔. **compose를 scp하지 않았을 때도** 같은 메시지(새 줄이 app에 안 넘어감) | `.env` 확인(위 2-5), compose scp 확인(위 2-6, 서버 compose에 `PERMIT_HASH_SECRET`이 있는지 `grep -c PERMIT_HASH_SECRET ~/board/docker-compose.yml` 가 1 이상) |
| `PERMIT_HASH_SECRET은 32바이트 이상이어야 합니다` | 값이 짧음(잘렸거나 일부만 붙여 넣음) | 64글자 값을 다시 넣음(위 2-5의 길이 확인) |
| `PERMIT_OCR_MODE는 off, vision 중 하나여야 합니다` | 값이 `off`/`vision`이 아님(`OFF`·`Vision`·앞뒤 공백도 실패) | `.env`의 `PERMIT_OCR_MODE=off` 로 고침 |
| `PERMIT_OCR_MODE=vision인데 VISION_API_KEY가 비어 있습니다` | vision인데 키가 없음 | 키를 넣거나 `off`로 되돌림 |
| `VISION_API_KEY에 공백이나 줄바꿈 같은 글자가 들어 있습니다` | 키 앞뒤에 공백·줄바꿈이 붙음 | `.env`의 그 줄을 다시 고침 |
| `VISION_TIMEOUT은 0보다 커야 합니다` | 시간 값이 0 이하 | 줄을 지우거나 `10s` |

고친 뒤에는 `cd ~/board && docker compose --profile prod up -d app` (단순 `restart`는 `.env`를 다시 읽지 않습니다). 앱이 시작 못 하는 동안 사이트는 502입니다. 빨리 고칠 수 없으면 아래 "S20 되돌리기"로 먼저 살립니다.

**S20 되돌리기** (문제가 있을 때만. 위 "Flyway 문제와 되돌리기"의 "S18 이미지로 돌아갈 때 (V14 적용 후)"에 안전한 이유가 있습니다)
- 핵심: **V14 표 2개가 DB에 남아 있어도 S18 이미지는 뜹니다**(ddl-auto `validate`는 엔티티에 있는 표·칸만 검사). DB를 되돌릴 필요가 없고 **기록장·V14 표를 손으로 지우지 않습니다**.
- 되돌릴 이미지: `seokam36/demo:2f4e560fa6057c2ec01bd290a29013f7cdb92c31`, `seokam36/demo-web:2f4e560fa6057c2ec01bd290a29013f7cdb92c31` (S18 배포 때의 이미지. 그 커밋 CI의 images가 초록이었어야 태그가 있습니다. 모르겠으면 GitHub Packages가 아니라 Docker Hub의 태그 목록을 봅니다.)
- **compose는 S20 것이 아니라 2-2에서 만든 백업(`docker-compose.yml.bak-s20`)을 바탕으로 합니다.** 이유: S18 앱은 메일 설정(`MAIL_*`)을 compose로 받는데, S20 compose에는 그 줄이 없어서 그대로 되돌리면 메일이 조용히 `log` 모드(코드를 로그에만 찍음)로 바뀌어 인증 메일이 안 갑니다. 백업에는 `MAIL_*` 줄과 S18의 나머지 줄이 그대로 있습니다. **그래서 배포 확인이 끝나기 전에는 서버 `.env`의 `MAIL_*`와 `.bak-s20` 파일을 지우지 않습니다.**
1. **[서버]** `cd ~/board && grep -c '^MAIL_' .env` 가 `6`인지 봅니다(값은 안 나옴). 6이 아니면 메일 설정이 사라진 것이라 되돌려도 메일이 안 갑니다(알려 주세요).
2. **[서버]** `cp docker-compose.yml docker-compose.yml.bak-s20-new` — 지금(S20) compose를 보관합니다(이미 있으면 덮어쓰지 말고 다른 이름으로).
3. **[서버]** `cp docker-compose.yml.bak-s20 docker-compose.yml` — S18 compose로 되돌립니다.
4. **[서버]** `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:2f4e560fa6057c2ec01bd290a29013f7cdb92c31#; s#image: seokam36/demo-web:latest#image: seokam36/demo-web:2f4e560fa6057c2ec01bd290a29013f7cdb92c31#' docker-compose.yml`
5. **[서버]** `grep 'image: seokam36' docker-compose.yml` — 두 줄 모두 `2f4e560...` 태그인지 확인합니다.
6. **[서버]** `docker compose --profile prod pull app web && docker compose --profile prod up -d app web`
7. **[서버]** `docker compose --profile prod logs --tail 100 app` 에 시작 실패가 없고(`newer than the latest available migration (14) ... (13)` 경고는 정상), **[브라우저]** `/actuator/health`가 UP이고 글 목록이 뜨는지 봅니다. 이어서 `docker exec board-app printenv LLM_MODE` 가 `gemini`(S18 때와 같음)인지도 봅니다.
- 되돌려 있는 동안 달라지는 것(코드·SQL을 읽고 판단한 것이고 실제로 돌려 보지는 않았습니다):
  - 허가서 신청·심사 화면과 API가 사라지고, 학교 메일 인증 화면이 돌아옵니다. 허가서로 승인된 회원은 `verified_school_id`가 채워져 있어 S18 코드에서도 인증된 회원으로 보여 글쓰기가 됩니다.
  - 이미 접수된 대기 신청(`PENDING`)과 S3의 `permits/` 사진은 그대로 남습니다. S18 코드는 14일 만료 작업이 없으니 **S3 수명 규칙(3-2)이 안전망**입니다. 이 규칙은 되돌려도 지우지 않습니다.
  - 대기 신청이 있는 회원이 S18 코드에서 탈퇴하면 DB가 신청 행을 같이 지워 오류는 나지 않지만(`ON DELETE CASCADE`) 사진 파일 이름을 아무도 모르게 되어 S3에 남습니다(수명 규칙이 14일 안에 지움).
  - `.env`의 `PERMIT_*`·`VISION_*` 줄은 S18 코드가 무시하니 그대로 둬도 됩니다.
- **주의**: 위 "(a) 이전 이미지로 되돌리기"의 주의 박스와 같습니다. image 줄을 SHA로 고정해 두면 **다음 Deploy도 S18 이미지를 받습니다.** 원인을 고친 새 `latest`가 올라온 뒤(Actions 초록) **[서버]** `cp docker-compose.yml.bak-s20-new docker-compose.yml`(S20 compose 복원)로 원복하고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다.
- **하지 말 것**: V14를 손으로 되돌리거나(표 삭제) `flyway_schema_history` 행을 지우지 마세요(새 코드로 돌아올 때 "이미 있음" 오류가 납니다).

**vision 전환** (나중에, 위 4번 화면 확인이 모두 끝난 뒤 따로 합니다. 이때부터 허가서 사진이 구글 Cloud Vision으로 전송되고 자동 승인이 시작됩니다. 화면의 동의·가림 안내 문구는 S20-g1에 이미 들어 있습니다)
1. **[Google Cloud]** 프로젝트 `board-vision`(Gemini 키가 있는 SpringStudy 프로젝트가 아님)에서 **서버용 새 키**를 만듭니다. "API 제한"은 **Cloud Vision API만**. 시험 키 `vision-local-test`는 **삭제**하고, 내 PC 로컬 `.env`의 `VISION_API_KEY`도 지웁니다(서버용과 같은 키를 쓰지 않음). 예산 알림(₩5,000)과 "Requests per minute" 5 설정이 남아 있는지도 봅니다.
2. **[서버]** `nano ~/board/.env` 에서 `PERMIT_OCR_MODE=off` 줄을 `PERMIT_OCR_MODE=vision` 으로 **고치고**(새 줄을 만들지 않음), `VISION_API_KEY=여기에키를붙여넣기` 줄을 추가해 진짜 키로 바꿉니다. 키는 nano 화면이 뜬 뒤에 붙여 넣습니다(위 2-5와 같은 규칙). 확인(**[서버]**): `grep -c '^PERMIT_OCR_MODE=vision$' ~/board/.env` 가 `1`, `grep -c '^VISION_API_KEY=여기에' ~/board/.env` 가 `0`, `grep -c '^VISION_API_KEY=' ~/board/.env` 가 `1`.
3. **[서버]** `cd ~/board && docker compose --profile prod up -d app` — app이 새 환경변수로 다시 만들어집니다.
4. **[서버]** 켜졌는지: `docker exec board-app printenv PERMIT_OCR_MODE` 가 `vision`인지 봅니다. **키는 `printenv VISION_API_KEY`로 확인하지 않습니다**(화면에 나옴). 시작 로그: `docker compose --profile prod logs app | grep -c '허가서 판독기: vision'` 이 `1`.
5. **[서버]** 국적 목록 경고가 없는지: `docker compose --profile prod logs app | grep -c '허가서 국적 목록에 한국어 나라 이름이 없음'` 이 `0`이어야 합니다. 1 이상이면 이미지(jre-alpine)에 한국어 나라 이름 데이터가 없는 것이라 **국적 판정이 항상 불통과 -> 자동 승인이 한 건도 안 됩니다**(전부 관리자 대기로 안전하게 남음). 이때는 vision을 `off`로 되돌리고 알려 주세요(이미지 쪽 수정이 필요).
6. **[브라우저]** 사용자 본인의 허가서(개인정보를 가린 사진)로 신청해 보고, 응답이 몇 초 안에 오는지(최대 약 10초), 결과가 자동 승인 또는 관리자 대기로 나오는지 봅니다. 판독 결과·허가서 번호는 이 파일·채팅·Notion에 적지 않습니다.
7. **[서버]** 메모리: `docker stats --no-stream board-app` 을 **신청 직후와 1분 뒤** 두 번 봅니다(512MiB 미만, 사진 한 장 판독 때 순간적으로 30~45MB 늘어남. 400MiB 근처까지 오르면 알려 주세요: 동시 판독 수를 1로 줄이는 설정이 필요한데, 지금 compose에는 그 환경변수가 없어 새 단계로 만들어야 합니다).
8. **[서버]** 로그에 키가 새지 않았는지: `docker compose --profile prod logs app | grep -c 'x-goog-api-key\|AIza\|AQ\.'` 가 `0`.
9. **끄기**: `.env`의 `PERMIT_OCR_MODE`를 `off`로 고치고 `docker compose --profile prod up -d app`. 키가 의심스러우면 Google Cloud에서 그 키를 삭제하고 새로 만들어 2번부터 다시 합니다.
- **키가 보이는 명령 주의**: `docker inspect board-app`, `docker compose config`(그대로 출력), `printenv VISION_API_KEY`·`printenv`·`env`, `cat ~/board/.env`, 앱 실행 옵션 **`-Djdk.httpclient.HttpClient.log=headers`**(HTTP 요청 헤더를 로그에 찍어 `x-goog-api-key`에 담긴 키가 남습니다. **절대 켜지 않습니다**). `VISION_TIMEOUT`처럼 시간·숫자 칸에 키를 잘못 붙여 넣으면 스프링이 그 값을 오류 로그에 그대로 찍습니다. 키는 `VISION_API_KEY=` 줄에만 적습니다.

**확인 끝난 뒤 정리** (위 4번이 모두 통과하고, 며칠 써 보고 S18로 되돌릴 일이 없다고 판단한 뒤에 합니다. **이 정리 뒤에는 S18로 되돌려도 메일 인증 메일이 가지 않습니다**)
1. **[서버]** 백업한 `.env`를 지웁니다(옛 비밀이 들어 있음): `rm ~/board/.env.bak-s20 && ls -l ~/board`
2. **[서버]** `nano ~/board/.env` 에서 `MAIL_*` 줄(`MAIL_MODE`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` 여섯 줄)을 지웁니다. 확인: `grep -c '^MAIL_' ~/board/.env` 가 `0`. 저장 뒤 앱을 다시 띄울 필요는 없지만, 다음 Deploy 때 자연히 반영됩니다(새 compose는 이미 `MAIL_*`를 안 읽습니다).
3. **[브라우저]** **Gmail 앱 비밀번호 폐기**: 발송 전용 Gmail 계정 -> Google 계정 -> 보안 -> "앱 비밀번호"에서 `board`(만든 이름)를 삭제합니다. 그 Gmail 계정을 더 쓰지 않으면 계정도 정리합니다.
4. **[서버]** (선택) 이제 필요 없는 `~/board/docker-compose.yml.bak-s20`(S18 compose, 비밀 값 없음)을 지우거나 보관합니다. 옛 compose 백업(`.bak-s18` 등)이 쌓였다면 같이 정리합니다.
5. 문서 정리(저장소): 위 "메일 발송 (S10)" 절 중 메일 설정·"학교 인증 코드가 막혔다는 문의"·"학교 메일 없이 운영에서 인증 테스트하기"와 "S10 이미지로 돌아갈 때" 메모의 메일 관련 내용을 지웁니다(S18 되돌리기가 필요 없어진 뒤).

### S21 배포 (유학생 인증 보강: 인증 취소·취소 번호 자동 승인 막기·Vision 하루 상한)
S20 다음 운영 배포입니다. 위 S20 절 중 "처음엔 off"·"가장 위가 `14 | t`"처럼 S20 배포 당시를 가정한 문장은 S21 이후 사실과 다릅니다(운영은 이미 `PERMIT_OCR_MODE=vision`이고 Flyway 최신은 15). 그 부분은 이 절을 기준으로 봅니다.

메모:
- **`<퍼블릭IP>` 칸**: 실제로 진행할 때 메인 대화(Claude)가 사용자에게 IP를 받아 완성된 명령으로 바꿔 줍니다. 아래 명령은 PowerShell 모양입니다(`ssh`·`scp`는 전체 경로, 키는 `"$HOME\.ssh\board-ec2"`, 서버 사용자 `ec2-user`).
- **왜 Deploy 버튼만으로 안 되나**: compose가 바뀌었습니다(app에 `VISION_GLOBAL_PER_DAY` 한 줄 추가). Deploy 버튼은 서버의 compose를 바꾸지 않으므로 먼저 새 compose를 서버로 복사해야 합니다. 빼먹으면 앱 기본값(30)이 그대로 쓰여 지금은 동작이 같지만, 나중에 `.env`로 값을 바꿔도 전달되지 않습니다.
- **V15**(`permit_requests` 상태 CHECK에 `REVOKED` 추가)가 앱이 켜질 때 Flyway로 적용됩니다. 기존 행은 모두 새 CHECK를 통과합니다. Deploy가 직전에 DB를 자동 백업합니다.
- **서버 `.env`는 안 고쳐도 됩니다.** `VISION_GLOBAL_PER_DAY`를 안 적으면 compose 기본값 30이 들어갑니다. 값은 하루 30건(30 x 31일 = 930건, 월 무료 1,000건 안)이고 `0`은 상한 끔, 음수는 앱 시작 실패입니다.
- 상한을 넘으면 구글을 부르지 않고 그 신청은 "못 읽음"으로 관리자 대기에 남습니다(신청 자체는 접수됨). 카운터는 Redis 키 `permit-vision-day:{서울 날짜}`(2일 뒤 자동 삭제)이고, Redis를 재시작하면 0으로 돌아갑니다.

**1) 서버 준비 (Deploy 버튼 전)**
1. **[PC]** 배포할 커밋을 push한 뒤(push는 직접) GitHub Actions 탭에서 그 커밋의 CI 세 작업(backend, flutter, images)이 모두 초록인지 봅니다. 배포할 커밋이 main의 마지막 커밋인지도 확인합니다. `git rev-parse HEAD` 로 SHA를 적어 둡니다.
2. **[콘솔]** EC2 인스턴스를 켜고(시작) 퍼블릭 IP를 확인합니다.
3. **[PC, PowerShell]** 서버의 현재 compose를 백업합니다(S20 compose, 되돌리기용). 같은 이름이 있으면 덮어쓰지 않고 멈춥니다:
   - `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<퍼블릭IP> 'test ! -e ~/board/docker-compose.yml.bak-s21 && cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-s21 && ls -l ~/board/docker-compose.yml.bak-s21'`
4. **[PC, PowerShell, 저장소 루트에서]** 새 compose를 서버로 복사합니다:
   - `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
5. **[PC, PowerShell]** 서버 compose에 새 줄이 들어갔는지 봅니다: `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<퍼블릭IP> "grep -c VISION_GLOBAL_PER_DAY ~/board/docker-compose.yml"` 가 `1` 이상(주석 줄까지 세어져 2일 수 있음)인지 봅니다.
6. **[브라우저]** Actions 탭 -> Deploy -> Run workflow (위 "자동 배포"와 같음). DB 백업·헬스체크가 모두 초록인지 봅니다.

**2) 배포 뒤 확인** (앞에서부터 순서대로)
1. **[브라우저]** Deploy 실행이 초록이고 `http://<퍼블릭IP>/actuator/health`가 UP인지 봅니다.
2. **[서버]** 시작 실패가 없는지: `cd ~/board && docker compose --profile prod logs app | grep -c -E 'IllegalStateException|APPLICATION FAILED|Application run failed'` 가 `0`.
3. **[서버]** 표 변경: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"'` 에서 가장 위가 `15 | t`.
4. **[서버]** 새 값이 앱에 들어갔는지: `docker exec board-app printenv VISION_GLOBAL_PER_DAY` 가 `30`. (비밀 값이 아니라 그대로 출력해도 됩니다. 키는 `printenv VISION_API_KEY`로 확인하지 않습니다.)
5. **[서버]** 메모리와 재시작: `docker stats --no-stream board-app` 의 메모리(직전 약 392MiB/512MiB, **400MiB를 넘으면 지켜보기**, 512MiB에 가까우면 알려 주세요). `docker ps` 에서 `board-app`이 `Restarting`이 아니고 `Up` 시간이 늘어나는지 1분쯤 뒤 한 번 더 봅니다.
6. **[브라우저]** 화면 확인(**실제로 [인증 취소]를 누르지는 않습니다**): 관리자 계정으로 로그인 -> "인증 심사"에 **"취소" 탭**이 보이는지, "승인" 탭의 남의 줄에 **[인증 취소]** 버튼이 보이는지. 운영 테스트 계정 `wntjrdka`는 SQL로 인증된 계정이라 신청 줄이 없을 수 있으니, 승인 줄이 하나도 없으면 버튼은 못 봐도 정상입니다(탭만 봅니다). 이어서 회원 계정으로 `/me`가 정상으로 열리고 글 목록·새로고침해도 로그인 유지가 되는지 봅니다.
7. **[서버]** 로그에 개인정보·키가 없는지(0이어야 정상): `docker compose --profile prod logs app | grep -c 'permits/'` 와 `docker compose --profile prod logs app | grep -c 'x-goog-api-key\|AIza\|AQ\.'`.

**3) Vision 하루 상한 확인·풀기** (**[서버]**)
- 상한에 닿았는지: `docker logs board-app 2>&1 | grep '하루 전체 상한'` — 처음 넘을 때 한 줄 `Vision 하루 전체 상한(N건)에 도달`이 남습니다(그날 한 번만, 건수 외에 개인정보는 없음). 이 줄이 있으면 오늘 신청은 전부 관리자 대기입니다(정상 동작).
- 오늘 카운터 보기(비밀번호는 컨테이너에 이미 `REDISCLI_AUTH`로 들어 있어 따로 안 침): `docker exec board-redis redis-cli GET "permit-vision-day:$(TZ=Asia/Seoul date +%F)"` — 숫자가 오늘 판독 시도 건수입니다(없으면 `nil` = 오늘 아직 0건).
- **급히 오늘만 풀기**: `docker exec board-redis redis-cli DEL "permit-vision-day:$(TZ=Asia/Seoul date +%F)"` — 카운터를 0으로 되돌려 오늘 30건을 더 쓸 수 있게 됩니다. 월 무료 1,000건을 넘을 수 있으니 자주 하지 않습니다.
- **값 자체를 올리기**: `nano ~/board/.env` 에 `VISION_GLOBAL_PER_DAY=숫자` 줄을 추가하거나 고칩니다(빈 값 금지, 음수 금지, `0`은 상한 끔). 저장 전에 키 줄을 건드리지 않았는지 확인하고, **`up -d app` 전에 반드시** `PERMIT_HASH_SECRET` 확인을 합니다: `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1`(파일 값 = 실행 중인 앱 값, 값은 화면에 안 나옴. 이 명령은 아직 안 재시작한 앱이 떠 있을 때만 쓸 수 있습니다). 이어서 `grep -c '^VISION_GLOBAL_PER_DAY=' ~/board/.env` 가 `1`이면 `cd ~/board && docker compose --profile prod up -d app`, 뒤에 `docker exec board-app printenv VISION_GLOBAL_PER_DAY` 로 새 값을 확인합니다.

**S21 되돌리기** (문제가 있을 때만)
- 되돌릴 이미지(직전 운영 = S20 배포 때의 이미지, 커밋 2375394): `seokam36/demo:2375394a8e1aca7e4464abea4702b554937e6b11`, `seokam36/demo-web:2375394a8e1aca7e4464abea4702b554937e6b11`. 그 뒤 운영에 다른 배포가 있었다면 그 직전 운영 배포 커밋의 40자리 SHA로 바꿉니다(찾는 법은 위 "(a) 이전 이미지로 되돌리기" 1번: GitHub Commits 목록에서 복사, 그 커밋 CI images가 초록이었어야 태그가 있음).
- **V15는 그대로 남습니다.** 옛 코드는 `validate`라서 CHECK 제약이 바뀐 것은 무관하고 앱은 뜹니다. **그러나 `REVOKED` 행이 DB에 있으면 옛 코드의 상태 목록에 그 값이 없어, 그 회원의 인증 상태 조회와 관리자 인증 심사 목록이 500이 됩니다.** 그래서 이미지를 되돌리기 전에 먼저 셉니다(**[서버]**):
  1. `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT status, count(*) FROM permit_requests GROUP BY status ORDER BY status;"'` — `REVOKED` 줄이 없으면 아래 2번은 건너뜁니다(사용자가 [인증 취소]를 한 번도 안 눌렀다면 없습니다).
  2. 있으면 **되돌린 뒤 다시 S21로 올 때 복구할 수 있게 id를 적어 둡니다**: 서버 셸에서
     ```sh
     docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
     SELECT id FROM permit_requests WHERE status = 'REVOKED' ORDER BY id;
     SQL
     ```
  3. 그 행들을 `REJECTED`로 바꿉니다(취소 사유 칸은 그대로라 반려 사유로 보임):
     ```sh
     docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
     UPDATE permit_requests SET status = 'REJECTED' WHERE status = 'REVOKED';
     SQL
     ```
     `UPDATE 건수`가 1번의 `REVOKED` 건수와 같아야 합니다.
  - **대가**: 옛 코드는 "취소된 번호는 자동 승인하지 않는다"를 모릅니다. 취소된 허가서 번호를 다시 올리면 **자동 승인될 수 있고**, 이 행들은 "취소"가 아니라 "반려"로 보입니다. 이미 인증이 비워진 회원은 그대로 인증 해제 상태입니다. 다시 S21로 올라온 뒤 2번의 id만 `UPDATE permit_requests SET status = 'REVOKED' WHERE id IN (...)` 로 되돌릴 수 있습니다(그 사이 새 승인·반려가 섞였다면 id로만 고릅니다).
- 이미지·compose 되돌리는 순서 (**[서버]**): ① 위 REVOKED 처리 ② `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-s21-new` (지금 S21 compose 보관, 이미 있으면 다른 이름) ③ `cp docker-compose.yml.bak-s21 docker-compose.yml` (S20 compose로 복귀) ④ `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:2375394a8e1aca7e4464abea4702b554937e6b11#; s#image: seokam36/demo-web:latest#image: seokam36/demo-web:2375394a8e1aca7e4464abea4702b554937e6b11#' docker-compose.yml` ⑤ `grep 'image: seokam36' docker-compose.yml` 두 줄 모두 `2375394...` ⑥ `docker compose --profile prod pull app web && docker compose --profile prod up -d app web` ⑦ 로그에 시작 실패가 없고(`newer than the latest available migration (14) ... (15)` 경고는 정상) `/actuator/health`가 UP, 글 목록이 뜨는지 봅니다. `.env`의 `VISION_GLOBAL_PER_DAY`가 있어도 옛 코드는 무시합니다.
- **주의**: 위 "(a)"의 주의 박스와 같습니다. image 줄을 SHA로 고정해 두면 **다음 Deploy도 옛 이미지를 받습니다.** 고친 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-s21-new docker-compose.yml` 로 원복하고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다.
- **하지 말 것**: V15를 손으로 되돌리거나(CHECK 다시 만들기) `flyway_schema_history`의 15번 행을 지우지 않습니다(`REVOKED` 행이 있으면 CHECK를 옛 모양으로 되돌리는 것 자체가 실패하고, 새 코드로 돌아올 때 "이미 있음" 오류가 납니다).

**S21 배포 끝난 뒤 정리**: 며칠 써 보고 되돌릴 일이 없다고 판단하면 **[서버]** `~/board/docker-compose.yml.bak-s21`(비밀 값 없음)을 지우거나 보관합니다.

## S22 HTTPS 전환 (도메인 + Let's Encrypt, 켤 때 DNS 자동 고침)
> **2026-11-19 운영 전환 끝** (이미지 커밋 bd6c70b). 실제로 해 본 결과: DDNS 첫 실행(실제 Cloudflare) 성공, 인증서 standalone 첫 발급 → webroot `renew --dry-run` 통과(authenticator 수정 필요 없었음), 서버에 cron(cronie)이 없어 설치함, crontab 세 줄 등록, 저장소 Variables `SITE_DOMAIN` 등록. 이메일은 등록하지 않고 `--register-unsafely-without-email`로 발급했습니다(만료 알림 메일이 없어져 받을 메일이 없음). 아래 순서는 서버를 새로 만들 때 다시 씁니다.
목표: `https://<사이트주소>` 로 접속. 서버(Spring)·DB 변경은 없고, nginx(web)와 compose만 바뀝니다. 실제 도메인 이름은 이 공개 파일에 적지 않고 `<도메인>`, `<사이트주소>`(게시판 서브도메인 전체, 예: `kampus.<도메인>`) 자리표시를 씁니다.
- 고정 IP(Elastic IP)는 쓰지 않습니다. EC2는 쓸 때만 켜서 켤 때마다 공인 IP가 바뀌므로, 켜질 때 서버가 스스로 Cloudflare DNS를 새 IP로 고칩니다(아래 "S22 DNS 자동 고침").
- 80번은 남깁니다: 인증서 확인(Let's Encrypt), Deploy 헬스체크(`http://<IP>/actuator/health`, 리다이렉트 없이 답함), 나머지는 https로 301 이동.
- 인증서는 서버의 `~/board/certbot/conf`(인증서)·`~/board/certbot/www`(확인용 파일) 폴더에 저장되고, web 컨테이너가 읽기 전용으로 봅니다. 인증서 이름은 `board`로 고정(`--cert-name board`)이라 nginx 설정에 도메인이 없습니다.
- HSTS(브라우저에게 "앞으로 https만"이라고 기억시키는 헤더)는 일부러 넣지 않았습니다. 되돌릴 때 브라우저가 http를 못 쓰게 되는 걸 피하기 위해서입니다.

### 준비물 (사용자가 직접, AWS·Cloudflare 화면에서) — 2026-11-19 모두 끝남
1. **도메인 구매**: Cloudflare Registrar에서 구매 완료, **자동 갱신** 켬(만료되면 사이트가 사라집니다).
2. **Elastic IP를 안 쓰는 이유·비용**: 공개 IPv4 주소는 인스턴스를 꺼 두어도 계속 요금이 나옵니다(시간당 약 $0.005, 월 약 $3.65). 포트폴리오용이라 꺼 둔 동안의 요금이 아까워서 쓰지 않고, 대신 켤 때 DNS를 자동으로 고칩니다. 단점: 켠 뒤 1~5분은 새 IP가 퍼지는 동안 접속이 안 될 수 있습니다.
3. **DNS A 레코드**: Cloudflare DNS에 `<사이트주소>`(게시판 서브도메인) A 레코드를 만들어 둠. **프록시 끔(회색 구름)**, **TTL 1분**, 처음 값은 아무 IP(`192.0.2.1`)여도 됩니다(스크립트가 켤 때 진짜 IP로 고침). 프록시를 끄는 이유: 인증서 발급(80 직접 확인)·헬스체크가 서버로 곧장 오게 하려는 것입니다.
4. **Cloudflare API 토큰**: 그 도메인의 **DNS 편집 권한만**(Zone / DNS / Edit, 해당 영역 하나만), 만료 없음. 비밀 값입니다(서버 `.env`에만 둠, 채팅·문서·깃에 쓰지 않음).
5. **영역 ID(Zone ID)**: 32글자. Cloudflare 도메인 개요 화면 오른쪽 아래 API 칸에 있습니다. 비밀은 아닙니다.
6. **보안 그룹**: 인바운드에 **443(HTTPS)** 추가(소스 `0.0.0.0/0`). 80은 그대로 둡니다.

### S22 DNS 자동 고침 (켤 때 IP → Cloudflare)
서버가 켜지면 `~/board/cloudflare-ddns.sh`(저장소 `deploy/cloudflare-ddns.sh`)가 EC2 메타데이터(IMDSv2)로 **자기 공인 IP**를 알아내 Cloudflare API로 A 레코드를 새 IP로 고칩니다. 이미 같은 IP면 "그대로"라고 로그만 남기고 끝납니다. 설정(`.env`의 세 줄)에서 토큰은 화면·로그에 찍지 않습니다. IMDS 홉 제한 2·IMDSv2 필수는 이미 설정돼 있습니다.

**설치 (처음 한 번)**
1. **[PC, 저장소 루트에서]** 스크립트 복사: `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" deploy\cloudflare-ddns.sh ec2-user@<퍼블릭IP>:~/board/cloudflare-ddns.sh` (`<퍼블릭IP>`는 지금 콘솔에서 본 값, 꺾쇠 없이 숫자만)
2. **[서버]** 실행 권한(나만 읽고 실행)과 줄 끝 확인: `chmod 700 ~/board/cloudflare-ddns.sh && bash -n ~/board/cloudflare-ddns.sh && head -1 ~/board/cloudflare-ddns.sh | od -c | head -1`
   - 윈도우에서 복사하면 줄 끝에 `\r`이 붙어 스크립트가 안 돌 수 있습니다. `od` 출력 끝에 `\r`이 **없어야** 합니다(`\n`으로 끝남). 있으면 `sed -i 's/\r$//' ~/board/cloudflare-ddns.sh` 로 지우고 다시 확인합니다. `bash -n`은 문법만 검사하고 아무것도 보내지 않습니다.
3. **[서버]** `.env`에 세 줄을 넣습니다. **토큰은 만료가 없고 그 영역의 DNS 전체를 고칠 수 있는 비밀입니다. S18 절 4번과 같은 주의**로, `cat .env`나 `echo ... >> .env`는 쓰지 않고 편집기로 고칩니다.
   - `nano ~/board/.env` 를 엽니다. **nano 화면이 뜬 것을 확인한 뒤에** 붙여 넣습니다. 셸 프롬프트에 붙여 넣었다면 토큰이 화면과 `~/.bash_history`에 남으니 Cloudflare에서 그 토큰을 **Roll(교체)** 합니다.
   - 맨 아래에 세 줄을 적습니다(이미 같은 이름의 줄이 있으면 그 줄을 고칩니다):
     ```
     CF_API_TOKEN=<토큰>
     CF_ZONE_ID=<영역 ID 32글자>
     CF_RECORD_NAME=<사이트주소 전체>
     ```
   - **`<토큰>` 등 자리 글자를 꺾쇠 `<` `>` 까지 전부 지우고** 진짜 값을 붙여 넣습니다. `=` 앞뒤에 띄어쓰기·따옴표를 넣지 않고 줄바꿈이 끼어도 안 됩니다.
   - 저장하고 닫습니다(`Ctrl+O` 엔터 -> `Ctrl+X`). nano가 비정상으로 끝났다면 `ls -la ~/board`에 `.env.save`가 남았는지 보고 있으면 열어 보지 말고 `rm`으로 지웁니다(토큰이 들어 있을 수 있음).
4. **[서버]** 확인(다른 줄을 망가뜨리지 않았는지, 값은 화면에 안 나옵니다. **값을 보려고 `cat`하지 않습니다**):
   - `grep -c '^CF_API_TOKEN=<' ~/board/.env` 가 `0` (자리 글자가 남지 않음)
   - `grep -c '^CF_' ~/board/.env` 가 `3`
   - `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1`
   - `ls -l ~/board/.env` 가 `-rw-------`

**첫 실행 확인**
- **이 실행이 실제 Cloudflare API와의 첫 확인입니다.** 스크립트 시험은 가짜 서버로만 했기 때문에, 진짜 응답 모양·토큰 권한은 여기서 처음 맞아 봅니다. 실패하면 아래 "실패 대처" 표를 봅니다.
- **[서버]** `~/board/cloudflare-ddns.sh; echo "종료코드=$?"` → `고침: ... -> <IP>`(처음엔 `192.0.2.1`에서 바뀜) 또는 `그대로: ...` 가 나오고 `종료코드=0`.
- **[PC]** `nslookup <사이트주소> 1.1.1.1` 이 EC2 퍼블릭 IP(콘솔 값)와 같은지 봅니다. 아니면 1~2분 뒤 다시 봅니다.

**켤 때 자동 실행 (서버 crontab)**
`crontab -e` 에 아래 줄을 넣습니다(cron이 없으면 아래 "자동 갱신"의 `cronie` 설치 안내를 먼저 따릅니다):
```
# 부팅 20초 뒤 한 번: 새 공인 IP를 Cloudflare DNS에 적기 (네트워크가 늦으면 스크립트가 다시 시도)
@reboot sleep 20 && "$HOME/board/cloudflare-ddns.sh" >> "$HOME/board/ddns.log" 2>&1
```
- **순서가 중요합니다.** 이 줄이 "자동 갱신"의 certbot `@reboot` 줄(`sleep 600`)보다 **먼저** 끝나야 합니다. certbot 갱신은 Let's Encrypt가 `<사이트주소>`로 서버를 찾아오는데, DNS가 옛 IP를 가리키면 확인이 엉뚱한 곳으로 가서 실패하기 때문입니다.
- **최악의 시간**: 20초 대기 + 공인 IP 받기 최대 약 192초 + Cloudflare 조회·수정 각 최대 약 75초(15초 x 4번 + 5초 x 3번) = 약 6분. 그래서 certbot 줄은 600초(10분)로 둡니다(`crontab -l`에서도 이 줄을 위에 둡니다).
- `>> ddns.log 2>&1`: 결과(성공·실패 이유)를 `~/board/ddns.log`에 쌓습니다. 토큰은 로그에 안 찍힙니다.

**로그 확인과 실패 대처** (실패 대처 표): `tail ~/board/ddns.log`
| 로그 메시지 | 원인·대처 |
| --- | --- |
| `실패: .env에 ... 빈 값` / `CF_ZONE_ID·CF_RECORD_NAME 형식이 아님` | `.env` 세 줄 확인. 영역 ID는 32글자 16진수(복사 오타), 이름은 영문·숫자·점·하이픈만 |
| `실패: CF_API_TOKEN 형식이 아님` | 토큰에 꺾쇠 `<>`·따옴표·공백이 섞임(영문·숫자·`_`·`-`만 가능). `nano`로 그 줄을 고침. 값은 화면에 안 나옵니다 |
| `실패: ... Cloudflare에 연결 못 함` | 3번 다시 시도한 뒤에도 바깥 인터넷·DNS가 안 됨. 보안 그룹 아웃바운드·인스턴스 네트워크 확인 뒤 스크립트를 손으로 다시 실행 |
| `실패: 찾은 레코드 이름이 ... 이 아님` | 검색 조건이 어긋나 다른 레코드가 왔음. **다른 레코드를 덮어쓰지 않으려고 고치지 않고 멈춘 것**입니다. `CF_RECORD_NAME` 오타·대소문자 확인 |
| `실패: ... A 레코드가 1개가 아님` | 같은 이름의 A 레코드가 2개 이상. Cloudflare 콘솔에서 하나만 남김 |
| `실패: 레코드 조회 실패 (오류 코드: ...)` | 토큰이 틀렸거나 만료·삭제됨, 또는 권한(DNS 편집)·대상 영역이 다름, 또는 영역 ID 오타. Cloudflare에서 토큰 권한 범위와 영역 ID를 다시 확인 |
| `실패: Cloudflare에 ... A 레코드가 없음` | `CF_RECORD_NAME` 오타이거나 레코드가 없음. Cloudflare DNS에서 A 레코드(프록시 끔)를 만들고 이름을 맞춤 |
| `실패: 레코드 수정 실패` | 토큰에 "편집" 권한이 없음(읽기만 있음). 권한을 DNS Edit로 |
| `실패: EC2 메타데이터에서 공인 IP를 못 받음` | 약 2분(10초 간격 12번) 동안 IMDS에 못 닿음. 홉 제한 2·IMDSv2 필수 설정 확인(이미 해 둠), 인스턴스에 공인 IP가 붙어 있는지 확인. 급하면 콘솔 IP를 보고 `~/board/cloudflare-ddns.sh`를 손으로 다시 실행 |

**켤 때 사용자 흐름**
1. AWS 콘솔에서 인스턴스를 시작합니다.
2. **1~5분 기다립니다**(부팅 -> 스크립트가 DNS를 고침 -> 새 IP가 퍼짐. TTL이 1분이라 보통 빨리 퍼집니다. 네트워크가 늦으면 스크립트가 최대 약 6분까지 다시 시도하므로 그때는 더 걸립니다).
3. `https://<사이트주소>` 로 접속합니다. 안 열리면 조금 더 기다린 뒤 `nslookup <사이트주소> 1.1.1.1` 이 콘솔의 퍼블릭 IP와 같은지 봅니다.
- SSH도 이제 `ec2-user@<사이트주소>` 로 가능합니다. 단 DNS가 갱신되기 전에는 옛 IP라 연결이 실패할 수 있으니, 그땐 콘솔의 퍼블릭 IP로 접속합니다. 이름으로 처음 접속하면 ssh의 `known_hosts`에 "이름" 기준 항목이 하나 더 생기는 정도(처음 확인 질문에 yes)입니다.

**토큰이 새어 나갔을 때**: Cloudflare -> 내 프로필 -> API 토큰에서 그 토큰을 **삭제하거나 Roll(새 값으로 교체)** 하고, 새 토큰을 서버 `.env`의 `CF_API_TOKEN=` 에 `nano`로 교체합니다. 이어서 위 "설치" 3번(nano 주의)·4번 확인과 "첫 실행 확인"을 다시 합니다.

### 전환 순서 (한 번에, 순서대로)
**443 열기 + 인증서 + `COOKIE_SECURE=true`는 같은 배포에서 함께 합니다.** 따로 하면 로그인이 깨집니다(Secure 쿠키는 http에서 전달되지 않고, 반대로 https인데 Secure가 꺼져 있으면 쿠키가 약합니다). 전환 직후 기존 로그인은 한 번 풀립니다(다시 로그인).

아래 명령의 `<퍼블릭IP>`는 꺾쇠까지 지우고 숫자만 넣습니다. PowerShell 기준(Git Bash는 `ssh -i ~/.ssh/board-ec2 ...`로 그대로): ssh는 `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<퍼블릭IP>`, scp는 `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" ...`.

1. **[브라우저]** 이번 커밋의 CI 세 작업(backend, flutter, images)이 초록인지 Actions 탭에서 봅니다(images가 초록이어야 새 web 이미지가 있습니다). **Deploy는 아직 누르지 않습니다.**
2. **[PC]** 서버 compose 백업. 이미 `.bak-s22`가 있으면 **멈춥니다**(덮어쓰면 되돌릴 원본을 잃습니다. 있는 이유부터 확인):
   `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@<퍼블릭IP> 'test ! -e ~/board/docker-compose.yml.bak-s22 && cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-s22 && echo 백업됨'`
3. **[PC, 저장소 루트에서]** 새 compose를 서버로 복사: `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
   (복사만으로는 실행 중인 컨테이너가 바뀌지 않습니다. 옛 web은 계속 80에서 동작합니다.)
4. **[서버]** 인증서 폴더를 미리 만들고 certbot 이미지를 **미리** 받아 둡니다(사이트를 끄기 전에 받아야 꺼진 시간이 짧습니다): `mkdir -p ~/board/certbot/conf ~/board/certbot/www && cd ~/board && docker compose --profile certbot pull certbot`
5. **[서버]** `docker stop board-web` 전에 `.env`부터 고칩니다(사이트가 꺼진 시간을 줄이기 위함). `.env`에 `COOKIE_SECURE=true`와 DNS 자동 고침용 `CF_*` 세 줄을 **한 번의 nano로 함께** 넣습니다(`CF_*` 줄 적는 법·꺾쇠 지우기·토큰 주의는 위 "S22 DNS 자동 고침" 설치 3번과 같음. 토큰을 셸에 붙였다면 Roll). `.env`는 컨테이너를 새로 만들 때만 읽히므로 미리 고쳐도 지금 떠 있는 앱엔 영향이 없어 안전합니다. **단, 이 단계부터 Deploy(8번)까지 사이에 app이 저절로 재시작되면(서버 재부팅 등) http인데 Secure 쿠키라 로그인이 깨집니다.** 그 경우 8번을 서둘러 끝내거나 `COOKIE_SECURE`를 `false`로 되돌립니다. `cat .env`는 쓰지 않고 `nano ~/board/.env`로 고칩니다(이미 `COOKIE_SECURE` 줄이 있으면 그 줄을 `true`로, 없으면 맨 아래에 추가). 저장 후 **`.env`를 고친 뒤에는 반드시 확인**합니다(nano에서 다른 줄을 망가뜨리지 않았는지):
   - `grep -c '^COOKIE_SECURE=true$' ~/board/.env` 가 `1`
   - `grep -c '^CF_API_TOKEN=<' ~/board/.env` 가 `0`, `grep -c '^CF_' ~/board/.env` 가 `3` (값은 `cat` 금지, `.env.save`가 생기지 않았는지도 `ls -la ~/board`로 확인)
   - `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1` (파일 값 = 지금 실행 중인 앱의 값. 이 값이 바뀌면 허가서 번호 해시가 전부 달라집니다. 0이면 저장하지 말고 원래 값으로 되살립니다.)
   - `ls -l ~/board/.env` 가 `-rw-------`
6. **[PC·서버] DNS 자동 고침 설치 + 첫 실행 + `nslookup` 확인** (위 "S22 DNS 자동 고침"의 설치 1~2번[복사·권한·줄 끝 확인], 첫 실행 확인, crontab 줄. 설치 3~4번은 5번에서 이미 했습니다). 첫 실행 확인은 **실제 Cloudflare와의 첫 확인**이라 실패하면 그 절의 실패 대처 표를 봅니다. **인증서 첫 발급 전에** DNS가 지금 EC2의 IP를 가리켜야 하기 때문입니다(인증서 발급 때 Let's Encrypt가 `<사이트주소>`로 이 서버를 찾아옵니다). 지금 IP(`<퍼블릭IP>`)는 AWS 콘솔에서 봅니다. **`nslookup <사이트주소> 1.1.1.1` 이 이 IP를 돌려주지 않으면 7번으로 가지 않습니다**(DNS가 퍼지기 전에 시도하면 실패하고, 실패가 쌓이면 제한이 걸립니다). 이 단계는 사이트를 끄지 않습니다.
7. **[서버] 인증서 첫 발급.** 문제: 지금 떠 있는 옛 nginx(80만, 옛 이미지)는 `/.well-known/`(인증서 확인 길)을 모르고, 새 이미지는 인증서가 있어야 뜹니다. 닭-달걀 문제입니다. 선택지 비교:
   | 방법 | 장점 | 단점 |
   | --- | --- | --- |
   | A. `certonly --standalone` (옛 web을 잠깐 멈추고 certbot이 80을 직접 씀) | 명령 하나, 설정 변경 없음, 가장 단순 | 몇 분 사이트가 꺼짐(학습용 사이트라 괜찮음) |
   | B. 임시 nginx를 80에 띄워 webroot로 발급 | 다운 시간이 거의 같음(어차피 80이 필요해서 옛 web을 멈춰야 함) | 임시 설정이 더 필요해서 실수 여지만 늘어남 |
   | C. 먼저 Deploy 후 webroot 발급 | 없음 | 인증서가 없어 새 nginx가 시작 실패 -> 불가능 |
   | D. DNS 방식(TXT 레코드를 손으로) | 80 필요 없음 | 갱신을 자동화할 수 없어서 90일마다 손으로 해야 함 |

   **추천: A.** (B는 80을 쓰는 건 같아서 이득이 없고 D는 자동 갱신이 안 됩니다.) 순서:
   1. `cd ~/board && docker stop board-web` — 80을 비웁니다(여기서부터 사이트 꺼짐. `restart: unless-stopped`라도 손으로 멈춘 건 자동으로 안 켜집니다). **발급을 중간에 포기하면 `docker start board-web`으로 옛 사이트를 되살립니다.**
   2. **연습(--dry-run)**: Let's Encrypt의 시험용 서버로 해 보는 것이라 진짜 인증서도 안 남고 발급 제한도 거의 안 셉니다.
      `docker compose run --rm -p 80:80 certbot certonly --standalone --dry-run -d <사이트주소> --cert-name board --register-unsafely-without-email --agree-tos`
      `The dry run was successful.` 가 나와야 합니다. 실패하면 **진짜 발급으로 넘어가지 않고** 원인(DNS·80 열림·보안 그룹)부터 고칩니다.
      (`--staging`으로 진짜 저장하는 연습은 하지 않습니다: 시험용 가짜 인증서가 `board` 이름으로 남아서 진짜 발급 때 꼬입니다.)
   3. **진짜 발급** (한 번만): 같은 명령에서 `--dry-run`만 뺍니다.
      `docker compose run --rm -p 80:80 certbot certonly --standalone -d <사이트주소> --cert-name board --register-unsafely-without-email --agree-tos`
      - `-p 80:80`: certbot 컨테이너가 80을 직접 열어 Let's Encrypt의 확인을 받습니다. `--cert-name board`: 인증서 이름 고정. `--register-unsafely-without-email`: 계정에 이메일을 등록하지 않습니다(이메일은 연락처일 뿐이고, Let's Encrypt는 2025년에 만료 알림 메일을 끝냈습니다. 등록하고 싶으면 이 옵션 대신 `--email <메일> --no-eff-email`). 만료 확인은 `docker compose run -T --rm certbot certificates`와 `~/board/certbot/renew.log`로 합니다.
   4. 확인: `sudo ls ~/board/certbot/conf/live/board/` 에 `fullchain.pem`, `privkey.pem`이 있어야 합니다. **`sudo` 없이 `ls`하면 폴더가 root 전용(700)이라 `Permission denied`가 나옵니다. 이건 실패가 아닙니다.** 오해해서 다시 발급하면 발급 제한이 걸립니다. sudo가 싫으면 `docker compose run -T --rm certbot certificates`로 확인해도 됩니다.
   - 이 시점에 사이트는 아직 꺼져 있습니다. 바로 8번으로 갑니다.
8. **[브라우저]** Actions 탭 -> Deploy -> Run workflow. 새 web 이미지가 인증서를 읽어 443을 열고, app이 `COOKIE_SECURE=true`로 새로 만들어집니다. (자동 DB 백업 -> 배포 -> http 헬스체크가 초록이어야 합니다.)
9. **[브라우저]** GitHub 저장소 Settings -> Secrets and variables -> Actions -> **Variables** 탭에 `SITE_DOMAIN` = `<사이트주소>` 를 추가합니다. 이때부터 Deploy가 `https://$SITE_DOMAIN/actuator/health`도 같이 확인합니다(없으면 그 단계는 건너뜀). 도메인은 비밀이 아니라서 Secrets가 아닌 Variables입니다. 이건 **자동 갱신 설정(아래)과 확인 목록을 끝낸 뒤**에 해도 됩니다.

### 확인 목록 (배포 뒤)
1. `https://<사이트주소>/actuator/health` 가 200(`UP`)이고 브라우저 주소창에 **인증서 경고가 없음**.
2. `http://<사이트주소>/` 가 `https://` 로 **301 이동**: PC에서 `curl.exe -sI http://<사이트주소>/` 의 첫 줄 `301`, `Location: https://...`. 반면 `http://<퍼블릭IP>/actuator/health` 는 이동 없이 200(Deploy 헬스체크용).
3. 브라우저에서 **로그인**(전환 직후 다시 로그인) -> 새로고침해도 유지. 개발자도구 -> Application -> Cookies에서 `access_token`·`refresh_token`에 **Secure** 체크. **403 + `Invalid CORS request`가 나오면** 서버가 https를 http로 보고 있는 것입니다. compose app의 `SERVER_FORWARD_HEADERS_STRATEGY`를 확인합니다: `docker exec board-app printenv SERVER_FORWARD_HEADERS_STRATEGY` 가 `native`여야 합니다.
4. **글 작성**이 됨(CSRF 포함, 403이 아님). 403 + `Invalid CORS request`면 위 3번의 `SERVER_FORWARD_HEADERS_STRATEGY`(`native`) 확인.
5. **1MB 넘는 사진** 올리기가 됨(본문 한도 규칙이 443에도 그대로 적용됐는지).
6. Actions Deploy 헬스체크가 초록.
7. **[서버]** `docker stats --no-stream board-web` 의 메모리가 `64MiB` 미만(443·TLS 추가로 늘었는지).
8. **[서버]** `cd ~/board && docker compose run -T --rm certbot renew --dry-run --webroot -w /var/www/certbot; echo "종료코드=$?"` 가 `종료코드=0`(`all simulated renewals succeeded`). 첫 발급은 standalone 방식이었는데 갱신은 webroot로 하므로, **이 연습이 통과해야 자동 갱신이 믿을 만합니다.** 실패하면 갱신 설정 파일(`~/board/certbot/conf/renewal/board.conf`, root 전용이라 `sudo cat`·`sudo nano`로 열어야 합니다)의 `authenticator`가 `standalone`인 것이 원인일 수 있습니다(`webroot`로 고쳐야 할 수 있음, 아직 실제로 겪어 본 적 없음).
9. `docker ps` 에서 네 컨테이너가 `Up`(`Restarting` 아님), `docker logs --tail 50 board-web` 에 `emerg`가 없음.

### 자동 갱신 (서버 crontab)
Let's Encrypt 인증서는 **90일**짜리입니다. certbot은 만료 30일 전부터만 실제로 갱신하므로 매일 돌려도 안전합니다. 갱신하면 nginx가 새 인증서를 읽도록 `reload`(연결을 끊지 않고 설정 다시 읽기)를 합니다.
- **cron이 없을 수 있습니다**: Amazon Linux 2023에는 cron(`cronie`)이 기본으로 설치돼 있지 않을 수 있습니다(이 서버에서 직접 확인하지 못함). `crontab -l` 이 "command not found"면 설치합니다: `sudo dnf install -y cronie && sudo systemctl enable --now crond`
- `crontab -e` 로 아래 두 줄을 넣습니다:
  ```
  # 부팅 10분 뒤(docker가 컨테이너를 다 올리고, DDNS가 DNS를 먼저 고친 뒤) 한 번: EC2를 오래 꺼 뒀다 켰을 때 만료된 인증서를 바로 갱신
  @reboot sleep 600 && ( cd "$HOME/board" && docker compose run -T --rm certbot renew --webroot -w /var/www/certbot && docker exec board-web nginx -s reload ) >> "$HOME/board/certbot/renew.log" 2>&1
  # 켜져 있는 동안은 매일 04:17(서버 시각, UTC)에 한 번
  17 4 * * * ( cd "$HOME/board" && docker compose run -T --rm certbot renew --webroot -w /var/www/certbot && docker exec board-web nginx -s reload ) >> "$HOME/board/certbot/renew.log" 2>&1
  ```
  - `-T`: cron에는 키보드 화면(TTY)이 없어서 필요합니다(없으면 "not a TTY" 오류).
  - 위 "S22 DNS 자동 고침"의 DDNS `@reboot` 줄(sleep 20, 최악 약 6분)이 이 `@reboot` 줄(sleep 600)보다 먼저 끝나야 합니다(`crontab -l`에서 위에 둠). 600초로 늘린 이유: DNS가 먼저 새 IP로 맞아야 Let's Encrypt의 인증서 확인이 이 서버로 옵니다.
  - EC2를 쓸 때만 켜므로 매일 줄만으로는 부족합니다. **90일 넘게 꺼 두면 켰을 때 이미 만료**돼 있고(브라우저 경고), `@reboot` 줄이 켜진 지 10분 뒤 갱신합니다. 그 10분 동안은 경고가 보일 수 있습니다.
- 확인: `crontab -l`, 며칠 뒤 `tail ~/board/certbot/renew.log`(`not yet due for renewal`이면 정상), 인증서 만료일: `docker compose run -T --rm certbot certificates`.

### 인증서 발급 제한 주의 (Let's Encrypt)
- 같은 이름으로 **실패를 반복하면 제한**이 걸려 한동안(최대 1시간~일주일) 발급이 막힙니다. 진짜 발급은 `--dry-run`이 통과한 뒤에 **한 번만** 합니다. 실패하면 원인을 먼저 고치고 다시 하지, 연달아 재시도하지 않습니다.
- DNS가 퍼지기 전(`nslookup <사이트주소> 1.1.1.1`이 지금 EC2 IP를 안 돌려줌)에는 시도하지 않습니다.
- `--dry-run`은 시험용 서버를 써서 제한이 훨씬 넉넉하지만, 그래도 무한 반복은 피합니다.

### S22 되돌리기 (문제가 있을 때만)
HTTPS를 도입한 뒤 문제가 생겨 옛 방식(http 80)으로 돌아가는 방법입니다. 핵심 네 가지를 **함께** 합니다(하나라도 빠지면 로그인이나 시작이 깨집니다).
- 되돌릴 web 이미지: `seokam36/demo-web:0dc513f93fbefe64e750ffc65a1f16349729b4ff` (S21 배포 때의 web, 443 블록 없음)
1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-s22-new` — 지금 compose 보관(이미 있으면 다른 이름).
2. **[서버]** `cp docker-compose.yml.bak-s22 docker-compose.yml` — 443·인증서 폴더가 없던 옛 compose로 복귀.
3. **[서버]** web 이미지를 SHA로 고정: `sed -i 's#image: seokam36/demo-web:latest#image: seokam36/demo-web:0dc513f93fbefe64e750ffc65a1f16349729b4ff#' docker-compose.yml` 그리고 `grep 'image: seokam36' docker-compose.yml` 로 web 줄이 `0dc513f...` 인지 확인.
4. **[서버]** `.env`의 `COOKIE_SECURE`를 `false`로 되돌리거나 그 줄을 지웁니다(`nano ~/board/.env`). Secure 쿠키가 켜진 채 http로 돌아가면 로그인이 안 됩니다. 이어서 확인(위 5번과 같음): `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1`, `ls -l ~/board/.env` 가 `-rw-------`.
5. **[서버]** `docker compose --profile prod pull web && docker compose --profile prod up -d --remove-orphans` — web은 옛 이미지로, app은 새 `COOKIE_SECURE` 값으로 다시 만들어집니다. 그다음 `http://<퍼블릭IP>/actuator/health` 가 UP이고 로그인이 되는지 봅니다.
6. **[브라우저]** 저장소 Variables에서 `SITE_DOMAIN`을 **지웁니다**(남기면 Deploy의 https 확인 단계가 실패합니다).
7. **[서버]** `crontab -e` 로 위 "자동 갱신"의 두 줄(`@reboot ...`, `17 4 * * * ...`) 앞에 `#`을 붙여 주석 처리합니다. 옛 compose에는 certbot 서비스가 없어서 그대로 두면 매일 오류가 납니다. 나중에 다시 https로 전환할 때는 `#`을 지워 되살립니다.
8. **[서버]** DDNS `@reboot` 줄(`cloudflare-ddns.sh`)은 **그대로 둬도 무해**합니다(DNS 레코드만 고치고 사이트·컨테이너엔 영향이 없음). 켤 때 DNS가 새 IP를 따라가므로 오히려 두는 게 편합니다. 완전히 그만두려면 그 줄 앞에 `#`을 붙이고, Cloudflare 토큰도 삭제한 뒤 `.env`의 `CF_*` 세 줄을 지웁니다.
- HSTS를 넣지 않았으므로 브라우저가 `https://`를 기억해 http를 막지는 않습니다. 다만 주소창이 자동완성으로 `https://`를 시도해 안 열릴 수 있으니 `http://`를 직접 칩니다.
- **주의**: (a)의 주의 박스와 같습니다. web image 줄을 SHA로 고정해 두면 **다음 Deploy도 옛 web을 받습니다.** 고친 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-s22-new docker-compose.yml` 로 되돌리고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다.

## 안드로이드 앱 릴리스 빌드·설치 (S23)
Flutter 화면(`flutter_app/`)을 안드로이드 앱(앱 이름 "게시판", applicationId `com.kampus.board`)으로도 만들었습니다. 앱은 서버에 올리는 것이 아니라 **내 PC에서 APK 파일을 만들어 폰에 직접 설치**합니다(Play 스토어 등록 없음). 그래서 Deploy 버튼·Docker와 상관없고, 서버 쪽 변경도 없습니다.
- 앱은 서버가 켜져 있어야 씁니다. 평소 EC2를 꺼 두므로, 쓰기 전에 EC2를 켜고 1~5분 DNS를 기다립니다(위 "자동 배포" 4번, "S22 DNS 자동 고침" 참고). 서버가 꺼져 있으면 앱은 네트워크 오류만 보여 줍니다.
- 서버 주소는 앱 안에 **빌드할 때 박아 넣습니다**(`--dart-define=API_BASE_URL`, 코드는 `flutter_app/lib/api/api_base_url.dart`). 웹과 달리 같은 주소의 `/api`로 자동 연결되지 않기 때문입니다.

### 1) 릴리스 빌드 (집 PC, flutter_app 폴더에서)
- `flutter build apk --release --dart-define=API_BASE_URL=https://<사이트주소>/api`
  - `<사이트주소>`는 실제 사이트 주소(예: `kampus.<도메인>`)로 바꿉니다. 꺾쇠(`<` `>`)는 지웁니다. 실제 주소는 이 파일에 적지 않습니다.
  - 규칙: `https://`로 시작(릴리스는 https만 됩니다), 맨 끝에 `/`를 붙이지 않습니다, 끝은 `/api`입니다.
  - 주소를 틀리게 넣으면 앱이 서버에 붙지 않고 "설정 오류" 안내 화면을 띄웁니다. 그러면 주소를 고쳐 다시 빌드합니다.
- 빌드가 끝나면 Gradle 백그라운드 프로그램(데몬)이 메모리를 잡고 남아 있으니 정리합니다: `flutter_app/android/gradlew.bat --stop` (PowerShell에서는 `.\android\gradlew.bat --stop`, flutter_app 폴더 기준)
- 결과 파일: `flutter_app/build/app/outputs/flutter-apk/app-release.apk` (크기는 대략 50MB대). `/build/`는 `.gitignore`에 있어 커밋되지 않습니다.

### 2) 폰에 설치 (두 방법 중 하나)
- **(a) USB + adb**: 폰에서 개발자 옵션 -> USB 디버깅을 켜고 USB로 연결한 뒤 `C:\Users\seoka\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r <APK 전체 경로>`. `-r`은 이미 설치된 앱 위에 덮어 설치(로그인 기록 유지)하는 옵션입니다.
  - 삼성 폰은 설정의 "자동 차단"(Auto Blocker)이 켜져 있으면 USB 디버깅이 잠겨 있습니다. 끄고 시도합니다.
- **(b) 파일로 설치**: APK 파일을 폰으로 복사(USB 파일 전송, 클라우드 등)하고 폰의 파일 앱에서 눌러 설치합니다. "출처를 알 수 없는 앱" 설치 허용이 필요하고, Play 프로텍트 경고가 뜰 수 있습니다(직접 만든 앱이라 뜨는 정상 경고입니다. "그래도 설치"를 고릅니다).

### 3) 주의
- **릴리스 빌드는 집 PC에서만 합니다.** 지금 릴리스 서명은 디버그 키(`android/app/build.gradle.kts`)인데, 디버그 키는 PC마다 달라서 다른 PC에서 만든 APK는 폰에 이미 있는 앱 위에 덮어 설치되지 않습니다(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). 사용자 폴더 이름이 영문인 집 PC에서 빌드합니다(안드로이드 빌드 환경은 `docs/handoff.md`의 "안드로이드 빌드 환경").
- 덮어 설치가 위 오류로 안 되면(집 PC 포맷 등으로 키가 바뀐 경우도 같음) 폰에서 기존 앱을 삭제하고 새로 설치합니다. 로그인만 다시 하면 됩니다(데이터는 서버에 있음).

### 4) 앱 되돌리기
- 이전 버전 APK 파일이 남아 있으면 다시 설치합니다(같은 PC·같은 키로 만든 것이어야 덮어 설치됩니다. 안 되면 삭제 후 설치).
- 개발용으로 잠깐 쓸 때는 USB로 연결해 `flutter run`으로 올립니다(명령은 CLAUDE.md의 "안드로이드 앱(개발)").
- 앱은 서버 배포와 따로라서, 서버를 되돌려도 앱은 그대로입니다(S22 되돌리기 등과 무관).

### 5) 운영 확인 목록 (설치 뒤, EC2를 켜 둔 상태에서)
1. Wi-Fi를 끄고 LTE(모바일 데이터)에서 글 목록과 중고 글 사진이 보이는지 봅니다.
2. 로그인한 뒤 앱을 완전히 닫았다 켜도 로그인이 유지되는지 봅니다.
3. 로그인 후 16분쯤 지나서(접근 토큰 만료 후) 목록을 열어도 로그아웃되지 않고 재발급으로 이어지는지 봅니다.
4. 글 안의 바깥 링크를 누르면 폰 브라우저가 열리는지 봅니다.
5. 뒤로 가기 버튼이 이전 화면으로 가는지(로그아웃·만료 뒤에는 보호 화면으로 돌아가지 않는지) 봅니다.
- 유학생 인증(허가서 신청)은 운영에서 시험하지 않습니다. 진짜 OCR·개인정보가 오가고 Vision 호출은 하루 30회 상한이 있기 때문입니다.

## S23 운영 배포 (2026-11-20)
S23(안드로이드 앱)은 Flutter 화면 코드만 바뀌어서 Deploy 버튼으로 운영에 올렸습니다. 커밋 `fe3871c95db20febb3eeb374e84d7e366909f318`, CI(backend·flutter·images)와 Deploy 모두 초록이었습니다.
- **무엇이 바뀌었나(웹)**: 두 군데입니다. (1) 없는 주소 화면(404)이 상단바와 함께 보입니다(화면 틀 안에 들어옴). (2) 사진 미리보기가 공용 부품으로 바뀌었습니다. 쿠키 보관함·바깥 링크·갤러리·뒤로 가기는 **앱 전용**이라 웹에서는 켜지지 않습니다.
- **배포 방법**: 버튼만 눌렀습니다. 서버(`src`)·compose·nginx 설정 변경이 없어서(`git diff bd6c70b..fe3871c` 결과가 비어 있음) EC2 `docker-compose.yml` 백업·scp가 필요 없었습니다. compose 파일을 바꿀 때만 백업·scp를 하는 위 "자동 배포"의 규칙대로입니다.
- **확인한 것(모두 정상)**:
  1. 웹: 글 목록, 로그인 유지, 없는 주소 화면(상단바와 함께 보임).
  2. 릴리스 APK(LTE): 목록과 사진.
  3. 앱 로그인, 껐다 켜도 유지, 16분 뒤 자동 재발급.
  4. 하이코리아 바깥 링크, 뒤로 가기, 사진 업로드.
  5. EC2를 켤 때 DNS 자동 고침이 첫 실제 재부팅에서도 정상(몇 분 안에 `https://<사이트주소>`가 열림).

### S23 되돌리기 (문제가 있을 때만)
- 직전(S22) 이미지: web `seokam36/demo-web:bd6c70b5c3e15762a396403beab491f59811325c`. app은 서버 코드 변경이 없어 **되돌릴 필요가 없습니다**(web만 되돌립니다).
  1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-s23` — 지금 compose 보관(이미 있으면 다른 이름).
  2. **[서버]** `sed -i 's#image: seokam36/demo-web:latest#image: seokam36/demo-web:bd6c70b5c3e15762a396403beab491f59811325c#' docker-compose.yml` — web 이미지 줄만 바꿉니다.
  3. **[서버]** `grep 'image: seokam36' docker-compose.yml` — web 줄이 `demo-web:bd6c70b...`이고 app 줄은 그대로인지 확인합니다.
  4. **[서버]** `docker compose --profile prod pull web && docker compose --profile prod up -d web` — web만 받아서 다시 띄웁니다.
  5. 브라우저에서 `https://<사이트주소>/` 가 열리는지 봅니다(강력 새로고침).
- **주의**: 위 "(a) 이전 이미지로 되돌리기"의 주의 박스와 같습니다. web image 줄을 SHA로 고정해 두면 **다음 Deploy도 옛 web을 받습니다.** 고친 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-s23 docker-compose.yml` 로 되돌리고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다.
- **앱(APK)**: 서버와 따로입니다. 문제가 앱에만 있으면 이전 APK를 다시 설치합니다(위 "4) 앱 되돌리기").

## S24 푸시 알림 배포 (비자 일정 알림, FCM)
S24(비자 만료 전 휴대폰 알림)를 운영에 올립니다. 서버가 구글 FCM에 "이 폰에 알림 보내 줘"라고 부탁하려면 **서비스 계정 키(JSON 파일)** 가 필요합니다. 이 파일은 비밀이라 저장소·이미지에 넣지 않고 **서버에만 두고, compose가 앱 컨테이너에 읽기 전용으로 보여 줍니다.** 이번에는 **compose가 바뀝니다**(위 "자동 배포"의 규칙: compose를 바꾼 배포는 Deploy 버튼만으로 안 되고 백업·scp를 먼저 합니다).

메모:
- 서버 설정은 `PUSH_MODE`(`off` 기본 / `fcm`)와 `FCM_CREDENTIALS_FILE`(컨테이너 안 키 파일 경로) 두 가지입니다. 서버 `.env`에는 **`PUSH_MODE=fcm` 한 줄만** 넣으면 됩니다. 키 파일 경로(`/app/fcm/service-account.json`)는 compose에 고정돼 있어 `.env`에 안 적습니다. `FCM_TIMEOUT`(기본 10초)과 알림 주기(켜지고 5분 뒤 + 1시간마다, 서울 09~21시만)도 기본값 그대로 씁니다. **운영 `.env`·compose에 `GOOGLE_SDK_JAVA_LOGGING`을 넣지 않습니다**(켜면 구글 요청 내용이 로그에 찍혀 출입증이 샐 수 있음).
- **DB 표가 처음 늘어납니다**: V16(`device_tokens`, 폰 알림 주소), V17(`visa_plan_notifications`, 이미 보낸 알림 기록)이 이번 Deploy에서 Flyway로 자동 적용됩니다. Deploy가 배포 직전에 DB를 자동 백업합니다.
- compose 폴더 마운트: 서버 `~/board/fcm/` 폴더를 컨테이너 `/app/fcm/`에 읽기 전용(`:ro`)으로 보여 줍니다. 파일이 아니라 **폴더**를 마운트한 이유는, 없는 파일을 마운트하면 Docker가 그 이름으로 빈 **폴더**를 만들어 버려서 "키 파일 경로가 폴더"가 되기 때문입니다. 폴더는 비어 있어도 안전하고, `PUSH_MODE=off`이면 앱이 키 파일을 읽지 않아 폴더가 비어 있어도 정상입니다.
- 앱 컨테이너는 root가 아니라 `app`이라는 사용자로 돕니다(Dockerfile의 `USER app`). 서버 파일이 `ec2-user` 전용(600)이면 컨테이너가 못 읽어서 `PUSH_MODE=fcm`일 때 앱이 시작 실패합니다. 그래서 아래 3번에서 파일 주인을 컨테이너 사용자로 맞춥니다.
- web(화면) 이미지도 새 커밋이라 같이 배포되지만 웹에는 푸시 칸이 없어서 화면은 거의 그대로입니다.
- 직전(S23) 이미지: `seokam36/demo:fe3871c95db20febb3eeb374e84d7e366909f318`, `seokam36/demo-web:fe3871c95db20febb3eeb374e84d7e366909f318` — 되돌리기는 아래 "S24 되돌리기".

준비 (Deploy 버튼 누르기 전). **IP는 EC2를 켠 뒤 AWS 콘솔에서 보는 값입니다. 아래 `ssh`·`scp` 명령은 메인 대화가 IP(와 키 파일 이름)를 받아서 완성해 줍니다**(이 문서에는 자리표시를 두지 않고 명령의 모양만 적습니다). 사용자 PowerShell은 `C:\Windows\System32`에서 열려 있을 수 있어서 **파일은 항상 전체 경로**로 씁니다. ssh·scp는 `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@...` 형식입니다.
1. **[PC]** 이번 커밋의 CI 세 작업(backend, flutter, images)이 모두 초록인지 GitHub Actions 탭에서 봅니다(images가 초록이어야 새 이미지가 올라가 있습니다). 그리고 서버를 켜고 1~5분 DNS를 기다립니다.
2. **[PC → 서버]** 서버에 키 폴더를 만듭니다. 왜: scp가 폴더가 없으면 실패하고, 폴더가 없는 채로 compose를 올리면 Docker가 root 소유 빈 폴더를 만들어 권한이 꼬입니다. 명령 모양: `& "C:\Windows\System32\OpenSSH\ssh.exe" -i "$HOME\.ssh\board-ec2" ec2-user@(IP) 'mkdir -p ~/board/fcm'`
3. **[PC → 서버]** 키 파일을 서버로 올립니다. 키 파일은 집 PC `C:\Users\seoka\Documents\` 안의 `...-firebase-adminsdk-....json`(저장소 밖)입니다. 왜: 서버가 구글에 "나는 이 Firebase 프로젝트의 서버"라고 증명하는 출입증입니다. 파일 이름의 가운데 글자는 PowerShell에서 `Get-ChildItem "C:\Users\seoka\Documents\*-firebase-adminsdk-*.json" | Select-Object -ExpandProperty FullName` 로 전체 경로를 보고 확인합니다(와일드카드는 scp에 직접 쓸 수 없습니다). 명령 모양: `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" "(위에서 확인한 전체 경로)" ec2-user@(IP):~/board/fcm/service-account.json` — **서버 쪽 이름은 반드시 `service-account.json`**(compose가 이 이름을 가리킵니다). 키 내용을 화면에 띄우거나 붙여넣지 않습니다.
4. **[서버]** 컨테이너 사용자가 읽을 수 있게 주인·권한을 맞춥니다(ssh로 접속한 서버 셸에서):
   - `sudo chown -R "$(docker exec board-app id -u):$(docker exec board-app id -g)" ~/board/fcm` — 왜: 실행 중인 앱 컨테이너의 사용자(`app`) 번호를 `docker exec ... id`로 물어서, 폴더와 파일의 주인을 그 번호로 바꿉니다(번호를 외워 적지 않아도 됩니다). 앱이 안 떠 있으면 이 명령이 실패하니 먼저 `docker ps`로 `board-app`이 Up인지 봅니다.
   - `sudo chmod 700 ~/board/fcm && sudo chmod 400 ~/board/fcm/service-account.json` — 왜: 주인(컨테이너 사용자)만 읽게 하고 다른 사람은 못 보게 합니다(키는 비밀).
   - `sudo ls -l ~/board/fcm` — 확인: 파일이 `-r--------` 이고 주인이 `ec2-user`가 아니라 숫자 또는 `app`인지 봅니다. 이제 `ec2-user`는 `sudo` 없이는 이 폴더를 못 봅니다(정상). 키 파일을 다시 올릴 때는 `sudo rm ~/board/fcm/service-account.json` 뒤 3번부터 다시 합니다.
5. **[PC → 서버]** 서버의 현재 compose를 백업합니다. 왜: 되돌리기용. 명령 모양: `ssh ... 'cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-s24'` (이미 `.bak-s24`가 있으면 덮어쓰지 말고 다른 이름으로).
6. **[PC → 서버]** 새 compose를 서버로 복사합니다. 왜: Deploy 버튼은 서버의 compose를 바꾸지 않아서, 안 올리면 키 폴더 마운트와 `PUSH_MODE`가 앱에 전달되지 않습니다. 명령 모양(저장소 폴더 `C:\Users\seoka\IdeaProjects\board-vibe-coding`의 파일을 전체 경로로): `& "C:\Windows\System32\OpenSSH\scp.exe" -i "$HOME\.ssh\board-ec2" "C:\Users\seoka\IdeaProjects\board-vibe-coding\docker-compose.yml" ec2-user@(IP):~/board/docker-compose.yml`
7. **[서버]** 새 compose로 앱만 다시 띄워 키가 **읽히는지 먼저** 확인합니다. 왜: 아직 `PUSH_MODE`가 `off`라 이때는 옛 이미지든 새 이미지든 안전하고, 권한 실수를 켜기 전에 잡을 수 있습니다.
   - `cd ~/board && docker compose --profile prod up -d app` — 마운트가 추가돼 app이 새로 만들어집니다(몇 초 끊김).
   - `docker exec board-app sh -c 'test -r /app/fcm/service-account.json && echo 읽힘 || echo 못읽음'` — `읽힘`이어야 합니다. `못읽음`이면 4번의 주인·권한을 다시 봅니다(`docker exec board-app id`로 사용자 번호, `sudo ls -ln ~/board/fcm`으로 파일 주인 번호가 같은지).
   - `docker exec board-app sh -c 'test -f /app/fcm/service-account.json && echo 파일 || echo 파일아님'` — `파일`이어야 합니다(`파일아님`이면 폴더가 만들어진 것이니 3번 업로드를 확인).
8. **[서버]** `.env`에 한 줄을 넣습니다. **`cat .env`나 `echo ... >> .env`는 쓰지 않고 편집기로 고칩니다**(`.env`에는 다른 비밀 값이 많습니다).
   - `nano ~/board/.env` 를 열고 **nano 화면이 뜬 것을 확인한 뒤** 맨 아래에 `PUSH_MODE=fcm` 한 줄을 적습니다(이미 같은 이름의 줄이 있으면 새로 만들지 말고 고칩니다). `=` 앞뒤에 띄어쓰기·따옴표를 넣지 않습니다. 저장은 `Ctrl+O` 엔터 -> `Ctrl+X`.
   - 확인: `grep -c '^PUSH_MODE=fcm$' ~/board/.env` 가 `1`, `grep -c 'GOOGLE_SDK_JAVA_LOGGING' ~/board/.env` 가 `0`.
   - **허가서 해시 비밀이 안 바뀌었는지 확인**(앱을 다시 띄우기 전에 항상): `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1`(0이면 멈추고 `.env`를 다시 봅니다. 이 값은 절대 바꾸지 않습니다). `ls -l ~/board/.env` 가 `-rw-------` 인지도 봅니다.
9. **[브라우저]** Actions 탭 -> Deploy -> Run workflow (위 "자동 배포"와 같음). 새 이미지를 받아 앱이 `PUSH_MODE=fcm`으로 뜨고, Flyway가 V16·V17을 적용합니다. DB 백업·헬스체크가 모두 초록이어야 합니다. (버튼을 못 쓰면 **[서버]** `cd ~/board && docker compose --profile prod pull && docker compose --profile prod up -d`.)

확인 (배포 뒤)
1. **[브라우저]** `https://<사이트주소>/actuator/health` 가 `UP`인지 봅니다.
2. **[서버]** 푸시가 켜졌는지: `docker logs board-app 2>&1 | grep -c '푸시 fcm 켜짐'` 이 `1` 이상(로그 "푸시 fcm 켜짐, 시간 상한 10초"). 0이면 `docker logs --tail 50 board-app` 에서 시작 실패 메시지를 봅니다(아래 문제 해결).
3. **[서버]** 표 변경(V16·V17) 확인: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"'` 에서 `17 | t`, `16 | t`가 보이는지 봅니다(`f`가 있으면 아래 "Flyway(표 변경) 문제와 되돌리기"로).
4. **[서버]** 로그에 키 내용이 없는지: `docker logs board-app 2>&1 | grep -ci 'private.key\|begin.*key'` 가 `0`. (명령에 내 키를 쓰지 않고 키 파일에 들어 있는 글자 모양으로 찾는 것입니다. 0이 아니면 로그를 펴 보지 말고 Firebase 콘솔에서 그 키를 삭제하고 새로 만든 뒤 위 3~4번으로 교체합니다.)
5. **[서버]** 메모리와 재시작: `docker stats --no-stream board-app` 의 메모리가 `512MiB` 미만인지, `docker ps` 에서 `board-app`이 `Restarting`이 아니고 `Up` 시간이 늘어나는지 봅니다.
6. **[브라우저]** 기존 기능 3가지(글 목록, 중고 글 상세, 새로고침해도 로그인 유지)를 봅니다.

문제 해결
- 앱이 시작 때 실패하고 로그에 "FCM_CREDENTIALS_FILE을 읽을 수 없습니다(파일이 없거나, 읽기 권한이 없거나, 서비스 계정 키가 아님)": 위 7번의 `읽힘` 확인을 다시 합니다. 주인 번호 불일치(4번), 파일이 아니라 폴더(3번), 파일이 서비스 계정 키가 아님(콘솔에서 받은 `...-firebase-adminsdk-....json`이 맞는지) 중 하나입니다. 급하면 아래 "푸시 끄기"로 먼저 살립니다.
- `PUSH_MODE는 off, fcm 중 하나여야 합니다`: `.env`의 값 철자·공백을 봅니다(`grep -c '^PUSH_MODE=fcm$'`).
- 알림이 안 옴: 아래 "운영 알림 시험"의 안내를 봅니다(앱이 화면에 떠 있으면 알림이 안 보입니다).

**푸시 끄기** (이미지를 되돌릴 필요가 없습니다)
1. **[서버]** `nano ~/board/.env` 에서 `PUSH_MODE=fcm` 을 `PUSH_MODE=off` 로 바꿉니다(줄을 지워도 같습니다).
2. **[서버]** `cd ~/board && docker compose --profile prod up -d app` — 환경변수가 바뀌어 app이 새로 만들어집니다(단순 `restart`는 `.env`를 다시 읽지 않습니다). 앞의 `PERMIT_HASH_SECRET` 확인 명령도 같이 칩니다.
3. 확인: `docker logs board-app 2>&1 | grep -c '푸시 알림 꺼짐'` 이 `1` 이상. 키 파일과 폴더는 남겨 둬도 됩니다(off면 안 읽음).

### 운영 알림 시험 (테스트 계정으로 1회)
운영 테스트 계정(`wntjrdka`, 연세대 인증)으로 확인합니다. 계정의 [인증 해제]는 누르지 않습니다. **운영 DB에서 알림 기록(`visa_plan_notifications`)을 지우는 조작은 하지 않습니다**(로컬 시험과 달리 진짜 데이터라서). 알림은 같은 만료일·단계당 한 번만 가므로(일정을 지웠다 다시 등록하면 다시 감) 시험은 한 번만 하고, 안 와도 기록을 지우지 말고 원인을 먼저 찾습니다.
1. **[브라우저/앱]** 시험 시간은 **서울 09~21시** 안이어야 합니다(밖이면 알림이 안 갑니다). 앱이 켜지고 5분 뒤 한 번, 그 뒤로는 앞 회차가 끝나고 1시간 뒤마다(정시가 아님) 돌아서, 최대 약 1시간(+5분) 안에 옵니다. 빨리 보려면 `docker compose --profile prod up -d --force-recreate app`으로 앱만 재시작하면 5분 뒤 회차가 새로 시작됩니다(재시작 20~30초 동안 사이트 안 열림).
2. **[앱]** 운영용 릴리스 앱(아래 "앱 다시 설치")으로 테스트 계정 로그인 -> 비자 일정 화면에서 **만료일을 오늘부터 7일 뒤**로 저장합니다.
3. **[앱]** 비자 일정 화면의 "휴대폰 알림" 칸에서 **[알림 켜기]** -> 폰의 알림 권한 팝업에서 허용. 확인(**[서버]**, 토큰 값은 출력하지 않고 개수만): `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM device_tokens;"'` 가 1 이상.
4. **[앱]** 앱을 **홈 버튼으로 내려 둡니다**(앱이 화면에 떠 있으면 알림이 표시되지 않습니다. 앱을 완전히 닫아도 됩니다). 다음 정시 주기를 기다립니다.
5. **[폰]** "비자 일정 알림"이 **한 번** 오는지 봅니다. **[서버]** 보낸 결과 로그 확인: `docker logs --since 2h board-app 2>&1 | grep -i '푸시\|알림' | tail -20` (토큰·키는 로그에 안 남게 만들어져 있습니다. 구글 호출 실패 줄이 있으면 상태 숫자를 봅니다: 401·403이면 키·프로젝트 문제, 404면 토큰이 낡음).
6. **[앱]** 시험이 끝나면 **만료일을 원래대로 되돌립니다**(또는 시험 전 값으로). 같은 화면에서 로그아웃도 한 번 해 보고, **[서버]** 위 3번의 개수가 줄어드는지 봅니다(로그아웃 때 기기 토큰 삭제 요청이 nginx를 거쳐 서버에 전달되는지 확인).
- 이 시험에서 **구글 액세스 토큰을 처음 실제로 받습니다**(자동 테스트는 가짜 출입증으로 했음). 서버 시계가 크게 어긋나 있으면 토큰 요청이 실패할 수 있습니다.

### 앱 다시 설치 (운영용 릴리스)
지금 폰의 "게시판" 앱은 로컬(8081) 개발용 디버그 앱이라, 운영에서 쓰려면 릴리스 APK를 다시 설치합니다. 빌드·설치 방법은 위 "안드로이드 앱 릴리스 빌드·설치 (S23)"를 그대로 따릅니다. 이번에 추가로 알 것:
- **`flutter_app/android/app/google-services.json`이 있어야 빌드됩니다**(Firebase 앱 명찰 파일, 커밋 안 함, 저장소 밖 사본이나 Firebase 콘솔에서 받기). 없으면 "File google-services.json is missing"으로 실패합니다.
- **빌드 전에 도메인부터 확인**(S23 규칙): `https://<사이트주소>/actuator/health` 가 `{"status":"UP"}`이고 네임서버가 `*.ns.cloudflare.com`인지 확인하고 나서 빌드합니다. 아니면 빌드·설치 금지.
- 집 PC(영문 사용자 폴더)에서만 빌드·`adb install -r`로 덮어 설치합니다(디버그 키 서명). 설치 뒤 로그인을 다시 하고 [알림 켜기]를 누릅니다.

### S24 되돌리기 (문제가 있을 때만)
- **먼저 푸시만 끄면 되는 문제인지** 봅니다(위 "푸시 끄기"). 알림 문제는 대부분 이걸로 충분하고 이미지·compose를 되돌릴 필요가 없습니다.
- 이미지·compose까지 되돌릴 때: 직전(S23) 이미지는 app `seokam36/demo:fe3871c95db20febb3eeb374e84d7e366909f318`, web `seokam36/demo-web:fe3871c95db20febb3eeb374e84d7e366909f318`.
  1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-s24-new` — 지금 compose 보관(이미 있으면 다른 이름).
  2. **[서버]** `cp docker-compose.yml.bak-s24 docker-compose.yml` — 키 폴더 마운트와 `PUSH_MODE`가 없던 옛 compose로 복귀. (`.env`의 `PUSH_MODE=fcm` 줄은 옛 compose가 앱에 안 넘기므로 무해합니다. 지워도 됩니다.)
  3. **[서버]** `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:fe3871c95db20febb3eeb374e84d7e366909f318#; s#image: seokam36/demo-web:latest#image: seokam36/demo-web:fe3871c95db20febb3eeb374e84d7e366909f318#' docker-compose.yml` 그리고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `fe3871c...` 인지 확인합니다.
  4. **[서버]** `.env` 확인(앱을 다시 띄우기 전에): `grep -cxF "PERMIT_HASH_SECRET=$(docker exec board-app printenv PERMIT_HASH_SECRET)" ~/board/.env` 가 `1`.
  5. **[서버]** `docker compose --profile prod pull app web && docker compose --profile prod up -d app web` 후 `https://<사이트주소>/actuator/health` 가 UP이고 글 목록이 뜨는지 봅니다.
- **DB(V16·V17)는 되돌리지 않습니다.** 표 `device_tokens`·`visa_plan_notifications`가 남아도 옛 앱은 문제없이 뜹니다. 옛 앱에는 그 표의 엔티티가 없어서 시작 때 표 구조 검사(validate) 대상이 아니기 때문입니다(Flyway 기록 17은 옛 앱이 몰라도 무시되는 새 항목일 뿐). 다시 올릴 때도 표가 이미 있어 그대로 이어집니다.
- **주의**: 위 "(a) 이전 이미지로 되돌리기"의 주의 박스와 같습니다. 이미지를 SHA로 고정해 두면 **다음 Deploy도 옛 이미지를 받습니다.** 고친 새 `latest`가 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-s24-new docker-compose.yml` 로 되돌리고 `grep 'image: seokam36' docker-compose.yml` 로 두 줄이 `:latest`인지 확인한 다음 Deploy를 누릅니다.
- **앱(APK)**: 서버와 따로입니다. 푸시를 끄면 앱은 알림만 안 올 뿐 그대로 씁니다. 앱의 [알림 켜기]는 서버가 되돌아가 있어도 "알림이 켜져 있어요"로 보입니다(폰 권한 기준이고, 옛 서버에 없는 등록 API 실패는 조용히 넘어감). 알림만 안 옵니다.

## 웹 화면: Flutter로 교체 (S16-n2)
운영 `web` 이미지가 React에서 **Flutter 웹**(`flutter_app/`)으로 바뀌었습니다. `/v2` 같은 별도 주소 없이 통째로 교체합니다.
- 이미지 이름은 그대로 `seokam36/demo-web`(`:latest`, `:<커밋 SHA>`)이라 **EC2의 `docker-compose.yml`을 바꿀 필요가 없습니다**(포트 80, 볼륨 없음, healthcheck 없음 - Flutter 이미지와 그대로 맞음. Deploy 워크플로의 헬스체크는 `/actuator/health`를 nginx 경유로 부르는데 Flutter nginx.conf에도 같은 블록이 있습니다).
- 만드는 방법: CI의 `flutter` 작업이 `flutter build web --web-content-hash`로 `build/web`을 만들고 e2e까지 통과시킨 뒤, 그 결과를 `images` 작업이 받아 `flutter_app/Dockerfile`(빌드 없이 COPY만)로 이미지에 담습니다. 그래서 Flutter 검사가 실패하면 이미지도 올라가지 않습니다.
- nginx 설정(`/api` 전달 규칙은 `flutter_app/board-locations.conf`, 서버 블록(80·443)은 `flutter_app/nginx.conf`)은 React 때와 같은 `/api` 전달(사진 올리기 `client_max_body_size 11m`·`proxy_read_timeout 150s` 포함, `/api/chat`만 75초(S17-h), **허가서 신청 주소 `/api/users/me/permit-verification`도 같은 11m·150s**(S20, 없으면 1MB 넘는 허가서 사진이 413))과 `/actuator/health` 열기, `/api/actuator` 404를 갖습니다. 달라진 점: 해시 붙은 파일(`main.dart.<해시>.js`)만 1년 immutable 캐시, `index.html` 등 나머지는 `no-cache`, 없는 `/assets/...`·`/canvaskit/...`은 404, 그 밖의 주소는 `index.html`(딥링크), JSON도 gzip 압축.
- 배포 절차는 위 "자동 배포"와 똑같습니다(Deploy 버튼). 바뀌는 건 화면 파일뿐이고 DB·백엔드는 영향이 없습니다.

**첫 배포 뒤 확인할 것** (브라우저 `http://<퍼블릭IP>/`)
1. **새로고침 딥링크**: `/posts/1`, `/market/1`을 주소창에서 직접 열거나 새로고침해도 화면이 뜨는지(JSON·404가 아니라). 첫 로딩은 React보다 느릴 수 있습니다(Flutter 엔진을 받음).
2. **/api 전달**: 목록에 글이 보이고 로그인이 되는지. 또는 `http://<퍼블릭IP>/api/posts`가 JSON 배열을 주는지. `/actuator/health`가 UP인지.
3. **사진 올리기**: 중고 글쓰기에서 3MB 안팎 사진을 올려 413·504 없이 되는지(nginx 한도 11m, 대기 150초가 Flutter 이미지에도 들어 있는지 보는 확인). S20 배포 뒤에는 **1MB 넘는 허가서 사진 신청이 201**인지도 봅니다(위 "S20 배포"의 4번 화면 확인, 허가서 주소 11MB·150초 예외).
4. **캐시 헤더** (**[PC]** Git Bash): `curl -sI http://<퍼블릭IP>/ | grep -i cache-control` 이 `no-cache`인지, 그리고 `curl -s http://<퍼블릭IP>/flutter_bootstrap.js | grep -o 'main\.dart\.[0-9a-f]\{8\}\.js'` 로 나온 파일 이름을 `curl -sI http://<퍼블릭IP>/<그 이름> | grep -i cache-control` 에 넣어 `public, max-age=31536000, immutable`인지 봅니다. 없는 파일(`/assets/x.png`)은 404여야 합니다.
5. **옛 화면이 남지 않는지**: 이미 React 화면을 열어 본 브라우저는 강력 새로고침(Ctrl+Shift+R) 한 번 하고 확인합니다(React 파일은 해시 이름이라 새 화면과 섞이지는 않습니다).
6. 손 확인표(`flutter_app/e2e/README.md`의 "손 확인표": 한글 입력, iPhone 사진 선택 등)도 이때 훑어봅니다.

**web만 React로 되돌리기** (Flutter 화면에 문제가 있을 때. 백엔드·DB는 그대로 둡니다)
- 되돌릴 이미지: **S16-n2 커밋 바로 앞 커밋(S16-n1, `80431c3`)의 SHA 태그** `seokam36/demo-web:80431c308c0ad7d6d519a597ed21f3e63840ce6b` 가 React 마지막 이미지입니다. (n1은 n2보다 먼저 따로 push해서 그 CI의 images가 React 이미지를 올렸습니다. 확인: **[PC]** `git log --oneline` 에서 n2 커밋(메시지에 `S16-n2`) 바로 아래 줄이 `80431c3`인지.)
- 그 커밋의 CI에서 `images`까지 초록이었어야 태그가 있습니다. main에서는 기다리는 실행이 가장 최신 1개만 남아 중간 커밋의 images가 건너뛰어졌을 수 있으니, 그럼 GitHub Actions 탭에서 images가 초록이었던 가장 가까운 이전 커밋을 씁니다.
- 순서 (app 되돌리기 (a)와 같은 방식이고, web 줄만 바꿉니다):
  1. **[서버]** `cd ~/board && cp docker-compose.yml docker-compose.yml.bak-latest` — 지금 설정을 복사해 둡니다(이미 (a)로 만들어 둔 백업이 있으면 덮어쓰지 말고 다른 이름으로).
  2. **[서버]** `sed -i 's#image: seokam36/demo-web:latest#image: seokam36/demo-web:80431c308c0ad7d6d519a597ed21f3e63840ce6b#' docker-compose.yml` — web 이미지 줄만 React 마지막 이미지로 바꿉니다.
  3. **[서버]** `grep 'image: seokam36' docker-compose.yml` — web 줄이 `seokam36/demo-web:80431c308c0ad7d6d519a597ed21f3e63840ce6b`이고 app 줄은 그대로인지 확인합니다.
  4. **[서버]** `docker compose --profile prod pull web && docker compose --profile prod up -d web` — web만 받아서 다시 띄웁니다.
  5. 브라우저에서 React 화면이 뜨는지 봅니다(강력 새로고침).
- **주의**: 위 "(a) 이전 이미지로 되돌리기"의 주의 박스와 똑같습니다. image 줄을 SHA로 고정해 두면 **다음 Deploy도 React를 받습니다.** 고친 Flutter 이미지가 `latest`로 올라온 뒤(Actions 초록) `cp docker-compose.yml.bak-latest docker-compose.yml`로 원복하고 `grep`으로 `demo-web:latest`인지 확인한 다음 Deploy를 누릅니다.
- 되돌리기 전에 서버에서 옛 React 이미지가 이미지 정리(prune)로 지워졌어도 괜찮습니다. SHA 태그로 Docker Hub에서 다시 받습니다.
- React 앱 코드는 S16-p에서 저장소에서 지웠습니다. 그래도 이미 올라간 SHA 태그 이미지는 Docker Hub에 남아 있어 되돌릴 수 있습니다(코드는 없어졌지만 이미지는 그대로). 코드가 필요하면 S16-p 이전 커밋(`git show 80431c3:frontend/...`)에서 볼 수 있습니다.

## 메일 발송 (S10)
> **S20 배포 확인 뒤 지움.** S20-f1에서 학교 메일 인증(S10) 백엔드를 걷어내서, S20 이미지는 메일을 보내지 않고 `MAIL_*`도 읽지 않습니다(새 compose에도 `MAIL_*` 줄이 없음). 이 절의 메일 설정·"학교 인증 코드가 막혔다는 문의"·"학교 메일 없이 운영에서 인증 테스트하기"는 **S20 배포 문제로 S18 이미지로 되돌렸을 때만** 필요해서 남겨 둡니다. 되돌릴 일이 없다고 확인한 뒤 위 "S20 배포"의 "확인 끝난 뒤 정리"(서버 `.env`의 `MAIL_*` 삭제, Gmail 앱 비밀번호 폐기)를 하고, 그때 이 세 부분을 지웁니다. 아래 "챗봇이 막혔다는 문의" 이하는 계속 쓰는 내용입니다.

학교 메일 인증 코드를 실제로 보내는 설정입니다(S18 이미지 기준). 값은 모두 EC2 `~/board/.env`에만 두고 이 파일·저장소에는 적지 않습니다.

환경변수 6개 (`docker-compose.yml`이 `.env`에서 읽어 app에 넘김):
- `MAIL_MODE`: `log`(기본) 또는 `smtp`. `smtp`일 때만 실제로 보냅니다.
- `MAIL_HOST`: 메일 서버 주소 (Gmail은 `smtp.gmail.com`).
- `MAIL_PORT`: 메일 서버 포트 (기본 `587`).
- `MAIL_USERNAME`: 메일 서버 로그인 아이디 (Gmail은 전용 Gmail 주소).
- `MAIL_PASSWORD`: 로그인 비밀번호 (**비밀**. Gmail은 계정 비밀번호가 아니라 "앱 비밀번호").
- `MAIL_FROM`: 보내는 사람 주소 (Gmail은 `MAIL_USERNAME`과 같게).

`MAIL_MODE=smtp`인데 HOST, USERNAME, PASSWORD, FROM 중 하나라도 비면 앱이 시작 때 실패합니다(Deploy 헬스체크도 빨간불).
`/actuator/health`는 메일 상태를 보지 않으므로, 나중에 Gmail이 장애여도 배포 헬스체크는 영향이 없습니다.
Gmail 무료 계정은 하루 약 500통까지만 보낼 수 있습니다. 회원당 하루 10회 상한이라 사용자가 많이 몰리면 그날 나머지 발송은 실패(API 500)할 수 있고, 근본 해결은 AWS SES 전환입니다.

**주의**: `MAIL_MODE`를 `log`로 두거나 안 적으면 메일이 가지 않고 서버 로그에 코드가 찍히기만 합니다. 운영에서 인증 메일이 안 온다면 먼저 `.env`에 `MAIL_MODE=smtp`가 있는지 봅니다. (코드가 로그에 남으므로 운영에서는 `smtp`로 씁니다.)

**Gmail 앱 비밀번호 만들기 (요약)**
1. 서비스 전용 Gmail 계정을 새로 만듭니다(개인 계정 사용 금지).
2. 그 계정에서 Google 계정 -> 보안 -> "2단계 인증"을 켭니다(켜야 앱 비밀번호 메뉴가 나옵니다).
3. Google 계정 -> 보안 -> "앱 비밀번호"에서 이름(예: `board`)을 정해 만듭니다. 나오는 16자리를 복사해 둡니다(다시 볼 수 없음).
4. 그 16자리가 `MAIL_PASSWORD`, Gmail 주소가 `MAIL_USERNAME`과 `MAIL_FROM`입니다.

**운영에 적용하기** (운영 배포는 S10-g에서 합니다)
1. **[서버]** `~/board/.env`에 6개 값을 추가합니다(`MAIL_MODE=smtp`, `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, 나머지는 위 값). 로그에 비밀번호가 남지 않게 `cat .env`로 화면에 띄우지 않습니다.
2. **[PC]** `docker-compose.yml`이 바뀌었으므로 위 비상용 4~5단계(compose 백업 -> `scp`)로 EC2에 먼저 복사합니다. 이걸 빼먹으면 `.env`에 값을 넣어도 app에 전달되지 않습니다.
3. Deploy 버튼을 누릅니다(앱이 새 환경변수로 다시 뜹니다).
4. 확인: **[서버]** `docker compose --profile prod config | grep -c '^ *MAIL_'` 가 `6`인지 봅니다(비밀번호가 화면에 나오므로 `config`를 그대로 출력하지 않습니다). 실제 메일이 오는지는 브라우저에서 인증 코드를 요청해 봅니다.

**앱 비밀번호 교체** (유출 의심, 정기 교체)
1. Gmail 계정의 "앱 비밀번호"에서 새로 만들고, 옛 것은 삭제합니다.
2. **[서버]** `~/board/.env`의 `MAIL_PASSWORD` 줄만 새 값으로 고칩니다.
3. **[서버]** `cd ~/board && docker compose --profile prod up -d app` — 환경변수가 바뀌었으므로 app 컨테이너가 새로 만들어집니다(단순 `restart`는 `.env`를 다시 읽지 않아 반영되지 않습니다).

**나중에 AWS SES로 바꿀 때**: 코드는 그대로이고 `.env`의 `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` 값만 SES SMTP 정보로 바꾸면 됩니다(`MAIL_MODE=smtp`, 포트 587 유지). 위 "앱 비밀번호 교체"와 같은 순서로 app을 다시 띄웁니다.

**학교 인증 코드가 막혔다는 문의가 오면** (운영자 메모, **S20 배포 확인 뒤 지움** - S20에서는 메일 인증이 없어 Redis `email-verify-*` 키도 새로 생기지 않습니다. S18로 되돌렸을 때만 해당)
인증 코드 발송에는 Redis 제한이 있습니다. 모든 키는 만들어진 지 24시간 뒤 저절로 사라지므로, 급하지 않으면 기다려도 풀립니다.
- 회원 60초 쿨다운: `email-verify-cooldown:{회원id}`
- 회원 하루 10회: `email-verify-daily:{회원id}`
- 같은 회원·같은 메일 하루 2회: `email-verify-pair:{회원id}:{메일해시}`
- 같은 메일 하루 5회(모든 계정 합산): `email-verify-target:{메일해시}`

메일해시 = 메일 주소를 앞뒤 공백 제거·소문자로 바꾼 뒤 SHA-256 하고 hex 64자로 적은 값입니다. 누군가 남의 메일로 일부러 보내서 막은 경우(`target` 키)에는 운영자가 키를 지워 풀 수 있습니다.

1. **[PC, PowerShell]** 메일해시 계산 (메일 자리에 실제 주소를 넣고, 소문자로 씁니다):
   - `$m='abc@school.ac.kr'.Trim().ToLowerInvariant(); [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($m))).Replace('-','').ToLower()`
2. **[PC]** EC2에 접속합니다: `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>`
3. **[서버]** 같은 메일 하루 5회 제한 풀기. board-redis 컨테이너에 비밀번호가 `REDISCLI_AUTH` 환경변수로 이미 들어 있어서 명령에 비밀번호를 쓰지 않습니다(`REDIS_PASSWORD`를 입력하거나 화면에 찍지 않습니다):
   - `docker exec board-redis redis-cli DEL email-verify-target:<해시>`
4. **[서버]** (필요하면) 그 메일의 회원별 2회 제한(`pair`) 키는 `KEYS` 대신 SCAN으로 찾아서 지웁니다(`KEYS`는 키가 많으면 Redis를 멈추게 합니다):
   - 찾기: `docker exec board-redis redis-cli --scan --pattern 'email-verify-pair:*:<해시>'`
   - 지우기: 위에서 나온 키 이름을 확인한 뒤 `docker exec board-redis redis-cli DEL email-verify-pair:<회원id>:<해시>`
5. 쿨다운·하루 10회 키(`cooldown`, `daily`)도 같은 방식으로 `DEL email-verify-cooldown:<회원id>` 처럼 지울 수 있습니다.

주의: 메일 주소 자체는 로그·문서·이슈에 남기지 않습니다(해시만 다룹니다). 위 PowerShell 줄은 주소가 터미널 기록에 남을 수 있으니 계산 후 창을 닫거나 기록을 지웁니다.

**챗봇이 막혔다는 문의가 오면** (운영자 메모)
챗봇은 회원 한 명당 1분에 5번, 서울 하루에 30번까지만 물을 수 있고 넘으면 429이고, 화면에는 "질문 횟수를 넘었어요 (1분에 5번, 하루 30번까지). 1분 뒤에도 안 되면 내일 다시 시도해 주세요"가 나옵니다(S17-f4). 카운터는 Redis에 있고 시간이 지나면 저절로 사라지므로 급하지 않으면 기다려도 풀립니다(분 키는 약 2분, 하루 키는 최대 25시간).
**운영이 off일 때는 횟수를 세지 않아(Redis 키가 안 생김) 이 절은 해당 없습니다.** 이때 "챗봇이 안 돼요" 문의는 429가 아니라 503("지금은 AI 챗봇을 쓸 수 없어요")이고, 정상(의도된 동작)입니다. 아래 내용은 운영 AI를 켠 뒤(`LLM_MODE=gemini`, S18 배포 후)에 씁니다.
- 분 카운터: `chat-rate-min:{회원id}:{분 번호}` (분 번호 = 1970년부터 센 분)
- 하루 카운터: `chat-rate-day:{회원id}:{서울 날짜, 예 2026-11-15}`

아래 명령은 모두 **[서버]**(EC2에 접속한 뒤)에서 칩니다. `REDISCLI_AUTH`가 이미 들어 있어서 비밀번호를 쓰지 않습니다(위 학교 인증 코드 절과 같음).
1. 확인: `docker exec board-redis redis-cli --scan --pattern 'chat-rate-day:<회원id>:*'` 로 키 이름을 찾고, `docker exec board-redis redis-cli GET <키 이름>` 으로 오늘 쓴 횟수를 봅니다. (`KEYS`는 키가 많으면 Redis를 멈추게 하니 `--scan`을 씁니다.)
2. 풀어 주기: 위에서 나온 키 이름을 확인한 뒤 `docker exec board-redis redis-cli DEL <키 이름>` (분 키도 `--scan --pattern 'chat-rate-min:<회원id>:*'` 로 찾아 같은 방식으로 지웁니다.)
3. 한도 숫자 자체를 바꾸려면 `src/main/resources/application.properties`의 `app.chat.rate-limit.per-minute`·`per-day` 기본값을 고쳐 다시 배포합니다(환경변수로는 받지 않습니다).
4. Redis를 재시작하면 횟수가 모두 초기화됩니다(디스크에 저장하지 않는 설정). 재시작 직후에는 모두가 한도를 새로 받는다는 점만 알아 두면 됩니다.

**챗봇이 "서버 전체 상한" 때문에 막혔다면** (S18-d, 운영자 메모)
회원 한 명의 한도(429)와 별개로, 모든 회원 합계로 분 7질문·하루 200질문(운영 값, compose 기본값, 환경변수 `CHAT_GLOBAL_PER_MINUTE`·`CHAT_GLOBAL_PER_DAY`, 0이면 검사 안 함)을 넘으면 503이 납니다. 구글 무료 한도를 지키려는 것이라 **정상 동작**입니다. 하루 카운터는 **태평양 자정(한국 오후 4~5시)** 에 새로 시작하고, 분 카운터는 1분 뒤에 풀립니다. 로그에는 "챗봇 서버 전체 상한(분|하루) 넘음 -> 503"이 남습니다.
- 분 카운터: `chat-global-min:{분 번호}` (회원 구분 없이 키 하나)
- 하루 카운터: `chat-global-day:{태평양 날짜, 예 2026-11-15}`

아래 명령은 **[서버]**에서 칩니다(위 챗봇 절과 같음).
1. 확인: `docker exec board-redis redis-cli --scan --pattern 'chat-global-*'` 로 키 이름을 찾고, `docker exec board-redis redis-cli GET <키 이름>` 으로 지금 센 질문 수를 봅니다.
2. 급히 풀어 주기(구글 한도가 실제로는 남았을 때만): 키 이름을 확인한 뒤 `docker exec board-redis redis-cli DEL <키 이름>`. 구글 한도가 정말 소진된 상태에서 지우면 헛호출이 다시 나가 구글 429가 날 수 있습니다.
3. 로그에 "전체 상한(하루)"가 자주 보이면 일부 회원이 하루 한도를 다 쓰고 있다는 뜻이니, 회원 하루 한도(`app.chat.rate-limit.per-day`)를 낮추거나 구글 유료 전환을 검토합니다.
4. 숫자를 바꾸려면 서버 환경변수 `CHAT_GLOBAL_PER_MINUTE`·`CHAT_GLOBAL_PER_DAY`를 고쳐 앱을 다시 띄웁니다. Redis를 재시작하면 카운터가 초기화됩니다.

**학교 메일 없이 운영에서 인증 테스트하기** (S10-h 배포 때 실제로 한 방법. **S20 배포 확인 뒤 지움** - S20에서는 위 "S20 배포"의 4번처럼 허가서 신청으로 테스트합니다)
학교 메일 도메인만 허용되므로, 잠깐 `gmail.com`을 연세대 도메인으로 등록해서 테스트합니다. SQL 실행 방식은 아래 "관리자 지정·해제"와 같습니다([서버]에서 실행, PowerShell 아님).

1. **[PC]** EC2에 접속합니다: `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>`
2. **[서버]** 임시 도메인 추가 (연세대와 같은 학교로 `gmail.com`을 등록합니다):
```sh
docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
INSERT INTO school_email_domains (school_id, domain) SELECT school_id, 'gmail.com' FROM school_email_domains WHERE domain = 'yonsei.ac.kr';
SQL
```
   `INSERT 0 1`이 나오면 성공입니다.
3. **[브라우저]** 테스트 계정을 새로 가입합니다. 내 정보에서 발송 전용 Gmail(`MAIL_USERNAME`과 같은 주소)로 인증 코드를 요청하면 자기 자신에게 메일이 갑니다. **첫 메일은 스팸함**에 있을 수 있습니다. 코드를 입력해 인증하고, 연세대 글쓰기가 되는지 확인한 뒤 테스트 글을 삭제합니다.
4. **[서버]** **꼭** 임시 도메인을 지웁니다:
```sh
docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
DELETE FROM school_email_domains WHERE domain = 'gmail.com';
SQL
```
   `DELETE 1`이 나오면 성공입니다.
5. **[브라우저]** 테스트 계정을 탈퇴합니다. 학교 메일 해시는 `users` 표의 `school_email_hash` 칸에 저장되고 탈퇴는 회원 행 자체를 지우므로, 해시도 같이 사라집니다. 그래서 같은 Gmail로 나중에 다시 테스트할 수 있습니다.

주의: 2번~4번 사이에는 **누구나 gmail 주소로 연세대 인증을 받을 수 있습니다.** 짧게 끝내세요. 또 같은 메일은 하루 5회, 같은 계정+같은 메일은 하루 2회까지만 보낼 수 있어서 여러 번 하면 막힐 수 있습니다(위 "학교 인증 코드가 막혔다는 문의가 오면" 참고).

## 사진 저장 (S13-g6)
중고거래 사진은 AWS S3 버킷에 저장됩니다. app은 EC2에 붙은 IAM 역할(`board-ec2-role`)로 S3에 접근하므로 액세스 키가 없습니다(`.env`·저장소에 키를 적지 않습니다).
버킷 이름은 저장소가 공개라서 이 파일에도 적지 않습니다(아래 `<버킷 이름>`은 본인 값으로 바꿉니다).

바뀌는 것:
- 환경변수 3개 (`docker-compose.yml`이 `.env`에서 읽어 app에 넘김):
  - `PHOTO_STORAGE`: compose에서 기본 `s3`(빈 값이어도 `s3`로 채워짐). `local`이면 컨테이너 안 폴더에 저장하는데, 운영에는 사진 볼륨이 없어서 재배포하면 사라집니다. 보통 적지 않습니다.
  - `PHOTO_S3_BUCKET`: 버킷 이름 (**필수**, 비어 있으면 앱이 시작 때 실패).
  - `PHOTO_S3_REGION`: 기본 `ap-northeast-2`(서울). 서울이면 적지 않아도 됩니다.
- nginx(`web`) 설정: **사진 올리기 주소**(`POST /api/market-items/{id}/photos`)에만 업로드 한도 `client_max_body_size 11m`(스프링 `max-request-size`와 같음)과 응답 대기 `proxy_read_timeout 150s`를 줍니다. 한도가 없으면 nginx 기본 1MB 때문에 3MB 사진도 413입니다. 대기 150초는 S3가 느릴 때(처리 자리 대기 최대 5초 + 이미지 처리 + put 2번 각 최대 60초) nginx가 먼저 504를 내서 "서버는 저장했는데 화면은 실패"가 되는 어긋남을 막습니다. **유학생 허가서 신청 주소**(`/api/users/me/permit-verification`, 사진 최대 10MB)도 같은 11MB·150초 예외입니다(없으면 413, 이 주소의 GET·DELETE도 같은 블록을 지나가지만 값만 넉넉할 뿐 문제 없음). 나머지 API는 nginx 기본값(본문 1MB, 대기 60초) 그대로입니다. 단 `/api/chat`만 대기 75초입니다(S17-h, 서버 질문 예산 60초 < nginx 75초 < 화면 90초). 이 설정은 web 이미지 안에 있으므로 Deploy 버튼이 새 이미지를 받아 반영합니다. (S16-n2부터 이 설정은 `flutter_app/nginx.conf`, S22부터 규칙 부분은 `flutter_app/board-locations.conf`에 있고 내용은 같습니다.)

**운영에 적용하기**
1. **[콘솔]** (처음 한 번) EC2 -> 인스턴스 -> 해당 인스턴스 선택 -> 작업 -> 인스턴스 설정 -> 인스턴스 메타데이터 옵션 수정에서 "메타데이터 응답 홉 제한"이 **2 이상**인지 확인합니다. **1이면 2로 바꾸고 저장합니다.** 1이면 컨테이너 안의 앱이 역할 자격 증명을 받지 못해서 시작할 때 HeadBucket(버킷 확인) 단계에서 실패합니다. 같은 인스턴스에 역할 `board-ec2-role`이 연결돼 있는지도 봅니다(작업 -> 보안 -> IAM 역할 수정).
2. **[서버]** `~/board/.env`에 `PHOTO_S3_BUCKET=<버킷 이름>` 한 줄을 추가합니다. `PHOTO_STORAGE`·`PHOTO_S3_REGION`은 기본값이면 생략해도 됩니다.
3. **[PC]** `docker-compose.yml`이 바뀌었으므로 Deploy 버튼을 누르기 전에 위 비상용 4~5단계(compose 백업 -> `scp`)로 EC2에 먼저 복사합니다. **빼먹으면 위험합니다**: 서버의 옛 compose에는 `PHOTO_*`가 없어서 앱이 `local`로 조용히 켜집니다(오류도 없고 health도 초록). 사진이 컨테이너 안에만 쌓이다가 재배포하면 사라지고 DB 행만 남아 404가 됩니다.
4. Deploy 버튼을 누릅니다(app·web 새 이미지로 다시 뜹니다).
5. 확인:
   - **[서버]** `cd ~/board && docker compose --profile prod config | grep -c '^ *PHOTO_'` 가 `3`인지 봅니다(버킷 이름이 화면에 나오므로 `config`를 그대로 출력하지 않습니다).
   - **[서버]** `docker compose --profile prod config | grep -c 'PHOTO_S3_BUCKET: ""'` 가 `0`인지 봅니다. 위 3은 버킷 값이 비어 있어도 3이라서, 이 줄로 "버킷이 비어 있지 않은지"를 따로 봅니다(값은 화면에 나오지 않습니다).
   - **[서버]** `docker compose --profile prod exec app printenv PHOTO_STORAGE` 가 `s3`인지 봅니다. 실제로 떠 있는 컨테이너의 값이라서 scp를 빼먹어 `local`로 떨어진 경우를 잡습니다. **`s3`가 아니거나 아무것도 안 나오면**(옛 compose에는 이 변수가 아예 없어서 빈 줄이 나옴) scp가 빠진 것입니다(`s3`는 비밀이 아닙니다. 버킷 이름은 일부러 출력하지 않습니다).
   - **[서버]** `docker compose --profile prod logs --tail 100 app` 에 시작 실패 메시지가 없는지 봅니다. app 로그는 GitHub 이슈 등 공개된 곳에 붙이지 않습니다(S3 오류 메시지에 버킷 이름이 든 주소가 섞일 수 있습니다).
   - **[브라우저]** 중고거래 글에 사진을 올리고 상세에서 보이는지 확인한 뒤, **올린 사진 한 장을 지워서** 상세에서 사라지는지 봅니다(삭제 경로까지 S3로 가는지 보는 단계).
   - **[서버]** `docker compose --profile prod logs app | grep -c '사진 파일 삭제에 실패'` 가 `0`인지 봅니다. S3에서 파일 삭제가 실패해도 앱은 예외를 삼키고 대기 표에 남겨 두므로, 이 문구가 S3 삭제 권한 문제를 알려 주는 유일한 신호입니다. 0이 아니면 아래 "사진 청소부 로그"를 봅니다.
   - **[서버]** **약 10분 뒤** `docker compose --profile prod logs app | grep '사진 청소부 첫 실행'` 을 봅니다. INFO(`사진 청소부 첫 실행: n개 처리`)는 **스케줄이 돌았다는 뜻일 뿐**, S3가 정상이라는 뜻이 아닙니다(대기 표가 비어 있으면 S3를 부르지 않고, S3 삭제가 실패해도 예외를 삼켜서 INFO가 나옵니다). S3 정상 여부는 위 삭제 확인과 `사진 파일 삭제에 실패` 0건으로 봅니다.

**사진 청소부 로그** (운영 주기 10분)
- WARN `사진 청소부 첫 실행에 실패했습니다`(첫 실행이 아니면 `사진 청소부 실행에 실패했습니다`)는 대기 표를 읽고 쓰는 **DB 쪽** 문제(`runOnce`에서 예외)입니다. S3 권한 문제가 아닙니다. 다음 주기(10분 뒤)에 다시 하니, 계속 나오면 DB 상태와 앱 로그의 예외를 봅니다.
- WARN `사진 파일 삭제에 실패했습니다(대기 표에 남겨 둡니다)`는 S3 삭제 한 번이 실패한 것입니다(권한·네트워크). 같은 파일이 5번 이상 실패하면 주기마다 WARN `사진 파일 삭제가 n번째 실패했습니다. 확인이 필요합니다`가 나옵니다(n은 5, 6, 7…로 계속 올라감). 이 문구가 보이면 역할 정책의 삭제 권한을 점검합니다.

**앱이 시작하지 못할 때** (실제 로그 글자로 구분)
- 찾는 명령: **[서버]** `docker compose --profile prod logs app | grep -E 'PHOTO_S3|S3 버킷에|PhotoStorage'`. app이 계속 재시작하면 `--tail 100`에는 마지막 스택만 보일 수 있어서, 위처럼 전체 로그에서 grep합니다.

| 로그에 보이는 글자 | 뜻 | 할 일 |
|---|---|---|
| `환경변수 PHOTO_S3_BUCKET을 설정하세요` | `.env`에 버킷 이름이 없음 | `.env`에 `PHOTO_S3_BUCKET=<버킷 이름>` 추가 |
| `환경변수 PHOTO_S3_REGION을 설정하세요` | 리전이 비어 있음. compose는 `:-`로 서울을 채워 줘서 compose로 띄우면 이 경우가 생기지 않음 | compose 밖에서 띄운 경우만 해당 |
| `S3 버킷에 접근할 수 없습니다` + `Status Code: 403` | 역할은 받았지만 정책이 막음. 또는 버킷 이름 오타가 남의 버킷 이름과 겹침(버킷 이름은 전 세계에서 하나, 알려진 S3 동작 기준) | 역할 `board-ec2-role`의 정책(해당 버킷 접근 허용) 점검, `.env`의 버킷 이름 오타도 확인 |
| `S3 버킷에 접근할 수 없습니다` + `Status Code: 404` | 버킷 이름 오타(없는 버킷) | `.env`의 `PHOTO_S3_BUCKET` 확인 |
| `S3 버킷에 접근할 수 없습니다` + `Status Code: 301` 또는 `400` | 버킷과 리전이 다름 (알려진 S3 동작 기준, 이 서비스에서 실제로 확인하지는 않음) | `.env`의 `PHOTO_S3_REGION`을 버킷의 리전으로 |
| `S3 버킷에 접근할 수 없습니다` 뒤에 상태 숫자(403·404·301) 없이 `credentials`라는 영어 단어가 보임 (예: `Unable to load credentials ...`, SDK 버전마다 문장이 조금 다를 수 있음) | 자격 증명을 못 받음 | 홉 제한이 1인지, 역할이 인스턴스에 연결됐는지 |

- `PHOTO_STORAGE`에 `local`·`s3` 외의 값이면 PhotoStorage 빈이 없어 시작이 실패합니다(로그에는 `PhotoStorage`라는 스프링 영어 메시지만 나옵니다). `.env`의 값을 고칩니다.
- 고친 뒤에는 `docker compose --profile prod up -d app` (단순 `restart`는 `.env`를 다시 읽지 않습니다).

**되돌리기 주의**
- `PHOTO_STORAGE=local`로 바꾸거나 이 단계 이전의 옛 이미지로 돌아가면 S3에 있는 사진은 보이지 않습니다(404). 사진 파일은 S3에 그대로 남아 있고, 새 코드로 돌아오면 다시 보입니다.
- **조용히 local로 떨어지는 경우가 둘 있습니다.** (a) 위 "되돌리기"로 compose만 옛 파일로 돌린 경우, (b) scp를 빼먹고 Deploy를 누른 경우. 둘 다 앱은 정상으로 켜지고 health도 초록인데 사진이 컨테이너 안(local)에 쌓입니다. 위 5번의 `printenv PHOTO_STORAGE`가 `s3`인지 꼭 봅니다.
- `local`로 떠 있던 동안의 부작용:
  - 그동안 올린 사진은 `s3`로 돌아와도 계속 404입니다(파일이 컨테이너 안에만 있고, 재배포하면 사라져 DB 행만 남음).
  - 그동안 S3에 있는 사진을 지우면 로컬 삭제가 "성공"으로 처리돼서 S3 파일이 영원히 남습니다.
- `local`은 사진 볼륨이 없어서, 그 상태로 올린 사진은 재배포(컨테이너 재생성) 때 사라집니다. 운영에서는 `local`을 쓰지 않습니다.

## DB 설정에서 바꾸면 안 되는 것
- **DB 메시지 언어는 영어(공식 이미지 기본값)로 둡니다.** 서버 코드(`ConstraintViolations`)가 PostgreSQL 오류 메시지의 `constraint "..."` 글자에서 제약 이름을 꺼내 404/401로 바꿉니다. `lc_messages`를 한국어로 바꾸면 그 글자가 없어 댓글·공감 작성 중 경쟁 상황에서 500이 납니다.

## 관리자 지정·해제 (S9)
관리자는 API로 만들 수 없습니다. 운영 DB에서 SQL로만 지정합니다(회원 `role` 칸을 `USER` -> `ADMIN`).
아래 명령은 **EC2에 ssh로 접속한 화면([서버])에서** 실행합니다. PowerShell이 아닙니다(PC에서 치면 따옴표가 깨집니다).
작은따옴표가 겹치지 않도록 SQL은 `<<'SQL'` ... `SQL` 사이에 따로 적습니다(여러 줄을 통째로 붙여넣으면 됩니다). `<아이디>`는 지정할 회원의 로그인 아이디로 바꿉니다.

1. **[서버]** `cd ~/board` — 작업 폴더로 이동합니다.
2. **[서버]** 지정하기:
```sh
docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<'SQL'
UPDATE users SET role = 'ADMIN' WHERE username = '<아이디>';
SQL
```
   `UPDATE 1`이 나오면 성공입니다. `UPDATE 0`이면 아이디가 틀린 것입니다.
3. **[서버]** 확인하기(관리자만 나옵니다):
```sh
docker exec -i board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT id, username, role FROM users WHERE role = 'ADMIN';
SQL
```
4. **[브라우저]** 그 회원이 **로그아웃 -> 다시 로그인**합니다. 액세스 토큰에 role이 들어 있어서 최대 15분 묵기 때문입니다(다시 로그인하면 바로 반영, 토큰 재발급 때도 DB의 role로 갱신됩니다).
5. **해제**: 2번의 SQL을 `SET role = 'USER'`로 바꿔 똑같이 실행합니다. 서버가 관리자 권한을 쓸 때마다 DB를 다시 확인하므로 해제는 로그아웃 없이 즉시 먹힙니다.
- 이 값은 비밀이 아니지만, 실제 아이디를 이 파일에 적어 두지는 않습니다.

## Flyway(표 변경) 문제와 되돌리기
Flyway는 DB 표 구조를 바꾸는 "V 파일"(예: `V2__...sql`)을 앱이 켜질 때 순서대로 적용하는 도구입니다. 적용한 기록은 DB 안의 `flyway_schema_history` 표(기록장)에 남습니다.
앱 시작 순서는 Flyway(기록장 확인·적용) -> Hibernate 표 검사(`ddl-auto=validate`) -> 웹 서버 시작입니다. 앞 단계에서 실패하면 앱이 뜨지 못해 Deploy의 헬스체크가 약 3분 뒤 빨간불이 되고, app 컨테이너는 `restart: unless-stopped` 때문에 켜졌다 꺼지기를 반복합니다.

표시: **[서버]** = EC2에 ssh로 접속한 화면(`ec2-user@...` 프롬프트), **[PC]** = 내 컴퓨터 PowerShell(`PS C:\...` 프롬프트).

### (g) 로그 보기
- **[서버]** `cd ~/board` — 작업 폴더로 이동합니다.
- **[서버]** `docker compose --profile prod logs --tail 100 app` — app 컨테이너의 최근 로그 100줄을 봅니다.

대표 로그와 뜻 (Flyway 12.4.0 기준. 문구는 버전에 따라 조금 다를 수 있습니다):
- `Creating Schema History table "public"."flyway_schema_history" with baseline ...` — 기록장이 없어서 새로 만들고, "지금 표 상태를 1번으로 친다"고 적는 중입니다. S6-c 배포 때 한 번 나온 로그입니다. S6-c 후속부터는 baseline 설정을 꺼서 나오지 않습니다(옛 백업을 되살릴 때 아래 (e)의 일회성 절차로 켜면 다시 나옵니다).
- `Successfully baselined schema with version: 1` — 위 작업이 끝났습니다(정상, 마찬가지로 S6-c 배포 때 한 번 나왔고 S6-c 후속부터는 나오지 않습니다).
- `Found non-empty schema(s) "public" but no schema history table` — 표는 있는데 기록장이 없는 DB라서 Flyway가 거절한 것입니다(S6-c 후속부터 baseline 설정 꺼짐). 앱은 뜨지 않습니다. 옛 백업을 되살린 경우라면 아래 (e)의 일회성 baseline 절차를 따릅니다. 처음 세팅하는 완전히 빈 DB는 V1이 실행되므로 이 오류가 나지 않습니다.
- `Current version of schema "public": 1` — DB가 지금 몇 번까지 적용됐는지 알려 줍니다.
- `Schema "public" is up to date. No migration necessary.` — 새로 적용할 V 파일이 없습니다(정상).
- `Successfully applied N migration(s)` — 새 V 파일 N개를 적용했습니다(정상).
- `Schema "public" has a version (2) that is newer than the latest available migration (1) !` — DB 기록장이 실행 중인 코드보다 새롭다는 경고입니다. 새 코드 배포 뒤 S6 이미지로 돌아갔을 때 나오며 정상입니다(앱은 뜹니다). 아래 (b)를 봅니다.
- `Validate failed` — 이미 적용된 V 파일의 내용이 바뀌었거나(체크섬 불일치) 실패한 기록이 남아 있습니다. 아래 (c), (d)를 봅니다. 앱은 뜨지 않습니다.
- `Schema-validation: missing table` / `missing column` — Flyway는 통과했지만 코드(엔티티)가 기대하는 표·칸이 DB에 없습니다. Hibernate 검사 실패이고 앱은 뜨지 않습니다. 보통 필요한 V 파일을 빠뜨렸다는 뜻입니다.

### (a) 이전 이미지로 되돌리기
새 이미지가 Flyway 때문에 안 뜰 때, 서버가 옛 이미지를 쓰게 만드는 방법입니다. CI는 이미지에 `latest` 말고 커밋 40자리 SHA 태그(`seokam36/demo:<40자리 SHA>`)도 붙여 둡니다. 서버에서는 `latest`에 이미지 정리(prune)로 옛 것이 지워졌기 때문에, SHA 태그로 Docker Hub에서 다시 받아 옵니다.
1. **[PC]** 브라우저로 GitHub 저장소의 Commits 목록에서 되돌아갈 커밋(예: Flyway 도입 직전, `e66aa02`로 시작하는 커밋)을 찾아 40자리 SHA 전체를 복사합니다. 그 커밋의 CI(images 포함)가 초록이었어야 태그가 있습니다.
2. **[서버]** `cd ~/board` — 작업 폴더로 이동합니다.
3. **[서버]** `cp docker-compose.yml docker-compose.yml.bak-latest` — 지금 설정을 복사해 둡니다(나중에 원복할 때 씁니다).
4. **[서버]** `sed -i 's#image: seokam36/demo:latest#image: seokam36/demo:<SHA>#' docker-compose.yml` — app 이미지 줄만 `latest`에서 그 SHA로 바꿉니다. `<SHA>`는 1단계에서 복사한 40자리입니다. (web 이미지는 `demo-web`이라 영향 없습니다.)
5. **[서버]** `grep 'image: seokam36' docker-compose.yml` — 두 줄이 보이고 app 쪽이 `seokam36/demo:<SHA>`로 바뀌었는지 확인합니다.
6. **[서버]** `docker compose --profile prod pull app && docker compose --profile prod up -d app` — app 이미지만 받아서 다시 띄웁니다.
7. **[서버]** `docker compose --profile prod logs --tail 100 app` — 앱이 정상으로 떴는지 로그를 봅니다. 이어서 브라우저에서 `http://<퍼블릭IP>/actuator/health`가 UP인지 확인합니다.

> **주의 (꼭 읽기)**: Deploy 버튼은 서버의 `docker-compose.yml`을 바꾸지 않습니다. 위처럼 image 줄을 옛 SHA로 바꿔 두면 **다음 Deploy도 옛 SHA를 받아서**, 새 코드가 배포되지 않는데도 초록불이 뜰 수 있습니다.
> 1. 원인을 고친 새 커밋을 push하고 CI가 새 `latest` 이미지를 Docker Hub에 올릴 때까지(Actions 초록불) 기다립니다.
> 2. 그다음 **[서버]** `cd ~/board && cp docker-compose.yml.bak-latest docker-compose.yml` 로 원복하고, **[서버]** `grep 'image: seokam36' docker-compose.yml` 로 app 줄이 다시 `seokam36/demo:latest`인지 확인합니다.
> 3. 그다음에 Deploy 버튼을 누릅니다.
>
> 새 `latest`가 올라오기 전에 원복하면 문제 있던 `latest`가 다시 뜹니다. 원복을 잊으면 Deploy가 계속 옛 SHA를 받습니다.

### (b) 옛 이미지로 돌아가도 괜찮은가
Flyway 도입 전의 옛 이미지(S6 이전, `ddl-auto=update`, Flyway 없음)는 `flyway_schema_history`를 읽지도 쓰지도 않습니다. 그래서 DB에 기록장이 남아 있어도 그대로 두면 되고 무해합니다.
단, 새 V 파일이 이미 칸 삭제나 이름 변경을 적용했다면 옛 코드가 그 칸을 찾다가 깨질 수 있습니다. 이럴 땐 아래 (e)의 백업 되살리기를 고려합니다.

**S6 이미지(V1만 아는 코드)로 돌아갈 때 (V2 적용 후)**
2026-11-07에 임시 DB로 시험했습니다(S6 코드 `898019f`를 빌드해 실행). V2는 학교 표를 새로 만들고 `posts`에 칸을 더하기만 했고, 새 칸에는 기본값이 있어서 옛 코드가 깨지지 않았습니다.
- 앱이 뜹니다(health 200). 표 구조를 V1로 되돌릴 필요가 없습니다.
- 로그에 `Schema "public" has a version (2) that is newer than the latest available migration (1) !` 경고가 나오지만 정상입니다. "DB가 이 코드보다 새롭다"고 알려 줄 뿐이고 기록장은 그대로입니다.
- 가입, 로그인, 글쓰기, 목록 조회가 됩니다. 그때 쓴 글은 학교 빈칸(`school_id`), 말머리 `CHAT`으로 저장됩니다(V2 기본값 덕분).
- S6 코드는 학교·말머리를 모르므로 응답과 화면에 학교·말머리가 보이지 않습니다. 고장이 아닙니다.
- 다시 새 코드로 시작하면 정상이고 기록장은 1, 2 그대로입니다. V2를 다시 실행하지 않습니다.
- **하지 말 것**: V2를 손으로 되돌리거나(표·칸 삭제) 기록장(`flyway_schema_history`) 행을 지우지 마세요. 그러면 새 코드로 돌아올 때 V2가 다시 실행되어 "이미 있음" 오류로 앱이 못 뜰 수 있습니다.
- 아직 확인하지 못한 것: S6 코드로 기존 글 수정·삭제, 운영 서버에서의 실행.
- 이 결과는 **V2에 한정**됩니다. V3 이상이 생기면(칸 삭제·이름 변경, NOT NULL 추가 등) 그 V 파일 내용을 기준으로 옛 코드가 되는지 다시 확인해야 합니다.

**S7 이미지(V2까지 아는 코드)로 돌아갈 때 (V3 적용 후)**
V3(S8-a)는 `comments`·`post_likes` 표를 **새로 만들기만** 하고 기존 표는 건드리지 않습니다. 옛 코드는 새 표를 모르니(validate는 엔티티가 있는 표만 검사) 그대로 뜨고, 글·회원 기능도 그대로입니다. 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다. 단, 옛 코드로 글을 지우면 그 글의 댓글·공감도 DB가 같이 지웁니다(ON DELETE CASCADE). 기록장 행은 지우지 마세요(V2와 같은 이유).

**S8 이미지(V3까지 아는 코드)로 돌아갈 때 (V4·V5 적용 후)**
- V4(S9-a)는 `users`에 `role` 칸을 **더하기만** 합니다(`NOT NULL DEFAULT 'USER'`). 옛 코드의 엔티티에는 `role`이 없고 `ddl-auto=validate`는 엔티티에 없는 칸을 보지 않으므로 앱이 뜹니다. 옛 코드의 가입(INSERT)은 `role`을 안 적어도 DEFAULT로 `USER`가 들어갑니다. 옛 코드에는 관리자 기능이 없으니 관리자 화면·삭제만 안 보입니다.
- V5(S9-b)는 `reports` 표를 **새로 만들기만** 합니다. 옛 코드는 새 표를 무시합니다(V3와 같은 이유). `reports`의 외래키(FK)는 모두 삭제 동작이 있어서(글·댓글은 `ON DELETE CASCADE`, 회원은 `ON DELETE SET NULL`) 옛 코드가 글·댓글·회원을 지워도 DB가 알아서 처리하고 오류가 나지 않습니다.
- 로그에 `Schema "public" has a version (5) that is newer than the latest available migration (3) !` 경고가 나와도 정상입니다(V2·V3 때와 같음, 앱은 뜹니다).
- 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다. 기록장 행은 지우지 마세요.

**S9/UI1 이미지(V5까지 아는 코드)로 돌아갈 때 (V6 적용 후)**
- V6(S10-a)는 `school_email_domains` 표를 **새로 만들고**, `users`에 인증 정보 칸 3개(`verified_school_id`, `verified_at`, `school_email_hash`)를 **더하기만** 합니다. 세 칸 모두 NULL을 허용합니다. 옛 코드의 엔티티에는 이 표·칸이 없고 `ddl-auto=validate`는 엔티티에 없는 것을 보지 않으므로 앱이 뜹니다. 옛 코드의 가입(INSERT)도 새 칸을 안 적으면 NULL이 들어가서 문제없습니다.
- 제약(UNIQUE·FK)도 옛 코드 동작을 막지 않습니다. `school_email_hash`의 UNIQUE는 NULL끼리는 충돌하지 않아서(옛 코드로 가입한 회원은 모두 NULL) 가입이 됩니다. `users`의 FK(`verified_school_id` -> `schools`)는 "회원이 학교를 가리키는" 방향이라, 회원을 지우는 쪽에는 영향이 없습니다(학교 행은 지우지 않습니다). `school_email_domains`도 학교만 가리킵니다. 그래서 회원 삭제·글 삭제에서 FK 오류가 나지 않습니다.
- **하지만 학교 인증 제한이 사라집니다.** 옛 코드에는 인증 검사가 없어서, 인증하지 않은 회원도 아무 학교 글에 글쓰기·댓글·공감·신고를 다시 할 수 있습니다. 화면의 인증 섹션·뱃지도 보이지 않습니다. 짧게만 되돌리고 빨리 새 코드로 돌아오세요.
- 이미 인증한 정보(DB 칸)는 그대로 남아서, 새 코드로 돌아오면 인증 상태가 유지됩니다. Redis에 남은 인증 키도 해롭지 않습니다.
- 운영 `.env`에 `MAIL_*` 값이 있어도 옛 코드는 무시합니다(문제없음).
- 로그에 `Schema "public" has a version (6) that is newer than the latest available migration (5) !` 경고가 나와도 정상입니다(앱은 뜹니다).
- 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다. 기록장 행은 지우지 마세요.
- 아직 확인하지 못한 것: 실제로 S9/UI1 이미지로 돌아가 시험하지는 않았습니다. 위 내용은 V6 SQL과 코드를 읽고 판단한 것입니다.

**S10 이미지(V6까지 아는 코드)로 돌아갈 때 (V7 적용 후)**
- V7(S11-a, `src/main/resources/db/migration/V7__add_visa_reviews.sql`)은 `visa_reviews` 표를 **새로 만들고** 인덱스 3개(`visa_reviews` 2개 + `users.verified_school_id` 1개)를 **더하기만** 합니다. 기존 표의 칸은 바꾸지 않습니다. 옛 코드의 엔티티에는 이 표가 없고 `ddl-auto=validate`는 엔티티에 없는 표를 보지 않으므로 앱이 뜹니다.
- 로그에 `Schema "public" has a version (7) that is newer than the latest available migration (6) !` 경고가 나와도 정상입니다(앱은 뜹니다).
- 비자 후기 화면·API가 사라집니다. 쌓인 후기 데이터는 표에 그대로 남고, 새 코드로 돌아오면 다시 보입니다.
- **함정: 비자 후기를 쓴 회원이 탈퇴하면 500 오류가 납니다.** `visa_reviews.author_id`의 외래키(`fk_visa_reviews_author`)에는 삭제 동작(ON DELETE)이 없습니다. 새 코드(S11)의 `UserService.withdraw`는 글·댓글·비자 후기의 작성자를 먼저 NULL로 비운 뒤 회원을 지웁니다. 그런데 S10 코드의 `withdraw`는 글·댓글만 비우고 회원을 지우므로, 후기가 있는 회원은 FK 위반으로 탈퇴가 실패합니다(500). 후기가 없는 회원의 탈퇴는 정상입니다. 짧게만 되돌리고 빨리 새 코드로 돌아오세요.
- **하지 말 것**: V7을 손으로 되돌리거나(표 삭제) 기록장(`flyway_schema_history`) 행을 지우지 마세요(V2 안내와 같은 이유로, 새 코드로 돌아올 때 V7이 다시 실행되어 "이미 있음" 오류가 날 수 있습니다).
- 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다.
- 아직 확인하지 못한 것: 실제로 S10 이미지로 돌아가 시험하지는 않았습니다. 위 내용은 V7 SQL과 S10 코드(커밋 `f5d917b`의 `UserService`)를 읽고 판단한 것입니다.

**S11 이미지(V7까지 아는 코드)로 돌아갈 때 (V8 적용 후)**
- V8(S12-a, `src/main/resources/db/migration/V8__add_visa_plans.sql`)은 `visa_plans` 표를 **새로 만들기만** 합니다(기존 표·데이터는 그대로, 채울 데이터 없음). 옛 코드의 엔티티에는 이 표가 없고 `ddl-auto=validate`는 엔티티에 없는 표를 보지 않으므로 앱이 뜹니다(V3·V5·V7과 같은 이유). 로그의 `newer than the latest available migration` 경고는 정상입니다.
- `visa_plans.user_id`의 외래키(`fk_visa_plans_user`)는 `ON DELETE CASCADE`라서, 옛 코드가 회원을 지워도(탈퇴) DB가 그 회원의 일정 행을 같이 지웁니다. 옛 코드에서도 탈퇴가 깨지지 않습니다(V7 후기와 달리 함정 없음).
- 되돌려 있는 동안 회원이 등록한 일정은 표에 그대로 남아 있다가, 다시 새 이미지로 오면 그대로 보입니다. 기록장 행은 지우지 마세요(V2와 같은 이유).
- 아직 확인하지 못한 것: 실제로 S11 이미지로 돌아가 시험하지는 않았습니다. 위 내용은 V8 SQL을 읽고 판단한 것입니다.

**S12 이미지(V8까지 아는 코드)로 돌아갈 때 (V9~V13 적용 후)**
S13·S14를 한 번에 배포하면 V9~V13이 한꺼번에 적용됩니다. 아래는 V9~V13 SQL(`src/main/resources/db/migration/`)과 S12 배포 기록 커밋 `9c8b600`의 옛 코드(`Report`, `AdminReportResponse`, `AdminReportService`, `ReportRepository`, `UserService.withdraw`)를 읽고 판단한 것입니다. 실제로 돌려 본 것이 아니므로 "코드를 읽어 확인"과 "추측"을 나눠 적습니다.

코드를 읽어 확인한 것:
- 앱은 뜹니다. V9~V13은 표를 새로 만들고(`market_items`, `market_item_photos`, `photo_delete_queue`, `conversations`, `messages`) `reports`에 칸 2개(`market_item_id`, `conversation_id`)를 더합니다. 옛 엔티티에 없는 표·칸은 `ddl-auto=validate`가 보지 않습니다(V3·V5·V7·V8과 같은 이유). 로그의 `newer than the latest available migration (13) ... (8)` 경고는 정상입니다.
- **옛 코드의 글·댓글 신고 저장은 새 제약을 통과합니다.** V10·V13이 `ck_reports_target`을 "글·댓글·중고 글·대화방 중 정확히 하나"(`num_nonnulls(...) = 1`)로 바꿨습니다. 옛 `Report`는 `post_id` 또는 `comment_id` 하나만 채우고 새 두 칸은 모르니 NULL로 들어가서, 채워진 칸이 정확히 하나라 통과합니다. 같은 이유로 새 UNIQUE(`uk_reports_market_item_reporter`, `uk_reports_conversation_reporter`)도 옛 신고를 막지 않습니다(그 칸이 NULL이면 중복으로 치지 않음).
- **중고 글 신고나 대화 신고가 한 건이라도 있으면 옛 관리자 신고 목록이 500입니다.** 옛 `AdminReportResponse.from`은 "글이 있으면 글, 아니면 댓글"로 가정하고 댓글 쪽에서 `comment.getId()`를 바로 부릅니다. 중고·대화 신고는 `post_id`와 `comment_id`가 둘 다 NULL이라 `NullPointerException`이 납니다. 목록은 상태별(대기 등)로 한꺼번에 만들어서, 그 상태에 새 종류 신고가 하나만 섞여도 그 상태의 목록 전체가 열리지 않습니다. 같은 이유로 그런 신고의 기각도 500입니다(옛 `dismiss`가 기각한 뒤 같은 변환을 해서, 트랜잭션이 취소되어 기각은 반영되지 않습니다. 추측: 이 취소 동작은 코드만 보고 판단).
  - 되돌리기 전에 **[서버]** `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM reports WHERE post_id IS NULL AND comment_id IS NULL;"'` 로 새 종류 신고가 몇 건인지 봅니다(0이면 이 문제는 없습니다). 지우지는 않습니다(기록과 새 코드로 돌아온 뒤를 위해 그대로 둡니다).
- **회원 탈퇴는 깨지지 않습니다.** 새 표에서 `users`를 가리키는 외래키가 전부 삭제 동작을 가졌습니다: `market_items.seller_id`는 `ON DELETE CASCADE`, `conversations.buyer_id`·`seller_id`와 `messages.sender_id`는 `ON DELETE SET NULL`(V12). 옛 `UserService.withdraw`가 글·댓글·비자 후기 작성자만 비우고 회원을 지워도 DB가 알아서 처리합니다(V7 후기 때의 함정과 다름). 판매자가 탈퇴하면 그 사람의 중고 글이 지워지고, 글에 달린 사진 행(`market_item_photos`)과 신고도 CASCADE로 같이 지워집니다. 그 글을 가리키던 대화방은 `market_item_id`만 NULL이 되어 남습니다. `ck_conversations_buyer_not_seller`는 한쪽이 NULL이면 통과합니다.
- 중고·쪽지 화면과 API가 사라집니다. 옛 web 이미지와 옛 app 이미지를 같이 되돌리면 화면 자체가 없어지고, **새 web + 옛 app이면** 중고·쪽지 화면은 보이는데 API가 없어 "찾을 수 없다"(404) 문구가 뜹니다. 데이터(중고 글, 사진, 쪽지)는 표에 그대로 남아 있다가 새 코드로 돌아오면 다시 보입니다.
- 옛 compose로 되돌리면 사진 저장이 조용히 `local`로 바뀝니다. 위 "사진 저장 (S13-g6)"의 "되돌리기 주의"를 먼저 읽습니다.

추측(코드로 확정하지 못한 것):
- **옛 이미지에서 판매자가 탈퇴하면 S3 파일이 남을 것입니다.** 글·사진 행은 DB가 CASCADE로 지우지만, 옛 코드는 `photo_delete_queue`에 "지울 파일"을 적는 코드가 없어서(S13에서 생김) S3의 사진 파일은 아무도 지우지 않을 것으로 봅니다. 데이터베이스에서 행이 사라졌으니 나중에 새 코드로 돌아와도 그 파일 이름을 알 길이 없습니다(저장 비용만 조금 듭니다, 서비스 동작에는 영향 없음). 실제로 S3를 확인해 보지는 않았습니다.
- 옛 이미지에서 중고·대화 신고는 새로 만들 수 없고, 이미 있던 신고는 위처럼 목록만 막습니다. 그 사이 관리자가 대화 내용을 보려면 새 코드로 돌아와야 합니다.

**하지 말 것**: V9~V13을 손으로 되돌리거나(표·칸·제약 삭제, 특히 `ck_reports_target`을 옛 정의로 되돌리는 것) 기록장(`flyway_schema_history`) 행을 지우지 마세요. 새 코드로 돌아올 때 V 파일이 다시 실행되어 "이미 있음" 오류가 나거나, 이미 들어간 중고·대화 신고 행이 옛 제약에 걸려 마이그레이션이 실패할 수 있습니다. 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다.

아직 확인하지 못한 것: 실제로 S12 이미지로 돌아가 시험하지는 않았습니다. 위 내용은 V9~V13 SQL과 `9c8b600`의 옛 코드를 읽고 판단한 것입니다(신고 목록 500은 코드상 확실하지만 실행으로 보지는 않았습니다).

**S18 이미지(V13까지 아는 코드)로 돌아갈 때 (V14 적용 후)**
S20을 배포하면 V14가 적용됩니다. 아래는 V14 SQL(`src/main/resources/db/migration/V14__*.sql`)과 S20 코드를 읽고 판단한 것이고, 실제로 S18 이미지로 돌아가 시험하지는 않았습니다. 순서(compose 백업 사용)는 위 "S20 되돌리기"에 있습니다.
- 앱은 뜹니다. V14는 `permit_requests`·`school_name_aliases` 표를 **새로 만들기만** 하고 기존 표·칸은 건드리지 않습니다(S20-f1도 DB 표·칸은 그대로 둠: `school_email_domains`, `users.school_email_hash`는 남아 있음). `ddl-auto=validate`는 엔티티에 있는 표·칸만 보므로 S18 코드가 새 표를 모른 채로 뜹니다(V3·V5·V7·V8과 같은 이유). 로그의 `newer than the latest available migration (14) ... (13)` 경고는 정상입니다.
- 새 표의 외래키는 모두 삭제 동작이 있거나 "신청이 회원·학교를 가리키는" 방향입니다: `permit_requests.user_id`는 `ON DELETE CASCADE`, `decided_by`는 `ON DELETE SET NULL`, 학교 행은 지우지 않습니다. S18 코드가 회원을 탈퇴시켜도 FK 오류가 나지 않습니다(V7 후기 때의 함정 없음). 다만 신청 행이 사라질 때 S3 사진 이름을 아무도 모르게 되므로 사진은 S3 수명 규칙(14일)이 지웁니다.
- 허가서로 승인된 회원은 `users.verified_school_id`가 채워져 있어 S18 코드에서도 인증 회원으로 보입니다. 학교 메일 인증 화면·메일 발송은 S18 코드로 돌아오며, 메일을 실제로 보내려면 `MAIL_*` 환경변수(옛 compose 백업 + 서버 `.env`)가 필요합니다.
- 특별한 절차 없이 (a)대로 이미지만 바꾸면 됩니다(단, compose는 위 "S20 되돌리기"처럼 백업을 바탕으로). 기록장 행과 V14 표는 지우지 마세요(V2와 같은 이유).

### (c) V2 이상이 적용 중 실패했을 때
PostgreSQL은 표 변경(DDL)도 트랜잭션이라, V 파일 하나가 중간에 실패하면 그 파일의 표 변경은 통째로 원상복구됩니다(반쯤 바뀐 채로 남지 않음). 먼저 기록장 상태를 확인합니다.
- **[서버]** `cd ~/board` — 작업 폴더로 이동합니다.
- **[서버]** `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'` — 어떤 번호까지 적용됐는지 봅니다(비밀번호는 필요 없습니다).
- `success`가 `f`(false)인 행이 **있을 때만** 아래를 합니다. 실패 기록 때문에 Flyway가 다음 시작을 거부하기 때문입니다. **[서버]** `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DELETE FROM flyway_schema_history WHERE success = false;"'` — 실패 기록 행만 지웁니다(성공한 기록은 건드리지 않습니다).
- 그다음 원인을 고친 새 커밋을 push하고, CI가 초록이 되면 Deploy를 다시 실행합니다. (옛 이미지로 임시 복구 중이라면 위 (a)의 주의 박스 순서를 따릅니다.)

### (d) 체크섬 불일치 (이미 적용된 V 파일을 고쳤을 때)
Flyway는 적용한 V 파일의 지문(체크섬)을 기록해 두고, 다음 시작 때 파일이 바뀌었는지 비교합니다. 이미 적용된 V 파일을 수정하면 `Validate failed`로 앱이 뜨지 않습니다.
- 대처: 그 V 파일을 **원래 내용으로 되돌린** 이미지를 배포합니다(코드를 되돌려 push하고 CI 초록을 확인한 뒤 Deploy).
- **서버 DB는 고치지 않습니다.** 기록장을 손으로 고쳐 맞추려 하지 않습니다.
- 바꾸고 싶은 내용은 **새 번호의 V 파일**(예: 다음 번호 `V3__...sql`)로 만듭니다. 한 번 적용된 V 파일은 절대 고치지 않는 것이 규칙입니다.

### (e) 백업과 기록장
기록장(`flyway_schema_history`)이 백업 안에 있는지가 중요합니다.
- S6-c 두 번째 Deploy부터 만들어진 백업에는 기록장이 있습니다. 위 "되살리기" 1~4단계를 그대로 쓰면 됩니다(되살린 DB와 기록장이 서로 맞습니다).
- S6-c 첫 Deploy 백업(`db-20261105-133326.sql`)과 그 이전 백업은 기록장이 없습니다(첫 Deploy 백업은 Flyway 앱이 뜨기 직전에 만들어졌기 때문입니다). 그냥 되살리면 앱이 시작되지 않습니다(Flyway가 `Found non-empty schema(s) ... but no schema history table`로 거절). 이때는 아래 일회성 baseline 절차를 씁니다.

**일회성 baseline 절차 (기록장 없는 옛 백업을 되살릴 때만)**
비유하면, 기록장이 없는 DB는 "이미 지어진 건물인데 공사 일지가 없는 상태"입니다. 일지 맨 앞에 "1번 공사까지 끝남"이라고 한 번만 적어 주면(baseline) Flyway가 거절하지 않습니다. 이 설정은 `docker-compose.override.yml`이라는 임시 파일로 켭니다(compose가 `docker-compose.yml`에 자동으로 합쳐서 읽으므로 `.env`와 `docker-compose.yml`은 건드리지 않습니다). baseline 버전은 기본값 1이라 V1과 맞습니다.
1. **[서버]** 위 "되살리기"의 1~3단계(앱 멈춤 -> 스키마 비움 -> 백업 넣기)를 그대로 합니다. 4단계는 하지 않습니다.
2. **[서버]** `cd ~/board && printf 'services:\n  app:\n    environment:\n      SPRING_FLYWAY_BASELINE_ON_MIGRATE: "true"\n' > docker-compose.override.yml` — baseline을 켜는 임시 파일을 만듭니다.
3. **[서버]** `docker compose --profile prod up -d app` — 앱을 띄웁니다. 이어서 `docker compose --profile prod logs --tail 100 app`에서 `Successfully baselined schema with version: 1`이 보이는지, `http://<퍼블릭IP>/actuator/health`가 UP인지 확인합니다.
4. **[서버]** `rm docker-compose.override.yml && docker compose --profile prod up -d app` — 임시 파일을 지우고 앱을 다시 띄워 baseline을 끕니다. 기록장이 이미 생겼으므로 이제는 필요 없습니다.
5. **[서버]** `docker compose --profile prod config | grep -c BASELINE` — 결과가 `0`이면 baseline이 꺼진 것입니다.

주의:
- override 파일을 지우지 않으면, Deploy 버튼은 compose 파일을 건드리지 않으므로 baseline이 계속 켜진 채로 남습니다. 4~5단계를 꼭 합니다.
- 백업의 표 구조가 V1과 같을 때만 맞습니다. 즉 S5-f 배포 이후부터 S6-c 첫 Deploy까지의 백업만 해당합니다. 그 전 백업(`nickname` 칸이 없음)은 baseline 뒤 Hibernate 검사에서 `missing column`으로 막히므로 쓰면 안 됩니다. 이 경우에도 서버 DB를 직접 고치지 말고 위 조건에 맞는 다른 백업을 고릅니다.
