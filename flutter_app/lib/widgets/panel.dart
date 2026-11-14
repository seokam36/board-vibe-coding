import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 판 (React의 .panel): paper 바탕 + line 테두리 + 둥근 모서리, 안쪽 16, 아래 바깥 16.
class Panel extends StatelessWidget {
  const Panel({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: AppSizes.space4),
      padding: const EdgeInsets.all(AppSizes.space4),
      decoration: BoxDecoration(
        color: AppColors.paper,
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
      ),
      child: child,
    );
  }
}

// 안쪽 여백 없는 판 (React의 .panel.panel-flush): 행·표가 판 테두리까지 닿는다. 넘치는 내용은 모서리에서 잘린다.
class FlushPanel extends StatelessWidget {
  const FlushPanel({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: AppSizes.space4),
      clipBehavior: Clip.hardEdge,
      decoration: BoxDecoration(
        color: AppColors.paper,
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
    );
  }
}

// 판 안의 상태 문구(빈 목록·에러 등) 자리 (React의 .panel-state): 안쪽 여백 16
class PanelState extends StatelessWidget {
  const PanelState({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.all(AppSizes.space4), child: child);
}

// 화면 제목 (React의 h1): 22px 굵게, 아래 여백 16. 스크린리더는 가장 큰 제목으로 읽는다.
class PageTitle extends StatelessWidget {
  const PageTitle(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSizes.space4),
      child: Semantics(
        header: true,
        headingLevel: 1,
        child: Text(
          text,
          style: const TextStyle(
            fontSize: AppSizes.fsTitle,
            fontWeight: FontWeight.w700,
            color: AppColors.ink,
            height: 1.35,
          ),
        ),
      ),
    );
  }
}
