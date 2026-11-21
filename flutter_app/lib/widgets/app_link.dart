import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 글자 링크 (React의 <a>): 포인트색 밑줄 글자. 누르면 onTap(보통 context.go)
class AppLink extends StatelessWidget {
  const AppLink({super.key, required this.label, required this.onTap, this.style, this.tall = false, this.oneLine = false});

  final String label;
  // true면 글자를 한 줄로 두고 길면 끝을 줄임표로 줄인다(긴 제목이 칸 높이를 키우지 않게). 기본은 줄바꿈.
  // 부모가 폭을 정해 줄 때(Expanded/Flexible 안)만 줄임표가 된다
  final bool oneLine;
  final VoidCallback onTap;
  final TextStyle? style;
  // true면 눌림 높이를 최소 48로 키운다(글자는 세로 가운데). 단독으로 놓인 링크(위 막대·바로가기)용.
  // 문장 속 링크는 높이가 커지면 줄 간격이 깨지니 false(기본)로 둔다.
  final bool tall;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      link: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: tall ? AppSizes.tapMin : 0),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSizes.space1),
            // Align은 글자 크기에 딱 맞게(heightFactor 1) 두고, 최소 높이(tall)는 위 ConstrainedBox가 정한다.
            // 최소 높이가 더 크면 Align이 그 안에서 글자를 세로 가운데에 놓는다.
            child: Align(
              alignment: Alignment.center,
              widthFactor: 1,
              heightFactor: 1,
              child: Text(
                label,
                maxLines: oneLine ? 1 : null,
                overflow: oneLine ? TextOverflow.ellipsis : null,
                style: const TextStyle(
                  fontSize: AppSizes.fsSmall2,
                  color: AppColors.accent,
                  decoration: TextDecoration.underline,
                  decorationColor: AppColors.accent,
                ).merge(style),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
