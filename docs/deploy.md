# 배포 방법 (EC2, nginx 컨테이너)
브라우저 -> EC2 80번 -> nginx(`web` 컨테이너). 화면은 **Flutter 웹**(S16-n2부터, 아래 "웹 화면: Flutter로 교체 (S16-n2)" 참고)이고, nginx가 화면 파일을 주고,
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

## 웹 화면: Flutter로 교체 (S16-n2)
운영 `web` 이미지가 React에서 **Flutter 웹**(`flutter_app/`)으로 바뀌었습니다. `/v2` 같은 별도 주소 없이 통째로 교체합니다.
- 이미지 이름은 그대로 `seokam36/demo-web`(`:latest`, `:<커밋 SHA>`)이라 **EC2의 `docker-compose.yml`을 바꿀 필요가 없습니다**(포트 80, 볼륨 없음, healthcheck 없음 - Flutter 이미지와 그대로 맞음. Deploy 워크플로의 헬스체크는 `/actuator/health`를 nginx 경유로 부르는데 Flutter nginx.conf에도 같은 블록이 있습니다).
- 만드는 방법: CI의 `flutter` 작업이 `flutter build web --web-content-hash`로 `build/web`을 만들고 e2e까지 통과시킨 뒤, 그 결과를 `images` 작업이 받아 `flutter_app/Dockerfile`(빌드 없이 COPY만)로 이미지에 담습니다. 그래서 Flutter 검사가 실패하면 이미지도 올라가지 않습니다.
- nginx 설정(`flutter_app/nginx.conf`)은 React 때와 같은 `/api` 전달(사진 올리기 `client_max_body_size 11m`·`proxy_read_timeout 150s` 포함, `/api/chat`만 75초(S17-h), **허가서 신청 주소 `/api/users/me/permit-verification`도 같은 11m·150s**(S20, 없으면 1MB 넘는 허가서 사진이 413))과 `/actuator/health` 열기, `/api/actuator` 404를 갖습니다. 달라진 점: 해시 붙은 파일(`main.dart.<해시>.js`)만 1년 immutable 캐시, `index.html` 등 나머지는 `no-cache`, 없는 `/assets/...`·`/canvaskit/...`은 404, 그 밖의 주소는 `index.html`(딥링크), JSON도 gzip 압축.
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
- nginx(`web`) 설정: **사진 올리기 주소**(`POST /api/market-items/{id}/photos`)에만 업로드 한도 `client_max_body_size 11m`(스프링 `max-request-size`와 같음)과 응답 대기 `proxy_read_timeout 150s`를 줍니다. 한도가 없으면 nginx 기본 1MB 때문에 3MB 사진도 413입니다. 대기 150초는 S3가 느릴 때(처리 자리 대기 최대 5초 + 이미지 처리 + put 2번 각 최대 60초) nginx가 먼저 504를 내서 "서버는 저장했는데 화면은 실패"가 되는 어긋남을 막습니다. **유학생 허가서 신청 주소**(`/api/users/me/permit-verification`, 사진 최대 10MB)도 같은 11MB·150초 예외입니다(없으면 413, 이 주소의 GET·DELETE도 같은 블록을 지나가지만 값만 넉넉할 뿐 문제 없음). 나머지 API는 nginx 기본값(본문 1MB, 대기 60초) 그대로입니다. 단 `/api/chat`만 대기 75초입니다(S17-h, 서버 질문 예산 60초 < nginx 75초 < 화면 90초). 이 설정은 web 이미지 안에 있으므로 Deploy 버튼이 새 이미지를 받아 반영합니다. (S16-n2부터 이 설정은 `flutter_app/nginx.conf`에 있고 내용은 같습니다.)

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
