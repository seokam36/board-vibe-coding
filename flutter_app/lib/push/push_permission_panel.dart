// "휴대폰 알림" 칸 (S24-e). 비자 일정 화면(학교 인증 회원용)에 끼운다.
// 앱에서만 보인다: 푸시 부품(PushRegistrar)이 끼워진 앱이 아니면(웹 등) 칸 자체가 없다.
// 상태는 화면에 들어올 때, 그리고 앱이 다시 앞으로 돌아올 때(휴대폰 설정에서 허용하고 온 경우) 읽는다.
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../theme/app_colors.dart';
import '../widgets/panel.dart';
import '../widgets/section_parts.dart';
import 'app_push.dart';
import 'push_registrar.dart';

class PushPermissionPanel extends StatelessWidget {
  const PushPermissionPanel({super.key});

  @override
  Widget build(BuildContext context) {
    // 앱 시작 때 한 번 정해지고 안 바뀌어서 read
    // 푸시 부품이 없으면(웹) 아무것도 그리지 않는다
    final registrar = context.read<PushRegistrar?>();
    if (registrar == null) return const SizedBox.shrink();
    return _PushPermissionBody(registrar: registrar);
  }
}

class _PushPermissionBody extends StatefulWidget {
  const _PushPermissionBody({required this.registrar});

  final PushRegistrar registrar;

  @override
  State<_PushPermissionBody> createState() => _PushPermissionBodyState();
}

class _PushPermissionBodyState extends State<_PushPermissionBody> with WidgetsBindingObserver {
  PushPermission? _permission; // null = 아직 모름(읽는 중) 또는 푸시를 못 쓰는 기기 → 칸을 안 그린다
  bool _asked = false; // [알림 켜기]를 눌러 시스템에 물어봤는지 (물었는데도 허용이 아니면 설정 안내)
  bool _busy = false; // 요청 진행 중에는 버튼을 막는다 (두 번 누름 방지)

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _read();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  // 설정 앱에 다녀온 경우를 잡으려고, 앱이 앞으로 돌아올 때 한 번 다시 읽는다
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_busy) _read();
  }

  // 지금 권한 상태만 읽는다 (팝업 없음). 허용이면 서버 등록도 한 번 확인한다 (같은 토큰이면 PUT은 다시 안 나간다)
  Future<void> _read() async {
    final value = await widget.registrar.readPermission();
    if (!mounted) return;
    setState(() => _permission = value);
    if (value == PushPermission.granted) await widget.registrar.registerNow();
  }

  // [알림 켜기]: 시스템 팝업으로 한 번 묻는다. 허용이면 registrar가 바로 서버에 등록한다
  Future<void> _turnOn() async {
    setState(() => _busy = true);
    final value = await widget.registrar.askPermission();
    if (!mounted) return;
    setState(() {
      _busy = false;
      _asked = true;
      _permission = value;
    });
  }

  @override
  Widget build(BuildContext context) {
    final permission = _permission;
    if (permission == null) return const SizedBox.shrink();

    final String message;
    var showButton = false;
    if (permission == PushPermission.granted) {
      message = '알림이 켜져 있어요';
    } else if (_asked) {
      // 물어봤는데도 허용이 아니면 시스템이 더는 안 묻는 경우가 많다 → 설정으로 안내
      message = '휴대폰 설정에서 이 앱의 알림을 허용해 주세요';
    } else {
      // 미결정, 그리고 처음부터 "거부"로 오는 안드로이드도 한 번은 물어볼 수 있게 버튼을 보인다
      message = '비자 만료 120·30·7일 전에 알림을 보내 드려요';
      showButton = true;
    }

    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeading('휴대폰 알림'), // 제목(시맨틱 header + 아래 여백 포함)
          Text(message),
          if (showButton) ...[
            const SizedBox(height: AppSizes.space3),
            FilledButton(
              onPressed: _busy ? null : _turnOn,
              child: const Text('알림 켜기'),
            ),
          ],
        ],
      ),
    );
  }
}
