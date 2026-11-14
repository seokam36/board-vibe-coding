// S16-j1 중고·나눔 목록 화면 테스트 (React의 market.spec / market-search.spec / market-photos.spec 목록 부분):
// 필터 3개↔주소↔요청, q 유지, 검색(Enter·버튼·빈칸·trim·뒤로 가기 동기화·포커스·50자), 임시 항목,
// 0건 두 문구, 에러 4종, 썸네일 있음·없음·실패, 뱃지 색, 가격, 글쓰기 버튼·게이트, 375px, 늦은 응답.
import 'dart:async';

import 'package:board_web/market/market_format.dart';
import 'package:board_web/market/market_thumb.dart';
import 'package:board_web/market/models.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

final _schoolSelect = find.byKey(const ValueKey('filter-schoolId'));
final _tradeSelect = find.byKey(const ValueKey('filter-tradeType'));
final _statusSelect = find.byKey(const ValueKey('filter-status'));
final _searchField = find.byKey(const ValueKey('market-search'));

// 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pick(WidgetTester tester, Finder select, String text) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

// 검색창의 지금 글자
String _inputText(WidgetTester tester) => tester.widget<TextField>(_searchField).controller!.text;

// 검색창에 포커스가 있는지
bool _searchFocused(WidgetTester tester) =>
    tester.widget<EditableText>(find.descendant(of: _searchField, matching: find.byType(EditableText))).focusNode.hasFocus;

// 글자를 쓰고 Enter(키보드의 검색 동작)를 보낸다
Future<void> _typeAndEnter(WidgetTester tester, String text) async {
  await tester.enterText(_searchField, text);
  await tester.testTextInput.receiveAction(TextInputAction.search);
  await tester.pumpAndSettle();
}

Map<String, String> _lastQuery(FakeAdapter a) => a.callsTo('GET', '/market-items').last.query;

void main() {
  group('머리말·주소 쿼리 → 요청', () {
    testWidgets('머리말은 "중고·나눔", 쿼리가 없으면 조건 없이 요청하고 학교 목록은 한 번만', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      // 상단바 메뉴에도 같은 글자가 있어, 머리말(30px 글자)만 골라 확인한다
      expect(
        find.byWidgetPredicate((w) => w is Text && w.data == '중고·나눔' && w.style?.fontSize == AppSizes.fsBoard),
        findsOneWidget,
      );
      expect(adapter.callsTo('GET', '/market-items').single.query, isEmpty);
      expect(adapter.count('GET', '/schools'), 1);
    });

    testWidgets('네 조건이 그대로 쿼리로 가고, 드롭다운과 검색창이 주소 값을 보여 준다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      await pumpApp(
        tester,
        location: '/market?schoolId=2&tradeType=FREE&status=RESERVED&q=%EC%B1%85',
        adapter: adapter,
      );
      expect(adapter.callsTo('GET', '/market-items').single.query, {
        'schoolId': '2',
        'tradeType': 'FREE',
        'status': 'RESERVED',
        'q': '책',
      });
      expect(find.text('부산대'), findsOneWidget);
      expect(find.text('나눔'), findsOneWidget);
      expect(find.text('예약중'), findsOneWidget);
      expect(_inputText(tester), '책');
    });

    testWidgets('드롭다운 3개의 글자: 전체 항목과 고정 선택지', (tester) async {
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [])]));
      expect(find.text('전체 학교'), findsOneWidget);
      expect(find.text('판매·나눔 전체'), findsOneWidget);
      expect(find.text('전체 상태'), findsOneWidget);

      await tester.tap(_tradeSelect);
      await tester.pumpAndSettle();
      expect(find.text('판매'), findsOneWidget);
      expect(find.text('나눔'), findsOneWidget);
      await tester.tap(find.text('판매·나눔 전체').last);
      await tester.pumpAndSettle();

      await tester.tap(_statusSelect);
      await tester.pumpAndSettle();
      expect(find.text('판매중'), findsOneWidget);
      expect(find.text('예약중'), findsOneWidget);
      expect(find.text('거래완료'), findsOneWidget);
    });
  });

  group('드롭다운 → 주소 → 재요청', () {
    testWidgets('학교·판매나눔·상태를 고르면 주소가 바뀌고 그 조건으로 다시 요청한다 (학교 목록은 다시 안 부른다)', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);

      await _pick(tester, _schoolSelect, '서울대');
      expect(_uri(app), '/market?schoolId=1');
      expect(_lastQuery(adapter), {'schoolId': '1'});

      await _pick(tester, _tradeSelect, '판매');
      expect(_uri(app), '/market?schoolId=1&tradeType=SALE');

      await _pick(tester, _statusSelect, '거래완료');
      expect(_uri(app), '/market?schoolId=1&tradeType=SALE&status=DONE');
      expect(_lastQuery(adapter), {'schoolId': '1', 'tradeType': 'SALE', 'status': 'DONE'});
      expect(adapter.count('GET', '/market-items'), 4);
      expect(adapter.count('GET', '/schools'), 1);
    });

    testWidgets('"전체"를 고르면 그 파라미터가 주소에서 빠진다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market?schoolId=1&tradeType=SALE&status=DONE', adapter: adapter);

      await _pick(tester, _statusSelect, '전체 상태');
      expect(_uri(app), '/market?schoolId=1&tradeType=SALE');
      await _pick(tester, _tradeSelect, '판매·나눔 전체');
      expect(_uri(app), '/market?schoolId=1');
      await _pick(tester, _schoolSelect, '전체 학교');
      expect(_uri(app), '/market');
      expect(_lastQuery(adapter), isEmpty);
    });

    testWidgets('드롭다운을 바꿔도 검색어 q는 주소와 요청에 그대로 남는다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market?q=%EC%B1%85', adapter: adapter);
      await _pick(tester, _statusSelect, '판매중');
      expect(_uri(app), '/market?q=%EC%B1%85&status=ON_SALE');
      expect(_lastQuery(adapter), {'q': '책', 'status': 'ON_SALE'});
      expect(_inputText(tester), '책');
    });

    testWidgets('주소가 직접 바뀌어도(뒤로 가기 등) 다시 요청하고 드롭다운이 따라간다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      app.router.go('/market?status=RESERVED');
      await tester.pumpAndSettle();
      expect(_lastQuery(adapter), {'status': 'RESERVED'});
      expect(find.text('예약중'), findsOneWidget);
    });
  });

  group('임시 항목', () {
    testWidgets('목록에 없는 학교·판매나눔·상태는 "알 수 없는 ..." 항목으로 보인다', (tester) async {
      await pumpApp(
        tester,
        location: '/market?schoolId=99&tradeType=XX&status=YY',
        adapter: marketAdapter([FakeReply(200, [])]),
      );
      expect(find.text('알 수 없는 학교 (99)'), findsOneWidget);
      expect(find.text('알 수 없는 판매/나눔 (XX)'), findsOneWidget);
      expect(find.text('알 수 없는 상태 (YY)'), findsOneWidget);
    });

    testWidgets('"전체"를 고르면 임시 항목에서 빠져나온다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market?status=YY', adapter: adapter);
      await _pick(tester, _statusSelect, '전체 상태');
      expect(_uri(app), '/market');
      expect(find.textContaining('알 수 없는'), findsNothing);
    });

    testWidgets('학교 목록을 받는 중이면 "불러오는 중...", 받고 나면 학교 이름', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, marketSchools, gate))
        ..on('GET', '/market-items', FakeReply(200, []));
      await pumpApp(tester, location: '/market?schoolId=1', adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.textContaining('알 수 없는'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('서울대'), findsOneWidget);
    });

    testWidgets('학교 목록이 500이면 "선택지 목록을 불러오지 못했습니다" 안내, 글 목록은 그대로 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(500))
        ..on('GET', '/market-items', FakeReply(200, [marketItemJson()]));
      await pumpApp(tester, location: '/market?schoolId=1', adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('알 수 없는 학교 (1)'), findsOneWidget);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
    });
  });

  group('검색', () {
    testWidgets('입력만으로는 요청도 주소 변경도 없다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      await tester.enterText(_searchField, '자전거');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/market-items'), 1);
      expect(_uri(app), '/market');
    });

    testWidgets('라벨 "제목 검색어", 안내 글자 "제목으로 검색", [검색] 버튼이 있다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [])]));
      expect(find.bySemanticsLabel(RegExp('제목 검색어')), findsOneWidget);
      expect(find.text('제목으로 검색'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '검색'), findsOneWidget);
      handle.dispose();
    });

    testWidgets('Enter를 누르면 q가 주소에 들어가고 그 조건으로 요청한다 (다른 필터는 그대로)', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market?status=ON_SALE', adapter: adapter);
      await _typeAndEnter(tester, '자전거');
      expect(_uri(app), '/market?status=ON_SALE&q=%EC%9E%90%EC%A0%84%EA%B1%B0');
      expect(_lastQuery(adapter), {'status': 'ON_SALE', 'q': '자전거'});
    });

    testWidgets('[검색] 버튼으로도 제출된다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      await tester.enterText(_searchField, '노트북');
      await tester.tap(find.widgetWithText(OutlinedButton, '검색'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/market?q=%EB%85%B8%ED%8A%B8%EB%B6%81');
      expect(_lastQuery(adapter), {'q': '노트북'});
    });

    testWidgets('앞뒤 공백은 trim해서 주소·요청에 넣는다 (가운데 공백은 그대로)', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      await _typeAndEnter(tester, '  전공  책  ');
      expect(Uri.parse(_uri(app)).queryParameters['q'], '전공  책');
      expect(_lastQuery(adapter), {'q': '전공  책'});
    });

    testWidgets('빈 값이나 공백뿐이면 q를 주소에서 빼고 조건 없이 요청한다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market?q=%EC%B1%85', adapter: adapter);
      await _typeAndEnter(tester, '   ');
      expect(_uri(app), '/market');
      expect(_lastQuery(adapter), isEmpty);

      app.router.go('/market?q=%EC%B1%85&status=DONE');
      await tester.pumpAndSettle();
      await _typeAndEnter(tester, '');
      expect(_uri(app), '/market?status=DONE');
    });

    testWidgets('주소의 q가 바뀌면(뒤로 가기 포함) 입력칸 글자가 따라가고, 쓰다 만 글자는 덮어쓴다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      await _typeAndEnter(tester, '자전거');
      expect(_inputText(tester), '자전거');

      app.router.go('/market?q=%EC%B1%85'); // 다른 q로 이동
      await tester.pumpAndSettle();
      expect(_inputText(tester), '책');

      await tester.enterText(_searchField, '쓰다만'); // 제출하지 않은 글자
      app.router.go('/market'); // q가 사라짐 → 칸도 빈칸
      await tester.pumpAndSettle();
      expect(_inputText(tester), '');
    });

    testWidgets('Enter 뒤에도 검색창에 포커스가 남아 있다 (칸을 다시 만들지 않는다)', (tester) async {
      final adapter = marketAdapter([FakeReply(200, []), FakeReply(200, [])]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      await _typeAndEnter(tester, '자전거');
      expect(_searchFocused(tester), isTrue);
      expect(_inputText(tester), '자전거');
    });

    testWidgets('검색어는 UTF-16 50칸까지만 입력된다 (이모지는 2칸)', (tester) async {
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [])]));
      await tester.enterText(_searchField, 'a' * 60);
      expect(_inputText(tester).length, 50);
      await tester.enterText(_searchField, '${'a' * 49}😀');
      expect(_inputText(tester), 'a' * 49); // 반쪽이 남을 이모지는 통째로 뺀다
    });
  });

  group('빈 목록·에러', () {
    testWidgets('검색어가 없고 0건이면 "아직 올라온 글이 없습니다"', (tester) async {
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [])]));
      expect(find.text('아직 올라온 글이 없습니다'), findsOneWidget);
      expect(find.text('검색 결과가 없습니다'), findsNothing);
    });

    testWidgets('검색어가 있고 0건이면 "검색 결과가 없습니다"', (tester) async {
      await pumpApp(tester, location: '/market?q=zzz', adapter: marketAdapter([FakeReply(200, [])]));
      expect(find.text('검색 결과가 없습니다'), findsOneWidget);
      expect(find.text('아직 올라온 글이 없습니다'), findsNothing);
    });

    testWidgets('불러오는 동안은 "불러오는 중..."만 보이고 빈 목록 문구는 없다', (tester) async {
      final gate = Completer<void>();
      final adapter = marketAdapter([FakeReply(200, [], gate)]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.text('아직 올라온 글이 없습니다'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
    });

    testWidgets('400 + 검색어 있음: 검색어 50자 안내까지 붙은 문구', (tester) async {
      await pumpApp(tester, location: '/market?q=x', adapter: marketAdapter([FakeReply(400)]));
      expect(
        find.text('잘못된 조건입니다. 학교·판매/나눔·상태를 다시 고르고 검색어는 50자 이하로 써 주세요'),
        findsOneWidget,
      );
    });

    testWidgets('400 + 검색어 없음: 짧은 문구', (tester) async {
      await pumpApp(tester, location: '/market?status=XX', adapter: marketAdapter([FakeReply(400)]));
      expect(find.text('잘못된 조건입니다. 학교·판매/나눔·상태를 다시 골라 주세요'), findsOneWidget);
    });

    testWidgets('404는 "존재하지 않는 학교입니다"', (tester) async {
      await pumpApp(tester, location: '/market?schoolId=999', adapter: marketAdapter([FakeReply(404)]));
      expect(find.text('존재하지 않는 학교입니다'), findsOneWidget);
    });

    testWidgets('500은 "글 목록을 불러오지 못했습니다"', (tester) async {
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(500)]));
      expect(find.text('글 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('아직 올라온 글이 없습니다'), findsNothing);
    });

    testWidgets('네트워크 오류는 "서버에 연결할 수 없습니다"', (tester) async {
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply.networkError()]));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    testWidgets('에러 뒤에 조건을 바꾸면 에러가 사라지고 새 결과가 보인다', (tester) async {
      final adapter = marketAdapter([FakeReply(500), FakeReply(200, [marketItemJson()])]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.text('글 목록을 불러오지 못했습니다'), findsOneWidget);
      app.router.go('/market?status=ON_SALE');
      await tester.pumpAndSettle();
      expect(find.text('글 목록을 불러오지 못했습니다'), findsNothing);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
    });
  });

  group('행', () {
    testWidgets('상태 뱃지(서버 name), 제목, 가격, 학교, 작성일이 보이고 작성자는 없다', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [marketItemJson()]),
      ]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.text('판매중'), findsOneWidget);
      expect(find.text('전공 서적 팝니다'), findsOneWidget);
      expect(find.text('15,000원'), findsOneWidget);
      // 학교 이름은 드롭다운(서울대 항목은 닫혀 있어 안 보임)과 별개로 행에 한 번 보인다
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('2020년 11월 8일 15:19'), findsOneWidget); // 올해가 아니면 연도가 붙는다 (formatDate)
      expect(find.text('길동이'), findsNothing);
    });

    testWidgets('제목을 누르면 /market/{id} 로 간다', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [marketItemJson(id: 42, title: '자전거')]),
      ]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      await tester.tap(find.text('자전거'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/42');
    });

    testWidgets('나눔 글은 가격 대신 "나눔"', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [marketItemJson(tradeCode: 'FREE', tradeName: '나눔', price: 0, title: '책상 나눔')]),
      ]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.text('나눔'), findsOneWidget);
      expect(find.text('0원'), findsNothing);
    });

    testWidgets('여러 건이 순서대로 보인다', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [marketItemJson(id: 3, title: '셋째'), marketItemJson(id: 2, title: '둘째'), marketItemJson(id: 1, title: '첫째')]),
      ]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      final ys = [for (final t in ['셋째', '둘째', '첫째']) tester.getTopLeft(find.text(t)).dy];
      expect(ys[0] < ys[1] && ys[1] < ys[2], isTrue);
    });

    test('가격 표기: 나눔·쉼표·작은 값·큰 값', () {
      expect(formatPrice('FREE', 0), '나눔');
      expect(formatPrice('FREE', 5000), '나눔');
      expect(formatPrice('SALE', 1), '1원');
      expect(formatPrice('SALE', 999), '999원');
      expect(formatPrice('SALE', 1000), '1,000원');
      expect(formatPrice('SALE', 5000), '5,000원');
      expect(formatPrice('SALE', 123456), '123,456원');
      expect(formatPrice('SALE', 10000000), '10,000,000원');
    });

    test('사진 주소: 같은 출처 /api 경로, 썸네일이면 ?size=thumb', () {
      expect(marketPhotoUrl(5, 9, thumb: true), '/api/market-items/5/photos/9?size=thumb');
      expect(marketPhotoUrl(5, 9), '/api/market-items/5/photos/9');
    });
  });

  group('상태 뱃지 색', () {
    // 뱃지 글자 색과 배경 색을 꺼낸다
    (Color?, Color?) colors(WidgetTester tester, String text) {
      final ink = tester.widget<Text>(find.text(text)).style?.color;
      final box = tester.widget<Container>(
        find.ancestor(of: find.text(text), matching: find.byType(Container)).first,
      );
      return (ink, (box.decoration as BoxDecoration).color);
    }

    testWidgets('ON_SALE 청자, RESERVED 파랑, DONE 회색, 모르는 코드는 색 없음 (글자는 서버 name)', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [
          marketItemJson(id: 1, title: 'a', statusCode: 'ON_SALE', statusName: '판매중'),
          marketItemJson(id: 2, title: 'b', statusCode: 'RESERVED', statusName: '예약중'),
          marketItemJson(id: 3, title: 'c', statusCode: 'DONE', statusName: '거래완료'),
          marketItemJson(id: 4, title: 'd', statusCode: 'NEW_ONE', statusName: '새 상태'),
        ]),
      ]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      expect(colors(tester, '판매중'), (AppColors.celadonInk, AppColors.celadonSoft));
      expect(colors(tester, '예약중'), (const Color(0xFFA9C2F0), const Color(0xFF1F2B42)));
      expect(colors(tester, '거래완료'), (AppColors.muted, AppColors.badgeChatBg));
      expect(colors(tester, '새 상태'), (AppColors.ink, Colors.transparent));
    });
  });

  group('썸네일', () {
    testWidgets('대표 사진이 있으면 썸네일 주소로 Image를 그리고 "{제목} 대표 사진" 이름이 붙는다', (tester) async {
      final handle = tester.ensureSemantics();
      final item = MarketListItem.fromJson(marketItemJson(id: 5, title: '의자', coverPhoto: coverJson(id: 9)));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: MarketThumb(item: item))));
      final image = tester.widget<Image>(find.byType(Image));
      expect((image.image as NetworkImage).url, '/api/market-items/5/photos/9?size=thumb');
      expect(image.width, 72);
      expect(image.height, 72);
      expect(find.bySemanticsLabel('의자 대표 사진'), findsOneWidget);
      handle.dispose();
    });

    testWidgets('사진 파일이 깨지면(불러오기 실패) 같은 크기 빈칸으로 바뀌고 스크린리더 이름도 없다', (tester) async {
      final handle = tester.ensureSemantics();
      final item = MarketListItem.fromJson(marketItemJson(id: 5, title: '의자', coverPhoto: coverJson()));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: MarketThumb(item: item))));
      await tester.pumpAndSettle(); // 테스트 환경에서는 사진 요청이 실패한다
      expect(find.bySemanticsLabel('의자 대표 사진'), findsNothing);
      final box = tester.getSize(find.byType(MarketThumb));
      expect(box, const Size(72, 72));
      expect(tester.takeException(), isNull);
      handle.dispose();
    });

    testWidgets('대표 사진이 없으면 72x72 회색 빈칸이고 Image도 이름도 없다', (tester) async {
      final handle = tester.ensureSemantics();
      final item = MarketListItem.fromJson(marketItemJson(title: '의자'));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: MarketThumb(item: item))));
      expect(find.byType(Image), findsNothing);
      expect(tester.getSize(find.byType(MarketThumb)), const Size(72, 72));
      expect(
        tester.widgetList<ColoredBox>(find.descendant(of: find.byType(MarketThumb), matching: find.byType(ColoredBox))).single.color,
        AppColors.hover,
      );
      expect(find.bySemanticsLabel('의자 대표 사진'), findsNothing);
      handle.dispose();
    });

    testWidgets('목록에서 썸네일 2칸이 그려지고(테스트에선 사진 요청이 실패해 둘 다 빈칸) 목록은 에러 없이 뜬다', (tester) async {
      final adapter = marketAdapter([
        FakeReply(200, [
          marketItemJson(id: 1, title: '사진있음', coverPhoto: coverJson(id: 3)),
          marketItemJson(id: 2, title: '사진없음'),
        ]),
      ]);
      await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.byType(MarketThumb), findsNWidgets(2));
      expect(find.byType(Image), findsNothing); // 사진 요청이 실패해 빈칸으로 바뀜
      expect(tester.getSize(find.byType(MarketThumb).first), const Size(72, 72));
      expect(find.text('사진있음'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('글쓰기 버튼·게이트', () {
    testWidgets('학교 인증 회원이면 [글쓰기]가 보이고 누르면 /market/new, 안내는 없다', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      final app = await pumpApp(
        tester,
        location: '/market',
        user: userJson(school: marketSeoul),
        adapter: adapter,
      );
      expect(find.text('글쓰기'), findsOneWidget);
      expect(find.textContaining('로그인이 필요해요'), findsNothing);
      expect(find.textContaining('학교 인증 후에'), findsNothing);
      await tester.tap(find.text('글쓰기'));
      await tester.pump(); // 글쓰기 화면은 아직 자리표시라 요청이 없다
      await tester.pumpAndSettle();
      expect(_uri(app), '/market/new');
    });

    testWidgets('비로그인이면 [글쓰기] 대신 "글을 올리려면 로그인이 필요해요"와 로그인 링크', (tester) async {
      final app = await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [])]));
      expect(find.text('글쓰기'), findsNothing);
      expect(find.text('글을 올리려면 로그인이 필요해요'), findsOneWidget);
      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    testWidgets('로그인했지만 학교 미인증이면 인증 안내와 "내 정보에서 학교 인증하기" 링크 → /me', (tester) async {
      final adapter = marketAdapter([FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/market', user: userJson(), adapter: adapter);
      expect(find.text('글쓰기'), findsNothing);
      expect(find.text('중고·나눔 글은 학교 인증 후에 올릴 수 있어요'), findsOneWidget);
      await tester.tap(find.text('내 정보에서 학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });
  });

  group('375px 화면', () {
    testWidgets('좁은 화면에서도 가로로 넘치지 않는다 (긴 제목·긴 학교 이름·사진 있는 행·글쓰기 버튼)', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      final longTitle = '아주아주 긴 제목의 중고 물건을 팝니다 ' * 4;
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, [{'id': 1, 'name': '아주아주아주 긴 이름의 대학교 대학원 캠퍼스'}]))
        ..on(
          'GET',
          '/market-items',
          FakeReply(200, [
            marketItemJson(
              title: longTitle,
              coverPhoto: coverJson(),
              school: {'id': 1, 'name': '아주아주아주 긴 이름의 대학교 대학원 캠퍼스'},
              price: 10000000,
            ),
            marketItemJson(id: 8, title: 'NoSpaceVeryLongTitle' * 8),
          ]),
        );
      await pumpApp(tester, location: '/market', user: userJson(school: marketSeoul), adapter: adapter);
      expect(tester.takeException(), isNull);
      expect(find.text('글쓰기'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '검색'), findsOneWidget);
    });

    testWidgets('비로그인(게이트 안내)도 375px에서 넘치지 않는다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 900);
      addTearDown(tester.view.reset);
      await pumpApp(tester, location: '/market', adapter: marketAdapter([FakeReply(200, [marketItemJson()])]));
      expect(tester.takeException(), isNull);
    });
  });

  group('늦게 온 옛 응답', () {
    testWidgets('조건을 바꾼 뒤 옛 조건의 응답이 늦게 와도 새 결과를 덮어쓰지 못한다', (tester) async {
      final gate = Completer<void>();
      final adapter = marketAdapter([
        FakeReply(200, [marketItemJson(id: 1, title: '옛 결과')], gate), // 붙잡아 둔 첫 요청
        FakeReply(200, [marketItemJson(id: 2, title: '새 결과')]),
      ]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);

      app.router.go('/market?status=DONE');
      await tester.pumpAndSettle();
      expect(find.text('새 결과'), findsOneWidget);

      gate.complete(); // 옛 응답이 이제 도착
      await tester.pumpAndSettle();
      expect(find.text('새 결과'), findsOneWidget);
      expect(find.text('옛 결과'), findsNothing);
    });

    testWidgets('화면을 떠난 뒤 도착한 응답은 에러 없이 버려진다', (tester) async {
      final gate = Completer<void>();
      final adapter = marketAdapter([FakeReply(200, [marketItemJson()], gate)]);
      final app = await pumpApp(tester, location: '/market', adapter: adapter);
      app.router.go('/market/7');
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });
}
