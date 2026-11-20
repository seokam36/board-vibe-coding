// 웹·기본용: 웹은 main.dart에서 이 도구를 쓰지 않고 기존 file_picker_web 방식을 쓴다.
// 혹시 불려도 해롭지 않게 아무것도 하지 않는다.
import 'file_picker.dart';

class _NoopAppPicker implements PhotoPicker {
  @override
  void pick(void Function(List<PickedPhoto> files) onPicked, {bool multiple = true}) {}
}

PhotoPicker createPlatformPhotoPicker() => _NoopAppPicker();
