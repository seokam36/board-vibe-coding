// 사진 올리기 규칙과 실제 올리기 (React의 marketPhotoUpload.ts). docs/api.md 33·35번.
// 최종 판단은 서버이고, 브라우저는 바로 알려 주려고 미리 거른다.
import 'package:dio/dio.dart';

import '../api/api_client.dart';
import '../common/file_picker.dart';
import '../errors/error_messages.dart';
import 'models.dart';

const maxPhotos = 10;
const maxPhotoBytes = 10 * 1024 * 1024; // 정확히 10MB까지는 통과
// 브라우저가 JPEG를 image/jpg·image/pjpeg로 알려 주는 경우도 있어 함께 받는다
const _allowedTypes = ['image/jpeg', 'image/jpg', 'image/pjpeg', 'image/png'];
// 사진 한 장 올리기 제한 시간 (5분). 느린 회선에서 8~10MB가 정상 업로드되는 중에 끊지 않도록 넉넉히 둔다
const _uploadTimeout = Duration(minutes: 5);
// 시간 초과 전용 문구. 서버가 저장을 끝냈을 수도 있어 확인을 안내한다
const photoUploadTimeoutMessage = '시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요';

// 올리기 실패 상태코드별 문구 (401은 표에 두지 않는다 → describeError가 로그인 안내로 바꾼다)
const photoUploadErrors = <int, String>{
  400: ErrorMessages.marketPhotoInvalid,
  403: ErrorMessages.marketPhotoForbidden,
  404: ErrorMessages.postNotFound,
  409: ErrorMessages.marketPhotoConflict,
  413: ErrorMessages.marketPhotoTooLarge,
  503: ErrorMessages.marketPhotoBusy,
};

// 지우기 실패 상태코드별 문구
final photoDeleteErrors = <int, String>{
  403: ErrorMessages.deleteRetryAfterReload,
  404: ErrorMessages.alreadyDeleted('사진'),
  409: ErrorMessages.marketPhotoDeleteConflict,
};

// 사전 검사 결과: 받을 수 있는 것(accepted)과, 뺀 이유를 한 줄로 합친 안내(message, 없으면 null)
class PhotoCheck {
  const PhotoCheck(this.accepted, this.message);
  final List<PickedPhoto> accepted;
  final String? message;
}

// 고른 파일을 서버에 보내기 전에 거른다. 순서는 종류 → 크기 → 장수.
// existing: 이미 올라가 있거나 미리보기로 담아 둔 사진 수
// 종류가 빈 글자면(브라우저가 모르는 확장자) 통과시킨다. 진짜 판단은 서버가 파일 내용(매직 바이트)으로 한다
PhotoCheck checkPhotoFiles(List<PickedPhoto> files, int existing) {
  final notes = <String>[];
  final typeOk = files.where((f) => f.type.isEmpty || _allowedTypes.contains(f.type)).toList();
  if (typeOk.length < files.length) {
    notes.add(ErrorMessages.photoSkipped(ErrorMessages.marketPhotoTypeOnly, files.length - typeOk.length));
  }
  final sizeOk = typeOk.where((f) => f.size <= maxPhotoBytes).toList();
  if (sizeOk.length < typeOk.length) {
    notes.add(ErrorMessages.photoSkipped(ErrorMessages.marketPhotoTooLarge, typeOk.length - sizeOk.length));
  }
  final room = (maxPhotos - existing).clamp(0, maxPhotos);
  final accepted = sizeOk.take(room).toList();
  if (accepted.length < sizeOk.length) {
    notes.add(ErrorMessages.photoSkipped(ErrorMessages.marketPhotoMaxCount, sizeOk.length - accepted.length));
  }
  return PhotoCheck(accepted, notes.isEmpty ? null : notes.join(' '));
}

// 제한 시간 초과로 끊긴 오류인지
bool isTimeoutError(Object? error) =>
    error is DioException &&
    (error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout ||
        error.type == DioExceptionType.connectionTimeout);

// 사진 한 장 올리기 (multipart, 파트 이름 file). CSRF 헤더는 ApiClient가 붙인다
Future<MarketPhoto> uploadOne(Dio dio, int itemId, PickedPhoto photo) async {
  final bytes = await photo.readBytes(); // 올리기 직전에 이 한 장만 읽는다
  // FormData는 한 번 보내면 소진된다. 401 재시도 때 ApiClient가 새로 만들 수 있게 "만드는 함수"로 둔다
  FormData make() => FormData.fromMap({
    'file': MultipartFile.fromBytes(
      bytes,
      filename: photo.name,
      contentType: photo.type.isEmpty ? null : DioMediaType.parse(photo.type),
    ),
  });
  final res = await dio.post<Map<String, dynamic>>(
    '/market-items/$itemId/photos',
    data: make(),
    // receiveTimeout: 응답이 없으면 무한정 기다리지 않는다 (웹에서는 요청 전체 제한 시간으로 쓰인다)
    options: Options(receiveTimeout: _uploadTimeout, extra: {ApiClient.formDataFactoryKey: make}),
  );
  return MarketPhoto.fromJson(res.data!);
}

class UploadResult {
  const UploadResult(this.failed, this.firstError);
  final int failed; // 올리지 못한 장수 (중간에 멈췄으면 남은 것도 포함)
  final Object? firstError; // 처음 난 에러 (없으면 null). 문구는 uploadFailureText로 만든다
}

// 사진을 한 장씩 차례로(동시에 아님) 올린다. 서버 이미지 처리 자리가 2개뿐이라 한꺼번에 보내지 않는다.
// 401(로그인 풀림)·403(권한/보안 토큰)·404(글 없음)·409(10장 초과/거래완료)·응답 없는 오류(서버 꺼짐·시간 초과)는
// 다음 장도 똑같이 실패하니 남은 것은 시도하지 않고 실패로 센다. 400·413·503은 그 사진만의 문제라 계속한다.
// isCancelled: 화면을 떠났을 때 true를 돌려주면 남은 것은 보내지 않는다
Future<UploadResult> uploadPhotosInOrder(
  Dio dio,
  int itemId,
  List<PickedPhoto> files,
  void Function(int current, int total) onProgress,
  void Function(MarketPhoto photo) onUploaded, [
  bool Function()? isCancelled,
]) async {
  var failed = 0;
  Object? firstError;
  for (var i = 0; i < files.length; i++) {
    if (isCancelled?.call() ?? false) {
      failed += files.length - i;
      break;
    }
    onProgress(i + 1, files.length);
    try {
      onUploaded(await uploadOne(dio, itemId, files[i]));
    } catch (e) {
      failed++;
      firstError ??= e;
      final status = getStatus(e);
      if (status == 401 || status == 403 || status == 404 || status == 409 || isNetworkError(e)) {
        failed += files.length - i - 1;
        break;
      }
    }
  }
  return UploadResult(failed, firstError);
}

// 실패 안내 문구: 첫 에러 문구 뒤에, 2장 이상 실패했으면 장수를 붙인다 (1장이면 문구 그대로)
ErrorInfo uploadFailureText(UploadResult result) {
  final error = result.firstError;
  final info = error == null
      ? const ErrorMessage(ErrorMessages.marketPhotoUploadFailed, null)
      : describeError(error, photoUploadErrors, ErrorMessages.marketPhotoUploadFailed);
  if (info is! ErrorMessage) return info; // 로그인 풀림
  // 시간 초과는 "연결 불가"가 아니라 따로 안내한다 (서버가 이미 저장했을 수 있어서)
  final base = isTimeoutError(error) ? photoUploadTimeoutMessage : info.message;
  return ErrorMessage(result.failed > 1 ? '$base (${result.failed}장 실패)' : base, info.status);
}
