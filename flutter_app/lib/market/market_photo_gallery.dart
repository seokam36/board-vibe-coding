import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'market_format.dart';
import 'models.dart';

// 상세 화면의 사진 영역 (React의 .market-photos): 큰 사진 + (2장 이상이면) 56px 썸네일 버튼 줄.
// 사진이 0장이면 이 위젯을 아예 그리지 않는 건 부르는 쪽 몫이다.
// index는 지금 큰 사진으로 보는 번호(0부터, 이미 범위 안으로 맞춰서 넘긴다).
class MarketPhotoGallery extends StatelessWidget {
  const MarketPhotoGallery({
    super.key,
    required this.itemId,
    required this.photos,
    required this.index,
    required this.onSelect,
  });

  final int itemId;
  final List<MarketPhoto> photos;
  final int index;
  final void Function(int index) onSelect;

  @override
  Widget build(BuildContext context) {
    final current = photos[index];
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // key가 사진 id라 다른 사진을 고르면 위젯이 새로 만들어져 다시 불러와 본다(실패 상태가 이어지지 않는다)
          MarketMainPhoto(
            key: ValueKey('market-main-photo-${current.id}'),
            url: marketPhotoUrl(itemId, current.id),
            label: '사진 ${index + 1} / ${photos.length}',
          ),
          if (photos.length >= 2)
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space2),
              child: Wrap(
                spacing: AppSizes.space2,
                runSpacing: AppSizes.space2,
                children: [
                  for (var i = 0; i < photos.length; i++)
                    _PhotoThumbButton(
                      key: ValueKey('market-photo-thumb-${photos[i].id}'),
                      url: marketPhotoUrl(itemId, photos[i].id, thumb: true),
                      number: i + 1,
                      selected: i == index,
                      onTap: () => onSelect(i),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

// 큰 사진 틀: 4:3 고정이라 사진 비율이 달라도 아래 썸네일 줄이 움직이지 않는다. 사진은 잘리지 않게 contain.
// 불러오지 못하면 같은 틀 안에 회색 안내 글을 보여 준다(이때는 사진이 없으니 "사진 n / 전체" 이름도 함께 없어진다).
class MarketMainPhoto extends StatelessWidget {
  const MarketMainPhoto({super.key, required this.url, required this.label});

  final String url;
  final String label; // 스크린리더 이름 "사진 1 / 3"

  static const failedText = '사진을 불러오지 못했습니다';

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 4 / 3,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
        child: ColoredBox(
          color: AppColors.canvas,
          child: Center(
            child: Image.network(
              url,
              width: double.infinity,
              height: double.infinity,
              fit: BoxFit.contain,
              // 실패하면 이 자리에 안내 글만 (Image 자체가 사라지므로 이름도 사라진다)
              frameBuilder: (context, child, frame, sync) => Semantics(label: label, image: true, child: ExcludeSemantics(child: child)),
              errorBuilder: (context, error, stack) => const Text(
                failedText,
                style: TextStyle(color: AppColors.muted, fontSize: AppSizes.fsSmall2),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// 56px 정사각 썸네일 버튼. 고른 것은 청자색 테두리 + selected. 이름은 "사진 n 보기"(그림은 장식).
// Tab으로 오고 Enter·Space로 누를 수 있다(InkWell 기본 동작).
class _PhotoThumbButton extends StatelessWidget {
  const _PhotoThumbButton({
    super.key,
    required this.url,
    required this.number,
    required this.selected,
    required this.onTap,
  });

  final String url;
  final int number;
  final bool selected;
  final VoidCallback onTap;

  static const size = 56.0;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      selected: selected,
      label: '사진 $number 보기',
      excludeSemantics: true, // 안쪽 그림의 정보는 버튼 이름이 대신한다
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
        child: Container(
          width: size,
          height: size,
          clipBehavior: Clip.antiAlias,
          decoration: BoxDecoration(
            color: AppColors.hover,
            borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
            border: Border.all(color: selected ? AppColors.celadon : Colors.transparent, width: 2),
          ),
          child: Image.network(
            url,
            fit: BoxFit.cover,
            // 56px 칸에 필요한 크기로만 풀어 메모리를 아낀다 (가로만 줘서 비율 유지)
            cacheWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
            // 썸네일이 깨져도 버튼(회색 칸)은 그대로 남긴다
            errorBuilder: (context, error, stack) => const SizedBox.shrink(),
          ),
        ),
      ),
    );
  }
}
