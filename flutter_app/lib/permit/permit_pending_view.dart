import 'package:material_ui/material_ui.dart';

import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/section_parts.dart';
import 'permit_apply_form.dart';
import 'permit_status.dart';

// "심사 대기 중" 화면: 신청한 때 + 철회 실패 안내 + [신청 철회].
// 철회 요청(확인 창·DELETE)은 부모 섹션이 맡고, 이 위젯은 그리기만 한다
class PermitPendingView extends StatelessWidget {
  const PermitPendingView({
    super.key,
    required this.status,
    required this.removeError,
    required this.removing,
    required this.onWithdraw,
  });

  final PermitStatus status;
  final ErrorInfo? removeError; // 철회 실패 이유
  final bool removing; // 철회 요청 중이면 버튼을 잠근다
  final VoidCallback onWithdraw;

  @override
  Widget build(BuildContext context) {
    final date = status.requestedAt == null
        ? ''
        : formatDate(status.requestedAt!);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('심사 대기 중'),
        if (date.isNotEmpty)
          FieldHint('신청한 때: $date')
        else
          const SizedBox(height: AppSizes.space3),
        ...permitRemoveNotice(removeError),
        OutlinedButton(
          key: const ValueKey('permit-withdraw-button'),
          onPressed: removing ? null : onWithdraw,
          child: const Text('신청 철회'),
        ),
      ],
    );
  }
}
