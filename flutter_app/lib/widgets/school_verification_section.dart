import 'package:dio/dio.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/file_picker.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../market/photo_upload.dart' show maxPhotoBytes;
import '../theme/app_colors.dart';
import 'app_badge.dart';
import 'loading.dart';
import 'notice.dart';
import 'panel.dart';
import 'relogin_notice.dart';
import 'section_parts.dart';

// ---- 문구 (화면에 보이는 글자는 여기 한 곳에 모은다) ----

// 사진을 수집하는 것에 대한 동의 문구 (체크박스 옆에 그대로 보인다)
const permitConsentText =
    '표준입학허가서 사진을 유학생·학교 확인 목적으로 수집합니다. 사진은 심사가 끝나면 바로 지우고, 늦어도 14일 뒤 삭제합니다. '
    '허가서 번호는 중복 확인용 암호화 값(해시)만 보관합니다. 사진은 글자 확인을 위해 Google Cloud Vision으로 전송될 수 있습니다. '
    '언제든 철회할 수 있습니다.';

// 사진에서 가려야 할 것 / 보이게 둘 것 (두 줄로 나눠 보여 준다)
const permitMaskHide = '가려 주세요: 여권번호, 생년월일, 이메일, 재정 금액';
const permitMaskShow = '보이게 두세요: 국적, 학교명, 표준입학허가서 번호';

// 반려 사유 코드 → 안내 문구 (서버 코드 7개. 모르는 코드는 OTHER 문구)
const permitRejectReasons = <String, String>{
  'BLURRY': '사진이 흐려서 글자를 읽을 수 없어요',
  'NOT_PERMIT': '표준입학허가서가 아니에요',
  'NOT_MASKED': '가려야 할 정보가 보여요. 가리고 다시 올려 주세요',
  'UNSUPPORTED_SCHOOL': '아직 지원하지 않는 학교예요',
  'DUPLICATE': '이미 다른 계정에서 인증한 허가서예요',
  'EXPIRED': '14일 안에 확인하지 못했어요. 다시 신청해 주세요',
  'OTHER': '확인할 수 없었어요. 다시 신청해 주세요',
};

// 신청(POST) 실패 상태코드별 문구 (401은 표에 두지 않는다 → describeError가 로그인 안내로 바꾼다)
const permitSubmitErrors = <int, String>{
  400: '사진을 확인해 주세요. 동의에 체크하고 JPEG·PNG 사진(10MB 이하)인지, 손상되지 않았는지 봐 주세요',
  403: '신청하지 못했어요. 새로고침 후 다시 시도해 주세요',
  409: '이미 인증됐거나 심사를 기다리는 신청이 있어요',
  413: '사진은 10MB 이하여야 해요',
  429: '오늘은 신청을 3번 다 썼어요. 내일 다시 신청해 주세요',
  503: '서버가 바빠요. 잠시 뒤 다시 시도해 주세요',
};
const permitSubmitFailed = '신청하지 못했어요. 잠시 뒤 다시 시도해 주세요';
// 시간 초과: 서버가 이미 접수했을 수도 있어서, 상태를 다시 확인한다고 알린다
const permitTimeoutMessage = '시간이 오래 걸려 멈췄어요. 접수됐는지 상태를 다시 확인할게요';
const permitProgressMessage = '확인 중이에요. 최대 몇십 초 걸릴 수 있어요';
const permitLoadFailed = '인증 상태를 불러오지 못했어요';
// 사전 검사(브라우저에서 미리 거르는 것)
const permitTypeOnly = '사진은 JPEG·PNG만 올릴 수 있어요';
const permitTooLarge = '사진은 10MB 이하여야 해요';
// 로그인 중에 승인됐는데 회원 정보를 아직 못 받았을 때
const permitApprovedReload = '인증이 승인됐어요. 새로고침하면 인증 표시가 나타나요';
// 실패 뒤 상태를 다시 확인해 봤을 때의 결과 문구
const permitNotReceived = '접수되지 않았어요. 다시 신청해 주세요';
const permitRecheckFailed = '접수됐는지 확인하지 못했어요. 새로고침해서 확인해 주세요';

// 신청 요청의 제한 시간 (2분). 웹에서는 업로드를 포함한 요청 전체의 제한 시간으로 쓰인다
// (dio 웹 어댑터가 xhr.timeout = connectTimeout + receiveTimeout 으로 건다).
// 서버가 사진 글자를 읽는 데 최대 약 10초 걸리니 넉넉히 둔다
const permitSubmitTimeout = Duration(minutes: 2);

// 서버가 알려 주는 "내 인증 상태" (GET·POST 응답 공통). status: NONE / PENDING / REJECTED / APPROVED
class PermitStatus {
  const PermitStatus({
    required this.status,
    this.rejectReason,
    this.requestedAt,
  });

  final String status;
  final String? rejectReason;
  final String? requestedAt;

  factory PermitStatus.fromJson(Map<String, dynamic> json) => PermitStatus(
    status: json['status'] as String,
    rejectReason: json['rejectReason'] as String?,
    requestedAt: json['requestedAt'] as String?,
  );
}

// 화면이 상태를 알아 가는 단계
enum _LoadState { loading, failed, ready }

// 내 정보 화면의 "유학생 인증" 섹션.
// - 인증 회원: 서버에 묻지 않고 뱃지만 보여 준다
// - 미인증 회원: 상태를 1번 물어서 NONE/REJECTED면 신청 폼, PENDING이면 "심사 대기 중"을 보여 준다
// 요청을 보내고 상태가 바뀌므로 StatefulWidget이다 (인증 여부 자체는 AuthState에서 읽는다).
class SchoolVerificationSection extends StatefulWidget {
  const SchoolVerificationSection({super.key});

  @override
  State<SchoolVerificationSection> createState() =>
      _SchoolVerificationSectionState();
}

class _SchoolVerificationSectionState extends State<SchoolVerificationSection> {
  _LoadState _loadState = _LoadState.loading;
  ErrorInfo? _loadError; // 상태를 못 불러온 이유
  PermitStatus? _status; // 서버가 알려 준 내 인증 상태

  bool _consent = false; // 동의 체크
  PickedPhoto? _photo; // 고른 사진 한 장
  String? _checkMessage; // 사전 검사에서 거른 이유
  bool _submitting = false; // 신청 요청 중 (이 동안 컨트롤을 잠그고 연타를 막는다)
  ErrorInfo? _submitError; // 신청이 실패한 이유 (NeedLogin이면 로그인 안내)

  @override
  void initState() {
    super.initState();
    // 이미 인증한 회원은 물어볼 것이 없다. 미인증이면 상태를 한 번 묻는다
    if (context.read<AuthState>().user?.verifiedSchool == null) {
      _fetch();
    } else {
      _loadState = _LoadState.ready;
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
      await auth
          .refreshUser(); // 이게 끝나면 위쪽 AuthState가 바뀌어 뱃지(상단바 "인증" 포함)가 나타난다
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
    String? problem;
    if (picked.type.isNotEmpty && !allowedPhotoTypes.contains(picked.type)) {
      problem = permitTypeOnly;
    } else if (picked.size > maxPhotoBytes) {
      problem = permitTooLarge;
    }
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
      } else if (s == 'NONE' || s == 'REJECTED') {
        setState(
          () => _submitError = const ErrorMessage(permitNotReceived, null),
        );
      }
    }
    if (!mounted) return;
    // 요청이 끝났으니 컨트롤을 다시 푼다 (실패했으면 [인증 신청]을 다시 누를 수 있다)
    setState(() => _submitting = false);
  }

  @override
  Widget build(BuildContext context) {
    final school = context.watch<AuthState>().user?.verifiedSchool;
    // 인증 회원으로 시작했다가 인증이 풀려(학교가 사라져) 아직 상태를 모르면, 그때 한 번 묻는다.
    // 빌드 중에는 setState를 못 하니 값만 바꾸고 묻기는 그리기가 끝난 뒤로 미룬다
    if (school == null && _status == null && _loadState == _LoadState.ready) {
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
          ] else
            ..._unverified(),
        ],
      ),
    );
  }

  // 미인증 회원이 보는 내용
  List<Widget> _unverified() {
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
          final date = status.requestedAt == null
              ? ''
              : formatDate(status.requestedAt!);
          return [
            const Text('심사 대기 중'),
            if (date.isNotEmpty)
              FieldHint('신청한 때: $date')
            else
              const SizedBox(height: AppSizes.space3),
          ];
        }
        if (status.status == 'APPROVED') {
          // 승인됐는데 회원 정보 갱신이 실패한 드문 경우
          return const [
            Notice(kind: NoticeKind.info, message: permitApprovedReload),
          ];
        }
        return [
          if (status.status == 'REJECTED')
            Notice(
              kind: NoticeKind.error,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('지난 신청이 반려됐어요'),
                  Text(
                    permitRejectReasons[status.rejectReason] ??
                        permitRejectReasons['OTHER']!,
                  ),
                ],
              ),
            ),
          ..._form(),
        ];
    }
  }

  // 신청 폼: 가림 안내 + 동의 + 사진 고르기 + 신청 버튼
  List<Widget> _form() {
    final error = _submitError;
    final photo = _photo;
    return [
      const Text(permitMaskHide),
      const Text(permitMaskShow),
      const SizedBox(height: AppSizes.space3),
      // 동의 체크박스 + 문구. 문구를 눌러도 체크가 바뀌고, 스크린리더는 둘을 한 덩어리로 읽는다
      // (CheckboxListTile은 판(Panel) 안에서 물결 효과 경고가 나서 Row로 직접 만들었다)
      MergeSemantics(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Checkbox(
              value: _consent,
              // 신청 중에는 바꿀 수 없다 (onChanged가 null이면 비활성)
              onChanged: _submitting
                  ? null
                  : (v) => setState(() => _consent = v ?? false),
            ),
            Expanded(
              child: GestureDetector(
                onTap: _submitting
                    ? null
                    : () => setState(() => _consent = !_consent),
                child: const Padding(
                  padding: EdgeInsets.only(top: AppSizes.space3),
                  child: Text(
                    permitConsentText,
                    style: TextStyle(fontSize: AppSizes.fsSmall),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: AppSizes.space2),
      Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: AppSizes.space3,
        runSpacing: AppSizes.space2,
        children: [
          OutlinedButton(
            key: const ValueKey('permit-pick-button'),
            onPressed: _submitting ? null : _openPicker,
            child: const Text('사진 고르기'),
          ),
          if (photo != null) ...[
            // 작은 미리보기. 못 불러오면 빈 칸. 스크린리더에는 "고른 허가서 사진"으로 읽힌다
            Semantics(
              label: '고른 허가서 사진',
              image: true,
              excludeSemantics: true,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
                child: SizedBox(
                  width: 56,
                  height: 56,
                  child: ColoredBox(
                    color: AppColors.hover,
                    child: Image.network(
                      photo.previewUrl,
                      fit: BoxFit.cover,
                      cacheWidth: 112, // 작은 칸이라 줄여서 푼다
                      errorBuilder: (context, error, stack) =>
                          const SizedBox.shrink(),
                    ),
                  ),
                ),
              ),
            ),
            // 긴 파일 이름은 한 줄로 줄이고 끝을 "..."로 (칸을 밀어내지 않게 폭을 제한한다)
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 240),
              child: Text(
                photo.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: AppSizes.fsSmall,
                  color: AppColors.muted,
                ),
              ),
            ),
          ],
        ],
      ),
      const SizedBox(height: AppSizes.space3),
      if (_checkMessage != null)
        Notice(kind: NoticeKind.error, message: _checkMessage),
      if (_submitting)
        const Notice(kind: NoticeKind.info, message: permitProgressMessage),
      if (error is NeedLogin) const ReloginNotice(),
      if (error is ErrorMessage)
        Notice(kind: NoticeKind.error, message: error.message),
      FilledButton(
        key: const ValueKey('permit-submit-button'),
        // 동의와 사진이 있어야 하고, 신청 중에는 잠근다
        onPressed: (_consent && photo != null && !_submitting) ? _submit : null,
        child: const Text('인증 신청'),
      ),
    ];
  }
}
