// S16-h1 비자 후기 상세 화면 테스트: 표시 항목, 불러오기 실패, 수정·삭제 버튼 조건 표, 삭제 흐름과 실패 문구를 본다.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';

// 서울대 인증 회원 (id 1, 후기 작성자 id 1과 같다 = 내 후기)
Map<String, dynamic> _user({String role = 'USER', int id = 1}) => userJson(id: id, role: role, school: visaSeoul);

FakeAdapter _adapter({FakeReply? review}) =>
    FakeAdapter()..on('GET', '/visa-reviews/7', review ?? FakeReply(200, reviewJson()));

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) =>
    pumpApp(tester, location: '/visa-reviews/7', user: user, adapter: adapter);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

Finder _btn(String text) => find.widgetWithText(OutlinedButton, text);
final _edit = _btn('수정');
final _delete = _btn('삭제');
final _toList = _btn('목록으로');

Finder _dialogBtn(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));

void main() {
  group('표시', () {
    _test('불러오는 동안은 면책 문구·"불러오는 중..."·[목록으로]만 보인다', (tester) async {
      final gate = Completer<void>();
      await _open(tester, _adapter(review: FakeReply(200, reviewJson(), gate)));
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(find.text('하이코리아'), findsOneWidget);
      expect(_toList, findsOneWidget);
      expect(find.text('유학(D-2) · 연장'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('유학(D-2) · 연장'), findsOneWidget);
    });

    _test('결과·학교 뱃지, 제목 "{비자} · {신청}", 작성자, 작성일, 접수/결과 날짜 줄, 본문이 보인다', (tester) async {
      final review = reviewJson(content: '첫 줄\n둘째 줄\n\n넷째 줄', author: {'id': 2, 'nickname': '김철수'});
      await _open(tester, _adapter(review: FakeReply(200, review)));
      expect(find.text('승인'), findsOneWidget);
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('유학(D-2) · 연장'), findsOneWidget);
      expect(find.text('김철수'), findsOneWidget);
      expect(find.text(formatDate('2020-11-08T06:19:00Z')), findsOneWidget);
      expect(find.text('접수 2026년 9월 1일 → 결과 2026년 9월 20일 (19일)'), findsOneWidget);
      expect(find.text('첫 줄\n둘째 줄\n\n넷째 줄'), findsOneWidget); // 한 덩어리 글자 = 줄바꿈 그대로
    });

    _test('불허 뱃지는 회색', (tester) async {
      await _open(tester, _adapter(review: FakeReply(200, reviewJson(result: {'code': 'DENIED', 'name': '불허'}))));
      expect(tester.widget<Text>(find.text('불허')).style?.color, AppColors.muted);
    });

    _test('제목은 제목(heading) 시맨틱이다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter());
      expect(tester.getSemantics(find.text('유학(D-2) · 연장')).flagsCollection.isHeader, isTrue);
      handle.dispose();
    });

    _test('작성자 없으면 "(작성자 없음)"', (tester) async {
      await _open(tester, _adapter(review: FakeReply(200, reviewJson(author: null))));
      expect(find.text('(작성자 없음)'), findsOneWidget);
    });

    _test('본문이 비면 "남긴 메모가 없어요"(회색)', (tester) async {
      await _open(tester, _adapter(review: FakeReply(200, reviewJson(content: ''))));
      expect(tester.widget<Text>(find.text('남긴 메모가 없어요')).style?.color, AppColors.muted);
    });

    _test('본문은 글자로만: HTML도 그대로 보이고 드래그로 고를 수 있다(SelectionArea 안)', (tester) async {
      const html = '<b>굵게</b><script>alert(1)</script>';
      await _open(tester, _adapter(review: FakeReply(200, reviewJson(content: html))));
      expect(find.text(html), findsOneWidget);
      expect(find.descendant(of: find.byType(SelectionArea), matching: find.text(html)), findsOneWidget);
    });

    _test('[목록으로]를 누르면 /visa-reviews 로 간다', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/schools', FakeReply(200, visaSchools))
        ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      final app = await _open(tester, adapter);
      await tester.tap(_toList);
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews');
    });

    _test('주소의 id 글자는 인코딩해서 요청한다 (/visa-reviews/a%3Fb)', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/visa-reviews/a%3Fb', FakeReply(404));
      await pumpApp(tester, location: '/visa-reviews/a%3Fb', adapter: adapter);
      expect(adapter.calls.where((c) => c.method == 'GET').map((c) => c.path), contains('/api/visa-reviews/a%3Fb'));
    });
  });

  group('불러오기 실패', () {
    // (상태, 기대 문구): 400·404는 같은 문구, 그 밖은 기본 문구
    for (final (status, message) in const [
      (404, '후기를 찾을 수 없습니다'),
      (400, '후기를 찾을 수 없습니다'),
      (500, '후기를 불러오지 못했습니다'),
      (401, '후기를 불러오지 못했습니다'),
    ]) {
      _test('GET $status → "$message"와 [목록으로], 후기·수정·삭제는 없다', (tester) async {
        final adapter = _adapter(review: FakeReply(status));
        if (status == 401) adapter.on('POST', '/auth/refresh', FakeReply(401)); // 자동 재발급도 실패
        await _open(tester, adapter, user: _user());
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(_toList, findsOneWidget);
        expect(_edit, findsNothing);
        expect(_delete, findsNothing);
        expect(find.text('불러오는 중...'), findsNothing);
      });
    }

    _test('네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      await _open(tester, _adapter(review: FakeReply.networkError()));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });
  });

  group('수정·삭제 버튼 조건', () {
    // (설명, 로그인 사용자, 후기 작성자, 수정 보임, 삭제 보임)
    final cases = <(String, Map<String, dynamic>?, Map<String, dynamic>?, bool, bool)>[
      ('비로그인', null, {'id': 1, 'nickname': 'a'}, false, false),
      ('남의 후기를 일반 회원이', _user(id: 5), {'id': 1, 'nickname': 'a'}, false, false),
      ('내 후기', _user(), {'id': 1, 'nickname': 'a'}, true, true),
      ('남의 후기를 관리자가', _user(role: 'ADMIN', id: 9), {'id': 1, 'nickname': 'a'}, false, true),
      ('내 후기를 관리자가', _user(role: 'ADMIN', id: 1), {'id': 1, 'nickname': 'a'}, true, true),
      ('작성자 없는 후기를 관리자가', _user(role: 'ADMIN', id: 9), null, false, true),
      ('작성자 없는 후기를 일반 회원이', _user(), null, false, false),
    ];
    for (final (name, user, author, edit, del) in cases) {
      _test('$name → 수정 ${edit ? '보임' : '숨김'}, 삭제 ${del ? '보임' : '숨김'}', (tester) async {
        await _open(tester, _adapter(review: FakeReply(200, reviewJson(author: author))), user: user);
        expect(_edit, edit ? findsOneWidget : findsNothing);
        expect(_delete, del ? findsOneWidget : findsNothing);
        expect(_toList, findsOneWidget); // 목록으로는 항상
      });
    }

    _test('[수정]을 누르면 /visa-reviews/7/edit 로 간다', (tester) async {
      final app = await _open(tester, _adapter(), user: _user());
      await tester.tap(_edit);
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews/7/edit');
    });
  });

  group('삭제', () {
    _test('[삭제] → 확인 창 "이 후기를 삭제할까요?" → [취소]면 요청 없이 그대로', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter, user: _user());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      expect(find.text('이 후기를 삭제할까요?'), findsOneWidget);
      await tester.tap(_dialogBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(adapter.count('DELETE', '/visa-reviews/7'), 0);
      expect(_uri(app), '/visa-reviews/7');
      expect(_delete, findsOneWidget);
    });

    _test('Esc로 닫아도 취소와 같다 (요청 없음)', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _user());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/visa-reviews/7'), 0);
    });

    _test('확인하면 "삭제 중..."으로 잠기고 요청은 한 번, 성공하면 /visa-reviews 로 가며 기록을 교체한다', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()
        ..on('DELETE', '/visa-reviews/7', FakeReply(204, null, gate))
        ..on('GET', '/schools', FakeReply(200, visaSchools))
        ..on('GET', '/visa-review-options', FakeReply(200, visaOptionsJson))
        ..on('GET', '/visa-reviews', FakeReply(200, []));
      final app = await _open(tester, adapter, user: _user());

      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(_btn('삭제 중...'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_btn('삭제 중...')).onPressed, isNull);
      expect(adapter.count('DELETE', '/visa-reviews/7'), 1);
      expect(_uri(app), '/visa-reviews/7');

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/visa-reviews');
      // 기록 교체 (React의 navigate('/visa-reviews', { replace: true }))
      expect(nav.last.path, '/visa-reviews');
      expect(nav.last.replace, isTrue);
    });

    // (상태, 본인 후기인지, 기대 문구)
    for (final (status, mine, message) in const [
      (403, true, '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      (403, false, '삭제할 권한이 없습니다'),
      (404, true, '이미 삭제된 후기입니다'),
      (500, true, '삭제하지 못했습니다'),
    ]) {
      _test('DELETE $status (${mine ? '본인' : '관리자가 남의 후기'}) → "$message", 후기는 남고 머문다, 버튼 되살아남', (tester) async {
        final review = mine ? reviewJson() : reviewJson(author: {'id': 2, 'nickname': '남'});
        final user = mine ? _user() : _user(role: 'ADMIN', id: 9);
        final adapter = _adapter(review: FakeReply(200, review))..on('DELETE', '/visa-reviews/7', FakeReply(status));
        final app = await _open(tester, adapter, user: user);
        await tester.tap(_delete);
        await tester.pumpAndSettle();
        await tester.tap(_dialogBtn('삭제'));
        await tester.pumpAndSettle();

        expect(find.text(message), findsOneWidget);
        expect(_uri(app), '/visa-reviews/7');
        expect(find.text('유학(D-2) · 연장'), findsOneWidget);
        expect(tester.widget<OutlinedButton>(_delete).onPressed, isNotNull);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    _test('DELETE 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('DELETE', '/visa-reviews/7', FakeReply.networkError());
      await _open(tester, adapter, user: _user());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('DELETE 401(재발급 실패) → "다시 로그인해 주세요" 안내(error)와 로그인 링크, "삭제하지 못했습니다"는 아니다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/visa-reviews/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401)); // 자동 재발급도 실패
      final app = await _open(tester, adapter, user: _user());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    _test('다시 삭제하면 이전 실패 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/visa-reviews/7', FakeReply(500))
        ..on('DELETE', '/visa-reviews/7', FakeReply(500, null, Completer<void>()));
      await _open(tester, adapter, user: _user());
      for (var i = 0; i < 2; i++) {
        await tester.tap(_delete);
        await tester.pumpAndSettle();
        await tester.tap(_dialogBtn('삭제'));
        await tester.pump(const Duration(milliseconds: 50));
        if (i == 0) {
          await tester.pumpAndSettle();
          expect(find.text('삭제하지 못했습니다'), findsOneWidget);
        }
      }
      expect(find.text('삭제하지 못했습니다'), findsNothing);
    });
  });
}
