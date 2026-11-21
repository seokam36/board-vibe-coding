import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';
import 'initial_avatar.dart';

// 글 보기의 작성자 머리 (게시판 글·중고 글·비자 후기가 같이 쓴다):
// 왼쪽 이니셜 동그라미(36) + 오른쪽 위 "닉네임(15·굵게) + 뱃지들", 그 아래 시각(회색 13).
// 닉네임이 null이면(작성자 없음) 빈 동그라미와 emptyName 글자를 보여 준다. 뱃지 줄은 좁으면 줄바꿈한다.
// 글 본문을 드래그해서 복사할 때 이니셜이 섞이지 않게 동그라미만 선택에서 뺀다.
class AuthorHead extends StatelessWidget {
  const AuthorHead({
    super.key,
    required this.nickname,
    required this.time,
    this.badges = const [],
    this.emptyName = '(작성자 없음)',
  });

  final String? nickname;
  final String time; // 이미 읽기 좋게 바꾼 시각 글자 (formatDate 결과)
  final List<Widget> badges;
  final String emptyName; // 닉네임이 없을 때 대신 보여 줄 글자

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SelectionContainer.disabled(child: InitialAvatar(nickname: nickname)),
        const SizedBox(width: AppSizes.space2),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                spacing: AppSizes.space2,
                runSpacing: AppSizes.space1,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    nickname ?? emptyName,
                    style: const TextStyle(
                      fontSize: AppSizes.fsBody,
                      fontWeight: FontWeight.w700,
                      color: AppColors.ink,
                    ),
                  ),
                  ...badges,
                ],
              ),
              Text(
                time,
                style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
