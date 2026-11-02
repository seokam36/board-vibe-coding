# 게시판 프론트엔드

React + TypeScript (Vite) 화면입니다.

## 실행 방법
1. 백엔드를 먼저 띄웁니다 (저장소 루트에서 `docker compose up -d`, `./gradlew bootRun`) -> http://localhost:8080
2. `npm install`
3. `npm run dev`
4. 브라우저에서 http://localhost:5173 접속

## 환경변수
- `VITE_API_BASE_URL`: 백엔드 주소. 로컬 개발에서는 비워두면 기본값 `http://localhost:8080`을 씁니다.
  바꾸려면 `.env.example`을 `.env.local`로 복사해서 값을 채우세요.
- 이 값은 빌드(또는 dev 서버 시작) 시점에 고정됩니다. 바꾸면 `npm run dev`를 다시 시작하세요.
- 배포 이미지를 빌드할 때는 Dockerfile이 `VITE_API_BASE_URL=/api`를 자동으로 넣습니다 (보통 바꿀 필요 없음).
  자세한 내용은 아래 배포 방법을 보세요.

## 배포 방법 (EC2, nginx 컨테이너)
브라우저 -> EC2 80번 -> nginx(`web` 컨테이너). nginx가 화면 파일을 주고,
`/api/...` 는 `/api`를 떼고 백엔드(`app:8080`)로, `/actuator/health` 는 그대로 넘깁니다.
화면과 API 주소가 같아서 CORS 설정이 필요 없습니다 (EC2 `.env`에 `CORS_ALLOWED_ORIGINS`는 넣지 않아도 됩니다).

EC2는 이미지를 받아 실행만 합니다. 저장소를 clone할 필요 없이, EC2에는 `docker-compose.yml` 하나만 복사해 두면 됩니다.

전제:
- EC2의 `~/board` 폴더에 `.env`가 있고 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` 값이 들어 있어야 합니다.
  `.env`는 서버에만 두고, 복사하거나 커밋하지 않습니다.
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
   - SSH(22)는 내 PC IP만 허용하므로 브라우저의 EC2 Instance Connect는 안 되고, PC 터미널의 `ssh`를 씁니다.
   - 집 IP가 바뀌면 보안 그룹의 SSH 소스를 "내 IP"로 다시 저장합니다.
4. (개발 PC) EC2의 기존 compose 파일을 백업합니다 (되돌리기용, `<날짜>`는 예: 20260101)
   - `ssh -i ~/.ssh/demo-key.pem ec2-user@<퍼블릭IP> 'cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-<날짜>'`
5. (개발 PC, 저장소 루트에서) 새 compose 파일을 EC2로 복사합니다.
   - `scp -i ~/.ssh/demo-key.pem docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
6. (EC2) 접속해서 새 이미지로 실행합니다.
   - `ssh -i ~/.ssh/demo-key.pem ec2-user@<퍼블릭IP>`
   - `cd ~/board && docker compose --profile prod pull && docker compose --profile prod up -d`
   - `docker ps`로 `board-web`, `board-app`, `board-postgres` 세 컨테이너가 떠 있는지 확인합니다.
7. 브라우저에서 아래를 확인합니다.
   - `http://<퍼블릭IP>/`: 목록 화면이 뜨는지
   - `http://<퍼블릭IP>/posts/1`: 새로고침해도 JSON이 아니라 화면이 뜨는지
   - `http://<퍼블릭IP>/api/posts`: JSON 배열이 오는지 (health는 app만 살아 있어도 UP이라 `/api` 전달까지는 확인하지 못합니다)
   - `http://<퍼블릭IP>/actuator/health`: UP인지
   - `http://<퍼블릭IP>:8080`: 접속되지 않는지 (app은 외부에 열지 않습니다)

   `/api/posts`가 502이면 app이 부팅 중일 수 있으니 잠시 후 다시 시도하고,
   계속되면 `docker compose --profile prod ps`로 app 상태를 보고 `docker compose --profile prod logs app`으로 오류를 확인합니다.

되돌리기: 문제가 생기면 EC2에서 백업 파일을 되돌리고 다시 실행합니다.
- `cd ~/board && cp docker-compose.yml.bak-<날짜> docker-compose.yml && docker compose --profile prod up -d --remove-orphans`
- `--remove-orphans`: 백업 파일에 없는 컨테이너(예: `board-web`)를 같이 내립니다. 없으면 남은 컨테이너가 80번 포트를 붙잡아 app이 시작하지 못할 수 있습니다.
- 되돌리는 것은 compose 설정뿐입니다. 이미지는 `latest` 태그라 push할 때 Docker Hub의 옛 이미지가 덮어써지므로, 되돌려도 새 이미지가 그대로 쓰입니다.
  (이미지까지 되돌리고 싶다면 push 전에 옛 이미지에 다른 태그를 붙여 두어야 합니다.)
