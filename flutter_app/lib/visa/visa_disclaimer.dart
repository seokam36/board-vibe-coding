import 'package:material_ui/material_ui.dart';

import '../common/external_link.dart';
import '../widgets/app_link.dart';
import '../widgets/notice.dart';

// 비자 공식 안내(하이코리아) 주소 (React의 visaOfficialLink.ts)
const hikoreaUrl = 'https://www.hikorea.go.kr/';

// 비자 후기 화면 위쪽 안내 (React의 VisaDisclaimer): 후기는 개인 경험이고, 공식 확인은 하이코리아에서 하라는 문구.
// 하이코리아 링크는 새 창으로 연다(열린 창이 이 사이트를 건드리지 못하게 noopener).
class VisaDisclaimer extends StatelessWidget {
  const VisaDisclaimer({super.key});

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.info,
      // 글 사이에 링크를 끼워 넣으려고 Text.rich + WidgetSpan을 쓴다
      child: Text.rich(
        TextSpan(
          children: [
            const TextSpan(
              text: '후기는 개인 경험이라 지금 기준과 다를 수 있어요(작성일을 확인하세요). '
                  '신청 대행·개인별 법률 상담은 하지 않아요. 공식 안내는 ',
            ),
            WidgetSpan(
              alignment: PlaceholderAlignment.baseline,
              baseline: TextBaseline.alphabetic,
              child: AppLink(label: '하이코리아', onTap: () => externalLinkOpener.open(hikoreaUrl)),
            ),
            const TextSpan(text: '에서 확인하세요'),
          ],
        ),
      ),
    );
  }
}
