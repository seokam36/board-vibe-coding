import 'package:flutter/foundation.dart';

// 앱(안드로이드)의 "뒤로 가기"용 방문 기록.
// 화면 이동을 전부 context.go로 하니 라우터에는 쌓인 화면이 없어서, 뒤로 가기를 누르면 바로 앱이 꺼진다.
// 그래서 우리가 직접 "지나온 주소 목록"을 들고 있다가, 뒤로 가기가 오면 한 칸 앞 주소로 go 한다 (브라우저의 뒤로 가기와 비슷).
// enabled가 false(웹·기본값)면 아무것도 적지 않고 아무것도 가로채지 않는다.
class AppHistory extends ChangeNotifier {
  AppHistory({this.enabled = false, this.maxEntries = 50});

  final bool enabled;
  final int maxEntries; // 넘치면 오래된 것부터 버린다
  final List<String> _entries = [];

  // 지금까지 적힌 주소들 (테스트·확인용, 복사본)
  List<String> get entries => List.unmodifiable(_entries);

  // 돌아갈 곳이 있나: 지금 화면 말고 그 앞 주소가 남아 있어야 한다 (2칸 이상)
  bool get canGoBack => enabled && _entries.length >= 2;

  // 화면이 바뀔 때 부른다. 주소 전체(쿼리 포함)를 끝에 적되, 마지막과 같으면 적지 않는다.
  void record(String location) {
    if (!enabled) return;
    if (_entries.isNotEmpty && _entries.last == location) return;
    _entries.add(location);
    if (_entries.length > maxEntries) {
      _entries.removeRange(0, _entries.length - maxEntries);
    }
    notifyListeners();
  }

  // 뒤로 가기: 지금 화면(맨 끝)을 떼고, 그 앞 주소를 알려 준다. 돌아갈 곳이 없으면 null.
  // (알려 준 주소로 go 하면 record가 불리지만, 이미 맨 끝과 같아서 다시 늘지 않는다)
  String? goBack() {
    if (!canGoBack) return null;
    _entries.removeLast();
    notifyListeners();
    return _entries.last;
  }

  // 조건에 맞는 주소를 기록에서 뺀다 (세션 만료 뒤 보호 주소 정리용). 뺀 게 있을 때만 알린다.
  void removeWhere(bool Function(String location) test) {
    if (!enabled) return;
    final before = _entries.length;
    _entries.removeWhere(test);
    if (_entries.length != before) notifyListeners();
  }

  // 로그인·로그아웃 때 비운다: 지금 주소 하나만 남긴다 (로그아웃 뒤 뒤로 가기로 보호 화면에 못 가게)
  void reset(String location) {
    if (!enabled) return;
    _entries
      ..clear()
      ..add(location);
    notifyListeners();
  }
}
