# 배포 방법 (EC2, nginx 컨테이너)
브라우저 -> EC2 80번 -> nginx(`web` 컨테이너). nginx가 화면 파일을 주고,
`/api/...` 는 `/api`를 떼고 백엔드(`app:8080`)로, `/actuator/health` 는 그대로 넘깁니다.
화면과 API 주소가 같아서 CORS 설정이 필요 없습니다 (EC2 `.env`에 `CORS_ALLOWED_ORIGINS`는 넣지 않아도 됩니다).

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
4. 초록색이면 끝입니다. 확인용 주소는 `http://<퍼블릭IP>/`. 다 쓰고 나면 EC2를 콘솔에서 중지합니다.

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
- DB 비밀번호를 바꿀 때: `.env`의 `POSTGRES_PASSWORD`만 바꾸면 이미 만들어진 DB에는 적용되지 않습니다(처음 만들 때만 읽음).
  EC2 `~/board`에서 DB 안의 비밀번호와 `.env`를 같은 값으로 함께 바꿉니다. 값은 서버에서 만들고 화면에 출력하지 않습니다.
  - `set -a && . ./.env && set +a && NEWPW=$(openssl rand -hex 24)`
  - `docker exec board-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "ALTER USER \"$POSTGRES_USER\" PASSWORD '$NEWPW';"`
  - `sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEWPW/" .env && unset NEWPW POSTGRES_PASSWORD`
  - 떠 있는 app은 기존 연결로 계속 돌지만, 새로 연결할 때는 새 값이 필요하므로 이어서 `docker compose --profile prod up -d`로 다시 띄웁니다.
- 주의: S4부터 `docker-compose.yml`이 바뀌었습니다(위 환경변수 전달). S4를 처음 배포할 때는 변경된 `docker-compose.yml`을 EC2의 `~/board`로 먼저 `scp`해야 합니다.
- 백엔드 이미지 `seokam36/demo:latest`가 Docker Hub에 올라가 있어야 합니다.
- 퍼블릭 IP는 인스턴스를 시작할 때마다 바뀝니다 (탄력적 IP를 쓰지 않는 경우). 아래 `<퍼블릭IP>`는 그때그때 확인한 값으로 바꿉니다.

1. (개발 PC) 이미지 빌드 (빌드 컨텍스트는 `frontend/`)
   - `docker build -t seokam36/demo-web:latest frontend`
   - 백엔드 코드도 바뀌었다면 저장소 루트에서 `docker build -t seokam36/demo:latest .`
   - 개발 PC와 EC2(t3.micro, x86_64)의 아키텍처가 다르면(예: Apple Silicon) `--platform linux/amd64`를 붙입니다.
   - API 주소는 빌드 인자 `VITE_API_BASE_URL`(기본값 `/api`)로 바뀝니다. 보통은 바꿀 필요가 없습니다.
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

되돌리기: 문제가 생기면 EC2에서 백업 파일을 되돌리고 다시 실행합니다.
- `cd ~/board && cp docker-compose.yml.bak-<날짜> docker-compose.yml && docker compose --profile prod up -d --remove-orphans`
- `--remove-orphans`: 백업 파일에 없는 컨테이너(예: `board-web`)를 같이 내립니다. 없으면 남은 컨테이너가 80번 포트를 붙잡아 app이 시작하지 못할 수 있습니다.
- 되돌리는 것은 compose 설정뿐입니다. 이미지는 `latest` 태그라 push할 때 Docker Hub의 옛 이미지가 덮어써지므로, 되돌려도 새 이미지가 그대로 쓰입니다.
  이미지까지 되돌리고 싶다면 아래 "Flyway(표 변경) 문제와 되돌리기"의 (a) "이전 이미지로 되돌리기"를 따릅니다(CI가 커밋마다 40자리 SHA 태그를 남겨 두므로 미리 태그를 붙여 둘 필요가 없습니다).

## 메일 발송 (S10)
학교 메일 인증 코드를 실제로 보내는 설정입니다. 값은 모두 EC2 `~/board/.env`에만 두고 이 파일·저장소에는 적지 않습니다.

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

**학교 인증 코드가 막혔다는 문의가 오면** (운영자 메모)
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

**학교 메일 없이 운영에서 인증 테스트하기** (S10-h 배포 때 실제로 한 방법)
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
