// 테스트용 가짜 서버. 진짜 네트워크 대신 "경로·메서드별로 미리 정한 응답"을 큐에서 꺼내 준다.
// (http_mock_adapter 같은 패키지는 쓰지 않고 직접 만들었다.)
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:board_web/api/cookie_reader.dart';
import 'package:dio/dio.dart';

// VM(웹이 아닌 곳)의 dio는 baseUrl이 완전한 주소여야 해서, 테스트에서는 이 가짜 주소를 쓴다.
// (어댑터는 경로만 보므로 /api/... 키로 응답을 찾는다)
const fakeBaseUrl = 'http://fake.test/api';

// 가짜 응답 하나. error가 있으면 응답 대신 그 에러(네트워크 끊김 등)를 던진다.
class FakeReply {
  FakeReply(this.status, [this.body, this.gate]) : error = null;
  FakeReply.networkError()
    : status = 0,
      body = null,
      gate = null,
      error = DioExceptionType.connectionError;

  final int status;
  final Object? body; // JSON으로 바꿔서 보낼 값
  final Completer<void>? gate; // 있으면 이게 완료될 때까지 응답을 붙잡아 둔다 (요청을 겹치게 만들 때)
  final DioExceptionType? error;
}

// 어댑터가 받은 요청 기록
class FakeCall {
  FakeCall(this.method, this.path, this.headers);
  final String method;
  final String path; // 쿼리 뺀 경로 (예: /api/auth/me)
  final Map<String, dynamic> headers;
}

class FakeAdapter implements HttpClientAdapter {
  final Map<String, List<FakeReply>> _queues = {};
  final List<FakeCall> calls = [];
  // 요청을 받을 때마다 보낸 본문(dio가 JSON으로 바꾸기 전의 값, 보통 Map)을 알려 준다 (본문을 확인하는 테스트용)
  void Function(Object? data)? onRequestBody;

  // 'GET /auth/me' 같은 키로 응답을 줄 세운다. 큐가 비면 마지막 응답이 아니라 에러가 난다(테스트 실수 발견용).
  void on(String method, String path, FakeReply reply) {
    _queues.putIfAbsent('$method /api$path', () => []).add(reply);
  }

  int count(String method, String path) =>
      calls.where((c) => c.method == method && c.path == '/api$path').length;

  List<FakeCall> callsTo(String method, String path) =>
      calls.where((c) => c.method == method && c.path == '/api$path').toList();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.uri.path;
    calls.add(FakeCall(options.method, path, Map.of(options.headers)));
    onRequestBody?.call(options.data);
    final queue = _queues['${options.method} $path'];
    if (queue == null || queue.isEmpty) {
      throw StateError('준비되지 않은 요청: ${options.method} $path');
    }
    final reply = queue.removeAt(0);
    if (reply.gate != null) await reply.gate!.future;
    if (reply.error != null) {
      throw DioException(
        requestOptions: options,
        type: reply.error!,
        error: 'fake network error',
      );
    }
    return ResponseBody.fromString(
      jsonEncode(reply.body ?? {}),
      reply.status,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

// 쿠키를 마음대로 바꿀 수 있는 가짜 쿠키 읽기
class FakeCookies implements CookieReader {
  FakeCookies([Map<String, String>? initial]) : values = {...?initial};
  final Map<String, String> values;

  @override
  String? read(String name) => values[name];
}

// 가짜 서버가 돌려주는 사용자 JSON
Map<String, dynamic> userJson({
  int id = 1,
  String username = 'user01',
  String nickname = '홍길동',
  String role = 'USER',
  Map<String, dynamic>? school,
}) => {
  'id': id,
  'username': username,
  'nickname': nickname,
  'role': role,
  'verifiedSchool': school,
};
