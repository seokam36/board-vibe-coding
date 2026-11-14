import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/auth_card.dart';
import '../widgets/notice.dart';

// 회원가입 화면 (React의 SignupPage). 입력 형식 검사는 하지 않고 빈 값도 서버로 보내 서버의 400 문구를 쓴다.
class SignupPage extends StatefulWidget {
  const SignupPage({super.key});

  @override
  State<SignupPage> createState() => _SignupPageState();
}

class _SignupPageState extends State<SignupPage> {
  final _username = TextEditingController();
  final _nickname = TextEditingController();
  final _password = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _username.dispose();
    _nickname.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving) return; // 요청 중 Enter를 또 눌러도 한 번만 보낸다 (React는 버튼이 잠겨 제출이 막힌다)
    final api = context.read<ApiClient>();
    final router = GoRouter.of(context);
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await api.dio.post<dynamic>(
        '/auth/signup',
        data: {
          'username': _username.text,
          'nickname': _nickname.text,
          'password': _password.text,
        },
      );
      // 성공: "가입 완료" 문구를 extra로 로그인 화면에 넘긴다
      if (mounted) router.go('/login', extra: '가입 완료! 로그인해 주세요');
    } catch (err) {
      // 409: 서버가 아이디·닉네임 중 어느 쪽인지 구분해 알려 주지 않는다
      // 400: 프론트에서 형식 검사를 안 하니 서버 규칙을 문구로 알려 준다
      // 403: 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우
      final info = describeError(
        err,
        {
          409: '아이디 또는 닉네임이 이미 사용 중입니다',
          400:
              '아이디는 영문 소문자·숫자·_ 4~20자, 닉네임은 한글(완성형)·영문·숫자·_ 2~20자(공백·자음/모음만은 불가), 비밀번호는 8~64자로 입력해 주세요',
          403: csrfMessage,
        },
        '가입하지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        // 이 화면은 로그인이 필요 없어 401(NeedLogin)은 안 오지만, 오면 기본 문구를 보여 준다
        _error = info is ErrorMessage ? info.message : '가입하지 못했습니다';
        _saving = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AuthCard(
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: AppSizes.space4),
          // 제목은 스크린리더가 가장 큰 제목(React의 h1)으로 읽게 한다
          child: Semantics(
            header: true,
            headingLevel: 1,
            child: Text('회원가입', style: Theme.of(context).textTheme.titleLarge),
          ),
        ),
        const Notice(kind: NoticeKind.info, message: 'HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요'),
        // 휴대폰이 첫 글자를 대문자로 바꾸면 서버 규칙(소문자만)에 걸리므로 자동 대문자·맞춤법 검사를 끈다
        TextField(
          controller: _username,
          decoration: const InputDecoration(labelText: '아이디'),
          autofillHints: const [AutofillHints.username],
          textCapitalization: TextCapitalization.none,
          autocorrect: false,
          enableSuggestions: false,
          // Enter로 바로 제출하므로 키보드 버튼도 "실행(go)"으로 맞춘다
          textInputAction: TextInputAction.go,
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: AppSizes.space4),
        // 닉네임: 길이 제한(maxLength)을 걸지 않는다. 붙여넣은 긴 글이 몰래 잘려 엉뚱한 닉네임이 되는 걸 막고 서버(400)에 맡긴다
        TextField(
          controller: _nickname,
          decoration: const InputDecoration(labelText: '닉네임'),
          autofillHints: const [AutofillHints.nickname],
          textInputAction: TextInputAction.go,
          onSubmitted: (_) => _submit(),
        ),
        // 서버는 완성형 한글만 받으므로 "ㅋㅋ" 같은 자음·모음만은 안 된다고 미리 알려 준다
        Padding(
          padding: const EdgeInsets.only(top: AppSizes.space1),
          child: Text(
            '한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
        const SizedBox(height: AppSizes.space4),
        TextField(
          controller: _password,
          decoration: const InputDecoration(labelText: '비밀번호'),
          obscureText: true,
          autofillHints: const [AutofillHints.newPassword],
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _submit(),
        ),
        const SizedBox(height: AppSizes.space4),
        if (_error != null) Notice(kind: NoticeKind.error, message: _error),
        // 요청 중엔 버튼만 잠근다(입력칸은 그대로)
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: Text(_saving ? '가입 중...' : '가입'),
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
