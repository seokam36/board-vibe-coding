import 'package:material_ui/material_ui.dart';

import '../common/file_picker.dart';
import '../errors/error_messages.dart';
import '../market/photo_upload.dart' show maxPhotoBytes;
import '../theme/app_colors.dart';
import '../widgets/notice.dart';
import '../widgets/relogin_notice.dart';
import 'permit_reject_reasons.dart';
import 'permit_texts.dart';

// 신청 폼: 가림 안내 + 동의 + 사진 고르기 + 신청 버튼.
// 값(동의·사진·신청 중·안내)은 부모가 들고 있고, 이 위젯은 그리기만 한다.
// 동의 바꾸기·사진 고르기·신청은 콜백으로 부모에게 넘긴다.
class PermitApplyForm extends StatelessWidget {
  const PermitApplyForm({
    super.key,
    required this.consent,
    required this.photo,
    required this.submitting,
    required this.checkMessage,
    required this.error,
    required this.onConsentChanged,
    required this.onPickPhoto,
    required this.onSubmit,
  });

  final bool consent; // 동의 체크
  final PickedPhoto? photo; // 고른 사진 한 장
  final bool submitting; // 신청 요청 중 (컨트롤을 잠근다)
  final String? checkMessage; // 사전 검사에서 거른 이유
  final ErrorInfo? error; // 신청이 실패한 이유 (NeedLogin이면 로그인 안내)
  final ValueChanged<bool> onConsentChanged;
  final VoidCallback onPickPhoto;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    final error = this.error;
    final photo = this.photo;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
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
                value: consent,
                // 신청 중에는 바꿀 수 없다 (onChanged가 null이면 비활성)
                onChanged: submitting
                    ? null
                    : (v) => onConsentChanged(v ?? false),
              ),
              Expanded(
                child: GestureDetector(
                  onTap: submitting ? null : () => onConsentChanged(!consent),
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
              onPressed: submitting ? null : onPickPhoto,
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
        if (checkMessage != null)
          Notice(kind: NoticeKind.error, message: checkMessage),
        if (submitting)
          const Notice(kind: NoticeKind.info, message: permitProgressMessage),
        if (error is NeedLogin) const ReloginNotice(),
        if (error is ErrorMessage)
          Notice(kind: NoticeKind.error, message: error.message),
        FilledButton(
          key: const ValueKey('permit-submit-button'),
          // 동의와 사진이 있어야 하고, 신청 중에는 잠근다
          onPressed: (consent && photo != null && !submitting)
              ? onSubmit
              : null,
          child: const Text('인증 신청'),
        ),
      ],
    );
  }
}

// 지난 신청이 반려됐을 때 신청 폼 위에 보여 주는 안내 (모르는 코드는 OTHER 문구)
class PermitRejectedNotice extends StatelessWidget {
  const PermitRejectedNotice({super.key, required this.rejectReason});

  final String? rejectReason;

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.error,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('지난 신청이 반려됐어요'),
          Text(
            permitRejectReasons[rejectReason] ?? permitRejectReasons['OTHER']!,
          ),
        ],
      ),
    );
  }
}

// 관리자가 인증을 취소했을 때 신청 폼 위에 보여 주는 안내 + 취소 사유 (반려 사유 문구를 그대로 쓴다)
class PermitRevokedNotice extends StatelessWidget {
  const PermitRevokedNotice({super.key, required this.rejectReason});

  final String? rejectReason;

  @override
  Widget build(BuildContext context) {
    return Notice(
      kind: NoticeKind.error,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(permitRevokedMessage),
          Text(
            permitRejectReasons[rejectReason] ?? permitRejectReasons['OTHER']!,
          ),
        ],
      ),
    );
  }
}

// 사전 검사(브라우저에서 미리 거르기): 문제가 있으면 안내 문구, 없으면 null.
// 종류가 빈 글자면(브라우저가 모름) 통과시킨다. 진짜 판단은 서버가 파일 내용으로 한다
String? checkPickedPhoto(PickedPhoto photo) {
  if (photo.type.isNotEmpty && !allowedPhotoTypes.contains(photo.type)) {
    return permitTypeOnly;
  }
  if (photo.size > maxPhotoBytes) return permitTooLarge;
  return null;
}

// 철회·해제 실패 안내 (로그인 만료면 로그인 링크)
List<Widget> permitRemoveNotice(ErrorInfo? error) => [
  if (error is NeedLogin) const ReloginNotice(),
  if (error is ErrorMessage)
    Notice(kind: NoticeKind.error, message: error.message),
];
