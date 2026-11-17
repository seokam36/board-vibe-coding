import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../admin/permit_models.dart';
import '../admin/report_meta_row.dart';
import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/school_verification_section.dart' show permitRejectReasons;
import '../widgets/small_button_style.dart';

const _listFailedMessage = '인증 신청 목록을 불러오지 못했습니다';
const _photoFailedMessage = '사진을 불러오지 못했어요';
const _photoGoneMessage = '사진이 없어요(이미 처리되었을 수 있어요)';
const _photoDecodeFailed = '사진을 표시할 수 없어요';
const _requestTimeout = Duration(seconds: 30); // 요청마다 거는 응답 제한

// 탭: (서버 status 값, 탭 글자, 빈 목록 안내)
const _tabs = [
  ('PENDING', '대기', '대기 중인 신청이 없어요'),
  ('APPROVED', '승인', '승인된 신청이 없어요'),
  ('REJECTED', '반려', '반려된 신청이 없어요'),
];

// 관리자 "인증 심사" 화면 "/admin/permits": 대기/승인/반려 탭 + 신청 목록 + 사진 보기.
// 승인·반려 버튼은 아직 없다(다음 단계). 관리자가 아니면 안내만 보여 주고 API는 부르지 않는다.
class AdminPermitsPage extends StatefulWidget {
  const AdminPermitsPage({super.key});

  @override
  State<AdminPermitsPage> createState() => _AdminPermitsPageState();
}

class _AdminPermitsPageState extends State<AdminPermitsPage> {
  String _status = 'PENDING';
  List<AdminPermitRequest>? _items; // null이면 아직 못 불러온 상태
  String? _loadError;
  bool _needLogin = false;
  bool _started = false;
  int _seq = 0; // 마지막 요청의 응답만 반영한다

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 관리자가 되는 순간 첫 조회. 관리자가 아니면 요청을 보내지 않는다
    final admin = Provider.of<AuthState>(context).user?.isAdmin ?? false;
    if (admin && !_started) {
      _started = true;
      _fetch();
    } else if (!admin) {
      _started = false;
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    super.dispose();
  }

  Future<void> _fetch() async {
    final seq = ++_seq;
    try {
      final res = await context.read<ApiClient>().dio.get<List<dynamic>>(
        '/admin/permit-requests',
        queryParameters: {'status': _status},
        options: Options(receiveTimeout: _requestTimeout),
      );
      if (!mounted || seq != _seq) return;
      final list = [for (final e in res.data!) AdminPermitRequest.fromJson(e as Map<String, dynamic>)];
      setState(() {
        _items = list;
        _loadError = null;
        _needLogin = false;
      });
    } catch (e) {
      debugPrint('인증 신청 목록 불러오기 실패: $e');
      if (!mounted || seq != _seq) return;
      final info = describeError(e, {403: ErrorMessages.adminOnly}, _listFailedMessage);
      setState(() {
        _items = null;
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _loadError = info.message;
        }
      });
    }
  }

  void _changeTab(String next) {
    if (next == _status) return;
    setState(() {
      _status = next;
      _items = null;
      _loadError = null;
      _needLogin = false; // 이전 탭의 로그인 안내가 남아 빈 화면이 되지 않게 한다
    });
    _fetch();
  }

  // 사진 요청이 401(재발급도 실패)이라 로그아웃되어 이 줄이 사라진 경우에만 화면이 대신 로그인 안내를 켠다
  void _onPhotoNeedLogin() {
    if (mounted) setState(() => _needLogin = true);
  }

  void _retry() {
    setState(() => _loadError = null);
    _fetch();
  }

  @override
  Widget build(BuildContext context) {
    final admin = context.watch<AuthState>().user?.isAdmin ?? false;
    if (!admin) {
      return PageFrame(
        child: _needLogin
            ? const ReloginNotice()
            : const Notice(kind: NoticeKind.error, message: ErrorMessages.adminOnly),
      );
    }
    final items = _items;
    final loadError = _loadError;
    final emptyText = _tabs.firstWhere((t) => t.$1 == _status).$3;
    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('인증 심사'),
          Container(
            margin: const EdgeInsets.only(bottom: AppSizes.space4),
            decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line))),
            child: Wrap(
              spacing: AppSizes.space4,
              children: [
                for (final t in _tabs)
                  _PermitTab(label: t.$2, selected: _status == t.$1, onTap: () => _changeTab(t.$1)),
              ],
            ),
          ),
          if (loadError != null)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  Text(loadError),
                  // 권한 문제(403)는 다시 해도 같으니 버튼을 숨긴다
                  if (loadError != ErrorMessages.adminOnly)
                    OutlinedButton(style: smallButtonStyle, onPressed: _retry, child: const Text('다시 시도')),
                ],
              ),
            ),
          if (items == null && loadError == null && !_needLogin) const Loading(),
          if (items != null)
            FlushPanel(
              children: [
                if (items.isEmpty) PanelState(child: Text(emptyText, style: const TextStyle(color: AppColors.muted))),
                for (var i = 0; i < items.length; i++)
                  _PermitRow(
                    key: ValueKey('permit-${items[i].id}'),
                    item: items[i],
                    first: i == 0,
                    onNeedLogin: _onPhotoNeedLogin,
                  ),
              ],
            ),
          if (_needLogin) const ReloginNotice(),
        ],
      ),
    );
  }
}

// 탭 하나 (신고 관리 화면 탭과 같은 모양)
class _PermitTab extends StatelessWidget {
  const _PermitTab({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.celadon : AppColors.muted;
    return Semantics(
      button: true,
      selected: selected,
      child: InkWell(
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minHeight: 40),
          padding: const EdgeInsets.symmetric(horizontal: AppSizes.space1),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: selected ? AppColors.celadon : Colors.transparent, width: 2)),
          ),
          child: Center(
            widthFactor: 1,
            child: Text(
              label,
              style: TextStyle(
                fontSize: AppSizes.fsBody,
                color: color,
                fontWeight: selected ? FontWeight.w500 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// 신청 한 줄: 닉네임(+자동 배지) / 메타 / [사진 보기] / 펼친 사진
class _PermitRow extends StatefulWidget {
  const _PermitRow({super.key, required this.item, required this.first, required this.onNeedLogin});

  final AdminPermitRequest item;
  final bool first;
  final VoidCallback onNeedLogin; // 로그인이 풀렸음을 부모 화면에 알린다

  @override
  State<_PermitRow> createState() => _PermitRowState();
}

class _PermitRowState extends State<_PermitRow> {
  bool _open = false; // 사진이 펼쳐졌는가
  bool _loading = false;
  MemoryImage? _image; // 사진 (메모리에만 두고 파일에 저장하지 않는다)
  String? _error;
  bool _needLogin = false; // 줄이 남아 있는 채로 로그인이 풀린 경우, 사진 칸에 안내
  CancelToken? _cancel; // 접기·화면 이탈 때 진행 중인 요청을 끊는다
  AuthState? _auth; // dispose 때 로그아웃 여부를 보려고 잡아 둔다
  int _seq = 0;

  @override
  void dispose() {
    _seq++;
    // 사진 칸에 로그인 안내를 띄운 직후 로그아웃으로 줄이 사라지면, 안내가 같이 사라지므로 부모 화면이 대신 보여 준다
    if (_needLogin && _auth?.user == null) {
      final onNeedLogin = widget.onNeedLogin;
      Future.microtask(onNeedLogin); // 지금은 화면을 정리하는 중이라 setState를 미룬다
    }
    _cancel?.cancel();
    _discardPhoto(); // 화면을 떠나도 사진이 메모리에 남지 않게 한다
    super.dispose();
  }

  // 사진을 버린다: 전역 이미지 캐시에서도 지워야 메모리에서 사라진다
  void _discardPhoto() {
    _image?.evict();
    _image = null;
  }

  Future<void> _toggle() async {
    if (_open) {
      // 접기: 진행 중인 요청 결과는 버린다
      _cancel?.cancel();
      _discardPhoto();
      setState(() {
        _open = false;
        _loading = false;
        _error = null;
        _needLogin = false;
        _seq++;
      });
      return;
    }
    setState(() {
      _open = true;
      _loading = true;
      _error = null;
      _needLogin = false;
    });
    final seq = ++_seq;
    final cancel = _cancel = CancelToken();
    _auth = context.read<AuthState>();
    final onNeedLogin = widget.onNeedLogin; // 줄이 사라진 뒤에도 쓸 수 있게 미리 잡아 둔다
    try {
      final res = await context.read<ApiClient>().dio.get<List<int>>(
        '/admin/permit-requests/${widget.item.id}/photo',
        options: Options(responseType: ResponseType.bytes, receiveTimeout: _requestTimeout),
        cancelToken: cancel,
      );
      if (!mounted || seq != _seq) return;
      setState(() {
        final data = res.data!;
        _image = MemoryImage(data is Uint8List ? data : Uint8List.fromList(data)); // 이미 Uint8List면 복사하지 않는다
        _loading = false;
      });
    } catch (e) {
      debugPrint('허가서 사진 불러오기 실패: $e');
      final info = describeError(e, {403: ErrorMessages.adminOnly, 404: _photoGoneMessage}, _photoFailedMessage);
      // 로그아웃으로 줄이 이미 사라졌으면(mounted false) 로그인 안내는 부모 화면이 한 번만 보여 준다
      if (!mounted && info is NeedLogin) onNeedLogin();
      if (!mounted || seq != _seq) return;
      setState(() {
        _loading = false;
        // 줄이 남아 있으면 안내는 사진 칸에만 띄운다(부모 안내와 겹치지 않게)
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    final detected = it.detectedSchoolName;
    final reason = it.rejectReason;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
      decoration: BoxDecoration(border: widget.first ? null : const Border(top: BorderSide(color: AppColors.line))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(it.nickname, style: const TextStyle(color: AppColors.ink, fontWeight: FontWeight.w500)),
              if (it.autoDecided) const AppBadge(text: '자동'),
            ],
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSizes.space2),
            child: Column(
              children: [
                ReportMetaRow(label: '신청 시각', value: formatDate(it.createdAt)),
                ReportMetaRow(label: '기계 결과', value: machineResultText(it.machineResult)),
                if (detected != null) ReportMetaRow(label: '찾은 학교', value: detected),
                if (it.status == 'APPROVED' && it.schoolName != null)
                  ReportMetaRow(label: '승인 학교', value: it.schoolName!),
                if (it.status == 'REJECTED')
                  ReportMetaRow(label: '반려 사유', value: reason == null ? '-' : (permitRejectReasons[reason] ?? reason)),
              ],
            ),
          ),
          if (it.hasPhoto)
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(
                style: smallButtonStyle,
                onPressed: _toggle,
                child: Text(_open ? '사진 접기' : '사진 보기'),
              ),
            ),
          if (_open) _buildPhoto(),
        ],
      ),
    );
  }

  Widget _buildPhoto() {
    if (_loading) return const Loading(prefix: '사진');
    if (_needLogin) return const ReloginNotice();
    final error = _error;
    if (error != null) return Notice(kind: NoticeKind.error, message: error);
    final image = _image;
    if (image == null) return const SizedBox.shrink();
    // 원래 크기보다 커지지 않고, 높이는 화면의 80%까지만 (큰 사진이 화면을 덮지 않게)
    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space2),
      child: Align(
        alignment: Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
          child: Image(
            image: image,
            fit: BoxFit.contain,
            semanticLabel: '허가서 사진',
            errorBuilder: (context, error, stack) => const Notice(kind: NoticeKind.error, message: _photoDecodeFailed),
          ),
        ),
      ),
    );
  }
}
