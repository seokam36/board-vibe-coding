// 앱용: firebase_core·firebase_messaging으로 FCM(구글 푸시 우체국)을 쓴다.
// Firebase 패키지는 이 파일에서만 가져온다 (웹 빌드에 섞이지 않게).
// 토큰은 개인 기기의 주소라서 로그에 남기지 않는다.
// getToken·onTokenRefresh는 firebase_messaging 17에서 deprecated(새 방식 register/onRegistered는 FID라는 다른 종류의 주소를 줌).
// 서버(S24-b)는 FCM HTTP v1의 token 칸(예전 방식 FCM 토큰)으로 보내므로, 그 토큰을 주는 예전 메서드를 일부러 계속 쓴다.
// ignore_for_file: deprecated_member_use
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';

import 'app_push.dart';

class FirebaseAppPush implements AppPush {
  bool _ready = false; // init이 성공했을 때만 true. 아니면 모든 메서드가 "푸시 없음"처럼 동작한다

  @override
  Future<bool> init() async {
    try {
      // 옵션 없이 부른다: 안드로이드는 google-services.json(빌드 때 들어감)에서 설정을 읽는다
      await Firebase.initializeApp();
      _ready = true;
    } catch (_) {
      // 설정 파일이 없거나 기기에 구글 서비스가 없어도 앱은 떠야 한다 (오류 내용은 남기지 않는다)
      _ready = false;
    }
    return _ready;
  }

  @override
  Future<PushPermission> permission() async {
    if (!_ready) return PushPermission.denied;
    try {
      final settings = await FirebaseMessaging.instance.getNotificationSettings();
      return _toPermission(settings.authorizationStatus);
    } catch (_) {
      return PushPermission.denied;
    }
  }

  @override
  Future<PushPermission> requestPermission() async {
    if (!_ready) return PushPermission.denied;
    try {
      // 안드로이드 13+에서 "알림을 보내도 될까요?" 팝업이 뜬다
      final settings = await FirebaseMessaging.instance.requestPermission();
      return _toPermission(settings.authorizationStatus);
    } catch (_) {
      return PushPermission.denied;
    }
  }

  @override
  Future<String?> getToken() async {
    if (!_ready) return null;
    try {
      return await FirebaseMessaging.instance.getToken();
    } catch (_) {
      return null;
    }
  }

  @override
  Stream<String> get onTokenRefresh =>
      _ready ? FirebaseMessaging.instance.onTokenRefresh : const Stream<String>.empty();

  // Firebase의 권한 상태를 우리 3가지 상태로 줄인다 (임시 허용도 허용으로 본다)
  PushPermission _toPermission(AuthorizationStatus status) {
    switch (status) {
      case AuthorizationStatus.authorized:
      case AuthorizationStatus.provisional:
        return PushPermission.granted;
      case AuthorizationStatus.denied:
      case AuthorizationStatus.deniedPermanently:
        return PushPermission.denied;
      case AuthorizationStatus.notDetermined:
        return PushPermission.notDetermined;
    }
  }
}

AppPush createPlatformPush() => FirebaseAppPush();
