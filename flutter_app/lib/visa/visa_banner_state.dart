import 'package:flutter/foundation.dart';

import 'visa_banner.dart';

// "이번 화면(앱 실행)에서 닫은 띠 값" 보관 (React의 VisaPlanBanner closedNow).
// 화면(페이지)마다 상단바와 띠를 새로 그려서, 띠 위젯 안에 두면 화면을 옮길 때 사라진다.
// 저장소가 막힌 브라우저에서도 닫은 띠가 다시 뜨지 않도록 앱 전체에서 하나만 둔다.
class VisaBannerState extends ChangeNotifier {
  final Set<String> _closedNow = {};

  // 회원별로 따로 센다: 로그아웃 뒤 다른 회원이 새로고침 없이 로그인해도 앞 회원이 닫은 띠가 넘어오지 않게
  // (React는 새로고침 없이 회원이 바뀌면 같은 값이 넘어오는 틈이 있어서, 여기서는 회원 id를 같이 적는다)
  bool isClosedNow(int userId, String value) => _closedNow.contains('$userId|$value');

  // 닫기: 저장소에 남기고(막혀 있으면 무시) 이번 화면에서도 숨긴다
  void close(int userId, String value) {
    saveDismissed(userId, value);
    _closedNow.add('$userId|$value');
    notifyListeners();
  }
}
