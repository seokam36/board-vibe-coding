import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'market_format.dart';
import 'models.dart';

// 목록 행 왼쪽의 72px 정사각 썸네일 (React의 MarketThumb).
// 대표 사진이 없거나 불러오지 못하면 같은 크기의 회색 빈칸을 그린다(빈칸은 장식이라 스크린리더가 읽지 않는다).
class MarketThumb extends StatefulWidget {
  const MarketThumb({super.key, required this.item});

  final MarketListItem item;

  static const size = 72.0;

  @override
  State<MarketThumb> createState() => _MarketThumbState();
}

class _MarketThumbState extends State<MarketThumb> {
  // 사진 파일이 깨졌을 때(errorBuilder) true. 빈칸으로 바꾸고 "대표 사진" 이름도 함께 뺀다 (React의 broken)
  bool _broken = false;

  Widget get _empty => const ExcludeSemantics(
        child: SizedBox(
          width: MarketThumb.size,
          height: MarketThumb.size,
          child: ColoredBox(color: AppColors.hover),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final item = widget.item;
    final photo = item.coverPhoto;
    final Widget child;
    if (photo == null || _broken) {
      child = _empty;
    } else {
      child = Semantics(
        label: '${item.title} 대표 사진',
        image: true,
        child: ExcludeSemantics(
          child: Image.network(
            marketPhotoUrl(item.id, photo.id, thumb: true),
            width: MarketThumb.size,
            height: MarketThumb.size,
            fit: BoxFit.cover,
            errorBuilder: (context, error, stack) {
              // build 도중에 setState를 못 하니 그린 직후에 표시를 바꾼다. 그 사이엔 빈칸을 보여 준다
              WidgetsBinding.instance.addPostFrameCallback((_) {
                if (mounted && !_broken) setState(() => _broken = true);
              });
              return _empty;
            },
          ),
        ),
      );
    }
    return ClipRRect(borderRadius: BorderRadius.circular(AppSizes.radiusBtn), child: child);
  }
}
