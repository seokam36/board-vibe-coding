import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../visa/filter_options.dart';
import '../visa/models.dart';
import '../visa/visa_disclaimer.dart';
import '../visa/visa_filters.dart';
import '../visa/visa_tabs.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

const _statsFailedMessage = '통계를 불러오지 못했습니다';

// 비자 후기 통계 "/visa-reviews/stats" (React의 VisaReviewStatsPage, 읽기 전용).
// 거르기 값은 목록과 같이 주소 쿼리가 진짜 값이다. 서버가 준 값을 "N건"·"X.X일"로 보여 주기만 한다.
class VisaReviewStatsPage extends StatefulWidget {
  const VisaReviewStatsPage({
    super.key,
    this.schoolId = '',
    this.visaType = '',
    this.applicationType = '',
  });

  final String schoolId;
  final String visaType;
  final String applicationType;

  @override
  State<VisaReviewStatsPage> createState() => _VisaReviewStatsPageState();
}

class _VisaReviewStatsPageState extends State<VisaReviewStatsPage> with VisaFilterOptionsLoader {
  String? _resultKey;
  List<VisaReviewStat> _stats = const [];
  String? _error;
  int _seq = 0; // 늦게 도착한 옛 응답을 버리기 위한 요청 번호

  String get _queryKey => '${widget.schoolId}|${widget.visaType}|${widget.applicationType}';

  @override
  void initState() {
    super.initState();
    loadFilterOptions();
    _load();
  }

  @override
  void didUpdateWidget(VisaReviewStatsPage old) {
    super.didUpdateWidget(old);
    if (old.schoolId != widget.schoolId ||
        old.visaType != widget.visaType ||
        old.applicationType != widget.applicationType) {
      _load();
    }
  }

  @override
  void dispose() {
    _seq++;
    super.dispose();
  }

  void _load() {
    final seq = ++_seq;
    final key = _queryKey;
    final params = <String, String>{
      if (widget.schoolId.isNotEmpty) 'schoolId': widget.schoolId,
      if (widget.visaType.isNotEmpty) 'visaType': widget.visaType,
      if (widget.applicationType.isNotEmpty) 'applicationType': widget.applicationType,
    };
    context.read<ApiClient>().dio.get<List<dynamic>>('/visa-reviews/stats', queryParameters: params).then((res) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _resultKey = key;
        _stats = [for (final e in res.data!) VisaReviewStat.fromJson(e as Map<String, dynamic>)];
        _error = null;
      });
    }).catchError((Object e) {
      debugPrint('통계 불러오기 실패: $e');
      if (!mounted || seq != _seq) return;
      setState(() {
        _resultKey = key;
        _stats = const [];
        _error = errorText(
          e,
          {404: ErrorMessages.schoolNotFound, 400: ErrorMessages.reviewFilterInvalid},
          _statsFailedMessage,
        );
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final schoolId = widget.schoolId;
    final loading = _resultKey != _queryKey;
    final stats = loading ? const <VisaReviewStat>[] : _stats;
    final error = loading ? null : _error;

    // 머리말은 목록과 같은 규칙 (통계인지는 탭이 알려 준다)
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
          const VisaReviewTabs(current: VisaTab.stats),
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Align(
              alignment: Alignment.centerLeft,
              child: VisaReviewFilters(
                path: '/visa-reviews/stats',
                schoolId: schoolId,
                visaType: widget.visaType,
                applicationType: widget.applicationType,
                schools: schools,
                options: options,
                schoolsFailed: schoolsFailed,
                optionsFailed: optionsFailed,
              ),
            ),
          ),
          if (filterOptionsFailed)
            const Notice(kind: NoticeKind.error, message: ErrorMessages.reviewOptionsLoadFailed),
          FlushPanel(
            children: [
              if (loading) const Loading(),
              if (error != null) PanelState(child: Notice(kind: NoticeKind.error, message: error)),
              if (!loading && error == null && stats.isEmpty)
                const PanelState(child: Text('아직 통계가 없습니다', style: TextStyle(color: AppColors.muted))),
              if (stats.isNotEmpty) ...[
                _StatsTable(stats: stats, showSchool: schoolId.isEmpty),
                // 표가 있을 때만 보이는 주의 문구
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
                  decoration: const BoxDecoration(border: Border(top: BorderSide(color: AppColors.line))),
                  child: const Text(
                    '건수가 적으면 평균이 크게 흔들릴 수 있어요. 승인·불허를 모두 포함한 값이에요',
                    style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

// 통계 표. 표만 가로 스크롤(페이지는 안 밀린다), 숫자 열은 오른쪽 정렬.
// 학교로 거른 화면에서는 학교 열을 생략한다 (목록의 학교 뱃지 생략과 같은 규칙)
class _StatsTable extends StatelessWidget {
  const _StatsTable({required this.stats, required this.showSchool});

  final List<VisaReviewStat> stats;
  final bool showSchool;

  @override
  Widget build(BuildContext context) {
    Widget cell(String text, {bool header = false, bool right = false}) => Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space2),
          child: Align(
            alignment: right ? Alignment.centerRight : Alignment.centerLeft,
            child: Text(
              text,
              softWrap: false, // 줄바꿈 없이 한 줄 (표가 넓으면 가로로 스크롤)
              style: TextStyle(
                fontSize: header ? AppSizes.fsSmall : AppSizes.fsBody,
                fontWeight: header ? FontWeight.w500 : FontWeight.w400,
                color: header ? AppColors.muted : AppColors.ink,
              ),
            ),
          ),
        );

    return Semantics(
      label: '학교·비자 종류·신청 종류별 후기 건수와 평균 처리일', // 화면에는 안 보이고 스크린리더에만 읽히는 표 설명
      container: true,
      child: LayoutBuilder(
        builder: (context, constraints) => SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          // 판 폭보다 표가 좁으면 판 폭까지 늘리고, 넓으면 가로로 스크롤
          child: ConstrainedBox(
            constraints: BoxConstraints(minWidth: constraints.maxWidth),
            child: Table(
              defaultColumnWidth: const IntrinsicColumnWidth(flex: 1),
              border: const TableBorder(horizontalInside: BorderSide(color: AppColors.line)),
              children: [
                TableRow(
                  children: [
                    if (showSchool) cell('학교', header: true),
                    cell('비자 종류', header: true),
                    cell('신청 종류', header: true),
                    cell('건수', header: true, right: true),
                    cell('평균 처리일', header: true, right: true),
                  ],
                ),
                for (final s in stats)
                  TableRow(
                    children: [
                      if (showSchool) cell(s.school.name),
                      cell(s.visaType.name),
                      cell(s.applicationType.name),
                      cell('${s.count}건', right: true),
                      // 서버가 소수 1자리로 주므로 19.0도 "19.0일" 그대로 (자릿수가 들쭉날쭉하면 읽기 어렵다)
                      cell('${s.avgDays.toStringAsFixed(1)}일', right: true),
                    ],
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
