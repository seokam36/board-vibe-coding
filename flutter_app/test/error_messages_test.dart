// S16-d1 에러 문구 테스트: React errorMessage.test.ts 를 옮긴 것. 화면에 보이는 글자가 그대로인지 한 글자씩 고정한다.
import 'package:board_web/errors/error_messages.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

// 진짜 DioException을 만든다. status가 없으면 응답 자체가 없는 에러(서버 꺼짐 등)를 흉내 낸다.
DioException dioError([int? status, DioExceptionType? type]) {
  final options = RequestOptions(path: '/x');
  return DioException(
    requestOptions: options,
    type: type ?? (status == null ? DioExceptionType.connectionError : DioExceptionType.badResponse),
    response: status == null ? null : Response<dynamic>(requestOptions: options, statusCode: status),
  );
}

void main() {
  group('describeError', () {
    test('응답 없는 dio 에러는 연결 불가 문구와 status null', () {
      expect(describeError(dioError(), {}, '기본'), const ErrorMessage(networkErrorMessage, null));
    });

    test('timeout·cancel·unknown 등 응답 없는 에러도 네트워크 문구', () {
      for (final type in [
        DioExceptionType.connectionTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.receiveTimeout,
        DioExceptionType.cancel,
        DioExceptionType.unknown,
        DioExceptionType.badCertificate,
      ]) {
        expect(
          describeError(dioError(null, type), {}, '기본'),
          const ErrorMessage('서버에 연결할 수 없습니다', null),
          reason: '$type',
        );
      }
    });

    test('401 + 빈 표는 needLogin', () {
      expect(describeError(dioError(401), {}, '기본'), const NeedLogin());
    });

    test('401 + 표에 401 문구가 있으면 그 문구와 status 401', () {
      expect(
        describeError(dioError(401), {401: '아이디 또는 비밀번호가 틀렸습니다'}, '기본'),
        const ErrorMessage('아이디 또는 비밀번호가 틀렸습니다', 401),
      );
    });

    test('404 + 표에 404 문구가 있으면 그 문구', () {
      expect(
        describeError(dioError(404), {404: '글을 찾을 수 없습니다'}, '기본'),
        const ErrorMessage('글을 찾을 수 없습니다', 404),
      );
    });

    test('표에 없는 500은 기본 문구와 status 500', () {
      expect(
        describeError(dioError(500), {404: '글을 찾을 수 없습니다'}, '기본'),
        const ErrorMessage('기본', 500),
      );
    });

    test('dio 에러가 아닌 일반 Error는 기본 문구와 status null', () {
      expect(describeError(Exception('boom'), {}, '기본'), const ErrorMessage('기본', null));
    });

    // 빈 문자열 문구는 "없는 것"으로 보고 기본 문구를 쓴다 (React의 || 와 같은 동작)
    test('표의 문구가 빈 문자열이면 기본 문구', () {
      expect(describeError(dioError(401), {401: ''}, '기본'), const ErrorMessage('기본', 401));
    });
  });

  group('errorText', () {
    test('401 + 빈 표는 needLogin 대신 기본 문구', () {
      expect(errorText(dioError(401), {}, '기본'), '기본');
    });

    test('응답 없는 에러는 서버에 연결할 수 없다는 문구', () {
      expect(errorText(dioError(), {}, '기본'), '서버에 연결할 수 없습니다');
    });

    test('표에 있는 상태코드면 표의 문구', () {
      expect(errorText(dioError(404), {404: '글을 찾을 수 없습니다'}, '기본'), '글을 찾을 수 없습니다');
      expect(errorText(dioError(401), {401: '로그인이 필요합니다'}, '기본'), '로그인이 필요합니다');
    });
  });

  test('공용 문구 상수 글자가 바뀌지 않는다', () {
    expect(networkErrorMessage, '서버에 연결할 수 없습니다');
    expect(csrfMessage, '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요');
    expect(reloginMessage, '다시 로그인해 주세요');
  });

  // 문구 사전 37개: 실제 값과 기대 글자를 짝지어 한 글자씩 비교한다
  test('ErrorMessages 문구 37개 글자가 바뀌지 않는다', () {
    final pairs = <String, (String, String)>{
      'postNotFound': (ErrorMessages.postNotFound, '글을 찾을 수 없습니다'),
      'postLoadFailed': (ErrorMessages.postLoadFailed, '글을 불러오지 못했습니다'),
      'postFieldsInvalid': (ErrorMessages.postFieldsInvalid, '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)'),
      'saveFailed': (ErrorMessages.saveFailed, '저장하지 못했습니다'),
      'schoolNotFound': (ErrorMessages.schoolNotFound, '존재하지 않는 학교입니다'),
      'schoolGatePostAction': (
        ErrorMessages.schoolGatePostAction,
        '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
      ),
      'deleteRetryAfterReload': (ErrorMessages.deleteRetryAfterReload, '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      'deleteForbidden': (ErrorMessages.deleteForbidden, '삭제할 권한이 없습니다'),
      'deleteFailed': (ErrorMessages.deleteFailed, '삭제하지 못했습니다'),
      'postAlreadyLiked': (ErrorMessages.postAlreadyLiked, '이미 공감한 글입니다'),
      'actionFailed': (ErrorMessages.actionFailed, '처리하지 못했습니다'),
      'reportReasonInvalid': (ErrorMessages.reportReasonInvalid, '신고 사유를 확인해 주세요'),
      'reviewNotFound': (ErrorMessages.reviewNotFound, '후기를 찾을 수 없습니다'),
      'reviewLoadFailed': (ErrorMessages.reviewLoadFailed, '후기를 불러오지 못했습니다'),
      'reviewFieldsInvalid': (
        ErrorMessages.reviewFieldsInvalid,
        '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
      ),
      'reviewFilterInvalid': (ErrorMessages.reviewFilterInvalid, '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요'),
      'reviewOptionsLoadFailed': (ErrorMessages.reviewOptionsLoadFailed, '선택지 목록을 불러오지 못했습니다'),
      'visaSchoolRequiredRetry': (
        ErrorMessages.visaSchoolRequiredRetry,
        '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
      ),
      'marketFieldsInvalid': (
        ErrorMessages.marketFieldsInvalid,
        '제목, 본문, 판매/나눔, 가격을 확인해 주세요 (판매 가격은 1~10,000,000원)',
      ),
      'marketDoneNotEditable': (ErrorMessages.marketDoneNotEditable, '거래가 완료된 글은 수정할 수 없습니다.'),
      'marketStatusConflict': (ErrorMessages.marketStatusConflict, '그 사이 상태가 바뀌었습니다. 새로고침해 주세요.'),
      'marketStatusRetry': (ErrorMessages.marketStatusRetry, '상태를 바꾸지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      'marketStatusFailed': (ErrorMessages.marketStatusFailed, '상태를 바꾸지 못했습니다'),
      'marketPhotoInvalid': (ErrorMessages.marketPhotoInvalid, 'JPEG·PNG 사진만 올릴 수 있습니다 (손상되었거나 너무 큰 사진일 수 있습니다)'),
      'marketPhotoTypeOnly': (ErrorMessages.marketPhotoTypeOnly, 'JPEG·PNG 사진만 올릴 수 있습니다'),
      'marketPhotoMaxCount': (ErrorMessages.marketPhotoMaxCount, '사진은 10장까지 올릴 수 있습니다'),
      'marketPhotoForbidden': (ErrorMessages.marketPhotoForbidden, '사진을 올리지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      'marketPhotoConflict': (ErrorMessages.marketPhotoConflict, '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다'),
      'marketPhotoTooLarge': (ErrorMessages.marketPhotoTooLarge, '사진 한 장은 10MB 이하여야 합니다'),
      'marketPhotoBusy': (ErrorMessages.marketPhotoBusy, '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요'),
      'marketPhotoUploadFailed': (ErrorMessages.marketPhotoUploadFailed, '사진을 올리지 못했습니다'),
      'marketPhotoDeleteConflict': (ErrorMessages.marketPhotoDeleteConflict, '거래가 완료된 글의 사진은 지울 수 없습니다'),
      'messageContentInvalid': (ErrorMessages.messageContentInvalid, '쪽지 내용을 입력해 주세요 (최대 1000자)'),
      'messageSendFailed': (ErrorMessages.messageSendFailed, '쪽지를 보내지 못했습니다'),
      'conversationNotFound': (ErrorMessages.conversationNotFound, '대화를 찾을 수 없습니다'),
      'conversationLoadFailed': (ErrorMessages.conversationLoadFailed, '대화를 불러오지 못했습니다'),
      'adminOnly': (ErrorMessages.adminOnly, '관리자만 볼 수 있습니다'),
    };
    expect(pairs, hasLength(37));
    expect({for (final e in pairs.entries) e.key: e.value.$1}, {for (final e in pairs.entries) e.key: e.value.$2});

    expect(ErrorMessages.alreadyDeleted('글'), '이미 삭제된 글입니다');
    expect(ErrorMessages.alreadyDeleted('댓글'), '이미 삭제된 댓글입니다');
    expect(ErrorMessages.alreadyDeleted('후기'), '이미 삭제된 후기입니다');
    expect(ErrorMessages.alreadyDeleted('일정'), '이미 삭제된 일정입니다');
    expect(
      ErrorMessages.photoSkipped('사진 한 장은 10MB 이하여야 합니다', 2),
      '사진 한 장은 10MB 이하여야 합니다. 2장은 받지 않았습니다.',
    );
  });
}
