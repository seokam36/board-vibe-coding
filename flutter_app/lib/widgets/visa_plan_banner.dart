import 'dart:math' as math;

import 'package:flutter/semantics.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../auth/auth_state.dart';
import '../router.dart' show normalizePath;
import '../theme/app_colors.dart';
import '../visa/visa_banner.dart';
import '../visa/visa_banner_state.dart';
import '../visa/visa_plan_store.dart';
import 'app_link.dart';

// 상단바 바로 아래 비자 일정 띠 (React의 VisaPlanBanner). 단계(stage)가 있을 때만 뜨고,
// 불러오기 실패·미인증·비로그인이면 아무것도 안 그린다. 일정은 보관소(VisaPlanStore) 값만 읽고 따로 부르지 않는다.
// 역할 status: 에러 알림(alert)이 아니라 조용한 상태 안내 (Notice의 info와 같은 방식)
class VisaPlanBanner extends StatelessWidget {
  const VisaPlanBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final store = context.watch<VisaPlanStore>();
    final closed = context.watch<VisaBannerState>();
    final plan = store.plan;

    if (user == null || store.status != VisaPlanStatus.ready || plan == null) return const SizedBox.shrink();
    final stage = plan.stage;
    final expiryDate = plan.expiryDate;
    final daysLeft = plan.daysLeft;
    if (stage == null || expiryDate == null || daysLeft == null) return const SizedBox.shrink();
    // 일정 화면 안에서는 숨김. (GoRouterState.of는 화면이 막 닫히는 중에 무한 반복에 빠질 수 있어,
    // 로그인 상태 기록(AuthState)처럼 라우터가 확정한 현재 주소를 직접 읽는다)
    final path = GoRouter.of(context).routerDelegate.currentConfiguration.uri.path;
    if (normalizePath(path) == '/visa-plan') return const SizedBox.shrink();

    // 모르는 stage면 띠를 그리지 않는다 (예외로 전체 화면이 죽는 것 방지)
    final message = bannerMessage(stage, daysLeft);
    if (message == null) return const SizedBox.shrink();
    final value = dismissValue(stage, expiryDate); // 단계 + 만료일 해시
    if (closed.isClosedNow(user.id, value) || isDismissed(user.id, value)) return const SizedBox.shrink();

    return Semantics(
      container: true,
      role: SemanticsRole.status,
      child: Container(
        key: const ValueKey('visa-banner'),
        color: AppColors.infoSoft,
        child: LayoutBuilder(
          builder: (context, constraints) {
            // 안쪽 여백: 상단바·본문의 글자 시작선과 맞춘다 (NavBar와 같은 계산)
            final pad = math.max(AppSizes.space4, (constraints.maxWidth - AppSizes.pageMaxWidth) / 2 + AppSizes.space4);
            return Padding(
              padding: EdgeInsets.symmetric(horizontal: pad, vertical: AppSizes.space2),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  // 좁은 화면에서는 링크가 다음 줄로 내려간다
                  Expanded(
                    child: Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: AppSizes.space3,
                      runSpacing: AppSizes.space1,
                      children: [
                        Text(
                          message,
                          key: const ValueKey('visa-banner-text'),
                          style: const TextStyle(fontSize: AppSizes.fsSmall2, color: AppColors.ink, height: 1.5),
                        ),
                        AppLink(label: bannerLinkText, onTap: () => context.go('/visa-plan')),
                      ],
                    ),
                  ),
                  // 닫기: 글자는 ×, 스크린리더엔 '띠 닫기'. 터치하기 좋게 40px
                  Semantics(
                    container: true, // 띠의 문구와 라벨이 한데 섞이지 않게 따로 둔다
                    button: true,
                    label: bannerCloseLabel,
                    excludeSemantics: true,
                    child: InkWell(
                      key: const ValueKey('visa-banner-close'),
                      onTap: () => context.read<VisaBannerState>().close(user.id, value),
                      child: const SizedBox(
                        width: 40,
                        height: 40,
                        child: Center(
                          child: Text('×', style: TextStyle(fontSize: AppSizes.fsRow, color: AppColors.ink, height: 1)),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
