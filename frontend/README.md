# 게시판 프론트엔드

React + TypeScript (Vite) 화면입니다.

## 실행 방법
1. 백엔드를 먼저 띄웁니다 (저장소 루트에서 `docker compose up -d`, `./gradlew bootRun`) -> http://localhost:8080
2. `npm install`
3. `npm run dev`
4. 브라우저에서 http://localhost:5173 접속

## 환경변수
- `VITE_API_BASE_URL`: API 주소. 비워두면 기본값 `/api`를 씁니다. 로컬 개발에서는 Vite 프록시(`vite.config.ts`)가
  `/api` 요청을 `http://localhost:8080`으로 전달합니다 (같은 출처라 로그인 쿠키·CSRF가 그대로 동작).
  바꾸려면 `.env.example`을 `.env.local`로 복사해서 값을 채우세요.
- 주의: `http://localhost:8080`처럼 다른 출처로 바꾸면 브라우저가 로그인 쿠키·CSRF 헤더를 붙이지 않아 글쓰기·로그인이 실패합니다. 로컬에서는 기본값 `/api`를 그대로 쓰세요.
- 이 값은 빌드(또는 dev 서버 시작) 시점에 고정됩니다. 바꾸면 `npm run dev`를 다시 시작하세요.
- 배포 이미지를 빌드할 때는 Dockerfile이 `VITE_API_BASE_URL=/api`를 자동으로 넣습니다 (보통 바꿀 필요 없음).
  배포 방법은 저장소의 [docs/deploy.md](../docs/deploy.md)를 보세요.
