import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../posts/models.dart' show School;
import 'models.dart';

// 거르기용 목록(학교 + 비자·신청 종류)을 화면이 나타날 때 한 번 불러오는 도우미 (React의 useVisaFilterOptions).
// 목록 화면과 통계 화면의 State가 with로 가져다 쓴다. 학교·선택지는 따로 불러오고, 실패해도 본문은 계속 보여 준다.
mixin VisaFilterOptionsLoader<T extends StatefulWidget> on State<T> {
  List<School>? schools; // 아직 못 받았거나 실패하면 null
  VisaReviewOptions? options;
  bool schoolsFailed = false;
  bool optionsFailed = false;

  // 어느 한쪽이라도 실패하면 안내 문구를 보여 준다
  bool get filterOptionsFailed => schoolsFailed || optionsFailed;

  // initState에서 한 번 부른다
  void loadFilterOptions() {
    final dio = context.read<ApiClient>().dio;
    dio.get<List<dynamic>>('/schools').then((res) {
      if (!mounted) return; // 화면이 사라진 뒤 도착한 응답은 버린다
      setState(() => schools = [
            for (final e in res.data!) School.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object e) {
      debugPrint('학교 목록 불러오기 실패: $e');
      if (mounted) setState(() => schoolsFailed = true);
    });
    dio.get<Map<String, dynamic>>('/visa-review-options').then((res) {
      if (!mounted) return;
      setState(() => options = VisaReviewOptions.fromJson(res.data!));
    }).catchError((Object e) {
      debugPrint('비자 선택지 불러오기 실패: $e');
      if (mounted) setState(() => optionsFailed = true);
    });
  }
}
