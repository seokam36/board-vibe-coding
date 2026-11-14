import 'package:flutter/scheduler.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../common/external_link.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../visa/dday.dart';
import '../visa/visa_banner_text.dart';
import '../visa/visa_disclaimer.dart' show hikoreaUrl;
import '../visa/visa_plan.dart';
import '../visa/visa_plan_store.dart';
import '../widgets/app_link.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/section_parts.dart';
import '../widgets/small_button_style.dart';

// 모든 상태에서 보이는 한 줄 안내 (개인별 판단은 하지 않는다는 선). 하이코리아 링크는 새 창으로 연다
class _PlanGuide extends StatelessWidget {
  const _PlanGuide();

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.info,
      // 글 사이에 링크를 끼워 넣으려고 Text.rich + WidgetSpan을 쓴다
      child: Text.rich(
        TextSpan(
          children: [
            const TextSpan(text: '일반 안내이며 개인 상황은 '),
            WidgetSpan(
              alignment: PlaceholderAlignment.baseline,
              baseline: TextBaseline.alphabetic,
              child: AppLink(label: '하이코리아', onTap: () => externalLinkOpener.open(hikoreaUrl)),
            ),
            const TextSpan(text: '나 관할 출입국·외국인청에서 확인하세요'),
          ],
        ),
      ),
    );
  }
}

// 내 비자 일정 "/visa-plan" (React의 VisaPlanPage). 학교 인증 회원만 쓸 수 있다 (로그인 여부는 라우터 문지기가 먼저 거른다).
class VisaPlanPage extends StatefulWidget {
  const VisaPlanPage({super.key});

  @override
  State<VisaPlanPage> createState() => _VisaPlanPageState();
}

class _VisaPlanPageState extends State<VisaPlanPage> {
  // 마지막으로 본 인증 학교. 저장 중 로그인이 풀려 user가 null이 돼도 화면(입력값)을 지키려고 기억해 둔다
  VerifiedSchool? _lastSchool;

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final current = user?.verifiedSchool;
    if (current != null && current.id != _lastSchool?.id) _lastSchool = current;
    final school = user != null ? current : _lastSchool;

    // 미인증이면 일정 조회(GET)를 아예 부르지 않는다: 어차피 쓸 수 없어서 안내만 보여 주는 쪽이 단순하다
    if (school == null) {
      return PageFrame(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const PageTitle('비자 일정'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('비자 일정은 학교 인증 후에 등록할 수 있어요'),
                  const SizedBox(height: AppSizes.space3),
                  FilledButton(
                    onPressed: () => context.go('/me'),
                    child: const Text('내 정보에서 학교 인증하기'),
                  ),
                ],
              ),
            ),
            const _PlanGuide(),
          ],
        ),
      );
    }
    return const _VisaPlanView();
  }
}

// 인증 회원용 본체: 불러오기 → (없음 | 있음) 분기
class _VisaPlanView extends StatefulWidget {
  const _VisaPlanView();

  @override
  State<_VisaPlanView> createState() => _VisaPlanViewState();
}

class _VisaPlanViewState extends State<_VisaPlanView> {
  // 마지막으로 본 일정. 저장 중 로그인이 풀려 보관소가 비워져도 화면(폼)을 지키려고 기억해 둔다
  VisaPlan? _lastPlan;
  // 가장 최근 저장·삭제가 성공했는지 (로그인이 풀려 있어도 성공했으면 재로그인 안내를 내린다)
  bool _writeOk = false;
  bool _saveNeedLogin = false; // 저장·삭제 중 401(재발급까지 실패)
  // 날짜 칸의 글자가 곧 값("yyyy-MM-dd"). 저장에 실패해도 그대로 둔다
  final _date = TextEditingController();
  bool _dateFilled = false;
  bool _saving = false;
  String? _saveError;
  bool _deleting = false;
  String? _deleteError;

  @override
  void initState() {
    super.initState();
    // 들어온 순간의 상태만 본다 (이후 상태 변화를 따라가면 첫 GET 실패 직후 또 GET이 나간다).
    // 실패 상태였거나 받은 날짜가 오늘과 다르면(자정 넘김) 한 번 다시 부르고, 불러오는 중이면 기다리기만 한다.
    final store = context.read<VisaPlanStore>();
    final status = store.status;
    final loadedDay = store.loadedDay;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (status == VisaPlanStatus.failed ||
          (status == VisaPlanStatus.ready && loadedDay != null && loadedDay != store.today())) {
        store.reload();
      }
    });
  }

  @override
  void dispose() {
    _date.dispose();
    super.dispose();
  }

  // 저장·삭제 성공을 화면에 반영: 보관소(띠)와 로컬 기억을 같이 갱신한다. 로그인이 풀려 보관소가 idle이어도 화면은 바뀐다
  void _commit(VisaPlanStore store, VisaPlan next) {
    store.setPlan(next);
    if (!mounted) return;
    setState(() {
      _lastPlan = next;
      _writeOk = true;
    });
  }

  Future<void> _save() async {
    if (_saving || _deleting) return; // 저장·삭제 중 또 눌러도 한 번만
    final store = context.read<VisaPlanStore>();
    final dio = context.read<AuthState>().api.dio;
    setState(() {
      _saveError = null;
      _deleteError = null; // 이전 삭제 실패 문구가 저장 성공 뒤에도 남지 않게
      _saveNeedLogin = false;
      _writeOk = false;
    });
    final date = _date.text;
    if (date.isEmpty) {
      setState(() => _saveError = '만료일을 입력해 주세요'); // 빈 값은 서버에 보내지 않는다
      return;
    }
    setState(() => _saving = true);
    try {
      final res = await dio.put<Map<String, dynamic>>('/users/me/visa-plan', data: {'expiryDate': date});
      _commit(store, VisaPlan.fromJson(res.data!)); // 응답으로 바로 갱신한다 (다시 GET 하지 않음)
    } catch (err) {
      final info = describeError(
        err,
        const {
          400: '만료일을 확인해 주세요 (오늘부터 5년 이내의 날짜만 등록할 수 있어요)',
          403: ErrorMessages.visaSchoolRequiredRetry,
        },
        ErrorMessages.saveFailed,
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _saveNeedLogin = true;
        } else if (info is ErrorMessage) {
          _saveError = info.message; // 입력값(날짜 칸)은 그대로 남는다
        }
      });
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete() async {
    if (_saving || _deleting) return;
    final store = context.read<VisaPlanStore>();
    final dio = context.read<AuthState>().api.dio;
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    final ok = await showConfirm(context, message: '등록한 비자 일정을 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    setState(() {
      _deleting = true;
      _deleteError = null;
      _saveNeedLogin = false;
      _writeOk = false;
    });
    try {
      await dio.delete<dynamic>('/users/me/visa-plan');
      _commit(store, VisaPlan.empty); // 빈 상태로
      if (mounted) {
        setState(() => _saveError = null);
        _date.clear();
      }
    } catch (err) {
      final info = describeError(err, {404: ErrorMessages.alreadyDeleted('일정')}, ErrorMessages.deleteFailed);
      if (!mounted) return;
      if (info is NeedLogin) {
        setState(() => _saveNeedLogin = true);
      } else if (info is ErrorMessage) {
        setState(() => _deleteError = info.message);
        if (info.status == 404) {
          // 이미 없는 일정이니 화면도 빈 상태로 맞춘다
          _commit(store, VisaPlan.empty);
          _date.clear();
        }
      }
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<VisaPlanStore>();
    final status = store.status;
    final storePlan = store.plan;
    if (status == VisaPlanStatus.ready && storePlan != null && !identical(storePlan, _lastPlan)) {
      _lastPlan = storePlan; // 보관소의 새 일정을 기억해 둔다 (화면 상태를 바로 맞추는 용도라 setState 없이)
    }
    // 보관소가 ready면 보관소 값, 아니면(로그인 풀림 등) 마지막으로 본/저장한 값. null = 아직 모름(불러오는 중이거나 실패)
    final plan = status == VisaPlanStatus.ready ? storePlan : _lastPlan;
    final loading = status == VisaPlanStatus.loading;
    final loadError = status == VisaPlanStatus.failed ? store.errorMessage : null;
    // 불러오기 중 401이거나 로그인이 풀려 보관소가 비워진(idle) 경우도 같은 안내
    final needLogin =
        _saveNeedLogin || (status == VisaPlanStatus.idle && !_writeOk) || (status == VisaPlanStatus.failed && store.needLogin);
    if (!_dateFilled && plan != null) {
      // 일정이 처음 확인되면 변경 폼에 현재 값을 한 번 채운다 (아직 날짜 칸이 그려지기 전이다)
      _dateFilled = true;
      _date.text = plan.expiryDate ?? '';
    }
    final today = store.today();
    final hasPlan = plan != null && plan.hasPlan;

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('비자 일정'),
          const _PlanGuide(),
          if (loading) const Loading(),
          if (loadError != null) Notice(kind: NoticeKind.error, message: loadError),
          // 불러오기에 실패하면 일정이 있는지 모르니 폼을 숨긴다 (모르고 덮어쓰지 않게)
          if (plan != null && hasPlan) _PlanSummary(plan: plan),
          if (plan != null && !hasPlan)
            const Panel(
              child: Text('등록된 일정 없음', key: ValueKey('visa-empty')),
            ),
          if (plan != null)
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _PlanDateField(
                    controller: _date,
                    // 달력은 서울 오늘 ~ 오늘+5년만 고르게 한다. 최종 판단은 서버가 한다
                    firstDay: today,
                    lastDay: addYears(today, 5),
                  ),
                  if (_saveError != null) Notice(kind: NoticeKind.error, message: _saveError),
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: AppSizes.space2,
                    runSpacing: AppSizes.space2,
                    children: [
                      FilledButton(
                        onPressed: (_saving || _deleting) ? null : _save,
                        child: Text(_saving ? '저장 중...' : (hasPlan ? '만료일 변경' : '저장')),
                      ),
                      if (hasPlan)
                        OutlinedButton(
                          style: dangerButtonStyle,
                          onPressed: (_saving || _deleting) ? null : _delete,
                          child: Text(_deleting ? '삭제 중...' : '삭제'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          // 삭제 실패·로그인 풀림 안내는 폼 밖에 둔다
          if (_deleteError != null) Notice(kind: NoticeKind.error, message: _deleteError),
          if (needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}

// 일정이 있을 때 보이는 판: 큰 D-n, 만료일, (지났으면) 안내, 할 일 목록
class _PlanSummary extends StatelessWidget {
  const _PlanSummary({required this.plan});

  final VisaPlan plan;

  @override
  Widget build(BuildContext context) {
    final stage = plan.stage;
    // 큰 D-n: 단계(stage)는 색 강조에만 쓴다. 평소엔 본문색, CHECK·URGENT는 청자, 지났으면 흐리게 (빨강은 쓰지 않는다)
    final ddayColor = switch (stage) {
      'CHECK' || 'URGENT' => AppColors.celadon,
      'EXPIRED' => AppColors.muted,
      _ => AppColors.ink,
    };
    final expiry = plan.expiryDate ?? '';
    return Semantics(
      container: true,
      label: '내 비자 일정',
      child: Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              formatDDay(plan.daysLeft ?? 0),
              key: const ValueKey('visa-dday'),
              style: TextStyle(fontSize: AppSizes.fsBoard, fontWeight: FontWeight.w700, height: 1.2, color: ddayColor),
            ),
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space1, bottom: AppSizes.space3),
              child: Text(
                '체류 만료일 ${formatDay(expiry)}',
                style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
              ),
            ),
            if (stage == 'EXPIRED') Notice(kind: NoticeKind.info, message: visaBannerText['EXPIRED']),
            if (plan.todos.isNotEmpty) ...[
              const Padding(
                padding: EdgeInsets.only(top: AppSizes.space3),
                child: SectionHeading('할 일'),
              ),
              Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = 0; i < plan.todos.length; i++)
                    _TodoRow(index: i, todo: plan.todos[i], stage: stage, expiry: expiry, first: i == 0),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

// 할 일 한 줄: 만료 N일 전부터 · 날짜 · 상태 글자, 문구는 다음 줄
class _TodoRow extends StatelessWidget {
  const _TodoRow({required this.index, required this.todo, required this.stage, required this.expiry, required this.first});

  final int index;
  final VisaTodo todo;
  final String? stage;
  final String expiry;
  final bool first;

  @override
  Widget build(BuildContext context) {
    // 할 일의 날짜는 마감이 아니라 "확인을 시작할 시점"이다. 상태를 네 가지 글자로도 알린다(색만으로 알리지 않음)
    // 모르는 stage가 와도 단순 비교라 예외가 나지 않는다 (그냥 현재 단계 줄이 없을 뿐)
    final isCurrent = todo.code == stage; // 지금 들어와 있는 단계
    final isPast = !isCurrent && todo.daysLeft < 0; // 이미 지나간 단계 (EXPIRED면 전부)
    final statusText = isCurrent
        ? '지금 확인할 때'
        : todo.daysLeft > 0
        ? '${todo.daysLeft}일 뒤 시작'
        : todo.daysLeft == 0
        ? '오늘 시작' // 서버 규칙상 도달 불가(0인 날은 stage가 그 code). 모르는 stage 대비
        : '지난 단계';
    final baseColor = isPast ? AppColors.muted : AppColors.ink;
    final accent = isCurrent ? AppColors.celadon : baseColor; // 현재 줄은 만료 N일 전·상태 글자만 청자색
    return Container(
      key: ValueKey('visa-todo-$index'),
      padding: const EdgeInsets.symmetric(vertical: AppSizes.space3),
      decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: AppColors.line))),
      child: DefaultTextStyle.merge(
        style: TextStyle(fontSize: AppSizes.fsBody, color: baseColor),
        child: Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: AppSizes.space3,
          runSpacing: AppSizes.space1,
          children: [
            // 위 큰 D-n(만료일 기준)과 섞이지 않게, 이 줄은 "만료 N일 전부터"로 쓴다. 줄마다 날짜 시작 위치를 맞춘다
            ConstrainedBox(
              constraints: const BoxConstraints(minWidth: 128),
              child: Text(
                '만료 ${daysBetween(todo.dueDate, expiry)}일 전부터',
                key: ValueKey('visa-todo-dday-$index'),
                style: TextStyle(fontWeight: FontWeight.w700, color: accent),
              ),
            ),
            // 날짜(부터 없음)와 상태 글자는 따로 읽혀도 뜻이 서게 쓴다
            Text(
              formatDay(todo.dueDate),
              key: ValueKey('visa-todo-date-$index'),
              style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
            ),
            Text(
              statusText,
              key: ValueKey('visa-todo-status-$index'),
              style: TextStyle(
                fontSize: AppSizes.fsSmall,
                color: isCurrent ? AppColors.celadon : AppColors.muted,
                fontWeight: isCurrent ? FontWeight.w500 : FontWeight.w400,
              ),
            ),
            SizedBox(
              width: double.infinity,
              child: Text(todo.title, key: ValueKey('visa-todo-title-$index')),
            ),
          ],
        ),
      ),
    );
  }
}

// 체류 만료일 날짜 칸 (React의 <input type="date" min max>). 직접 타이핑하지 않는 읽기 전용 칸이고,
// 누르면 달력이 열려 고른 날이 "yyyy-MM-dd"로 칸에 들어간다. 서울 오늘 ~ 오늘+5년만 고를 수 있다.
class _PlanDateField extends StatelessWidget {
  const _PlanDateField({required this.controller, required this.firstDay, required this.lastDay});

  final TextEditingController controller;
  final String firstDay; // 고를 수 있는 첫 날 (서울 오늘)
  final String lastDay; // 고를 수 있는 마지막 날 (오늘+5년)

  static const _label = '체류 만료일';

  Future<void> _pick(BuildContext context) async {
    // 달력은 로컬 자정 DateTime을 쓰므로 연·월·일만 옮겨 담는다
    DateTime local(String day) {
      final d = parseYmd(day)!;
      return DateTime(d.year, d.month, d.day);
    }

    final first = local(firstDay);
    final last = local(lastDay);
    // 처음 보여 줄 날: 이미 값이 있으면 그 날, 없으면 오늘. 범위 밖(이미 지난 만료일 등)이면 범위 안으로 당긴다
    final existing = parseYmd(controller.text);
    var initial = existing == null ? first : DateTime(existing.year, existing.month, existing.day);
    if (initial.isAfter(last)) initial = last;
    if (initial.isBefore(first)) initial = first;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: first,
      lastDate: last,
      initialEntryMode: DatePickerEntryMode.calendar,
      helpText: _label,
      cancelText: '취소',
      confirmText: '확인',
      fieldLabelText: _label,
      fieldHintText: 'yyyy-MM-dd',
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
            child: const Text(
              _label,
              style: TextStyle(fontSize: AppSizes.fsSmall2, fontWeight: FontWeight.w500, color: AppColors.ink),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            label: _label,
            child: TextField(
              key: const ValueKey('visa-expiry-field'),
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
