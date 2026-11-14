import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

import '../common/utf16_length_formatter.dart';
import '../errors/error_messages.dart';
import '../theme/app_colors.dart';
import '../widgets/labeled_field.dart';
import '../widgets/notice.dart';
import '../widgets/panel.dart';
import '../widgets/relogin_notice.dart';
import '../widgets/section_parts.dart';
import '../widgets/select_field.dart';
import 'models.dart';

// 이 폼만 쓰는 문구 (React MarketItemForm의 글자 그대로)
const _fillAllMessage = '제목과 본문을 입력해 주세요';
const _priceRangeMessage = '판매 가격은 1원 이상 10,000,000원 이하의 숫자로 입력해 주세요';
const _maxPrice = 10000000;

// 중고·나눔 글 올리기·수정 공용 폼 (React의 MarketItemForm). 요청 본문이 하나라 폼도 하나다.
// item이 있으면 수정: 그 값으로 채운다. 사진은 이 폼 밖(쓰기 화면의 미리보기·수정 화면의 사진 편집기)이 맡는다.
class MarketItemForm extends StatefulWidget {
  const MarketItemForm({
    super.key,
    this.item,
    required this.onSave,
    required this.errorTable,
    required this.cancelTo,
    this.locked = false,
    this.onSavingChange,
  });

  final MarketItem? item; // 수정 화면에서 채울 처음 값
  // 저장 요청을 보내는 함수 (성공하면 이 함수가 화면을 이동시킨다). 실패하면 throw
  final Future<void> Function(Map<String, dynamic> body) onSave;
  final Map<int, String> errorTable; // 저장 실패 상태코드별 문구
  final String cancelTo;
  final bool locked; // 다른 작업(사진 올리기·지우기) 중이면 [저장]을 잠근다
  final void Function(bool saving)? onSavingChange; // 글자 저장 중인지 부모에게 알린다 (사진 영역 잠금용)

  @override
  State<MarketItemForm> createState() => _MarketItemFormState();
}

class _MarketItemFormState extends State<MarketItemForm> {
  // 입력값은 컨트롤러·State가 들고 있어서 실패해도 그대로 남는다
  late final _title = TextEditingController(text: widget.item?.title ?? '');
  late final _content = TextEditingController(text: widget.item?.content ?? '');
  late String _tradeType = widget.item?.tradeType.code ?? 'SALE';
  // 가격은 입력 중에 빈 칸·글자가 있을 수 있어 글자로 들고 있다가 보낼 때 숫자로 바꾼다 (나눔 글이면 빈 칸)
  late final _price = TextEditingController(
    text: widget.item != null && widget.item!.tradeType.code == 'SALE' ? '${widget.item!.price}' : '',
  );
  bool _saving = false;
  // 저장 중 401(재발급까지 실패): 로그인 안내를 따로 보여 준다
  bool _needLogin = false;
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _content.dispose();
    _price.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving || widget.locked) return; // Enter로 제출되는 경우도 막는다 (잠긴 동안·저장 중에는 무시)
    setState(() {
      _error = null;
      _needLogin = false;
    });
    if (_title.text.trim().isEmpty || _content.text.trim().isEmpty) {
      setState(() => _error = _fillAllMessage);
      return;
    }
    final body = <String, dynamic>{
      'title': _title.text,
      'content': _content.text,
      'tradeType': _tradeType,
    };
    if (_tradeType == 'SALE') {
      // 판매: 가격 1~10,000,000 정수 필수 (숫자 글자만). 어긋나면 서버에 보내지 않고 화면에 머문다
      final text = _price.text.trim();
      final n = RegExp(r'^\d+$').hasMatch(text) ? int.tryParse(text) : null;
      if (n == null || n < 1 || n > _maxPrice) {
        setState(() => _error = _priceRangeMessage);
        return;
      }
      body['price'] = n;
    } // 나눔이면 price 키를 아예 넣지 않는다 (서버가 0으로 저장)
    setState(() => _saving = true);
    widget.onSavingChange?.call(true);
    try {
      await widget.onSave(body);
    } catch (err) {
      final info = describeError(err, widget.errorTable, ErrorMessages.saveFailed);
      if (!mounted) return;
      setState(() {
        if (info is NeedLogin) {
          _needLogin = true;
        } else if (info is ErrorMessage) {
          _error = info.message;
        }
        _saving = false; // 실패했을 때만 버튼을 되살린다 (성공하면 화면이 바뀐다)
      });
      widget.onSavingChange?.call(false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 제목: 최대 100자(UTF-16 길이). Enter로도 제출된다
          LabeledField(
            fieldKey: const ValueKey('title-field'),
            label: '제목',
            controller: _title,
            maxLength: 100,
            onSubmitted: _submit,
          ),
          // 판매/나눔 + (판매일 때만) 가격
          Padding(
            padding: const EdgeInsets.only(bottom: AppSizes.space4),
            child: Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              spacing: AppSizes.space2,
              runSpacing: AppSizes.space2,
              children: [
                SelectField(
                  fieldKey: const ValueKey('trade-type-select'),
                  label: '판매/나눔',
                  width: 120,
                  value: _tradeType,
                  options: const [('SALE', '판매'), ('FREE', '나눔')],
                  onChanged: (v) => setState(() => _tradeType = v),
                ),
                // 나눔이면 가격 칸을 숨긴다
                if (_tradeType == 'SALE')
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ExcludeSemantics(
                        child: const Text(
                          '가격(원)',
                          style: TextStyle(fontSize: AppSizes.fsSmall2, fontWeight: FontWeight.w500, color: AppColors.ink),
                        ),
                      ),
                      const SizedBox(width: AppSizes.space2),
                      SizedBox(
                        width: 160,
                        child: Semantics(
                          label: '가격(원)',
                          child: TextField(
                            key: const ValueKey('price-field'),
                            controller: _price,
                            keyboardType: TextInputType.number,
                            textInputAction: TextInputAction.done,
                            onSubmitted: (_) => _submit(),
                          ),
                        ),
                      ),
                    ],
                  ),
              ],
            ),
          ),
          // 본문: 최대 2000자(UTF-16 길이), 8줄. 아래에 "n / 2000" 글자 수
          ExcludeSemantics(
            child: const Text(
              '본문',
              style: TextStyle(fontSize: AppSizes.fsSmall2, fontWeight: FontWeight.w500, color: AppColors.ink),
            ),
          ),
          const SizedBox(height: AppSizes.space1),
          Semantics(
            label: '본문',
            child: TextField(
              key: const ValueKey('content-field'),
              controller: _content,
              inputFormatters: const [Utf16LengthFormatter(2000)],
              minLines: 8,
              maxLines: 8,
              keyboardType: TextInputType.multiline,
              textInputAction: TextInputAction.newline,
            ),
          ),
          // 글자 수는 입력이 바뀔 때마다 새로 그린다 (React의 content.length)
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: _content,
            builder: (context, value, _) => FieldHint('${value.text.length} / 2000'),
          ),
          if (_error != null) Notice(kind: NoticeKind.error, message: _error),
          if (_needLogin) const ReloginNotice(),
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSizes.space2,
            runSpacing: AppSizes.space2,
            children: [
              FilledButton(
                onPressed: _saving || widget.locked ? null : _submit,
                child: Text(_saving ? '저장 중...' : '저장'),
              ),
              // 저장 중에는 이동을 막으려고 비활성 버튼으로 바꾼다 (React의 비활성 버튼 모양과 같다)
              OutlinedButton(
                onPressed: _saving ? null : () => context.go(widget.cancelTo),
                child: const Text('취소'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
