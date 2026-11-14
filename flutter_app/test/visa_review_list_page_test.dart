// S16-h1 비자 후기 목록 화면 테스트: 주소 쿼리가 진짜 값(필터 3개↔주소↔요청), 탭의 쿼리 유지, 행·뱃지·조건,
// 빈 목록·에러 3종, 선택지 실패 안내 1개, 후기 쓰기 버튼 2경우, 늦게 온 옛 응답 무시를 본다.
import 'dart:async';

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/visa_json.dart';

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 머리말(30px 글자)의 글자
String _title(WidgetTester tester) => tester
    .widget<Text>(find.byWidgetPredicate((w) => w is Text && w.style?.fontSize == AppSizes.fsBoard))
    .data!;

final _schoolSelect = find.byKey(const ValueKey('filter-schoolId'));
final _visaSelect = find.byKey(const ValueKey('filter-visaType'));
final _appSelect = find.byKey(const ValueKey('filter-applicationType'));

// 드롭다운을 열어 항목을 고른다 (선택된 항목은 버튼과 메뉴에 두 번 보이므로 마지막 것을 누른다)
Future<void> _pick(WidgetTester tester, Finder select, String text) async {
  await tester.tap(select);
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).last);
  await tester.pumpAndSettle();
}

// 선택지는 항상 성공, 목록 응답은 호출하는 쪽이 정한다
FakeAdapter _adapter(List<FakeReply> reviews) {
  final a = visaFilterAdapter();
  for (final r in reviews) {
    a.on('GET', '/visa-reviews', r);
  }
  return a;
}

void main() {
  group('머리말·탭·면책', () {
    testWidgets('학교를 안 골랐으면 "비자 후기", 골랐으면 "{학교명} 비자 후기"', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(_title(tester), '비자 후기');
    });

    testWidgets('학교를 골랐으면 학교 이름이 붙는다', (tester) async {
      await pumpApp(tester, location: '/visa-reviews?schoolId=2', adapter: _adapter([FakeReply(200, [])]));
      expect(_title(tester), '부산대 비자 후기');
    });

    testWidgets('학교 목록에 없거나 목록이 실패하면 "비자 후기"', (tester) async {
      await pumpApp(tester, location: '/visa-reviews?schoolId=99', adapter: _adapter([FakeReply(200, [])]));
      expect(_title(tester), '비자 후기');
    });

    testWidgets('학교 목록을 기다리는 동안은 "비자 후기"만, 드롭다운은 "불러오는 중..."', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, visaSchools, gate))
        ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      await pumpApp(tester, location: '/visa-reviews?schoolId=1', adapter: adapter);
      expect(_title(tester), '비자 후기');
      expect(find.text('불러오는 중...'), findsOneWidget);

      gate.complete();
      await tester.pumpAndSettle();
      expect(_title(tester), '서울대 비자 후기');
    });

    testWidgets('면책 문구와 탭(후기·통계)이 보이고, 현재 탭 "후기"가 청자색이다', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(find.text('하이코리아'), findsOneWidget);
      Color? color(String t) => tester.widget<Text>(find.text(t)).style?.color;
      expect(color('후기'), AppColors.celadon);
      expect(color('통계'), AppColors.muted);
    });

    testWidgets('[통계] 탭을 누르면 현재 쿼리를 그대로 들고 /visa-reviews/stats 로 간다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])])..on('GET', '/visa-reviews/stats', FakeReply(200, []));
      final app = await pumpApp(
        tester,
        location: '/visa-reviews?schoolId=1&visaType=D2',
        adapter: adapter,
      );
      await tester.tap(find.text('통계'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/stats?schoolId=1&visaType=D2');
      expect(adapter.callsTo('GET', '/visa-reviews/stats').single.query, {'schoolId': '1', 'visaType': 'D2'});
    });

    testWidgets('쿼리가 없으면 탭 주소에도 ? 가 붙지 않는다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])])..on('GET', '/visa-reviews/stats', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      await tester.tap(find.text('통계'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/stats');
    });
  });

  group('주소 쿼리 → 요청', () {
    testWidgets('쿼리가 없으면 조건 없이 요청, 선택지는 한 번씩만', (tester) async {
      final adapter = _adapter([FakeReply(200, [])]);
      await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      expect(adapter.callsTo('GET', '/visa-reviews').single.query, isEmpty);
      expect(adapter.count('GET', '/schools'), 1);
      expect(adapter.count('GET', '/visa-review-options'), 1);
    });

    testWidgets('세 조건이 그대로 쿼리로 간다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])]);
      await pumpApp(
        tester,
        location: '/visa-reviews?schoolId=1&visaType=D2&applicationType=CHANGE',
        adapter: adapter,
      );
      expect(adapter.callsTo('GET', '/visa-reviews').single.query, {
        'schoolId': '1',
        'visaType': 'D2',
        'applicationType': 'CHANGE',
      });
    });

    testWidgets('빈 값은 쿼리에 넣지 않고, 이상한 값은 거르지 않고 서버로 보낸다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])]);
      await pumpApp(tester, location: '/visa-reviews?schoolId=&visaType=abc', adapter: adapter);
      expect(adapter.callsTo('GET', '/visa-reviews').single.query, {'visaType': 'abc'});
    });
  });

  group('드롭다운 → 주소 → 재요청', () {
    testWidgets('세 드롭다운을 차례로 고르면 주소가 쌓이고 그때마다 그 조건으로 요청한다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, []), FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      expect(find.text('전체 학교'), findsOneWidget);
      expect(find.text('전체 비자 종류'), findsOneWidget);
      expect(find.text('전체 신청 종류'), findsOneWidget);

      await _pick(tester, _schoolSelect, '서울대');
      expect(_uri(app), '/visa-reviews?schoolId=1');
      expect(_title(tester), '서울대 비자 후기');

      await _pick(tester, _visaSelect, '유학(D-2)');
      expect(_uri(app), '/visa-reviews?schoolId=1&visaType=D2');

      await _pick(tester, _appSelect, '변경');
      expect(_uri(app), '/visa-reviews?schoolId=1&visaType=D2&applicationType=CHANGE');
      expect(adapter.callsTo('GET', '/visa-reviews').last.query, {
        'schoolId': '1',
        'visaType': 'D2',
        'applicationType': 'CHANGE',
      });
      expect(adapter.count('GET', '/visa-reviews'), 4);
      // 선택지는 다시 부르지 않는다
      expect(adapter.count('GET', '/schools'), 1);
      expect(adapter.count('GET', '/visa-review-options'), 1);
    });

    testWidgets('"전체"를 고르면 그 파라미터만 주소에서 빠진다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(
        tester,
        location: '/visa-reviews?schoolId=1&visaType=D2',
        adapter: adapter,
      );
      await _pick(tester, _visaSelect, '전체 비자 종류');
      expect(_uri(app), '/visa-reviews?schoolId=1');
      expect(adapter.callsTo('GET', '/visa-reviews').last.query, {'schoolId': '1'});
    });

    testWidgets('주소가 직접 바뀌어도(뒤로 가기 등) 다시 요청하고 드롭다운이 따라간다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      app.router.go('/visa-reviews?applicationType=CHANGE');
      await tester.pumpAndSettle();
      expect(adapter.callsTo('GET', '/visa-reviews').last.query, {'applicationType': 'CHANGE'});
      expect(find.text('변경'), findsOneWidget);
    });
  });

  group('임시 항목', () {
    testWidgets('목록에 없는 값은 "알 수 없는 ..." 항목으로 보인다', (tester) async {
      await pumpApp(
        tester,
        location: '/visa-reviews?schoolId=99&visaType=X1&applicationType=Y2',
        adapter: _adapter([FakeReply(200, [])]),
      );
      expect(find.text('알 수 없는 학교 (99)'), findsOneWidget);
      expect(find.text('알 수 없는 비자 종류 (X1)'), findsOneWidget);
      expect(find.text('알 수 없는 신청 종류 (Y2)'), findsOneWidget);
    });

    testWidgets('"전체"를 고르면 임시 항목에서 빠져나온다', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      final app = await pumpApp(tester, location: '/visa-reviews?schoolId=99', adapter: adapter);
      await _pick(tester, _schoolSelect, '전체 학교');
      expect(_uri(app), '/visa-reviews');
      expect(find.text('알 수 없는 학교 (99)'), findsNothing);
    });

    testWidgets('선택지를 받는 중이면 "알 수 없는"이 아니라 "불러오는 중..."', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, visaSchools))
        ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson, gate))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      await pumpApp(tester, location: '/visa-reviews?visaType=D2', adapter: adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.textContaining('알 수 없는'), findsNothing);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('유학(D-2)'), findsOneWidget);
      expect(find.text('불러오는 중...'), findsNothing);
    });
  });

  group('선택지 목록 실패', () {
    final failing = find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.error);

    testWidgets('하나만 실패해도 error 안내 1개, 후기 목록은 그대로 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(200, visaSchools))
        ..on('GET', '/visa-review-options', FakeReply(500))
        ..on('GET', '/visa-reviews', FakeReply(200, [reviewJson()]));
      await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(failing, findsOneWidget);
      expect(find.text('유학(D-2) · 연장 · 처리 19일'), findsOneWidget);
    });

    testWidgets('둘 다 실패해도 안내는 1개', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply.networkError())
        ..on('GET', '/visa-review-options', FakeReply(500))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      expect(find.text('선택지 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(failing, findsOneWidget);
    });

    testWidgets('둘 다 성공하면 error 안내가 없다 (면책 info만 있다)', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(failing, findsNothing);
    });
  });

  group('행', () {
    testWidgets('결과 뱃지·학교 뱃지·제목 링크·작성자·접수일→결과일·작성일이 보인다', (tester) async {
      final reviews = [
        reviewJson(id: 10),
        reviewJson(
          id: 11,
          author: null,
          school: visaBusan,
          visaType: const {'code': 'D4', 'name': '어학연수(D-4)'},
          applicationType: const {'code': 'CHANGE', 'name': '변경'},
          receivedDate: '2026-01-05',
          resultDate: '2026-02-04',
          result: const {'code': 'DENIED', 'name': '불허'},
          processingDays: 30,
        ),
      ];
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, reviews)]));

      expect(find.text('유학(D-2) · 연장 · 처리 19일'), findsOneWidget);
      expect(find.text('어학연수(D-4) · 변경 · 처리 30일'), findsOneWidget);
      expect(find.text('승인'), findsOneWidget);
      expect(find.text('불허'), findsOneWidget);
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('부산대'), findsOneWidget);
      expect(find.text('홍길동'), findsOneWidget);
      expect(find.text('(작성자 없음)'), findsOneWidget);
      expect(find.text('2026년 9월 1일 → 2026년 9월 20일'), findsOneWidget);
      expect(find.text('2026년 1월 5일 → 2026년 2월 4일'), findsOneWidget);
      expect(find.text('2020년 11월 8일 15:19'), findsNWidgets(2));
      // 본문 미리보기는 없다
      expect(find.text('서류는 빨리 냈어요'), findsNothing);
    });

    testWidgets('결과 뱃지 색: 승인은 청자, 불허는 회색', (tester) async {
      final reviews = [
        reviewJson(id: 10),
        reviewJson(id: 11, result: const {'code': 'DENIED', 'name': '불허'}),
      ];
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, reviews)]));
      Color? ink(String t) => tester.widget<Text>(find.text(t)).style?.color;
      Color? bg(String t) {
        final box = tester.widget<Container>(find.ancestor(of: find.text(t), matching: find.byType(Container)).first);
        return (box.decoration as BoxDecoration).color;
      }

      expect(ink('승인'), AppColors.celadonInk);
      expect(bg('승인'), AppColors.celadonSoft);
      expect(ink('불허'), AppColors.muted);
      expect(bg('불허'), AppColors.badgeChatBg);
    });

    testWidgets('학교로 거른 화면에서는 학교 뱃지를 생략한다', (tester) async {
      final reviews = [reviewJson(id: 10, school: visaBusan)];
      await pumpApp(
        tester,
        location: '/visa-reviews?schoolId=1',
        adapter: _adapter([FakeReply(200, reviews)]),
      );
      expect(find.text('부산대'), findsNothing);
      expect(find.text('유학(D-2) · 연장 · 처리 19일'), findsOneWidget);
    });

    testWidgets('제목만 링크: 누르면 /visa-reviews/{id} 상세로 간다', (tester) async {
      final adapter = _adapter([FakeReply(200, [reviewJson(id: 10), reviewJson(id: 11, visaType: const {'code': 'D4', 'name': '어학연수(D-4)'})])])
        ..on('GET', '/visa-reviews/11', FakeReply(200, reviewJson(id: 11, content: '상세에서 본 후기')));
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      await tester.tap(find.text('어학연수(D-4) · 연장 · 처리 19일'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/11');
      expect(find.text('상세에서 본 후기'), findsOneWidget);
    });
  });

  group('후기 쓰기 버튼', () {
    testWidgets('비로그인이어도 보이고, 누르면 /login', (tester) async {
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      await tester.tap(find.widgetWithText(FilledButton, '후기 쓰기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    testWidgets('로그인이면 /visa-reviews/new', (tester) async {
      final app = await pumpApp(
        tester,
        location: '/visa-reviews',
        user: userJson(),
        adapter: _adapter([FakeReply(200, [])]),
      );
      await tester.tap(find.widgetWithText(FilledButton, '후기 쓰기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/new');
    });
  });

  group('로딩·빈 목록·에러', () {
    testWidgets('불러오는 동안 "불러오는 중...", 끝나면 사라진다', (tester) async {
      final gate = Completer<void>();
      await pumpApp(
        tester,
        location: '/visa-reviews',
        adapter: _adapter([FakeReply(200, [reviewJson()], gate)]),
      );
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.text('아직 후기가 없습니다'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('유학(D-2) · 연장 · 처리 19일'), findsOneWidget);
    });

    testWidgets('후기가 없으면 "아직 후기가 없습니다"', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(find.text('아직 후기가 없습니다'), findsOneWidget);
    });

    // (상태, 기대 문구)
    for (final (status, message) in const [
      (404, '존재하지 않는 학교입니다'),
      (400, '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요'),
      (500, '후기 목록을 불러오지 못했습니다'),
      (401, '후기 목록을 불러오지 못했습니다'), // 로그인 개념이 없는 화면이라 401도 기본 문구
    ]) {
      testWidgets('GET $status → "$message" (빈 목록 문구는 안 보인다)', (tester) async {
        final adapter = _adapter([FakeReply(status)]);
        if (status == 401) adapter.on('POST', '/auth/refresh', FakeReply(401)); // 자동 재발급도 실패
        await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(find.text('아직 후기가 없습니다'), findsNothing);
      });
    }

    testWidgets('네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply.networkError()]));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });
  });

  group('늦게 온 옛 응답', () {
    testWidgets('조건을 바꾼 뒤 옛 조건의 응답이 늦게 와도 최신 결과를 덮어쓰지 않는다', (tester) async {
      final slow = Completer<void>();
      final adapter = _adapter([
        FakeReply(200, [reviewJson(id: 1, visaType: const {'code': 'D2', 'name': '옛 결과'})], slow), // 처음 조건: 느림
        FakeReply(200, [reviewJson(id: 2, visaType: const {'code': 'D4', 'name': '새 결과'})]), // 바꾼 조건: 빠름
      ]);
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      app.router.go('/visa-reviews?visaType=D4');
      await tester.pumpAndSettle();
      expect(find.text('새 결과 · 연장 · 처리 19일'), findsOneWidget);

      slow.complete();
      await tester.pumpAndSettle();
      expect(find.text('새 결과 · 연장 · 처리 19일'), findsOneWidget);
      expect(find.text('옛 결과 · 연장 · 처리 19일'), findsNothing);
    });
  });
}
