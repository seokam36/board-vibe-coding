import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../auth/auth_state.dart';
import '../theme/app_colors.dart';
import 'app_badge.dart';
import 'panel.dart';
import 'section_parts.dart';

// 내 정보 화면의 "학교 인증" 섹션.
// S20-f2에서 학교 메일 인증 폼을 걷어냈다: 인증 회원은 뱃지, 미인증 회원은 안내 한 줄만 보여 준다.
// 유학생 인증(표준입학허가서) 신청 화면은 S20-g에서 이 자리에 붙는다.
// 요청을 보내지 않으니 상태가 필요 없는 StatelessWidget이다 (인증 여부는 AuthState에서 읽기만 한다).
class SchoolVerificationSection extends StatelessWidget {
  const SchoolVerificationSection({super.key});

  @override
  Widget build(BuildContext context) {
    final school = context.watch<AuthState>().user?.verifiedSchool;
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeading('학교 인증'),
          if (school != null) ...[
            // 이미 인증했으면 뱃지와 안내만
            AppBadge(text: '학교 인증 완료 · ${school.name}', ink: AppColors.celadonInk, bg: AppColors.celadonSoft),
            const FieldHint('졸업 후에도 유지돼요'),
          ] else
            // 미인증: 버튼 없는 고정 안내
            const Text('유학생 인증(표준입학허가서) 화면을 준비 중이에요'),
        ],
      ),
    );
  }
}
