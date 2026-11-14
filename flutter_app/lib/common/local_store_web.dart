// 웹용: 브라우저의 window.localStorage를 쓴다 (package:web)
import 'package:web/web.dart' as web;

import 'local_store.dart';

class _WebStore implements LocalStore {
  @override
  String? getItem(String key) {
    // 시크릿 모드·저장소 차단 브라우저에서는 접근만 해도 예외가 난다 → "없음"으로 본다
    try {
      return web.window.localStorage.getItem(key);
    } catch (_) {
      return null;
    }
  }

  @override
  void setItem(String key, String value) {
    // 저장 실패(차단·용량 초과)는 무시한다
    try {
      web.window.localStorage.setItem(key, value);
    } catch (_) {}
  }
}

LocalStore createPlatformLocalStore() => _WebStore();
