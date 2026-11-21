// 앱(안드로이드)의 푸시 알림(FCM) 도구 입구. S24-d.
// 웹은 푸시를 쓰지 않아서(웹 푸시는 나중 단계), 웹 빌드에는 Firebase 코드가 섞이지 않게 해야 한다.
// 그래서 lib/common/app_photo_picker.dart 와 같은 조건부 import 방식을 쓴다:
// dart.library.io 가 있는 곳(앱·VM)일 때만 app_push_io.dart(Firebase 사용)를, 웹이면 app_push_stub.dart(아무것도 안 함)를 가져온다.
import 'app_push_stub.dart' if (dart.library.io) 'app_push_io.dart' as platform;

// 알림 권한 상태: 허용 / 거부 / 아직 안 물어봄
enum PushPermission { granted, denied, notDetermined }

// 푸시 도구의 모양. 진짜 구현(app_push_io.dart)과 테스트용 가짜가 이 모양을 따른다.
// 이 모양에는 Firebase 글자가 하나도 없다 → push_registrar.dart 같은 순수 로직은 Firebase를 몰라도 된다.
abstract class AppPush {
  // 푸시를 쓸 준비. 성공하면 true, 실패(설정 파일 없음 등)면 false (앱은 죽지 않고 푸시만 꺼진 채로 돈다)
  Future<bool> init();

  // 지금 권한 상태만 본다 (사용자에게 묻지 않는다)
  Future<PushPermission> permission();

  // 권한 팝업을 띄워 묻는다. S24-e의 "알림 켜기" 버튼에서만 부른다
  Future<PushPermission> requestPermission();

  // 이 기기의 푸시 주소(기기 토큰). 못 얻으면 null
  Future<String?> getToken();

  // 기기 토큰이 새로 바뀔 때마다 새 토큰이 흘러온다
  Stream<String> get onTokenRefresh;
}

// 앱이면 Firebase를 쓰는 도구를, 웹이면 아무것도 안 하는 도구를 돌려준다.
// main.dart의 앱(비웹) 갈래에서 BoardApp에 끼워 넣는다.
AppPush createAppPush() => platform.createPlatformPush();
