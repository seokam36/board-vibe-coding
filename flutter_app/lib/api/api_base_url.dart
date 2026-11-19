// 서버(백엔드) 주소를 정하는 단 한 곳. 다른 파일에서 '/api' 같은 글자를 직접 쓰지 않는다.
//
// - 웹: 기본값 '/api'. 같은 출처(화면과 같은 주소)로 보내서, 개발 서버 프록시나 nginx가 백엔드로 전달한다.
// - 앱(안드로이드): 상대 주소를 못 쓰므로 빌드할 때 절대 주소를 넣는다.
//     flutter build apk --dart-define=API_BASE_URL=https://<사이트주소>/api
// - 규칙: 끝에 '/'를 붙이지 않는다. ('.../api/' 가 아니라 '.../api'). 뒤에 '/market-items/...'를 이어 붙이기 때문.
const String apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: '/api');
