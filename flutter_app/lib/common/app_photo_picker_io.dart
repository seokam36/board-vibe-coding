// 앱용: image_picker로 기기 갤러리에서 사진을 고른다.
// 개인정보(허가서 사진 등)가 들어올 수 있어서 경로·파일 이름·오류 내용은 로그에 남기지 않는다.
import 'dart:io';
import 'dart:typed_data';

import 'package:image_picker/image_picker.dart';

import '../market/photo_upload.dart' show maxPhotoBytes, maxPhotos;
import 'file_picker.dart';

// 갤러리에서 한 장 / 여러 장 고르는 함수의 모양. 테스트에서는 진짜 갤러리 대신 가짜 함수를 끼운다
typedef PickOneFunction = Future<XFile?> Function();
typedef PickManyFunction = Future<List<XFile>> Function();

Future<XFile?> _defaultPickOne() => ImagePicker().pickImage(source: ImageSource.gallery);
// 갤러리 창에서부터 최대 장수(maxPhotos)까지만 고르게 한다 (limit은 2 이상이어야 한다)
Future<List<XFile>> _defaultPickMany() => ImagePicker().pickMultiImage(limit: maxPhotos);

class AppPhotoPicker implements PhotoPicker {
  // 안 넣으면 진짜 갤러리를 쓴다. 원본 그대로 받는다(maxWidth·imageQuality를 안 줌)
  // deleteTempFiles: 임시 파일을 지울지. 안드로이드는 image_picker가 앱 캐시에 만든 "사본"이라 지워도 원본은 안전하다.
  // 데스크톱(윈도우 등)은 사본이 아니라 사용자의 원본 경로라서 지우면 안 된다 → 기본값은 안드로이드일 때만 true
  AppPhotoPicker({PickOneFunction? pickOne, PickManyFunction? pickMany, bool? deleteTempFiles})
    : _pickOne = pickOne ?? _defaultPickOne,
      _pickMany = pickMany ?? _defaultPickMany,
      _deleteTempFiles = deleteTempFiles ?? Platform.isAndroid;

  final PickOneFunction _pickOne;
  final PickManyFunction _pickMany;
  final bool _deleteTempFiles;

  @override
  void pick(void Function(List<PickedPhoto> files) onPicked, {bool multiple = true}) {
    // pick은 값을 돌려주지 않는 void라서, 고르는 일은 뒤에서 따로 처리한다
    _pickQuietly(onPicked, multiple);
  }

  Future<void> _pickQuietly(void Function(List<PickedPhoto> files) onPicked, bool multiple) async {
    final List<PickedPhoto> photos;
    try {
      final List<XFile> files;
      if (multiple) {
        files = await _pickMany();
      } else {
        final one = await _pickOne();
        files = one == null ? <XFile>[] : [one];
      }
      if (files.isEmpty) return; // 취소하면 아무 일도 없다
      photos = await _convertAll(files);
    } catch (_) {
      // 이미 창이 열려 있음(already_active)·읽기 실패 등은 조용히 넘어간다 (오류 내용은 로그에 안 남긴다)
      return;
    }
    // 화면 쪽 처리에서 난 예외는 삼키지 않도록 try 밖에서 부른다
    onPicked(photos);
  }

  // 고른 파일들을 PickedPhoto로 바꾼다. 최대 장수를 넘은 뒷부분은 읽지 않고 임시 파일만 지운다.
  // 도중에 하나라도 실패하면 받은 파일 전체의 임시 파일을 지우고 예외를 다시 던진다 (onPicked는 불리지 않는다)
  Future<List<PickedPhoto>> _convertAll(List<XFile> files) async {
    try {
      for (final extra in files.skip(maxPhotos)) {
        await _deleteTempFile(extra.path);
      }
      final photos = <PickedPhoto>[];
      for (final file in files.take(maxPhotos)) {
        photos.add(await _toPickedPhoto(file));
      }
      return photos;
    } catch (_) {
      for (final file in files) {
        await _deleteTempFile(file.path); // 이미 지운 파일은 조용히 넘어간다
      }
      rethrow;
    }
  }

  // XFile 한 장을 PickedPhoto로 바꾼다. 성공하든 실패하든 그 파일의 임시 사본은 지운다
  Future<PickedPhoto> _toPickedPhoto(XFile file) async {
    try {
      final size = await file.length();
      if (size > maxPhotoBytes) {
        // 10MB 초과: 내용은 읽지 않는다. 크기만 알려 주면 사전 검사가 "10MB 초과"로 걸러 낸다
        return PickedPhoto(
          name: file.name,
          // 종류는 빈 글자로 둔다: 종류 검사는 통과하고 크기 검사에서 "10MB 초과"로 걸러진다
          type: '',
          size: size,
          previewUrl: '',
          // 검사에서 걸러져 올리기까지 가지 않는다. 혹시 불리면 빈 사진을 올리지 않도록 실패시킨다
          readBytes: () async => throw StateError('too large'),
        );
      }
      final bytes = await file.readAsBytes();
      return PickedPhoto(
        name: file.name,
        type: detectImageType(bytes),
        size: size,
        previewUrl: '',
        readBytes: () async => bytes,
        previewBytes: bytes,
      );
    } finally {
      await _deleteTempFile(file.path); // 내용을 읽어 뒀으니(또는 실패했으니) 임시 사본은 바로 지운다
    }
  }

  // image_picker가 앱 캐시에 만든 임시 사본을 지운다 (안드로이드만. 실패해도 무시)
  Future<void> _deleteTempFile(String path) async {
    if (!_deleteTempFiles || path.isEmpty) return;
    try {
      await File(path).delete();
    } catch (_) {
      // 일부러 무시
    }
  }
}


// 파일 맨 앞 바이트(매직 넘버)로 사진 종류를 알아낸다. 이름(확장자)은 믿지 않는다
String detectImageType(Uint8List bytes) {
  if (bytes.length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) {
    return 'image/jpeg';
  }
  if (bytes.length >= 4 &&
      bytes[0] == 0x89 &&
      bytes[1] == 0x50 &&
      bytes[2] == 0x4E &&
      bytes[3] == 0x47) {
    return 'image/png';
  }
  return 'application/octet-stream';
}

PhotoPicker createPlatformPhotoPicker() => AppPhotoPicker();
