import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import 'models.dart';

// 후기 작성·수정 화면용: 비자 종류·신청 종류·결과 선택지만 한 번 불러오는 도우미 (React의 useVisaReviewOptions).
// 학교 목록은 필요 없다(학교는 서버가 인증 학교로 정한다). 실패해도 화면은 계속 보여 주고 optionsFailed로 안내한다.
mixin VisaReviewOptionsLoader<T extends StatefulWidget> on State<T> {
  VisaReviewOptions? reviewOptions; // 아직 못 받았거나 실패하면 null
  bool optionsFailed = false;

  // initState에서 한 번 부른다
  void loadReviewOptions() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/visa-review-options').then((res) {
      if (!mounted) return; // 화면이 사라진 뒤 도착한 응답은 버린다
      setState(() => reviewOptions = VisaReviewOptions.fromJson(res.data!));
    }).catchError((Object e) {
      debugPrint('비자 선택지 불러오기 실패: $e');
      if (mounted) setState(() => optionsFailed = true);
    });
  }
}
