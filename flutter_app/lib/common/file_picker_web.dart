// 웹용: 눈에 안 보이는 <input type=file>을 만들어 click()으로 파일 창을 연다 (package:web)
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'file_picker.dart';

class _WebPicker implements PhotoPicker {
  // 입력칸은 하나만 만들어 계속 쓴다 (고를 때마다 새로 만들면 취소한 것이 body에 쌓인다)
  web.HTMLInputElement? _input;

  web.HTMLInputElement _ensureInput() {
    final existing = _input;
    if (existing != null) return existing;
    final input = web.HTMLInputElement()
      ..type = 'file'
      ..accept = 'image/jpeg,image/png'
      ..multiple = true
      ..tabIndex = -1;
    // display:none은 쓰지 않는다 (일부 브라우저에서 click()이 안 먹는다). 크기 1px·투명으로 "눈에만" 안 보이게 한다
    input.style.cssText =
        'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;overflow:hidden;pointer-events:none;';
    input.setAttribute('aria-hidden', 'true');
    web.document.body?.appendChild(input);
    return _input = input;
  }

  @override
  void pick(void Function(List<PickedPhoto> files) onPicked) {
    final input = _ensureInput();
    // 파일을 고르면(change) 겉 정보를 만들어 넘기고, 입력값을 비워 같은 파일을 다시 골라도 change가 일어나게 한다.
    // 마지막으로 연 창의 처리 함수만 남도록 매번 새로 단다 (취소한 창의 옛 처리 함수가 남지 않게)
    input.onchange = ((web.Event _) {
      final list = input.files;
      final files = <PickedPhoto>[];
      if (list != null) {
        for (var i = 0; i < list.length; i++) {
          final file = list.item(i);
          if (file != null) files.add(_toPicked(file));
        }
      }
      input.value = '';
      if (files.isNotEmpty) onPicked(files);
    }).toJS;
    input.click();
  }

  PickedPhoto _toPicked(web.File file) {
    final url = web.URL.createObjectURL(file);
    return PickedPhoto(
      name: file.name,
      type: file.type,
      size: file.size,
      previewUrl: url,
      // 파일 내용은 올릴 때 한 장씩 읽는다: arrayBuffer(JS) → Dart의 Uint8List
      readBytes: () async {
        final buffer = await file.arrayBuffer().toDart;
        return buffer.toDart.asUint8List();
      },
      onRelease: () => web.URL.revokeObjectURL(url),
    );
  }
}

PhotoPicker createPlatformPhotoPicker() => _WebPicker();
