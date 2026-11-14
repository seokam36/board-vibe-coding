import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../common/file_picker.dart';
import '../errors/error_messages.dart';
import '../widgets/confirm_dialog.dart';
import 'market_format.dart';
import 'market_photo_picker.dart';
import 'models.dart';
import 'photo_upload.dart';

// 수정 화면의 사진 영역 (React의 MarketPhotoEditor).
// 사진은 [저장] 버튼과 상관없이 올리거나 지우는 즉시 서버에 반영된다.
class MarketPhotoEditor extends StatefulWidget {
  const MarketPhotoEditor({
    super.key,
    required this.itemId,
    required this.initial,
    this.onBusyChange,
    this.disabled = false,
  });

  final int itemId;
  final List<MarketPhoto> initial;
  final void Function(bool busy)? onBusyChange; // 올리는 중·지우는 중인지 부모에게 알린다 (글자 [저장] 잠금용)
  final bool disabled; // 부모가 밖에서 잠그는 값 (글자 저장 중이면 사진 추가·삭제도 잠근다)

  @override
  State<MarketPhotoEditor> createState() => _MarketPhotoEditorState();
}

class _MarketPhotoEditorState extends State<MarketPhotoEditor> {
  late List<MarketPhoto> _photos = List.of(widget.initial);
  // 올리는 중·지우는 중. 필드라서 같은 순간에 두 번 시작되는 것을 바로 막는다 (확인 창이 떠 있는 동안도 포함)
  bool _busy = false;
  String? _progress;
  String? _error;
  bool _needLogin = false;

  // 바쁨 상태를 바꾸고 부모(글자 [저장] 잠금)에게도 알린다
  void _setBusy(bool busy) {
    setState(() => _busy = busy);
    widget.onBusyChange?.call(busy);
  }

  // 고른 사진을 한 장씩 올린다. 성공한 사진은 올라오는 대로 목록에 붙인다
  Future<void> _add(List<PickedPhoto> files) async {
    if (_busy) {
      for (final f in files) {
        f.release();
      }
      return;
    }
    final dio = context.read<ApiClient>().dio;
    _setBusy(true);
    setState(() {
      _error = null;
      _needLogin = false;
    });
    try {
      final result = await uploadPhotosInOrder(
        dio,
        widget.itemId,
        files,
        (cur, total) {
          if (mounted) setState(() => _progress = '사진 올리는 중 $cur/$total');
        },
        (photo) {
          if (mounted) setState(() => _photos = [..._photos, photo]);
        },
        () => !mounted, // 화면을 떠났으면 남은 사진은 보내지 않는다
      );
      if (!mounted) return;
      if (result.failed > 0) {
        final info = uploadFailureText(result);
        setState(() {
          if (info is ErrorMessage) {
            _error = info.message;
          } else {
            _needLogin = true;
          }
        });
      }
    } finally {
      // 예외가 나도 잠금이 풀리게 한다. 올린 사진의 미리보기 주소도 반납
      for (final f in files) {
        f.release();
      }
      if (mounted) {
        setState(() => _progress = null);
        _setBusy(false);
      }
    }
  }

  // 사진 삭제: 확인 창 → DELETE. 이미 지워진 사진(404)은 목록에서도 뺀다
  Future<void> _remove(int photoId) async {
    if (_busy) return;
    final index = _photos.indexWhere((p) => p.id == photoId);
    if (index < 0) return;
    final dio = context.read<ApiClient>().dio;
    _setBusy(true); // 확인 창이 떠 있는 동안에도 다른 작업이 끼어들지 못하게 먼저 잠근다
    final ok = await showConfirm(
      context,
      message: '사진 ${index + 1}번을 삭제할까요?',
      confirmLabel: '삭제',
      danger: true,
    );
    if (!mounted) return;
    if (!ok) {
      _setBusy(false);
      return;
    }
    setState(() {
      _error = null;
      _needLogin = false;
    });
    try {
      await dio.delete<dynamic>('/market-items/${widget.itemId}/photos/$photoId');
      if (mounted) setState(() => _photos = _photos.where((p) => p.id != photoId).toList());
    } catch (e) {
      if (!mounted) return;
      final info = describeError(e, photoDeleteErrors, ErrorMessages.deleteFailed);
      setState(() {
        if (info is ErrorMessage) {
          _error = info.message;
          if (info.status == 404) _photos = _photos.where((p) => p.id != photoId).toList();
        } else {
          _needLogin = true;
        }
      });
    } finally {
      if (mounted) _setBusy(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return MarketPhotoPicker(
      items: [
        for (final p in _photos)
          PhotoTile(key: p.id, src: marketPhotoUrl(widget.itemId, p.id, thumb: true)),
      ],
      disabled: _busy || widget.disabled,
      progress: _progress,
      error: _error,
      needLogin: _needLogin,
      removeLabel: '삭제',
      onAdd: _add,
      onRemove: (key) => _remove(key as int),
    );
  }
}
