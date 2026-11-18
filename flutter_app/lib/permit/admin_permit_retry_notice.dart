import 'package:material_ui/material_ui.dart';

import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/notice.dart';
import '../widgets/small_button_style.dart';

// 오류 안내 + 다시 하기 버튼 한 줄 (인증 심사 화면의 목록 실패·재조회 실패 안내가 같이 쓴다).
// 권한 없음(403) 안내는 다시 해도 같으니 버튼을 숨긴다. onPressed가 null이면 버튼이 눌리지 않는다
class AdminPermitRetryNotice extends StatelessWidget {
  const AdminPermitRetryNotice({super.key, required this.message, required this.buttonLabel, required this.onPressed});

  final String message;
  final String buttonLabel;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.error,
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: AppSizes.space2,
        children: [
          Text(message),
          if (message != ErrorMessages.adminOnly)
            OutlinedButton(style: smallButtonStyle, onPressed: onPressed, child: Text(buttonLabel)),
        ],
      ),
    );
  }
}
