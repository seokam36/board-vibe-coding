import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

// 읽기 전용 칸(누르면 달력이 열리는 날짜 칸)을 키보드로도 열게 한다.
// Tab으로 칸에 포커스가 간 뒤 Enter·Space를 누르면 onActivate를 부른다 (마우스로 누르는 것과 같은 동작).
// 칸이 읽기 전용이라 Space·Enter가 글자 입력으로 쓰이지 않으니 여기서 가로채도 안전하다.
class ActivateOnKey extends StatelessWidget {
  const ActivateOnKey({super.key, required this.onActivate, required this.child});

  final VoidCallback onActivate;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Focus(
      // 자기 자신은 포커스를 받지 않고, 안쪽 칸이 받은 키 이벤트만 위로 올라와 처리한다
      canRequestFocus: false,
      skipTraversal: true,
      onKeyEvent: (node, event) {
        if (event is! KeyDownEvent) return KeyEventResult.ignored;
        final key = event.logicalKey;
        if (key == LogicalKeyboardKey.enter || key == LogicalKeyboardKey.numpadEnter || key == LogicalKeyboardKey.space) {
          onActivate();
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: child,
    );
  }
}
