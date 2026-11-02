# ---------- 1단계: 화면 빌드 (Node) ----------
# 빌드 도구만 필요하므로 이 단계는 최종 이미지에 들어가지 않는다
FROM node:24-alpine AS build
WORKDIR /app

# 의존성 파일을 먼저 복사: 소스만 바뀌면 npm ci 결과를 캐시에서 재사용해 빌드가 빨라진다
COPY package.json package-lock.json ./
RUN npm ci

# 소스 복사 후 빌드 (tsc -b && vite build -> 결과는 dist 폴더)
COPY . .

# API 주소 (빌드 시점에 번들에 박힌다. 비밀 값 아님)
# nginx가 /api 로 들어온 요청을 백엔드에 넘겨주므로 기본값은 /api
# Vite는 셸 환경변수를 .env 파일보다 우선하므로 ENV로 넘기면 된다
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---------- 2단계: 실행 (nginx) ----------
# 완성된 정적 파일만 담아 이미지를 작게 유지
FROM nginx:1.30-alpine

# 빌드 결과(화면 파일)를 nginx가 서빙하는 폴더로 복사
COPY --from=build /app/dist /usr/share/nginx/html
# 우리 설정이 nginx 기본 설정(default.conf)을 대체한다
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
