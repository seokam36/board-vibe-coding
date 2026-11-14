// S16-k2: 파일 올리기(FormData)가 401을 받아 재발급 뒤 다시 보내질 때, 본문이 새로 만들어지는지 본다.
// FormData는 한 번 보내면 소진돼서(진짜 어댑터가 finalize) 같은 것을 다시 보낼 수 없기 때문이다.
import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/photo_support.dart';

void main() {
  late FakeAdapter adapter;
  late ApiClient client;

  setUp(() {
    adapter = FakeAdapter();
    client = ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl)..onRefreshed = (AuthUser _) {};
  });

  FormData makeForm() => FormData.fromMap({
    'file': MultipartFile.fromBytes([1, 2, 3], filename: 'a.jpg'),
  });

  test('401 → 재발급 → 재시도: 생성 함수가 있으면 본문이 새로 만들어져 file 파트·filename이 다시 들어 있다', () async {
    final forms = recordForms(adapter);
    adapter
      ..on('POST', '/up', FakeReply(401, {}))
      ..on('POST', '/auth/refresh', FakeReply(200, userJson()))
      ..on('POST', '/up', FakeReply(201, {'id': 1}));

    final res = await client.dio.post<Map<String, dynamic>>(
      '/up',
      data: makeForm(),
      options: Options(extra: {ApiClient.formDataFactoryKey: makeForm}),
    );
    await Future<void>.delayed(Duration.zero);

    expect(res.statusCode, 201);
    expect(adapter.count('POST', '/up'), 2);
    expect(forms, hasLength(2));
    expect(identical(forms[0].data, forms[1].data), isFalse); // 같은 FormData를 다시 쓰지 않았다
    for (final f in forms) {
      expect(f.body, contains('name="file"'));
      expect(f.body, contains('filename="a.jpg"'));
    }
    // 재시도 요청의 Content-Type 경계(boundary)는 새 본문의 것이다
    final retryType = adapter.callsTo('POST', '/up')[1].headers['content-type'] as String;
    expect(retryType, contains(forms[1].data.boundary));
  });

  test('생성 함수가 없는 FormData는 이미 소진돼 다시 보낼 수 없다 (그래서 함수를 넣는다)', () async {
    // dio는 요청을 보낼 때 FormData를 한 번 소진(finalize)한다. 같은 것을 다시 보내려 하면 StateError
    adapter
      ..on('POST', '/up', FakeReply(401, {}))
      ..on('POST', '/auth/refresh', FakeReply(200, userJson()))
      ..on('POST', '/up', FakeReply(201, {'id': 1}));

    await expectLater(
      client.dio.post<Map<String, dynamic>>('/up', data: makeForm()),
      throwsA(isA<DioException>().having((e) => '${e.error}', 'error', contains('already been finalized'))),
    );
  });

  test('재시도도 401이면 더 반복하지 않는다 (생성 함수는 한 번만 불린다)', () async {
    var made = 0;
    FormData counting() {
      made++;
      return makeForm();
    }

    adapter
      ..on('POST', '/up', FakeReply(401, {}))
      ..on('POST', '/auth/refresh', FakeReply(200, userJson()))
      ..on('POST', '/up', FakeReply(401, {}));

    await expectLater(
      client.dio.post<Map<String, dynamic>>(
        '/up',
        data: counting(),
        options: Options(extra: {ApiClient.formDataFactoryKey: counting}),
      ),
      throwsA(isA<DioException>().having((e) => e.response?.statusCode, 'status', 401)),
    );
    expect(made, 2); // 처음 한 번 + 재시도 직전 한 번
    expect(adapter.count('POST', '/auth/refresh'), 1);
  });

  test('FormData가 아닌 요청(JSON)은 생성 함수 없이 그대로 재시도된다', () async {
    adapter
      ..on('POST', '/j', FakeReply(401, {}))
      ..on('POST', '/auth/refresh', FakeReply(200, userJson()))
      ..on('POST', '/j', FakeReply(200, {'ok': true}));
    final res = await client.dio.post<Map<String, dynamic>>('/j', data: {'a': 1});
    expect(res.data, {'ok': true});
  });
}
