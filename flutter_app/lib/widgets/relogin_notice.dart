import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import 'app_link.dart';
import 'notice.dart';

// 자동 재발급까지 실패해 로그인이 풀렸을 때 쓰기 화면이 보여 주는 안내 (React의 ReloginNotice).
// 화면을 갑자기 /login 으로 바꾸지 않고, 쓰던 내용은 그대로 둔 채 로그인 링크만 알려 준다
class ReloginNotice extends StatelessWidget {
  const ReloginNotice({super.key, this.shrinkWrap = false});

  final bool shrinkWrap; // 작업 줄 안에서는 글자 폭만큼만 (Notice.shrinkWrap)

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.error,
      shrinkWrap: shrinkWrap,
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: AppSizes.space1,
        children: [
          const Text(reloginMessage),
          // 안내 문구 색(danger)과 구분되게 링크는 청자색 그대로 둔다
          AppLink(label: '로그인', onTap: () => context.go('/login')),
        ],
      ),
    );
  }
}
