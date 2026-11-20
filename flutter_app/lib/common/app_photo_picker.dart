// 앱(안드로이드 등)에서 갤러리 사진을 고르는 도우미.
// 웹은 file_picker_web.dart(<input type=file>)를 쓰지만, 앱에는 브라우저가 없어서 image_picker로 고른다.
// (조건부 import: dart.library.io 가 있는 곳 = 앱·VM일 때만 app_photo_picker_io.dart 를 가져온다)
import 'file_picker.dart';
import 'app_photo_picker_stub.dart'
    if (dart.library.io) 'app_photo_picker_io.dart'
    as platform;

// 앱이면 image_picker로 고르는 도구를, 웹이면 아무것도 안 하는 도구를 돌려준다.
// main.dart의 앱(비웹) 갈래에서 photoPicker에 끼워 넣는다.
PhotoPicker createAppPhotoPicker() => platform.createPlatformPhotoPicker();
