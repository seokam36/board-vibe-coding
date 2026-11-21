import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 닉네임 첫 글자를 담은 동그라미 (글 목록·글 보기·댓글 줄 왼쪽).
// 색은 한 가지(연노랑 바탕 + 진한 갈색 글자)라 색으로 사람을 구분하지 않는다.
// 작성자가 없거나 닉네임이 비었으면 글자 없는 회색(hover) 동그라미.
// 글자는 장식이라 스크린리더가 읽지 않는다 (옆의 닉네임이 읽힌다).
class InitialAvatar extends StatelessWidget {
  const InitialAvatar({super.key, required this.nickname, this.size = 36});

  final String? nickname; // 작성자가 없으면 null
  final double size;

  // 첫 글자. characters는 "보이는 글자 한 개" 단위라 이모지·한글이 반쪽으로 잘리지 않는다
  String get initial {
    final name = nickname?.trim() ?? '';
    return name.isEmpty ? '' : name.characters.first;
  }

  @override
  Widget build(BuildContext context) {
    final text = initial;
    return ExcludeSemantics(
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: text.isEmpty ? AppColors.hover : AppColors.highlightSoft,
          shape: BoxShape.circle,
        ),
        child: Text(
          text,
          maxLines: 1,
          style: TextStyle(
            fontSize: (size * 0.42).roundToDouble(), // 36이면 15
            fontWeight: FontWeight.w700,
            color: AppColors.highlightInk,
            height: 1.2,
          ),
        ),
      ),
    );
  }
}
