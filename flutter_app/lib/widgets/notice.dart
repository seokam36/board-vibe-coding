import 'package:flutter/semantics.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

enum NoticeKind { error, info, success }

// 화면 안내 문구 한 곳 (React의 Notice). error는 바로 읽어 주는 경고(alert),
// info·success는 정중하게 알리는 상태(status)로 스크린리더에 알린다.
// 글자만이면 message, 링크처럼 위젯이 섞이면 child를 쓴다.
class Notice extends StatelessWidget {
  const Notice({super.key, required this.kind, this.message, this.child})
    : assert(message != null || child != null, 'message나 child 중 하나는 있어야 한다');

  final NoticeKind kind;
  final String? message;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    // 종류별 (배경, 글자) 색
    final (bg, fg) = switch (kind) {
      NoticeKind.error => (AppColors.dangerSoft, AppColors.danger),
      NoticeKind.success => (AppColors.celadonSoft, AppColors.celadonInk),
      NoticeKind.info => (AppColors.infoSoft, AppColors.ink),
    };
    final isError = kind == NoticeKind.error;
    return Semantics(
      container: true,
      // 화면에 나타나면 스크린리더가 읽어 준다. 역할 alert(바로 읽음)·status(정중히 알림)는 각각 그 자체로
      // 라이브 영역이라 liveRegion을 같이 쓰면 Flutter가 에러를 낸다(둘은 함께 못 쓴다).
      role: isError ? SemanticsRole.alert : SemanticsRole.status,
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(bottom: AppSizes.space3),
        padding: const EdgeInsets.symmetric(
          horizontal: AppSizes.space3,
          vertical: AppSizes.space2,
        ),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
        ),
        child: DefaultTextStyle.merge(
          style: TextStyle(fontSize: AppSizes.fsSmall2, height: 1.5, color: fg),
          child: child ?? Text(message!),
        ),
      ),
    );
  }
}
