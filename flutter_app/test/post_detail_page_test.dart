// S16-f1 글 상세 화면 테스트: React PostDetailPage와 같은 동작(표시 항목, 불러오기 실패, 버튼 조건 표,
// SchoolGateNotice 3문구, 삭제 흐름)을 본다. 공감 버튼은 like_button_test, 신고 양식은 report_form_test에서 본다.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/nav_log.dart';
import 'support/post_json.dart';
import 'support/screen.dart';

const _seoul = {'id': 1, 'name': '서울대'};
const _busan = {'id': 2, 'name': '부산대'};

// 서울대 인증 회원 (id 1, 글쓴이 id 1과 같다 = 내 글)
Map<String, dynamic> _seoulUser({String role = 'USER', int id = 1}) => userJson(id: id, role: role, school: _seoul);

// 글 불러오기(+로그인 시 "내가 눌렀는지") 응답을 정한 가짜 서버
FakeAdapter _adapter({FakeReply? post}) => FakeAdapter()
  ..on('GET', '/posts/7', post ?? FakeReply(200, postJson()))
  ..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}));

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) async {
  final app = await pumpApp(tester, location: '/posts/7', user: user, adapter: adapter);
  // 글이 뜬 뒤에야 공감 버튼이 "내가 눌렀는지"(likes/me)를 묻는다. 그 요청의 타이머가 끝나도록 한 번 더 흘려 보낸다
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
  return app;
}

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

Finder _btn(String text) => find.widgetWithText(OutlinedButton, text);
final _like = find.textContaining('공감 ');
final _edit = _btn('수정');
final _delete = _btn('삭제');
final _report = _btn('신고');
final _toList = _btn('목록으로');

// 삭제 확인 창 안의 버튼
Finder _dialogBtn(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));

void main() {
  group('표시', () {
    _test('불러오는 동안은 "불러오는 중..."과 [목록으로]만 보인다', (tester) async {
      final gate = Completer<void>();
      await _open(tester, _adapter(post: FakeReply(200, postJson(), gate)));
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_toList, findsOneWidget);
      expect(find.text('제목입니다'), findsNothing);
      expect(_like, findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(find.text('제목입니다'), findsOneWidget);
    });

    _test('말머리·학교 뱃지, 제목, 작성자, 날짜, 본문(줄바꿈 유지)이 보인다', (tester) async {
      final post = postJson(
        title: '글 제목',
        content: '첫 줄\n둘째 줄\n\n넷째 줄',
        category: {'code': 'QUESTION', 'name': '질문'},
        author: {'id': 2, 'nickname': '김철수'},
      );
      await _open(tester, _adapter(post: FakeReply(200, post)));
      expect(find.text('질문'), findsOneWidget);
      expect(find.text('서울대'), findsOneWidget);
      expect(find.text('글 제목'), findsOneWidget);
      expect(find.text('김철수'), findsOneWidget);
      expect(find.text(formatDate('2020-11-08T06:19:00Z')), findsOneWidget);
      expect(find.text('첫 줄\n둘째 줄\n\n넷째 줄'), findsOneWidget); // 한 덩어리 글자 = 줄바꿈이 그대로
    });

    _test('제목은 제목(heading) 시맨틱이다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter());
      expect(tester.getSemantics(find.text('제목입니다')).flagsCollection.isHeader, isTrue);
      handle.dispose();
    });

    _test('학교 없는 글은 "학교 미지정", 작성자 없는 글은 "(작성자 없음)"', (tester) async {
      await _open(tester, _adapter(post: FakeReply(200, postJson(school: null, author: null))));
      expect(find.text('학교 미지정'), findsOneWidget);
      expect(find.text('(작성자 없음)'), findsOneWidget);
    });

    _test('본문은 글자로만 그린다: HTML 글자도 그대로 보이고, 드래그로 고를 수 있다(SelectionArea 안)', (tester) async {
      const html = '<b>굵게</b><script>alert(1)</script>';
      await _open(tester, _adapter(post: FakeReply(200, postJson(content: html))));
      expect(find.text(html), findsOneWidget);
      expect(find.descendant(of: find.byType(SelectionArea), matching: find.text(html)), findsOneWidget);
      expect(find.descendant(of: find.byType(SelectionArea), matching: find.text('제목입니다')), findsOneWidget);
    });

    _test('공감 숫자는 글의 likeCount로 시작한다', (tester) async {
      await _open(tester, _adapter(post: FakeReply(200, postJson(likeCount: 12))));
      expect(find.text('🤍 공감 12'), findsOneWidget);
    });

    _test('주소의 id 글자는 인코딩해서 요청한다 (/posts/a%3Fb → GET /posts/a%3Fb)', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/posts/a%3Fb', FakeReply(404));
      await pumpApp(tester, location: '/posts/a%3Fb', adapter: adapter);
      expect(adapter.calls.where((c) => c.method == 'GET').map((c) => c.path), contains('/api/posts/a%3Fb'));
    });
  });

  group('불러오기 실패', () {
    for (final (status, message) in const [
      (404, '글을 찾을 수 없습니다'),
      (400, '글을 찾을 수 없습니다'),
      (500, '글을 불러오지 못했습니다'),
      (401, '글을 불러오지 못했습니다'),
    ]) {
      _test('GET $status → "$message" + [목록으로]만 (글·작업 줄 없음)', (tester) async {
        final adapter = FakeAdapter()..on('GET', '/posts/7', FakeReply(status));
        if (status == 401) adapter.on('POST', '/auth/refresh', FakeReply(401));
        final app = await _open(tester, adapter, user: status == 401 ? _seoulUser() : null);
        if (status == 401) {
          await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
          await tester.pumpAndSettle();
        }
        expect(find.text(message), findsOneWidget);
        expect(find.text('불러오는 중...'), findsNothing);
        expect(find.text('다시 로그인해 주세요'), findsNothing); // 불러오기 실패엔 로그인 안내가 없다
        expect(_like, findsNothing);
        expect(_edit, findsNothing);
        expect(_delete, findsNothing);
        expect(_report, findsNothing);
        expect(_toList, findsOneWidget);

        await tester.tap(_toList);
        await tester.pumpAndSettle();
        expect(_uri(app), '/');
      });
    }

    _test('네트워크 오류 → "서버에 연결할 수 없습니다" + [목록으로]', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/posts/7', FakeReply.networkError());
      await _open(tester, adapter);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_toList, findsOneWidget);
    });

    _test('[목록으로]는 글이 보일 때도 있고 누르면 /', (tester) async {
      final app = await _open(tester, _adapter());
      expect(_toList, findsOneWidget);
      await tester.tap(_toList);
      await tester.pumpAndSettle();
      expect(_uri(app), '/');
    });
  });

  // 버튼 조건 표: (설명, 로그인 사용자, 글, 수정, 삭제, 신고, 안내문 글자)
  group('버튼 조건 표', () {
    final cases = <(String, Map<String, dynamic>?, Map<String, dynamic>, bool, bool, bool, String?)>[
      ('비로그인', null, postJson(), false, false, false, null),
      ('본인(같은 학교)', _seoulUser(), postJson(), true, true, false, null),
      ('남의 글(같은 학교)', _seoulUser(id: 5), postJson(author: {'id': 2, 'nickname': '남'}), false, false, true, null),
      ('관리자 + 남의 글(같은 학교)', _seoulUser(role: 'ADMIN', id: 9), postJson(author: {'id': 2, 'nickname': '남'}), false, true, true, null),
      (
        '관리자 + 남의 글(학교 미인증)',
        userJson(id: 9, role: 'ADMIN'),
        postJson(author: {'id': 2, 'nickname': '남'}),
        false,
        true,
        false,
        '학교 인증 후 같은 학교 글에만 댓글·공감·신고할 수 있어요',
      ),
      (
        '다른 학교 남의 글',
        userJson(id: 5, school: _busan),
        postJson(author: {'id': 2, 'nickname': '남'}),
        false,
        false,
        false,
        '인증한 학교(부산대) 글에만 댓글·공감·신고할 수 있어요',
      ),
      (
        '본인 글인데 다른 학교(옮겨 인증)',
        userJson(id: 1, school: _busan),
        postJson(),
        true,
        true,
        false,
        '인증한 학교(부산대) 글에만 댓글·공감·신고할 수 있어요',
      ),
      (
        '학교 없는 옛 글(남의 글)',
        _seoulUser(id: 5),
        postJson(school: null, author: {'id': 2, 'nickname': '남'}),
        false,
        false,
        false,
        '학교가 정해지지 않은 옛 글은 읽기만 할 수 있어요',
      ),
      ('탈퇴한 작성자 글(같은 학교): 신고가 보인다', _seoulUser(), postJson(author: null), false, false, true, null),
    ];
    for (final (name, user, post, edit, delete, report, notice) in cases) {
      _test(name, (tester) async {
        await _open(tester, _adapter(post: FakeReply(200, post)), user: user);
        expect(_like, findsOneWidget); // 공감은 항상
        expect(_edit, edit ? findsOneWidget : findsNothing);
        expect(_delete, delete ? findsOneWidget : findsNothing);
        expect(_report, report ? findsOneWidget : findsNothing);
        if (notice == null) {
          expect(find.textContaining('댓글·공감·신고할 수 있어요'), findsNothing);
          expect(find.textContaining('읽기만 할 수 있어요'), findsNothing);
        } else {
          expect(find.text(notice), findsOneWidget);
          // 안내문은 정보(info) 종류
          expect(tester.widget<Notice>(find.widgetWithText(Notice, notice)).kind, NoticeKind.info);
        }
      });
    }

    _test('수정 링크는 /posts/7/edit 로 간다', (tester) async {
      final app = await _open(tester, _adapter(), user: _seoulUser());
      await tester.tap(_edit);
      await tester.pumpAndSettle();
      expect(_uri(app), '/posts/7/edit');
    });

    _test('학교 미인증 안내의 [학교 인증하기]는 /me 로 간다', (tester) async {
      final app = await _open(tester, _adapter(), user: userJson(id: 5));
      await tester.tap(find.text('학교 인증하기'));
      await tester.pumpAndSettle();
      expect(_uri(app), '/me');
    });

    _test('안내문 링크는 미인증일 때만 있다 (다른 학교·옛 글 안내엔 없음)', (tester) async {
      await _open(tester, _adapter(), user: userJson(id: 5, school: _busan));
      expect(find.text('학교 인증하기'), findsNothing);
    });
  });

  group('삭제', () {
    _test('[삭제] → 확인 창 "이 글을 삭제할까요?" → [취소]면 요청 없이 그대로', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      expect(find.text('이 글을 삭제할까요?'), findsOneWidget);
      await tester.tap(_dialogBtn('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(adapter.count('DELETE', '/posts/7'), 0);
      expect(_uri(app), '/posts/7');
      expect(_delete, findsOneWidget);
    });

    _test('Esc로 닫아도 취소와 같다 (요청 없음)', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/posts/7'), 0);
    });

    _test('확인하면 "삭제 중..."으로 잠기고 요청은 한 번, 성공하면 / 로 가며 기록을 교체한다', (tester) async {
      final nav = NavLog()..install(tester);
      final gate = Completer<void>();
      final adapter = _adapter()..on('DELETE', '/posts/7', FakeReply(204, null, gate));
      final app = await _open(tester, adapter, user: _seoulUser());

      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pump(const Duration(milliseconds: 50));
      expect(_btn('삭제 중...'), findsOneWidget);
      expect(tester.widget<OutlinedButton>(_btn('삭제 중...')).onPressed, isNull);
      expect(adapter.count('DELETE', '/posts/7'), 1);
      expect(_uri(app), '/posts/7');

      gate.complete();
      await tester.pumpAndSettle();
      expect(_uri(app), '/');
      // 기록 교체 (React의 navigate('/', { replace: true })): 삭제된 글의 상세 칸을 덮어쓴다
      expect(nav.last.path, '/');
      expect(nav.last.replace, isTrue);
    });

    // (상태, 본인 글인지, 기대 문구, 글이 그대로 보이는지)
    for (final (status, mine, message) in const [
      (403, true, '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'),
      (403, false, '삭제할 권한이 없습니다'),
      (404, true, '이미 삭제된 글입니다'),
      (500, true, '삭제하지 못했습니다'),
    ]) {
      _test('DELETE $status (${mine ? '본인' : '관리자가 남의 글'}) → "$message", 글은 남고 머문다, 버튼 되살아남', (tester) async {
        final post = mine ? postJson() : postJson(author: {'id': 2, 'nickname': '남'});
        final user = mine ? _seoulUser() : _seoulUser(role: 'ADMIN', id: 9);
        final adapter = _adapter(post: FakeReply(200, post))..on('DELETE', '/posts/7', FakeReply(status));
        final app = await _open(tester, adapter, user: user);
        await tester.tap(_delete);
        await tester.pumpAndSettle();
        await tester.tap(_dialogBtn('삭제'));
        await tester.pumpAndSettle();

        expect(find.text(message), findsOneWidget);
        expect(_uri(app), '/posts/7');
        expect(find.text('제목입니다'), findsOneWidget);
        expect(tester.widget<OutlinedButton>(_delete).onPressed, isNotNull);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    _test('DELETE 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('DELETE', '/posts/7', FakeReply.networkError());
      await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('다시 삭제하면 이전 실패 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/posts/7', FakeReply(500))
        ..on('DELETE', '/posts/7', FakeReply(204));
      final app = await _open(tester, adapter, user: _seoulUser());
      for (var i = 0; i < 2; i++) {
        await tester.tap(_delete);
        await tester.pumpAndSettle();
        await tester.tap(_dialogBtn('삭제'));
        await tester.pumpAndSettle();
        if (i == 0) expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      }
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      expect(_uri(app), '/');
    });

    _test('401(재발급도 401) → "다시 로그인해 주세요"+로그인 링크, 주소 유지, 일반 실패 문구 없음', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/posts/7', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(app.auth.user, isNull);
      expect(_uri(app), '/posts/7'); // 풀려도 화면(주소)은 그대로
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      expect(find.text('제목입니다'), findsOneWidget); // 글은 계속 보인다
      expect(_delete, findsNothing); // 로그인이 풀려 버튼은 사라지고 안내만 남는다

      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });

    _test('확인 창이 떠 있을 때 주소가 바뀌면 창은 취소로 닫히고 삭제 요청은 없다', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter, user: _seoulUser());
      await tester.tap(_delete);
      await tester.pumpAndSettle();
      app.router.go('/');
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsNothing);
      expect(adapter.count('DELETE', '/posts/7'), 0);
    });
  });
}
