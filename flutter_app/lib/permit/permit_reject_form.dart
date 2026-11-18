import 'package:material_ui/material_ui.dart';

import 'permit_reject_reasons.dart';
import '../theme/app_colors.dart';
import '../widgets/notice.dart';
import '../widgets/select_field.dart';
import '../widgets/small_button_style.dart';

const _chooseReason = '반려 사유를 골라 주세요';

// 관리자가 고를 수 있는 반려 사유: 코드 목록(EXPIRED 제외)은 공용 파일에서 가져오고,
// 문구는 회원에게 보이는 것 그대로 쓴다 (permitRejectReasons를 재사용)
final _adminReasons = <(String, String)>[
  for (final code in adminRejectReasonCodes) (code, permitRejectReasons[code]!),
];

// 대기 신청 줄 아래로 펼쳐지는 반려 양식: 사유 드롭다운 + [반려하기] [취소].
// onSubmit은 사유 코드를 받아 처리(확인 창·요청)한다. 양식을 닫을지는 부모(줄)가 정한다
// (실패하면 양식을 그대로 남겨 다시 시도할 수 있게 한다)
class PermitRejectForm extends StatefulWidget {
  const PermitRejectForm({
    super.key,
    required this.enabled,
    required this.error,
    required this.onSubmit,
    required this.onCancel,
  });

  final bool enabled; // false면(처리 중) 버튼을 막는다
  final String? error; // 처리 실패 안내 (양식 안에 보여 준다)
  final void Function(String reason) onSubmit; // 승인과 같은 void: 요청은 부모가 맡는다
  final VoidCallback onCancel;

  @override
  State<PermitRejectForm> createState() => _PermitRejectFormState();
}

class _PermitRejectFormState extends State<PermitRejectForm> {
  String _reason = ''; // 빈 문자열 = 아직 안 골랐음
  String? _message; // 사유를 안 골랐을 때 안내

  void _submit() {
    if (_reason.isEmpty) {
      setState(() => _message = _chooseReason); // 요청은 보내지 않는다
      return;
    }
    setState(() => _message = null);
    widget.onSubmit(_reason);
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: AppSizes.space3),
      padding: const EdgeInsets.all(AppSizes.space3),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space2),
            child: SelectField(
              fieldKey: const ValueKey('permit-reject-reason'),
              label: '반려 사유',
              width: double.infinity,
              value: _reason,
              options: [('', '반려 사유를 고르세요'), ..._adminReasons],
              onChanged: (v) => setState(() {
                _reason = v;
                _message = null;
              }),
            ),
          ),
          if (_message != null) Notice(kind: NoticeKind.error, message: _message),
          if (widget.error != null) Notice(kind: NoticeKind.error, message: widget.error),
          Wrap(
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            children: [
              OutlinedButton(
                style: dangerSmallButtonStyle,
                onPressed: widget.enabled ? _submit : null,
                child: const Text('반려하기'),
              ),
              OutlinedButton(
                style: smallButtonStyle,
                onPressed: widget.enabled ? widget.onCancel : null,
                child: const Text('취소'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
