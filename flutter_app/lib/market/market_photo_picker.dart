import 'package:material_ui/material_ui.dart';

import '../common/file_picker.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/notice.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/section_parts.dart';
import '../widgets/small_button_style.dart';
import 'photo_upload.dart';

// 사진 한 칸에 보여 줄 것 (src는 썸네일 주소: 수정 화면은 서버 썸네일, 글쓰기 화면은 미리보기 blob 주소)
class PhotoTile {
  const PhotoTile({required this.key, required this.src});

  final Object key;
  final String src;
}

// 글쓰기·수정 화면 공용 사진 영역 (React의 MarketPhotoPicker).
// 목록 그리기 + 파일 고르기 + 브라우저 사전 검사까지만 하고, 실제로 올리고 지우는 일은 화면(부모)이 한다.
class MarketPhotoPicker extends StatefulWidget {
  const MarketPhotoPicker({
    super.key,
    required this.items,
    required this.disabled,
    required this.progress,
    required this.error,
    required this.needLogin,
    required this.removeLabel,
    required this.onAdd,
    required this.onRemove,
  });

  final List<PhotoTile> items;
  final bool disabled; // 올리는 중·지우는 중·저장 중이면 추가·삭제 버튼을 잠근다
  final String? progress; // "사진 올리는 중 2/5" (없으면 null)
  final String? error; // 서버 요청이 실패한 문구
  final bool needLogin; // 요청 중 401: 로그인 안내
  final String removeLabel; // 수정 화면은 서버에서 지우니 '삭제', 글쓰기 화면은 미리보기만 빼니 '빼기'
  final void Function(List<PickedPhoto> files) onAdd; // 사전 검사를 통과한 파일만 넘어온다
  final void Function(Object key) onRemove;

  @override
  State<MarketPhotoPicker> createState() => _MarketPhotoPickerState();
}

class _MarketPhotoPickerState extends State<MarketPhotoPicker> {
  String? _checkMessage; // 사전 검사에서 뺀 이유

  // [사진 추가]: 브라우저 파일 창을 연다 (누른 순간 안에서 바로 불러야 브라우저가 열어 준다)
  void _open() => photoPicker.pick(_handlePicked);

  void _handlePicked(List<PickedPhoto> files) {
    // 화면을 떠났거나 올리는 중이면 겹쳐 실행하지 않는다 (미리보기 주소는 반납)
    if (!mounted || widget.disabled) {
      for (final f in files) {
        f.release();
      }
      return;
    }
    final check = checkPhotoFiles(files, widget.items.length);
    setState(() => _checkMessage = check.message);
    // 받지 않은 파일의 미리보기 주소는 바로 반납한다
    for (final f in files) {
      if (!check.accepted.contains(f)) f.release();
    }
    if (check.accepted.isNotEmpty) widget.onAdd(check.accepted);
  }

  @override
  Widget build(BuildContext context) {
    final items = widget.items;
    final full = items.length >= maxPhotos;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SectionHeading('사진 (${items.length}/$maxPhotos)'),
          if (items.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space3),
              child: Wrap(
                spacing: AppSizes.space3,
                runSpacing: AppSizes.space3,
                children: [
                  for (var i = 0; i < items.length; i++)
                    _PhotoItem(
                      // 같은 key면 지워도 다른 칸의 그림이 다시 불러와지지 않는다
                      key: ValueKey('photo-item-${items[i].key}'),
                      number: i + 1,
                      src: items[i].src,
                      removeLabel: widget.removeLabel,
                      disabled: widget.disabled,
                      onRemove: () => widget.onRemove(items[i].key),
                    ),
                ],
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                OutlinedButton(
                  key: const ValueKey('photo-add-button'),
                  onPressed: widget.disabled || full ? null : _open,
                  child: const Text('사진 추가'),
                ),
                if (full) const FieldHint(ErrorMessages.marketPhotoMaxCount),
              ],
            ),
          ),
          if (widget.progress != null) Notice(kind: NoticeKind.info, message: widget.progress),
          if (_checkMessage != null) Notice(kind: NoticeKind.error, message: _checkMessage),
          if (widget.error != null) Notice(kind: NoticeKind.error, message: widget.error),
          if (widget.needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}

// 72px 정사각 미리보기 + 그 아래 [빼기]/[삭제] 버튼 (React의 .market-photo-item)
class _PhotoItem extends StatelessWidget {
  const _PhotoItem({
    super.key,
    required this.number,
    required this.src,
    required this.removeLabel,
    required this.disabled,
    required this.onRemove,
  });

  final int number;
  final String src;
  final String removeLabel;
  final bool disabled;
  final VoidCallback onRemove;

  static const size = 72.0;

  @override
  Widget build(BuildContext context) {
    final label = '사진 $number $removeLabel'; // 스크린리더용 이름 ("사진 2 삭제")
    return SizedBox(
      width: size,
      child: Column(
        children: [
          // 미리보기 그림: 이름은 "사진 n". 못 불러오면 같은 크기의 회색 칸
          Semantics(
            label: '사진 $number',
            image: true,
            child: ExcludeSemantics(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
                child: SizedBox(
                  width: size,
                  height: size,
                  child: ColoredBox(
                    color: AppColors.hover,
                    child: Image.network(
                      src,
                      width: size,
                      height: size,
                      fit: BoxFit.cover,
                      errorBuilder: (context, error, stack) => const SizedBox.shrink(),
                    ),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            button: true,
            enabled: !disabled,
            label: label,
            onTap: disabled ? null : onRemove,
            excludeSemantics: true, // 안쪽 글자("삭제")보다 위의 이름이 먼저 읽히게 한다
            child: OutlinedButton(
              style: smallButtonStyle,
              onPressed: disabled ? null : onRemove,
              child: Text(removeLabel),
            ),
          ),
        ],
      ),
    );
  }
}
