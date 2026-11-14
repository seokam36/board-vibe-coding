// S16-h1 비자 후기 통계 화면 테스트: 표(학교 열 조건·"N건"·소수 1자리), 빈 통계, hint 문구,
// 에러 3종, 필터↔주소↔요청, 탭 쿼리 유지, 표만 가로 스크롤을 본다.
import 'dart:async';

import 'package:board_web/theme/app_colors.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

String _title(WidgetTester tester) => tester
    .widget<Text>(find.byWidgetPredicate((w) => w is Text && w.style?.fontSize == AppSizes.fsBoard))
    .data!;

const _hint = '건수가 적으면 평균이 크게 흔들릴 수 있어요. 승인·불허를 모두 포함한 값이에요';

FakeAdapter _adapter(List<FakeReply> stats) {
  final a = visaFilterAdapter();
  for (final r in stats) {
    a.on('GET', '/visa-reviews/stats', r);
  }
  return a;
}

Future<void> _pick(WidgetTester tester, Finder select, String text) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

void main() {
  const path = '/visa-reviews/stats';

  // 탭 "후기"·"통계"는 React처럼 한 줄에 나란히(글자 폭만) 있어야 한다. (실제 화면에서 위아래로 쌓였던 버그를 지킨다)
  testWidgets('후기·통계 탭은 같은 줄에 나란히 있다', (tester) async {
    await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson(count: 1, avgDays: 10)])]));
    final list = tester.getRect(find.text('후기'));
    final stats = tester.getRect(find.text('통계'));
    expect(list.top, stats.top);
    expect(list.right <= stats.left, isTrue);
  });

  group('표', () {
    testWidgets('학교 필터가 없으면 학교·비자·신청·건수·평균 처리일 열, 값은 "N건"·"X.X일"', (tester) async {
      final stats = [
        statJson(count: 3, avgDays: 19.0),
        statJson(
          school: visaBusan,
          visaType: const {'code': 'D4', 'name': '어학연수(D-4)'},
          applicationType: const {'code': 'CHANGE', 'name': '변경'},
          count: 12,
          avgDays: 7.25, // 서버는 소수 1자리로 주지만 화면은 toStringAsFixed(1)로 맞춘다
        ),
      ];
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, stats)]));
      // 열 머리글 (필터의 "전체 학교" 같은 글자와 겹치지 않게 정확히 일치)
      for (final h in ['학교', '비자 종류', '신청 종류', '건수', '평균 처리일']) {
        expect(find.text(h), findsOneWidget);
      }
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('부산대'), findsOneWidget);
      expect(find.text('유학(D-2)'), findsOneWidget);
      expect(find.text('어학연수(D-4)'), findsOneWidget);
      expect(find.text('연장'), findsOneWidget);
      expect(find.text('변경'), findsOneWidget);
      expect(find.text('3건'), findsOneWidget);
      expect(find.text('12건'), findsOneWidget);
      expect(find.text('19.0일'), findsOneWidget); // 19.0도 "19.0일" 그대로
      expect(find.text('7.3일'), findsOneWidget);
    });

    testWidgets('서버가 19 처럼 정수로 줘도 "19.0일"', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson(avgDays: 19)])]));
      expect(find.text('19.0일'), findsOneWidget);
    });

    testWidgets('학교로 거른 화면에서는 학교 열이 없다', (tester) async {
      await pumpApp(
        tester,
        location: '$path?schoolId=1',
        adapter: _adapter([FakeReply(200, [statJson()])]),
      );
      expect(find.text('학교'), findsNothing);
      // 표 안에는 학교명이 없다 (드롭다운·머리말에는 있다)
      expect(find.descendant(of: find.byType(Table), matching: find.text('서울대')), findsNothing);
      expect(find.text('비자 종류'), findsOneWidget);
      expect(find.text('3건'), findsOneWidget);
    });

    testWidgets('숫자 열(건수·평균 처리일)은 오른쪽 정렬', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson()])]));
      Alignment? align(String t) =>
          tester.widget<Align>(find.ancestor(of: find.text(t), matching: find.byType(Align)).first).alignment as Alignment?;
      expect(align('3건'), Alignment.centerRight);
      expect(align('19.0일'), Alignment.centerRight);
      expect(align('건수'), Alignment.centerRight);
      expect(align('유학(D-2)'), Alignment.centerLeft);
    });

    testWidgets('표만 가로 스크롤이다 (표를 감싼 가로 스크롤이 있다)', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson()])]));
      final scrolls = tester.widgetList<SingleChildScrollView>(find.byType(SingleChildScrollView));
      expect(scrolls.where((s) => s.scrollDirection == Axis.horizontal), hasLength(1));
      expect(find.descendant(of: find.byWidgetPredicate((w) => w is SingleChildScrollView && w.scrollDirection == Axis.horizontal), matching: find.byType(Table)), findsOneWidget);
    });

    testWidgets('좁은 화면(375px)에서도 넘침 에러 없이 그려진다', (tester) async {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(375, 800);
      addTearDown(tester.view.reset);
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson()])]));
      expect(tester.takeException(), isNull);
      expect(find.text('평균 처리일'), findsOneWidget);
    });
  });

  group('빈 통계·hint', () {
    testWidgets('통계가 없으면 "아직 통계가 없습니다"이고 hint는 없다', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [])]));
      expect(find.text('아직 통계가 없습니다'), findsOneWidget);
      expect(find.text(_hint), findsNothing);
      expect(find.byType(Table), findsNothing);
    });

    testWidgets('표가 있을 때만 hint 문구가 보인다', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson()])]));
      expect(find.text(_hint), findsOneWidget);
      expect(find.text('아직 통계가 없습니다'), findsNothing);
    });

    testWidgets('불러오는 동안은 "불러오는 중..."뿐 (빈 문구·hint 없음)', (tester) async {
      final gate = Completer<void>();
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply(200, [statJson()], gate)]));
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.text('아직 통계가 없습니다'), findsNothing);
      expect(find.text(_hint), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
    });
  });

  group('에러', () {
    for (final (status, message) in const [
      (404, '존재하지 않는 학교입니다'),
      (400, '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요'),
      (500, '통계를 불러오지 못했습니다'),
      (401, '통계를 불러오지 못했습니다'), // 로그인 개념이 없는 화면이라 401도 기본 문구
    ]) {
      testWidgets('GET $status → "$message", 표·hint는 없다', (tester) async {
        final adapter = _adapter([FakeReply(status)]);
        if (status == 401) adapter.on('POST', '/auth/refresh', FakeReply(401)); // 자동 재발급도 실패
        await pumpApp(tester, location: path, adapter: adapter);
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(find.text('아직 통계가 없습니다'), findsNothing);
        expect(find.text(_hint), findsNothing);
      });
    }

    testWidgets('네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      await pumpApp(tester, location: path, adapter: _adapter([FakeReply.networkError()]));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    testWidgets('선택지가 실패하면 안내 1개, 통계 표는 그대로', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(500))
        ..on('GET', '/visa-review-options', FakeReply(500))
        ..on('GET', '/visa-reviews/stats', FakeReply(200, [statJson()]));
      await pumpApp(tester, location: path, adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('3건'), findsOneWidget);
    });
  });

  group('머리말·탭·필터', () {
    testWidgets('머리말은 목록과 같은 규칙, 면책 문구가 있고 현재 탭은 "통계"', (tester) async {
      await pumpApp(tester, location: '$path?schoolId=2', adapter: _adapter([FakeReply(200, [])]));
      expect(_title(tester), '부산대 비자 후기');
      expect(find.text('하이코리아'), findsOneWidget);
      Color? color(String t) => tester.widget<Text>(find.text(t)).style?.color;
      expect(color('통계'), AppColors.celadon);
      expect(color('후기'), AppColors.muted);
    });

    testWidgets('요청에 세 조건이 그대로 가고 빈 값은 빠진다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      await pumpApp(tester, location: '$path?schoolId=1&visaType=D2&applicationType=', adapter: adapter);
      expect(adapter.callsTo('GET', path).single.query, {'schoolId': '1', 'visaType': 'D2'});
    });

    testWidgets('드롭다운을 고르면 /visa-reviews/stats 주소의 쿼리가 바뀌고 다시 요청한다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: path, adapter: adapter);
      await _pick(tester, find.byKey(const ValueKey('filter-applicationType')), '변경');
      expect(_uri(app), '/visa-reviews/stats?applicationType=CHANGE');
      expect(adapter.callsTo('GET', path).last.query, {'applicationType': 'CHANGE'});
    });

    testWidgets('목록에 없는 값은 임시 항목 "알 수 없는 학교 (99)"', (tester) async {
      await pumpApp(tester, location: '$path?schoolId=99', adapter: _adapter([FakeReply(200, [])]));
      expect(find.text('알 수 없는 학교 (99)'), findsOneWidget);
    });

    testWidgets('조건을 바꾼 뒤 [후기] 탭을 누르면 바뀐 쿼리를 들고 간다 (build 때 담은 옛 값이 아니라)', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])])..on('GET', '/visa-reviews', FakeReply(200, []));
      final app = await pumpApp(tester, location: path, adapter: adapter);
      await _pick(tester, find.byKey(const ValueKey('filter-schoolId')), '부산대');
      await tester.tap(find.text('후기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews?schoolId=2');
    });

    testWidgets('[후기] 탭을 누르면 쿼리를 들고 /visa-reviews 로 간다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])])..on('GET', '/visa-reviews', FakeReply(200, []));
      final app = await pumpApp(tester, location: '$path?schoolId=1&visaType=D2', adapter: adapter);
      await tester.tap(find.text('후기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews?schoolId=1&visaType=D2');
      expect(adapter.callsTo('GET', '/visa-reviews').single.query, {'schoolId': '1', 'visaType': 'D2'});
    });
  });

  group('늦게 온 옛 응답', () {
    testWidgets('조건을 바꾼 뒤 옛 응답이 늦게 와도 덮어쓰지 않는다', (tester) async {
      final slow = Completer<void>();
      final adapter = _adapter([
        FakeReply(200, [statJson(count: 111)], slow),
        FakeReply(200, [statJson(count: 222)]),
      ]);
      final app = await pumpApp(tester, location: path, adapter: adapter);
      app.router.go('$path?visaType=D2');
      await tester.pumpAndSettle();
      expect(find.text('222건'), findsOneWidget);
      slow.complete();
      await tester.pumpAndSettle();
      expect(find.text('222건'), findsOneWidget);
      expect(find.text('111건'), findsNothing);
    });
  });
}
