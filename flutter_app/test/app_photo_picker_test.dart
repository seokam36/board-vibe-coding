// S23-d2 검사: 앱 갤러리 사진 고르기(AppPhotoPicker)와 미리보기 그리기.
// 진짜 갤러리 대신 가짜 함수를 끼우고, 임시 폴더의 가짜 파일(지어낸 바이트)만 쓴다.
// 10MB 파일은 디스크에 쓰지 않고 "길이만 큰 가짜 XFile"로 흉내 낸다.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:board_web/common/app_photo_picker.dart';
import 'package:board_web/common/app_photo_picker_io.dart';
import 'package:board_web/common/file_picker.dart';
import 'package:board_web/common/picked_photo_image.dart';
import 'package:board_web/market/market_photo_picker.dart';
import 'package:board_web/market/photo_upload.dart';
import 'package:board_web/permit/permit_apply_form.dart';
import 'package:board_web/permit/permit_texts.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker/image_picker.dart';
import 'package:material_ui/material_ui.dart';

import 'support/photo_support.dart';
import 'support/screen.dart';

Uint8List _jpeg() => Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 9, 8, 7]);
Uint8List _png() => Uint8List.fromList([0x89, 0x50, 0x4E, 0x47, 5, 4]);
Uint8List _heic() => Uint8List.fromList([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63]);

// 길이·읽기를 가짜로 바꿀 수 있고, 몇 번 불렸는지 세는 XFile.
// path는 진짜 임시 파일을 가리키게 해서 "임시 파일이 지워졌는지"도 볼 수 있다
class _FakeXFile extends XFile {
  _FakeXFile(super.path, {this.fakeLength, this.content, this.failRead = false});

  final int? fakeLength; // 있으면 파일 길이 대신 이 값을 돌려준다 (10MB 흉내)
  final Uint8List? content; // 있으면 읽을 때 이 내용을 돌려준다
  final bool failRead; // true면 읽을 때 예외
  int lengthCalls = 0;
  int readCalls = 0;

  @override
  Future<int> length() async {
    lengthCalls++;
    return fakeLength ?? await super.length();
  }

  @override
  Future<Uint8List> readAsBytes() async {
    readCalls++;
    if (failRead) throw StateError('읽기 실패');
    return content ?? await super.readAsBytes();
  }
}

// 임시 폴더에 가짜 파일을 만든다 (테스트가 끝나면 폴더째 지운다)
Future<File> _tempFile(String name, List<int> bytes) async {
  final dir = await Directory.systemTemp.createTemp('pick_test');
  addTearDown(() async {
    if (dir.existsSync()) await dir.delete(recursive: true);
  });
  return File('${dir.path}${Platform.pathSeparator}$name')..writeAsBytesSync(bytes);
}

// pick은 값을 안 돌려주므로 결과가 올 때까지 잠깐 기다린다. 끝내 안 오면 null
Future<List<PickedPhoto>?> _pick(AppPhotoPicker picker, {bool multiple = true, int waitMs = 1000}) async {
  List<PickedPhoto>? got;
  picker.pick((files) => got = files, multiple: multiple);
  for (var i = 0; i < waitMs ~/ 10 && got == null; i++) {
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
  return got;
}

// 결과가 "안 온다"는 것을 확인할 때: 충분히 기다린 뒤 onPicked가 몇 번 불렸는지 센다
Future<int> _pickCount(AppPhotoPicker picker, {bool multiple = true}) async {
  var called = 0;
  picker.pick((_) => called++, multiple: multiple);
  await Future<void>.delayed(const Duration(milliseconds: 150));
  return called;
}

void main() {
  group('종류·크기·내용·이름 변환', () {
    test('JPEG 파일은 image/jpeg로 바뀌고 크기·이름·내용이 그대로다', () async {
      final file = await _tempFile('trip.jpg', _jpeg());
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)]));
      expect(got!.single.type, 'image/jpeg');
      expect(got.single.size, _jpeg().length);
      expect(got.single.name, 'trip.jpg');
      expect(got.single.previewBytes, _jpeg());
      expect(await got.single.readBytes(), _jpeg());
    });

    test('PNG 파일은 image/png로 바뀌고 이름도 그대로다', () async {
      final file = await _tempFile('card.png', _png());
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)]));
      expect(got!.single.type, 'image/png');
      expect(got.single.name, 'card.png');
      expect(got.single.size, _png().length);
    });

    test('확장자가 jpg여도 내용이 PNG면 image/png다 (이름을 안 믿는다)', () async {
      final file = await _tempFile('fake.jpg', _png());
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)]));
      expect(got!.single.type, 'image/png');
    });

    test('3장 섞어 고르기: 종류는 맨 앞 바이트로 정하고 미리보기 주소는 빈 글자다', () async {
      final picker = AppPhotoPicker(
        pickMany: () async => [
          XFile.fromData(_jpeg(), name: 'a.dat'),
          XFile.fromData(_png(), name: 'b.jpg'), // 이름이 jpg여도 내용은 png
          XFile.fromData(Uint8List.fromList([1, 2, 3, 4]), name: 'c.jpg'),
        ],
      );
      final got = await _pick(picker);
      expect(got!.map((p) => p.type), ['image/jpeg', 'image/png', 'application/octet-stream']);
      expect(got.first.previewBytes, _jpeg());
      expect(await got.first.readBytes(), _jpeg());
      expect(got.first.previewUrl, '');
    });
  });

  group('종류·크기 거르기', () {
    test('HEIC 흉내 파일은 종류 안내로 거른다 (글쓰기 검사)', () async {
      final file = await _tempFile('x.jpg', _heic()); // 이름은 jpg여도 내용은 HEIC
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)]));
      expect(allowedPhotoTypes.contains(got!.single.type), isFalse);
      final check = checkPhotoFiles(got, 0);
      expect(check.accepted, isEmpty);
      expect(check.message, contains('JPEG')); // "종류" 안내
      expect(check.message, isNot(contains('10MB')));
    });

    test('HEIC 흉내 파일은 종류 안내로 거른다 (허가서 검사)', () async {
      final file = await _tempFile('x.heic', _heic());
      final got = await _pick(AppPhotoPicker(pickOne: () async => XFile(file.path)), multiple: false);
      expect(checkPickedPhoto(got!.single), permitTypeOnly);
    });

    test('10MB+1바이트는 크기 안내로 거르고 내용은 읽지 않는다 (readAsBytes가 안 불린다)', () async {
      final file = await _tempFile('big.jpg', _jpeg());
      final big = _FakeXFile(file.path, fakeLength: maxPhotoBytes + 1);
      final got = await _pick(AppPhotoPicker(pickMany: () async => [big]));
      final p = got!.single;
      expect(big.readCalls, 0); // 내용을 읽지 않았다
      expect(p.size, maxPhotoBytes + 1);
      expect(p.previewBytes, isNull);
      expect(p.type, ''); // 종류 검사는 통과하고 크기 검사에서 걸러진다
      // 혹시 올리기까지 오면 빈 사진을 올리지 않고 실패시킨다
      await expectLater(p.readBytes(), throwsStateError);
      final check = checkPhotoFiles(got, 0);
      expect(check.accepted, isEmpty);
      expect(check.message, contains('10MB'));
      expect(checkPickedPhoto(p), permitTooLarge);
    });

    test('정확히 10MB 파일은 크기 검사를 통과한다', () async {
      final file = await _tempFile('edge.jpg', _jpeg());
      final edge = _FakeXFile(file.path, fakeLength: maxPhotoBytes, content: _jpeg());
      final got = await _pick(AppPhotoPicker(pickMany: () async => [edge]));
      expect(got!.single.type, 'image/jpeg');
      expect(got.single.size, maxPhotoBytes);
      expect(checkPhotoFiles(got, 0).accepted, hasLength(1));
      expect(checkPickedPhoto(got.single), isNull);
    });

    test('여러 장 중 일부만 HEIC·10MB 초과여도 나머지는 정상으로 바뀐다', () async {
      final heic = await _tempFile('h.jpg', _heic());
      final okFile = await _tempFile('ok.jpg', _jpeg());
      final bigFile = await _tempFile('big.jpg', _jpeg());
      final big = _FakeXFile(bigFile.path, fakeLength: maxPhotoBytes + 1);
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(heic.path), XFile(okFile.path), big]));
      expect(got, hasLength(3));
      expect(got![1].type, 'image/jpeg');
      expect(got[1].previewBytes, _jpeg());
      expect(big.readCalls, 0);
      final check = checkPhotoFiles(got, 0);
      expect(check.accepted, [got[1]]); // 정상 1장만 받는다
    });
  });

  group('취소·예외', () {
    test('취소(null·빈 목록)·예외는 onPicked 0번이고 밖으로 예외가 새지 않는다', () async {
      var called = 0;
      final leaked = <Object>[];
      await runZonedGuarded(() async {
        AppPhotoPicker(pickOne: () async => null).pick((_) => called++, multiple: false);
        AppPhotoPicker(pickMany: () async => []).pick((_) => called++);
        AppPhotoPicker(pickMany: () async => throw StateError('already_active')).pick((_) => called++);
        AppPhotoPicker(pickOne: () async => throw StateError('boom')).pick((_) => called++, multiple: false);
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }, (e, s) => leaked.add(e));
      expect(called, 0);
      expect(leaked, isEmpty);
    });

    test('onPicked(화면 쪽)에서 난 예외는 삼키지 않는다', () async {
      final leaked = <Object>[];
      await runZonedGuarded(() async {
        AppPhotoPicker(pickMany: () async => [XFile.fromData(_jpeg(), name: 'a.jpg')]).pick((_) => throw StateError('화면 쪽 오류'));
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }, (e, s) => leaked.add(e));
      expect(leaked, hasLength(1));
    });
  });

  group('한 장/여러 장 함수 구분', () {
    test('multiple:false는 한 장 함수만 1번 부른다', () async {
      var one = 0, many = 0;
      final file = await _tempFile('a.png', _png());
      final picker = AppPhotoPicker(
        pickOne: () async {
          one++;
          return XFile(file.path);
        },
        pickMany: () async {
          many++;
          return [];
        },
      );
      await _pick(picker, multiple: false);
      expect([one, many], [1, 0]);
    });

    test('multiple:true는 여러 장 함수만 1번 부른다', () async {
      var one = 0, many = 0;
      final file = await _tempFile('a.png', _png());
      final picker = AppPhotoPicker(
        pickOne: () async {
          one++;
          return null;
        },
        pickMany: () async {
          many++;
          return [XFile(file.path)];
        },
      );
      await _pick(picker);
      expect([one, many], [0, 1]);
    });
  });

  group('임시 파일 삭제', () {
    test('변환이 끝나면 임시 파일이 디스크에 없다', () async {
      final file = await _tempFile('t.jpg', _jpeg());
      await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)], deleteTempFiles: true));
      expect(file.existsSync(), isFalse);
    });

    test('크기 초과 파일의 임시 파일도 지워진다', () async {
      final file = await _tempFile('big.jpg', _jpeg());
      final big = _FakeXFile(file.path, fakeLength: maxPhotoBytes + 1);
      await _pick(AppPhotoPicker(pickMany: () async => [big], deleteTempFiles: true));
      expect(file.existsSync(), isFalse);
    });

    test('deleteTempFiles: false면 원본을 지우지 않는다 (데스크톱은 원본 경로)', () async {
      final file = await _tempFile('keep.jpg', _jpeg());
      final got = await _pick(AppPhotoPicker(pickMany: () async => [XFile(file.path)], deleteTempFiles: false));
      expect(got!.single.type, 'image/jpeg');
      expect(file.existsSync(), isTrue);
    });

    test('읽기 실패(첫 장 경로 없음): onPicked 0번, 둘째 장 임시 파일도 지워진다', () async {
      final second = await _tempFile('second.jpg', _jpeg());
      final missing = '${second.parent.path}${Platform.pathSeparator}none.jpg'; // 없는 파일
      final picker = AppPhotoPicker(
        pickMany: () async => [XFile(missing), XFile(second.path)],
        deleteTempFiles: true,
      );
      expect(await _pickCount(picker), 0);
      expect(second.existsSync(), isFalse);
    });

    test('읽기 실패(둘째 장 읽다 예외): onPicked 0번, 이미 읽은 첫 장 임시 파일도 없다', () async {
      final first = await _tempFile('first.jpg', _jpeg());
      final secondFile = await _tempFile('second.jpg', _jpeg());
      final second = _FakeXFile(secondFile.path, failRead: true);
      final picker = AppPhotoPicker(
        pickMany: () async => [XFile(first.path), second],
        deleteTempFiles: true,
      );
      expect(await _pickCount(picker), 0);
      expect(first.existsSync(), isFalse);
      expect(secondFile.existsSync(), isFalse);
    });
  });

  group('최대 장수', () {
    test('11장을 받아도 10장만 읽고, 11번째는 읽지 않고 임시 파일만 지운다', () async {
      final files = <_FakeXFile>[];
      final disk = <File>[];
      for (var i = 0; i < maxPhotos + 1; i++) {
        final f = await _tempFile('p$i.jpg', _jpeg());
        disk.add(f);
        files.add(_FakeXFile(f.path));
      }
      final got = await _pick(AppPhotoPicker(pickMany: () async => files, deleteTempFiles: true));
      expect(got, hasLength(maxPhotos));
      for (var i = 0; i < maxPhotos; i++) {
        expect(files[i].readCalls, 1);
      }
      expect(files.last.readCalls, 0); // 11번째는 안 읽는다
      expect(files.last.lengthCalls, 0); // 크기도 안 본다
      expect(disk.last.existsSync(), isFalse); // 임시 파일은 지워진다
      expect(got!.every((p) => p.previewBytes != null), isTrue); // 빈 사진으로 넘기지 않는다
    });
    // 갤러리 창 자체의 제한: 여러 장 기본 함수가 ImagePicker().pickMultiImage(limit: maxPhotos)를 부르는 것은
    // app_photo_picker_io.dart의 _defaultPickMany에서 코드로 확인한다 (VM에는 진짜 갤러리가 없다)
  });

  group('미리보기 그리기', () {
    final bytes = _jpeg();
    // 아주 작은 진짜 PNG(1x1): 그림 캐시에 실제로 올라가는지 볼 때 쓴다
    final tinyPng = base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    );

    // cacheWidth를 주면 ResizeImage로 감싸지므로 안쪽 그림 출처를 꺼내 비교한다
    ImageProvider imageOf(WidgetTester tester) {
      final image = tester.widget<Image>(find.byType(Image)).image;
      return image is ResizeImage ? image.imageProvider : image;
    }

    Future<void> pumpMarket(WidgetTester tester, List<PhotoTile> items) async {
      useTallScreen(tester);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: MarketPhotoPicker(
                items: items,
                disabled: false,
                progress: null,
                error: null,
                needLogin: false,
                removeLabel: '빼기',
                onAdd: (_) {},
                onRemove: (_) {},
              ),
            ),
          ),
        ),
      );
    }

    Future<void> pumpPermit(WidgetTester tester, PickedPhoto photo) async {
      useTallScreen(tester);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: PermitApplyForm(
                consent: true,
                photo: photo,
                submitting: false,
                checkMessage: null,
                error: null,
                onConsentChanged: (_) {},
                onPickPhoto: () {},
                onSubmit: () {},
              ),
            ),
          ),
        ),
      );
    }

    PickedPhoto withBytes() => PickedPhoto(
      name: 'mine.jpg',
      type: 'image/jpeg',
      size: bytes.length,
      previewUrl: '',
      readBytes: () async => bytes,
      previewBytes: bytes,
    );

    testWidgets('중고 글쓰기 사진 칸: 바이트가 있으면 메모리 그림', (tester) async {
      await pumpMarket(tester, [PhotoTile(key: 1, src: '', bytes: bytes)]);
      expect(imageOf(tester), isA<MemoryImage>());
    });

    testWidgets('중고 글쓰기 사진 칸: 바이트가 없으면 Image.network', (tester) async {
      await pumpMarket(tester, [PhotoTile(key: 1, src: 'blob:fake/a')]);
      expect(imageOf(tester), isA<NetworkImage>());
    });

    testWidgets('허가서 신청 폼: 바이트가 있으면 메모리 그림', (tester) async {
      await pumpPermit(tester, withBytes());
      expect(imageOf(tester), isA<MemoryImage>());
    });

    testWidgets('허가서 신청 폼: 기존 가짜 사진(바이트 없음)은 Image.network', (tester) async {
      await pumpPermit(tester, fakePhoto('old.jpg'));
      expect(imageOf(tester), isA<NetworkImage>());
    });

    testWidgets('칸이 사라지면 원본 바이트가 그림 캐시에서 빠진다', (tester) async {
      final cache = PaintingBinding.instance.imageCache..clear();
      Widget host(bool show) => MaterialApp(
        home: Scaffold(
          body: show ? PickedPhotoImage(bytes: tinyPng, url: '', cacheWidth: 112) : const SizedBox(),
        ),
      );
      await tester.runAsync(() async {
        await tester.pumpWidget(host(true));
        await Future<void>.delayed(const Duration(milliseconds: 200)); // 그림 풀기
        await tester.pump();
      });
      expect(cache.currentSize + cache.liveImageCount, greaterThan(0)); // 캐시에 올라가 있다
      await tester.pumpWidget(host(false)); // 칸이 사라진다
      expect(cache.currentSize, 0);
      expect(cache.liveImageCount, 0);
    });
  });

  group('앱 갈래 입구', () {
    test('createAppPhotoPicker()는 VM에서 AppPhotoPicker를 돌려준다', () {
      expect(createAppPhotoPicker(), isA<AppPhotoPicker>());
    });
  });
}
