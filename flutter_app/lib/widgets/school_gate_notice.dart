import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../auth/auth_user.dart';
import '../theme/app_colors.dart';
import 'app_link.dart';
import 'notice.dart';

// 로그인했지만 댓글·공감·신고를 할 수 없을 때(미인증, 학교 없는 옛 글, 다른 학교 글) 보여 주는 짧은 안내
// (React의 SchoolGateNotice). 문구를 고르는 순서도 React와 같다: 미인증 → 학교 없는 글 → 다른 학교.
class SchoolGateNotice extends StatelessWidget {
  const SchoolGateNotice({super.key, required this.user, required this.noSchool});

  final AuthUser user;
  final bool noSchool; // 글에 학교가 정해지지 않았는지

  @override
  Widget build(BuildContext context) {
    final school = user.verifiedSchool;
    if (school == null) {
      // 인증 안 한 사람에게는 내 정보 화면으로 가는 링크를 같이 보여 준다
      return Notice(
        kind: NoticeKind.info,
        child: Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: AppSizes.space1,
          children: [
            const Text('학교 인증 후 같은 학교 글에만 댓글·공감·신고할 수 있어요'),
            AppLink(
              label: '학교 인증하기',
              onTap: () => context.go('/me'),
              style: const TextStyle(color: AppColors.celadon),
            ),
          ],
        ),
      );
    }
    return Notice(
      kind: NoticeKind.info,
      message: noSchool
          ? '학교가 정해지지 않은 옛 글은 읽기만 할 수 있어요'
          : '인증한 학교(${school.name}) 글에만 댓글·공감·신고할 수 있어요',
    );
  }
}
