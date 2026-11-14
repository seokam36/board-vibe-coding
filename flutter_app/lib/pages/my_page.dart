import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/school_verification_section.dart';
import '../widgets/section_parts.dart';
import '../widgets/small_button_style.dart';

// 이 화면만 쓰는 문구는 여기에 둔다 (React의 MyPage와 같은 글자)
const _httpWarning = 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요';
const _nicknameHint = '한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가';

// 내 정보 화면 "/me" (React의 MyPage): 아이디·닉네임 표시 + 학교 인증 자리 + 닉네임 변경 / 비밀번호 변경 / 회원 탈퇴.
// 로그인이 풀리면(401) 입력 내용을 지키려고 화면을 그대로 두고, 각 폼이 안내(ReloginNotice)를 보여 준다.
class MyPage extends StatelessWidget {
  const MyPage({super.key});

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('내 정보'),
          // 아이디는 바꿀 수 없어 읽기 전용 글자. 로그인이 풀린 직후엔 user가 없을 수 있다
          if (user != null)
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('아이디: ${user.username}'),
                  const SizedBox(height: AppSizes.space2),
                  Text('닉네임: ${user.nickname}'),
                ],
              ),
            ),
          // 학교 인증: 인증했으면 뱃지, 아직이면 메일 인증 폼
          const SchoolVerificationSection(),
          const _NicknameForm(),
          const _PasswordForm(),
          const _WithdrawForm(),
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton(
              onPressed: () => context.go('/'),
              child: const Text('목록으로'),
            ),
          ),
        ],
      ),
    );
  }
}

// 제목·안내 글자는 학교 인증 섹션과 같이 쓰는 공용 부품 (이 파일 안에서는 예전 이름 그대로 부른다)
typedef _Heading = SectionHeading;
typedef _Hint = FieldHint;

// ---------- 닉네임 변경 ----------
class _NicknameForm extends StatefulWidget {
  const _NicknameForm();

  @override
  State<_NicknameForm> createState() => _NicknameFormState();
}

class _NicknameFormState extends State<_NicknameForm> {
  final _nickname = TextEditingController();
  bool _saving = false;
  bool _needLogin = false; // 401: 자동 재발급까지 실패해 로그인이 풀림
  String? _error;
  String? _success;

  @override
  void dispose() {
    _nickname.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return; // 요청 중 Enter를 또 눌러도 한 번만 보낸다
    final auth = context.read<AuthState>();
    final dio = context.read<ApiClient>().dio;
    setState(() {
      _saving = true;
      _error = null;
      _success = null;
      _needLogin = false;
    });
    try {
      // 형식·길이 검사는 서버 400에 맡긴다 (클라이언트 검증 없음)
      final res = await dio.put<Map<String, dynamic>>('/users/me/nickname', data: {'nickname': _nickname.text});
      auth.updateUser(AuthUser.fromJson(res.data!)); // 상단바와 위쪽 닉네임 표시가 새 닉네임으로 바뀐다
      if (!mounted) return;
      _nickname.clear();
      setState(() {
        _success = '닉네임이 바뀌었습니다';
        _saving = false;
      });
    } catch (err) {
      final info = describeError(
        err,
        {
          409: '이미 사용 중인 닉네임입니다',
          400: '닉네임은 한글(완성형)·영문·숫자·_ 2~20자로 입력해 주세요 (공백·자음/모음만은 불가)',
          403: csrfMessage,
        },
        '닉네임을 바꾸지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _Heading('닉네임 변경'),
          TextField(
            controller: _nickname,
            decoration: const InputDecoration(labelText: '새 닉네임'),
            autofillHints: const [AutofillHints.nickname],
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
          ),
          const _Hint(_nicknameHint),
          if (_needLogin) const ReloginNotice(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error),
          if (_success != null) Notice(kind: NoticeKind.success, message: _success),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(
              onPressed: _saving ? null : _submit,
              child: Text(_saving ? '변경 중...' : '닉네임 변경'),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------- 비밀번호 변경 ----------
class _PasswordForm extends StatefulWidget {
  const _PasswordForm();

  @override
  State<_PasswordForm> createState() => _PasswordFormState();
}

class _PasswordFormState extends State<_PasswordForm> {
  final _current = TextEditingController();
  final _next = TextEditingController();
  bool _saving = false;
  bool _needLogin = false;
  String? _error;

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return;
    final auth = context.read<AuthState>();
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    setState(() {
      _saving = true;
      _error = null;
      _needLogin = false;
    });
    try {
      await dio.put<dynamic>('/users/me/password', data: {
        'currentPassword': _current.text,
        'newPassword': _next.text,
      });
      // 서버가 이미 쿠키를 지웠으니 /auth/logout 은 부르지 않는다.
      // 이동을 "먼저" 한다: 로그인 상태를 먼저 비우면 아직 /me 인 화면을 문지기가 /login 으로 덮어써 안내 문구가 사라진다.
      router.go('/login', extra: '비밀번호가 바뀌었습니다. 다시 로그인해 주세요');
      auth.clearUser();
      // 성공하면 화면이 바뀌므로 버튼은 잠긴 채로 둔다
    } catch (err) {
      // 400: 서버는 "현재 비밀번호 틀림"도 400으로 준다(원인을 구분해 주지 않아 함께 안내)
      // 500: 비밀번호를 바꾼 뒤 서버 후처리 실패로 500이 날 수 있어 이미 바뀌었을 수도 있다
      final info = describeError(
        err,
        {
          400: '현재 비밀번호가 맞는지, 새 비밀번호가 8~64자이고 현재 비밀번호와 다른지 확인해 주세요',
          403: csrfMessage,
          500: '비밀번호가 이미 바뀌었을 수 있습니다. 새 비밀번호로 다시 로그인해 보세요',
        },
        '비밀번호를 바꾸지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false; // 실패했을 때만 되살린다
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _Heading('비밀번호 변경'),
          const Notice(kind: NoticeKind.info, message: _httpWarning),
          TextField(
            controller: _current,
            decoration: const InputDecoration(labelText: '현재 비밀번호'),
            obscureText: true,
            autofillHints: const [AutofillHints.password],
            textInputAction: TextInputAction.next,
          ),
          const SizedBox(height: AppSizes.space4),
          TextField(
            controller: _next,
            decoration: const InputDecoration(labelText: '새 비밀번호'),
            obscureText: true,
            autofillHints: const [AutofillHints.newPassword],
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
          ),
          const _Hint('8~64자'),
          if (_needLogin) const ReloginNotice(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error),
          Align(
            alignment: Alignment.centerLeft,
            child: FilledButton(
              onPressed: _saving ? null : _submit,
              child: Text(_saving ? '변경 중...' : '비밀번호 변경'),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------- 회원 탈퇴 ----------
class _WithdrawForm extends StatefulWidget {
  const _WithdrawForm();

  @override
  State<_WithdrawForm> createState() => _WithdrawFormState();
}

class _WithdrawFormState extends State<_WithdrawForm> {
  final _password = TextEditingController();
  bool _saving = false;
  bool _needLogin = false;
  String? _error;

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return;
    final auth = context.read<AuthState>();
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    // 되돌릴 수 없는 작업이라 한 번 더 묻는다. 취소하면 요청을 보내지 않는다
    final ok = await showConfirm(
      context,
      message: "탈퇴하면 되돌릴 수 없습니다. 쓴 글은 남고 작성자는 '(작성자 없음)'으로 바뀝니다. 탈퇴할까요?",
      confirmLabel: '탈퇴',
      danger: true,
    );
    if (!ok || !mounted) return;
    setState(() {
      _saving = true;
      _error = null;
      _needLogin = false;
    });
    try {
      // DELETE지만 본문이 있다: dio에서는 data 옵션으로 보낸다
      await dio.delete<dynamic>('/users/me', data: {'password': _password.text});
      // 서버가 쿠키를 이미 지웠으므로 /auth/logout 은 부르지 않는다. 이동을 먼저, 그다음 상태 비우기(위 비밀번호 변경과 같은 이유)
      router.go('/', extra: '탈퇴가 완료되었습니다');
      auth.clearUser();
    } catch (err) {
      // 500: 회원을 지운 뒤 후처리 실패일 수 있지만 지워졌는지 알 수 없어 로그인 상태는 비우지 않는다
      final info = describeError(
        err,
        {
          400: '비밀번호를 입력했는지, 맞는지 확인해 주세요',
          403: csrfMessage,
          500: '탈퇴가 이미 처리됐을 수 있습니다. 새로고침해서 로그인 상태를 확인해 주세요',
        },
        '탈퇴하지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _Heading('회원 탈퇴', danger: true),
          TextField(
            controller: _password,
            decoration: const InputDecoration(labelText: '비밀번호'),
            obscureText: true,
            autofillHints: const [AutofillHints.password],
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
          ),
          const SizedBox(height: AppSizes.space4),
          if (_needLogin) const ReloginNotice(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error),
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton(
              style: dangerButtonStyle,
              onPressed: _saving ? null : _submit,
              child: Text(_saving ? '처리 중...' : '회원 탈퇴'),
            ),
          ),
        ],
      ),
    );
  }
}
