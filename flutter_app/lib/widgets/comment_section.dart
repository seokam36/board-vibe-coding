import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import 'app_link.dart';
import 'confirm_dialog.dart';
import 'loading.dart';
import 'notice.dart';
import 'relogin_notice.dart';
import 'report_form.dart';
import 'small_button_style.dart';

const _contentError = '내용을 입력해 주세요 (최대 1000자)';

// 글 상세 아래의 댓글 영역: 목록 + 작성 + (내 댓글) 수정·삭제 + 신고 (React의 CommentSection).
// postId: 주소의 :id (풀린 글자). 요청에 넣을 때 다시 인코딩한다
// canComment: 댓글 작성·신고를 허용할지 (로그인했는데 미인증이거나 다른 학교 글이면 false)
class CommentSection extends StatefulWidget {
  const CommentSection({super.key, required this.postId, required this.canComment});

  final String postId;
  final bool canComment;

  @override
  State<CommentSection> createState() => _CommentSectionState();
}

class _CommentSectionState extends State<CommentSection> {
  // null이면 아직 못 불러온 상태
  List<Comment>? _comments;
  String? _loadError;

  final _newText = TextEditingController(); // 새 댓글 입력칸
  final _editText = TextEditingController(); // 수정 중인 댓글 입력칸 (한 번에 하나만 수정)
  bool _posting = false;
  int? _editingId; // 수정 중인 댓글 id
  // 저장·삭제 요청 중에는 모든 행의 수정·삭제 버튼을 막는다 (연타 방지, 행마다 따로 두지 않는다)
  bool _busy = false;
  // 쓰기 실패 안내. 불러오기 에러와 따로 둬서 목록은 계속 보인다
  String? _actionError;
  // 쓰기 중 401(재발급까지 실패): 로그인 안내를 보여 준다
  bool _needLogin = false;

  String get _base => '/posts/${Uri.encodeComponent(widget.postId)}/comments';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _newText.dispose();
    _editText.dispose();
    super.dispose();
  }

  // 목록을 불러온다 ("다시 시도"도 이걸 다시 부른다). 사라진 화면에 도착한 응답은 버린다
  void _load() {
    setState(() => _loadError = null);
    context.read<ApiClient>().dio.get<List<dynamic>>(_base).then((res) {
      if (!mounted) return;
      setState(() {
        _comments = [for (final e in res.data!) Comment.fromJson(e as Map<String, dynamic>)];
        _loadError = null;
      });
    }).catchError((Object e) {
      if (!mounted) return;
      setState(() => _loadError = errorText(e, {}, '댓글을 불러오지 못했습니다'));
    });
  }

  void _resetNotices() {
    _actionError = null;
    _needLogin = false;
  }

  // 쓰기 실패 공통 안내: 로그인이 풀렸으면 로그인 안내, 아니면 문구. (setState 안에서 부른다)
  void _showError(ErrorInfo info) {
    if (info is NeedLogin) {
      _needLogin = true;
    } else if (info is ErrorMessage) {
      _actionError = info.message;
    }
  }

  void _removeComment(int id) {
    _comments = (_comments ?? []).where((c) => c.id != id).toList();
  }

  Future<void> _create() async {
    if (_posting) return;
    final dio = context.read<ApiClient>().dio;
    setState(() {
      _resetNotices();
      _posting = true;
    });
    try {
      // 빈 내용 검사는 앱에서 하지 않고 서버의 400을 그대로 보여 준다 (글쓰기와 같은 방식)
      final res = await dio.post<Map<String, dynamic>>(_base, data: {'content': _newText.text});
      if (!mounted) return;
      setState(() {
        // 오래된 순이라 맨 뒤에 붙이고, 입력칸을 비운다
        _comments = [...?_comments, Comment.fromJson(res.data!)];
        _newText.clear();
      });
    } catch (err) {
      final info = describeError(
        err,
        {400: _contentError, 403: ErrorMessages.schoolGatePostAction, 404: ErrorMessages.postNotFound},
        '댓글을 등록하지 못했습니다',
      );
      if (!mounted) return;
      setState(() => _showError(info));
    } finally {
      if (mounted) setState(() => _posting = false);
    }
  }

  void _startEdit(Comment c) {
    setState(() {
      _resetNotices();
      _editingId = c.id;
      _editText.text = c.content;
    });
  }

  Future<void> _save(Comment c) async {
    final dio = context.read<ApiClient>().dio;
    setState(() {
      _resetNotices();
      _busy = true;
    });
    try {
      final res = await dio.put<Map<String, dynamic>>('/comments/${c.id}', data: {'content': _editText.text});
      if (!mounted) return;
      final saved = Comment.fromJson(res.data!);
      setState(() {
        _comments = [for (final x in _comments ?? <Comment>[]) x.id == c.id ? saved : x];
        _editingId = null;
      });
    } catch (err) {
      final info = describeError(
        err,
        {400: _contentError, 403: '본인 댓글만 수정할 수 있습니다', 404: ErrorMessages.alreadyDeleted('댓글')},
        '수정하지 못했습니다',
      );
      if (!mounted) return;
      setState(() {
        _showError(info);
        // 이미 지워진 댓글이면 목록에서도 빼고 편집을 끝낸다
        if (info is ErrorMessage && info.status == 404) {
          _removeComment(c.id);
          _editingId = null;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete(Comment c, {required bool isMine}) async {
    // 되돌릴 수 없는 동작이라 먼저 확인한다
    final ok = await showConfirm(context, message: '이 댓글을 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    final dio = context.read<ApiClient>().dio;
    setState(() {
      _resetNotices();
      _busy = true;
    });
    try {
      await dio.delete<dynamic>('/comments/${c.id}');
      if (!mounted) return;
      setState(() => _removeComment(c.id));
    } catch (err) {
      // 403: 남의 댓글이면 권한 없음, 본인 댓글이면 보안 토큰 오류 가능성이라 새로고침을 안내한다
      final info = describeError(
        err,
        {
          403: isMine ? ErrorMessages.deleteRetryAfterReload : ErrorMessages.deleteForbidden,
          404: ErrorMessages.alreadyDeleted('댓글'),
        },
        ErrorMessages.deleteFailed,
      );
      if (!mounted) return;
      setState(() {
        _showError(info);
        if (info is ErrorMessage && info.status == 404) _removeComment(c.id);
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final comments = _comments;
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space5, bottom: AppSizes.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Semantics(
              header: true,
              headingLevel: 2,
              child: Text(
                '댓글${comments == null ? '' : ' ${comments.length}'}',
                style: const TextStyle(
                  fontSize: AppSizes.fsRow,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                ),
              ),
            ),
          ),
          if (_loadError != null)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  Text(_loadError!),
                  OutlinedButton(style: smallButtonStyle, onPressed: _load, child: const Text('다시 시도')),
                ],
              ),
            ),
          // 아직 응답 전이면 비어 있는 게 아니라 불러오는 중이라고 알린다
          if (comments == null && _loadError == null) const Loading(prefix: '댓글'),
          if (comments != null) _buildList(comments, user?.id, user?.isAdmin ?? false),
          // 실패 안내는 목록·입력칸 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게)
          if (_actionError != null) Notice(kind: NoticeKind.error, message: _actionError!),
          if (_needLogin) const ReloginNotice(),
          if (user != null && !widget.canComment)
            // 미인증·다른 학교 글: 작성 폼 대신 짧은 안내 (자세한 안내는 글 위쪽에 있다)
            const Padding(
              padding: EdgeInsets.only(top: AppSizes.space1),
              child: Text('댓글은 인증한 학교의 글에만 쓸 수 있어요'),
            )
          else if (user != null)
            _buildForm()
          else
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                const Text('로그인하면 댓글을 쓸 수 있습니다 '),
                AppLink(label: '로그인', onTap: () => context.go('/login')),
              ],
            ),
        ],
      ),
    );
  }

  // 글 목록과 같은 판 스타일: 판 하나 안의 행들 (행 사이는 선)
  Widget _buildList(List<Comment> comments, int? userId, bool isAdmin) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: AppSizes.space4),
      decoration: BoxDecoration(
        color: AppColors.paper,
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (comments.isEmpty)
            const Padding(
              padding: EdgeInsets.all(AppSizes.space4),
              child: Text('아직 댓글이 없습니다', style: TextStyle(color: AppColors.muted)),
            ),
          for (var i = 0; i < comments.length; i++)
            Container(
              key: ValueKey('comment-${comments[i].id}'),
              padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
              decoration: BoxDecoration(
                border: i == 0 ? null : const Border(top: BorderSide(color: AppColors.line)),
              ),
              child: _buildRow(comments[i], userId, isAdmin),
            ),
        ],
      ),
    );
  }

  Widget _buildRow(Comment c, int? userId, bool isAdmin) {
    final isMine = userId != null && c.author?.id == userId;
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    final editing = _editingId == c.id;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 작성자·날짜·내용: 드래그로 고를 수 있게 SelectionArea로 감싼다 (수정 칸·버튼은 밖)
        SelectionArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Wrap(
                spacing: AppSizes.space3,
                children: [
                  Text(c.author?.nickname ?? '(작성자 없음)', style: metaStyle),
                  Text(formatDate(c.createdAt), style: metaStyle),
                ],
              ),
              // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
              if (!editing)
                Padding(
                  padding: const EdgeInsets.only(top: AppSizes.space2),
                  child: Text(c.content, style: const TextStyle(height: 1.65)),
                ),
            ],
          ),
        ),
        if (editing) _buildEditBox(c) else _buildActions(c, isMine, isAdmin, userId != null),
      ],
    );
  }

  // 인라인 편집: 내용이 입력칸으로 바뀌고 저장/취소가 나온다
  Widget _buildEditBox(Comment c) {
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            label: '댓글 수정',
            child: TextField(
              key: const ValueKey('comment-edit'),
              controller: _editText,
              minLines: 3,
              maxLines: 3,
              keyboardType: TextInputType.multiline,
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space2),
            child: Wrap(
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                FilledButton(
                  style: smallFilledButtonStyle,
                  onPressed: _busy ? null : () => _save(c),
                  child: Text(_busy ? '저장 중...' : '저장'),
                ),
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: _busy ? null : () => setState(() => _editingId = null),
                  child: const Text('취소'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActions(Comment c, bool isMine, bool isAdmin, bool loggedIn) {
    final report = loggedIn && widget.canComment && !isMine; // 내 댓글이 아니고 인증한 학교의 글일 때만
    if (!(isMine || isAdmin || report)) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space2),
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.start,
        spacing: AppSizes.space2,
        runSpacing: AppSizes.space2,
        children: [
          // 수정은 내 댓글만, 삭제는 내 댓글 또는 관리자. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다
          if (isMine)
            OutlinedButton(
              style: smallButtonStyle,
              onPressed: _busy ? null : () => _startEdit(c),
              child: const Text('수정'),
            ),
          if (isMine || isAdmin)
            OutlinedButton(
              style: dangerSmallButtonStyle,
              onPressed: _busy ? null : () => _delete(c, isMine: isMine),
              child: const Text('삭제'),
            ),
          if (report)
            ReportForm(
              url: '/comments/${c.id}/reports',
              label: '댓글',
              onNeedLogin: () => setState(() {
                _resetNotices();
                _needLogin = true; // 로그인 안내는 목록 밖에서 보여 준다
              }),
            ),
        ],
      ),
    );
  }

  Widget _buildForm() {
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            label: '댓글 내용',
            child: TextField(
              key: const ValueKey('comment-new'),
              controller: _newText,
              decoration: const InputDecoration(
                hintText: '댓글을 입력하세요',
                hintStyle: TextStyle(color: AppColors.muted),
              ),
              minLines: 3,
              maxLines: 3,
              keyboardType: TextInputType.multiline,
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space2),
            child: FilledButton(
              onPressed: _posting ? null : _create,
              child: Text(_posting ? '등록 중...' : '등록'),
            ),
          ),
        ],
      ),
    );
  }
}
