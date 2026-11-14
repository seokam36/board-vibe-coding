import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/can_act_in_school.dart';
import '../common/format_date.dart';
import '../errors/error_messages.dart';
import '../market/market_format.dart';
import '../market/market_photo_gallery.dart';
import '../market/market_status_badge.dart';
import '../market/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_badge.dart';
import '../widgets/confirm_dialog.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/report_form.dart';
import '../widgets/small_button_style.dart';

// 지금 상태에서 갈 수 있는 상태 한 칸: 보낼 값(to), 버튼 글자(label), 성공했을 때 알려 주는 한 줄(done)
typedef _NextStatus = ({String to, String label, String done});

// 상태 전환 표 (서버 규칙과 같다: DONE에서는 나갈 수 없다). 화면은 이 표에 있는 버튼만 보여 준다.
// 서버만 먼저 배포돼 모르는 상태가 오면 표에 없으니 빈 목록(버튼 없음)으로 읽어 화면이 죽지 않게 한다.
const _nextStatuses = <String, List<_NextStatus>>{
  'ON_SALE': [
    (to: 'RESERVED', label: '예약중으로', done: '예약중으로 바꿨습니다'),
    (to: 'DONE', label: '거래 완료', done: '거래 완료로 바꿨습니다'),
  ],
  'RESERVED': [
    (to: 'ON_SALE', label: '판매중으로', done: '판매중으로 바꿨습니다'),
    (to: 'DONE', label: '거래 완료', done: '거래 완료로 바꿨습니다'),
  ],
  'DONE': [],
};

// 요청 중인 동작. 값이 있는 동안 모든 버튼을 잠가 두 번 눌림을 막는다
enum _Busy { status, delete }

// 중고·나눔 상세 "/market/:id" (React의 MarketDetailPage).
// 판매자 본인은 상태 바꾸기·수정·삭제, 관리자는 남의 글 삭제만, 같은 학교 인증 회원은 신고를 쓸 수 있다.
// photoFailed: 글쓰기에서 넘어온 "못 올린 사진 수". 라우터가 go_router의 extra(int)로 넘긴다.
//   글쓰기(S16-k1)는 `context.go('/market/{id}', extra: 못 올린 장수)` 형식으로 부른다. extra가 없으면 null.
class MarketDetailPage extends StatefulWidget {
  const MarketDetailPage({super.key, required this.id, this.photoFailed});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다
  final int? photoFailed;

  @override
  State<MarketDetailPage> createState() => _MarketDetailPageState();
}

class _MarketDetailPageState extends State<MarketDetailPage> {
  MarketItem? _item;
  bool _loading = true;
  String? _error; // 글을 못 불러온 경우
  _Busy? _busy;
  String? _actionError; // 상태 변경·삭제 실패 문구 (글은 계속 보인다)
  String? _statusNotice; // 상태 변경 성공 한 줄
  bool _needLogin = false; // 요청 중 401(재발급까지 실패): 로그인 안내
  int _selectedPhoto = 0; // 큰 사진으로 보고 있는 번호(0부터)
  // 글쓰기에서 넘어온 못 올린 사진 수. extra는 기록에 남을 수 있어서 처음 한 번만 지역 상태로 옮기고 주소에서는 지운다
  int _photoFailed = 0;

  // id를 경로에 넣기 전에 인코딩한다: '?' 같은 글자가 섞여 목록 API로 바뀌는 것을 막는다
  String get _safeId => Uri.encodeComponent(widget.id);

  @override
  void initState() {
    super.initState();
    final extra = widget.photoFailed;
    if (extra != null) {
      _photoFailed = extra;
      // 그린 직후 extra 없는 같은 주소로 "교체"한다: 새로고침·뒤로 가기 뒤에는 안내가 다시 보이지 않게
      // (지역 상태는 그대로라 지금 화면의 안내는 남는다. key=id라 같은 State가 이어 쓰인다)
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        final router = GoRouter.of(context);
        Router.neglect(context, () => router.go('/market/$_safeId'));
      });
    }
    _loadItem();
  }

  // 들어오면 글을 불러온다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted).
  // (id가 바뀌면 라우터가 key로 화면을 새로 만들기 때문에 id 바뀜은 따로 다루지 않는다)
  void _loadItem() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/market-items/$_safeId').then((res) {
      if (!mounted) return;
      setState(() {
        _item = MarketItem.fromJson(res.data!);
        _selectedPhoto = 0;
        _error = null;
        _loading = false;
      });
    }).catchError((Object e) {
      debugPrint('중고 글 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
      if (!mounted) return;
      setState(() {
        _item = null;
        _error = errorText(
          e,
          {404: ErrorMessages.postNotFound, 400: ErrorMessages.postNotFound},
          ErrorMessages.postLoadFailed,
        );
        _loading = false;
      });
    });
  }

  // 오류 처리 공통: 401이면 로그인 안내, 아니면 문구
  void _showFailure(ErrorInfo info) {
    if (info is NeedLogin) {
      _needLogin = true;
    } else if (info is ErrorMessage) {
      _actionError = info.message;
    }
  }

  // 상태 바꾸기: 성공하면 서버가 돌려준 글로 화면을 바꾸고 한 줄 안내를 보여 준다
  Future<void> _changeStatus(_NextStatus next) async {
    // 거래 완료는 되돌릴 수 없으니 먼저 확인한다. 취소하면 아무것도 하지 않는다
    if (next.to == 'DONE') {
      final ok = await showConfirm(
        context,
        message: '거래 완료로 바꾸면 되돌릴 수 없습니다. 완료할까요?',
        confirmLabel: '거래 완료',
        danger: true,
      );
      if (!ok || !mounted) return;
    }
    final dio = context.read<ApiClient>().dio;
    setState(() {
      _busy = _Busy.status;
      _actionError = null;
      _statusNotice = null; // 이전 성공 안내는 새 요청을 시작하면 지운다
      _needLogin = false;
    });
    try {
      final res = await dio.put<Map<String, dynamic>>('/market-items/$_safeId/status', data: {'status': next.to});
      if (!mounted) return;
      setState(() {
        _item = MarketItem.fromJson(res.data!);
        _statusNotice = next.done;
        _busy = null; // 성공해도 화면에 남으니 항상 버튼을 되살린다
      });
    } catch (err) {
      final info = describeError(
        err,
        {
          403: ErrorMessages.marketStatusRetry, // 본인 글인데 403이면 보안 토큰 문제일 수 있다
          404: ErrorMessages.alreadyDeleted('글'),
          409: ErrorMessages.marketStatusConflict,
        },
        ErrorMessages.marketStatusFailed,
      );
      if (!mounted) return;
      setState(() {
        _showFailure(info);
        _busy = null;
      });
    }
  }

  Future<void> _delete({required bool isMine}) async {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    final ok = await showConfirm(context, message: '이 글을 삭제할까요?', confirmLabel: '삭제', danger: true);
    if (!ok || !mounted) return;
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    setState(() {
      _busy = _Busy.delete;
      _actionError = null;
      _statusNotice = null;
      _needLogin = false;
    });
    try {
      await dio.delete<dynamic>('/market-items/$_safeId');
      // Router.neglect: 기록을 "새로 쌓지 않고 교체"해서 삭제된 글의 상세가 뒤로 가기 기록에 남지 않게 한다
      if (mounted) Router.neglect(context, () => router.go('/market'));
    } catch (err) {
      final info = describeError(
        err,
        {
          403: isMine ? ErrorMessages.deleteRetryAfterReload : ErrorMessages.deleteForbidden,
          404: ErrorMessages.alreadyDeleted('글'),
        },
        ErrorMessages.deleteFailed,
      );
      if (!mounted) return;
      setState(() {
        _showFailure(info);
        _busy = null; // 실패했을 때만 버튼을 되살린다 (성공하면 화면이 바뀐다)
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final item = _item;
    final isMine = user != null && item != null && item.sellerId == user.id; // 판매자 본인인지
    // 신고는 인증한 학교의 글에서만 (서버 규칙과 같다)
    final canAct = canActInSchool(user, item?.school.id);
    final locked = _busy != null;
    final nexts = item == null ? const <_NextStatus>[] : (_nextStatuses[item.status.code] ?? const <_NextStatus>[]);

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_loading) const Loading(),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error!),
          // 글쓰기 직후 사진 일부를 못 올렸을 때의 안내 (처음 한 번만 보이고, 새로고침·뒤로 가기 뒤에는 사라진다)
          if (_photoFailed > 0)
            Notice(kind: NoticeKind.error, message: '사진 $_photoFailed장을 올리지 못했습니다. 수정에서 다시 올려 주세요'),
          if (item != null) _buildArticle(item),
          // 버튼 표시 규칙(숨기는 건 편의일 뿐, 진짜 보호는 서버의 403):
          // 판매자 본인 = 상태 버튼(갈 수 있는 것만) + 수정(거래완료 아닐 때) + 삭제 / 관리자(남의 글) = 삭제만
          if (item != null && user != null && (isMine || user.isAdmin))
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: AppSizes.space2,
                runSpacing: AppSizes.space2,
                children: [
                  if (isMine)
                    for (final next in nexts)
                      OutlinedButton(
                        style: smallButtonStyle,
                        onPressed: locked ? null : () => _changeStatus(next),
                        child: Text(_busy == _Busy.status ? '처리 중...' : next.label),
                      ),
                  // 요청 중에는 링크를 못 막으니 비활성 버튼으로 바꾼다 (onPressed null)
                  if (isMine && item.status.code != 'DONE')
                    OutlinedButton(
                      style: smallButtonStyle,
                      onPressed: locked ? null : () => context.go('/market/$_safeId/edit'),
                      child: const Text('수정'),
                    ),
                  OutlinedButton(
                    style: dangerSmallButtonStyle,
                    onPressed: locked ? null : () => _delete(isMine: isMine),
                    child: Text(_busy == _Busy.delete ? '삭제 중...' : '삭제'),
                  ),
                ],
              ),
            ),
          // 신고는 로그인했고 내 글이 아니고 인증한 학교의 글일 때만 (글 상세와 같은 조건)
          if (item != null && user != null && !isMine && canAct)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSizes.space4),
              child: Wrap(
                children: [
                  ReportForm(
                    url: '/market-items/$_safeId/reports',
                    label: '중고 글',
                    onNeedLogin: () => setState(() {
                      _actionError = null;
                      _needLogin = true; // 로그인 안내는 user 조건 밖에서 보여 준다
                    }),
                  ),
                ],
              ),
            ),
          // 성공 안내: 누른 버튼이 사라져도 결과를 알려 준다. 에러가 나면 위에서 이미 지워져 있다
          if (_statusNotice != null) Notice(kind: NoticeKind.success, message: _statusNotice!),
          // 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게)
          if (_actionError != null) Notice(kind: NoticeKind.error, message: _actionError!),
          if (_needLogin) const ReloginNotice(),
          // S16-l1: 여기에 "판매자에게 쪽지" 칸(SellerMessageBox)이 들어온다
          Align(
            alignment: Alignment.centerLeft,
            child: OutlinedButton(
              onPressed: () => context.go('/market'),
              child: const Text('목록으로'),
            ),
          ),
        ],
      ),
    );
  }

  // 글 본문 판. 드래그로 글자를 고를 수 있게 SelectionArea로 감싼다
  Widget _buildArticle(MarketItem item) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    final photos = item.photos;
    // 고른 번호가 사진 수를 넘으면(상태 변경 응답에서 사진이 줄었을 때) 마지막 사진으로 맞춘다
    final photoIndex = photos.isEmpty ? 0 : _selectedPhoto.clamp(0, photos.length - 1);
    return Panel(
      child: SelectionArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 상태와 학교
            Wrap(
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                MarketStatusBadge(code: item.status.code, name: item.status.name),
                AppBadge(text: item.school.name, school: true),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space2, bottom: AppSizes.space2),
              child: Semantics(
                header: true,
                headingLevel: 1,
                child: Text(
                  item.title,
                  style: const TextStyle(
                    fontSize: AppSizes.fsTitle,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink,
                    height: 1.35,
                  ),
                ),
              ),
            ),
            Wrap(
              spacing: AppSizes.space3,
              children: [
                Text(item.sellerNickname, style: metaStyle),
                Text(formatDate(item.createdAt), style: metaStyle),
              ],
            ),
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space3),
              child: Text(
                formatPrice(item.tradeType.code, item.price),
                style: const TextStyle(fontWeight: FontWeight.w700, height: 1.65), // React의 .market-price: 굵게
              ),
            ),
            // 사진: 1장 이상일 때만 영역을 그린다
            if (photos.isNotEmpty)
              MarketPhotoGallery(
                itemId: item.id,
                photos: photos,
                index: photoIndex,
                onSelect: (i) => setState(() => _selectedPhoto = i),
              ),
            // 글자로만 그린다(HTML이 실행되지 않음). Text는 내용의 줄바꿈을 그대로 보여 준다
            Padding(
              padding: const EdgeInsets.only(top: AppSizes.space4),
              child: Text(item.content, style: const TextStyle(height: 1.65)),
            ),
          ],
        ),
      ),
    );
  }
}
