import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'page_frame.dart';

// 로그인·회원가입이 같이 쓰는 카드 틀 (React의 panel auth-card):
// 본문 틀(PageFrame) 안에서 최대 400 폭 가운데, 테두리 있는 판. 안의 칸들은 세로로 꽉 채운다.
class AuthCard extends StatelessWidget {
  const AuthCard({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return PageFrame(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 400),
          child: Container(
            margin: const EdgeInsets.only(top: AppSizes.space5),
            padding: const EdgeInsets.all(AppSizes.space4),
            decoration: BoxDecoration(
              color: AppColors.paper,
              border: Border.all(color: AppColors.line),
              borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
            ),
            // 자동완성(비밀번호 관리자)이 한 묶음으로 보게 한다
            child: AutofillGroup(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: children,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
