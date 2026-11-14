import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/can_act_in_school.dart';
import '../errors/error_messages.dart';
import '../market/models.dart';
import '../theme/app_colors.dart';
import '../widgets/app_link.dart';
import 'message_composer.dart';

// 거래완료 글 안내 (화면 안내와 서버 409 실패 문구가 같은 뜻이 되게 한 곳에 둔다)
const _doneNotice = '거래가 완료된 글이라 새 대화를 시작할 수 없어요. 이미 나눈 대화는 쪽지함에서 이어 갈 수 있어요';

// 중고 글 상세 아래의 "판매자에게 쪽지" 칸 (React의 SellerMessageBox).
// 내 글이면 칸 자체를 그리지 않고, 보낼 수 없는 사람(비로그인·미인증·다른 학교·거래완료 글)에게는 입력칸 대신 이유 한 줄만 보여 준다.
// 화면에서 막는 건 편의일 뿐, 진짜 보호는 서버의 403·409이다. 상세 화면이 key=글 id로 붙인다.
class SellerMessageBox extends StatefulWidget {
  const SellerMessageBox({super.key, required this.item});

  final MarketItem item;

  @override
  State<SellerMessageBox> createState() => _SellerMessageBoxState();
}

class _SellerMessageBoxState extends State<SellerMessageBox> {
  // 한 번이라도 보내기를 눌렀으면 true. 그 뒤 로그인이 풀려(user 비워짐) 입력칸이 사라지면
  // 재로그인 안내와 쓴 글이 같이 날아가므로 칸을 유지한다
  bool _submitted = false;

  // 첫 쪽지를 보내고, 성공하면 만들어진(또는 이미 있던) 대화 화면으로 간다
  Future<void> _send(String content) async {
    final dio = context.read<ApiClient>().dio;
    final res = await dio.post<Map<String, dynamic>>(
      '/market-items/${Uri.encodeComponent('${widget.item.id}')}/conversations',
      data: {'content': content},
    );
    if (!mounted) return;
    context.go('/messages/${res.data!['conversationId']}');
  }

  // 이유 한 줄: 글자 + (있으면) 링크
  Widget _hint(String text, {String? linkLabel, String? linkTo}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSizes.space4),
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: AppSizes.space1,
        children: [
          Text(text),
          if (linkLabel != null) AppLink(label: linkLabel, onTap: () => context.go(linkTo!)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final item = widget.item;
    if (user != null && item.sellerId == user.id) return const SizedBox.shrink(); // 내 글에는 칸이 없다

    // 못 보내는 이유를 React와 같은 순서로 고른다
    Widget? hint;
    if (user == null) {
      hint = _hint('로그인하면 판매자에게 쪽지를 보낼 수 있습니다', linkLabel: '로그인', linkTo: '/login');
    } else if (user.verifiedSchool == null) {
      hint = _hint('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요', linkLabel: '학교 인증하기', linkTo: '/me');
    } else if (!canActInSchool(user, item.school.id)) {
      hint = _hint('인증한 학교(${user.verifiedSchool!.name}) 글에만 쪽지를 보낼 수 있어요');
    } else if (item.status.code == 'DONE') {
      // 서버는 이미 대화방이 있으면 거래완료 글에도 쪽지를 받는다. 새 대화만 막히므로 쪽지함으로 가는 길을 같이 안내한다
      hint = _hint(_doneNotice, linkLabel: '쪽지함 가기', linkTo: '/messages');
    }
    if (hint != null && !_submitted) return hint;

    return Padding(
      padding: const EdgeInsets.only(top: AppSizes.space5, bottom: AppSizes.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            header: true,
            headingLevel: 2,
            child: const Text(
              '판매자에게 쪽지',
              style: TextStyle(fontSize: AppSizes.fsRow, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
          ),
          MessageComposer(
            label: '쪽지 내용',
            placeholder: '${item.sellerNickname} 님에게 보낼 쪽지를 입력하세요',
            submitLabel: '쪽지 보내기',
            errorTable: {
              400: ErrorMessages.messageContentInvalid,
              403: ErrorMessages.schoolGatePostAction,
              404: ErrorMessages.alreadyDeleted('글'),
              409: _doneNotice,
            },
            fallbackError: ErrorMessages.messageSendFailed,
            send: _send,
            onSubmitStart: () => _submitted = true,
          ),
        ],
      ),
    );
  }
}
