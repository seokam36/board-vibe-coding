import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../visa/models.dart';
import '../visa/result_badge.dart';
import '../visa/visa_disclaimer.dart';
import '../widgets/app_badge.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/small_button_style.dart';

// 비자 후기 상세 "/visa-reviews/:id" (React의 VisaReviewDetailPage).
// 수정(본인)·삭제(본인 또는 관리자) 버튼이 있다. 글 상세(PostDetailPage)와 같은 흐름.
class VisaReviewDetailPage extends StatefulWidget {
  const VisaReviewDetailPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<VisaReviewDetailPage> createState() => _VisaReviewDetailPageState();
}

class _VisaReviewDetailPageState extends State<VisaReviewDetailPage> {
  VisaReview? _review;
  bool _loading = true;
  String? _error; // 후기를 못 불러온 경우
  bool _deleting = false;
  String? _deleteError; // 삭제 실패 문구 (후기는 계속 보인다)
  bool _needLogin = false; // 삭제 중 401: 로그인 안내

  // '?' 같은 글자가 경로에 섞여 목록 API로 바뀌지 않게 인코딩한다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    _loadReview();
  }

  // (id가 바뀌면 라우터가 key로 화면을 새로 만들기 때문에 id 바뀜은 따로 다루지 않는다)
  void _loadReview() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/visa-reviews/$_safeId').then((res) {
      if (!mounted) return;
      setState(() {
        _review = VisaReview.fromJson(res.data!);
        _error = null;
        _loading = false;
      });
    }).catchError((Object e) {
      debugPrint('후기 불러오기 실패: $e');
      if (!mounted) return;
      setState(() {
        _review = null;
        // 400(이상한 id)도 404와 같은 문구. 401도 로그인 안내 없이 기본 문구
        _error = errorText(
          e,
          {400: ErrorMessages.reviewNotFound, 404: ErrorMessages.reviewNotFound},
          ErrorMessages.reviewLoadFailed,
        );
        _loading = false;
      });
    });
  }

  Future<void> _delete({required bool isMine}) async {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    final ok = await showConfirm(context, message: '이 후기를 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    setState(() {
      _deleting = true;
      _deleteError = null;
      _needLogin = false;
    });
    try {
      await dio.delete<dynamic>('/visa-reviews/$_safeId');
      // Router.neglect: 기록을 교체해서 삭제된 후기의 상세가 뒤로 가기에 남지 않게 한다
      if (mounted) Router.neglect(context, () => router.go('/visa-reviews'));
    } catch (err) {
      // 403: 남의 후기이거나, 본인 후기인데 보안 토큰 오류. 본인이면 새로고침을 안내한다
      final info = describeError(
        err,
        {
          403: isMine ? ErrorMessages.deleteRetryAfterReload : ErrorMessages.deleteForbidden,
          404: ErrorMessages.alreadyDeleted('후기'),
        },
        ErrorMessages.deleteFailed,
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _deleteError = info.message;
        }
        _deleting = false; // 실패했을 때만 버튼을 되살린다
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final review = _review;
    final isMine = user != null && review?.author?.id == user.id;

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const VisaDisclaimer(),
          if (_loading) const Loading(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (review != null) _buildArticle(review),
          // 수정은 내 후기만, 삭제는 내 후기 또는 관리자. 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다
          if (review != null && user != null && (isMine || user.isAdmin))
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                runSpacing: AppSizes.space2,
                children: [
                  if (isMine)
                    OutlinedButton(
                      style: smallButtonStyle,
                      onPressed: () => context.go('/visa-reviews/$_safeId/edit'),
                      child: const Text('수정'),
                    ),
                  OutlinedButton(
                    style: dangerSmallButtonStyle,
                    onPressed: _deleting ? null : () => _delete(isMine: isMine),
                    child: Text(_deleting ? '삭제 중...' : '삭제'),
                  ),
                ],
              ),
            ),
          // 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게)
          if (_deleteError != null) Notice(kind: NoticeKind.error, message: _deleteError!),
          if (_needLogin) const ReloginNotice(),
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

  // 후기 본문 판. 드래그로 글자를 고를 수 있게 SelectionArea로 감싼다
  Widget _buildArticle(VisaReview r) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    return Panel(
      child: SelectionArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 결과·학교만 (비자·신청 종류는 아래 제목에 있다)
            Wrap(
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                ResultBadge(result: r.result),
                AppBadge(text: r.school.name, school: true),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space2, bottom: AppSizes.space2),
              child: Semantics(
                header: true,
                headingLevel: 1,
                child: Text(
                  '${r.visaType.name} · ${r.applicationType.name}',
                  style: const TextStyle(
                    fontSize: AppSizes.fsTitle,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink,
                    height: 1.35,
                  ),
                ),
              ),
            ),
            Wrap(
              spacing: AppSizes.space3,
              children: [
                Text(r.author?.nickname ?? '(작성자 없음)', style: metaStyle),
                Text(formatDate(r.createdAt), style: metaStyle),
              ],
            ),
            // 접수일 → 결과일 (처리 일수)
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space3),
              child: Text(
                '접수 ${formatDay(r.receivedDate)} → 결과 ${formatDay(r.resultDate)} (${r.processingDays}일)',
                style: const TextStyle(fontWeight: FontWeight.w500),
              ),
            ),
            // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space4),
              child: r.content.isNotEmpty
                  ? Text(r.content, style: const TextStyle(height: 1.65))
                  : const Text('남긴 메모가 없어요', style: TextStyle(height: 1.65, color: AppColors.muted)),
            ),
          ],
        ),
      ),
    );
  }
}
