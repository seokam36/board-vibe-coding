// 라우터 테스트용: 시작 확인(loading)이 이미 끝난 비로그인 상태의 가짜 AuthState.
// 서버 호출은 일어나지 않는다(어댑터에 응답을 안 줬고, init()도 부르지 않는다).
import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';

import 'fake_adapter.dart';

class LoadedAuthState extends AuthState {
  LoadedAuthState()
    : super(
        api: ApiClient(adapter: FakeAdapter(), cookieReader: FakeCookies(), baseUrl: fakeBaseUrl),
        currentPath: () => '/',
      );

  @override
  bool get loading => false;
}
