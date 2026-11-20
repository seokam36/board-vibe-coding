import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';

// 고른 사진의 작은 미리보기 (글쓰기 사진 칸·허가서 신청 폼이 같이 쓴다).
// 바이트가 있으면(앱) 메모리 그림, 없으면(웹·서버 썸네일) 주소로 불러온다.
// 앱은 10MB짜리 원본 바이트를 들고 있어서, 그림 캐시(ImageCache)에 원본이 계속 남지 않게
// 이 칸이 사라지거나 바이트가 바뀔 때 캐시에서 직접 뺀다 (캐시 열쇠가 "줄인 그림"이라 그림을 만든 이 자리에서만 정확히 뺄 수 있다)
class PickedPhotoImage extends StatefulWidget {
  const PickedPhotoImage({
    super.key,
    required this.bytes,
    required this.url,
    required this.cacheWidth,
    this.width,
    this.height,
  });

  final Uint8List? bytes;
  final String url;
  final int cacheWidth; // 칸 크기에 맞춰 줄여서 푼다 (가로만 줘서 사진 비율은 그대로 지킨다)
  final double? width;
  final double? height;

  @override
  State<PickedPhotoImage> createState() => _PickedPhotoImageState();
}

class _PickedPhotoImageState extends State<PickedPhotoImage> {
  ImageProvider? _memory; // 바이트로 만든 그림 (웹·썸네일이면 null)

  @override
  void initState() {
    super.initState();
    _memory = _makeProvider();
  }

  @override
  void didUpdateWidget(PickedPhotoImage old) {
    super.didUpdateWidget(old);
    // 다른 사진·다른 크기로 바뀌면 이전 그림을 캐시에서 빼고 새로 만든다
    if (!identical(old.bytes, widget.bytes) || old.cacheWidth != widget.cacheWidth) {
      _evict();
      _memory = _makeProvider();
    }
  }

  @override
  void dispose() {
    _evict();
    super.dispose();
  }

  ImageProvider? _makeProvider() {
    final bytes = widget.bytes;
    return bytes == null ? null : ResizeImage(MemoryImage(bytes), width: widget.cacheWidth);
  }

  void _evict() {
    _memory?.evict(); // 결과를 기다릴 필요는 없다
    _memory = null;
  }

  @override
  Widget build(BuildContext context) {
    final memory = _memory;
    if (memory != null) {
      return Image(
        image: memory,
        width: widget.width,
        height: widget.height,
        fit: BoxFit.cover,
        errorBuilder: (context, error, stack) => const SizedBox.shrink(),
      );
    }
    return Image.network(
      widget.url,
      width: widget.width,
      height: widget.height,
      fit: BoxFit.cover,
      cacheWidth: widget.cacheWidth,
      errorBuilder: (context, error, stack) => const SizedBox.shrink(),
    );
  }
}
