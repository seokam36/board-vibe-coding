// 웹이 아닌 곳(VM 테스트 등)용: 브라우저 파일 창이 없으니 아무것도 하지 않는다.
// 테스트는 photoPicker를 가짜로 바꿔서 "고른 파일"을 직접 넣어 준다.
import 'file_picker.dart';

class _NoopPicker implements PhotoPicker {
  @override
  void pick(void Function(List<PickedPhoto> files) onPicked) {}
}

PhotoPicker createPlatformPhotoPicker() => _NoopPicker();
