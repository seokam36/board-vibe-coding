import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 목록 행의 제목 링크 (React의 .row-link): 평소엔 밑줄 없이 ink 색, 마우스를 올리면 밑줄.
// 글 목록과 비자 후기 목록이 같이 쓴다.
class RowLink extends StatefulWidget {
  const RowLink({super.key, required this.title, required this.onTap});

  final String title;
  final VoidCallback onTap;

  @override
  State<RowLink> createState() => _RowLinkState();
}

class _RowLinkState extends State<RowLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      link: true,
      child: InkWell(
        onTap: widget.onTap,
        onHover: (h) => setState(() => _hover = h),
        child: Text(
          widget.title,
          style: TextStyle(
            fontSize: AppSizes.fsRow,
            fontWeight: FontWeight.w500,
            color: AppColors.ink,
            height: 1.65,
            decoration: _hover ? TextDecoration.underline : TextDecoration.none,
            decorationColor: AppColors.ink,
          ),
        ),
      ),
    );
  }
}
