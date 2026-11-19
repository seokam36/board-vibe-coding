// 서버(백엔드) 주소를 정하는 단 한 곳. 다른 파일에서 '/api' 같은 글자를 직접 쓰지 않는다.
//
// - 웹: 기본값 '/api'. 같은 출처(화면과 같은 주소)로 보내서, 개발 서버 프록시나 nginx가 백엔드로 전달한다.
// - 앱(안드로이드): 상대 주소를 못 쓰므로 빌드할 때 절대 주소를 넣는다.
//     flutter build apk --dart-define=API_BASE_URL=https://<사이트주소>/api
// - 규칙: 끝에 '/'를 붙이지 않는다. ('.../api/' 가 아니라 '.../api'). 뒤에 '/market-items/...'를 이어 붙이기 때문.
const String apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: '/api');

// 서버 주소가 앱(비웹)에서 쓸 수 있는 모양인지 검사한다. 문제가 있으면 안내 문장, 괜찮으면 null.
// - 웹은 '/api' 상대 주소가 정상이라 검사하지 않는다(isWeb: true).
// - 앱은 'http://' 또는 'https://'로 시작하는 절대 주소여야 하고(빌드 때 --dart-define을 빠뜨리면 '/api'가 남는다), 끝에 '/'가 없어야 한다.
// - 릴리스(isRelease) 빌드는 https만 허락한다. 통신 라이브러리(dio, dart:io)가 안드로이드의 network security 정책을
//   안 따를 수 있어서, 릴리스 앱에 http 주소가 들어가면 비밀번호·쿠키가 암호화 없이 나갈 수 있다.
//   (http는 디버그 빌드의 에뮬레이터·USB 개발용으로만 쓴다)
String? apiBaseUrlProblem(String url, {required bool isWeb, required bool isRelease}) {
  if (isWeb) return null;
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    return '서버 주소가 설정되지 않았습니다.\n빌드 때 --dart-define=API_BASE_URL=... 를 넣어 주세요.';
  }
  if (url.endsWith('/')) {
    return '서버 주소 끝에 "/"가 있습니다.\nAPI_BASE_URL 끝의 "/"를 빼고 다시 빌드해 주세요.';
  }
  if (isRelease && !url.startsWith('https://')) {
    return '릴리스 앱은 https 주소만 쓸 수 있습니다.\nAPI_BASE_URL을 https://... 로 바꿔 다시 빌드해 주세요.';
  }
  return null;
}
