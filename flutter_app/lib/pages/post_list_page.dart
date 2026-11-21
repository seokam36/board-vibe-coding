import 'package:flutter/semantics.dart' show OrdinalSortKey;
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
import '../widgets/initial_avatar.dart';
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
// 학교 드롭다운·말머리 칩을 바꾸면 주소만 바꾸고(context.go), 주소가 바뀌어 이 위젯이 새 값을 받으면 목록을 다시 불러온다.
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

  // 학교·말머리 목록. 아직 못 받았거나 실패하면 null로 두고 글 목록은 계속 보여 준다
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

  // 학교·말머리 목록은 화면이 처음 나타날 때 한 번만 불러온다 (둘은 따로, 하나라도 실패하면 안내 문구)
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

  // 학교 드롭다운·말머리 칩을 바꾸면 주소만 바꾼다. 그러면 위 didUpdateWidget이 알아서 다시 불러온다.
  // "전체"(빈 값)는 주소에서 뺀다. 기록이 남는 context.go라 뒤로 가기로 이전 필터에 돌아간다.
  void _changeFilter({String? schoolId, String? category}) {
    final school = schoolId ?? widget.schoolId;
    final cat = category ?? widget.category;
    final query = <String, String>{
      if (school.isNotEmpty) 'schoolId': school,
      if (cat.isNotEmpty) 'category': cat,
    };
    // 이미 고른 값을 다시 누르면 아무것도 안 한다 (같은 주소로 go 하면 탈퇴 안내 같은 extra가 사라진다)
    if (school == widget.schoolId && cat == widget.category) return;
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

    // 주소의 값이 목록에 없으면 임시 항목을 끼워 넣어, 드롭다운·칩이 주소를 그대로 보여 주게 한다
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
    // 말머리 칩 줄: "전체" + 서버가 준 말머리. 주소의 값이 목록에 없으면 고른 상태의 임시 칩을 끼운다
    final chips = <Widget>[
      _CategoryChip(
        chipKey: const ValueKey('category-chip-all'),
        text: '전체',
        selected: category.isEmpty,
        onTap: () => _changeFilter(category: ''),
      ),
      if (categoryMissing)
        _CategoryChip(
          chipKey: ValueKey('category-chip-$category'),
          text: categoriesPending ? loadingText : '알 수 없는 말머리 ($category)',
          selected: true,
          onTap: () {},
        ),
      for (final c in categories)
        _CategoryChip(
          chipKey: ValueKey('category-chip-${c.code}'),
          text: c.name,
          selected: c.code == category,
          onTap: () => _changeFilter(category: c.code),
        ),
    ];

    // 글쓰기 연필 버튼은 스크롤과 상관없이 화면(탭 막대 바로 위) 오른쪽 아래에 떠 있어야 해서,
    // 스크롤되는 본문(PageFrame) 위에 Stack으로 겹친다 (페이지 안에는 Scaffold를 만들 수 없다)
    return Stack(
      children: [
        Semantics(
          container: true,
          sortKey: const OrdinalSortKey(1), // 연필 버튼(0) 다음에 본문
          child: PageFrame(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 제목 줄: 왼쪽 머리말, 오른쪽 학교 드롭다운. 좁으면 드롭다운이 아래 줄로 내려간다
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSizes.space3),
                  child: Wrap(
                    alignment: WrapAlignment.spaceBetween,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: AppSizes.space2,
                    runSpacing: AppSizes.space2,
                    children: [
                      Semantics(
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
                      SelectField(
                        fieldKey: const ValueKey('filter-school'),
                        label: '학교',
                        width: 170,
                        value: schoolId,
                        options: [
                          ('', '전체 학교'),
                          if (schoolMissing)
                            (
                              schoolId,
                              schoolsPending
                                  ? loadingText
                                  : '알 수 없는 학교 ($schoolId)',
                            ),
                          for (final s in schools) ('${s.id}', s.name),
                        ],
                        onChanged: (v) => _changeFilter(schoolId: v),
                      ),
                    ],
                  ),
                ),
                // 회원 탈퇴 화면이 넘긴 완료 문구 (없으면 안 보임)
                if (notice != null)
                  Notice(kind: NoticeKind.info, message: notice),
                // 말머리 칩 줄: 가로로 밀어 볼 수 있다 (칩이 많아도 줄바꿈·넘침 없음)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSizes.space3),
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children: [
                        for (var i = 0; i < chips.length; i++) ...[
                          if (i > 0) const SizedBox(width: AppSizes.space2),
                          chips[i],
                        ],
                      ],
                    ),
                  ),
                ),
                if (_optionsFailed)
                  const Notice(
                    kind: NoticeKind.error,
                    message: _optionsFailedMessage,
                  ),
                // 글 목록: 판 하나 안의 행들. 로딩·에러·빈 목록도 같은 판 안에 보여 준다.
                // 줄을 눌렀을 때 hover 색이 보이도록 Container가 아니라 Material을 판으로 쓴다
                // (불투명한 Container가 위에 있으면 눌림 표시가 가려진다).
                // 아래 여백은 연필 버튼에 마지막 줄이 가리지 않게 넉넉히 둔다
                Padding(
                  padding: const EdgeInsets.only(bottom: _fabSpace),
                  child: Material(
                    color: AppColors.paper,
                    clipBehavior: Clip.hardEdge,
                    shape: RoundedRectangleBorder(
                      side: const BorderSide(color: AppColors.line),
                      borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        if (loading) const Loading(),
                        if (error != null)
                          Padding(
                            padding: const EdgeInsets.all(AppSizes.space4),
                            child: Notice(
                              kind: NoticeKind.error,
                              message: error,
                            ),
                          ),
                        if (!loading && error == null && posts.isEmpty)
                          const Padding(
                            padding: EdgeInsets.all(AppSizes.space4),
                            child: Text(
                              '아직 글이 없습니다',
                              style: TextStyle(color: AppColors.muted),
                            ),
                          ),
                        for (var i = 0; i < posts.length; i++)
                          _PostRow(
                            post: posts[i],
                            first: i == 0,
                            showSchool: schoolId
                                .isEmpty, // 학교로 거른 화면에서는 모두 같은 학교라 뱃지를 생략한다
                          ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        // 글쓰기 연필 버튼: 본문 기둥(720) 오른쪽 끝에 맞춰 오른쪽 아래에 띄운다.
        // 비로그인이면 로그인 화면으로 보낸다
        Positioned.fill(
          child: Align(
            alignment: Alignment.bottomCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                maxWidth: AppSizes.pageMaxWidth,
              ),
              child: Align(
                alignment: Alignment.bottomRight,
                child: Padding(
                  padding: const EdgeInsets.all(AppSizes.space4),
                  // 스크린리더·키보드 순서: 연필 버튼을 목록(1)보다 먼저(0) 읽게 한다
                  child: Semantics(
                    container: true,
                    sortKey: const OrdinalSortKey(0),
                    child: FloatingActionButton(
                      heroTag: null,
                      shape: const CircleBorder(), // 기본은 둥근 네모라 동그라미로 지정
                      tooltip: '글쓰기',
                      elevation: 0,
                      focusElevation: 0,
                      hoverElevation: 0,
                      highlightElevation: 0,
                      backgroundColor:
                          AppColors.highlight, // 노랑은 바탕으로만, 위의 아이콘은 ink
                      foregroundColor: AppColors.ink,
                      onPressed: () =>
                          context.go(user != null ? '/posts/new' : '/login'),
                      child: const Icon(Icons.edit_outlined),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

// 목록 아래에 비워 두는 자리: 연필 버튼(56) + 버튼 바깥 여백(16) + 여유(16)
const _fabSpace = 88.0;

// 말머리 칩 한 개. 고른 칩은 ink 바탕 + 흰 글자 + 체크, 안 고른 칩은 흰 바탕 + line 테두리 + ink 글자.
// 색만으로 구분하지 않도록 고른 칩에는 체크 아이콘이 붙고, 스크린리더에는 selected로 알린다.
class _CategoryChip extends StatelessWidget {
  const _CategoryChip({
    required this.chipKey,
    required this.text,
    required this.selected,
    required this.onTap,
  });

  final Key chipKey;
  final String text;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final fg = selected ? AppColors.paper : AppColors.ink;
    return Semantics(
      button: true,
      selected: selected,
      label: text,
      excludeSemantics: true, // 안쪽 글자·체크는 위 라벨로 대신 읽는다
      onTap: onTap,
      child: Material(
        color: selected ? AppColors.ink : AppColors.paper,
        shape: StadiumBorder(
          side: BorderSide(color: selected ? AppColors.ink : AppColors.line),
        ),
        child: InkWell(
          key: chipKey,
          customBorder: const StadiumBorder(),
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 40), // 손가락으로 누르기 편한 높이
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSizes.space3),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (selected) ...[
                    Icon(Icons.check, size: 16, color: fg),
                    const SizedBox(width: AppSizes.space1),
                  ],
                  Text(
                    text,
                    maxLines: 1,
                    style: TextStyle(
                      fontSize: AppSizes.fsSmall2,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                      color: fg,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// 글 한 줄(피드형): 왼쪽 이니셜 동그라미, 오른쪽에 위에서부터
// 닉네임(굵게)+말머리 뱃지+학교 뱃지 / 제목 / 댓글·공감 수(왼쪽)와 시각(오른쪽 끝).
// 줄 어디를 눌러도 글로 가고, 스크린리더에는 줄 하나가 링크 하나로 읽힌다.
class _PostRow extends StatelessWidget {
  const _PostRow({
    required this.post,
    required this.first,
    required this.showSchool,
  });

  final Post post;
  final bool first; // 첫 줄은 위 선이 없다 (판 테두리와 겹치지 않게)
  final bool showSchool;

  @override
  Widget build(BuildContext context) {
    const metaStyle = TextStyle(
      fontSize: AppSizes.fsSmall,
      color: AppColors.muted,
      height: 1.65,
    );
    final nickname = post.author?.nickname;
    // 줄 하나를 링크 하나로 읽게, 읽을 말을 직접 정해 준다 (순서: 제목, 닉네임, 말머리, 학교, 댓글, 공감, 시각).
    // 안쪽 글자는 excludeSemantics로 빼고, 스크린리더로 누를 수 있게 onTap을 여기에 같이 준다.
    void goPost() => context.go('/posts/${post.id}');
    return Semantics(
        link: true,
        label: [
          post.title,
          nickname ?? '(작성자 없음)',
          post.category.name,
          if (showSchool) post.school?.name ?? '학교 미지정',
          '댓글 ${post.commentCount}',
          '공감 ${post.likeCount}',
          formatDate(post.createdAt),
        ].join(', '),
        excludeSemantics: true,
        onTap: goPost,
        child: InkWell(
          onTap: goPost,
          hoverColor: AppColors.hover, // 올리거나 누르면 조용히 hover 바탕
          splashColor: AppColors.hover,
          highlightColor: AppColors.hover,
          child: Container(
            constraints: const BoxConstraints(minHeight: 48), // 눌리는 높이
            padding: const EdgeInsets.symmetric(
              horizontal: AppSizes.space4,
              vertical: AppSizes.space3,
            ),
            decoration: BoxDecoration(
              border: first
                  ? null
                  : const Border(top: BorderSide(color: AppColors.line)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                InitialAvatar(nickname: nickname),
                const SizedBox(width: AppSizes.space3),
                // 아바타 폭이 같아서 모든 줄의 제목이 같은 x에서 시작한다
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // 가운뎃점 대신 굵기(닉네임)·색 뱃지(말머리)로 나눈다
                      Wrap(
                        spacing: AppSizes.space2,
                        runSpacing: AppSizes.space1,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            nickname ?? '(작성자 없음)',
                            style: const TextStyle(
                              fontSize: AppSizes.fsSmall2,
                              fontWeight: FontWeight.w700,
                              color: AppColors.ink,
                              height: 1.65,
                            ),
                          ),
                          CategoryBadge(
                            code: post.category.code,
                            name: post.category.name,
                          ),
                          if (showSchool)
                            AppBadge(
                              text: post.school?.name ?? '학교 미지정',
                              school: true,
                            ),
                        ],
                      ),
                      Text(
                        post.title,
                        style: const TextStyle(
                          fontSize: AppSizes.fsFeedTitle,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: AppSizes.space1),
                      Row(
                        children: [
                          _Count(
                            icon: Icons.chat_bubble_outline,
                            label: '댓글',
                            count: post.commentCount,
                          ),
                          const SizedBox(width: AppSizes.space3),
                          _Count(
                            icon: Icons.favorite_border,
                            label: '공감',
                            count: post.likeCount,
                          ),
                          // 시각은 오른쪽 끝 (좁으면 줄바꿈)
                          Expanded(
                            child: Text(
                              formatDate(post.createdAt),
                              textAlign: TextAlign.right,
                              style: metaStyle,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
    );
  }
}

// 댓글·공감 수: 눈에는 아이콘 + 숫자, 스크린리더에는 "댓글 3"·"공감 4".
// 0은 흐린 muted, 1 이상은 밝게(ink) 보여 반응이 있는 글이 눈에 띈다.
class _Count extends StatelessWidget {
  const _Count({required this.icon, required this.label, required this.count});

  final IconData icon;
  final String label;
  final int count;

  @override
  Widget build(BuildContext context) {
    final color = count > 0 ? AppColors.ink : AppColors.muted;
    return Semantics(
      label: '$label $count',
      child: ExcludeSemantics(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 16, color: color),
            const SizedBox(width: AppSizes.space1),
            Text(
              '$count',
              style: TextStyle(
                fontSize: AppSizes.fsSmall,
                color: color,
                height: 1.65,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
