// 브라우저 localStorage 도우미 (닫은 비자 띠 기록처럼 "이 브라우저에만" 남기는 작은 값용).
// 브라우저에서만 localStorage를 쓸 수 있고 테스트(VM)에는 브라우저가 없다.
// 그래서 external_link처럼 조건부 import: 웹이면 진짜 localStorage, 아니면 메모리 맵을 쓴다.
import 'local_store_stub.dart'
    if (dart.library.js_interop) 'local_store_web.dart'
    as platform;

abstract interface class LocalStore {
  // 값이 없거나(또는 저장소가 막혀 있어) 못 읽으면 null
  String? getItem(String key);

  // 저장한다. 저장소가 막혀 있으면 조용히 무시한다
  void setItem(String key, String value);
}

// 화면이 쓰는 저장소. 테스트는 이 값을 메모리·예외 던지는 가짜로 바꿔 끼운다
LocalStore localStore = platform.createPlatformLocalStore();
