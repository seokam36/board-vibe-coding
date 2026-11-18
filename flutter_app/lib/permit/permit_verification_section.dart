import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/file_picker.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/section_parts.dart';
import 'permit_apply_form.dart';
import 'permit_pending_view.dart';
import 'permit_status.dart';
import 'permit_texts.dart';

// 화면이 상태를 알아 가는 단계
enum _LoadState { loading, failed, ready }

// 내 정보 화면의 "유학생 인증" 섹션.
// - 인증 회원: 뱃지를 보여 주고 회원 정보만 다시 받는다 (그새 취소됐으면 아래 미인증 화면으로 바뀐다)
// - 미인증 회원: 상태를 1번 물어서 NONE/REJECTED/REVOKED면 신청 폼, PENDING이면 "심사 대기 중"을 보여 준다
// 요청을 보내고 상태가 바뀌므로 StatefulWidget이다.
class PermitVerificationSection extends StatefulWidget {
  const PermitVerificationSection({super.key});

  @override
  State<PermitVerificationSection> createState() =>
      _PermitVerificationSectionState();
}

class _PermitVerificationSectionState extends State<PermitVerificationSection> {
  _LoadState _loadState = _LoadState.loading;
  ErrorInfo? _loadError; // 상태를 못 불러온 이유
  PermitStatus? _status; // 서버가 알려 준 내 인증 상태

  bool _consent = false; // 동의 체크
  PickedPhoto? _photo; // 고른 사진 한 장
  String? _checkMessage; // 사전 검사에서 거른 이유
  bool _submitting = false; // 신청 요청 중 (이 동안 컨트롤을 잠그고 연타를 막는다)
  ErrorInfo? _submitError; // 신청이 실패한 이유 (NeedLogin이면 로그인 안내)
  bool _removing = false; // 철회·해제 요청 중 (이 동안 버튼을 잠근다)
  ErrorInfo? _removeError; // 철회·해제가 실패한 이유 (NeedLogin이면 로그인 안내)

  @override
  void initState() {
    super.initState();
    // 미인증이면 상태를 한 번 묻는다. 인증 회원은 그새 취소됐을 수 있어 회원 정보를 다시 받는다
    // (취소됐으면 학교가 사라져 뱃지가 없어지고, build가 상태를 물어 취소 안내를 보여 준다)
    final auth = context.read<AuthState>();
    if (auth.user?.verifiedSchool == null) {
      _fetch();
    } else {
      _loadState = _LoadState.ready;
      auth.refreshUser();
    }
  }

  @override
  void dispose() {
    _photo?.release(); // 미리보기 주소를 반납한다
    super.dispose();
  }

  // 내 인증 상태를 서버에 묻는다 (GET). quiet면 실패해도 화면을 "불러오기 실패"로 바꾸지 않는다
  // (신청 실패 뒤의 확인용: 이미 폼이 있으니 그대로 둔다)
  // 돌려주는 값: 상태를 받아 반영했으면 true, 실패했으면 false
  Future<bool> _fetch({bool quiet = false}) async {
    try {
      final res = await context.read<ApiClient>().dio.get<Map<String, dynamic>>(
        '/users/me/permit-verification',
      );
      if (!mounted) return false;
      final status = PermitStatus.fromJson(res.data!);
      await _applyStatus(status);
      if (!mounted) return false;
      setState(() => _loadState = _LoadState.ready);
      return true;
    } catch (e) {
      if (!mounted || quiet) return false;
      setState(() {
        _loadState = _LoadState.failed;
        _loadError = describeError(e, const {}, permitLoadFailed);
      });
      return false;
    }
  }

  // [다시 시도]
  void _retry() {
    setState(() => _loadState = _LoadState.loading);
    _fetch();
  }

  // 받은 상태를 화면에 반영한다. 승인인데 회원 정보에 학교가 없으면(로그인한 채로 승인된 경우) 회원 정보를 다시 받는다
  Future<void> _applyStatus(PermitStatus status) async {
    final auth = context.read<AuthState>();
    if (status.status == 'APPROVED' && auth.user?.verifiedSchool == null) {
      await auth.refreshUser(); // 끝나면 AuthState가 바뀌어 뱃지(상단바 "인증" 포함)가 나타난다
      if (!mounted) return;
    }
    setState(() {
      _status = status;
      // 대기·승인이 되면 신청 폼은 사라지니 쓰던 입력과 지난 오류를 치운다
      if (status.status == 'PENDING' || status.status == 'APPROVED') {
        _submitError = null;
        _resetForm();
      }
    });
  }

  void _resetForm() {
    _photo?.release();
    _photo = null;
    _consent = false;
    _checkMessage = null;
  }

  // [사진 고르기]: 한 장만 고르는 파일 창을 연다
  void _openPicker() => photoPicker.pick(_onPicked, multiple: false);

  void _onPicked(List<PickedPhoto> files) {
    // 화면을 떠났거나 신청 중이면 받지 않는다 (미리보기 주소는 반납)
    if (!mounted || _submitting) {
      for (final f in files) {
        f.release();
      }
      return;
    }
    if (files.isEmpty) return;
    final picked = files.first;
    for (final extra in files.skip(1)) {
      extra.release(); // 한 장만 쓴다
    }
    // 사전 검사: 종류가 빈 글자면(브라우저가 모름) 통과시킨다. 진짜 판단은 서버가 파일 내용으로 한다
    final problem = checkPickedPhoto(picked);
    setState(() {
      _checkMessage = problem;
      if (problem == null) {
        _photo?.release(); // 이전에 고른 사진은 버린다
        _photo = picked;
      }
    });
    if (problem != null) picked.release();
  }

  // [인증 신청]: 사진과 동의를 multipart로 보낸다
  Future<void> _submit() async {
    final photo = _photo;
    // 버튼이 잠겨 있어도 한 번 더 막는다 (연타해도 요청은 1번)
    if (_submitting || !_consent || photo == null) return;
    setState(() {
      _submitting = true;
      _submitError = null;
      _removeError = null; // 철회·해제 때의 옛 안내(404 등)가 다음 화면에 남지 않게 비운다
    });
    final api = context.read<ApiClient>();
    var recheck = false; // 실패 뒤 서버 상태를 다시 확인할지
    try {
      final bytes = await photo.readBytes(); // 보내기 직전에 한 번만 읽는다
      // FormData는 한 번 보내면 소진된다. 401 재시도 때 ApiClient가 새로 만들 수 있게 "만드는 함수"로 둔다
      FormData make() => FormData.fromMap({
        'file': MultipartFile.fromBytes(
          bytes,
          filename: photo.name,
          contentType: photo.type.isEmpty
              ? null
              : DioMediaType.parse(photo.type),
        ),
        'consent': 'true',
      });
      final res = await api.dio.post<Map<String, dynamic>>(
        '/users/me/permit-verification',
        data: make(),
        // receiveTimeout: 응답이 없으면 무한정 기다리지 않는다 (웹에서는 요청 전체 제한 시간으로 쓰인다)
        options: Options(
          receiveTimeout: permitSubmitTimeout,
          extra: {ApiClient.formDataFactoryKey: make},
        ),
      );
      if (!mounted) return;
      await _applyStatus(PermitStatus.fromJson(res.data!));
    } catch (e) {
      if (!mounted) return;
      ErrorInfo info;
      if (isTimeoutError(e)) {
        // 서버가 이미 저장했을 수 있다 → 상태를 다시 확인
        info = const ErrorMessage(permitTimeoutMessage, null);
        recheck = true;
      } else {
        info = describeError(e, permitSubmitErrors, permitSubmitFailed);
        recheck = getStatus(e) == 409; // 이미 신청·승인된 상태일 수 있다 → 다시 확인
      }
      setState(() => _submitError = info);
    }
    if (!mounted) return;
    if (recheck) {
      final ok = await _fetch(quiet: true);
      if (!mounted) return;
      final s = _status?.status;
      // 대기·승인이면 상태 화면이 이미 보인다. 아니면 확인 결과를 문구로 알린다
      if (!ok) {
        setState(
          () => _submitError = const ErrorMessage(permitRecheckFailed, null),
        );
      } else if (s == 'NONE' || s == 'REJECTED' || s == 'REVOKED') {
        setState(
          () => _submitError = const ErrorMessage(permitNotReceived, null),
        );
      }
    }
    if (!mounted) return;
    // 요청이 끝났으니 컨트롤을 다시 푼다 (실패했으면 [인증 신청]을 다시 누를 수 있다)
    setState(() => _submitting = false);
  }

  // [신청 철회] / [인증 해제]: 확인 창 → DELETE → 204면 상태를 처음부터 다시 불러온다
  Future<void> _remove({
    required String message,
    required String confirmLabel,
  }) async {
    if (_removing) return; // 연타 방지
    final ok = await showConfirm(
      context,
      message: message,
      confirmLabel: confirmLabel,
      danger: true,
    );
    if (!ok || !mounted) return; // 취소하면 요청을 보내지 않는다
    final hadSchool = context.read<AuthState>().user?.verifiedSchool != null;
    setState(() {
      _removing = true;
      _removeError = null;
    });
    final api = context.read<ApiClient>();
    var reload = false; // 성공·이미 처리된 상태(404)·시간 초과면 상태를 다시 불러온다
    var succeeded = false; // 204로 성공했는지
    try {
      await api.dio.delete<void>(
        '/users/me/permit-verification',
        options: Options(receiveTimeout: permitRemoveTimeout),
      );
      reload = true;
      succeeded = true;
    } catch (e) {
      if (!mounted) return;
      final info = describeError(e, permitRemoveErrors, permitRemoveFailed);
      // 시간 초과는 서버가 이미 지웠을 수도 있어서 상태를 다시 확인한다 (신청 POST와 같은 방식)
      reload = getStatus(e) == 404 || isTimeoutError(e);
      setState(() => _removeError = info);
    }
    if (!mounted) return;
    if (reload) {
      // 낡은 상태(대기·승인)를 지우고 "불러오는 중"으로 돌려 둔다. 그래야 인증이 풀린 뒤
      // build가 중복으로 묻지 않고, 다시 받은 상태(NONE)로 신청 폼이 바로 나온다
      setState(() {
        _status = null;
        _loadState = _LoadState.loading;
      });
      // 인증 회원이었다면 회원 정보를 갱신해 뱃지를 지운다 (대기 중 철회에는 뱃지가 없어 생략)
      if (hadSchool) {
        await context.read<AuthState>().refreshUser();
        if (!mounted) return;
      }
      await _fetch();
      if (!mounted) return;
      // 해제는 성공했는데 회원 정보 갱신이 조용히 실패해 뱃지가 남았으면 새로고침을 안내한다
      if (succeeded &&
          hadSchool &&
          context.read<AuthState>().user?.verifiedSchool != null) {
        setState(
          () => _removeError = const ErrorMessage(permitUnlinkReload, null),
        );
      }
    }
    setState(() => _removing = false);
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final school = user?.verifiedSchool;
    // 인증 회원으로 시작했다가 인증이 풀려(학교가 사라져) 아직 상태를 모르면, 그때 한 번 묻는다.
    // 빌드 중에는 setState를 못 하니 값만 바꾸고 묻기는 그리기가 끝난 뒤로 미룬다
    // 단, 철회·해제 중 로그인이 풀린 경우(NeedLogin)는 묻지 않는다: 물어 봐야 실패해서 로그인 안내만 가려진다
    // 로그인이 풀렸으면(user == null) 묻지 않는다: 물어 봐야 실패해서 [다시 시도]만 뜬다 (아래 _unverified가 로그인 안내를 보여 준다)
    if (user != null &&
        school == null &&
        _status == null &&
        _loadState == _LoadState.ready &&
        _removeError is! NeedLogin) {
      _loadState = _LoadState.loading;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _fetch();
      });
    }
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeading('유학생 인증'),
          if (school != null) ...[
            // 이미 인증했으면 뱃지와 안내만
            AppBadge(
              text: '유학생 인증 완료 · ${school.name}',
              ink: AppColors.celadonInk,
              bg: AppColors.celadonSoft,
            ),
            const FieldHint('졸업 후에도 유지돼요'),
            ...permitRemoveNotice(_removeError),
            OutlinedButton(
              key: const ValueKey('permit-unlink-button'),
              onPressed: _removing
                  ? null
                  : () => _remove(
                      message: permitUnlinkConfirm,
                      confirmLabel: '해제',
                    ),
              child: const Text('인증 해제'),
            ),
          ] else
            ..._unverified(),
        ],
      ),
    );
  }

  // 미인증 회원이 보는 내용
  List<Widget> _unverified() {
    // 해제 중 세션이 만료되면 학교 정보가 비어 이 화면으로 넘어온다. 로그인 안내를 그대로 보여 준다
    if (_removeError is NeedLogin) return const [ReloginNotice()];
    switch (_loadState) {
      case _LoadState.loading:
        return const [Loading()];
      case _LoadState.failed:
        final error = _loadError;
        return [
          if (error is NeedLogin)
            const ReloginNotice()
          else
            Notice(
              kind: NoticeKind.error,
              message: error is ErrorMessage ? error.message : permitLoadFailed,
            ),
          OutlinedButton(onPressed: _retry, child: const Text('다시 시도')),
        ];
      case _LoadState.ready:
        final status = _status;
        if (status == null) return const [];
        if (status.status == 'PENDING') {
          return [
            PermitPendingView(
              status: status,
              removeError: _removeError,
              removing: _removing,
              onWithdraw: () =>
                  _remove(message: permitWithdrawConfirm, confirmLabel: '철회'),
            ),
          ];
        }
        if (status.status == 'APPROVED') {
          // 승인됐는데 회원 정보 갱신이 실패한 드문 경우
          return const [
            Notice(kind: NoticeKind.info, message: permitApprovedReload),
          ];
        }
        return [
          ...permitRemoveNotice(_removeError), // 404(이미 처리됨) 안내가 신청 폼 위에 남도록
          if (status.status == 'REJECTED')
            PermitRejectedNotice(rejectReason: status.rejectReason),
          // 취소된 회원: 안내 + 사유 + 신청 폼 ([철회]·[인증 해제]는 없다)
          if (status.status == 'REVOKED')
            PermitRevokedNotice(rejectReason: status.rejectReason),
          PermitApplyForm(
            consent: _consent,
            photo: _photo,
            submitting: _submitting,
            checkMessage: _checkMessage,
            error: _submitError,
            onConsentChanged: (v) => setState(() => _consent = v),
            onPickPhoto: _openPicker,
            onSubmit: _submit,
          ),
        ];
    }
  }
}
