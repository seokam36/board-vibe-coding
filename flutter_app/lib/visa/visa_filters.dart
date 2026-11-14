import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../posts/models.dart' show School;
import '../widgets/loading.dart';
import '../widgets/select_field.dart';
import '../theme/app_colors.dart';
import 'models.dart';

// 학교·비자 종류·신청 종류 드롭다운 3개 (React의 VisaReviewFilters). 목록·통계 화면이 같이 쓴다.
// 값의 "진짜 위치"는 주소 쿼리다. 바꾸면 주소만 바꾸고, 주소가 바뀌면 화면이 알아서 다시 불러온다.
// path: 바꿀 때 이동할 경로('/visa-reviews' 또는 '/visa-reviews/stats').
class VisaReviewFilters extends StatelessWidget {
  const VisaReviewFilters({
    super.key,
    required this.path,
    required this.schoolId,
    required this.visaType,
    required this.applicationType,
    required this.schools,
    required this.options,
    required this.schoolsFailed,
    required this.optionsFailed,
  });

  final String path;
  final String schoolId;
  final String visaType;
  final String applicationType;
  final List<School>? schools;
  final VisaReviewOptions? options;
  final bool schoolsFailed;
  final bool optionsFailed;

  // 하나만 바꾼다. 주소의 다른 쿼리는 그대로 두고, "전체"(빈 값)는 주소에서 뺀다
  void _change(BuildContext context, String name, String value) {
    final query = Map<String, String>.of(GoRouterState.of(context).uri.queryParameters);
    if (value.isEmpty) {
      query.remove(name);
    } else {
      query[name] = value;
    }
    context.go(Uri(path: path, queryParameters: query.isEmpty ? null : query).toString());
  }

  // 드롭다운 하나. 주소의 값이 목록에 없으면 임시 항목을 끼워 넣어 주소를 그대로 보여 준다
  // (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다). 목록을 받는 중이면 "불러오는 중..."
  Widget _select(
    BuildContext context, {
    required String name,
    required String label,
    required String allLabel,
    required String unknownLabel,
    required double width,
    required String value,
    required List<(String, String)>? items,
    required bool pending,
  }) {
    final missing = value.isNotEmpty && !(items ?? const []).any((i) => i.$1 == value);
    return SelectField(
      fieldKey: ValueKey('filter-$name'),
      label: label,
      width: width,
      value: value,
      options: [
        ('', allLabel),
        if (missing) (value, pending ? loadingText : '알 수 없는 $unknownLabel ($value)'),
        ...?items,
      ],
      onChanged: (v) => _change(context, name, v),
    );
  }

  @override
  Widget build(BuildContext context) {
    final opts = options;
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: AppSizes.space2,
      runSpacing: AppSizes.space2,
      children: [
        _select(
          context,
          name: 'schoolId',
          label: '학교',
          allLabel: '전체 학교',
          unknownLabel: '학교',
          width: 170,
          value: schoolId,
          items: schools == null ? null : [for (final s in schools!) ('${s.id}', s.name)],
          pending: schools == null && !schoolsFailed,
        ),
        _select(
          context,
          name: 'visaType',
          label: '비자 종류',
          allLabel: '전체 비자 종류',
          unknownLabel: '비자 종류',
          width: 170,
          value: visaType,
          items: opts == null ? null : [for (final o in opts.visaTypes) (o.code, o.name)],
          pending: opts == null && !optionsFailed,
        ),
        _select(
          context,
          name: 'applicationType',
          label: '신청 종류',
          allLabel: '전체 신청 종류',
          unknownLabel: '신청 종류',
          width: 170,
          value: applicationType,
          items: opts == null ? null : [for (final o in opts.applicationTypes) (o.code, o.name)],
          pending: opts == null && !optionsFailed,
        ),
      ],
    );
  }
}
