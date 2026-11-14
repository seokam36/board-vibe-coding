// 웹이 아닌 곳(VM 테스트 등)용: 브라우저가 없으니 메모리 맵에 담는다 (앱을 끄면 사라진다)
import 'local_store.dart';

class _MemoryStore implements LocalStore {
  final Map<String, String> _data = {};

  @override
  String? getItem(String key) => _data[key];

  @override
  void setItem(String key, String value) => _data[key] = value;
}

LocalStore createPlatformLocalStore() => _MemoryStore();
