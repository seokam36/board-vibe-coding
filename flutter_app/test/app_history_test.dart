// 앱 뒤로 가기 방문 기록(AppHistory)의 목록 규칙만 본다 (화면 없이).
import 'package:board_web/common/app_history.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('꺼져 있으면(기본) 아무것도 적지 않고 돌아갈 곳도 없다', () {
    final h = AppHistory();
    h.record('/');
    h.record('/market');
    expect(h.entries, isEmpty);
    expect(h.canGoBack, isFalse);
    expect(h.goBack(), isNull);
  });

  test('전체 주소(쿼리 포함)를 끝에 적고, 마지막과 같으면 적지 않는다', () {
    final h = AppHistory(enabled: true);
    h.record('/');
    h.record('/market?tradeType=SHARE');
    h.record('/market?tradeType=SHARE');
    expect(h.entries, ['/', '/market?tradeType=SHARE']);
  });

  test('뒤로 가기: 2칸 이상이면 맨 끝을 떼고 그 앞 주소를 준다, 1칸이면 null', () {
    final h = AppHistory(enabled: true);
    h.record('/');
    expect(h.canGoBack, isFalse);
    expect(h.goBack(), isNull);
    h.record('/market');
    h.record('/market/3');
    expect(h.goBack(), '/market');
    expect(h.goBack(), '/');
    expect(h.goBack(), isNull);
    expect(h.entries, ['/']);
  });

  test('최대 50칸: 넘치면 오래된 것부터 버린다', () {
    final h = AppHistory(enabled: true);
    for (var i = 0; i < 60; i++) {
      h.record('/posts/$i');
    }
    expect(h.entries.length, 50);
    expect(h.entries.first, '/posts/10');
    expect(h.entries.last, '/posts/59');
  });

  test('removeWhere: 조건에 맞는 주소만 빼고, 껐을 땐 아무것도 안 하고, 뺀 게 있을 때만 알린다', () {
    final h = AppHistory(enabled: true);
    h.record('/');
    h.record('/me');
    h.record('/market');
    var notified = 0;
    h.addListener(() => notified++);
    h.removeWhere((l) => l == '/없는주소');
    expect(notified, 0);
    h.removeWhere((l) => l == '/me');
    expect(h.entries, ['/', '/market']);
    expect(notified, 1);

    final off = AppHistory();
    off.removeWhere((_) => true); // 꺼진 기록은 그냥 지나간다
    expect(off.entries, isEmpty);
  });

  test('reset: 지금 주소 하나만 남긴다', () {
    final h = AppHistory(enabled: true);
    h.record('/');
    h.record('/me');
    h.reset('/login');
    expect(h.entries, ['/login']);
    expect(h.canGoBack, isFalse);
  });
}
