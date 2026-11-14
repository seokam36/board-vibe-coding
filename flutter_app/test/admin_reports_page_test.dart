// S16-m 관리자 신고 관리 화면 테스트 (React의 admin-conversation.spec(목록 부분) / market-report.spec(관리자 시나리오)):
// 권한(요청 0건), 탭(?status·기록 교체·잠금), 로딩·빈 목록·실패 3종·다시 시도, 행 4종(배지·링크·메타),
// 삭제(확인·취소·성공·404·409·403·500·401), 문제 없음(성공·404·409·403·500·401), 처리 중 잠금, 성공 안내 유지, 375px.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/screen.dart';

const _list = '/admin/reports';

// 신고 목록 첫 응답을 정한 가짜 서버 (기본: 미처리 탭에 글 신고 한 건)
FakeAdapter _adapter({List<Map<String, dynamic>>? reports, FakeReply? reply}) =>
    FakeAdapter()..on('GET', _list, reply ?? FakeReply(200, reports ?? [postReport()]));

Future<TestApp> _open(
  WidgetTester tester,
  FakeAdapter adapter, {
  Map<String, dynamic>? user,
  String location = _list,
}) => pumpApp(tester, location: location, user: user ?? adminUser(), adapter: adapter);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 주소 쿼리의 status로 본 "마지막 목록 요청"의 탭
String? _lastStatus(FakeAdapter a) => a.callsTo('GET', _list).last.query['status'];

Finder _row(int id) => find.byKey(ValueKey('report-$id'));
Finder _btnIn(int id, String text) =>
    find.descendant(of: _row(id), matching: find.widgetWithText(OutlinedButton, text));
Finder _deleteIn(int id) => _btnIn(id, '삭제');
Finder _dismissIn(int id) => _btnIn(id, '문제 없음');
Finder _dialogText(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));
Finder _noticeOf(String message) => find.ancestor(of: find.text(message), matching: find.byType(Notice));
NoticeKind _kind(WidgetTester tester, String message) => tester.widget<Notice>(_noticeOf(message)).kind;
Finder _retry() => find.widgetWithText(OutlinedButton, '다시 시도');

bool _enabled(WidgetTester tester, Finder button) => tester.widget<OutlinedButton>(button).onPressed != null;

// 탭 글자의 색(선택됨 = 청자)과 눌림 가능 여부
Color? _tabColor(WidgetTester tester, String label) => tester.widget<Text>(find.text(label)).style?.color;
bool _tabEnabled(WidgetTester tester, String label) =>
    tester.widget<InkWell>(find.ancestor(of: find.text(label), matching: find.byType(InkWell))).onTap != null;

void main() {
  group('권한', () {
    for (final (name, user) in <(String, Map<String, dynamic>?)>[('일반 회원', userJson()), ('비로그인', null)]) {
      testWidgets('$name은 "관리자만 볼 수 있습니다"(error)만 보이고 관리자 요청은 한 건도 없다', (tester) async {
        final adapter = FakeAdapter();
        await pumpApp(tester, location: _list, user: user, adapter: adapter);
        expect(find.text('관리자만 볼 수 있습니다'), findsOneWidget);
        expect(_kind(tester, '관리자만 볼 수 있습니다'), NoticeKind.error);
        expect(adapter.calls.where((c) => c.path.contains('/admin')), isEmpty);
        expect(find.text('미처리'), findsNothing); // 탭도 없다
      });
    }
  });

  group('탭', () {
    _test('처음엔 미처리 탭: GET /admin/reports?status=PENDING, 미처리가 선택(청자)·기각은 muted', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter);
      expect(adapter.count('GET', _list), 1);
      expect(_lastStatus(adapter), 'PENDING');
      expect(find.text('신고 관리'), findsWidgets); // 화면 제목 (관리자 상단바의 같은 이름 링크도 있다)
      expect(_tabColor(tester, '미처리'), AppColors.celadon);
      expect(_tabColor(tester, '기각'), AppColors.muted);
    });

    _test('?status=DISMISSED 로 열면 기각 탭, 요청도 DISMISSED, 처리 버튼은 없다', (tester) async {
      final adapter = _adapter(reports: [postReport(id: 4)]);
      await _open(tester, adapter, location: '$_list?status=DISMISSED');
      expect(_lastStatus(adapter), 'DISMISSED');
      expect(_tabColor(tester, '기각'), AppColors.celadon);
      expect(_tabColor(tester, '미처리'), AppColors.muted);
      expect(_row(4), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '삭제'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, '문제 없음'), findsNothing);
    });

    _test('모르는 status 값(?status=abc)은 미처리로 본다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, location: '$_list?status=abc');
      expect(_lastStatus(adapter), 'PENDING');
      expect(_tabColor(tester, '미처리'), AppColors.celadon);
    });

    _test('[기각] 탭을 누르면 주소가 ?status=DISMISSED 로 바뀌고(기록 교체) 기각 목록을 읽는다', (tester) async {
      final nav = NavLog()..install(tester);
      final adapter = _adapter()..on('GET', _list, FakeReply(200, [postReport(id: 4)]));
      final app = await _open(tester, adapter);
      await tester.tap(find.text('기각'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/admin/reports?status=DISMISSED');
      expect(nav.last.replace, isTrue); // 탭을 누를 때마다 뒤로 가기 기록이 쌓이지 않는다
      expect(_lastStatus(adapter), 'DISMISSED');
      expect(adapter.count('GET', _list), 2);
      expect(_row(4), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(_tabColor(tester, '기각'), AppColors.celadon);
    });

    _test('다시 [미처리]를 누르면 PENDING 으로 다시 읽는다', (tester) async {
      final adapter = _adapter()
        ..on('GET', _list, FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, [postReport(id: 8)]));
      final app = await _open(tester, adapter);
      await tester.tap(find.text('기각'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('미처리'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/admin/reports?status=PENDING');
      expect(_lastStatus(adapter), 'PENDING');
      expect(_row(8), findsOneWidget);
    });

    _test('탭을 바꾸면 이전 목록이 바로 비고 "불러오는 중..."이 보인다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('GET', _list, FakeReply(200, [], gate));
      await _open(tester, adapter);
      await tester.tap(find.text('기각'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(_row(1), findsNothing);
      expect(find.text('불러오는 중...'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('신고가 없습니다'), findsOneWidget);
    });

    _test('지금 보는 탭을 다시 눌러도 아무 일도 없다 (요청 안 나가고 목록 그대로)', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter);
      await tester.tap(find.text('미처리'));
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _list), 1);
      expect(_row(1), findsOneWidget);
    });

    _test('탭을 바꾸면 앞의 성공 안내·실패 안내가 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(200, reportJson(status: 'DISMISSED')))
        ..on('GET', _list, FakeReply(200, [postReport(id: 2)]))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pumpAndSettle();
      expect(find.text('처리했습니다'), findsOneWidget);
      await tester.tap(find.text('기각'));
      await tester.pumpAndSettle();
      expect(find.text('처리했습니다'), findsNothing);
    });
  });

  group('불러오기', () {
    _test('불러오는 동안 "불러오는 중..."이 보이고, 끝나면 사라진다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(reply: FakeReply(200, [postReport()], gate));
      await _open(tester, adapter);
      expect(find.text('불러오는 중...'), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(_row(1), findsOneWidget);
    });

    _test('신고가 없으면 "신고가 없습니다"', (tester) async {
      await _open(tester, _adapter(reports: []));
      expect(find.text('신고가 없습니다'), findsOneWidget);
    });

    _test('500 → 기본 문구 "신고 목록을 불러오지 못했습니다"(error) + [다시 시도], 누르면 다시 불러온다', (tester) async {
      final adapter = _adapter(reply: FakeReply(500))..on('GET', _list, FakeReply(200, [postReport()]));
      await _open(tester, adapter);
      expect(find.text('신고 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(_kind(tester, '신고 목록을 불러오지 못했습니다'), NoticeKind.error);
      expect(_retry(), findsOneWidget);
      expect(find.text('불러오는 중...'), findsNothing);
      await tester.tap(_retry());
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _list), 2);
      expect(find.text('신고 목록을 불러오지 못했습니다'), findsNothing);
      expect(_row(1), findsOneWidget);
    });

    _test('403 → "관리자만 볼 수 있습니다"(error), [다시 시도]는 없다', (tester) async {
      await _open(tester, _adapter(reply: FakeReply(403)));
      expect(_kind(tester, '관리자만 볼 수 있습니다'), NoticeKind.error);
      expect(_retry(), findsNothing);
      expect(find.text('미처리'), findsOneWidget); // 관리자로 보였으니 탭은 그대로
    });

    _test('네트워크 오류 → "서버에 연결할 수 없습니다" + [다시 시도]', (tester) async {
      await _open(tester, _adapter(reply: FakeReply.networkError()));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_retry(), findsOneWidget);
    });

    _test('401 + 재발급 401 → "다시 로그인해 주세요"와 로그인 링크 (관리자 안내로 바뀌지 않는다)', (tester) async {
      final adapter = _adapter(reply: FakeReply(401))..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(app.auth.user, isNull);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('관리자만 볼 수 있습니다'), findsNothing);
    });

    _test('실패한 뒤에는 옛 목록과 버튼이 남지 않는다 (탭을 바꿔 실패)', (tester) async {
      final adapter = _adapter()..on('GET', _list, FakeReply(500));
      await _open(tester, adapter);
      await tester.tap(find.text('기각'));
      await tester.pumpAndSettle();
      expect(find.text('신고 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(_row(1), findsNothing);
    });

    _test('늦게 도착한 옛 응답은 무시한다 (미처리 응답이 기각 탭 화면을 덮지 않는다)', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [postReport(id: 1)], gate))
        ..on('GET', _list, FakeReply(200, [postReport(id: 4)]));
      await _open(tester, adapter);
      await tester.tap(find.text('기각'));
      await tester.pumpAndSettle();
      expect(_row(4), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing);
      expect(_row(4), findsOneWidget);
    });
  });

  group('행', () {
    _test('글·댓글·중고·대화 신고가 섞여도 각자 배지·링크·버튼이 맞다', (tester) async {
      final adapter = _adapter(reports: [postReport(), commentReport(), marketReport(), conversationReport()]);
      await _open(tester, adapter);
      Finder badge(int id, String text) => find.descendant(of: _row(id), matching: find.text(text));
      expect(badge(1, '글'), findsOneWidget);
      expect(badge(2, '댓글'), findsOneWidget);
      expect(badge(3, '중고'), findsOneWidget);
      expect(badge(7, '쪽지'), findsOneWidget);
      expect(find.descendant(of: _row(1), matching: find.text('글로 가기')), findsOneWidget);
      expect(find.descendant(of: _row(2), matching: find.text('글로 가기')), findsOneWidget);
      expect(find.descendant(of: _row(3), matching: find.text('글로 가기')), findsOneWidget);
      expect(find.descendant(of: _row(7), matching: find.text('대화 보기')), findsOneWidget);
      expect(find.descendant(of: _row(7), matching: find.text('글로 가기')), findsNothing);
      for (final id in [1, 2, 3]) {
        expect(_deleteIn(id), findsOneWidget);
      }
      expect(_deleteIn(7), findsNothing); // 대화는 삭제 없이 [문제 없음]만
      for (final id in [1, 2, 3, 7]) {
        expect(_dismissIn(id), findsOneWidget);
      }
    });

    _test('배지 색: 글=청자, 댓글=회색, 중고=파랑, 쪽지=청자 글자+hover 배경', (tester) async {
      await _open(tester, _adapter(reports: [postReport(), commentReport(), marketReport(), conversationReport()]));
      (Color?, Color?) colors(int id, String text) {
        final label = find.descendant(of: _row(id), matching: find.text(text));
        final ink = tester.widget<Text>(label).style?.color;
        final box = tester.widget<Container>(find.ancestor(of: label, matching: find.byType(Container)).first);
        return (ink, (box.decoration as BoxDecoration?)?.color);
      }

      expect(colors(1, '글'), (AppColors.celadonInk, AppColors.celadonSoft));
      expect(colors(2, '댓글'), (AppColors.muted, AppColors.badgeChatBg));
      expect(colors(3, '중고'), (AppColors.badgeQuestionInk, AppColors.badgeQuestionBg));
      expect(colors(7, '쪽지'), (AppColors.celadonInk, AppColors.hover));
    });

    for (final (name, report, label, to) in <(String, Map<String, dynamic>, String, String)>[
      ('글 신고 [글로 가기]', postReport(), '글로 가기', '/posts/5'),
      ('댓글 신고 [글로 가기]는 postId 글로', commentReport(), '글로 가기', '/posts/5'),
      ('중고 신고 [글로 가기]는 /market/{target.id}', marketReport(), '글로 가기', '/market/42'),
      ('대화 신고 [대화 보기]는 방 id(12)가 아니라 신고 id(7)', conversationReport(), '대화 보기', '/admin/reports/7/conversation'),
    ]) {
      _test('$name → $to', (tester) async {
        final app = await _open(tester, _adapter(reports: [report]));
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        expect(_uri(app), to);
      });
    }

    _test('미리보기는 줄바꿈 그대로, 메타(대상 작성자·사유·상세·신고자·날짜)가 보인다', (tester) async {
      final r = reportJson(preview: '첫 줄\n둘째 줄', detail: '자세한 설명\n두 줄', reasonName: '욕설');
      await _open(tester, _adapter(reports: [r]));
      expect(find.text('첫 줄\n둘째 줄'), findsOneWidget);
      expect(find.text('대상 작성자'), findsOneWidget);
      expect(find.text('글쓴이'), findsOneWidget);
      expect(find.text('사유'), findsOneWidget);
      expect(find.text('욕설'), findsOneWidget);
      expect(find.text('자세한 설명\n두 줄'), findsOneWidget);
      expect(find.text('신고자'), findsNWidgets(2)); // 라벨 + 신고자 닉네임(테스트 데이터가 같은 글자)
      expect(find.text('날짜'), findsOneWidget);
      expect(find.text(formatDate('2026-11-12T00:00:00Z')), findsOneWidget);
    });

    _test('HTML 글자는 실행되지 않고 그대로 보인다', (tester) async {
      const html = '<b>굵게</b><script>x</script>';
      await _open(tester, _adapter(reports: [reportJson(preview: html)]));
      expect(find.text(html), findsOneWidget);
    });

    _test('상세가 없으면(null·빈 글자) "-"', (tester) async {
      await _open(tester, _adapter(reports: [reportJson(id: 1), reportJson(id: 2, detail: '')]));
      expect(find.text('-'), findsNWidgets(2));
    });

    _test('신고자가 없으면 "탈퇴한 회원", 작성자가 없으면 "탈퇴한 회원 또는 작성자 없음"', (tester) async {
      await _open(tester, _adapter(reports: [reportJson(reporter: null, author: null)]));
      expect(find.text('탈퇴한 회원'), findsOneWidget);
      expect(find.text('탈퇴한 회원 또는 작성자 없음'), findsOneWidget);
    });

    _test('대화 신고는 라벨이 "대화 상대", 상대가 없으면 "탈퇴한 회원 또는 상대 없음"', (tester) async {
      await _open(tester, _adapter(reports: [conversationReport(), reportJson(id: 8, type: 'CONVERSATION', author: null)]));
      expect(find.text('대화 상대'), findsNWidgets(2));
      expect(find.text('대상 작성자'), findsNothing);
      expect(find.text('판매자'), findsOneWidget);
      expect(find.text('탈퇴한 회원 또는 상대 없음'), findsOneWidget);
    });
  });

  group('삭제', () {
    for (final (type, report, what, path) in <(String, Map<String, dynamic>, String, String)>[
      ('글', postReport(), '글', '/posts/5'),
      ('댓글', commentReport(), '댓글', '/comments/9'),
      ('중고 글', marketReport(), '중고 글', '/market-items/42'),
    ]) {
      _test('$type: [삭제] → 확인 창 "신고된 $what을(를) 삭제할까요?" → [취소]면 요청 없이 그대로', (tester) async {
        final adapter = _adapter(reports: [report]);
        await _open(tester, adapter);
        await tester.tap(_deleteIn(report['id'] as int));
        await tester.pumpAndSettle();
        expect(find.text('신고된 $what을(를) 삭제할까요?'), findsOneWidget);
        await tester.tap(_dialogText('취소'));
        await tester.pumpAndSettle();
        expect(adapter.count('DELETE', path), 0);
        expect(find.byType(Dialog), findsNothing);
        expect(find.text('처리했습니다'), findsNothing);
      });

      _test('$type: 확인하면 DELETE $path → "처리했습니다"(success) + 목록 다시 읽기', (tester) async {
        final id = report['id'] as int;
        final adapter = _adapter(reports: [report])
          ..on('DELETE', path, FakeReply(204))
          ..on('GET', _list, FakeReply(200, []));
        await _open(tester, adapter);
        await tester.tap(_deleteIn(id));
        await tester.pumpAndSettle();
        await tester.tap(_dialogText('삭제'));
        await tester.pumpAndSettle();
        expect(adapter.count('DELETE', path), 1);
        expect(adapter.count('GET', _list), 2);
        expect(_kind(tester, '처리했습니다'), NoticeKind.success);
        expect(_row(id), findsNothing);
        expect(find.text('신고가 없습니다'), findsOneWidget);
      });
    }

    _test('Esc로 닫아도 취소와 같다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter);
      await tester.tap(_deleteIn(1));
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/posts/5'), 0);
    });

    for (final (status, message, refetch) in const [
      (404, '이미 삭제된 글입니다', true),
      (409, '이미 삭제된 글입니다', true),
      (403, '관리자만 볼 수 있습니다', false),
      (500, '삭제하지 못했습니다', false),
    ]) {
      _test('DELETE $status → "$message"(error)${refetch ? ' + 목록 다시 읽기' : ', 목록은 그대로'}', (tester) async {
        final adapter = _adapter()..on('DELETE', '/posts/5', FakeReply(status));
        if (refetch) adapter.on('GET', _list, FakeReply(200, []));
        await _open(tester, adapter);
        await tester.tap(_deleteIn(1));
        await tester.pumpAndSettle();
        await tester.tap(_dialogText('삭제'));
        await tester.pumpAndSettle();
        expect(_kind(tester, message), NoticeKind.error);
        expect(adapter.count('GET', _list), refetch ? 2 : 1);
        expect(find.text('처리했습니다'), findsNothing);
        if (refetch) {
          expect(_row(1), findsNothing);
        } else {
          expect(_row(1), findsOneWidget);
          expect(_enabled(tester, _deleteIn(1)), isTrue); // 버튼 되살아남
        }
        expect(_retry(), findsNothing);
      });
    }

    _test('댓글 신고 DELETE 404 → "이미 삭제된 댓글입니다", 중고 글은 "이미 삭제된 중고 글입니다"', (tester) async {
      final adapter = _adapter(reports: [commentReport(), marketReport()])
        ..on('DELETE', '/comments/9', FakeReply(404))
        ..on('GET', _list, FakeReply(200, [marketReport()]))
        ..on('DELETE', '/market-items/42', FakeReply(404))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_deleteIn(2));
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('이미 삭제된 댓글입니다'), findsOneWidget);
      await tester.tap(_deleteIn(3));
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('이미 삭제된 중고 글입니다'), findsOneWidget);
      expect(find.text('이미 삭제된 댓글입니다'), findsNothing); // 다음 처리를 시작하면 앞 안내는 지워진다
    });

    _test('DELETE 401 + 재발급 401 → "다시 로그인해 주세요", "삭제하지 못했습니다"는 없다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/posts/5', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter);
      await tester.tap(_deleteIn(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      expect(find.text('관리자만 볼 수 있습니다'), findsNothing);
    });
  });

  group('문제 없음', () {
    _test('확인 창 없이 바로 PUT /admin/reports/1/dismiss → "처리했습니다"(success) + 목록 다시 읽기', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(200, reportJson(status: 'DISMISSED')))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(adapter.count('PUT', '/admin/reports/1/dismiss'), 1);
      expect(adapter.count('GET', _list), 2);
      expect(_kind(tester, '처리했습니다'), NoticeKind.success);
      expect(find.text('신고가 없습니다'), findsOneWidget);
    });

    _test('대화 신고도 [문제 없음]으로 신고 id(7)에 PUT 하고 목록에서 사라진다', (tester) async {
      final adapter = _adapter(reports: [conversationReport()])
        ..on('PUT', '/admin/reports/7/dismiss', FakeReply(200, conversationReport()))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(7));
      await tester.pumpAndSettle();
      expect(adapter.count('PUT', '/admin/reports/7/dismiss'), 1);
      expect(_row(7), findsNothing);
      expect(find.text('처리했습니다'), findsOneWidget);
    });

    for (final (status, message, refetch) in const [
      (404, '이미 처리됐거나 사라진 신고입니다', true),
      (409, '이미 처리됐거나 사라진 신고입니다', true),
      (403, '관리자만 볼 수 있습니다', false),
      (500, '처리하지 못했습니다', false),
    ]) {
      _test('PUT $status → "$message"(error)${refetch ? ' + 목록 다시 읽기' : ', 목록은 그대로'}', (tester) async {
        final adapter = _adapter()..on('PUT', '/admin/reports/1/dismiss', FakeReply(status));
        if (refetch) adapter.on('GET', _list, FakeReply(200, []));
        await _open(tester, adapter);
        await tester.tap(_dismissIn(1));
        await tester.pumpAndSettle();
        expect(_kind(tester, message), NoticeKind.error);
        expect(adapter.count('GET', _list), refetch ? 2 : 1);
        expect(find.text('처리했습니다'), findsNothing);
        expect(_row(1), refetch ? findsNothing : findsOneWidget);
      });
    }

    _test('PUT 401 + 재발급 401 → "다시 로그인해 주세요"', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('처리하지 못했습니다'), findsNothing);
    });

    _test('네트워크 오류 → "서버에 연결할 수 없습니다", 목록은 그대로', (tester) async {
      final adapter = _adapter()..on('PUT', '/admin/reports/1/dismiss', FakeReply.networkError());
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_row(1), findsOneWidget);
    });
  });

  group('처리 중 잠금·안내 유지', () {
    _test('PUT이 끝날 때까지, 그리고 목록을 다시 읽을 때까지 버튼·탭이 모두 잠긴다 (요청은 한 번)', (tester) async {
      final putGate = Completer<void>();
      final getGate = Completer<void>();
      final adapter = _adapter(reports: [postReport(id: 1), postReport(id: 2)])
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(200, reportJson(status: 'DISMISSED'), putGate))
        ..on('GET', _list, FakeReply(200, [postReport(id: 2)], getGate));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pump(const Duration(milliseconds: 50));

      // PUT 진행 중
      for (final id in [1, 2]) {
        expect(_enabled(tester, _deleteIn(id)), isFalse);
        expect(_enabled(tester, _dismissIn(id)), isFalse);
      }
      expect(_tabEnabled(tester, '미처리'), isFalse);
      expect(_tabEnabled(tester, '기각'), isFalse);
      expect(adapter.count('PUT', '/admin/reports/1/dismiss'), 1);

      putGate.complete();
      await tester.pump(const Duration(milliseconds: 50));
      // 재조회 진행 중: 아직 잠겨 있다
      expect(adapter.count('GET', _list), 2);
      expect(_enabled(tester, _dismissIn(2)), isFalse);
      expect(_tabEnabled(tester, '기각'), isFalse);

      getGate.complete();
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing);
      expect(_enabled(tester, _dismissIn(2)), isTrue);
      expect(_tabEnabled(tester, '기각'), isTrue);
    });

    _test('삭제 확인 뒤에도 요청 중에는 버튼이 모두 잠긴다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(reports: [postReport(id: 1), commentReport(id: 2)])
        ..on('DELETE', '/posts/5', FakeReply(204, null, gate))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_deleteIn(1));
      await tester.pumpAndSettle();
      await tester.tap(_dialogText('삭제'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(_enabled(tester, _deleteIn(2)), isFalse);
      expect(_enabled(tester, _dismissIn(1)), isFalse);
      expect(adapter.count('DELETE', '/posts/5'), 1);
      gate.complete();
      await tester.pumpAndSettle();
    });

    _test('"처리했습니다"는 재조회가 끝나도 남고, 다음 처리를 시작하면 지워진다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(reports: [postReport(id: 1), postReport(id: 2)])
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(200, reportJson(status: 'DISMISSED')))
        ..on('GET', _list, FakeReply(200, [postReport(id: 2)]))
        ..on('PUT', '/admin/reports/2/dismiss', FakeReply(200, reportJson(id: 2, status: 'DISMISSED'), gate))
        ..on('GET', _list, FakeReply(200, []));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pumpAndSettle();
      expect(find.text('처리했습니다'), findsOneWidget); // 재조회가 끝난 뒤에도 남는다
      await tester.tap(_dismissIn(2));
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('처리했습니다'), findsNothing); // 다음 처리를 시작하면 지워진다
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('처리했습니다'), findsOneWidget);
    });

    _test('실패 안내도 다음 처리를 시작하면 지워진다', (tester) async {
      final adapter = _adapter(reports: [postReport(id: 1), postReport(id: 2)])
        ..on('PUT', '/admin/reports/1/dismiss', FakeReply(500))
        ..on('PUT', '/admin/reports/2/dismiss', FakeReply(200, reportJson(id: 2, status: 'DISMISSED')))
        ..on('GET', _list, FakeReply(200, [postReport(id: 1)]));
      await _open(tester, adapter);
      await tester.tap(_dismissIn(1));
      await tester.pumpAndSettle();
      expect(find.text('처리하지 못했습니다'), findsOneWidget);
      await tester.tap(_dismissIn(2));
      await tester.pumpAndSettle();
      expect(find.text('처리하지 못했습니다'), findsNothing);
      expect(find.text('처리했습니다'), findsOneWidget);
    });
  });

  testWidgets('375px 좁은 화면에서도 넘치지 않고 행과 버튼이 보인다', (tester) async {
    tester.view.devicePixelRatio = 1.0;
    tester.view.physicalSize = const Size(375, 1800);
    addTearDown(tester.view.reset);
    final r = reportJson(preview: '아주 긴 미리보기 ${'가나다라마바사 ' * 30}', detail: '상세 ${'아자차카타파하 ' * 20}');
    await _open(tester, _adapter(reports: [r, conversationReport()]));
    expect(tester.takeException(), isNull);
    expect(_deleteIn(1), findsOneWidget);
    expect(_dismissIn(7), findsOneWidget);
  });
}
