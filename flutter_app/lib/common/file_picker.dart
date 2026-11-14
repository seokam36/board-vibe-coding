// 사진 파일을 고르는 도우미 (file_picker 패키지 대신 직접 만든 아주 작은 버전).
// 브라우저에서만 <input type=file>을 쓸 수 있고 테스트(VM)에는 브라우저가 없다.
// 그래서 external_link처럼 조건부 import: 웹이면 진짜 파일 창, 아니면 아무것도 안 하는 구현을 쓴다.
import 'dart:typed_data';

import 'file_picker_stub.dart'
    if (dart.library.js_interop) 'file_picker_web.dart'
    as platform;

// 사용자가 고른 사진 한 장의 "겉 정보" (이름·종류·크기). 내용(바이트)은 올릴 때만 읽는다.
// 파일 안 열어도 되는 사전 검사(종류·크기)를 하려고 겉 정보와 내용을 나눠 둔다.
class PickedPhoto {
  PickedPhoto({
    required this.name,
    required this.type,
    required this.size,
    required this.previewUrl,
    required this.readBytes,
    this.onRelease,
  });

  final String name;
  final String type; // 브라우저가 알려 준 MIME 종류 (모르면 빈 글자)
  final int size; // 바이트
  final String previewUrl; // 미리보기용 주소 (웹은 blob 주소)
  final Future<Uint8List> Function() readBytes; // 파일 내용을 읽는다 (올리기 직전에 한 장씩)
  final void Function()? onRelease; // 미리보기 주소를 반납하는 함수 (웹: revokeObjectURL)
  bool _released = false;

  // 더 안 쓰는 사진의 미리보기 주소를 반납한다. 두 번 불러도 한 번만 동작한다
  void release() {
    if (_released) return;
    _released = true;
    onRelease?.call();
  }
}

abstract interface class PhotoPicker {
  // 파일 선택 창을 연다. 사용자가 고르면 onPicked로 사진 목록을 넘긴다 (취소하면 아무 일도 없다).
  // 브라우저는 "사용자가 누른 순간"에만 창을 열어 주므로, 버튼 눌림 처리 안에서 바로 불러야 한다
  void pick(void Function(List<PickedPhoto> files) onPicked);
}

// 화면이 쓰는 파일 선택 도구. 테스트는 이 값을 가짜로 바꿔 끼운다
PhotoPicker photoPicker = platform.createPlatformPhotoPicker();
