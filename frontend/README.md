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

전제: EC2에 이 저장소가 clone되어 있고, 그 폴더의 `.env`에 `POSTGRES_*` 값이 있어야 합니다.
또한 백엔드 이미지 `seokam36/demo:latest`가 Docker Hub에 올라가 있어야 합니다.

1. 개발 PC에서 이미지 빌드 (빌드 컨텍스트는 `frontend/`)
   - `docker build -t seokam36/demo-web:latest frontend`
   - 개발 PC와 EC2(t3.micro, x86_64)의 아키텍처가 다르면(예: Apple Silicon) `--platform linux/amd64`를 붙입니다.
   - API 주소는 빌드 인자 `VITE_API_BASE_URL`(기본값 `/api`)로 바뀝니다. 보통은 바꿀 필요가 없습니다.
2. Docker Hub에 올리기: `docker login` 후 `docker push seokam36/demo-web:latest`
3. EC2 보안 그룹 인바운드에 80(HTTP)이 열려 있는지 확인합니다. 예전에 8080을 열었다면 이제 닫아도 됩니다 (app은 외부 접근 안 함).
4. EC2 접속: `ssh -i ~/.ssh/demo-key.pem ec2-user@<퍼블릭IP>` (퍼블릭 IP는 인스턴스를 시작할 때마다 바뀝니다)
5. EC2에서 `git pull` 후 `docker compose --profile prod pull && docker compose --profile prod up -d`
6. 브라우저에서 `http://<퍼블릭IP>` 접속. `/api/posts`가 502이면 app이 부팅 중일 수 있으니 잠시 후 다시 시도하고,
   계속되면 `docker compose --profile prod ps`로 app 상태를 보고 `docker compose --profile prod logs app`으로 오류를 확인합니다.
