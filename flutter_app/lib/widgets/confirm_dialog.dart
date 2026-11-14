import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../theme/app_colors.dart';

// 지금 열려 있는 확인 창을 false로 닫는 함수 (없으면 null).
// 확인 창을 또 열면 앞의 것은 취소로 끝내려고 한 곳에 들고 있는다 (React의 "이미 떠 있으면 앞의 것은 취소").
VoidCallback? _closeOpenConfirm;

// 확인 창을 띄우고 답을 기다린다 (React의 useConfirm).
// 확인 → true / 취소·Esc·화면(주소) 바뀜·다른 확인 창이 새로 열림 → false.
// 바깥(뒤 막)을 눌러도 닫히지 않는다(실수로 닫히지 않게).
Future<bool> showConfirm(
  BuildContext context, {
  required String message,
  required String confirmLabel,
  bool danger = false,
}) async {
  // 이미 떠 있는 창이 있으면 먼저 취소로 닫는다
  _closeOpenConfirm?.call();
  final router = GoRouter.of(context);
  final result = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    barrierColor: AppColors.backdrop,
    builder: (dialogContext) => _ConfirmDialog(
      router: router,
      message: message,
      confirmLabel: confirmLabel,
      danger: danger,
      onClose: (ok) => Navigator.of(dialogContext).pop(ok),
    ),
  );
  return result ?? false; // Esc 등으로 값 없이 닫혀도 취소
}

class _ConfirmDialog extends StatefulWidget {
  const _ConfirmDialog({
    required this.router,
    required this.message,
    required this.confirmLabel,
    required this.danger,
    required this.onClose,
  });

  final GoRouter router;
  final String message;
  final String confirmLabel;
  final bool danger;
  final void Function(bool ok) onClose;

  @override
  State<_ConfirmDialog> createState() => _ConfirmDialogState();
}

class _ConfirmDialogState extends State<_ConfirmDialog> {
  bool _closed = false;
  late final String _openedAt; // 창이 열린 순간의 주소 (initState에서 기록)

  // 다른 확인 창이 새로 열릴 때 부를 "취소로 닫기"
  void _closeAsCancel() => _close(false);

  String _where() => widget.router.routerDelegate.currentConfiguration.uri.toString();

  @override
  void initState() {
    super.initState();
    _openedAt = _where();
    _closeOpenConfirm = _closeAsCancel;
    // 화면(주소)이 바뀌면 취소로 닫는다. 안 닫으면 떠난 화면의 "삭제"가 다른 화면 위에 남아 눌릴 수 있다.
    widget.router.routerDelegate.addListener(_onRouteChanged);
  }

  @override
  void dispose() {
    widget.router.routerDelegate.removeListener(_onRouteChanged);
    if (_closeOpenConfirm == _closeAsCancel) _closeOpenConfirm = null;
    super.dispose();
  }

  void _onRouteChanged() {
    if (_where() != _openedAt) _close(false);
  }

  // 한 번만 닫는다 (버튼과 주소 변경이 겹쳐도 pop이 두 번 나가지 않게)
  void _close(bool ok) {
    if (_closed) return;
    _closed = true;
    widget.onClose(ok);
  }

  @override
  Widget build(BuildContext context) {
    final danger = widget.danger;
    return Dialog(
      backgroundColor: AppColors.paper,
      insetPadding: const EdgeInsets.symmetric(horizontal: AppSizes.space4),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
        side: const BorderSide(color: AppColors.line),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: Padding(
          padding: const EdgeInsets.all(AppSizes.space5),
          // Esc는 취소 (Dialog 기본 동작에 기대지 않고 명시한다)
          child: CallbackShortcuts(
            bindings: {const SingleActivator(LogicalKeyboardKey.escape): () => _close(false)},
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSizes.space2),
                  child: Text('확인', style: Theme.of(context).textTheme.titleMedium),
                ),
                // 줄바꿈이 있는 문구도 그대로 보인다
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSizes.space5),
                  child: Text(widget.message, style: const TextStyle(height: 1.65)),
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    // 취소를 첫 포커스로 두면 실수로 확인을 누르기 어렵다
                    OutlinedButton(
                      autofocus: true,
                      onPressed: () => _close(false),
                      child: const Text('취소'),
                    ),
                    const SizedBox(width: AppSizes.space2),
                    if (danger)
                      // 위험한 동작: 선·글자가 danger 색 (누르는 동안 danger-strong 배경)
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.danger,
                          side: const BorderSide(color: AppColors.danger),
                        ).copyWith(
                          backgroundColor: WidgetStateProperty.resolveWith((states) {
                            if (states.contains(WidgetState.pressed)) return AppColors.dangerStrong;
                            if (states.contains(WidgetState.hovered)) return AppColors.dangerSoft;
                            return null;
                          }),
                        ),
                        onPressed: () => _close(true),
                        child: Text(widget.confirmLabel),
                      )
                    else
                      FilledButton(
                        onPressed: () => _close(true),
                        child: Text(widget.confirmLabel),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
