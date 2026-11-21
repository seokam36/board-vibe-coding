import 'package:flutter/semantics.dart' show OrdinalSortKey;
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 목록 아래에 비워 두는 자리: 연필 버튼(56) + 버튼 바깥 여백(16) + 여유(16).
// 목록 맨 아래 줄이 연필 버튼에 가리지 않게, 목록 판 아래 여백으로 쓴다.
const writeFabSpace = 88.0;

// 글쓰기 연필 버튼이 붙은 목록 화면 틀 (게시판·비자 후기·중고 목록에서 같이 쓴다).
// 연필 버튼은 스크롤과 상관없이 화면 오른쪽 아래에 떠 있어야 해서, 스크롤되는 본문(child) 위에 Stack으로 겹친다
// (페이지 안에는 Scaffold를 만들 수 없다). onPressed가 null이면 버튼 없이 child만 보여 준다.
class WriteFabLayout extends StatelessWidget {
  const WriteFabLayout({
    super.key,
    required this.child,
    required this.tooltip,
    this.onPressed,
  });

  final Widget child;
  final String tooltip; // 버튼에 올렸을 때 뜨는 글자이자 스크린리더가 읽는 이름
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final onPressed = this.onPressed;
    // onPressed가 없어도 같은 Stack 구조를 돌려준다 (뿌리 위젯이 바뀌면 스크롤·상태가 초기화되므로)
    return Stack(
      children: [
        Semantics(
          container: true,
          sortKey: const OrdinalSortKey(1), // 연필 버튼(0) 다음에 본문
          child: child,
        ),
        // 본문 기둥(720) 오른쪽 끝에 맞춰 오른쪽 아래에 띄운다
        if (onPressed != null)
        Positioned.fill(
          child: Align(
            alignment: Alignment.bottomCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(
                maxWidth: AppSizes.pageMaxWidth,
              ),
              child: Align(
                alignment: Alignment.bottomRight,
                child: Padding(
                  padding: const EdgeInsets.all(AppSizes.space4),
                  // 스크린리더 읽는 순서: 연필 버튼을 목록(1)보다 먼저(0) 읽게 한다 (OrdinalSortKey는 키보드 Tab 순서는 바꾸지 않는다)
                  child: Semantics(
                    container: true,
                    sortKey: const OrdinalSortKey(0),
                    child: FloatingActionButton(
                      heroTag: null,
                      shape: const CircleBorder(), // 기본은 둥근 네모라 동그라미로 지정
                      tooltip: tooltip,
                      elevation: 0,
                      focusElevation: 0,
                      hoverElevation: 0,
                      highlightElevation: 0,
                      backgroundColor:
                          AppColors.highlight, // 노랑은 바탕으로만, 위의 아이콘은 ink
                      foregroundColor: AppColors.ink,
                      onPressed: onPressed,
                      child: const Icon(Icons.edit_outlined),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
