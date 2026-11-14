// 화면 전환 즉시 테스트: 화면을 옮기면 전환 효과(300ms) 동안 이전 화면이 같이 그려져 겹쳐 보이던 버그를 막는다.
// 핵심: context.go 뒤에 pump() 딱 한 번(시간을 거의 안 흘림)만 해도 이전 화면이 트리에서 사라져야 한다.
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';

// 목록(/)과 글 7 상세를 돌려주는 가짜 서버
FakeAdapter _adapter() => FakeAdapter()
  ..on('GET', '/schools', FakeReply(200, []))
  ..on('GET', '/posts', FakeReply(200, []))
  ..on('GET', '/posts/7', FakeReply(200, postJson(title: '글 7')))
  ..on('GET', '/posts/7/comments', FakeReply(200, []));

void main() {
  testWidgets('목록 → 상세로 go 하면 pump 한 번 만에 목록은 사라지고 상세만 있다', (tester) async {
    final app = await pumpApp(tester, location: '/', user: userJson(), adapter: _adapter());
    await tester.pumpAndSettle();
    expect(find.text('전체 게시판'), findsOneWidget);

    app.router.go('/posts/7');
    await tester.pump(); // 시간을 흘리지 않고 한 프레임만

    // 이전 화면(목록)이 아직 남아 겹쳐 있지 않아야 한다
    expect(find.text('전체 게시판'), findsNothing);

    // 뒷정리: 상세의 요청 타이머가 끝나도록 흘려 보낸다
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
  });

  testWidgets('상세에서 브라우저 뒤로 가기를 흉내 내면 pump 한 번 만에 상세는 사라지고 목록만 있다', (tester) async {
    final app = await pumpApp(tester, location: '/', user: userJson(), adapter: _adapter());
    await tester.pumpAndSettle();
    app.router.go('/posts/7');
    await tester.pump(const Duration(milliseconds: 50));
    await tester.pumpAndSettle();
    expect(find.text('글 7'), findsOneWidget);
    expect(find.text('전체 게시판'), findsNothing);

    // 브라우저 뒤로 가기: "이전 주소는 / 야" 알림을 직접 보낸다 (router_test.dart와 같은 방식)
    final message = const JSONMethodCodec().encodeMethodCall(
      const MethodCall('pushRouteInformation', {'location': '/'}),
    );
    await tester.binding.defaultBinaryMessenger
        .handlePlatformMessage('flutter/navigation', message, (_) {});
    await tester.pump(); // 한 프레임만

    expect(find.text('글 7'), findsNothing); // 이전(상세) 화면이 겹쳐 남지 않는다

    await tester.pumpAndSettle();
    expect(find.text('전체 게시판'), findsOneWidget);
  });
}
