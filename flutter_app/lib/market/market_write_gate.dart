import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../auth/auth_user.dart';
import '../theme/app_colors.dart';
import '../widgets/app_link.dart';
import '../widgets/notice.dart';

// 중고·나눔 글을 올릴 수 없는 사람(비로그인, 학교 미인증)에게 보여 주는 안내 (React의 MarketWriteGate).
// 비로그인이면 로그인 링크, 로그인했지만 미인증이면 내 정보(학교 인증) 링크를 같이 보여 준다.
class MarketWriteGate extends StatelessWidget {
  const MarketWriteGate({super.key, required this.user});

  final AuthUser? user;

  @override
  Widget build(BuildContext context) {
    final loggedIn = user != null;
    return Notice(
      kind: NoticeKind.info,
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: AppSizes.space1,
        children: [
          Text(loggedIn ? '중고·나눔 글은 학교 인증 후에 올릴 수 있어요' : '글을 올리려면 로그인이 필요해요'),
          AppLink(
            label: loggedIn ? '내 정보에서 학교 인증하기' : '로그인',
            onTap: () => context.go(loggedIn ? '/me' : '/login'),
          ),
        ],
      ),
    );
  }
}
