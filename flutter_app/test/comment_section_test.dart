// S16-f2 댓글 영역 테스트: React CommentSection과 같은 동작(목록·로딩·실패·다시 시도, 버튼 조건 표,
// 쓰기·인라인 수정·삭제의 성공/실패 문구, busy 공유, 댓글 신고, 힌트·로그인 링크)을 본다.
// 댓글 영역은 글 상세 안에 붙어 있어서, 진짜 상세 화면(/posts/7)을 띄워 가짜 서버로 확인한다.
import 'dart:async';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/widgets/comment_section.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';
import 'support/screen.dart';

const _seoul = {'id': 1, 'name': '서울대'};
const _busan = {'id': 2, 'name': '부산대'};

// 서울대 인증 회원 (글쓴이는 id 99라서 기본으로 "내 글"이 아니다)
Map<String, dynamic> _user({String role = 'USER', int id = 5, Map<String, dynamic>? school = _seoul}) =>
    userJson(id: id, role: role, school: school);

// 댓글 JSON 한 건. authorId가 null이면 탈퇴한 작성자(author: null)
Map<String, dynamic> _c(int id, String content, {int? authorId, String createdAt = '2020-11-08T06:19:00Z'}) => {
  'id': id,
  'postId': 7,
  'content': content,
  'createdAt': createdAt,
  'author': authorId == null ? null : {'id': authorId, 'nickname': '작성자$authorId'},
};

// 기본 댓글 3개: 1 = 내 댓글(id 5), 2 = 남의 댓글(id 6), 3 = 탈퇴한 작성자
List<Map<String, dynamic>> _three() => [
  _c(1, '내가 쓴 댓글', authorId: 5),
  _c(2, '남이 쓴 댓글', authorId: 6),
  _c(3, '탈퇴한 사람 댓글'),
];

// 글 + 댓글 목록 + (로그인 시) 공감 여부 응답을 정한 가짜 서버
FakeAdapter _adapter({FakeReply? comments, Map<String, dynamic>? school = _seoul}) => FakeAdapter()
  ..on('GET', '/posts/7', FakeReply(200, postJson(author: {'id': 99, 'nickname': '글쓴이'}, school: school)))
  ..on('GET', '/posts/7/comments', comments ?? FakeReply(200, _three()))
  ..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}));

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) async {
  final app = await pumpApp(tester, location: '/posts/7', user: user, adapter: adapter);
  // 공감 버튼의 likes/me 요청 타이머가 끝나도록 한 번 더 흘려 보낸다
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

// 401은 자동 재발급을 먼저 시도한다(실패하면 로그인이 풀린다). 그 비동기 요청이 끝나길 기다린다
Future<void> _waitRefresh(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
  await tester.pumpAndSettle();
}

Finder _row(int id) => find.byKey(ValueKey('comment-$id'));
// 댓글 행 안의 버튼 (글 상단의 같은 이름 버튼과 섞이지 않게 행 안에서만 찾는다)
Finder _btn(int id, String text) =>
    find.descendant(of: _row(id), matching: find.widgetWithText(OutlinedButton, text));
// 댓글 영역 안의 [로그인] 링크 (상단바에도 같은 글자가 있어서 영역 안에서만 찾는다)
final _sectionLogin = find.descendant(of: find.byType(CommentSection), matching: find.text('로그인'));
// 댓글 영역 안의 로그인 안내 (영역 밖의 같은 안내와 섞이지 않게)
final _reloginInSection = find.descendant(of: find.byType(CommentSection), matching: find.byType(ReloginNotice));
final _newField = find.byKey(const ValueKey('comment-new'));
final _editField = find.byKey(const ValueKey('comment-edit'));
final _register = find.widgetWithText(FilledButton, '등록');
final _save = find.widgetWithText(FilledButton, '저장');
final _cancel = find.widgetWithText(OutlinedButton, '취소');
Finder _dialogBtn(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));

String _fieldText(WidgetTester tester, Finder field) => tester.widget<TextField>(field).controller!.text;
bool _enabled(WidgetTester tester, Finder button) => tester.widget<OutlinedButton>(button).onPressed != null;

// 서버가 받은 요청 본문들
List<Map<dynamic, dynamic>> _recordBodies(FakeAdapter adapter) {
  final bodies = <Map<dynamic, dynamic>>[];
  adapter.onRequestBody = (data) {
    if (data is Map) bodies.add(data);
  };
  return bodies;
}

void main() {
  group('목록', () {
    _test('불러오는 동안은 "댓글 불러오는 중..."만 보이고, 끝나면 헤더에 개수가 붙는다', (tester) async {
      final gate = Completer<void>();
      await _open(tester, _adapter(comments: FakeReply(200, _three(), gate)));
      expect(find.text('댓글'), findsOneWidget); // 개수는 아직 없다
      expect(find.text('댓글 불러오는 중...'), findsOneWidget);
      expect(find.text('아직 댓글이 없습니다'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('댓글 불러오는 중...'), findsNothing);
      expect(find.text('댓글 3'), findsOneWidget);
    });

    _test('빈 목록 → "아직 댓글이 없습니다", 헤더 "댓글 0"', (tester) async {
      await _open(tester, _adapter(comments: FakeReply(200, [])));
      expect(find.text('아직 댓글이 없습니다'), findsOneWidget);
      expect(find.text('댓글 0'), findsOneWidget);
    });

    _test('행: 작성자·날짜·본문(줄바꿈 유지)이 보이고 서버 순서 그대로다', (tester) async {
      final list = [
        _c(1, '첫 줄\n둘째 줄\n\n넷째 줄', authorId: 6, createdAt: '2020-11-08T06:19:00Z'),
        _c(2, '두 번째', authorId: 7),
        _c(3, '탈퇴한 사람 댓글'),
      ];
      await _open(tester, _adapter(comments: FakeReply(200, list)));
      expect(find.text('작성자6'), findsOneWidget);
      expect(find.text(formatDate('2020-11-08T06:19:00Z')), findsNWidgets(4)); // 댓글 3개 + 글 날짜 1개
      expect(find.text('첫 줄\n둘째 줄\n\n넷째 줄'), findsOneWidget); // 한 덩어리 글자 = 줄바꿈이 그대로
      expect(find.text('(작성자 없음)'), findsOneWidget);
      final y1 = tester.getTopLeft(_row(1)).dy;
      final y2 = tester.getTopLeft(_row(2)).dy;
      final y3 = tester.getTopLeft(_row(3)).dy;
      expect(y1 < y2 && y2 < y3, isTrue);
    });

    _test('내용은 글자로만 그린다: HTML 글자도 그대로 보이고, 드래그로 고를 수 있다(SelectionArea 안)', (tester) async {
      const html = '<b>굵게</b><script>alert(1)</script>';
      await _open(tester, _adapter(comments: FakeReply(200, [_c(1, html, authorId: 6)])));
      expect(find.text(html), findsOneWidget);
      expect(find.descendant(of: find.byType(SelectionArea), matching: find.text(html)), findsOneWidget);
    });

    _test('불러오기 500 → "댓글을 불러오지 못했습니다" + [다시 시도], [다시 시도]로 다시 받는다', (tester) async {
      final adapter = _adapter(comments: FakeReply(500))..on('GET', '/posts/7/comments', FakeReply(200, _three()));
      await _open(tester, adapter);
      expect(find.text('댓글을 불러오지 못했습니다'), findsOneWidget);
      expect(find.text('댓글'), findsOneWidget); // 개수 없음
      expect(find.text('댓글 불러오는 중...'), findsNothing);
      expect(find.text('아직 댓글이 없습니다'), findsNothing);
      // 글 본문은 그대로 보인다
      expect(find.text('제목입니다'), findsOneWidget);

      await tester.tap(find.widgetWithText(OutlinedButton, '다시 시도'));
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/posts/7/comments'), 2);
      expect(find.text('댓글을 불러오지 못했습니다'), findsNothing);
      expect(find.text('댓글 3'), findsOneWidget);
    });

    _test('불러오기 네트워크 오류 → "서버에 연결할 수 없습니다" + [다시 시도]', (tester) async {
      await _open(tester, _adapter(comments: FakeReply.networkError()));
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, '다시 시도'), findsOneWidget);
    });

    _test('글을 못 불러오면 댓글 영역도 없고 댓글 요청도 안 간다', (tester) async {
      final adapter = FakeAdapter()..on('GET', '/posts/7', FakeReply(404));
      await _open(tester, adapter);
      expect(find.text('댓글'), findsNothing);
      expect(find.text('로그인하면 댓글을 쓸 수 있습니다 '), findsNothing);
      expect(adapter.count('GET', '/posts/7/comments'), 0);
    });

    _test('댓글 요청 주소는 id를 인코딩한다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/posts/a%3Fb', FakeReply(200, postJson()))
        ..on('GET', '/posts/a%3Fb/comments', FakeReply(200, []));
      await pumpApp(tester, location: '/posts/a%3Fb', adapter: adapter);
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pumpAndSettle();
      expect(adapter.calls.map((c) => c.path), contains('/api/posts/a%3Fb/comments'));
    });
  });

  // 버튼 조건 표: (설명, 로그인 사용자, 학교, 행별 (수정, 삭제, 신고), 힌트 있음, 폼 있음, 로그인 링크 있음)
  group('버튼·입력칸 조건 표', () {
    const none = (false, false, false);
    final cases = <(String, Map<String, dynamic>?, Map<String, dynamic>?, Map<int, (bool, bool, bool)>, bool, bool, bool)>[
      (
        '본인(같은 학교): 내 댓글은 수정·삭제, 남·탈퇴 작성자 댓글은 신고',
        _user(),
        _seoul,
        {1: (true, true, false), 2: (false, false, true), 3: (false, false, true)},
        false,
        true,
        false,
      ),
      (
        '관리자(같은 학교): 모든 댓글 삭제 + 신고',
        _user(role: 'ADMIN', id: 9),
        _seoul,
        {1: (false, true, true), 2: (false, true, true), 3: (false, true, true)},
        false,
        true,
        false,
      ),
      (
        '관리자(학교 미인증): 삭제만, 신고·폼 없음 + 힌트',
        userJson(id: 9, role: 'ADMIN'),
        _seoul,
        {1: (false, true, false), 2: (false, true, false), 3: (false, true, false)},
        true,
        false,
        false,
      ),
      (
        '다른 학교 회원: 내 댓글은 수정·삭제만, 남의 댓글은 아무 버튼 없음 + 힌트',
        _user(school: _busan),
        _seoul,
        {1: (true, true, false), 2: none, 3: none},
        true,
        false,
        false,
      ),
      (
        '학교 없는 글(회원): 신고·폼 없음 + 힌트',
        _user(),
        null,
        {1: (true, true, false), 2: none, 3: none},
        true,
        false,
        false,
      ),
      (
        '비로그인: 버튼 없음, 폼 대신 로그인 링크',
        null,
        _seoul,
        {1: none, 2: none, 3: none},
        false,
        false,
        true,
      ),
    ];
    for (final (name, user, school, rows, hint, form, link) in cases) {
      _test(name, (tester) async {
        await _open(tester, _adapter(school: school), user: user);
        for (final MapEntry(key: id, value: (edit, delete, report)) in rows.entries) {
          expect(_btn(id, '수정'), edit ? findsOneWidget : findsNothing, reason: '댓글 $id 수정');
          expect(_btn(id, '삭제'), delete ? findsOneWidget : findsNothing, reason: '댓글 $id 삭제');
          expect(_btn(id, '신고'), report ? findsOneWidget : findsNothing, reason: '댓글 $id 신고');
        }
        expect(find.text('댓글은 인증한 학교의 글에만 쓸 수 있어요'), hint ? findsOneWidget : findsNothing);
        expect(_newField, form ? findsOneWidget : findsNothing);
        expect(_register, form ? findsOneWidget : findsNothing);
        expect(find.text('로그인하면 댓글을 쓸 수 있습니다 '), link ? findsOneWidget : findsNothing);
        expect(_sectionLogin, link ? findsOneWidget : findsNothing);
      });
    }

    _test('비로그인의 [로그인] 링크는 /login 으로 간다', (tester) async {
      final app = await _open(tester, _adapter());
      await tester.tap(_sectionLogin);
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/login');
    });
  });

  group('쓰기', () {
    _test('입력칸은 "댓글 내용" 이름·"댓글을 입력하세요" 안내·3줄이다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(), user: _user());
      expect(find.bySemanticsLabel(RegExp('댓글 내용')), findsWidgets); // 안내 글자와 한 이름으로 합쳐질 수 있다
      expect(find.text('댓글을 입력하세요'), findsOneWidget);
      final field = tester.widget<TextField>(_newField);
      expect((field.minLines, field.maxLines), (3, 3));
      handle.dispose();
    });

    _test('등록 성공 → 맨 뒤에 붙고 입력칸이 비고 헤더가 +1', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply(201, _c(10, '새 댓글', authorId: 5)));
      final bodies = _recordBodies(adapter);
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '새 댓글');
      await tester.tap(_register);
      await tester.pumpAndSettle();

      expect(bodies.last, {'content': '새 댓글'});
      expect(_fieldText(tester, _newField), '');
      expect(find.text('댓글 4'), findsOneWidget);
      expect(tester.getTopLeft(_row(10)).dy > tester.getTopLeft(_row(3)).dy, isTrue);
      expect(_btn(10, '수정'), findsOneWidget); // 내 댓글이라 수정·삭제가 붙는다
    });

    _test('등록하는 동안 버튼은 "등록 중..."이고 눌리지 않는다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply(201, _c(10, '새 댓글', authorId: 5), gate));
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '새 댓글');
      await tester.tap(_register);
      await tester.pump();
      expect(find.text('등록 중...'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, '등록 중...')).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('등록 중...'), findsNothing);
      expect(_register, findsOneWidget);
    });

    _test('비어 있어도 앱에서는 막지 않고 서버로 보낸다 (서버 400을 그대로 보여 준다)', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply(400));
      final bodies = _recordBodies(adapter);
      await _open(tester, adapter, user: _user());
      await tester.tap(_register);
      await tester.pumpAndSettle();
      expect(bodies.last, {'content': ''});
      expect(find.text('내용을 입력해 주세요 (최대 1000자)'), findsOneWidget);
    });

    for (final (status, message) in const [
      (400, '내용을 입력해 주세요 (최대 1000자)'),
      (403, '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요'),
      (404, '글을 찾을 수 없습니다'),
      (500, '댓글을 등록하지 못했습니다'),
    ]) {
      _test('등록 $status → "$message" (입력·목록 유지, 다시 누를 수 있다)', (tester) async {
        final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply(status));
        await _open(tester, adapter, user: _user());
        await tester.enterText(_newField, '쓰던 내용');
        await tester.tap(_register);
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(_fieldText(tester, _newField), '쓰던 내용');
        expect(find.text('댓글 3'), findsOneWidget);
        expect(tester.widget<FilledButton>(_register).onPressed, isNotNull);
      });
    }

    _test('등록 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply.networkError());
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '내용');
      await tester.tap(_register);
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('등록 401(재발급도 401) → "다시 로그인해 주세요"+로그인 링크, 일반 실패 문구 없음', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts/7/comments', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '내용');
      await tester.tap(_register);
      await _waitRefresh(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('로그인'), findsWidgets);
      expect(find.text('댓글을 등록하지 못했습니다'), findsNothing);
      expect(_reloginInSection, findsOneWidget); // 안내는 댓글 영역 안에 하나만
      // 로그인이 풀려서 새 댓글 칸은 사라지고 로그인 링크 문구가 보인다
      expect(_newField, findsNothing);
      expect(find.text('로그인하면 댓글을 쓸 수 있습니다 '), findsOneWidget);
    });

    _test('다시 등록하면 이전 실패 문구는 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts/7/comments', FakeReply(500))
        ..on('POST', '/posts/7/comments', FakeReply(201, _c(10, '새 댓글', authorId: 5)));
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '새 댓글');
      await tester.tap(_register);
      await tester.pumpAndSettle();
      expect(find.text('댓글을 등록하지 못했습니다'), findsOneWidget);
      await tester.tap(_register);
      await tester.pumpAndSettle();
      expect(find.text('댓글을 등록하지 못했습니다'), findsNothing);
      expect(find.text('댓글 4'), findsOneWidget);
    });
  });

  group('인라인 수정', () {
    Future<void> startEdit(WidgetTester tester, [int id = 1]) async {
      await tester.tap(_btn(id, '수정'));
      await tester.pumpAndSettle();
    }

    _test('[수정]을 누르면 같은 자리에 현재 내용이 든 입력칸과 [저장]·[취소]가 나온다', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(), user: _user());
      await startEdit(tester);
      expect(_editField, findsOneWidget);
      expect(_fieldText(tester, _editField), '내가 쓴 댓글');
      expect(find.bySemanticsLabel('댓글 수정'), findsWidgets);
      expect(_save, findsOneWidget);
      expect(find.descendant(of: _row(1), matching: _cancel), findsOneWidget);
      expect(_btn(1, '수정'), findsNothing);
      expect(_btn(1, '삭제'), findsNothing);
      handle.dispose();
    });

    _test('저장 성공 → PUT /comments/1 {content}, 응답으로 교체, 편집 끝, 헤더 그대로', (tester) async {
      final adapter = _adapter()..on('PUT', '/comments/1', FakeReply(200, _c(1, '고친 댓글', authorId: 5)));
      final bodies = _recordBodies(adapter);
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.enterText(_editField, '고친 댓글');
      await tester.tap(_save);
      await tester.pumpAndSettle();

      expect(bodies.last, {'content': '고친 댓글'});
      expect(adapter.count('PUT', '/comments/1'), 1);
      expect(_editField, findsNothing);
      expect(find.text('고친 댓글'), findsOneWidget);
      expect(find.text('내가 쓴 댓글'), findsNothing);
      expect(find.text('댓글 3'), findsOneWidget);
      expect(find.text('남이 쓴 댓글'), findsOneWidget); // 다른 댓글은 그대로
      expect(_btn(1, '수정'), findsOneWidget);
      expect(find.text('수정됨'), findsNothing); // 수정됨 표시는 없다
    });

    _test('[취소] → 요청 없이 원래 내용이 그대로 보인다', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.enterText(_editField, '바꾸다 만 내용');
      await tester.tap(find.descendant(of: _row(1), matching: _cancel));
      await tester.pumpAndSettle();
      expect(adapter.count('PUT', '/comments/1'), 0);
      expect(_editField, findsNothing);
      expect(find.text('내가 쓴 댓글'), findsOneWidget);
      expect(find.text('바꾸다 만 내용'), findsNothing);
    });

    _test('저장하는 동안 [저장]은 "저장 중..."이고 [저장]·[취소] 모두 눌리지 않는다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('PUT', '/comments/1', FakeReply(200, _c(1, '고침', authorId: 5), gate));
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.tap(_save);
      await tester.pump();
      expect(find.text('저장 중...'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, '저장 중...')).onPressed, isNull);
      expect(_enabled(tester, find.descendant(of: _row(1), matching: _cancel)), isFalse);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_editField, findsNothing);
    });

    for (final (status, message) in const [
      (400, '내용을 입력해 주세요 (최대 1000자)'),
      (403, '본인 댓글만 수정할 수 있습니다'),
      (500, '수정하지 못했습니다'),
    ]) {
      _test('저장 $status → "$message" (편집·입력 유지)', (tester) async {
        final adapter = _adapter()..on('PUT', '/comments/1', FakeReply(status));
        await _open(tester, adapter, user: _user());
        await startEdit(tester);
        await tester.enterText(_editField, '고치던 내용');
        await tester.tap(_save);
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(_fieldText(tester, _editField), '고치던 내용');
        expect(find.text('댓글 3'), findsOneWidget);
        expect(tester.widget<FilledButton>(_save).onPressed, isNotNull);
      });
    }

    _test('저장 404 → "이미 삭제된 댓글입니다" + 목록에서 제거 + 편집 끝 + 헤더 -1', (tester) async {
      final adapter = _adapter()..on('PUT', '/comments/1', FakeReply(404));
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.tap(_save);
      await tester.pumpAndSettle();
      expect(find.text('이미 삭제된 댓글입니다'), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(_editField, findsNothing);
      expect(find.text('댓글 2'), findsOneWidget);
    });

    _test('저장 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('PUT', '/comments/1', FakeReply.networkError());
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.tap(_save);
      await tester.pumpAndSettle();
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('저장 401(재발급도 401) → "다시 로그인해 주세요", 일반 실패 문구 없음', (tester) async {
      final adapter = _adapter()
        ..on('PUT', '/comments/1', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter, user: _user());
      await startEdit(tester);
      await tester.tap(_save);
      await _waitRefresh(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('수정하지 못했습니다'), findsNothing);
      expect(_reloginInSection, findsOneWidget);
      // 쓰던 편집칸과 입력값은 그대로 남는다
      expect(_editField, findsOneWidget);
      expect(_fieldText(tester, _editField), '내가 쓴 댓글');
    });
  });

  group('삭제', () {
    Future<void> tapDelete(WidgetTester tester, int id) async {
      await tester.tap(_btn(id, '삭제'));
      await tester.pumpAndSettle();
    }

    Future<void> confirmDelete(WidgetTester tester) async {
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
    }

    _test('[삭제] → 확인 창 "이 댓글을 삭제할까요?" → 취소하면 요청 없이 그대로', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      expect(find.text('이 댓글을 삭제할까요?'), findsOneWidget);
      await tester.tap(_dialogBtn('취소'));
      await tester.pumpAndSettle();
      expect(adapter.count('DELETE', '/comments/1'), 0);
      expect(_row(1), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
    });

    _test('삭제 성공 → 행이 사라지고 헤더 -1', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/1', FakeReply(204));
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await confirmDelete(tester);
      expect(adapter.count('DELETE', '/comments/1'), 1);
      expect(_row(1), findsNothing);
      expect(find.text('내가 쓴 댓글'), findsNothing);
      expect(find.text('댓글 2'), findsOneWidget);
    });

    _test('관리자는 남의 댓글도 삭제한다', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/2', FakeReply(204));
      await _open(tester, adapter, user: _user(role: 'ADMIN', id: 9));
      await tapDelete(tester, 2);
      await confirmDelete(tester);
      expect(_row(2), findsNothing);
      expect(find.text('댓글 2'), findsOneWidget);
    });

    _test('삭제 403: 내 댓글이면 "새로고침 후 다시 시도" 안내 (목록 유지)', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/1', FakeReply(403));
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await confirmDelete(tester);
      expect(find.text('삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요'), findsOneWidget);
      expect(_row(1), findsOneWidget);
    });

    _test('삭제 403: 남의 댓글(관리자 화면)이면 "삭제할 권한이 없습니다"', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/2', FakeReply(403));
      await _open(tester, adapter, user: _user(role: 'ADMIN', id: 9));
      await tapDelete(tester, 2);
      await confirmDelete(tester);
      expect(find.text('삭제할 권한이 없습니다'), findsOneWidget);
      expect(_row(2), findsOneWidget);
    });

    _test('삭제 404 → "이미 삭제된 댓글입니다" + 목록에서 제거', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/1', FakeReply(404));
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await confirmDelete(tester);
      expect(find.text('이미 삭제된 댓글입니다'), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(find.text('댓글 2'), findsOneWidget);
    });

    _test('삭제 500 → "삭제하지 못했습니다" (목록 유지)', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/1', FakeReply(500));
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await confirmDelete(tester);
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      expect(_row(1), findsOneWidget);
      expect(_enabled(tester, _btn(1, '삭제')), isTrue);
    });

    _test('삭제 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('DELETE', '/comments/1', FakeReply.networkError());
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await confirmDelete(tester);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
    });

    _test('삭제 401(재발급도 401) → "다시 로그인해 주세요", 일반 실패 문구 없음', (tester) async {
      final adapter = _adapter()
        ..on('DELETE', '/comments/1', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await _open(tester, adapter, user: _user());
      await tapDelete(tester, 1);
      await tester.tap(_dialogBtn('삭제'));
      await _waitRefresh(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing);
      expect(_reloginInSection, findsOneWidget);
    });
  });

  group('busy 공유', () {
    // 내 댓글이 둘(1, 4)인 목록
    final two = [_c(1, '첫 내 댓글', authorId: 5), _c(2, '남의 댓글', authorId: 6), _c(4, '둘째 내 댓글', authorId: 5)];

    _test('삭제 중에는 다른 댓글의 수정·삭제 버튼도 막히고, 끝나면 풀린다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(comments: FakeReply(200, two))..on('DELETE', '/comments/1', FakeReply(204, null, gate));
      await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pump();
      expect(_enabled(tester, _btn(4, '수정')), isFalse);
      expect(_enabled(tester, _btn(4, '삭제')), isFalse);
      expect(_enabled(tester, _btn(1, '수정')), isFalse);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_row(1), findsNothing);
      expect(_enabled(tester, _btn(4, '수정')), isTrue);
      expect(_enabled(tester, _btn(4, '삭제')), isTrue);
    });

    _test('저장 중에는 다른 댓글의 수정·삭제 버튼이 막힌다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter(comments: FakeReply(200, two))
        ..on('PUT', '/comments/1', FakeReply(200, _c(1, '고침', authorId: 5), gate));
      await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      await tester.tap(_save);
      await tester.pump();
      expect(_enabled(tester, _btn(4, '수정')), isFalse);
      expect(_enabled(tester, _btn(4, '삭제')), isFalse);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_enabled(tester, _btn(4, '수정')), isTrue);
    });
  });

  group('댓글 신고', () {
    final openReport = find.widgetWithText(OutlinedButton, '신고 보내기');
    const reasons = [
      {'code': 'SPAM', 'name': '광고·스팸'},
    ];

    Future<void> fillAndSend(WidgetTester tester) async {
      await tester.tap(_btn(2, '신고'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('report-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('광고·스팸').last);
      await tester.pumpAndSettle();
      await tester.tap(openReport);
    }

    _test('남의 댓글의 [신고]는 "댓글 신고" 이름이고, 보내면 /comments/2/reports 로 간다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/comments/2/reports', FakeReply(201));
      final bodies = _recordBodies(adapter);
      await _open(tester, adapter, user: _user());
      expect(find.bySemanticsLabel('댓글 신고'), findsWidgets);
      await fillAndSend(tester);
      await tester.pumpAndSettle();
      expect(bodies.last, {'reason': 'SPAM'});
      expect(adapter.count('POST', '/comments/2/reports'), 1);
      expect(find.text('신고가 접수됐습니다'), findsOneWidget);
      handle.dispose();
    });

    _test('label이 "댓글"이라 409는 "이미 신고한 댓글입니다"',(tester) async {
      final adapter = _adapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/comments/2/reports', FakeReply(409));
      await _open(tester, adapter, user: _user());
      await fillAndSend(tester);
      await tester.pumpAndSettle();
      expect(find.text('이미 신고한 댓글입니다'), findsOneWidget);
    });

    _test('신고 401(재발급도 401) → 섹션이 "다시 로그인해 주세요" 안내를 띄운다', (tester) async {
      final adapter = _adapter()
        ..on('GET', '/report-reasons', FakeReply(200, reasons))
        ..on('POST', '/comments/2/reports', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      adapter.on('DELETE', '/comments/1', FakeReply(500));
      await _open(tester, adapter, user: _user());
      // 먼저 삭제 실패 문구를 띄워 둔다
      await tester.tap(_btn(1, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);

      await fillAndSend(tester);
      await _waitRefresh(tester);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('신고하지 못했습니다'), findsNothing);
      expect(_reloginInSection, findsOneWidget);
      expect(find.text('삭제하지 못했습니다'), findsNothing); // 앞서 뜬 실패 문구는 지워진다
    });
  });

  // _posting(등록 중)과 _busy(저장·삭제 중)는 서로 막지 않는다
  group('등록 중/저장 중 분리', () {
    _test('등록 중에도 내 댓글의 [수정]·[삭제]는 눌린다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', '/posts/7/comments', FakeReply(201, _c(10, '새', authorId: 5), gate));
      await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '새');
      await tester.tap(_register);
      await tester.pump();
      expect(find.text('등록 중...'), findsOneWidget);
      expect(_enabled(tester, _btn(1, '수정')), isTrue);
      expect(_enabled(tester, _btn(1, '삭제')), isTrue);
      gate.complete();
      await tester.pumpAndSettle();
    });

    _test('저장 중에도 [등록]은 눌린다', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('PUT', '/comments/1', FakeReply(200, _c(1, '고침', authorId: 5), gate));
      await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      await tester.tap(_save);
      await tester.pump();
      expect(find.text('저장 중...'), findsOneWidget);
      expect(tester.widget<FilledButton>(_register).onPressed, isNotNull);
      expect(find.text('등록 중...'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
    });
  });

  // 내 댓글이 둘(1, 4)
  group('수정 중 상태 전환', () {
    final two = [_c(1, '첫 내 댓글', authorId: 5), _c(2, '남의 댓글', authorId: 6), _c(4, '둘째 내 댓글', authorId: 5)];

    _test('1번 수정 중 다른 댓글 [수정] → 입력칸은 하나, 값은 새 댓글 내용, 1번은 원래 글자', (tester) async {
      await _open(tester, _adapter(comments: FakeReply(200, two)), user: _user());
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      await tester.enterText(_editField, '고치던 중');
      await tester.tap(_btn(4, '수정'));
      await tester.pumpAndSettle();
      expect(_editField, findsOneWidget);
      expect(find.descendant(of: _row(4), matching: _editField), findsOneWidget);
      expect(_fieldText(tester, _editField), '둘째 내 댓글');
      expect(find.text('첫 내 댓글'), findsOneWidget); // 1번은 원래 글자로 돌아온다
      expect(_btn(1, '수정'), findsOneWidget);
    });

    _test('1번 수정 중 다른 댓글 삭제 성공 → 1번 편집·입력값 유지', (tester) async {
      final adapter = _adapter(comments: FakeReply(200, two))..on('DELETE', '/comments/4', FakeReply(204));
      await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      await tester.enterText(_editField, '고치던 중');
      await tester.tap(_btn(4, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
      expect(_row(4), findsNothing);
      expect(find.descendant(of: _row(1), matching: _editField), findsOneWidget);
      expect(_fieldText(tester, _editField), '고치던 중');
    });

    _test('실패 문구가 떠 있을 때 [수정]을 누르면 문구가 지워진다', (tester) async {
      final adapter = _adapter(comments: FakeReply(200, two))..on('DELETE', '/comments/4', FakeReply(500));
      await _open(tester, adapter, user: _user());
      await tester.tap(_btn(4, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      expect(find.text('삭제하지 못했습니다'), findsNothing);
    });
  });

  // 응답이 오기 전에 화면을 떠나도 예외 없이 응답을 버린다
  group('응답 전 화면 떠나기', () {
    FakeAdapter homeAdapter({FakeReply? comments}) => _adapter(comments: comments)
      ..on('GET', '/schools', FakeReply(200, [_seoul]))
      ..on('GET', '/categories', FakeReply(200, [{'code': 'CHAT', 'name': '잡담'}]))
      ..on('GET', '/posts', FakeReply(200, []));

    Future<void> leaveThenRelease(WidgetTester tester, TestApp app, Completer<void> gate) async {
      app.router.go('/');
      await tester.pumpAndSettle();
      gate.complete();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }

    _test('목록 GET 중에 떠나기', (tester) async {
      final gate = Completer<void>();
      final app = await _open(tester, homeAdapter(comments: FakeReply(200, _three(), gate)), user: _user());
      await leaveThenRelease(tester, app, gate);
    });

    _test('등록 POST 중에 떠나기', (tester) async {
      final gate = Completer<void>();
      final adapter = homeAdapter()..on('POST', '/posts/7/comments', FakeReply(201, _c(10, '새', authorId: 5), gate));
      final app = await _open(tester, adapter, user: _user());
      await tester.enterText(_newField, '새');
      await tester.tap(_register);
      await tester.pump();
      await leaveThenRelease(tester, app, gate);
    });

    _test('저장 PUT 중에 떠나기', (tester) async {
      final gate = Completer<void>();
      final adapter = homeAdapter()..on('PUT', '/comments/1', FakeReply(200, _c(1, '고침', authorId: 5), gate));
      final app = await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '수정'));
      await tester.pumpAndSettle();
      await tester.tap(_save);
      await tester.pump();
      await leaveThenRelease(tester, app, gate);
    });

    _test('삭제 DELETE 중에 떠나기', (tester) async {
      final gate = Completer<void>();
      final adapter = homeAdapter()..on('DELETE', '/comments/1', FakeReply(204, null, gate));
      final app = await _open(tester, adapter, user: _user());
      await tester.tap(_btn(1, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(_dialogBtn('삭제'));
      await tester.pump();
      await leaveThenRelease(tester, app, gate);
    });
  });
}
