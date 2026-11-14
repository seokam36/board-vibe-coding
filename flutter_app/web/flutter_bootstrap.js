{{flutter_js}}
{{flutter_build_config}}

// 앱 시작 설정 (flutter build가 위 두 줄 자리를 채운다)
// - canvasKitBaseUrl: 그림 그리는 엔진(canvaskit)을 구글 CDN이 아니라 우리 서버(canvaskit/ 폴더)에서 받는다.
//   빌드 옵션을 빠뜨려도 조용히 구글로 돌아가지 않게 설정을 소스에 남겼다.
// - serviceWorkerSettings는 일부러 주지 않는다: 서비스워커 등록을 쓰지 않는다(Flutter 3.50 기준 deprecated).
_flutter.loader.load({
  config: {
    canvasKitBaseUrl: "canvaskit/",
  },
});
