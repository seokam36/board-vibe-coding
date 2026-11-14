import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../visa/filter_options.dart';
import '../visa/models.dart';
import '../visa/result_badge.dart';
import '../visa/visa_disclaimer.dart';
import '../visa/visa_filters.dart';
import '../visa/visa_tabs.dart';
import '../widgets/app_badge.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/row_link.dart';

const _listFailedMessage = '후기 목록을 불러오지 못했습니다';

// 비자 후기 목록 "/visa-reviews" (React의 VisaReviewListPage, 읽기 전용).
// 필터의 진짜 값은 주소 쿼리(?schoolId&visaType&applicationType). 라우터가 읽어 넘겨 주고,
// 드롭다운은 주소만 바꾸며, 주소가 바뀌어 새 값을 받으면 목록을 다시 불러온다 (글 목록과 같은 방식).
class VisaReviewListPage extends StatefulWidget {
  const VisaReviewListPage({
    super.key,
    this.schoolId = '',
    this.visaType = '',
    this.applicationType = '',
  });

  final String schoolId;
  final String visaType;
  final String applicationType;

  @override
  State<VisaReviewListPage> createState() => _VisaReviewListPageState();
}

class _VisaReviewListPageState extends State<VisaReviewListPage> with VisaFilterOptionsLoader {
  // 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해, 지금 조건의 결과인지 비교한다
  String? _resultKey;
  List<VisaReview> _reviews = const [];
  String? _error;
  int _seq = 0; // 요청마다 번호를 매겨, 늦게 도착한 옛 응답은 버린다

  String get _queryKey => '${widget.schoolId}|${widget.visaType}|${widget.applicationType}';

  @override
  void initState() {
    super.initState();
    loadFilterOptions();
    _load();
  }

  @override
  void didUpdateWidget(VisaReviewListPage old) {
    super.didUpdateWidget(old);
    if (old.schoolId != widget.schoolId ||
        old.visaType != widget.visaType ||
        old.applicationType != widget.applicationType) {
      _load();
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  void _load() {
    final seq = ++_seq;
    final key = _queryKey;
    // 빈 값은 보내지 않는다(조건 없음). 주소의 값 그대로 보내서 서버가 400/404로 판단하게 한다
    final params = <String, String>{
      if (widget.schoolId.isNotEmpty) 'schoolId': widget.schoolId,
      if (widget.visaType.isNotEmpty) 'visaType': widget.visaType,
      if (widget.applicationType.isNotEmpty) 'applicationType': widget.applicationType,
    };
    context.read<ApiClient>().dio.get<List<dynamic>>('/visa-reviews', queryParameters: params).then((res) {
      if (!mounted || seq != _seq) return; // 늦게 온 옛 응답은 무시
      setState(() {
        _resultKey = key;
        _reviews = [for (final e in res.data!) VisaReview.fromJson(e as Map<String, dynamic>)];
        _error = null;
      });
    }).catchError((Object e) {
      debugPrint('후기 목록 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
      if (!mounted || seq != _seq) return;
      setState(() {
        _resultKey = key;
        _reviews = const [];
        _error = errorText(
          e,
          {404: ErrorMessages.schoolNotFound, 400: ErrorMessages.reviewFilterInvalid},
          _listFailedMessage,
        );
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final schoolId = widget.schoolId;

    final loading = _resultKey != _queryKey; // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중
    final reviews = loading ? const <VisaReview>[] : _reviews;
    final error = loading ? null : _error;

    // 머리말: 고른 학교 이름 + " 비자 후기", 아니면 "비자 후기" (학교 목록을 기다리는 동안도 "비자 후기")
    final schoolName = schoolId.isEmpty
        ? null
        : schools?.where((s) => '${s.id}' == schoolId).map((s) => s.name).firstOrNull;
    final title = schoolName != null ? '$schoolName 비자 후기' : '비자 후기';

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
                title,
                style: const TextStyle(
                  fontSize: AppSizes.fsBoard,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  height: 1.3,
                ),
              ),
            ),
          ),
          const VisaDisclaimer(),
          const VisaReviewTabs(current: VisaTab.list),
          // 툴바: 왼쪽 필터 셋, 오른쪽 후기 쓰기. 좁은 화면에선 줄바꿈된다
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                VisaReviewFilters(
                  path: '/visa-reviews',
                  schoolId: schoolId,
                  visaType: widget.visaType,
                  applicationType: widget.applicationType,
                  schools: schools,
                  options: options,
                  schoolsFailed: schoolsFailed,
                  optionsFailed: optionsFailed,
                ),
                // 후기 쓰기 버튼은 항상 보이되, 비로그인이면 로그인 화면으로 보낸다 (글쓰기 버튼과 같은 규칙)
                FilledButton(
                  onPressed: () => context.go(user != null ? '/visa-reviews/new' : '/login'),
                  child: const Text('후기 쓰기'),
                ),
              ],
            ),
          ),
          if (filterOptionsFailed)
            const Notice(kind: NoticeKind.error, message: ErrorMessages.reviewOptionsLoadFailed),
          FlushPanel(
            children: [
              if (loading) const Loading(),
              if (error != null) PanelState(child: Notice(kind: NoticeKind.error, message: error)),
              if (!loading && error == null && reviews.isEmpty)
                const PanelState(child: Text('아직 후기가 없습니다', style: TextStyle(color: AppColors.muted))),
              for (var i = 0; i < reviews.length; i++)
                _ReviewRow(
                  review: reviews[i],
                  first: i == 0,
                  showSchool: schoolId.isEmpty, // 학교로 거른 화면에서는 모두 같은 학교라 뱃지를 생략한다
                ),
            ],
          ),
        ],
      ),
    );
  }
}

// 후기 한 줄: 결과 뱃지, (학교 뱃지), 제목 링크 / 작성자·접수일→결과일·작성일 (본문 미리보기는 없다)
class _ReviewRow extends StatelessWidget {
  const _ReviewRow({required this.review, required this.first, required this.showSchool});

  final VisaReview review;
  final bool first;
  final bool showSchool;

  @override
  Widget build(BuildContext context) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    final r = review;
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
              ResultBadge(result: r.result),
              if (showSchool) AppBadge(text: r.school.name, school: true),
              RowLink(
                title: '${r.visaType.name} · ${r.applicationType.name} · 처리 ${r.processingDays}일',
                onTap: () => context.go('/visa-reviews/${r.id}'),
              ),
            ],
          ),
          Wrap(
            spacing: AppSizes.space3,
            children: [
              Text(r.author?.nickname ?? '(작성자 없음)', style: metaStyle),
              Text('${formatDay(r.receivedDate)} → ${formatDay(r.resultDate)}', style: metaStyle),
              Text(formatDate(r.createdAt), style: metaStyle),
            ],
          ),
        ],
      ),
    );
  }
}
