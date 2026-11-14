import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import 'loading.dart';
import 'notice.dart';
import 'select_field.dart';
import 'small_button_style.dart';

const _detailMax = 300;
const _reasonsFailed = '신고 사유를 불러오지 못했습니다';
const _chooseReason = '신고 사유를 골라 주세요';
const _badRequest = '내용을 확인해 주세요 (자기 것은 신고할 수 없고, 사유는 필수입니다)';
const _sendFailed = '신고하지 못했습니다';

// 글·댓글·중고 글·대화 화면에 붙는 "신고" 버튼 + 펼쳐지는 신고 양식 (React의 ReportForm).
// url: 신고를 보낼 주소(/posts/{id}/reports 등), label: '글'·'댓글'·'중고 글'·'대화' (안내 문구에 들어감)
// onNeedLogin: 401(재발급까지 실패)이면 부모가 로그인 안내를 띄우게 알린다
// (로그인이 풀리면 이 위젯이 사라지므로 안내를 여기서 띄우면 안 보인다)
class ReportForm extends StatefulWidget {
  const ReportForm({super.key, required this.url, required this.label, required this.onNeedLogin});

  final String url;
  final String label;
  final VoidCallback onNeedLogin;

  @override
  State<ReportForm> createState() => _ReportFormState();
}

class _ReportFormState extends State<ReportForm> {
  bool _open = false;
  // 사유 목록 (code, name). null이면 아직 못 받은 상태
  List<(String, String)>? _reasons;
  String? _reasonsError;
  String _reason = '';
  final _detail = TextEditingController();
  bool _sending = false;
  String? _message; // 성공·실패 안내 (React처럼 성공도 error 스타일)
  bool _done = false; // 접수 완료 또는 이미 신고함 → 양식을 닫고 다시 못 열게 한다

  bool get _isConversation => widget.label == '대화';

  @override
  void dispose() {
    _detail.dispose();
    super.dispose();
  }

  // 사유 목록은 양식을 처음 열 때만 받는다 (안 누르는 사람은 요청 낭비가 없다)
  void _loadReasons() {
    setState(() => _reasonsError = null);
    context.read<ApiClient>().dio.get<List<dynamic>>('/report-reasons').then((res) {
      if (!mounted) return;
      setState(() => _reasons = [
            for (final e in res.data!)
              ((e as Map<String, dynamic>)['code'] as String, e['name'] as String),
          ]);
    }).catchError((Object e) {
      if (!mounted) return;
      setState(() => _reasonsError = errorText(e, {}, _reasonsFailed));
    });
  }

  void _handleOpen() {
    setState(() {
      _open = true;
      _message = null;
    });
    if (_reasons == null) _loadReasons();
  }

  Future<void> _submit() async {
    if (_sending) return; // 보내는 중에 또 눌려도 한 번만 보낸다
    if (_reason.isEmpty) {
      setState(() => _message = _chooseReason); // 요청은 보내지 않는다
      return;
    }
    final dio = context.read<ApiClient>().dio;
    // 부모가 준 콜백은 미리 잡아 둔다: 401로 로그인이 풀리면 이 위젯이 먼저 사라져(mounted=false)도 안내는 띄워야 한다
    final onNeedLogin = widget.onNeedLogin;
    final detail = _detail.text.trim();
    setState(() {
      _sending = true;
      _message = null;
    });
    try {
      // 상세 내용은 비워 두면 키 자체를 보내지 않는다
      await dio.post<dynamic>(widget.url, data: {'reason': _reason, if (detail.isNotEmpty) 'detail': detail});
      if (!mounted) return;
      setState(() {
        _message = '신고가 접수됐습니다';
        _done = true;
        _open = false;
      });
    } catch (err) {
      final label = widget.label;
      final info = describeError(
        err,
        {
          409: '이미 신고한 $label입니다',
          // 대화는 "삭제"가 아니라 없어졌거나 내 방이 아닌 경우(404)라 대화 전용 문구를 쓴다.
          // 대화는 자기 것 신고 제한이 없어서(400은 사유 문제뿐) 400 문구도 따로 둔다
          404: _isConversation ? ErrorMessages.conversationNotFound : ErrorMessages.alreadyDeleted(label),
          // 대화 신고 403은 보안 토큰이나 화면-서버 인증 상태 불일치뿐이라 "인증한 학교의 글이 아니에요"는 맞지 않는다
          403: _isConversation ? ErrorMessages.visaSchoolRequiredRetry : ErrorMessages.schoolGatePostAction,
          400: _isConversation ? ErrorMessages.reportReasonInvalid : _badRequest,
        },
        _sendFailed,
      );
      if (info is NeedLogin) {
        onNeedLogin(); // 양식은 그대로 둔다. 화면이 사라졌어도 부모에게는 알린다(mounted 검사 전)
        return;
      }
      if (!mounted) return;
      if (info is ErrorMessage) {
        setState(() {
          _message = info.message;
          // 409(이미 신고함)·404(이미 지워진 대상)이면 더 보낼 수 없으니 양식을 닫는다
          if (info.status == 409 || info.status == 404) {
            _done = true;
            _open = false;
          }
        });
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final label = widget.label;
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!_open && !_done)
          Semantics(
            button: true,
            label: '$label 신고',
            onTap: _handleOpen,
            excludeSemantics: true,
            child: OutlinedButton(style: smallButtonStyle, onPressed: _handleOpen, child: const Text('신고')),
          ),
        if (_open) _buildBox(),
        // 양식이 닫혀 작업 줄의 한 칸일 때는 글자 폭만큼만, 열려 있으면 줄 전체
        if (_message != null) Notice(kind: NoticeKind.error, message: _message, shrinkWrap: !_open),
      ],
    );
    // 열려 있으면 작업 줄(Wrap)에서 한 줄을 다 차지한다 (React의 .report-open { flex: 1 1 100% })
    return _open ? SizedBox(width: double.infinity, child: content) : content;
  }

  Widget _buildBox() {
    final reasons = _reasons;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: AppSizes.space3),
      padding: const EdgeInsets.all(AppSizes.space3),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 사유 목록: 실패(다시 시도만) / 로딩 중 / 성공(드롭다운)
          if (_reasonsError != null)
            Notice(
              kind: NoticeKind.error,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                children: [
                  Text(_reasonsError!),
                  OutlinedButton(style: smallButtonStyle, onPressed: _loadReasons, child: const Text('다시 시도')),
                ],
              ),
            )
          else if (reasons == null)
            const Loading(prefix: '사유')
          else
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space2),
              child: SelectField(
                fieldKey: const ValueKey('report-reason'),
                label: '신고 사유',
                width: double.infinity,
                value: _reason,
                options: [('', '사유를 고르세요'), ...reasons],
                onChanged: (v) => setState(() => _reason = v),
              ),
            ),
          Semantics(
            label: '신고 상세 내용 (선택)',
            child: TextField(
              key: const ValueKey('report-detail'),
              controller: _detail,
              decoration: const InputDecoration(
                hintText: '자세한 내용 (선택)',
                hintStyle: TextStyle(color: AppColors.muted),
              ),
              // UTF-16 길이로 제한 (카운터의 text.length와 같은 단위)
              inputFormatters: const [Utf16LengthFormatter(_detailMax)],
              minLines: 2,
              maxLines: 2,
              keyboardType: TextInputType.multiline,
              onChanged: (_) => setState(() {}), // 글자 수 표시를 갱신
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: AppSizes.space1),
            child: Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                Text(
                  '${_detail.text.length}/$_detailMax',
                  style: const TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
                ),
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: (_sending || reasons == null) ? null : _submit,
                  child: Text(_sending ? '보내는 중...' : '신고 보내기'),
                ),
                // 취소는 닫기만 한다 (쓰던 내용은 그대로 남는다)
                OutlinedButton(
                  style: smallButtonStyle,
                  onPressed: _sending ? null : () => setState(() => _open = false),
                  child: const Text('취소'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
