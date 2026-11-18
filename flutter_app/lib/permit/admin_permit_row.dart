import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../admin/report_meta_row.dart';
import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../posts/models.dart' show School;
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/small_button_style.dart';
import 'admin_permit_models.dart';
import 'admin_permit_texts.dart';
import 'permit_approve_form.dart';
import 'permit_reject_form.dart';
import 'permit_reject_reasons.dart' show permitRejectReasons;

// 신청 한 줄: 닉네임(+자동 배지) / 메타 / [사진 보기] / 펼친 사진
class AdminPermitRow extends StatefulWidget {
  const AdminPermitRow({
    super.key,
    required this.item,
    required this.first,
    required this.myId,
    required this.enabled,
    required this.onReject,
    required this.onApprove,
    required this.onRevoke,
    required this.error,
    required this.onClearError,
    required this.schools,
    required this.schoolsFailed,
    required this.onOpenApprove,
    required this.onNeedLogin,
    required this.onPhotoGone,
  });

  final AdminPermitRequest item;
  final bool first;
  final int? myId; // 로그인한 관리자 id
  final bool enabled; // 처리 중이 아닐 때만 처리 버튼을 누를 수 있다
  final Future<bool> Function(AdminPermitRequest item, String reason) onReject; // 성공하면 true
  final Future<bool> Function(AdminPermitRequest item, int schoolId, String permitNumber) onApprove;
  final Future<bool> Function(AdminPermitRequest item, String reason) onRevoke; // 인증 취소 (승인 탭 줄)
  final String? error; // 이 줄의 처리 실패 안내 (부모가 한 군데에 들고 있다가 내려 준다)
  final VoidCallback onClearError; // 양식을 닫거나 다른 양식을 열 때 부모의 안내를 지운다
  final List<School>? schools; // 승인 양식의 학교 목록 (화면이 한 번만 불러온다)
  final bool schoolsFailed;
  final VoidCallback onOpenApprove; // 승인 양식을 열 때 학교 목록 불러오기를 요청한다 (이미 있으면 아무 일 없음)
  final VoidCallback onPhotoGone; // 사진이 404면 부모가 목록을 다시 읽게 알린다
  final VoidCallback onNeedLogin; // 로그인이 풀렸음을 부모 화면에 알린다

  @override
  State<AdminPermitRow> createState() => _AdminPermitRowState();
}

class _AdminPermitRowState extends State<AdminPermitRow> {
  bool _rejectOpen = false; // 반려 양식이 펼쳐졌는가
  bool _revokeOpen = false; // 인증 취소 양식이 펼쳐졌는가 (다른 양식과 동시에 열리지 않는다)
  bool _approveOpen = false; // 승인 양식이 펼쳐졌는가 (반려 양식과 동시에 열리지 않는다)
  bool _open = false; // 사진이 펼쳐졌는가
  bool _loading = false;
  MemoryImage? _image; // 사진 (메모리에만 두고 파일에 저장하지 않는다)
  String? _error;
  bool _needLogin = false; // 줄이 남아 있는 채로 로그인이 풀린 경우, 사진 칸에 안내
  CancelToken? _cancel; // 접기·화면 이탈 때 진행 중인 요청을 끊는다
  AuthState? _auth; // dispose 때 로그아웃 여부를 보려고 잡아 둔다
  int _seq = 0;

  // 양식을 하나만 연다: 다른 양식은 닫고 이전 실패 안내는 지운다
  void _openForm({bool approve = false, bool revoke = false}) {
    setState(() {
      _approveOpen = approve;
      _revokeOpen = revoke;
      _rejectOpen = !approve && !revoke;
    });
    widget.onClearError();
    if (approve) widget.onOpenApprove();
  }

  void _closeForms() {
    setState(() {
      _approveOpen = false;
      _revokeOpen = false;
      _rejectOpen = false;
    });
    widget.onClearError();
  }

  // 처리가 성공이면 양식을 닫는다 (실패 안내는 부모가 error로 내려 준다)
  void _applyResult(bool success) {
    if (mounted && success) _closeForms();
  }

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
    final onPhotoGone = widget.onPhotoGone;
    try {
      final res = await context.read<ApiClient>().dio.get<List<int>>(
        '/admin/permit-requests/${widget.item.id}/photo',
        options: Options(responseType: ResponseType.bytes, receiveTimeout: adminPermitTimeout),
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
      final info = describeError(e, {403: ErrorMessages.adminOnly, 404: permitPhotoGone}, permitPhotoFailed);
      // 로그아웃으로 줄이 이미 사라졌으면(mounted false) 로그인 안내는 부모 화면이 한 번만 보여 준다
      if (!mounted && info is NeedLogin) onNeedLogin();
      // 사진이 없다면 신청이 철회·처리됐을 수 있으니 목록을 다시 읽게 알린다
      if (info is ErrorMessage && info.status == 404) onPhotoGone();
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
      decoration: BoxDecoration(
        border: widget.first ? null : const Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                it.nickname,
                style: const TextStyle(color: AppColors.ink, fontWeight: FontWeight.w500),
              ),
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
                if ((it.status == 'APPROVED' || it.status == 'REVOKED') && it.schoolName != null)
                  ReportMetaRow(label: '승인 학교', value: it.schoolName!),
                if (it.status == 'REJECTED' || it.status == 'REVOKED')
                  ReportMetaRow(
                    label: it.status == 'REVOKED' ? '취소 사유' : '반려 사유',
                    value: reason == null ? '-' : (permitRejectReasons[reason] ?? reason),
                  ),
              ],
            ),
          ),
          Wrap(
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (it.hasPhoto)
                OutlinedButton(style: smallButtonStyle, onPressed: _toggle, child: Text(_open ? '사진 접기' : '사진 보기')),
              // 처리 버튼은 대기 탭의 남의 신청에만. 본인 신청은 안내만 (서버도 403으로 막는다)
              if (it.status == 'PENDING' && it.userId != widget.myId && !_approveOpen)
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: widget.enabled ? () => _openForm(approve: true) : null,
                  child: const Text('승인'),
                ),
              if (it.status == 'PENDING' && it.userId != widget.myId && !_rejectOpen)
                OutlinedButton(
                  style: dangerSmallButtonStyle,
                  onPressed: widget.enabled ? () => _openForm(approve: false) : null,
                  child: const Text('반려'),
                ),
              // 승인 탭의 남의 줄에만 [인증 취소] (본인 건은 서버도 403)
              if (it.status == 'APPROVED' && it.userId != widget.myId && !_revokeOpen)
                OutlinedButton(
                  style: dangerSmallButtonStyle,
                  onPressed: widget.enabled ? () => _openForm(revoke: true) : null,
                  child: const Text('인증 취소'),
                ),
              if (it.status == 'PENDING' && it.userId == widget.myId)
                const Text(
                  permitOwnRequest,
                  style: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                ),
            ],
          ),
          if (_open) _buildPhoto(),
          if (_rejectOpen)
            PermitRejectForm(
              enabled: widget.enabled,
              error: widget.error,
              onCancel: _closeForms,
              onSubmit: (reason) async => _applyResult(await widget.onReject(it, reason)),
            ),
          if (_revokeOpen)
            PermitRejectForm(
              revoke: true,
              enabled: widget.enabled,
              error: widget.error,
              onCancel: _closeForms,
              onSubmit: (reason) async => _applyResult(await widget.onRevoke(it, reason)),
            ),
          if (_approveOpen)
            PermitApproveForm(
              enabled: widget.enabled,
              schools: widget.schools,
              schoolsFailed: widget.schoolsFailed,
              detectedSchoolId: it.detectedSchoolId,
              error: widget.error,
              onRetrySchools: widget.onOpenApprove,
              onCancel: _closeForms,
              onSubmit: (schoolId, number) async => _applyResult(await widget.onApprove(it, schoolId, number)),
            ),
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
            errorBuilder: (context, error, stack) =>
                const Notice(kind: NoticeKind.error, message: permitPhotoDecodeFailed),
          ),
        ),
      ),
    );
  }
}
