// S16-h1 비자 후기 목록 화면 테스트: 주소 쿼리가 진짜 값(필터 3개↔주소↔요청), 탭의 쿼리 유지, 행·뱃지·조건,
// 빈 목록·에러 3종, 선택지 실패 안내 1개, 연필 버튼(후기 쓰기), 늦게 온 옛 응답 무시를 본다.
import 'dart:async';
import 'dart:ui' show Tristate;

import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/initial_avatar.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/page_frame.dart';
import 'package:flutter/semantics.dart';
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

    testWidgets('면책 문구와 탭(후기·통계)이 보이고, 현재 탭 "후기"가 포인트색이다', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(find.text('하이코리아'), findsOneWidget);
      Color? color(String t) => tester.widget<Text>(find.text(t)).style?.color;
      expect(color('후기'), AppColors.accent);
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

    testWidgets('학교 목록(/schools)이 500이면 머리말은 "비자 후기", 주소의 학교는 임시 항목으로 남는다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/schools', FakeReply(500))
        ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      await pumpApp(tester, location: '/visa-reviews?schoolId=1', adapter: adapter);
      expect(_title(tester), '비자 후기');
      expect(find.text('알 수 없는 학교 (1)'), findsOneWidget);
    });

    testWidgets('조건을 바꾼 뒤 [통계] 탭을 누르면 바뀐 쿼리를 들고 간다 (build 때 담은 옛 값이 아니라)', (tester) async {
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])])
        ..on('GET', '/visa-reviews/stats', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      await _pick(tester, _schoolSelect, '서울대');
      await tester.tap(find.text('통계'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/stats?schoolId=1');
    });

    testWidgets('쿼리가 없으면 탭 주소에도 ? 가 붙지 않는다', (tester) async {
      final adapter = _adapter([FakeReply(200, [])])..on('GET', '/visa-reviews/stats', FakeReply(200, []));
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      await tester.tap(find.text('통계'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/stats');
    });

    // S29-b: 탭은 tab 역할이 아니라 링크. 앱 경로의 의미(링크·현재 탭·주소·높이)를 본다
    testWidgets('탭은 링크 노드: 현재 탭만 selected, linkUrl은 쿼리 포함, 눌림 높이 40', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/visa-reviews?schoolId=1', adapter: _adapter([FakeReply(200, [])]));
      SemanticsNode node(String label) => tester.getSemantics(find.ancestor(of: find.text(label), matching: find.byType(InkWell)).first);
      final list = node('후기');
      final stats = node('통계');
      expect(list.flagsCollection.isLink, isTrue);
      expect(stats.flagsCollection.isLink, isTrue);
      expect(list.flagsCollection.isSelected, Tristate.isTrue);
      expect(stats.flagsCollection.isSelected, Tristate.isFalse);
      expect(list.linkUrl, Uri.parse('/visa-reviews?schoolId=1'));
      expect(stats.linkUrl, Uri.parse('/visa-reviews/stats?schoolId=1'));
      expect(tester.getSize(find.ancestor(of: find.text('통계'), matching: find.byType(Container)).first).height, 40);
      handle.dispose();
    });

    testWidgets('조건을 바꾸면 탭의 링크 주소도 바뀐 쿼리로 다시 만들어진다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter([FakeReply(200, []), FakeReply(200, [])]);
      await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      Uri? statsUrl() => tester.getSemantics(find.ancestor(of: find.text('통계'), matching: find.byType(InkWell)).first).linkUrl;
      expect(statsUrl(), Uri.parse('/visa-reviews/stats'));
      await _pick(tester, _schoolSelect, '서울대');
      expect(statsUrl(), Uri.parse('/visa-reviews/stats?schoolId=1'));
      handle.dispose();
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

    testWidgets('결과 뱃지 색: 승인은 포인트, 불허는 회색', (tester) async {
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

      expect(ink('승인'), AppColors.accentInk);
      expect(bg('승인'), AppColors.accentSoft);
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

    testWidgets('줄 전체 링크: 누르면 /visa-reviews/{id} 상세로 간다', (tester) async {
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
      await tester.tap(find.byTooltip('후기 쓰기'));
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
      await tester.tap(find.byTooltip('후기 쓰기'));
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

  // S26-h2: 피드형 줄 + 연필 버튼
  group('피드형 줄·연필 버튼 (S26-h2)', () {
    Finder rowOf(String text) => find.ancestor(of: find.text(text), matching: find.byType(InkWell)).first;

    void small(WidgetTester tester, [double h = 900]) {
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = Size(375, h);
      addTearDown(tester.view.reset);
    }

    testWidgets('줄의 빈 곳(왼쪽 가장자리)을 눌러도 상세로 가고, 줄 높이는 48 이상', (tester) async {
      final adapter = _adapter([FakeReply(200, [reviewJson(id: 10), reviewJson(id: 11, visaType: const {'code': 'D4', 'name': '어학연수(D-4)'})])])
        ..on('GET', '/visa-reviews/11', FakeReply(200, reviewJson(id: 11)));
      final app = await pumpApp(tester, location: '/visa-reviews', adapter: adapter);
      final row = rowOf('어학연수(D-4) · 연장 · 처리 19일');
      expect(tester.getSize(row).height, greaterThanOrEqualTo(48));
      final rect = tester.getRect(row);
      await tester.tapAt(Offset(rect.left + 4, rect.center.dy));
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/11');
    });

    testWidgets('내용이 짧은 줄도 눌리는 높이가 48 이상', (tester) async {
      await pumpApp(
        tester,
        location: '/visa-reviews',
        adapter: _adapter([FakeReply(200, [reviewJson(visaType: const {'code': 'X', 'name': 'V'}, applicationType: const {'code': 'Y', 'name': 'A'})])]),
      );
      expect(tester.getSize(rowOf('V · A · 처리 19일')).height, greaterThanOrEqualTo(48));
    });

    testWidgets('줄 하나 = 링크 하나, tap 동작, 라벨은 제목으로 시작하고 결과·학교·작성자·시각을 담는다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [reviewJson(id: 1), reviewJson(id: 2, visaType: const {'code': 'D4', 'name': '어학연수(D-4)'})])]));
      final data = tester.getSemantics(rowOf('유학(D-2) · 연장 · 처리 19일')).getSemanticsData();
      expect(data.flagsCollection.isLink, isTrue);
      expect(data.linkUrl, Uri.parse('/visa-reviews/1')); // 링크 주소 = 눌러서 가는 곳
      expect(data.hasAction(SemanticsAction.tap), isTrue);
      expect(data.label, startsWith('유학(D-2) · 연장 · 처리 19일'));
      for (final t in ['승인', '서울대', '홍길동', '접수 2026년 9월 1일', '결과 2026년 9월 20일', '작성 2020년 11월 8일']) {
        expect(data.label, contains(t), reason: data.label);
      }
      // 첫 후기 제목으로 시작하는 링크 노드는 줄 하나뿐
      var links = 0;
      void walk(SemanticsNode n) {
        final d = n.getSemanticsData();
        if (d.flagsCollection.isLink && d.label.startsWith('유학(D-2)')) links++;
        n.visitChildren((c) {
          walk(c);
          return true;
        });
      }

      walk(tester.getSemantics(find.byType(PageFrame)));
      expect(links, 1);
      handle.dispose();
    });

    testWidgets('학교로 거른 화면은 라벨에 학교가 없다', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpApp(tester, location: '/visa-reviews?schoolId=1', adapter: _adapter([FakeReply(200, [reviewJson()])]));
      final label = tester.getSemantics(rowOf('유학(D-2) · 연장 · 처리 19일')).getSemanticsData().label;
      expect(label, isNot(contains('서울대')), reason: label);
      expect(label, contains('홍길동'));
      handle.dispose();
    });

    testWidgets('20자 닉네임도 375·320px에서 넘치지 않고 작성 시각이 한 줄로 남는다', (tester) async {
      for (final w in [375.0, 320.0]) {
        small(tester);
        tester.view.physicalSize = Size(w, 900);
        await pumpApp(
          tester,
          location: '/visa-reviews',
          adapter: _adapter([FakeReply(200, [reviewJson(author: const {'id': 1, 'nickname': '가나다라마바사아자차카타파하가나다라마바'})])]),
        );
        expect(tester.takeException(), isNull, reason: 'width $w');
        final t = tester.getRect(find.text('2020년 11월 8일 15:19'));
        expect(t.height, lessThan(24), reason: '시각이 쪼개지지 않음 (width $w)');
        expect(t.right, lessThanOrEqualTo(w - 16));
      }
    });

    testWidgets('줄 안에 이니셜 동그라미는 없다', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [reviewJson()])]));
      expect(find.byType(InitialAvatar), findsNothing);
    });

    testWidgets('툴바에 [후기 쓰기] 버튼은 없고 연필 버튼 1개(동그라미, 순서표 0)', (tester) async {
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, [])]));
      expect(find.widgetWithText(FilledButton, '후기 쓰기'), findsNothing);
      expect(find.byType(FloatingActionButton), findsOneWidget);
      expect(tester.widget<FloatingActionButton>(find.byType(FloatingActionButton)).shape, isA<CircleBorder>());
      final keyed = find.ancestor(
        of: find.byType(FloatingActionButton),
        matching: find.byWidgetPredicate((w) => w is Semantics && w.properties.sortKey != null),
      );
      expect((tester.widget<Semantics>(keyed.first).properties.sortKey! as OrdinalSortKey).order, 0);
    });

    testWidgets('375px: 넘치지 않고, 연필 버튼 오른쪽 끝은 375-16', (tester) async {
      small(tester);
      await pumpApp(
        tester,
        location: '/visa-reviews',
        user: userJson(),
        adapter: _adapter([FakeReply(200, [reviewJson(), reviewJson(id: 8, author: null)])]),
      );
      expect(tester.takeException(), isNull);
      expect(tester.getRect(find.byType(FloatingActionButton)).right, 375 - 16);
    });

    testWidgets('높이 500에서 끝까지 내리면 마지막 줄 아래가 연필 버튼 위쪽보다 위', (tester) async {
      small(tester, 500);
      final reviews = [for (var i = 1; i <= 8; i++) reviewJson(id: i, visaType: {'code': 'D$i', 'name': '비자$i'})];
      await pumpApp(tester, location: '/visa-reviews', adapter: _adapter([FakeReply(200, reviews)]));
      await tester.dragFrom(const Offset(187, 300), const Offset(0, -3000)); // 제목이 화면 밖이라 빈 지점에서 끌어 내린다
      await tester.pumpAndSettle();
      final last = tester.getRect(rowOf('비자8 · 연장 · 처리 19일'));
      expect(last.bottom, lessThanOrEqualTo(tester.getRect(find.byType(FloatingActionButton)).top));
    });
  });
}
