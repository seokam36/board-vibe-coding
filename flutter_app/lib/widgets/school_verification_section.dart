import 'dart:async';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import 'app_badge.dart';
import 'notice.dart';
import 'panel.dart';
import 'relogin_notice.dart';
import 'section_parts.dart';

const _showDomains = 3; // 도메인 안내에 보여 줄 최대 개수
const _resendSeconds = 60; // 서버의 재요청 쿨다운과 같다

enum _Step { email, code }

// 내 정보 화면의 "학교 인증" 섹션 (React의 SchoolVerificationForm).
// 인증 완료면 뱃지만, 미인증이면 [메일 입력 → 코드 보내기 → 6자리 코드 입력 → 확인] 두 단계를 보여 준다.
// now: 지금 시각을 주는 함수. 테스트가 시계를 마음대로 돌리려고 바꿔 끼운다.
class SchoolVerificationSection extends StatefulWidget {
  const SchoolVerificationSection({super.key, this.now = DateTime.now});

  final DateTime Function() now;

  @override
  State<SchoolVerificationSection> createState() => _SchoolVerificationSectionState();
}

class _SchoolVerificationSectionState extends State<SchoolVerificationSection> {
  final _email = TextEditingController();
  final _code = TextEditingController();
  _Step _step = _Step.email;
  bool _busy = false; // 요청 중에는 버튼을 잠가 중복 클릭을 막는다
  bool _needLogin = false;
  String? _error;
  String? _info;
  List<School> _schools = const []; // 허용 도메인 안내용. 못 받으면 안내만 생략

  // 재전송 쿨다운: 끝나는 시각을 저장하고, 남은 초는 (끝 - 지금)을 올림해서 구한다 (화면이 느려져도 어긋나지 않는다)
  DateTime? _cooldownEnd;
  late DateTime _now = widget.now();
  Timer? _timer;

  int get _cooldown {
    final end = _cooldownEnd;
    if (end == null) return 0;
    final ms = end.difference(_now).inMilliseconds;
    return ms <= 0 ? 0 : (ms / 1000).ceil();
  }

  bool get _counting => _cooldown > 0;

  @override
  void initState() {
    super.initState();
    _loadSchools();
  }

  @override
  void dispose() {
    _timer?.cancel(); // 화면이 사라진 뒤에 타이머가 남지 않게
    _email.dispose();
    _code.dispose();
    super.dispose();
  }

  // 허용 도메인 안내에 쓸 학교 목록을 한 번 받는다 (실패해도 인증 자체는 진행 가능)
  Future<void> _loadSchools() async {
    final dio = context.read<ApiClient>().dio;
    try {
      final res = await dio.get<List<dynamic>>('/schools');
      final list = res.data!.map((e) => School.fromJson(e as Map<String, dynamic>)).toList();
      if (mounted) setState(() => _schools = list);
    } catch (_) {
      // 조용히 무시
    }
  }

  // 쿨다운 시작: 끝 시각을 정하고 0.5초마다 지금 시각을 갱신한다. 끝나면 타이머를 멈춘다
  void _startCooldown() {
    _now = widget.now();
    _cooldownEnd = _now.add(const Duration(seconds: _resendSeconds));
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      setState(() => _now = widget.now());
      if (!_counting) t.cancel();
    });
  }

  void _resetNotices() {
    _error = null;
    _info = null;
    _needLogin = false;
  }

  // 코드 보내기 / 다시 보내기 (같은 요청)
  Future<void> _sendCode() async {
    if (_busy) return;
    final dio = context.read<ApiClient>().dio;
    final auth = context.read<AuthState>();
    setState(() {
      _resetNotices();
      _busy = true;
    });
    var refresh = false;
    try {
      await dio.post<dynamic>('/users/me/school-verification/code', data: {'email': _email.text.trim()});
      if (!mounted) return;
      setState(() {
        _step = _Step.code;
        _code.clear();
        _startCooldown();
        _info = '인증 코드를 보냈어요. 코드는 10분 동안 유효해요';
      });
    } catch (err) {
      final names = _schools.map((s) => s.name).join(', ');
      final info = describeError(
        err,
        {
          400: '학교 메일 주소를 확인해 주세요${names.isEmpty ? '' : ' (지원 학교: $names)'}',
          409: '이미 인증했거나 다른 계정이 인증에 쓴 메일이에요',
          429: '이미 보낸 코드가 있으면 메일의 코드를 입력해 주세요 (1분에 한 번, 하루 10번까지 보낼 수 있어요)',
          403: csrfMessage,
          500: '메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요',
        },
        '코드를 보내지 못했어요',
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          if (info.status == 429) {
            // 기존 코드는 그대로 유효하니 코드 입력 단계로 넘기고, 연타 방지로 쿨다운도 건다 (오류가 아니라 안내)
            _step = _Step.code;
            _code.clear();
            _startCooldown();
            _info = info.message;
          } else {
            _error = info.message;
            // 이미 인증됐거나 메일이 겹친 경우: 회원 정보를 다시 받아 이미 인증됐다면 바로 완료 화면으로
            refresh = info.status == 409;
          }
        }
      });
    }
    if (refresh) await auth.refreshUser();
    if (mounted) setState(() => _busy = false);
  }

  // 받은 코드 확인: 성공하면 서버가 돌려준 회원 정보로 바로 갱신해 상단바까지 즉시 바뀐다
  Future<void> _verify() async {
    if (_busy || _code.text.length != 6) return;
    final dio = context.read<ApiClient>().dio;
    final auth = context.read<AuthState>();
    setState(() {
      _resetNotices();
      _busy = true;
    });
    var refresh = false;
    try {
      final res = await dio.post<Map<String, dynamic>>(
        '/users/me/school-verification',
        data: {'code': _code.text},
      );
      auth.updateUser(AuthUser.fromJson(res.data!)); // verifiedSchool이 생기면 이 섹션은 "인증 완료" 모양으로 바뀐다
    } catch (err) {
      final info = describeError(
        err,
        {
          400: '코드가 맞지 않아요. 다시 입력해 주세요',
          410: '코드가 만료됐거나 너무 많이 틀렸어요. 코드를 다시 받아 주세요',
          409: '이미 인증됐거나 다른 계정이 쓴 메일이에요',
          403: csrfMessage,
        },
        '인증하지 못했어요',
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          if (info.status == 410) {
            // 코드가 폐기됐으니 입력칸을 비우고 메일 입력 단계로 돌려보낸다 (메일 주소는 남겨 둔다)
            _step = _Step.email;
            _code.clear();
            // 보내기 쿨다운이 남아 있으면 언제 다시 받을 수 있는지 덧붙인다
            _error = _counting ? '${info.message} ($_cooldown초 뒤에 다시 받을 수 있어요)' : info.message;
          } else if (info.status == 409) {
            // 코드는 다시 받아야 한다. 이미 인증됐다면 회원 정보를 새로 받아 바로 완료 뱃지로
            _step = _Step.email;
            _code.clear();
            _error = info.message;
            refresh = true;
          } else {
            _error = info.message; // 400 등: 코드 칸은 그대로 두고 다시 입력하게
          }
        }
      });
    }
    if (refresh) await auth.refreshUser();
    if (mounted) setState(() => _busy = false);
  }

  // "@a, @b, @c 등" 식의 허용 도메인 안내 (맨 앞 몇 개만). 도메인이 없으면 null
  String? get _domainHint {
    final domains = _schools.expand((s) => s.emailDomains).toList();
    if (domains.isEmpty) return null;
    final shown = domains.take(_showDomains).map((d) => '@$d').join(', ');
    // 표시 개수보다 도메인이 더 많을 때만 "등"을 붙인다
    return '$shown${domains.length > _showDomains ? ' 등' : ''} 학교 메일만 쓸 수 있어요';
  }

  @override
  Widget build(BuildContext context) {
    final school = context.watch<AuthState>().user?.verifiedSchool;
    // 이미 인증했으면 뱃지와 안내만 보여 준다
    if (school != null) {
      return Panel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SectionHeading('학교 인증'),
            AppBadge(text: '학교 인증 완료 · ${school.name}', ink: AppColors.celadonInk, bg: AppColors.celadonSoft),
            const FieldHint('졸업 후에도 유지돼요'),
          ],
        ),
      );
    }
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SectionHeading('학교 인증'),
          const Padding(
            padding: EdgeInsets.only(bottom: AppSizes.space3),
            child: Text('학교 메일로 인증하면 글쓰기·댓글·공감·신고를 할 수 있어요'),
          ),
          if (_step == _Step.email) _emailStep() else _codeStep(),
        ],
      ),
    );
  }

  // 1단계: 메일 입력 → 코드 보내기
  Widget _emailStep() {
    final hint = _domainHint;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _email,
          decoration: const InputDecoration(labelText: '학교 메일'),
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
          textInputAction: TextInputAction.done,
          onSubmitted: (_) {
            if (!_counting) _sendCode();
          },
        ),
        if (hint != null) FieldHint(hint) else const SizedBox(height: AppSizes.space4),
        if (_needLogin) const ReloginNotice(),
        if (_error != null) Notice(kind: NoticeKind.error, message: _error),
        Wrap(
          spacing: AppSizes.space2,
          runSpacing: AppSizes.space2,
          children: [
            FilledButton(
              onPressed: _busy || _counting ? null : _sendCode,
              child: Text(_busy ? '보내는 중...' : _counting ? '코드 보내기 ($_cooldown초)' : '코드 보내기'),
            ),
            // 새로고침 등으로 이 단계로 돌아와도 이미 받은 코드를 입력할 수 있게
            OutlinedButton(
              onPressed: _busy
                  ? null
                  : () => setState(() {
                        _resetNotices();
                        _step = _Step.code;
                      }),
              child: const Text('이미 코드를 받았어요'),
            ),
          ],
        ),
      ],
    );
  }

  // 2단계: 6자리 코드 입력 → 확인
  Widget _codeStep() {
    final full = _code.text.length == 6;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _code,
          decoration: const InputDecoration(labelText: '인증 코드 (6자리)'),
          keyboardType: TextInputType.number,
          autofillHints: const [AutofillHints.oneTimeCode],
          // 숫자만 받고 6자리까지만 (붙여넣기도 걸러진다)
          inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
          textInputAction: TextInputAction.done,
          onChanged: (_) => setState(() {}), // 6자리가 됐는지에 따라 [확인]·안내가 바뀐다
          onSubmitted: (_) => _verify(),
        ),
        const FieldHint('메일이 안 보이면 스팸함도 확인해 주세요'),
        // [확인]이 꺼져 있는 이유
        if (!full) const FieldHint('6자리를 입력하면 확인할 수 있어요'),
        if (_needLogin) const ReloginNotice(),
        if (_info != null) Notice(kind: NoticeKind.info, message: _info),
        if (_error != null) Notice(kind: NoticeKind.error, message: _error),
        Wrap(
          spacing: AppSizes.space2,
          runSpacing: AppSizes.space2,
          children: [
            FilledButton(
              onPressed: _busy || !full ? null : _verify,
              child: Text(_busy ? '확인 중...' : '확인'),
            ),
            OutlinedButton(
              onPressed: _busy || _counting ? null : _sendCode,
              child: Text(_counting ? '다시 보내기 ($_cooldown초)' : '다시 보내기'),
            ),
            OutlinedButton(
              onPressed: _busy
                  ? null
                  : () => setState(() {
                        _resetNotices();
                        _step = _Step.email;
                      }),
              child: const Text('메일 바꾸기'),
            ),
          ],
        ),
      ],
    );
  }
}
