import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// "불러오는 중..." 문구 한 곳 (React의 loadingText.ts)
const loadingText = '불러오는 중...';

// 불러오는 중 표시. prefix를 주면 "댓글 불러오는 중..."처럼 앞에 붙는다
class Loading extends StatelessWidget {
  const Loading({super.key, this.prefix});

  final String? prefix;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(AppSizes.space4),
      child: Text(
        prefix == null ? loadingText : '$prefix $loadingText',
        style: const TextStyle(color: AppColors.muted),
      ),
    );
  }
}
