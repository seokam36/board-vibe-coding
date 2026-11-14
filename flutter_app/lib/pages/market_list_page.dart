import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import '../api/api_client.dart';
import '../auth/auth_state.dart';
import '../common/format_date.dart';
import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../market/market_format.dart';
import '../market/market_status_badge.dart';
import '../market/market_thumb.dart';
import '../market/market_write_gate.dart';
import '../market/models.dart';
import '../posts/models.dart' show School;
import '../theme/app_colors.dart';
import '../widgets/loading.dart';
import '../widgets/notice.dart';
import '../widgets/page_frame.dart';
import '../widgets/panel.dart';
import '../widgets/row_link.dart';
import '../widgets/select_field.dart';

const _listFailedMessage = '글 목록을 불러오지 못했습니다';
// 400 문구는 검색어가 있을 때만 "검색어는 50자 이하" 안내가 붙는다
const _badFilterMessage = '잘못된 조건입니다. 학교·판매/나눔·상태를 다시 골라 주세요';
const _badFilterWithQueryMessage = '잘못된 조건입니다. 학교·판매/나눔·상태를 다시 고르고 검색어는 50자 이하로 써 주세요';

// 제목 검색어 최대 글자 수 (서버와 같은 50자, UTF-16 단위)
const _maxSearchLength = 50;

// 판매/나눔, 상태의 고정 선택지 (값은 서버가 받는 값, 글자는 프론트 고정)
const _tradeTypes = [('SALE', '판매'), ('FREE', '나눔')];
const _statuses = [('ON_SALE', '판매중'), ('RESERVED', '예약중'), ('DONE', '거래완료')];

// 중고·나눔 목록 "/market" (React의 MarketListPage, 읽기 전용).
// 필터·검색어의 진짜 값은 주소 쿼리(?schoolId&tradeType&status&q)다. 라우터가 읽어 넘겨 주고,
// 드롭다운·검색은 주소만 바꾸며, 주소가 바뀌어 새 값을 받으면 목록을 다시 불러온다 (비자 후기 목록과 같은 방식).
class MarketListPage extends StatefulWidget {
  const MarketListPage({
    super.key,
    this.schoolId = '',
    this.tradeType = '',
    this.status = '',
    this.q = '',
  });

  final String schoolId;
  final String tradeType;
  final String status;
  final String q;

  @override
  State<MarketListPage> createState() => _MarketListPageState();
}

class _MarketListPageState extends State<MarketListPage> {
  // 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해, 지금 조건의 결과인지 비교한다
  String? _resultKey;
  List<MarketListItem> _items = const [];
  String? _error;
  int _seq = 0; // 요청마다 번호를 매겨, 늦게 도착한 옛 응답은 버린다

  // 학교 드롭다운 목록. 아직 못 받았거나 실패하면 null로 두고 글 목록은 계속 보여 준다
  List<School>? _schools;
  bool _schoolsFailed = false;

  // 검색 입력칸. 한 번만 만들어 계속 쓴다(다시 만들면 Enter 뒤 포커스가 사라진다).
  // 입력만으론 목록이 바뀌지 않고, Enter·[검색]으로 제출할 때만 주소의 q가 바뀐다
  late final TextEditingController _search = TextEditingController(text: widget.q);

  String get _queryKey => '${widget.schoolId}|${widget.tradeType}|${widget.status}|${widget.q}';

  @override
  void initState() {
    super.initState();
    _loadSchools();
    _load();
  }

  @override
  void didUpdateWidget(MarketListPage old) {
    super.didUpdateWidget(old);
    // 주소의 q가 바뀌면(뒤로 가기 포함) 입력칸 글자만 맞춘다. 커서는 끝으로
    if (old.q != widget.q) {
      _search.value = TextEditingValue(
        text: widget.q,
        selection: TextSelection.collapsed(offset: widget.q.length),
      );
    }
    if (old.schoolId != widget.schoolId ||
        old.tradeType != widget.tradeType ||
        old.status != widget.status ||
        old.q != widget.q) {
      _load();
    }
  }

  @override
  void dispose() {
    _seq++; // 화면이 사라진 뒤 도착하는 응답은 버린다
    _search.dispose();
    super.dispose();
  }

  // 학교 목록은 화면이 처음 나타날 때 한 번만 불러온다
  void _loadSchools() {
    context.read<ApiClient>().dio.get<List<dynamic>>('/schools').then((res) {
      if (!mounted) return;
      setState(() => _schools = [
            for (final e in res.data!) School.fromJson(e as Map<String, dynamic>),
          ]);
    }).catchError((Object e) {
      debugPrint('학교 목록 불러오기 실패: $e');
      if (mounted) setState(() => _schoolsFailed = true);
    });
  }

  void _load() {
    final seq = ++_seq;
    final key = _queryKey;
    final hasQuery = widget.q.isNotEmpty;
    // 빈 값은 보내지 않는다(조건 없음). 주소의 값 그대로 보내서 서버가 400/404로 판단하게 한다
    final params = <String, String>{
      if (widget.schoolId.isNotEmpty) 'schoolId': widget.schoolId,
      if (widget.tradeType.isNotEmpty) 'tradeType': widget.tradeType,
      if (widget.status.isNotEmpty) 'status': widget.status,
      if (hasQuery) 'q': widget.q,
    };
    context.read<ApiClient>().dio.get<List<dynamic>>('/market-items', queryParameters: params).then((res) {
      if (!mounted || seq != _seq) return; // 늦게 온 옛 응답은 무시
      setState(() {
        _resultKey = key;
        _items = [for (final e in res.data!) MarketListItem.fromJson(e as Map<String, dynamic>)];
        _error = null;
      });
    }).catchError((Object e) {
      debugPrint('중고 글 목록 불러오기 실패: $e'); // 응답 해석 실패 같은 코드 문제도 여기로 오므로 원인을 남긴다
      if (!mounted || seq != _seq) return;
      setState(() {
        _resultKey = key;
        _items = const [];
        _error = errorText(
          e,
          {404: ErrorMessages.schoolNotFound, 400: hasQuery ? _badFilterWithQueryMessage : _badFilterMessage},
          _listFailedMessage,
        );
      });
    });
  }

  // 주소의 쿼리 하나를 바꾼다. 다른 쿼리는 그대로 두고, 빈 값은 주소에서 뺀다.
  // 기록이 남는 context.go라 뒤로 가기로 이전 조건에 돌아간다
  void _setQuery(String name, String value) {
    final query = Map<String, String>.of(GoRouterState.of(context).uri.queryParameters);
    if (value.isEmpty) {
      query.remove(name);
    } else {
      query[name] = value;
    }
    context.go(Uri(path: '/market', queryParameters: query.isEmpty ? null : query).toString());
  }

  // 검색 제출(Enter·[검색]): 앞뒤 공백을 뺀 값을 주소 q에 넣는다. 비면 q를 주소에서 뺀다
  void _submitSearch() => _setQuery('q', _search.text.trim());

  // 드롭다운 하나. 주소의 값이 선택지에 없으면 임시 항목을 끼워 넣어 주소를 그대로 보여 준다
  // (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다)
  Widget _select({
    required String name,
    required String label,
    required String allLabel,
    required double width,
    required String value,
    required List<(String, String)>? items,
    required bool pending,
  }) {
    final missing = value.isNotEmpty && !(items ?? const []).any((i) => i.$1 == value);
    return SelectField(
      fieldKey: ValueKey('filter-$name'),
      label: label,
      width: width,
      value: value,
      options: [
        ('', allLabel),
        if (missing) (value, pending ? loadingText : '알 수 없는 $label ($value)'),
        ...?items,
      ],
      onChanged: (v) => _setQuery(name, v),
    );
  }

  @override
  Widget build(BuildContext context) {
    final user = context.watch<AuthState>().user;
    final canWrite = user?.verifiedSchool != null;

    final loading = _resultKey != _queryKey; // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중
    final items = loading ? const <MarketListItem>[] : _items;
    final error = loading ? null : _error;
    final schools = _schools;

    return PageFrame(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Semantics(
              header: true,
              headingLevel: 1,
              child: const Text(
                '중고·나눔',
                style: TextStyle(
                  fontSize: AppSizes.fsBoard,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  height: 1.3,
                ),
              ),
            ),
          ),
          // 툴바: 왼쪽 필터 셋 + 검색창, 오른쪽 글쓰기(학교 인증 회원만). 좁은 화면에선 줄바꿈된다
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space3),
            child: Wrap(
              alignment: WrapAlignment.spaceBetween,
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                Wrap(
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSizes.space2,
                  runSpacing: AppSizes.space2,
                  children: [
                    _select(
                      name: 'schoolId',
                      label: '학교',
                      allLabel: '전체 학교',
                      width: 170,
                      value: widget.schoolId,
                      items: schools == null ? null : [for (final s in schools) ('${s.id}', s.name)],
                      pending: schools == null && !_schoolsFailed,
                    ),
                    _select(
                      name: 'tradeType',
                      label: '판매/나눔',
                      allLabel: '판매·나눔 전체',
                      width: 150,
                      value: widget.tradeType,
                      items: _tradeTypes,
                      pending: false,
                    ),
                    _select(
                      name: 'status',
                      label: '상태',
                      allLabel: '전체 상태',
                      width: 130,
                      value: widget.status,
                      items: _statuses,
                      pending: false,
                    ),
                    // 제목 검색 (React의 <form role="search">). 이 Flutter 버전엔 search 역할이 없어 묶음(container)으로만 둔다
                    Semantics(
                      container: true,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SizedBox(
                            width: 200,
                            child: Semantics(
                              label: '제목 검색어',
                              child: TextField(
                                key: const ValueKey('market-search'),
                                controller: _search,
                                decoration: const InputDecoration(
                                  hintText: '제목으로 검색',
                                  hintStyle: TextStyle(color: AppColors.muted),
                                ),
                                // UTF-16 길이로 제한한다 (React maxLength와 같은 단위)
                                inputFormatters: const [Utf16LengthFormatter(_maxSearchLength)],
                                textInputAction: TextInputAction.search,
                                // onEditingComplete를 주면 Enter 뒤에 포커스를 자동으로 잃지 않는다
                                onEditingComplete: () {},
                                onSubmitted: (_) => _submitSearch(),
                              ),
                            ),
                          ),
                          const SizedBox(width: AppSizes.space2),
                          OutlinedButton(onPressed: _submitSearch, child: const Text('검색')),
                        ],
                      ),
                    ),
                  ],
                ),
                if (canWrite)
                  FilledButton(onPressed: () => context.go('/market/new'), child: const Text('글쓰기')),
              ],
            ),
          ),
          // 비로그인·미인증이면 버튼 대신 안내 (로그인·학교 인증 링크 포함)
          if (!canWrite) MarketWriteGate(user: user),
          if (_schoolsFailed)
            const Notice(kind: NoticeKind.error, message: ErrorMessages.reviewOptionsLoadFailed),
          FlushPanel(
            children: [
              if (loading) const Loading(),
              if (error != null) PanelState(child: Notice(kind: NoticeKind.error, message: error)),
              // 검색어가 있는데 0건이면 "글이 없다"가 아니라 "검색 결과가 없다"로 알려 준다
              if (!loading && error == null && items.isEmpty)
                PanelState(
                  child: Text(
                    widget.q.isNotEmpty ? '검색 결과가 없습니다' : '아직 올라온 글이 없습니다',
                    style: const TextStyle(color: AppColors.muted),
                  ),
                ),
              for (var i = 0; i < items.length; i++) _MarketRow(item: items[i], first: i == 0),
            ],
          ),
        ],
      ),
    );
  }
}

// 글 한 줄: 왼쪽 72px 썸네일 + 오른쪽 (상태 뱃지, 제목 링크 / 가격·학교·작성일). 작성자는 목록에 없다
class _MarketRow extends StatelessWidget {
  const _MarketRow({required this.item, required this.first});

  final MarketListItem item;
  final bool first;

  @override
  Widget build(BuildContext context) {
    const metaStyle = TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted, height: 1.65);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSizes.space4, vertical: AppSizes.space3),
      decoration: BoxDecoration(
        border: first ? null : const Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          MarketThumb(item: item),
          const SizedBox(width: AppSizes.space3),
          // Expanded: 본문 칸이 남는 폭만 쓰게 해서 375px에서도 가로로 넘치지 않는다
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  spacing: AppSizes.space2,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    MarketStatusBadge(code: item.status.code, name: item.status.name),
                    RowLink(title: item.title, onTap: () => context.go('/market/${item.id}')),
                  ],
                ),
                Wrap(
                  spacing: AppSizes.space3,
                  children: [
                    Text(
                      formatPrice(item.tradeType.code, item.price),
                      style: metaStyle.copyWith(fontWeight: FontWeight.w700), // React의 .market-price: 굵게
                    ),
                    Text(item.school.name, style: metaStyle),
                    Text(formatDate(item.createdAt), style: metaStyle),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
