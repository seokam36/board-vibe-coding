import 'package:material_ui/material_ui.dart';

import '../posts/models.dart';
import '../theme/app_colors.dart';
import '../widgets/labeled_field.dart';
import '../widgets/notice.dart';
import '../widgets/select_field.dart';
import '../widgets/small_button_style.dart';

const _needBoth = '학교와 허가서 번호를 입력해 주세요';
const _schoolsFailedMessage = '학교 목록을 불러오지 못했습니다';
const permitNumberMaxLength = 50;

// 대기 신청 줄 아래로 펼쳐지는 승인 양식: 학교 드롭다운 + 허가서 번호 + [승인하기] [취소].
// 학교 목록은 부모(화면)가 한 번만 불러와 넘겨 준다. 확인 창·요청·양식 닫기는 부모가 맡는다.
class PermitApproveForm extends StatefulWidget {
  const PermitApproveForm({
    super.key,
    required this.enabled,
    required this.schools,
    required this.schoolsFailed,
    required this.detectedSchoolId,
    required this.error,
    required this.onRetrySchools,
    required this.onSubmit,
    required this.onCancel,
  });

  final bool enabled; // false면(처리 중) 버튼을 막는다
  final List<School>? schools; // null이면 아직 못 불러온 상태
  final bool schoolsFailed;
  final int? detectedSchoolId; // 기계가 찾은 학교: 목록에 있으면 미리 고른다
  final String? error; // 처리 실패 안내 (양식 안에 보여 준다)
  final VoidCallback onRetrySchools;
  final void Function(int schoolId, String permitNumber) onSubmit; // 번호는 앞뒤 공백을 뺀 값
  final VoidCallback onCancel;

  @override
  State<PermitApproveForm> createState() => _PermitApproveFormState();
}

class _PermitApproveFormState extends State<PermitApproveForm> {
  final _number = TextEditingController();
  int? _schoolId; // null = 아직 안 골랐음
  bool _touched = false; // 사람이 직접 골랐으면 미리 선택으로 덮어쓰지 않는다
  String? _message; // 입력이 비었을 때 안내

  @override
  void initState() {
    super.initState();
    _preselect();
  }

  @override
  void didUpdateWidget(PermitApproveForm old) {
    super.didUpdateWidget(old);
    _preselect(); // 학교 목록이 나중에 도착해도 미리 선택한다
  }

  @override
  void dispose() {
    _number.dispose();
    super.dispose();
  }

  void _preselect() {
    final detected = widget.detectedSchoolId;
    final schools = widget.schools;
    if (_touched || _schoolId != null || detected == null || schools == null) return;
    if (schools.any((s) => s.id == detected)) _schoolId = detected;
  }

  void _submit() {
    final schoolId = _schoolId;
    final number = _number.text.trim();
    if (schoolId == null || number.isEmpty) {
      setState(() => _message = _needBoth); // 요청은 보내지 않는다
      return;
    }
    setState(() => _message = null);
    widget.onSubmit(schoolId, number);
  }

  @override
  Widget build(BuildContext context) {
    final schools = widget.schools;
    final error = widget.error;
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
          if (schools != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space3),
              child: SelectField(
                fieldKey: const ValueKey('permit-approve-school'),
                label: '학교',
                width: double.infinity,
                value: _schoolId?.toString() ?? '',
                options: [('', '학교를 고르세요'), for (final s in schools) (s.id.toString(), s.name)],
                onChanged: (v) => setState(() {
                  _schoolId = int.tryParse(v);
                  _touched = true;
                  _message = null;
                }),
              ),
            )
          else if (widget.schoolsFailed)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  const Text(_schoolsFailedMessage),
                  OutlinedButton(style: smallButtonStyle, onPressed: widget.onRetrySchools, child: const Text('다시 시도')),
                ],
              ),
            )
          else
            const Padding(
              padding: EdgeInsets.only(bottom: AppSizes.space3),
              child: Text('학교 목록을 불러오는 중…', style: TextStyle(color: AppColors.muted)),
            ),
          LabeledField(
            fieldKey: const ValueKey('permit-approve-number'),
            label: '허가서 번호',
            controller: _number,
            hint: '예: 2026-0001',
            maxLength: permitNumberMaxLength,
          ),
          // 서버가 글자·숫자만 남기고 대문자로 바꾸지만 '제'·'호' 같은 접두·접미어는 지우지 않는다
          const Padding(
            padding: EdgeInsets.only(bottom: AppSizes.space3),
            child: Text(
              "허가서의 '표준입학허가서 번호' 중 영문·숫자 부분만 입력해 주세요 ('제', '호'는 빼고)",
              style: TextStyle(color: AppColors.muted, fontSize: AppSizes.fsSmall),
            ),
          ),
          if (_message != null) Notice(kind: NoticeKind.error, message: _message),
          if (error != null) Notice(kind: NoticeKind.error, message: error),
          Wrap(
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            children: [
              OutlinedButton(
                style: smallButtonStyle,
                // 학교 목록이 없으면 학교를 고를 수 없으니 막는다
                onPressed: widget.enabled && schools != null ? _submit : null,
                child: const Text('승인하기'),
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
