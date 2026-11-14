import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/labeled_field.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/select_field.dart';

// 이 화면만 쓰는 문구는 여기에 둔다 (여러 화면이 겹쳐 쓰는 문구만 error_messages 사전에 있다)
const _forbiddenMessage = '학교 인증이 필요하거나 인증한 학교 게시판이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요';
const _pickCategoryMessage = '말머리를 골라 주세요';
const _optionsFailedSaveMessage = '말머리 목록을 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요';
const _optionsFailedMessage = '말머리 목록을 불러오지 못했습니다';

// 글쓰기 화면 "/posts/new" (React의 PostCreatePage). 학교는 고르지 않고 인증한 학교로 고정된다.
class PostCreatePage extends StatefulWidget {
  const PostCreatePage({super.key});

  @override
  State<PostCreatePage> createState() => _PostCreatePageState();
}

class _PostCreatePageState extends State<PostCreatePage> {
  final _title = TextEditingController();
  final _content = TextEditingController();
  // 말머리: 처음엔 아무것도 고르지 않은 상태(''). 입력창과 달리 드롭다운 값은 State에 들고 있는다
  String _category = '';
  // 마지막으로 본 인증 학교. 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고 기억해 둔다
  VerifiedSchool? _lastSchool;
  // 말머리 목록. 못 받으면 null로 남고, _optionsFailed로 안내한다
  List<Category>? _categories;
  bool _optionsFailed = false;
  bool _saving = false;
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  bool _needLogin = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadCategories(); // 화면이 처음 나타날 때 말머리 목록을 한 번 불러온다
  }

  @override
  void dispose() {
    _title.dispose();
    _content.dispose();
    super.dispose();
  }

  void _loadCategories() {
    context.read<ApiClient>().dio.get<List<dynamic>>('/categories').then((res) {
      if (!mounted) return;
      setState(() => _categories = [
            for (final e in res.data!) Category.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object _) {
      if (mounted) setState(() => _optionsFailed = true);
    });
  }

  Future<void> _submit(VerifiedSchool? school) async {
    if (_saving) return; // 저장 중 Enter를 또 눌러도 한 번만 보낸다 (React는 버튼이 잠겨 제출이 막힌다)
    setState(() {
      _error = null;
      _needLogin = false;
    });
    if (school == null) return; // 미인증이면 폼 자체가 안 보이지만, 혹시 몰라 막는다
    // 말머리를 안 골랐으면 서버에 보내지 않고 화면에 머문다 (제목·내용은 그대로 남는다)
    if (_category.isEmpty) {
      setState(() => _error = _optionsFailed ? _optionsFailedSaveMessage : _pickCategoryMessage);
      return;
    }
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    setState(() => _saving = true);
    try {
      // 제목·내용의 빈 값 검사와 앞뒤 공백 정리는 일부러 하지 않는다. 서버의 400 응답을 그대로 확인한다
      final res = await dio.post<Map<String, dynamic>>('/posts', data: {
        'title': _title.text,
        'content': _content.text,
        'schoolId': school.id,
        'category': _category,
      });
      // 성공하면 새 글의 상세로 이동 (기록이 남는 go = React의 navigate). 버튼은 잠긴 채 화면이 바뀐다
      if (mounted) router.go('/posts/${res.data!['id']}');
    } catch (err) {
      // 403: 미인증이거나 인증한 학교가 아님. CSRF 토큰 문제도 403이라 구분이 안 되어 새로고침도 함께 안내한다
      final info = describeError(
        err,
        {400: ErrorMessages.postFieldsInvalid, 403: _forbiddenMessage},
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
    final user = context.watch<AuthState>().user;
    final current = user?.verifiedSchool;
    if (current != null && current.id != _lastSchool?.id) _lastSchool = current;
    // 미인증 판단은 user가 있을 때만. user가 null(세션 만료)이면 기억해 둔 학교로 폼을 유지 → ReloginNotice가 보인다
    final school = user != null ? current : _lastSchool; // null이면 미인증 → 폼 대신 안내를 보여 준다

    // 미인증: 글을 쓸 수 없으니 폼 대신 안내와 인증 화면 링크를 보여 준다
    if (school == null) {
      return PageFrame(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const PageTitle('글쓰기'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('글쓰기는 학교 인증 후에 할 수 있어요'),
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

    final categories = _categories ?? const <Category>[];
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('글쓰기'),
          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // React의 .filters: 학교 뱃지 + 말머리 드롭다운 (아래 여백 16)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSizes.space4),
                  child: Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: AppSizes.space2,
                    runSpacing: AppSizes.space2,
                    children: [
                      // 학교는 인증한 학교로 고정: 고르는 칸이 아니라 글자로만 보여 준다.
                      // 스크린리더에는 "학교: 이름"으로 읽힌다 (React의 sr-only 접두어)
                      Semantics(
                        label: '학교: ${school.name}',
                        child: ExcludeSemantics(child: AppBadge(text: school.name, school: true)),
                      ),
                      SelectField(
                        fieldKey: const ValueKey('category-select'),
                        label: '말머리',
                        width: 170,
                        value: _category,
                        options: [
                          ('', '말머리 선택'),
                          for (final c in categories) (c.code, c.name),
                        ],
                        onChanged: (v) => setState(() => _category = v),
                      ),
                    ],
                  ),
                ),
                if (_optionsFailed) const Notice(kind: NoticeKind.error, message: _optionsFailedMessage),
                LabeledField(
                  fieldKey: const ValueKey('title-field'),
                  label: '제목',
                  hint: '제목',
                  controller: _title,
                  maxLength: 100,
                  onSubmitted: () => _submit(school),
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
                      onPressed: _saving ? null : () => _submit(school),
                      child: Text(_saving ? '저장 중...' : '저장'),
                    ),
                    OutlinedButton(
                      onPressed: () => context.go('/'),
                      child: const Text('목록으로'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
