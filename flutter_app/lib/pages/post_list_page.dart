import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/select_field.dart';

// 글 목록 400(조건이 잘못됨) 문구. 다른 화면과 겹치지 않는 이 화면만의 글자라 여기에 둔다
const _badFilterMessage = '잘못된 조건입니다. 학교와 말머리를 다시 골라 주세요';
const _listFailedMessage = '목록을 불러오지 못했습니다';
const _optionsFailedMessage = '학교·말머리 목록을 불러오지 못했습니다';

// 글 목록 화면 "/" (React의 PostListPage).
// 필터의 "진짜 값"은 주소(?schoolId=1&category=CHAT)다. 라우터가 주소에서 읽어 schoolId·category로 넘겨 준다.
// 드롭다운을 바꾸면 주소만 바꾸고(context.go), 주소가 바뀌어 이 위젯이 새 값을 받으면 목록을 다시 불러온다.
class PostListPage extends StatefulWidget {
  const PostListPage({super.key, this.schoolId = '', this.category = '', this.notice});

  final String schoolId; // 주소의 ?schoolId (없으면 "")
  final String category; // 주소의 ?category (없으면 "")
  // 탈퇴 화면이 넘겨준 완료 문구 (없으면 null)
  final String? notice;

  @override
  State<PostListPage> createState() => _PostListPageState();
}

class _PostListPageState extends State<PostListPage> {
  // 목록 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해, 지금 조건의 결과인지 비교한다
  String? _resultKey;
  List<Post> _posts = const [];
  String? _error;

  // 드롭다운 목록. 아직 못 받았거나 실패하면 null로 두고 글 목록은 계속 보여 준다
  List<School>? _schools;
  List<Category>? _categories;
  bool _optionsFailed = false; // 학교·말머리 목록 중 하나라도 실패

  // 요청마다 번호를 매겨, 늦게 도착한 옛 응답(번호가 지금 것이 아님)은 버린다
  int _listSeq = 0;

  String get _queryKey => '${widget.schoolId}|${widget.category}';

  @override
  void initState() {
    super.initState();
    _loadOptions();
    _loadPosts();
  }

  @override
  void didUpdateWidget(PostListPage old) {
    super.didUpdateWidget(old);
    // 주소의 필터가 바뀌면 목록을 다시 불러온다
    if (old.schoolId != widget.schoolId || old.category != widget.category) _loadPosts();
  }

  @override
  void dispose() {
    _listSeq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  // 드롭다운 목록은 화면이 처음 나타날 때 한 번만 불러온다 (둘은 따로, 하나라도 실패하면 안내 문구)
  void _loadOptions() {
    final dio = context.read<ApiClient>().dio;
    dio.get<List<dynamic>>('/schools').then((res) {
      if (!mounted) return;
      setState(() => _schools = [
            for (final e in res.data!) School.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object _) {
      if (mounted) setState(() => _optionsFailed = true);
    });
    dio.get<List<dynamic>>('/categories').then((res) {
      if (!mounted) return;
      setState(() => _categories = [
            for (final e in res.data!) Category.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object _) {
      if (mounted) setState(() => _optionsFailed = true);
    });
  }

  void _loadPosts() {
    final seq = ++_listSeq;
    final key = _queryKey;
    // 빈 값은 보내지 않는다(조건 없음). 주소에서 온 값 그대로 보내서 서버가 400/404로 판단하게 한다
    final params = <String, String>{
      if (widget.schoolId.isNotEmpty) 'schoolId': widget.schoolId,
      if (widget.category.isNotEmpty) 'category': widget.category,
    };
    context.read<ApiClient>().dio.get<List<dynamic>>('/posts', queryParameters: params).then((res) {
      if (!mounted || seq != _listSeq) return; // 늦게 온 옛 응답은 무시
      setState(() {
        _resultKey = key;
        _posts = [for (final e in res.data!) Post.fromJson(e as Map<String, dynamic>)];
        _error = null;
      });
    }).catchError((Object e) {
      // 응답 해석(fromJson) 실패 같은 코드 문제도 여기로 오므로, 원인을 콘솔에 남겨 둔다
      debugPrint('글 목록 불러오기 실패: $e');
      if (!mounted || seq != _listSeq) return;
      setState(() {
        _resultKey = key;
        _posts = const [];
        // 401도 로그인 안내 없이 기본 문구 (목록은 누구나 보는 화면이라 로그인 개념이 없다)
        _error = errorText(
          e,
          {404: ErrorMessages.schoolNotFound, 400: _badFilterMessage},
          _listFailedMessage,
        );
      });
    });
  }

  // 드롭다운을 바꾸면 주소만 바꾼다. 그러면 위 didUpdateWidget이 알아서 다시 불러온다.
  // "전체"(빈 값)는 주소에서 뺀다. 기록이 남는 context.go라 뒤로 가기로 이전 필터에 돌아간다.
  void _changeFilter({String? schoolId, String? category}) {
    final school = schoolId ?? widget.schoolId;
    final cat = category ?? widget.category;
    final query = <String, String>{
      if (school.isNotEmpty) 'schoolId': school,
      if (cat.isNotEmpty) 'category': cat,
    };
    context.go(Uri(path: '/', queryParameters: query.isEmpty ? null : query).toString());
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final schoolId = widget.schoolId;
    final category = widget.category;
    final schools = _schools ?? const <School>[];
    final categories = _categories ?? const <Category>[];

    // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중이다
    final loading = _resultKey != _queryKey;
    final posts = loading ? const <Post>[] : _posts;
    final error = loading ? null : _error;

    // 주소의 값이 목록에 없으면 임시 항목을 끼워 넣어, 드롭다운이 주소를 그대로 보여 주게 한다
    // (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다).
    // 목록을 아직 받는 중이면 "알 수 없는"이 아니라 "불러오는 중..."으로 표시한다
    final schoolMissing = schoolId.isNotEmpty && !schools.any((s) => '${s.id}' == schoolId);
    final categoryMissing = category.isNotEmpty && !categories.any((c) => c.code == category);
    final schoolsPending = _schools == null && !_optionsFailed;
    final categoriesPending = _categories == null && !_optionsFailed;

    // 머리말: 고른 학교 이름, 학교를 안 골랐으면 "전체 게시판".
    // 학교 목록을 기다리는 중이면 높이만 차지하는 빈 머리말(깜빡임 방지), 목록 실패·없는 학교면 "학교 게시판"
    final schoolName = schools.where((s) => '${s.id}' == schoolId).map((s) => s.name).firstOrNull;
    final boardTitle = schoolId.isEmpty
        ? '전체 게시판'
        : schoolName ?? (schoolsPending ? ' ' : '학교 게시판');

    final notice = widget.notice;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Semantics(
              header: true,
              headingLevel: 1,
              child: Text(
                boardTitle,
                style: const TextStyle(
                  fontSize: AppSizes.fsBoard,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  height: 1.3,
                ),
              ),
            ),
          ),
          // 회원 탈퇴 화면이 넘긴 완료 문구 (없으면 안 보임)
          if (notice != null) Notice(kind: NoticeKind.info, message: notice),
          // 툴바: 왼쪽 필터 둘, 오른쪽 글쓰기. 좁은 화면에선 줄바꿈된다
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                // React의 .filters. 툴바 안에서는 아래 여백이 0이다(.toolbar .filters { margin-bottom: 0 }).
                // 여백을 두면 버튼과 드롭다운의 세로 위치가 어긋난다
                Wrap(
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSizes.space2,
                  runSpacing: AppSizes.space2,
                  children: [
                    SelectField(
                      fieldKey: const ValueKey('filter-school'),
                      label: '학교',
                      width: 170,
                      value: schoolId,
                      options: [
                        ('', '전체 학교'),
                        if (schoolMissing)
                          (schoolId, schoolsPending ? loadingText : '알 수 없는 학교 ($schoolId)'),
                        for (final s in schools) ('${s.id}', s.name),
                      ],
                      onChanged: (v) => _changeFilter(schoolId: v),
                    ),
                    SelectField(
                      fieldKey: const ValueKey('filter-category'),
                      label: '말머리',
                      width: 150,
                      value: category,
                      options: [
                        ('', '전체 말머리'),
                        if (categoryMissing)
                          (
                            category,
                            categoriesPending ? loadingText : '알 수 없는 말머리 ($category)',
                          ),
                        for (final c in categories) (c.code, c.name),
                      ],
                      onChanged: (v) => _changeFilter(category: v),
                    ),
                  ],
                ),
                // 글쓰기 버튼은 항상 보이되, 비로그인이면 로그인 화면으로 보낸다
                FilledButton(
                  onPressed: () => context.go(user != null ? '/posts/new' : '/login'),
                  child: const Text('글쓰기'),
                ),
              ],
            ),
          ),
          if (_optionsFailed) const Notice(kind: NoticeKind.error, message: _optionsFailedMessage),
          // 글 목록: 판 하나 안의 행들. 로딩·에러·빈 목록도 같은 판 안에 보여 준다
          Container(
            margin: const EdgeInsets.only(bottom: AppSizes.space4),
            clipBehavior: Clip.hardEdge,
            decoration: BoxDecoration(
              color: AppColors.paper,
              border: Border.all(color: AppColors.line),
              borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (loading) const Loading(),
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.all(AppSizes.space4),
                    child: Notice(kind: NoticeKind.error, message: error),
                  ),
                if (!loading && error == null && posts.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(AppSizes.space4),
                    child: Text('아직 글이 없습니다', style: TextStyle(color: AppColors.muted)),
                  ),
                for (var i = 0; i < posts.length; i++)
                  _PostRow(
                    post: posts[i],
                    first: i == 0,
                    showSchool: schoolId.isEmpty, // 학교로 거른 화면에서는 모두 같은 학교라 뱃지를 생략한다
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// 글 한 줄: 말머리 뱃지, (학교 뱃지), 제목 링크 / 작성자·날짜·댓글·공감
class _PostRow extends StatelessWidget {
  const _PostRow({required this.post, required this.first, required this.showSchool});

  final Post post;
  final bool first; // 첫 줄은 위 선이 없다 (판 테두리와 겹치지 않게)
  final bool showSchool;

  @override
  Widget build(BuildContext context) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
      decoration: BoxDecoration(
        border: first ? null : const Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              _CategoryBadge(code: post.category.code, name: post.category.name),
              if (showSchool) AppBadge(text: post.school?.name ?? '학교 미지정', school: true),
              // 제목만 링크 (행 전체가 아니다)
              _RowLink(title: post.title, onTap: () => context.go('/posts/${post.id}')),
            ],
          ),
          // 작성자·날짜·댓글 수·공감 수: 가운뎃점 대신 간격으로 나눈다
          Wrap(
            spacing: AppSizes.space3,
            children: [
              Text(post.author?.nickname ?? '(작성자 없음)', style: metaStyle),
              Text(formatDate(post.createdAt), style: metaStyle),
              Text('댓글 ${post.commentCount}', style: metaStyle),
              Text('공감 ${post.likeCount}', style: metaStyle),
            ],
          ),
        ],
      ),
    );
  }
}

// 말머리 뱃지: code마다 색이 다르다 (CHAT 회색, QUESTION 파랑, CAMPUS_LIFE 청자).
// 모르는 code는 React의 .badge 기본처럼 색 없는 뱃지(ink 글자, 배경 없음)
class _CategoryBadge extends StatelessWidget {
  const _CategoryBadge({required this.code, required this.name});

  final String code;
  final String name;

  @override
  Widget build(BuildContext context) {
    final (ink, bg) = switch (code) {
      'CHAT' => (AppColors.muted, AppColors.badgeChatBg),
      'QUESTION' => (AppColors.badgeQuestionInk, AppColors.badgeQuestionBg),
      'CAMPUS_LIFE' => (AppColors.celadonInk, AppColors.celadonSoft),
      _ => (AppColors.ink, Colors.transparent),
    };
    return AppBadge(text: name, ink: ink, bg: bg);
  }
}

// 제목 링크: 평소엔 밑줄 없이 ink 색, 마우스를 올리면 밑줄 (React의 .row-link)
class _RowLink extends StatefulWidget {
  const _RowLink({required this.title, required this.onTap});

  final String title;
  final VoidCallback onTap;

  @override
  State<_RowLink> createState() => _RowLinkState();
}

class _RowLinkState extends State<_RowLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      link: true,
      child: InkWell(
        onTap: widget.onTap,
        onHover: (h) => setState(() => _hover = h),
        child: Text(
          widget.title,
          style: TextStyle(
            fontSize: AppSizes.fsRow,
            fontWeight: FontWeight.w500,
            color: AppColors.ink,
            height: 1.65,
            decoration: _hover ? TextDecoration.underline : TextDecoration.none,
            decorationColor: AppColors.ink,
          ),
        ),
      ),
    );
  }
}
