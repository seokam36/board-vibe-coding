// 웹·기본용: 웹은 main.dart에서 이 도구를 끼우지 않는다(푸시 없음).
// 혹시 불려도 해롭지 않게 아무것도 하지 않는다.
import 'app_push.dart';

class _NoopAppPush implements AppPush {
  @override
  Future<bool> init() async => false;

  @override
  Future<PushPermission> permission() async => PushPermission.denied;

  @override
  Future<PushPermission> requestPermission() async => PushPermission.denied;

  @override
  Future<String?> getToken() async => null;

  @override
  Stream<String> get onTokenRefresh => const Stream<String>.empty();
}

AppPush createPlatformPush() => _NoopAppPush();
