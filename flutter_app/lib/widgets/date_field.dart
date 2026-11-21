import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import '../visa/dday.dart';
import 'activate_on_key.dart';

// 날짜 칸 하나 (React의 <input type="date" min max>). 비자 후기 폼(접수일·결과일)과 비자 일정(만료일)이 같이 쓴다.
// 직접 타이핑하지 않는 읽기 전용 칸이고, 누르면 달력이 열려 고른 날이 "yyyy-MM-dd"로 칸에 들어간다.
// 고를 수 있는 범위(firstDay~lastDay)와 "오늘" 표시(today)는 부르는 쪽이 서울 기준 글자로 넘긴다.
class DateField extends StatelessWidget {
  const DateField({
    super.key,
    required this.fieldKey,
    required this.label,
    required this.controller,
    required this.firstDay,
    required this.lastDay,
    required this.today,
  });

  final Key fieldKey; // 입력 칸(TextField)에 붙는 키 (테스트·화면 구분용)
  final String label;
  final TextEditingController controller;
  final String firstDay; // 고를 수 있는 첫 날 "yyyy-MM-dd"
  // 마지막 날·오늘은 "칸을 누르는 순간" 계산한다(글자를 돌려주는 함수). 화면을 열어 둔 채 자정을 넘겨도 어제로 남지 않게.
  final String Function() lastDay; // 고를 수 있는 마지막 날 "yyyy-MM-dd"
  final String Function() today; // 서울 오늘 "yyyy-MM-dd" (달력의 "오늘" 표시와 빈 칸일 때 처음 보여 줄 날)

  // 달력은 로컬 자정 DateTime을 쓰므로 연·월·일만 옮겨 담는다
  static DateTime _local(String day) {
    final d = parseYmd(day)!;
    return DateTime(d.year, d.month, d.day);
  }

  Future<void> _pick(BuildContext context) async {
    final first = _local(firstDay);
    final last = _local(lastDay()); // 누르는 순간의 값
    final todayLocal = _local(today());
    // 처음 보여 줄 날: 이미 값이 있으면 그 날, 없으면 오늘. 범위 밖이면 범위 안으로 당긴다
    final existing = parseYmd(controller.text);
    var initial = existing == null ? todayLocal : DateTime(existing.year, existing.month, existing.day);
    if (initial.isAfter(last)) initial = last;
    if (initial.isBefore(first)) initial = first;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: last,
      currentDate: todayLocal, // "오늘" 표시는 기기 시간대가 아니라 서울 오늘 기준
      initialEntryMode: DatePickerEntryMode.calendar, // 입력 모드 전환 버튼이 있어 직접 쓸 수도 있다
      helpText: label,
      cancelText: '취소',
      confirmText: '확인',
      fieldLabelText: label,
      fieldHintText: '2026. 12. 01.', // 직접 입력 모드 힌트: 한국어 로케일이 실제로 받는 모양(끝 점까지)
    );
    if (picked != null) controller.text = formatYmd(picked); // 고른 날을 칸에 그대로 적는다
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
            // Tab으로 포커스한 뒤 Enter·Space로도 달력이 열린다 (키보드 사용자용)
            child: ActivateOnKey(
              onActivate: () => _pick(context),
              child: TextField(
                key: fieldKey,
                controller: controller,
                readOnly: true,
                showCursor: false,
                enableInteractiveSelection: false,
                onTap: () => _pick(context),
                decoration: const InputDecoration(
                  hintText: '날짜를 선택하세요',
                  hintStyle: TextStyle(color: AppColors.muted),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
