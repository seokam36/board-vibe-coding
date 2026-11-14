// S16-j2 중고·나눔 상세 화면 테스트 (React의 market.spec / market-photos.spec / market-report.spec /
// market-photo-upload.spec의 상세 부분): 표시, 불러오기 실패, 사진(0/1/3장·선택·줄어듦·실패·키보드),
// 버튼 조건 표, 상태 변경(확인 창·성공 안내·실패 5종·잠금), 삭제, 신고 연결, 글쓰기에서 넘어온 사진 실패 안내, 375px.
// Image.network는 테스트에서 항상 실패하므로, 사진은 "어느 사진인지"(위젯 key)·시맨틱·안내 글로 확인하고
// 사진 주소·이름은 갤러리 부품 하나만 띄워(첫 프레임, 아직 실패 전) 확인한다.
import 'dart:async';
import 'dart:ui' show Tristate;

import 'package:board_web/common/format_date.dart';
import 'package:board_web/market/market_photo_gallery.dart';
import 'package:board_web/market/models.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';

// 서울대 인증 회원 (id 1 = 기본 판매자, 즉 내 글)
Map<String, dynamic> _seoulUser({String role = 'USER', int id = 1}) =>
    userJson(id: id, role: role, school: marketSeoul);

// 상세 불러오기 응답을 정한 가짜 서버 (기본: 판매중, 사진 없음, 판매자 id 1)
FakeAdapter _adapter({FakeReply? item}) =>
    FakeAdapter()..on('GET', '/market-items/7', item ?? FakeReply(200, marketDetailJson()));

Future<TestApp> _open(
  WidgetTester tester,
  FakeAdapter adapter, {
  Map<String, dynamic>? user,
  String location = '/market/7',
}) => pumpApp(tester, location: location, user: user, adapter: adapter);

// 서버가 받은 요청 본문을 모은다 (본문 없는 GET은 뺀다)
void Function(Object?) _collect(List<Object?> into) => (data) {
  if (data != null) into.add(data);
};

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

Finder _btn(String text) => find.widgetWithText(OutlinedButton, text);
final _delete = _btn('삭제');
final _edit = _btn('수정');
final _toList = _btn('목록으로');
final _report = _btn('신고');

// 삭제·거래 완료 확인 창 안의 글자
Finder _dialogText(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));

// 안내(Notice) 하나: 글자가 같은 것
Finder _notice(String message) => find.byWidgetPredicate((w) => w is Notice && w.message == message);
NoticeKind _kind(WidgetTester tester, String message) => tester.widget<Notice>(_notice(message)).kind;

// 큰 사진(key = 사진 id)과 썸네일 버튼
Finder _main(int photoId) => find.byKey(ValueKey('market-main-photo-$photoId'));
Finder _thumb(int photoId) => find.byKey(ValueKey('market-photo-thumb-$photoId'));
final _mainAny = find.byType(MarketMainPhoto);
final _anyThumb = find.byWidgetPredicate((w) => w.key is ValueKey<String> && (w.key as ValueKey<String>).value.startsWith('market-photo-thumb-'));

bool _selected(WidgetTester tester, String label) =>
    tester.getSemantics(find.bySemanticsLabel(label)).getSemanticsData().flagsCollection.isSelected == Tristate.isTrue;

void main() {
  group('표시', () {
    _test('불러오는 동안은 "불러오는 중..."과 [목록으로]만 보이고, 끝나면 글이 뜬다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(item: FakeReply(200, marketDetailJson(), gate));
      await _open(tester, adapter); // 응답을 붙잡아 둔 채로 화면이 뜬다
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_toList, findsOneWidget);
      expect(find.text('전공 서적 팝니다'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
      expect(adapter.count('GET', '/market-items/7'), 1);
    });

    _test('상태·학교 뱃지, 제목(heading), 판매자 닉네임, 날짜, 굵은 가격, 줄바꿈 본문이 보인다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(
        tester,
        _adapter(
          item: FakeReply(
            200,
            marketDetailJson(
              title: '자전거',
              content: '첫 줄\n둘째 줄\n\n넷째 줄',
              price: 1234567,
              sellerNickname: '김철수',
              statusCode: 'RESERVED',
              statusName: '예약중',
            ),
          ),
        ),
      );
      expect(find.text('예약중'), findsOneWidget);
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('자전거'), findsOneWidget);
      expect(tester.getSemantics(find.text('자전거')).flagsCollection.isHeader, isTrue);
      expect(find.text('김철수'), findsOneWidget);
      expect(find.text(formatDate('2020-11-08T06:19:00Z')), findsOneWidget);
      expect(tester.widget<Text>(find.text('1,234,567원')).style!.fontWeight, FontWeight.w700);
      expect(find.text('첫 줄\n둘째 줄\n\n넷째 줄'), findsOneWidget); // 한 덩어리 글자 = 줄바꿈이 그대로
      expect(find.byType(SelectionArea), findsOneWidget); // 드래그로 글자를 고를 수 있다
      expect(_toList, findsOneWidget);
      handle.dispose();
    });

    _test('나눔 글은 가격 자리에 "나눔"', (tester) async {
      await _open(tester, _adapter(item: FakeReply(200, marketDetailJson(tradeCode: 'FREE', tradeName: '나눔', price: 0))));
      expect(find.text('나눔'), findsOneWidget);
      expect(find.text('0원'), findsNothing);
    });

    _test('[목록으로]를 누르면 /market 으로 간다', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/schools', FakeReply(200, marketSchools))
        ..on('GET', '/market-items', FakeReply(200, []));
      final app = await _open(tester, adapter);
      await tester.tap(_toList);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market');
    });

    _test('"?"가 든 id도 경로에 인코딩되어 /market-items/? (목록 API)로 바뀌지 않는다', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/market-items/a%3Fb', FakeReply(404));
      await _open(tester, adapter, location: '/market/a%3Fb');
      expect(adapter.calls.single.path, '/api/market-items/a%3Fb');
      expect(find.text('글을 찾을 수 없습니다'), findsOneWidget);
    });
  });

  group('불러오기 실패', () {
    for (final (status, message) in const [
      (404, '글을 찾을 수 없습니다'),
      (400, '글을 찾을 수 없습니다'),
      (500, '글을 불러오지 못했습니다'),
      (401, '글을 불러오지 못했습니다'), // 불러오기 실패에는 로그인 안내가 따로 없다
    ]) {
      _test('GET $status → "$message", 글·버튼은 없고 [목록으로]만 남는다', (tester) async {
        final adapter = _adapter(item: FakeReply(status));
        if (status == 401) adapter.on('POST', '/auth/refresh', FakeReply(401)); // 재발급도 실패 → 로그인이 풀린다
        await _open(tester, adapter, user: _seoulUser());
        if (status == 401) {
          await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
          await tester.pumpAndSettle();
        }
        expect(find.text(message), findsOneWidget);
        expect(_kind(tester, message), NoticeKind.error);
        expect(find.text('불러오는 중...'), findsNothing);
        expect(_delete, findsNothing);
        expect(_edit, findsNothing);
        expect(_report, findsNothing);
        expect(_toList, findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    _test('네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      await _open(tester, _adapter(item: FakeReply.networkError()));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_toList, findsOneWidget);
    });
  });

  group('사진', () {
    _test('0장이면 사진 영역도 썸네일 버튼도 없다', (tester) async {
      await _open(tester, _adapter());
      expect(find.byType(MarketPhotoGallery), findsNothing);
      expect(_mainAny, findsNothing);
      expect(_anyThumb, findsNothing);
      expect(find.text('사진을 불러오지 못했습니다'), findsNothing);
    });

    _test('1장이면 큰 사진 하나만 있고 썸네일 버튼은 없다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7]))));
      expect(_main(7), findsOneWidget);
      expect(_anyThumb, findsNothing);
      expect(find.bySemanticsLabel(RegExp(r'^사진 \d+ 보기$')), findsNothing);
      handle.dispose();
    });

    _test('3장이면 썸네일 버튼 3개, 처음엔 1번이 선택(selected)이고 3번을 누르면 큰 사진이 3번으로 바뀐다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9]))));
      expect(_anyThumb, findsNWidgets(3));
      expect(find.bySemanticsLabel(RegExp(r'^사진 \d+ 보기$')), findsNWidgets(3));
      expect(_main(7), findsOneWidget);
      expect(_selected(tester, '사진 1 보기'), isTrue);
      expect(_selected(tester, '사진 2 보기'), isFalse);
      expect(_selected(tester, '사진 3 보기'), isFalse);
      expect(tester.getSemantics(find.bySemanticsLabel('사진 3 보기')).getSemanticsData().flagsCollection.isButton, isTrue);

      await tester.tap(find.bySemanticsLabel('사진 3 보기'));
      await tester.pumpAndSettle();

      expect(_main(9), findsOneWidget);
      expect(_main(7), findsNothing);
      expect(_selected(tester, '사진 3 보기'), isTrue);
      expect(_selected(tester, '사진 1 보기'), isFalse);
      handle.dispose();
    });

    _test('키보드: 첫 썸네일에서 Tab으로 둘째로 가 Enter를 누르면 큰 사진이 2번으로 바뀐다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9]))));
      Focus.of(tester.element(find.descendant(of: _thumb(7), matching: find.byType(Container)).first)).requestFocus();
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.tab);
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(_main(8), findsOneWidget);
      expect(_selected(tester, '사진 2 보기'), isTrue);
      handle.dispose();
    });

    _test('테스트에선 사진 요청이 실패하므로 큰 사진 틀 안에 "사진을 불러오지 못했습니다"가 보이고 썸네일 버튼은 남는다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9]))));
      expect(
        find.descendant(of: _main(7), matching: find.text('사진을 불러오지 못했습니다')),
        findsOneWidget,
      );
      expect(find.bySemanticsLabel('사진 1 / 3'), findsNothing); // 사진이 없으니 이름도 없다
      expect(_anyThumb, findsNWidgets(3));
      // 다른 사진을 고르면 새 위젯(key=사진 id)이라 다시 불러와 본다 (여기서는 같은 이유로 또 실패)
      await tester.tap(find.bySemanticsLabel('사진 2 보기'));
      await tester.pumpAndSettle();
      expect(find.descendant(of: _main(8), matching: find.text('사진을 불러오지 못했습니다')), findsOneWidget);
      expect(find.text('사진을 불러오지 못했습니다'), findsOneWidget);
      handle.dispose();
    });

    _test('3번 사진을 보다가 상태 변경 응답의 사진이 1장으로 줄면 마지막(1번) 사진으로 맞춰지고 썸네일 줄이 사라진다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9])))
        ..on(
          'PUT',
          '/market-items/7/status',
          FakeReply(200, marketDetailJson(photoIds: [7], statusCode: 'RESERVED', statusName: '예약중')),
        );
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(find.bySemanticsLabel('사진 3 보기'));
      await tester.pumpAndSettle();
      expect(_main(9), findsOneWidget);

      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();

      expect(_notice('예약중으로 바꿨습니다'), findsOneWidget);
      expect(_main(7), findsOneWidget);
      expect(_anyThumb, findsNothing);
      handle.dispose();
    });

    _test('2장에서 3번째 자리 선택은 없고, 사진이 2장으로 줄면 선택이 마지막으로 맞춰진다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9])))
        ..on('PUT', '/market-items/7/status', FakeReply(200, marketDetailJson(photoIds: [7, 8], statusCode: 'RESERVED', statusName: '예약중')));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(find.bySemanticsLabel('사진 3 보기'));
      await tester.pumpAndSettle();
      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();
      expect(_main(8), findsOneWidget); // 마지막(2번)
      expect(_anyThumb, findsNWidgets(2));
      expect(_selected(tester, '사진 2 보기'), isTrue);
      handle.dispose();
    });

    _test('다른 글을 열면 선택한 사진이 0번부터 다시 시작한다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter(item: FakeReply(200, marketDetailJson(photoIds: [7, 8, 9])))
        ..on('GET', '/market-items/8', FakeReply(200, marketDetailJson(id: 8, photoIds: [20, 21, 22])));
      final app = await _open(tester, adapter);
      await tester.tap(find.bySemanticsLabel('사진 3 보기'));
      await tester.pumpAndSettle();
      expect(_main(9), findsOneWidget);

      app.router.go('/market/8');
      await tester.pumpAndSettle();
      expect(_main(20), findsOneWidget);
      expect(_selected(tester, '사진 1 보기'), isTrue);
      handle.dispose();
    });
  });

  // 갤러리 부품 하나만 띄운 테스트: 첫 프레임은 아직 사진 요청이 실패하기 전이라 사진 주소·이름을 볼 수 있다
  group('사진 갤러리 부품', () {
    Future<void> pumpGallery(WidgetTester tester, List<int> ids, int index, {void Function(int)? onSelect}) {
      useTallScreen(tester); // 큰 사진(4:3)이 화면 높이를 거의 다 써서, 키 큰 화면이 아니면 썸네일이 화면 밖이다
      return tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: MarketPhotoGallery(
                itemId: 7,
                photos: [for (final id in ids) MarketPhoto(id: id)],
                index: index,
                onSelect: onSelect ?? (_) {},
              ),
            ),
          ),
        ),
      );
    }

    String urlOf(WidgetTester tester, Finder within) =>
        (tester.widget<Image>(find.descendant(of: within, matching: find.byType(Image))).image as NetworkImage).url;

    testWidgets('큰 사진은 본 사진 주소(썸네일 쿼리 없음), 이름 "사진 2 / 3", 4:3 틀 안에 contain', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpGallery(tester, [7, 8, 9], 1);
      expect(urlOf(tester, _main(8)), '/api/market-items/7/photos/8');
      expect(find.bySemanticsLabel('사진 2 / 3'), findsOneWidget);
      final image = tester.widget<Image>(find.descendant(of: _main(8), matching: find.byType(Image)));
      expect(image.fit, BoxFit.contain);
      expect(tester.widget<AspectRatio>(find.descendant(of: _main(8), matching: find.byType(AspectRatio))).aspectRatio, 4 / 3);
      expect(tester.takeException(), isNull);
      handle.dispose();
    });

    testWidgets('1장이면 이름 "사진 1 / 1"이고 썸네일 버튼이 없다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpGallery(tester, [7], 0);
      expect(find.bySemanticsLabel('사진 1 / 1'), findsOneWidget);
      expect(_anyThumb, findsNothing);
      handle.dispose();
    });

    testWidgets('썸네일은 56px 정사각, ?size=thumb 주소, 선택된 것만 청자색 테두리', (tester) async {
      await pumpGallery(tester, [7, 8], 1);
      expect(tester.getSize(_thumb(7)), const Size(56, 56));
      expect(urlOf(tester, _thumb(8)), '/api/market-items/7/photos/8?size=thumb');
      Color border(int id) {
        final box = tester
            .widget<Container>(find.descendant(of: _thumb(id), matching: find.byType(Container)).first)
            .decoration! as BoxDecoration;
        return (box.border! as Border).top.color;
      }

      expect(border(8), AppColors.celadon);
      expect(border(7), Colors.transparent);
    });

    testWidgets('썸네일을 누르면 onSelect에 그 번호(0부터)가 간다', (tester) async {
      final picked = <int>[];
      await pumpGallery(tester, [7, 8, 9], 0, onSelect: picked.add);
      await tester.tap(_thumb(9));
      expect(picked, [2]);
    });

    testWidgets('사진 요청이 실패하면 같은 틀 안에 회색 안내 글, 이름은 사라지고 에러는 없다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpGallery(tester, [7, 8], 0);
      await tester.pumpAndSettle();
      final text = find.descendant(of: _main(7), matching: find.text('사진을 불러오지 못했습니다'));
      expect(text, findsOneWidget);
      expect(tester.widget<Text>(text).style!.color, AppColors.muted);
      expect(find.bySemanticsLabel('사진 1 / 2'), findsNothing);
      expect(tester.getSize(_main(7)).width / tester.getSize(_main(7)).height, closeTo(4 / 3, 0.01)); // 틀 크기 그대로
      expect(tester.takeException(), isNull);
      handle.dispose();
    });
  });

  group('버튼 조건 표', () {
    // (설명, 로그인 사용자, 글 응답, 보일 상태 버튼 글자, 수정, 삭제, 신고)
    final cases = <(String, Map<String, dynamic>?, Map<String, dynamic>, List<String>, bool, bool, bool)>[
      ('판매자·판매중', _seoulUser(), marketDetailJson(), ['예약중으로', '거래 완료'], true, true, false),
      (
        '판매자·예약중',
        _seoulUser(),
        marketDetailJson(statusCode: 'RESERVED', statusName: '예약중'),
        ['판매중으로', '거래 완료'],
        true,
        true,
        false,
      ),
      (
        '판매자·거래완료: 삭제만',
        _seoulUser(),
        marketDetailJson(statusCode: 'DONE', statusName: '거래완료'),
        [],
        false,
        true,
        false,
      ),
      (
        '판매자·모르는 상태: 상태 버튼 없음, 수정·삭제는 있음',
        _seoulUser(),
        marketDetailJson(statusCode: 'SOLD_OUT', statusName: '품절'),
        [],
        true,
        true,
        false,
      ),
      ('관리자(남의 글)·판매중: 삭제만 + 신고는 같은 학교라 보임', _seoulUser(role: 'ADMIN', id: 9), marketDetailJson(), [], false, true, true),
      (
        '관리자(남의 글)·거래완료: 삭제만',
        _seoulUser(role: 'ADMIN', id: 9),
        marketDetailJson(statusCode: 'DONE', statusName: '거래완료'),
        [],
        false,
        true,
        true,
      ),
      ('관리자인데 내 글: 일반 판매자와 같다', _seoulUser(role: 'ADMIN'), marketDetailJson(), ['예약중으로', '거래 완료'], true, true, false),
      ('일반 회원(남의 글): 판매자 버튼 없음, 신고만', _seoulUser(id: 5), marketDetailJson(), [], false, false, true),
      ('비로그인: 아무 버튼도 없다', null, marketDetailJson(), [], false, false, false),
    ];
    for (final (name, user, item, statuses, edit, delete, report) in cases) {
      _test(name, (tester) async {
        await _open(tester, _adapter(item: FakeReply(200, item)), user: user);
        for (final label in ['예약중으로', '판매중으로', '거래 완료']) {
          expect(_btn(label), statuses.contains(label) ? findsOneWidget : findsNothing, reason: label);
        }
        expect(_edit, edit ? findsOneWidget : findsNothing);
        expect(_delete, delete ? findsOneWidget : findsNothing);
        expect(_report, report ? findsOneWidget : findsNothing);
        expect(_toList, findsOneWidget);
      });
    }

    _test('[수정]을 누르면 /market/7/edit 으로 간다', (tester) async {
      final app = await _open(tester, _adapter(), user: _seoulUser());
      await tester.tap(_edit);
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/7/edit');
    });

    _test('삭제 버튼은 위험(빨강) 모양, 상태·수정 버튼은 보통 작은 모양', (tester) async {
      await _open(tester, _adapter(), user: _seoulUser());
      expect(tester.widget<OutlinedButton>(_delete).style!.foregroundColor!.resolve({}), AppColors.danger);
      expect(tester.widget<OutlinedButton>(_btn('예약중으로')).style!.minimumSize!.resolve({})!.height, 36);
    });
  });

  group('상태 변경', () {
    FakeAdapter adapterWith(FakeReply put) => _adapter()..on('PUT', '/market-items/7/status', put);

    _test('[예약중으로] → 확인 창 없이 PUT {"status":"RESERVED"}, 뱃지·버튼이 응답으로 바뀌고 성공 안내(success)', (tester) async {
      final bodies = <Object?>[];
      final adapter = adapterWith(FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중')))
        ..onRequestBody = _collect(bodies);
      await _open(tester, adapter, user: _seoulUser());
      expect(find.text('판매중'), findsOneWidget);

      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();

      expect(find.byType(Dialog), findsNothing);
      expect(bodies, [
        {'status': 'RESERVED'},
      ]);
      expect(adapter.count('PUT', '/market-items/7/status'), 1);
      expect(find.text('예약중'), findsOneWidget); // 뱃지
      expect(find.text('판매중'), findsNothing);
      expect(_btn('판매중으로'), findsOneWidget);
      expect(_btn('예약중으로'), findsNothing);
      expect(_notice('예약중으로 바꿨습니다'), findsOneWidget);
      expect(_kind(tester, '예약중으로 바꿨습니다'), NoticeKind.success);
    });

    _test('[판매중으로] → PUT {"status":"ON_SALE"}, "판매중으로 바꿨습니다"', (tester) async {
      final bodies = <Object?>[];
      final adapter = _adapter(item: FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중')))
        ..on('PUT', '/market-items/7/status', FakeReply(200, marketDetailJson()))
        ..onRequestBody = _collect(bodies);
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('판매중으로'));
      await tester.pumpAndSettle();
      expect(bodies, [
        {'status': 'ON_SALE'},
      ]);
      expect(_notice('판매중으로 바꿨습니다'), findsOneWidget);
      expect(find.text('판매중'), findsOneWidget);
    });

    _test('[거래 완료] → 확인 창 "거래 완료로 바꾸면 되돌릴 수 없습니다. 완료할까요?", [취소]면 요청 없이 그대로', (tester) async {
      final adapter = adapterWith(FakeReply(200, marketDetailJson()));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('거래 완료'));
      await tester.pumpAndSettle();
      expect(find.text('거래 완료로 바꾸면 되돌릴 수 없습니다. 완료할까요?'), findsOneWidget);
      // 위험 모양 확인 버튼 (글자는 "거래 완료")
      expect(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '거래 완료')), findsOneWidget);
      await tester.tap(_dialogText('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(adapter.count('PUT', '/market-items/7/status'), 0);
      expect(find.text('판매중'), findsOneWidget);
      expect(find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.success), findsNothing);
    });

    _test('거래 완료를 확인하면 PUT {"status":"DONE"}, 상태 버튼과 [수정]이 사라지고 [삭제]만 남으며 "거래 완료로 바꿨습니다"', (tester) async {
      final bodies = <Object?>[];
      final adapter = adapterWith(FakeReply(200, marketDetailJson(statusCode: 'DONE', statusName: '거래완료')))
        ..onRequestBody = _collect(bodies);
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('거래 완료'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '거래 완료')));
      await tester.pumpAndSettle();

      expect(bodies, [
        {'status': 'DONE'},
      ]);
      expect(find.text('거래완료'), findsOneWidget);
      expect(_btn('거래 완료'), findsNothing);
      expect(_btn('예약중으로'), findsNothing);
      expect(_edit, findsNothing);
      expect(_delete, findsOneWidget);
      expect(_notice('거래 완료로 바꿨습니다'), findsOneWidget);
    });

    _test('요청 중에는 상태 버튼이 "처리 중..."으로 바뀌고 [수정]·[삭제]까지 모두 잠기며 요청은 한 번만 나간다', (tester) async {
      final gate = Completer<void>();
      final adapter = adapterWith(FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중'), gate));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('예약중으로'));
      await tester.pump(const Duration(milliseconds: 50));

      expect(_btn('처리 중...'), findsNWidgets(2)); // 상태 버튼 둘 다
      for (final f in [_btn('처리 중...').first, _btn('처리 중...').last, _edit, _delete]) {
        expect(tester.widget<OutlinedButton>(f).onPressed, isNull);
      }
      await tester.tap(_btn('처리 중...').first, warnIfMissed: false);
      await tester.pump();
      expect(adapter.count('PUT', '/market-items/7/status'), 1);

      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.widget<OutlinedButton>(_delete).onPressed, isNotNull); // 성공해도 화면에 남으니 되살아난다
      expect(_btn('판매중으로'), findsOneWidget);
    });

    // (응답, 기대 문구)
    for (final (status, message) in const [
      (403, '상태를 바꾸지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      (404, '이미 삭제된 글입니다'),
      (409, '그 사이 상태가 바뀌었습니다. 새로고침해 주세요.'),
      (500, '상태를 바꾸지 못했습니다'),
    ]) {
      _test('PUT $status → "$message"(error), 글은 그대로고 버튼은 되살아나며 성공 안내는 없다', (tester) async {
        await _open(tester, adapterWith(FakeReply(status)), user: _seoulUser());
        await tester.tap(_btn('예약중으로'));
        await tester.pumpAndSettle();
        expect(_notice(message), findsOneWidget);
        expect(_kind(tester, message), NoticeKind.error);
        expect(find.text('판매중'), findsOneWidget);
        expect(find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.success), findsNothing);
        expect(tester.widget<OutlinedButton>(_btn('예약중으로')).onPressed, isNotNull);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    _test('PUT 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      await _open(tester, adapterWith(FakeReply.networkError()), user: _seoulUser());
      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('앞의 성공 안내는 다음 요청이 실패하면 지워진다', (tester) async {
      final adapter = adapterWith(FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중')))
        ..on('PUT', '/market-items/7/status', FakeReply(409));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();
      expect(_notice('예약중으로 바꿨습니다'), findsOneWidget);
      await tester.tap(_btn('판매중으로'));
      await tester.pumpAndSettle();
      expect(_notice('예약중으로 바꿨습니다'), findsNothing);
      expect(_notice('그 사이 상태가 바뀌었습니다. 새로고침해 주세요.'), findsOneWidget);
    });

    _test('PUT 401 + 재발급 401 → 로그인이 풀려 버튼이 사라져도 "다시 로그인해 주세요"가 남고 주소는 그대로', (tester) async {
      final adapter = adapterWith(FakeReply(401))..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_btn('예약중으로'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(_btn('예약중으로'), findsNothing);
      expect(find.text('상태를 바꾸지 못했습니다'), findsNothing);
      expect(_uri(app), '/market/7');
    });
  });

  group('삭제', () {
    FakeAdapter withList(FakeAdapter a) => a
      ..on('GET', '/schools', FakeReply(200, marketSchools))
      ..on('GET', '/market-items', FakeReply(200, []));

    _test('[삭제] → 확인 창 "이 글을 삭제할까요?" → [취소]면 요청 없이 그대로', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      expect(find.text('이 글을 삭제할까요?'), findsOneWidget);
      await tester.tap(_dialogText('취소'));
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/market-items/7'), 0);
      expect(_uri(app), '/market/7');
      expect(_delete, findsOneWidget);
    });

    _test('Esc로 닫아도 취소와 같다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/market-items/7'), 0);
    });

    _test('확인하면 "삭제 중..."으로 모두 잠기고 요청은 한 번, 성공하면 /market 으로 가며 기록을 교체한다', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = withList(_adapter())..on('DELETE', '/market-items/7', FakeReply(204, null, gate));
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pump(const Duration(milliseconds: 50));

      expect(_btn('삭제 중...'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_btn('삭제 중...')).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_edit).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_btn('예약중으로')).onPressed, isNull);
      expect(adapter.count('DELETE', '/market-items/7'), 1);
      expect(_uri(app), '/market/7');

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/market');
      expect(nav.last.path, '/market');
      expect(nav.last.replace, isTrue); // 삭제된 글의 상세 칸을 덮어쓴다
    });

    for (final (status, mine, message) in const [
      (403, true, '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      (403, false, '삭제할 권한이 없습니다'),
      (404, true, '이미 삭제된 글입니다'),
      (500, true, '삭제하지 못했습니다'),
    ]) {
      _test('DELETE $status (${mine ? '본인' : '관리자가 남의 글'}) → "$message", 글은 남고 머문다, 버튼 되살아남', (tester) async {
        final user = mine ? _seoulUser() : _seoulUser(role: 'ADMIN', id: 9);
        final adapter = _adapter()..on('DELETE', '/market-items/7', FakeReply(status));
        final app = await _open(tester, adapter, user: user);
        await tester.tap(_delete);
        await tester.pumpAndSettle();
        await tester.tap(_dialogText('삭제'));
        await tester.pumpAndSettle();

        expect(_notice(message), findsOneWidget);
        expect(_kind(tester, message), NoticeKind.error);
        expect(_uri(app), '/market/7');
        expect(find.text('전공 서적 팝니다'), findsOneWidget);
        expect(tester.widget<OutlinedButton>(_delete).onPressed, isNotNull);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    _test('DELETE 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('DELETE', '/market-items/7', FakeReply.networkError());
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('DELETE 401 + 재발급 401 → "다시 로그인해 주세요", "삭제하지 못했습니다"는 없다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/market-items/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing);
    });

    _test('삭제 실패 뒤 상태 변경을 시작하면 앞의 실패 안내는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/market-items/7', FakeReply(500))
        ..on('PUT', '/market-items/7/status', FakeReply(200, marketDetailJson(statusCode: 'RESERVED', statusName: '예약중')));
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      await tester.tap(_btn('예약중으로'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      expect(_notice('예약중으로 바꿨습니다'), findsOneWidget);
    });
  });

  group('신고 연결 (상세 ↔ ReportForm)', () {
    final reasons = FakeReply(200, [
      {'code': 'SPAM', 'name': '광고·스팸'},
    ]);

    Future<void> openAndPick(WidgetTester tester) async {
      await tester.tap(_report);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('report-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('광고·스팸').last);
      await tester.pumpAndSettle();
    }

    // (설명, 로그인 사용자, 글, 신고 버튼 보임)
    final cases = <(String, Map<String, dynamic>?, Map<String, dynamic>, bool)>[
      ('같은 학교 인증 회원이 남의 글: 보인다', _seoulUser(id: 5), marketDetailJson(), true),
      ('본인 글: 없다', _seoulUser(), marketDetailJson(), false),
      ('비로그인: 없다', null, marketDetailJson(), false),
      ('학교 미인증 회원: 없다', userJson(id: 5), marketDetailJson(), false),
      ('다른 학교로 인증한 회원: 없다', userJson(id: 5, school: marketBusan), marketDetailJson(), false),
    ];
    for (final (name, user, item, visible) in cases) {
      _test(name, (tester) async {
        await _open(tester, _adapter(item: FakeReply(200, item)), user: user);
        expect(_report, visible ? findsOneWidget : findsNothing);
      });
    }

    _test('신고 열기 → 사유 선택 → [신고 보내기]하면 POST /market-items/7/reports 가 한 번 나가고 성공 문구', (tester) async {
      final bodies = <Object?>[];
      final adapter = _adapter()
        ..on('GET', '/report-reasons', reasons)
        ..on('POST', '/market-items/7/reports', FakeReply(201))
        ..onRequestBody = _collect(bodies);
      await _open(tester, adapter, user: _seoulUser(id: 5));
      await openAndPick(tester);
      await tester.tap(_btn('신고 보내기'));
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/market-items/7/reports'), 1);
      expect(bodies.single, {'reason': 'SPAM'});
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
    });

    _test('label이 "중고 글"이라 409는 "이미 신고한 중고 글입니다", 404는 "이미 삭제된 중고 글입니다"', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/report-reasons', reasons)
        ..on('POST', '/market-items/7/reports', FakeReply(409));
      await _open(tester, adapter, user: _seoulUser(id: 5));
      await openAndPick(tester);
      await tester.tap(_btn('신고 보내기'));
      await tester.pumpAndSettle();
      expect(find.text('이미 신고한 중고 글입니다'), findsOneWidget);
    });

    _test('404이면 "이미 삭제된 중고 글입니다"', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/report-reasons', reasons)
        ..on('POST', '/market-items/7/reports', FakeReply(404));
      await _open(tester, adapter, user: _seoulUser(id: 5));
      await openAndPick(tester);
      await tester.tap(_btn('신고 보내기'));
      await tester.pumpAndSettle();
      expect(find.text('이미 삭제된 중고 글입니다'), findsOneWidget);
    });

    _test('신고 401 + 재발급 401 → 신고 양식이 사라져도 "다시 로그인해 주세요"가 보이고 주소는 그대로', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/report-reasons', reasons)
        ..on('POST', '/market-items/7/reports', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter, user: _seoulUser(id: 5));
      await openAndPick(tester);
      await tester.tap(_btn('신고 보내기'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('신고하지 못했습니다'), findsNothing);
      expect(_uri(app), '/market/7');
    });
  });

  // 글쓰기(S16-k1)는 사진을 못 올렸으면 `context.go('/market/{id}', extra: 못 올린 장수)`로 넘어온다
  group('글쓰기에서 넘어온 사진 실패 안내', () {
    const message2 = '사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요';

    // 다른 화면(자리표시 /admin/reports)에서 시작해, 글쓰기가 하는 것처럼 extra를 붙여 상세로 간다
    Future<(TestApp, NavLog)> arrive(WidgetTester tester, FakeAdapter adapter, Object? extra) async {
      final nav = NavLog()..install(tester);
      final app = await pumpApp(tester, location: '/admin/reports', user: _seoulUser(), adapter: adapter);
      app.router.go('/market/7', extra: extra);
      await tester.pumpAndSettle();
      return (app, nav);
    }

    _test('안내 "사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요"(error)가 보이고, 주소는 extra 없는 같은 주소로 "교체"된다', (tester) async {
      final (app, nav) = await arrive(tester, _adapter(), 2);
      expect(_notice(message2), findsOneWidget);
      expect(_kind(tester, message2), NoticeKind.error);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
      expect(_uri(app), '/market/7');
      expect(app.router.routerDelegate.currentConfiguration.extra, isNull);
      expect(nav.last.path, '/market/7');
      expect(nav.last.replace, isTrue);
      // 글은 한 번만 불러온다 (주소를 교체해도 화면은 새로 만들어지지 않는다)
      expect(app.adapter.count('GET', '/market-items/7'), 1);
    });

    _test('1장이면 "사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요"', (tester) async {
      await arrive(tester, _adapter(), 1);
      expect(find.text('사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요'), findsOneWidget);
    });

    _test('한 번만 보인다: 다른 화면에 갔다가 같은 글로 돌아오면(extra 없음) 안내가 없다', (tester) async {
      final adapter = _adapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson()));
      final (app, _) = await arrive(tester, adapter, 2);
      expect(_notice(message2), findsOneWidget);
      app.router.go('/admin/reports');
      await tester.pumpAndSettle();
      app.router.go('/market/7');
      await tester.pumpAndSettle();
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
    });

    _test('다른 글(/market/8)로 옮기면 안내는 0으로 사라진다', (tester) async {
      final adapter = _adapter()..on('GET', '/market-items/8', FakeReply(200, marketDetailJson(id: 8, title: '다른 글')));
      final (app, _) = await arrive(tester, adapter, 2);
      app.router.go('/market/8');
      await tester.pumpAndSettle();
      expect(find.text('다른 글'), findsOneWidget);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
    });

    _test('0이면 안내는 없지만 기록은 교체된다 (실패한 사진이 없을 때도 extra는 지운다)', (tester) async {
      final (app, nav) = await arrive(tester, _adapter(), 0);
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
      expect(nav.last.replace, isTrue);
      expect(app.router.routerDelegate.currentConfiguration.extra, isNull);
    });

    _test('모르는 모양의 extra(숫자가 아님)는 안내 없이 무시하고 기록도 건드리지 않는다', (tester) async {
      final (_, nav) = await arrive(tester, _adapter(), '엉뚱한 값');
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
      expect(nav.last.replace, isFalse);
    });

    _test('extra 없이 직접 들어오면 안내도 기록 교체도 없다', (tester) async {
      final nav = NavLog()..install(tester);
      await _open(tester, _adapter());
      expect(find.textContaining('올리지 못했습니다'), findsNothing);
      // 처음 주소를 알린 한 번뿐이고, 주소를 다시 교체하는 보고는 없다
      expect(nav.entries.map((e) => e.path), ['/market/7']);
    });

    _test('글을 못 불러와도(404) 안내는 보이고, 글이 있을 때처럼 [목록으로]도 있다', (tester) async {
      await arrive(tester, _adapter(item: FakeReply(404)), 2);
      expect(_notice(message2), findsOneWidget);
      expect(find.text('글을 찾을 수 없습니다'), findsOneWidget);
      expect(_toList, findsOneWidget);
    });
  });

  group('375px 화면', () {
    testWidgets('긴 제목·긴 학교 이름·긴 본문·사진 10장·판매자 버튼이 있어도 가로로 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      final item = marketDetailJson(
        title: '아주아주 긴 제목의 중고 물건을 팝니다 ' * 4,
        content: 'NoSpaceVeryLongContent' * 12,
        school: {'id': 1, 'name': '아주아주아주 긴 이름의 대학교 대학원 캠퍼스'},
        price: 10000000,
        sellerNickname: '닉네임이아주아주길어요' * 3,
        photoIds: [for (var i = 1; i <= 10; i++) i],
      );
      await _open(tester, _adapter(item: FakeReply(200, item)), user: _seoulUser());
      expect(tester.takeException(), isNull);
      expect(_btn('예약중으로'), findsOneWidget);
      expect(_anyThumb, findsNWidgets(10));
      expect(tester.getSize(_mainAny).width, lessThanOrEqualTo(375));
    });

    testWidgets('신고 양식이 열려도, 비로그인이어도 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      final adapter = _adapter()
        ..on('GET', '/report-reasons', FakeReply(200, [
          {'code': 'SPAM', 'name': '광고·스팸'},
        ]));
      await _open(tester, adapter, user: _seoulUser(id: 5));
      await tester.tap(_report);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}
