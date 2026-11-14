import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../common/format_date.dart';
import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/notice.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/section_parts.dart';
import '../widgets/select_field.dart';
import 'models.dart';

// 이 폼만 쓰는 문구 (React VisaReviewForm의 글자 그대로)
const _optionsFailedSaveMessage = '선택지를 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요';
const _fillAllMessage = '비자 종류·신청 종류·결과·날짜를 모두 입력해 주세요';
const _optionsFailedWithCurrentMessage = '선택지 목록을 불러오지 못했습니다. 지금 값으로만 저장할 수 있습니다';

// 날짜 칸이 고를 수 있는 가장 이른 날. React는 max만 두었지만 달력에는 시작이 필요해서 넉넉히 잡는다
final _firstDate = DateTime(2000, 1, 1);

// "yyyy-MM-dd" 글자 → 그 날의 DateTime. 모양이 다르면 null. toIso8601String·시간대를 거치지 않고 글자를 직접 쪼갠다
DateTime? _parseDay(String day) {
  final m = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(day);
  if (m == null) return null;
  return DateTime(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
}

// 달력에서 고른 날 → "yyyy-MM-dd". 피커가 돌려준 DateTime의 연·월·일 칸을 그대로 이어 붙인다 (toIso8601String 금지: 시간대로 하루가 밀릴 수 있다)
String _formatDay(DateTime d) {
  final mm = d.month.toString().padLeft(2, '0');
  final dd = d.day.toString().padLeft(2, '0');
  return '${d.year.toString().padLeft(4, '0')}-$mm-$dd';
}

// 받은 선택지 + (목록에 없거나 못 받았으면) 후기가 원래 가진 값 (React의 withCurrent, 글 수정의 말머리와 같은 패턴)
List<VisaOption> _withCurrent(List<VisaOption>? list, VisaOption? current) {
  final out = <VisaOption>[...?list];
  if (current != null && !out.any((o) => o.code == current.code)) out.insert(0, current);
  return out;
}

// 비자 후기 작성·수정 공용 폼 (React의 VisaReviewForm). 요청 본문이 하나라 폼도 하나다.
// review가 있으면 수정: 그 값으로 채우고, 선택지를 못 받아도 쓸 수 있게 현재 값을 목록에 끼워 넣는다.
class VisaReviewForm extends StatefulWidget {
  const VisaReviewForm({
    super.key,
    required this.schoolName,
    required this.options,
    required this.optionsFailed,
    this.review,
    required this.onSave,
    required this.errorTable,
    required this.cancelTo,
    required this.cancelLabel,
  });

  final String schoolName; // 고정 뱃지에 보일 학교 (바꿀 수 없다)
  final VisaReviewOptions? options; // 못 받았으면 null
  final bool optionsFailed;
  final VisaReview? review; // 수정 화면에서 채울 원래 후기
  // 저장 요청을 보내는 함수 (성공하면 이 함수가 화면을 이동시킨다). 실패하면 throw
  final Future<void> Function(Map<String, dynamic> body) onSave;
  final Map<int, String> errorTable; // 저장 실패 상태코드별 문구
  final String cancelTo;
  final String cancelLabel;

  @override
  State<VisaReviewForm> createState() => _VisaReviewFormState();
}

class _VisaReviewFormState extends State<VisaReviewForm> {
  // 드롭다운 값은 State에 들고, 날짜·본문은 컨트롤러에 둔다. 처음엔 빈 값('')
  late String _visaType = widget.review?.visaType.code ?? '';
  late String _applicationType = widget.review?.applicationType.code ?? '';
  late String _result = widget.review?.result.code ?? '';
  // 날짜 칸의 글자가 곧 값("yyyy-MM-dd")이다
  late final _receivedDate = TextEditingController(text: widget.review?.receivedDate ?? '');
  late final _resultDate = TextEditingController(text: widget.review?.resultDate ?? '');
  late final _content = TextEditingController(text: widget.review?.content ?? '');
  bool _saving = false;
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  bool _needLogin = false;
  String? _error;

  @override
  void dispose() {
    _receivedDate.dispose();
    _resultDate.dispose();
    _content.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return; // 저장 중 또 눌러도 한 번만 보낸다
    setState(() {
      _error = null;
      _needLogin = false;
    });
    final received = _receivedDate.text;
    final resultDay = _resultDate.text;
    // 드롭다운 셋·날짜 둘 중 빈 게 있으면 서버에 보내지 않고 화면에 머문다 (입력값은 그대로)
    if (_visaType.isEmpty || _applicationType.isEmpty || _result.isEmpty || received.isEmpty || resultDay.isEmpty) {
      // 선택지를 못 받아 드롭다운이 빈 경우는 새로고침을 안내한다
      final selectEmpty = _visaType.isEmpty || _applicationType.isEmpty || _result.isEmpty;
      setState(() => _error = widget.optionsFailed && selectEmpty ? _optionsFailedSaveMessage : _fillAllMessage);
      return;
    }
    setState(() => _saving = true);
    try {
      // 미래 날짜는 달력(lastDate)이 막고, 접수일≤결과일·5년·365일은 서버의 400에 맡긴다. schoolId는 보내지 않는다
      await widget.onSave({
        'visaType': _visaType,
        'applicationType': _applicationType,
        'receivedDate': received,
        'resultDate': resultDay,
        'result': _result,
        'content': _content.text,
      });
    } catch (err) {
      final info = describeError(err, widget.errorTable, ErrorMessages.saveFailed);
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false; // 실패했을 때만 버튼을 되살린다 (성공하면 화면이 바뀐다)
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final opts = widget.options;
    final review = widget.review;
    List<(String, String)> items(String placeholder, List<VisaOption>? list, VisaOption? current) => [
          ('', placeholder),
          for (final o in _withCurrent(list, current)) (o.code, o.name),
        ];

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space4),
            child: Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                // 학교는 고정: 고르는 칸이 아니라 글자로만 보여 준다. 스크린리더에는 "학교: 이름" (React의 sr-only 접두어)
                Semantics(
                  label: '학교: ${widget.schoolName}',
                  child: ExcludeSemantics(child: AppBadge(text: widget.schoolName, school: true)),
                ),
                SelectField(
                  fieldKey: const ValueKey('visa-type-select'),
                  label: '비자 종류',
                  width: 170,
                  value: _visaType,
                  options: items('비자 종류 선택', opts?.visaTypes, review?.visaType),
                  onChanged: (v) => setState(() => _visaType = v),
                ),
                SelectField(
                  fieldKey: const ValueKey('application-type-select'),
                  label: '신청 종류',
                  width: 170,
                  value: _applicationType,
                  options: items('신청 종류 선택', opts?.applicationTypes, review?.applicationType),
                  onChanged: (v) => setState(() => _applicationType = v),
                ),
                SelectField(
                  fieldKey: const ValueKey('result-select'),
                  label: '결과',
                  width: 170,
                  value: _result,
                  options: items('결과 선택', opts?.results, review?.result),
                  onChanged: (v) => setState(() => _result = v),
                ),
              ],
            ),
          ),
          if (widget.optionsFailed)
            Notice(
              kind: NoticeKind.error,
              message: review != null ? _optionsFailedWithCurrentMessage : ErrorMessages.reviewOptionsLoadFailed,
            ),
          // 접수일·결과일: 나란히(좁으면 세로). 둘 다 오늘(서울)까지만 고를 수 있다
          LayoutBuilder(
            builder: (context, constraints) {
              final received = _DateField(
                fieldKey: const ValueKey('received-date-field'),
                label: '접수일',
                controller: _receivedDate,
              );
              final resultDay = _DateField(
                fieldKey: const ValueKey('result-date-field'),
                label: '결과일',
                controller: _resultDate,
              );
              // 한 칸 최소 160 + 사이 12가 두 칸 들어갈 만큼 넓으면 가로, 아니면 세로 (React의 flex-wrap)
              final wide = constraints.maxWidth >= 160 * 2 + AppSizes.space3;
              return wide
                  ? Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(child: received),
                        const SizedBox(width: AppSizes.space3),
                        Expanded(child: resultDay),
                      ],
                    )
                  : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [received, resultDay]);
            },
          ),
          // 본문: 선택 입력, 최대 2000자(UTF-16 길이), 8줄. 아래에 "n / 2000" 글자 수
          ExcludeSemantics(
            child: const Text(
              '본문 (선택)',
              style: TextStyle(fontSize: AppSizes.fsSmall2, fontWeight: FontWeight.w500, color: AppColors.ink),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            label: '본문 (선택)',
            child: TextField(
              key: const ValueKey('content-field'),
              controller: _content,
              decoration: const InputDecoration(
                hintText: '준비한 서류, 팁 등을 남겨 주세요',
                hintStyle: TextStyle(color: AppColors.muted),
              ),
              inputFormatters: const [Utf16LengthFormatter(2000)],
              minLines: 8,
              maxLines: 8,
              keyboardType: TextInputType.multiline,
              textInputAction: TextInputAction.newline,
            ),
          ),
          // 글자 수는 입력이 바뀔 때마다 새로 그린다 (React의 content.length)
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: _content,
            builder: (context, value, _) => FieldHint('${value.text.length} / 2000'),
          ),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error),
          if (_needLogin) const ReloginNotice(),
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            children: [
              FilledButton(
                onPressed: _saving ? null : _submit,
                child: Text(_saving ? '저장 중...' : '저장'),
              ),
              OutlinedButton(
                onPressed: () => context.go(widget.cancelTo),
                child: Text(widget.cancelLabel),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

// 날짜 칸 하나 (React의 <input type="date" max={today}>). 직접 타이핑하지 않는 읽기 전용 칸이고,
// 누르면 달력이 열려 고른 날이 "yyyy-MM-dd"로 칸에 들어간다. 서울 오늘 이후는 고를 수 없다.
class _DateField extends StatelessWidget {
  const _DateField({required this.fieldKey, required this.label, required this.controller});

  final Key fieldKey;
  final String label;
  final TextEditingController controller;

  Future<void> _pick(BuildContext context) async {
    // 고를 수 있는 마지막 날 = 서울 기준 오늘 (UTC+9 고정 계산)
    final lastDate = _parseDay(seoulToday())!;
    // 처음 보여 줄 날: 이미 값이 있으면 그 날, 없으면 오늘. 범위 밖이면 범위 안으로 당긴다
    var initial = _parseDay(controller.text) ?? lastDate;
    if (initial.isAfter(lastDate)) initial = lastDate;
    if (initial.isBefore(_firstDate)) initial = _firstDate;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: _firstDate,
      lastDate: lastDate,
      initialEntryMode: DatePickerEntryMode.calendar, // 입력 모드 전환 버튼이 있어 직접 쓸 수도 있다
      helpText: label,
      cancelText: '취소',
      confirmText: '확인',
      fieldLabelText: label,
      fieldHintText: 'yyyy-MM-dd',
    );
    if (picked != null) controller.text = _formatDay(picked); // 고른 날을 칸에 그대로 적는다
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSizes.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ExcludeSemantics(
            child: Text(
              label,
              style: const TextStyle(fontSize: AppSizes.fsSmall2, fontWeight: FontWeight.w500, color: AppColors.ink),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            label: label,
            child: TextField(
              key: fieldKey,
              controller: controller,
              readOnly: true,
              showCursor: false,
              enableInteractiveSelection: false,
              onTap: () => _pick(context),
              decoration: const InputDecoration(
                hintText: 'yyyy-MM-dd',
                hintStyle: TextStyle(color: AppColors.muted),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
