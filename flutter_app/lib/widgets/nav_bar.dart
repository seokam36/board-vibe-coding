import 'dart:math' as math;

import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../auth/auth_state.dart';
import '../site_name.dart';
import '../theme/app_colors.dart';
import 'app_link.dart';
import 'notice.dart';

// 로그아웃 실패 때 보여 주는 문구
const logoutFailedMessage = '로그아웃하지 못했습니다. 다시 시도해 주세요';

// 모든 화면 맨 위 한 줄 (React의 NavBar): 왼쪽 홈 링크, 오른쪽 로그인 상태에 따른 메뉴.
// 쪽지 개수(안 읽은 수)는 이번 단계에 없다: 숫자 없이 "쪽지"만 보이고 unread API도 부르지 않는다.
class NavBar extends StatefulWidget {
  const NavBar({super.key});

  @override
  State<NavBar> createState() => _NavBarState();
}

class _NavBarState extends State<NavBar> {
  String? _error; // 로그아웃 실패 안내

  Future<void> _logout() async {
    final auth = context.read<AuthState>();
    setState(() => _error = null); // 다시 누르면 이전 안내는 지운다
    try {
      // 성공하면 로그인 상태가 비워지고, 보호 화면이면 문지기가 /login 으로 보낸다
      await auth.logout();
    } catch (_) {
      // 서버 쿠키가 아직 살아 있으니 로그인 상태는 그대로 두고, 반응이 없어 보이지 않게 문구만 띄운다
      if (mounted) setState(() => _error = logoutFailedMessage);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final school = user?.verifiedSchool;

    // 메뉴 항목들 (React와 같은 순서·조건). 비자 후기·중고·나눔은 로그인과 무관하게 누구나 본다
    final menu = <Widget>[
      AppLink(label: '비자 후기', onTap: () => context.go('/visa-reviews')),
      AppLink(label: '중고·나눔', onTap: () => context.go('/market')),
      if (user != null) ...[
        Text('${user.nickname} 님', style: const TextStyle(fontSize: AppSizes.fsSmall2)),
        // 학교 인증을 마친 회원 표시. 학교 이름은 마우스를 올리거나 스크린리더로 알 수 있다
        if (school != null) _VerifiedBadge(schoolName: school.name),
        // 비자 일정은 학교 인증 회원에게만 보인다
        if (school != null) AppLink(label: '비자 일정', onTap: () => context.go('/visa-plan')),
        AppLink(label: '쪽지', onTap: () => context.go('/messages')),
        AppLink(label: '내 정보', onTap: () => context.go('/me')),
        // 관리자에게만 보이는 메뉴
        if (user.isAdmin) AppLink(label: '신고 관리', onTap: () => context.go('/admin/reports')),
        OutlinedButton(
          onPressed: _logout,
          style: OutlinedButton.styleFrom(
            minimumSize: const Size(0, 36),
            padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
          ),
          child: const Text('로그아웃'),
        ),
      ] else ...[
        AppLink(label: '로그인', onTap: () => context.go('/login')),
        AppLink(label: '회원가입', onTap: () => context.go('/signup')),
      ],
    ];

    return Container(
      decoration: const BoxDecoration(
        color: AppColors.paper,
        border: Border(bottom: BorderSide(color: AppColors.line)),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          // 상단바 안쪽 여백: 본문(최대 720 가운데, 좌우 16)의 글자 시작선과 맞춘다
          final pad = math.max(
            AppSizes.space4,
            (constraints.maxWidth - AppSizes.pageMaxWidth) / 2 + AppSizes.space4,
          );
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: EdgeInsets.symmetric(horizontal: pad, vertical: AppSizes.space3),
                // Wrap: 좁은 화면(모바일)에서 메뉴가 넘치지 않고 줄바꿈된다
                child: Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSizes.space4,
                  runSpacing: AppSizes.space2,
                  children: [
                    InkWell(
                      onTap: () => context.go('/'),
                      child: const Text(
                        siteName,
                        style: TextStyle(
                          color: AppColors.celadon,
                          fontWeight: FontWeight.w700,
                          fontSize: AppSizes.fsRow,
                        ),
                      ),
                    ),
                    Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: AppSizes.space4,
                      runSpacing: AppSizes.space2,
                      children: menu,
                    ),
                  ],
                ),
              ),
              // 로그아웃 실패 안내는 메뉴 줄 밖(아래)에 둔다
              if (_error != null)
                Padding(
                  padding: EdgeInsets.fromLTRB(pad, 0, pad, AppSizes.space3),
                  child: Notice(kind: NoticeKind.error, message: _error),
                ),
            ],
          );
        },
      ),
    );
  }
}

// "인증" 뱃지. 화면엔 "인증"만, 툴팁·스크린리더엔 학교 이름 (React의 title + sr-only)
class _VerifiedBadge extends StatelessWidget {
  const _VerifiedBadge({required this.schoolName});

  final String schoolName;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: schoolName,
      child: Semantics(
        label: '인증 ($schoolName 인증 회원)',
        excludeSemantics: true, // 안쪽 "인증" 글자는 위 라벨로 대신 읽는다
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 6),
          decoration: BoxDecoration(
            color: AppColors.celadonSoft,
            borderRadius: BorderRadius.circular(4),
          ),
          child: const Text(
            '인증',
            style: TextStyle(
              fontSize: AppSizes.fsSmall,
              color: AppColors.celadonInk,
              height: 1.6,
            ),
          ),
        ),
      ),
    );
  }
}
