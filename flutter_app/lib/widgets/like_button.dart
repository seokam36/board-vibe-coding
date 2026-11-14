import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../auth/auth_user.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import 'notice.dart';
import 'relogin_notice.dart';
import 'small_button_style.dart';

const _cancelForbidden = '취소하지 못했습니다. 새로고침 후 다시 시도해 주세요';
const _notLiked = '공감하지 않은 글입니다';

// 공감 버튼 (React의 LikeButton). 숫자는 글 응답의 likeCount로 시작하고,
// 누를 때마다 서버 응답의 값으로만 맞춘다(누르자마자 숫자를 바꾸는 "낙관적 갱신"은 하지 않는다).
// canLike: 새로 공감할 수 있는지(미인증·다른 학교 글이면 false). 이미 누른 공감의 취소는 항상 가능하다.
class LikeButton extends StatefulWidget {
  const LikeButton({super.key, required this.postId, required this.initialCount, required this.canLike});

  final String postId; // 주소의 :id (풀린 글자). 요청에 넣을 때 다시 인코딩한다
  final int initialCount;
  final bool canLike;

  @override
  State<LikeButton> createState() => _LikeButtonState();
}

class _LikeButtonState extends State<LikeButton> {
  late int _count = widget.initialCount;
  // 누가 눌렀는지(uid)와 같이 저장해, 로그아웃·다른 계정이 되면 자동으로 "공감 안 함"으로 본다
  ({int uid, bool liked})? _likedState;
  bool _busy = false; // 요청 중에는 버튼을 막아 연타를 방지한다
  String? _message;
  bool _needLogin = false;

  // 로그인한 사람이 바뀌었는지 알아보는 기록과, 늦게 온 옛 응답을 버리는 번호
  bool _started = false;
  int? _watchedUid;
  int _seq = 0;

  String get _url => '/posts/${Uri.encodeComponent(widget.postId)}/likes';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 로그인 사용자가 바뀔 때만 "내가 눌렀는지"를 다시 묻는다 (비로그인으로 물으면 401 재발급만 헛돈다)
    final uid = Provider.of<AuthState>(context).user?.id;
    if (_started && uid == _watchedUid) return;
    _started = true;
    _watchedUid = uid;
    _seq++;
    if (uid == null) return;
    final seq = _seq;
    context.read<ApiClient>().dio.get<Map<String, dynamic>>('$_url/me').then((res) {
      if (!mounted || seq != _seq) return; // 화면이 사라졌거나 그 사이 계정이 바뀜
      setState(() => _likedState = (uid: uid, liked: res.data!['liked'] as bool));
    }).catchError((Object _) {
      // 확인에 실패해도 버튼은 쓸 수 있게 둔다. 누르면 서버가 409/404로 알려 준다
    });
  }

  bool _isLiked(AuthUser? user) =>
      user != null && _likedState?.uid == user.id && _likedState!.liked;

  // 지금 로그인한 계정 이름으로 공감 여부를 기록한다 (로그인이 풀렸으면 기록하지 않음)
  void _setLiked(bool value) {
    final user = context.read<AuthState>().user;
    if (user != null) _likedState = (uid: user.id, liked: value);
  }

  Future<void> _click() async {
    if (_busy) return;
    final user = context.read<AuthState>().user;
    // 비로그인이면 글쓰기 버튼처럼 로그인 화면으로 보낸다
    if (user == null) {
      context.go('/login');
      return;
    }
    final dio = context.read<ApiClient>().dio;
    final wasLiked = _isLiked(user);
    setState(() {
      _busy = true;
      _message = null;
      _needLogin = false;
    });
    try {
      // 지금 상태에 따라 공감(POST) 또는 취소(DELETE)
      final res = wasLiked
          ? await dio.delete<Map<String, dynamic>>(_url)
          : await dio.post<Map<String, dynamic>>(_url);
      if (!mounted) return;
      setState(() {
        _count = res.data!['likeCount'] as int;
        _setLiked(res.data!['liked'] as bool);
      });
    } catch (err) {
      final info = describeError(
        err,
        {
          409: ErrorMessages.postAlreadyLiked,
          404: ErrorMessages.postNotFound,
          // 취소(DELETE)의 403은 보안 토큰 문제뿐이다. 누르기(POST)의 403만 학교 인증 문제
          403: wasLiked ? _cancelForbidden : ErrorMessages.schoolGatePostAction,
        },
        ErrorMessages.actionFailed,
      );
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          if (info.status == 409) {
            // 이미 눌려 있었음: 화면 상태만 "공감함"으로 맞춘다
            _setLiked(true);
            _message = ErrorMessages.postAlreadyLiked;
          } else if (info.status == 404 && wasLiked) {
            // 취소하려는데 눌러 둔 적이 없음: "공감 안 함"으로 맞춘다
            _setLiked(false);
            _message = _notLiked;
          } else {
            _message = info.message;
          }
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final liked = _isLiked(user);
    final enabled = !(_busy || (!widget.canLike && !liked));
    // 눌린 상태: 청자 배경·선·글자 (React의 .btn[aria-pressed='true'], hover해도 같은 색)
    final style = liked
        ? smallButtonStyle.merge(
            OutlinedButton.styleFrom(
              backgroundColor: AppColors.celadonSoft,
              foregroundColor: AppColors.celadonInk,
              side: const BorderSide(color: AppColors.celadon),
            ),
          )
        : smallButtonStyle;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 글자는 "공감 N"으로 고정하고, 읽어 주는 이름과 눌림 상태(toggled)는 따로 붙인다
        // ("공감 취소 3"이 "3개 취소"처럼 읽히지 않게)
        Semantics(
          button: true,
          enabled: enabled,
          toggled: liked,
          label: liked ? '공감 취소 (현재 $_count명)' : '공감 (현재 $_count명)',
          onTap: enabled ? _click : null,
          excludeSemantics: true,
          child: OutlinedButton(
            style: style,
            onPressed: enabled ? _click : null,
            child: Text('${liked ? '❤️' : '🤍'} 공감 $_count'),
          ),
        ),
        if (_message != null) Notice(kind: NoticeKind.error, message: _message),
        if (_needLogin) const ReloginNotice(),
      ],
    );
  }
}
