import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../errors/error_messages.dart';
import '../common/file_picker.dart';
import '../market/market_item_form.dart';
import '../market/market_photo_picker.dart';
import '../market/market_write_gate.dart';
import '../market/photo_upload.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';

// 고른 사진 한 장 (key는 목록에서 빼기 위한 번호)
class _Picked {
  _Picked(this.key, this.photo);
  final int key;
  final PickedPhoto photo;
}

// 중고·나눔 글쓰기 "/market/new" (React의 MarketCreatePage).
// 학교 인증을 마친 회원만 쓸 수 있다. 학교는 고르지 않고 서버가 인증 학교로 정한다.
class MarketCreatePage extends StatefulWidget {
  const MarketCreatePage({super.key});

  @override
  State<MarketCreatePage> createState() => _MarketCreatePageState();
}

class _MarketCreatePageState extends State<MarketCreatePage> {
  // 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고, 인증 회원이었음을 기억해 둔다
  bool _wasVerified = false;

  // 고른 사진: 서버에는 [저장]을 누를 때 올리고, 그 전에는 브라우저 미리보기(blob 주소)로만 보인다
  final List<_Picked> _picked = [];
  int _nextKey = 0;
  bool _saving = false; // 글 만들기 + 사진 올리기 전체 진행 중 (사진 버튼 잠금)
  String? _progress;

  @override
  void dispose() {
    // 화면을 떠날 때 만든 미리보기 주소를 모두 반납한다
    for (final p in _picked) {
      p.photo.release();
    }
    super.dispose();
  }

  void _handleAdd(List<PickedPhoto> files) {
    setState(() {
      for (final f in files) {
        _picked.add(_Picked(_nextKey++, f));
      }
    });
  }

  void _handleRemove(Object key) {
    setState(() {
      final i = _picked.indexWhere((p) => p.key == key);
      if (i >= 0) _picked.removeAt(i).photo.release();
    });
  }

  // 폼이 부르는 저장 함수. 글 만들기 → (성공하면) 사진 차례로 올리기 → 상세로 이동.
  // 글 만들기가 실패하면 throw해서 폼이 문구를 보여 주고, 사진은 올리지 않으며 미리보기는 그대로 남는다.
  Future<void> _save(Map<String, dynamic> body) async {
    final dio = context.read<ApiClient>().dio;
    final router = GoRouter.of(context);
    final res = await dio.post<Map<String, dynamic>>('/market-items', data: body);
    final id = res.data!['id'] as int;
    // 글은 만들어졌으니 사진을 차례로 올린다. 일부가 실패해도 상세로 가서 안내한다
    var failed = 0;
    if (_picked.isNotEmpty) {
      final result = await uploadPhotosInOrder(
        dio,
        id,
        [for (final p in _picked) p.photo],
        (cur, total) {
          if (mounted) setState(() => _progress = '사진 올리는 중 $cur/$total');
        },
        (_) {},
        () => !mounted, // 화면을 떠났으면 남은 사진은 보내지 않는다
      );
      failed = result.failed;
    }
    // 화면을 떠났으면 이동하지 않는다. 기록이 남는 go (React의 navigate)
    if (!mounted) return;
    // 못 올린 장수는 extra(int)로 상세 화면에 넘긴다. 0장이면 extra 없이 간다
    if (failed > 0) {
      router.go('/market/$id', extra: failed);
    } else {
      router.go('/market/$id');
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final verified = user?.verifiedSchool != null;
    if (verified) _wasVerified = true;
    final canWrite = user != null ? verified : _wasVerified;

    if (!canWrite) {
      return PageFrame(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const PageTitle('중고·나눔 글쓰기'),
            MarketWriteGate(user: user),
          ],
        ),
      );
    }

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageTitle('중고·나눔 글쓰기'),
          // 사진 미리보기·고르기 영역 (글 저장 + 사진 올리는 동안 잠금)
          MarketPhotoPicker(
            items: [for (final p in _picked) PhotoTile(key: p.key, src: p.photo.previewUrl)],
            disabled: _saving,
            progress: _progress,
            error: null,
            needLogin: false,
            removeLabel: '빼기',
            onAdd: _handleAdd,
            onRemove: _handleRemove,
          ),
          MarketItemForm(
            onSave: _save,
            // 폼이 저장 중인 동안(글 + 사진 올리기)은 사진 버튼을 잠근다. 실패해 끝나면 풀린다
            onSavingChange: (saving) => setState(() => _saving = saving),
            errorTable: const {
              400: ErrorMessages.marketFieldsInvalid,
              // 미인증이거나 보안 토큰이 어긋났을 때 (둘 다 403)
              403: ErrorMessages.visaSchoolRequiredRetry,
            },
            cancelTo: '/market',
          ),
        ],
      ),
    );
  }
}
