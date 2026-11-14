import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/can_act_in_school.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/like_button.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/report_form.dart';
import '../widgets/school_gate_notice.dart';
import '../widgets/small_button_style.dart';

// 글 상세 화면 "/posts/:id" (React의 PostDetailPage).
// 댓글 영역은 다음 단계(S16-f2)에서 이 화면 아래(목록으로 버튼 위)에 붙인다.
class PostDetailPage extends StatefulWidget {
  const PostDetailPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<PostDetailPage> createState() => _PostDetailPageState();
}

class _PostDetailPageState extends State<PostDetailPage> {
  Post? _post;
  bool _loading = true;
  String? _error; // 글을 못 불러온 경우
  // 삭제 요청 중인지, 삭제 실패 문구 (불러오기 에러와 따로 둬서 글은 계속 보인다)
  bool _deleting = false;
  String? _deleteError;
  // 삭제 중 401(재발급까지 실패): "삭제하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  bool _needLogin = false;

  // id를 경로에 넣기 전에 인코딩한다: '?' 같은 글자가 그대로 섞이면 /posts/? (목록 API)로 바뀌어 버린다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    _loadPost();
  }

  // 들어오면 글을 불러온다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted).
  // (id가 바뀌면 라우터가 key로 화면을 새로 만들기 때문에 id 바뀜은 따로 다루지 않는다)
  void _loadPost() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/posts/$_safeId').then((res) {
      if (!mounted) return;
      setState(() {
        _post = Post.fromJson(res.data!);
        _error = null;
        _loading = false;
      });
    }).catchError((Object e) {
      if (!mounted) return;
      setState(() {
        _post = null;
        // 401도 로그인 안내 없이 기본 문구 (불러오기 실패에는 로그인 안내가 따로 없다)
        _error = errorText(
          e,
          {404: ErrorMessages.postNotFound, 400: ErrorMessages.postNotFound},
          ErrorMessages.postLoadFailed,
        );
        _loading = false;
      });
    });
  }

  Future<void> _delete({required bool isMine}) async {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    final ok = await showConfirm(context, message: '이 글을 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    setState(() {
      _deleting = true;
      _deleteError = null;
      _needLogin = false;
    });
    try {
      await dio.delete<dynamic>('/posts/$_safeId');
      // Router.neglect: 기록을 "새로 쌓지 않고 교체"해서 삭제된 글의 상세가 뒤로 가기 기록에 남지 않게 한다
      if (mounted) Router.neglect(context, () => router.go('/'));
    } catch (err) {
      // 403: 남의 글이거나, 본인 글인데 보안 토큰 오류. 본인이면 새로고침을 안내한다
      final info = describeError(
        err,
        {
          403: isMine ? ErrorMessages.deleteRetryAfterReload : ErrorMessages.deleteForbidden,
          404: ErrorMessages.alreadyDeleted('글'),
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
        _deleting = false; // 실패했을 때만 버튼을 되살린다 (성공하면 화면이 바뀐다)
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final post = _post;
    final isMine = user != null && post?.author?.id == user.id; // 내 글인지
    // 댓글·공감·신고는 인증한 학교의 글에서만. 비로그인은 로그인 링크가 있는 기존 화면이라 여기서 막지 않는다
    final canAct = canActInSchool(user, post?.school?.id);

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_loading) const Loading(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          if (post != null) _buildArticle(post),
          // 작업 줄: 공감 / 수정·삭제 / 신고
          if (post != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.start,
                spacing: AppSizes.space2,
                runSpacing: AppSizes.space2,
                children: [
                  LikeButton(postId: widget.id, initialCount: post.likeCount, canLike: user == null || canAct),
                  // 수정은 내 글만, 삭제는 내 글 또는 관리자. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다
                  if (user != null && isMine)
                    OutlinedButton(
                      style: smallButtonStyle,
                      onPressed: () => context.go('/posts/$_safeId/edit'),
                      child: const Text('수정'),
                    ),
                  if (user != null && (isMine || user.isAdmin))
                    OutlinedButton(
                      style: dangerSmallButtonStyle,
                      onPressed: _deleting ? null : () => _delete(isMine: isMine),
                      child: Text(_deleting ? '삭제 중...' : '삭제'),
                    ),
                  // 신고는 로그인했고 내 글이 아니고 인증한 학교의 글일 때만
                  if (user != null && !isMine && canAct)
                    ReportForm(
                      url: '/posts/$_safeId/reports',
                      label: '글',
                      onNeedLogin: () => setState(() {
                        _deleteError = null;
                        _needLogin = true; // 로그인 안내는 user 조건 밖에서 보여 준다
                      }),
                    ),
                ],
              ),
            ),
          // 로그인했지만 공감·신고를 막은 이유 (이미 누른 공감의 취소는 가능)
          if (post != null && user != null && !canAct) SchoolGateNotice(user: user, noSchool: post.school == null),
          // 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게)
          if (_deleteError != null) Notice(kind: NoticeKind.error, message: _deleteError!),
          if (_needLogin) const ReloginNotice(),
          // TODO(S16-f2): 여기에 댓글 영역이 들어온다
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

  // 글 본문 판. 드래그로 글자를 고를 수 있게 SelectionArea로 감싼다(React 화면은 원래 선택된다)
  Widget _buildArticle(Post post) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    return Panel(
      child: SelectionArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 말머리와 학교 (학교 없는 글은 "학교 미지정")
            Wrap(
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                CategoryBadge(code: post.category.code, name: post.category.name),
                AppBadge(text: post.school?.name ?? '학교 미지정', school: true),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space2, bottom: AppSizes.space2),
              child: Semantics(
                header: true,
                headingLevel: 1,
                child: Text(
                  post.title,
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
                Text(post.author?.nickname ?? '(작성자 없음)', style: metaStyle),
                Text(formatDate(post.createdAt), style: metaStyle),
              ],
            ),
            // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space4),
              child: Text(post.content, style: const TextStyle(height: 1.65)),
            ),
          ],
        ),
      ),
    );
  }
}
