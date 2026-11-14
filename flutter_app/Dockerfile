# Flutter 웹 화면을 운영과 같은 nginx 이미지에 담는다.
# 이미지 안에서 Flutter SDK를 받아 빌드하지 않는다(무겁고 느려서).
# 먼저 밖(내 컴퓨터/CI)에서 `flutter build web --web-content-hash` 를 실행해 build/web 을 만들어 둔다.
# 주의: build/web 이 없으면 아래 COPY에서 "/build/web: not found"로 실패한다.
#       반대로 옛 빌드가 남아 있으면 그게 조용히 담기므로, 항상 빌드 "직후"에 docker build 한다.
FROM nginx:1.30-alpine

# 빌드 결과(화면 파일)를 nginx가 서빙하는 폴더로 복사
COPY build/web /usr/share/nginx/html
# 우리 설정이 nginx 기본 설정(default.conf)을 대체한다 (옛 React 이미지의 frontend/Dockerfile과 같은 위치, S16-p에서 삭제)
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
