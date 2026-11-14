import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../errors/error_messages.dart';
import '../market/market_item_form.dart';
import '../market/market_photo_editor.dart';
import '../market/models.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

// 이 화면만 쓰는 문구
const _forbiddenMineMessage = '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요';
const _notMineMessage = '본인 글만 수정할 수 있습니다';

// 중고·나눔 수정 "/market/:id/edit" (React의 MarketEditPage): 기존 글로 채운 같은 폼.
// 판매자 본인만, 거래완료(DONE) 글은 수정 불가.
class MarketEditPage extends StatefulWidget {
  const MarketEditPage({super.key, required this.id});

  final String id; // 주소의 :id (이미 풀린 글자). 요청에 넣을 때 다시 인코딩한다

  @override
  State<MarketEditPage> createState() => _MarketEditPageState();
}

class _MarketEditPageState extends State<MarketEditPage> {
  MarketItem? _item;
  bool _loading = true;
  String? _loadError; // 글을 못 불러온 경우(폼 숨김)
  bool _photoBusy = false; // 사진을 올리는 중·지우는 중 (글자 [저장] 잠금)
  bool _textSaving = false; // 글자 저장 중 (사진 추가·삭제 잠금)

  String get _safeId => Uri.encodeComponent(widget.id); // 상세 화면과 같은 이유로 인코딩

  @override
  void initState() {
    super.initState();
    _loadItem();
  }

  // 들어오면 원래 글을 불러온다. 화면이 사라진 뒤 도착한 응답은 버린다(mounted)
  void _loadItem() {
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('/market-items/$_safeId').then((res) {
      if (!mounted) return;
      setState(() {
        _item = MarketItem.fromJson(res.data!);
        _loadError = null;
        _loading = false;
      });
    }).catchError((Object e) {
      debugPrint('중고 글 불러오기 실패: $e');
      if (!mounted) return;
      setState(() {
        _item = null;
        _loadError = errorText(
          e,
          {400: ErrorMessages.postNotFound, 404: ErrorMessages.postNotFound},
          ErrorMessages.postLoadFailed,
        );
        _loading = false;
      });
    });
  }

  // 폼이 부르는 저장 함수. 성공하면 상세로 이동하고, 실패하면 throw해서 폼이 문구를 보여 준다
  Future<void> _save(Map<String, dynamic> body) async {
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    final safeId = _safeId;
    await dio.put<dynamic>('/market-items/$safeId', data: body);
    // Router.neglect: 기록을 새로 쌓지 않고 교체해서(React의 replace) 수정 폼이 뒤로 가기에 남지 않게 한다
    if (mounted) Router.neglect(context, () => router.go('/market/$safeId'));
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final item = _item;
    // 지금 주소의 글일 때만 쓴다 (숫자로 비교해 /market/01 처럼 서버가 같은 글로 보는 주소도 통과)
    final loaded = item != null && item.id == int.tryParse(widget.id) ? item : null;
    // 로그인했는데 남의 글이면 폼 대신 안내. (user가 null = 저장 중 세션 만료면 폼을 유지해 ReloginNotice가 보이게 한다)
    final notMine = loaded != null && user != null && loaded.sellerId != user.id;
    final done = loaded?.status.code == 'DONE';
    final showForm = loaded != null && !notMine && !done;

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('중고·나눔 글 수정'),
          if (_loading) const Loading(),
          if (_loadError != null) Notice(kind: NoticeKind.error, message: _loadError!),
          if (notMine) const Notice(kind: NoticeKind.error, message: _notMineMessage),
          if (loaded != null && !notMine && done)
            const Notice(kind: NoticeKind.error, message: ErrorMessages.marketDoneNotEditable),
          // 사진은 글자 저장과 따로, 올리거나 지우는 즉시 서버에 반영된다 (글자 저장 중이면 잠금)
          if (showForm)
            MarketPhotoEditor(
              key: ValueKey('photos-${loaded.id}'),
              itemId: loaded.id,
              initial: loaded.photos,
              onBusyChange: (busy) => setState(() => _photoBusy = busy),
              disabled: _textSaving,
            ),
          if (showForm)
            MarketItemForm(
              key: ValueKey(loaded.id),
              item: loaded,
              onSave: _save,
              // 사진을 올리거나 지우는 동안은 글자 [저장]을 잠그고, 글자 저장 중에는 사진 영역을 잠근다
              locked: _photoBusy,
              onSavingChange: (saving) => setState(() => _textSaving = saving),
              errorTable: const {
                400: ErrorMessages.marketFieldsInvalid,
                // 본인 글인데 403이면 보안 토큰 오류일 수 있다 (비자 후기 수정과 같은 방식)
                403: _forbiddenMineMessage,
                404: ErrorMessages.postNotFound,
                409: ErrorMessages.marketDoneNotEditable,
              },
              cancelTo: '/market/$_safeId',
            ),
          // 폼이 숨겨진 경우에도 돌아갈 길을 남긴다
          if (_loadError != null || notMine || done)
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
}
