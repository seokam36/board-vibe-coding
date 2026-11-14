import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../visa/review_options_loader.dart';
import '../visa/visa_disclaimer.dart';
import '../visa/visa_review_form.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

// 비자 후기 쓰기 "/visa-reviews/new" (React의 VisaReviewCreatePage).
// 학교 인증을 마친 회원만 쓸 수 있다. 학교는 고르지 않고 서버가 인증 학교로 정한다.
class VisaReviewCreatePage extends StatefulWidget {
  const VisaReviewCreatePage({super.key});

  @override
  State<VisaReviewCreatePage> createState() => _VisaReviewCreatePageState();
}

class _VisaReviewCreatePageState extends State<VisaReviewCreatePage> with VisaReviewOptionsLoader {
  // 마지막으로 본 인증 학교. 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고 기억해 둔다
  VerifiedSchool? _lastSchool;

  @override
  void initState() {
    super.initState();
    loadReviewOptions(); // 화면이 처음 나타날 때 선택지를 한 번 불러온다
  }

  // 폼이 부르는 저장 함수. 성공하면 새 후기 상세로 이동하고, 실패하면 throw해서 폼이 문구를 보여 준다
  Future<void> _save(Map<String, dynamic> body) async {
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    final res = await dio.post<Map<String, dynamic>>('/visa-reviews', data: body);
    // 기록이 남는 go (React의 navigate): 뒤로 가기로 쓰기 화면에 돌아올 수 있다
    if (mounted) router.go('/visa-reviews/${res.data!['id']}');
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final current = user?.verifiedSchool;
    if (current != null && current.id != _lastSchool?.id) _lastSchool = current;
    // 미인증 판단은 user가 있을 때만. user가 null(세션 만료)이면 기억해 둔 학교로 폼을 유지 → ReloginNotice가 보인다
    final school = user != null ? current : _lastSchool;

    // 미인증: 폼과 면책 문구 대신 안내와 인증 화면 링크만 보여 준다
    if (school == null) {
      return PageFrame(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const PageTitle('비자 후기 쓰기'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('비자 후기 작성은 학교 인증 후에 할 수 있어요'),
                  const SizedBox(height: AppSizes.space3),
                  FilledButton(
                    onPressed: () => context.go('/me'),
                    child: const Text('내 정보에서 학교 인증하기'),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('비자 후기 쓰기'),
          const VisaDisclaimer(),
          VisaReviewForm(
            schoolName: school.name,
            options: reviewOptions,
            optionsFailed: optionsFailed,
            onSave: _save,
            errorTable: const {
              400: ErrorMessages.reviewFieldsInvalid,
              // 미인증이거나 보안 토큰이 어긋났을 때 (둘 다 403)
              403: ErrorMessages.visaSchoolRequiredRetry,
            },
            cancelTo: '/visa-reviews',
            cancelLabel: '목록으로',
          ),
        ],
      ),
    );
  }
}
