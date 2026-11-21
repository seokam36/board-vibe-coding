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

// 모든 화면 맨 위 얇은 한 줄: 왼쪽 사이트 이름(홈 링크), 오른쪽 로그인 상태에 따른 메뉴(로그인·회원가입 / 챗봇·닉네임·인증·로그아웃).
class NavBar extends StatefulWidget {
  const NavBar({super.key});

  @override
  State<NavBar> createState() => _NavBarState();
}

class _NavBarState extends State<NavBar> {
  String? _error; // 로그아웃 실패 안내

  bool _loggingOut = false; // 로그아웃 진행 중이면 버튼을 막는다 (두 번 눌려 요청이 두 번 나가는 것 방지)

  Future<void> _logout() async {
    if (_loggingOut) return;
    final auth = context.read<AuthState>();
    setState(() {
      _error = null; // 다시 누르면 이전 안내는 지운다
      _loggingOut = true;
    });
    try {
      // 성공하면 로그인 상태가 비워지고, 보호 화면이면 문지기가 /login 으로 보낸다
      await auth.logout();
    } catch (_) {
      // 서버 쿠키가 아직 살아 있으니 로그인 상태는 그대로 두고, 반응이 없어 보이지 않게 문구만 띄운다
      if (mounted) setState(() => _error = logoutFailedMessage);
    } finally {
      // 성공이든 실패든 끝나면 원래대로 (실패 뒤 다시 시도할 수 있게)
      if (mounted) setState(() => _loggingOut = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final school = user?.verifiedSchool;

    // 얇은 위 막대(S26-c): 비자 후기·중고·나눔·쪽지·내 정보는 아래 탭(BottomTabs)으로, 비자 일정·관리자 메뉴는 내 정보 화면으로 옮겼다.
    // 여기엔 로그인 상태에 따른 것만 남는다.
    final menu = <Widget>[
      if (user != null) ...[
        // 중고 글 챗봇은 로그인한 회원 누구나 (학교 인증 불필요)
        AppLink(label: '챗봇', onTap: () => context.go('/chat')),
        // 닉네임은 메뉴 맨 끝, 로그아웃 버튼 바로 왼쪽 ("누가 로그인했나"와 "나가기"를 한 자리에)
        Text('${user.nickname} 님', style: const TextStyle(fontSize: AppSizes.fsSmall2)),
        // 학교 인증을 마친 회원 표시. 학교 이름은 마우스를 올리거나 스크린리더로 알 수 있다
        if (school != null) _VerifiedBadge(schoolName: school.name),
        OutlinedButton(
          onPressed: _loggingOut ? null : _logout,
          style: OutlinedButton.styleFrom(
            minimumSize: const Size(0, 36),
            padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
          ),
          child: Text(_loggingOut ? '로그아웃 중...' : '로그아웃'), // 진행 중에만 라벨이 바뀐다
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
                // Row: 이름은 왼쪽, 메뉴는 남은 자리의 오른쪽 끝. 375px에서도 한 줄에 들어오게 한다
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    // 사이트 이름은 홈 링크: 스크린리더가 "링크"로 읽게 한다
                    Semantics(
                      link: true,
                      child: InkWell(
                        onTap: () => context.go('/'),
                        child: const Text(
                          siteName,
                          style: TextStyle(
                            color: AppColors.accent,
                            fontWeight: FontWeight.w700,
                            fontSize: AppSizes.fsRow,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: AppSizes.space3),
                    Expanded(
                      // Wrap: 아주 좁거나 글자가 크면 넘치지 않고 줄바꿈된다
                      child: Wrap(
                        alignment: WrapAlignment.end,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        spacing: AppSizes.space3,
                        runSpacing: AppSizes.space2,
                        children: menu,
                      ),
                    ),
                  ],
                ),
              ),
              // 로그아웃 실패 안내는 메뉴 줄 밖(아래)에 둔다
              if (_error != null)
                Padding(
                  padding: EdgeInsets.fromLTRB(pad, 0, pad, AppSizes.space3),
                  child: Notice(kind: NoticeKind.error, message: _error, bottomMargin: 0),
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
            color: AppColors.accentSoft,
            borderRadius: BorderRadius.circular(4),
          ),
          child: const Text(
            '인증',
            style: TextStyle(
              fontSize: AppSizes.fsSmall,
              color: AppColors.accentInk,
              height: 1.6,
            ),
          ),
        ),
      ),
    );
  }
}
