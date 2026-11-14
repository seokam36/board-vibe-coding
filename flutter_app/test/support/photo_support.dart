// 사진 올리기 테스트 도구: 가짜 파일 선택기·가짜 사진, 그리고 FormData 본문 기록.
import 'dart:convert';
import 'dart:typed_data';

import 'package:board_web/common/file_picker.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'fake_adapter.dart';

// 가짜 파일 선택기: [사진 추가]가 pick을 부르면 처리 함수를 기억해 두고, 테스트가 choose로 "파일을 골랐다"고 알린다
class FakePhotoPicker implements PhotoPicker {
  void Function(List<PickedPhoto> files)? _callback;
  int opened = 0; // 파일 창이 열린 횟수

  @override
  void pick(void Function(List<PickedPhoto> files) onPicked) {
    opened++;
    _callback = onPicked;
  }

  void choose(List<PickedPhoto> files) => _callback!(files);
}

// 테스트마다 가짜 선택기를 끼우고, 끝나면 원래대로 돌려놓는다
FakePhotoPicker installFakePicker() {
  final original = photoPicker;
  final fake = FakePhotoPicker();
  photoPicker = fake;
  addTearDown(() => photoPicker = original);
  return fake;
}

// 가짜 사진. 내용은 'fake-이름' 글자. 읽은 횟수(reads)와 반납 여부(released)를 기록한다
class FakePhoto extends PickedPhoto {
  FakePhoto._(String name, String type, int size, this.log)
    : super(
        name: name,
        type: type,
        size: size,
        previewUrl: 'blob:fake/$name',
        readBytes: () async {
          log.reads.add(name);
          return Uint8List.fromList(utf8.encode('fake-$name'));
        },
        onRelease: () => log.released.add(name),
      );

  final PhotoLog log;
}

// 사진 읽기·반납 기록 (한 테스트가 여러 장을 한 기록에 모은다)
class PhotoLog {
  final List<String> reads = [];
  final List<String> released = [];
}

PickedPhoto fakePhoto(String name, {String type = 'image/jpeg', int size = 100, PhotoLog? log}) =>
    FakePhoto._(name, type, size, log ?? PhotoLog());

// 올린 요청 한 건: 본문(FormData)의 복사본을 읽어 글자로 모은다.
// (dio가 이미 원본을 소진(finalize)했기 때문에 복사본으로 읽는다. 원본은 data로 들고 있어 같은 객체인지 비교할 수 있다)
class SentForm {
  SentForm(this.data) {
    final chunks = <int>[];
    data.clone().finalize().listen(chunks.addAll, onDone: () => body = utf8.decode(chunks, allowMalformed: true));
  }

  final FormData data;
  String body = ''; // 요청이 다 읽힌 뒤 채워진다 (pump 뒤에 확인)
}

// 어댑터가 받는 FormData 본문을 순서대로 모은다 (Map 같은 다른 본문은 무시)
List<SentForm> recordForms(FakeAdapter adapter) {
  final list = <SentForm>[];
  adapter.onRequestBody = (d) {
    if (d is FormData) list.add(SentForm(d));
  };
  return list;
}

// 사진 올리기 응답(201) 본문
Map<String, dynamic> photoJson(int id) => {
  'id': id,
  'width': 1365,
  'height': 2048,
  'thumbWidth': 213,
  'thumbHeight': 320,
  'contentType': 'image/jpeg',
};
