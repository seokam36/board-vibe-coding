import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/labeled_field.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/select_field.dart';

// 이 화면만 쓰는 문구는 여기에 둔다
const _forbiddenMessage = '본인 글만 수정할 수 있습니다';
const _optionsFailedMessage = '말머리 목록을 불러오지 못했습니다. 지금 말머리로만 저장할 수 있습니다';

// 글 수정 화면 "/posts/:id/edit" (React의 PostEditPage).
// 작성자 확인은 화면에서 하지 않는다: 주소로 들어와도 열리지만 저장은 서버가 403으로 막는다.
class PostEditPage extends StatefulWidget {
  const PostEditPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<PostEditPage> createState() => _PostEditPageState();
}

class _PostEditPageState extends State<PostEditPage> {
  final _title = TextEditingController();
  final _content = TextEditingController();
  // 학교는 바꿀 수 없어 글자로만 보여 준다 (학교 없는 옛 글은 null)
  String? _schoolName;
  // _category: 선택된 말머리 code. _current: 글이 원래 가진 말머리 (목록을 못 받아도 잃지 않으려고 따로 보관)
  String _category = '';
  Category? _current;
  List<Category>? _categories;
  bool _categoriesFailed = false;
  bool _loading = true;
  // _loadError: 글을 못 불러온 경우(폼 숨김), _error: 저장 실패(폼 유지)
  String? _loadError;
  String? _error;
  bool _saving = false;
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  bool _needLogin = false;

  // 상세 화면과 같은 이유로 id를 경로에 넣기 전에 인코딩한다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    _loadPost();
    _loadCategories();
  }

  @override
  void dispose() {
    _title.dispose();
    _content.dispose();
    super.dispose();
  }

  // 들어오면 원래 글을 불러와 폼에 채운다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted)
  void _loadPost() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/posts/$_safeId').then((res) {
      if (!mounted) return;
      final post = Post.fromJson(res.data!);
      setState(() {
        _title.text = post.title;
        _content.text = post.content;
        _schoolName = post.school?.name;
        _category = post.category.code;
        _current = post.category;
        _loadError = null;
        _loading = false;
      });
    }).catchError((Object e) {
      if (!mounted) return;
      setState(() {
        // 401도 로그인 안내 없이 기본 문구 (불러오기 실패에는 로그인 안내가 따로 없다)
        _loadError = errorText(
          e,
          {404: ErrorMessages.postNotFound, 400: ErrorMessages.postNotFound},
          ErrorMessages.postLoadFailed,
        );
        _loading = false;
      });
    });
  }

  // 말머리 목록도 불러온다. 실패해도 수정은 막지 않는다 (현재 말머리 하나로 저장할 수 있다)
  void _loadCategories() {
    context.read<ApiClient>().dio.get<List<dynamic>>('/categories').then((res) {
      if (!mounted) return;
      setState(() => _categories = [
            for (final e in res.data!) Category.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object _) {
      if (mounted) setState(() => _categoriesFailed = true);
    });
  }

  Future<void> _submit() async {
    if (_saving) return; // 저장 중 Enter를 또 눌러도 한 번만 보낸다
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    final safeId = _safeId;
    setState(() {
      _saving = true;
      _error = null;
      _needLogin = false;
    });
    try {
      // 학교는 바꿀 수 없으니 schoolId는 일부러 보내지 않는다. 빈 값 검사는 작성 화면처럼 서버의 400에 맡긴다
      await dio.put<dynamic>('/posts/$safeId', data: {
        'title': _title.text,
        'content': _content.text,
        'category': _category,
      });
      // 수정한 글의 상세로 이동. Router.neglect: 기록을 "새로 쌓지 않고 교체"해서(React의 replace)
      // 저장한 수정 폼이 뒤로 가기 기록에 남지 않게 한다
      if (mounted) Router.neglect(context, () => router.go('/posts/$safeId'));
    } catch (err) {
      // 403: 남의 글. 이 화면은 주소로 직접 들어와도 열리지만 저장은 서버가 막는다
      final info = describeError(
        err,
        {
          403: _forbiddenMessage,
          400: ErrorMessages.postFieldsInvalid,
          404: ErrorMessages.postNotFound,
        },
        ErrorMessages.saveFailed,
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false; // 실패했을 때만 버튼을 되살린다 (입력값은 그대로 남는다)
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // 드롭다운에 보일 말머리: 받은 목록 + (목록에 없거나 못 받았으면) 글의 현재 말머리를 맨 앞에
    final options = <Category>[...?_categories];
    final current = _current;
    if (current != null && !options.any((c) => c.code == current.code)) options.insert(0, current);

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('글 수정'),
          if (_loading) const Loading(),
          if (_loadError != null) Notice(kind: NoticeKind.error, message: _loadError!),
          // 글을 불러오는 데 성공했을 때만 폼을 보여준다
          if (!_loading && _loadError == null)
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSizes.space4),
                    child: Wrap(
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: AppSizes.space2,
                      runSpacing: AppSizes.space2,
                      children: [
                        // 학교는 글자로만 보여 준다 (바꿀 수 없음)
                        AppBadge(text: _schoolName ?? '학교 미지정', school: true),
                        SelectField(
                          fieldKey: const ValueKey('category-select'),
                          label: '말머리',
                          width: 170,
                          value: _category,
                          options: [for (final c in options) (c.code, c.name)],
                          onChanged: (v) => setState(() => _category = v),
                        ),
                      ],
                    ),
                  ),
                  if (_categoriesFailed) const Notice(kind: NoticeKind.error, message: _optionsFailedMessage),
                  LabeledField(
                    fieldKey: const ValueKey('title-field'),
                    label: '제목',
                    hint: '제목',
                    controller: _title,
                    maxLength: 100,
                    onSubmitted: _submit,
                  ),
                  LabeledField(
                    fieldKey: const ValueKey('content-field'),
                    label: '내용',
                    hint: '내용',
                    controller: _content,
                    lines: 10,
                  ),
                  if (_error != null) Notice(kind: NoticeKind.error, message: _error),
                  if (_needLogin) const ReloginNotice(),
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: AppSizes.space2,
                    runSpacing: AppSizes.space2,
                    children: [
                      FilledButton(
                        onPressed: _saving ? null : _submit,
                        child: Text(_saving ? '저장 중...' : '저장'),
                      ),
                      OutlinedButton(
                        onPressed: () => context.go('/posts/$_safeId'),
                        child: const Text('취소'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          // 폼이 숨겨진 경우에도 돌아갈 길을 남긴다
          if (_loadError != null)
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
