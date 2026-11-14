import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../errors/error_messages.dart';
import '../visa/models.dart';
import '../visa/review_options_loader.dart';
import '../visa/visa_disclaimer.dart';
import '../visa/visa_review_form.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

// 이 화면만 쓰는 문구
const _forbiddenMineMessage = '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요';
const _forbiddenOthersMessage = '본인 후기만 수정할 수 있습니다';

// 비자 후기 수정 "/visa-reviews/:id/edit" (React의 VisaReviewEditPage): 기존 후기로 채운 같은 폼.
// 작성자 확인은 화면에서 하지 않는다: 주소로 들어와도 열리지만 저장은 서버가 403으로 막는다.
class VisaReviewEditPage extends StatefulWidget {
  const VisaReviewEditPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<VisaReviewEditPage> createState() => _VisaReviewEditPageState();
}

class _VisaReviewEditPageState extends State<VisaReviewEditPage> with VisaReviewOptionsLoader {
  VisaReview? _review;
  bool _loading = true;
  String? _loadError; // 후기를 못 불러온 경우(폼 숨김)

  // 상세 화면과 같은 이유로 id를 경로에 넣기 전에 인코딩한다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    _loadReview();
    loadReviewOptions();
  }

  // 들어오면 원래 후기를 불러와 폼에 채운다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted)
  void _loadReview() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/visa-reviews/$_safeId').then((res) {
      if (!mounted) return;
      setState(() {
        _review = VisaReview.fromJson(res.data!);
        _loadError = null;
        _loading = false;
      });
    }).catchError((Object e) {
      debugPrint('후기 불러오기 실패: $e');
      if (!mounted) return;
      setState(() {
        _review = null;
        // 400(이상한 id)도 404와 같은 문구. 401도 로그인 안내 없이 기본 문구
        _loadError = errorText(
          e,
          {400: ErrorMessages.reviewNotFound, 404: ErrorMessages.reviewNotFound},
          ErrorMessages.reviewLoadFailed,
        );
        _loading = false;
      });
    });
  }

  // 폼이 부르는 저장 함수. 성공하면 상세로 이동하고, 실패하면 throw해서 폼이 문구를 보여 준다
  Future<void> _save(Map<String, dynamic> body) async {
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    final safeId = _safeId;
    await dio.put<dynamic>('/visa-reviews/$safeId', data: body);
    // Router.neglect: 기록을 "새로 쌓지 않고 교체"해서(React의 replace) 저장한 수정 폼이 뒤로 가기에 남지 않게 한다
    if (mounted) Router.neglect(context, () => router.go('/visa-reviews/$safeId'));
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final review = _review;
    final isMine = user != null && review?.author?.id == user.id; // 내 후기인지 (403 문구 분기용)

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('비자 후기 수정'),
          const VisaDisclaimer(),
          if (_loading) const Loading(),
          if (_loadError != null) Notice(kind: NoticeKind.error, message: _loadError!),
          // 지금 주소의 후기일 때만 폼을 보여 준다. 숫자로 비교해서 /visa-reviews/01 처럼 서버가 같은 후기로 보는 주소도 통과한다
          if (review != null && review.id == int.tryParse(widget.id))
            VisaReviewForm(
              key: ValueKey(review.id),
              schoolName: review.school.name,
              options: reviewOptions,
              optionsFailed: optionsFailed,
              review: review,
              onSave: _save,
              errorTable: {
                400: ErrorMessages.reviewFieldsInvalid,
                // 본인 후기인데 403이면 보안 토큰 오류일 수 있다 (상세 화면 삭제와 같은 방식)
                403: isMine ? _forbiddenMineMessage : _forbiddenOthersMessage,
                404: ErrorMessages.reviewNotFound,
              },
              cancelTo: '/visa-reviews/$_safeId',
              cancelLabel: '취소',
            ),
          // 폼이 숨겨진 경우에도 돌아갈 길을 남긴다
          if (_loadError != null)
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                onPressed: () => context.go('/visa-reviews'),
                child: const Text('목록으로'),
              ),
            ),
        ],
      ),
    );
  }
}
