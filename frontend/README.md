# 게시판 프론트엔드

React + TypeScript (Vite) 화면입니다.

## 실행 방법
1. 백엔드를 먼저 띄웁니다 (저장소 루트에서 `docker compose up -d`, `./gradlew bootRun`) -> http://localhost:8080
2. `npm install`
3. `npm run dev`
4. 브라우저에서 http://localhost:5173 접속

## 환경변수
- `VITE_API_BASE_URL`: 백엔드 주소. 비워두면 `http://localhost:8080`을 씁니다.
  바꾸려면 `.env.example`을 `.env.local`로 복사해서 값을 채우세요.
- 이 값은 빌드(또는 dev 서버 시작) 시점에 고정됩니다. 바꾸면 `npm run dev`를 다시 시작하세요.
  배포 빌드 때는 반드시 실제 백엔드 주소를 넣어야 합니다 (빠뜨리면 사용자 PC의 `localhost:8080`으로 요청합니다).
