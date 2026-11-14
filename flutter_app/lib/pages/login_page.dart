import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../auth/auth_state.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/auth_card.dart';
import '../widgets/notice.dart';

// 로그인 화면 (React의 LoginPage). 클라이언트 검증은 없다: 빈 값도 서버로 보내고 서버의 400 문구를 보여 준다.
class LoginPage extends StatefulWidget {
  const LoginPage({super.key, this.notice});

  // 회원가입 화면이 넘겨준 "가입 완료" 같은 안내 문구 (없으면 null)
  final String? notice;

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return; // 요청 중 Enter를 또 눌러도 한 번만 보낸다 (React는 버튼이 잠겨 제출이 막힌다)
    final auth = context.read<AuthState>();
    final router = GoRouter.of(context);
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await auth.login(_username.text, _password.text); // 성공하면 로그인 상태가 갱신된다
      if (mounted) router.go('/'); // 글 목록으로
    } catch (err) {
      // 여기서 401은 "로그인 풀림"이 아니라 아이디·비밀번호 오류라서 표에 직접 적는다
      // 403: 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우 (서버가 꺼져 있었다 등)
      final info = describeError(
        err,
        {
          401: '아이디 또는 비밀번호가 틀렸습니다',
          400: '아이디와 비밀번호를 입력해 주세요',
          403: csrfMessage,
        },
        '로그인하지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        if (info is ErrorMessage) _error = info.message;
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final notice = widget.notice;
    // 카드 틀(최대 400 폭 가운데)은 회원가입과 같이 쓰는 AuthCard
    return AuthCard(
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSizes.space4),
          // 제목은 스크린리더가 가장 큰 제목(React의 h1)으로 읽게 한다
          child: Semantics(
            header: true,
            headingLevel: 1,
            child: Text('로그인', style: Theme.of(context).textTheme.titleLarge),
          ),
        ),
        if (notice != null) Notice(kind: NoticeKind.info, message: notice),
        const Notice(kind: NoticeKind.info, message: 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요'),
        // 휴대폰이 첫 글자를 대문자로 바꾸면 로그인이 실패하므로 자동 대문자·맞춤법 검사를 끈다
        TextField(
          controller: _username,
          decoration: const InputDecoration(labelText: '아이디'),
          autofillHints: const [AutofillHints.username],
          textCapitalization: TextCapitalization.none,
          autocorrect: false,
          enableSuggestions: false,
          // Enter로 바로 제출하므로 키보드 버튼도 "이동(next)"이 아니라 "실행(go)"으로 맞춘다
          textInputAction: TextInputAction.go,
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: AppSizes.space4),
        TextField(
          controller: _password,
          decoration: const InputDecoration(labelText: '비밀번호'),
          obscureText: true,
          autofillHints: const [AutofillHints.password],
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: AppSizes.space4),
        if (_error != null) Notice(kind: NoticeKind.error, message: _error),
        // 요청 중엔 버튼만 잠근다(입력칸은 그대로)
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: Text(_saving ? '로그인 중...' : '로그인'),
        ),
        const SizedBox(height: AppSizes.space2),
        OutlinedButton(
          onPressed: () => context.go('/'),
          child: const Text('목록으로'),
        ),
      ],
    );
  }
}
