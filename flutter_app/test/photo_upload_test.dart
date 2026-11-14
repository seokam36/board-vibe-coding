// S16-k2 사진 사전 검사·차례 올리기·실패 문구 단위 테스트 (React의 marketPhotoUpload.test.ts + e2e 규칙).
import 'dart:async';

import 'package:board_web/api/api_client.dart';
import 'package:board_web/common/file_picker.dart';
import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/market/photo_upload.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_adapter.dart';
import 'support/photo_support.dart';

const _tenMb = 10 * 1024 * 1024;

void main() {
  group('checkPhotoFiles (사전 검사: 종류 → 크기 → 장수)', () {
    test('JPEG·PNG와 브라우저가 jpg·pjpeg로 알려 준 JPEG는 통과한다', () {
      final files = [
        fakePhoto('a.jpg'),
        fakePhoto('b.png', type: 'image/png'),
        fakePhoto('c.jpg', type: 'image/jpg'),
        fakePhoto('d.jpg', type: 'image/pjpeg'),
      ];
      final r = checkPhotoFiles(files, 0);
      expect(r.accepted, files);
      expect(r.message, isNull);
    });

    test('종류가 빈 글자면 통과한다 (진짜 판단은 서버가 파일 내용으로)', () {
      final r = checkPhotoFiles([fakePhoto('a.jpg', type: '')], 0);
      expect(r.accepted, hasLength(1));
      expect(r.message, isNull);
    });

    test('gif는 거르고 "JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다."', () {
      final r = checkPhotoFiles([fakePhoto('a.gif', type: 'image/gif'), fakePhoto('b.jpg')], 0);
      expect(r.accepted.map((f) => f.name), ['b.jpg']);
      expect(r.message, 'JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다.');
    });

    test('정확히 10MB는 통과, 1바이트라도 넘으면 거른다', () {
      final ok = checkPhotoFiles([fakePhoto('a.jpg', size: _tenMb)], 0);
      expect(ok.accepted, hasLength(1));
      expect(ok.message, isNull);
      final big = checkPhotoFiles([fakePhoto('a.jpg', size: _tenMb + 1)], 0);
      expect(big.accepted, isEmpty);
      expect(big.message, '사진 한 장은 10MB 이하여야 합니다. 1장은 받지 않았습니다.');
    });

    test('이미 1장 + 11장을 고르면 9장만 받고 "2장은 받지 않았습니다"', () {
      final files = [for (var i = 0; i < 11; i++) fakePhoto('p$i.jpg')];
      final r = checkPhotoFiles(files, 1);
      expect(r.accepted, hasLength(9));
      expect(r.accepted.first.name, 'p0.jpg'); // 앞에서부터 받는다
      expect(r.message, '사진은 10장까지 올릴 수 있습니다. 2장은 받지 않았습니다.');
    });

    test('이미 10장이면 하나도 못 받는다', () {
      final r = checkPhotoFiles([fakePhoto('a.jpg')], 10);
      expect(r.accepted, isEmpty);
      expect(r.message, '사진은 10장까지 올릴 수 있습니다. 1장은 받지 않았습니다.');
    });

    test('한 번에 걸리면 종류 → 크기 → 장수 순서로 한 줄에 이어 붙는다', () {
      final files = [
        fakePhoto('a.gif', type: 'image/gif'),
        fakePhoto('big.jpg', size: _tenMb + 1),
        fakePhoto('b.jpg'),
        fakePhoto('c.jpg'),
      ];
      final r = checkPhotoFiles(files, 9); // 남은 자리 1장
      expect(r.accepted.map((f) => f.name), ['b.jpg']);
      expect(
        r.message,
        'JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다. '
        '사진 한 장은 10MB 이하여야 합니다. 1장은 받지 않았습니다. '
        '사진은 10장까지 올릴 수 있습니다. 1장은 받지 않았습니다.',
      );
    });
  });

  group('uploadPhotosInOrder (차례로 올리기)', () {
    late FakeAdapter adapter;
    late Dio dio;

    setUp(() {
      adapter = FakeAdapter();
      dio = ApiClient(adapter: adapter, cookieReader: FakeCookies(), baseUrl: fakeBaseUrl).dio;
    });

    // 올리기 결과와 진행·성공 기록을 한꺼번에 돌려주는 도우미
    Future<(UploadResult, List<String>, List<int>)> run(
      List<PickedPhoto> files, {
      bool Function()? isCancelled,
    }) async {
      final progress = <String>[];
      final uploaded = <int>[];
      final result = await uploadPhotosInOrder(
        dio,
        7,
        files,
        (cur, total) => progress.add('$cur/$total'),
        (p) => uploaded.add(p.id),
        isCancelled,
      );
      return (result, progress, uploaded);
    }

    test('성공하면 한 장씩 차례로 보내고 파트 이름 file·파일 이름·종류가 본문에 들어 있다', () async {
      final forms = recordForms(adapter);
      adapter
        ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(101)))
        ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(102)));
      final (result, progress, uploaded) = await run([fakePhoto('a.jpg'), fakePhoto('b.png', type: 'image/png')]);
      await Future<void>.delayed(Duration.zero);
      expect(result.failed, 0);
      expect(result.firstError, isNull);
      expect(progress, ['1/2', '2/2']);
      expect(uploaded, [101, 102]);
      expect(forms, hasLength(2));
      expect(forms[0].body, contains('name="file"'));
      expect(forms[0].body, contains('filename="a.jpg"'));
      expect(forms[0].body, contains('fake-a.jpg')); // 파일 내용
      expect(forms[1].body, contains('filename="b.png"'));
      expect(forms[1].body.toLowerCase(), contains('content-type: image/png'));
    });

    test('동시에 보내지 않는다: 첫 응답이 와야 두 번째 요청이 나간다', () async {
      final gate = Completer<void>();
      adapter
        ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(101), gate))
        ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(102)));
      final future = run([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      for (var i = 0; i < 20; i++) {
        await Future<void>.delayed(Duration.zero);
      }
      expect(adapter.count('POST', '/market-items/7/photos'), 1); // 첫 장이 붙잡혀 있는 동안 한 건뿐
      gate.complete();
      final (result, _, uploaded) = await future;
      expect(adapter.count('POST', '/market-items/7/photos'), 2);
      expect(result.failed, 0);
      expect(uploaded, [101, 102]);
    });

    test('파일 내용은 올리기 직전에 한 장씩 읽는다', () async {
      final log = PhotoLog();
      adapter.on('POST', '/market-items/7/photos', FakeReply(201, photoJson(101)));
      final photos = [fakePhoto('a.jpg', log: log), fakePhoto('b.jpg', log: log)];
      adapter.on('POST', '/market-items/7/photos', FakeReply(413, {}));
      await run(photos);
      expect(log.reads, ['a.jpg', 'b.jpg']);
    });

    for (final status in [400, 413, 503]) {
      test('$status는 그 사진만의 문제라 다음 장은 계속 올린다', () async {
        adapter
          ..on('POST', '/market-items/7/photos', FakeReply(status, {}))
          ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(102)));
        final (result, _, uploaded) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
        expect(adapter.count('POST', '/market-items/7/photos'), 2);
        expect(result.failed, 1);
        expect(getStatus(result.firstError!), status);
        expect(uploaded, [102]);
      });
    }

    for (final status in [403, 404, 409]) {
      test('$status면 남은 장은 보내지 않고 모두 실패로 센다', () async {
        adapter
          ..on('POST', '/market-items/7/photos', FakeReply(status, {}))
          ..on('POST', '/market-items/7/photos', FakeReply(201, photoJson(102)));
        final (result, _, uploaded) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')]);
        expect(adapter.count('POST', '/market-items/7/photos'), 1);
        expect(result.failed, 3);
        expect(uploaded, isEmpty);
      });
    }

    test('401(재발급도 401)이면 남은 장은 보내지 않는다', () async {
      adapter
        ..on('POST', '/market-items/7/photos', FakeReply(401, {}))
        ..on('POST', '/auth/refresh', FakeReply(401, {}));
      final (result, _, _) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(adapter.count('POST', '/market-items/7/photos'), 1);
      expect(result.failed, 2);
      expect(getStatus(result.firstError!), 401);
    });

    test('네트워크 오류(응답 없음)면 남은 장은 보내지 않는다', () async {
      adapter.on('POST', '/market-items/7/photos', FakeReply.networkError());
      final (result, _, _) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(adapter.count('POST', '/market-items/7/photos'), 1);
      expect(result.failed, 2);
    });

    test('시간 초과도 응답이 없는 오류라 남은 장은 보내지 않는다', () async {
      adapter.on('POST', '/market-items/7/photos', FakeReply.timeout());
      final (result, _, _) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg')]);
      expect(adapter.count('POST', '/market-items/7/photos'), 1);
      expect(result.failed, 2);
      expect(isTimeoutError(result.firstError), isTrue);
    });

    test('시작 전에 이미 떠났으면(isCancelled) 한 장도 보내지 않는다', () async {
      final (result, progress, _) = await run([fakePhoto('a.jpg'), fakePhoto('b.jpg')], isCancelled: () => true);
      expect(adapter.calls, isEmpty);
      expect(progress, isEmpty);
      expect(result.failed, 2);
    });

    test('첫 장을 올린 뒤 화면을 떠나면 나머지는 보내지 않는다', () async {
      adapter.on('POST', '/market-items/7/photos', FakeReply(201, photoJson(101)));
      var left = false;
      final result = await uploadPhotosInOrder(
        dio,
        7,
        [fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')],
        (cur, total) {},
        (_) => left = true, // 첫 장이 올라간 순간 화면을 떠난 것으로 본다
        () => left,
      );
      expect(adapter.count('POST', '/market-items/7/photos'), 1);
      expect(result.failed, 2);
      expect(result.firstError, isNull);
    });

    test('파일을 읽다 실패한 장은 그 장만 실패로 세고 다음 장은 계속 올린다', () async {
      adapter.on('POST', '/market-items/7/photos', FakeReply(201, photoJson(102)));
      final broken = PickedPhoto(
        name: 'broken.jpg',
        type: 'image/jpeg',
        size: 10,
        previewUrl: 'blob:x',
        readBytes: () async => throw StateError('읽기 실패'),
      );
      final (result, _, uploaded) = await run([broken, fakePhoto('b.jpg')]);
      expect(result.failed, 1);
      expect(uploaded, [102]);
      final info = uploadFailureText(result);
      expect(info, const ErrorMessage('사진을 올리지 못했습니다', null));
    });
  });

  group('uploadFailureText (실패 문구)', () {
    DioException http(int status) => DioException(
      requestOptions: RequestOptions(path: '/x'),
      response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: status),
      type: DioExceptionType.badResponse,
    );

    test('상태코드별 첫 문구', () {
      final cases = {
        400: 'JPEG·PNG 사진만 올릴 수 있습니다 (손상되었거나 너무 큰 사진일 수 있습니다)',
        403: '사진을 올리지 못했습니다. 새로고침 후 다시 시도해 주세요',
        404: '글을 찾을 수 없습니다',
        409: '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다',
        413: '사진 한 장은 10MB 이하여야 합니다',
        503: '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요',
        500: '사진을 올리지 못했습니다', // 표에 없는 코드는 기본 문구
      };
      cases.forEach((status, text) {
        expect(uploadFailureText(UploadResult(1, http(status))), ErrorMessage(text, status), reason: '$status');
      });
    });

    test('2장 이상 실패하면 " (n장 실패)"가 붙고 1장이면 안 붙는다', () {
      expect(uploadFailureText(UploadResult(1, http(413))), isA<ErrorMessage>().having((m) => m.message, 'message', '사진 한 장은 10MB 이하여야 합니다'));
      expect(
        uploadFailureText(UploadResult(2, http(413))),
        isA<ErrorMessage>().having((m) => m.message, 'message', '사진 한 장은 10MB 이하여야 합니다 (2장 실패)'),
      );
    });

    test('401은 로그인 안내(NeedLogin)', () {
      expect(uploadFailureText(UploadResult(2, http(401))), const NeedLogin());
    });

    test('응답이 없으면 연결 문구, 시간 초과면 전용 문구 (장수 접미사 포함)', () {
      final net = DioException(requestOptions: RequestOptions(path: '/x'), type: DioExceptionType.connectionError);
      final timeout = DioException(requestOptions: RequestOptions(path: '/x'), type: DioExceptionType.receiveTimeout);
      expect(
        uploadFailureText(UploadResult(2, net)),
        isA<ErrorMessage>().having((m) => m.message, 'message', '서버에 연결할 수 없습니다 (2장 실패)'),
      );
      expect(
        uploadFailureText(UploadResult(1, timeout)),
        isA<ErrorMessage>().having((m) => m.message, 'message', '시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요'),
      );
      expect(
        uploadFailureText(UploadResult(3, timeout)),
        isA<ErrorMessage>().having(
          (m) => m.message,
          'message',
          '시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요 (3장 실패)',
        ),
      );
    });

    test('지우기 실패 표: 403 새로고침 안내, 404 이미 삭제, 409 거래완료', () {
      expect(photoDeleteErrors[403], '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요');
      expect(photoDeleteErrors[404], '이미 삭제된 사진입니다');
      expect(photoDeleteErrors[409], '거래가 완료된 글의 사진은 지울 수 없습니다');
    });
  });
}
