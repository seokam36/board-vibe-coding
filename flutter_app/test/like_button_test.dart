// S16-f1 공감 버튼 테스트: React LikeButton과 같은 동작(비로그인, likes/me, 응답 숫자만 반영, 비활성 조건,
// 실패 문구, 시맨틱)을 글 상세 화면 안에서 본다.
import 'dart:async';
import 'dart:ui' show Tristate;

import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';
import 'support/screen.dart';

const _seoul = {'id': 1, 'name': '서울대'};
const _busan = {'id': 2, 'name': '부산대'};

// 서울대 인증 회원(id 5). 기본 글은 서울대 글(작성자 id 1)이라 공감할 수 있다
Map<String, dynamic> _user({Map<String, dynamic>? school = _seoul, int id = 5}) => userJson(id: id, school: school);

// likesMe: false면 "내가 눌렀는지" 응답을 준비하지 않는다 (테스트가 직접 정할 때)
FakeAdapter _adapter({bool liked = false, bool likesMe = true, int likeCount = 3, Map<String, dynamic>? school = _seoul}) {
  final a = FakeAdapter()
    ..on('GET', '/posts/7', FakeReply(200, postJson(likeCount: likeCount, school: school)))
    ..on('GET', '/posts/7/comments', FakeReply(200, [])); // S16-f2: 상세가 댓글도 불러온다
  if (likesMe) a.on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': liked}));
  return a;
}

Future<TestApp> _open(WidgetTester tester, FakeAdapter adapter, {Map<String, dynamic>? user}) async {
  useTallScreen(tester);
  final app = await pumpApp(tester, location: '/posts/7', user: user, adapter: adapter);
  await tester.pump(const Duration(milliseconds: 50)); // 글이 뜬 뒤 시작되는 likes/me 요청의 타이머를 끝낸다
  await tester.pumpAndSettle();
  return app;
}

String _uri(TestApp app) => app.router.routerDelegate.currentConfiguration.uri.toString();

// 버튼 위젯 (글자가 "공감 N"인 OutlinedButton)
Finder get _button => find.ancestor(of: find.textContaining('공감 '), matching: find.byType(OutlinedButton));
bool _enabled(WidgetTester tester) => tester.widget<OutlinedButton>(_button).onPressed != null;

Future<void> _tapLike(WidgetTester tester) async {
  await tester.tap(_button);
  await tester.pumpAndSettle();
}

void main() {
  group('비로그인', () {
    testWidgets('누르면 /login 으로 가고 요청은 없다 (likes/me도 안 묻는다)', (tester) async {
      final adapter = _adapter();
      final app = await _open(tester, adapter);
      expect(find.text('🤍 공감 3'), findsOneWidget);
      expect(_enabled(tester), isTrue);
      await _tapLike(tester);
      expect(_uri(app), '/login');
      expect(adapter.count('GET', '/posts/7/likes/me'), 0);
      expect(adapter.count('POST', '/posts/7/likes'), 0);
    });
  });

  group('내가 눌렀는지(likes/me)', () {
    testWidgets('로그인하면 한 번 물어보고, liked:false면 🤍', (tester) async {
      final adapter = _adapter();
      await _open(tester, adapter, user: _user());
      expect(adapter.count('GET', '/posts/7/likes/me'), 1);
      expect(find.text('🤍 공감 3'), findsOneWidget);
    });

    testWidgets('liked:true면 ❤️ + 눌린 모양(청자 배경)', (tester) async {
      await _open(tester, _adapter(liked: true), user: _user());
      expect(find.text('❤️ 공감 3'), findsOneWidget);
      final style = tester.widget<OutlinedButton>(_button).style!;
      expect(style.backgroundColor!.resolve({}), const Color(0xFF1E3A33)); // celadonSoft
    });

    testWidgets('안 눌린 상태는 눌린 배경색이 없다', (tester) async {
      await _open(tester, _adapter(), user: _user());
      final bg = tester.widget<OutlinedButton>(_button).style!.backgroundColor;
      expect(bg, isNull);
    });

    testWidgets('likes/me가 실패해도 무시: 🤍로 두고 버튼은 쓸 수 있다, 안내 문구 없음', (tester) async {
      final adapter = _adapter(likesMe: false)..on('GET', '/posts/7/likes/me', FakeReply(500));
      await _open(tester, adapter, user: _user());
      expect(find.text('🤍 공감 3'), findsOneWidget);
      expect(_enabled(tester), isTrue);
      expect(find.byType(Notice), findsNothing);
    });

    testWidgets('계정이 바뀌면(로그아웃) 눌린 상태는 사라진다', (tester) async {
      final app = await _open(tester, _adapter(liked: true), user: _user());
      expect(find.text('❤️ 공감 3'), findsOneWidget);
      app.auth.clearUser();
      await tester.pumpAndSettle();
      expect(find.text('🤍 공감 3'), findsOneWidget);
    });

    testWidgets('다른 계정으로 로그인하면 그 계정 기준으로 다시 묻고, 이전 계정의 눌림은 안 이어진다', (tester) async {
      final adapter = _adapter(liked: true)..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}));
      final app = await _open(tester, adapter, user: _user());
      expect(find.text('❤️ 공감 3'), findsOneWidget);
      app.auth.updateUser(AuthUser.fromJson(_user(id: 6)));
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pumpAndSettle();
      expect(adapter.count('GET', '/posts/7/likes/me'), 2);
      expect(find.text('🤍 공감 3'), findsOneWidget);
    });
  });

  group('누르기', () {
    testWidgets('응답 숫자로만 바뀐다: 응답 전엔 숫자·모양 그대로(낙관적 갱신 없음), 요청 중엔 비활성·한 번만', (tester) async {
      final gate = Completer<void>();
      final adapter = _adapter()..on('POST', '/posts/7/likes', FakeReply(201, {'likeCount': 4, 'liked': true}, gate));
      await _open(tester, adapter, user: _user());
      await tester.tap(_button);
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.text('🤍 공감 3'), findsOneWidget); // 아직 그대로
      expect(_enabled(tester), isFalse);
      expect(adapter.count('POST', '/posts/7/likes'), 1);

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('❤️ 공감 4'), findsOneWidget);
      expect(_enabled(tester), isTrue);
    });

    testWidgets('이미 눌렀으면 취소(DELETE), 응답 숫자로 🤍', (tester) async {
      final adapter = _adapter(liked: true)..on('DELETE', '/posts/7/likes', FakeReply(200, {'likeCount': 2, 'liked': false}));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(adapter.count('DELETE', '/posts/7/likes'), 1);
      expect(adapter.count('POST', '/posts/7/likes'), 0);
      expect(find.text('🤍 공감 2'), findsOneWidget);
    });

    testWidgets('응답 숫자가 내 기대와 달라도(다른 사람이 눌러 7) 응답 값을 그대로 보여 준다', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/likes', FakeReply(201, {'likeCount': 7, 'liked': true}));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(find.text('❤️ 공감 7'), findsOneWidget);
    });

    testWidgets('실패한 뒤 다시 누르면 이전 안내 문구가 지워진다', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts/7/likes', FakeReply(500))
        ..on('POST', '/posts/7/likes', FakeReply(201, {'likeCount': 4, 'liked': true}));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(find.text('처리하지 못했습니다'), findsOneWidget);
      await _tapLike(tester);
      expect(find.text('처리하지 못했습니다'), findsNothing);
    });
  });

  group('비활성 조건: busy || (!canLike && !liked)', () {
    testWidgets('다른 학교 글 + 공감 안 함 → 비활성 (누를 수 없다)', (tester) async {
      final adapter = _adapter(school: _busan);
      await _open(tester, adapter, user: _user());
      expect(_enabled(tester), isFalse);
      await tester.tap(_button, warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(adapter.count('POST', '/posts/7/likes'), 0);
    });

    testWidgets('다른 학교 글 + 이미 공감함 → 활성 (취소는 가능)', (tester) async {
      final adapter = _adapter(liked: true, school: _busan)
        ..on('DELETE', '/posts/7/likes', FakeReply(200, {'likeCount': 2, 'liked': false}));
      await _open(tester, adapter, user: _user());
      expect(_enabled(tester), isTrue);
      await _tapLike(tester);
      expect(find.text('🤍 공감 2'), findsOneWidget);
      expect(_enabled(tester), isFalse); // 취소했으니 이제는 공감할 수 없다
    });

    testWidgets('학교 미인증 회원 + 공감 안 함 → 비활성', (tester) async {
      await _open(tester, _adapter(), user: _user(school: null));
      expect(_enabled(tester), isFalse);
    });

    testWidgets('학교 없는 글 + 공감 안 함 → 비활성', (tester) async {
      await _open(tester, _adapter(school: null), user: _user());
      expect(_enabled(tester), isFalse);
    });
  });

  group('실패 문구', () {
    // (설명, 눌려 있었는지, 상태, 기대 문구, 이후 글자)
    final cases = <(String, bool, int, String, String)>[
      ('POST 409 → 상태를 "공감함"으로 맞추고 이미 공감 안내', false, 409, ErrorMessages.postAlreadyLiked, '❤️ 공감 3'),
      ('POST 404 → 글 없음 문구', false, 404, ErrorMessages.postNotFound, '🤍 공감 3'),
      ('POST 403 → 학교 인증 문구', false, 403, ErrorMessages.schoolGatePostAction, '🤍 공감 3'),
      ('POST 500 → 처리하지 못했습니다', false, 500, ErrorMessages.actionFailed, '🤍 공감 3'),
      ('POST 400 → 처리하지 못했습니다', false, 400, ErrorMessages.actionFailed, '🤍 공감 3'),
      ('DELETE 404 → "공감하지 않은 글입니다" + 상태를 🤍로 맞춤', true, 404, '공감하지 않은 글입니다', '🤍 공감 3'),
      ('DELETE 403 → 취소 실패(새로고침 안내), 학교 인증 문구가 아님', true, 403, '취소하지 못했습니다. 새로고침 후 다시 시도해 주세요', '❤️ 공감 3'),
      ('DELETE 500 → 처리하지 못했습니다', true, 500, ErrorMessages.actionFailed, '❤️ 공감 3'),
    ];
    for (final (name, wasLiked, status, message, after) in cases) {
      testWidgets(name, (tester) async {
        final adapter = _adapter(liked: wasLiked)..on(wasLiked ? 'DELETE' : 'POST', '/posts/7/likes', FakeReply(status));
        await _open(tester, adapter, user: _user());
        await _tapLike(tester);
        expect(find.text(message), findsOneWidget);
        expect(tester.widget<Notice>(find.widgetWithText(Notice, message)).kind, NoticeKind.error);
        expect(find.text(after), findsOneWidget);
        expect(find.text('다시 로그인해 주세요'), findsNothing);
      });
    }

    testWidgets('POST 네트워크 오류 → "서버에 연결할 수 없습니다"', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/likes', FakeReply.networkError());
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(find.text('🤍 공감 3'), findsOneWidget);
    });

    testWidgets('실패한 뒤에도 버튼은 다시 눌러 볼 수 있다', (tester) async {
      final adapter = _adapter()..on('POST', '/posts/7/likes', FakeReply(500));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(_enabled(tester), isTrue);
    });

    testWidgets('409 뒤에는 상태가 "공감함"이라 다음 클릭은 취소(DELETE)가 된다', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts/7/likes', FakeReply(409))
        ..on('DELETE', '/posts/7/likes', FakeReply(200, {'likeCount': 2, 'liked': false}));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      await _tapLike(tester);
      expect(adapter.count('DELETE', '/posts/7/likes'), 1);
      expect(find.text('🤍 공감 2'), findsOneWidget);
    });

    testWidgets('401(재발급도 401) → 버튼 안 "다시 로그인해 주세요" + 로그인 링크, 다른 실패 문구 없음', (tester) async {
      final adapter = _adapter()
        ..on('POST', '/posts/7/likes', FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await _open(tester, adapter, user: _user());
      await tester.tap(_button);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();

      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('처리하지 못했습니다'), findsNothing);
      expect(_uri(app), '/posts/7');
      await tester.tap(find.descendant(of: find.byType(Notice), matching: find.text('로그인')));
      await tester.pumpAndSettle();
      expect(_uri(app), '/login');
    });
  });

  group('시맨틱', () {
    testWidgets('안 눌림: 이름 "공감 (현재 3명)", 눌림 상태(toggled) 꺼짐, 버튼', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(), user: _user());
      final node = tester.getSemantics(find.bySemanticsLabel('공감 (현재 3명)'));
      final data = node.getSemanticsData();
      expect(data.label, '공감 (현재 3명)');
      expect(data.flagsCollection.isToggled, Tristate.isFalse); // 눌림 상태가 "꺼짐"으로 알려진다 (aria-pressed=false)
      expect(data.flagsCollection.isButton, isTrue);
      handle.dispose();
    });

    testWidgets('눌림: 이름 "공감 취소 (현재 3명)", toggled 켜짐', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(liked: true), user: _user());
      final data = tester.getSemantics(find.bySemanticsLabel('공감 취소 (현재 3명)')).getSemanticsData();
      expect(data.flagsCollection.isToggled, Tristate.isTrue);
      handle.dispose();
    });

    testWidgets('숫자가 응답으로 바뀌면 이름의 숫자도 바뀐다', (tester) async {
      final handle = tester.ensureSemantics();
      final adapter = _adapter()..on('POST', '/posts/7/likes', FakeReply(201, {'likeCount': 4, 'liked': true}));
      await _open(tester, adapter, user: _user());
      await _tapLike(tester);
      expect(find.bySemanticsLabel('공감 취소 (현재 4명)'), findsOneWidget);
      handle.dispose();
    });

    testWidgets('비활성일 땐 enabled 꺼짐', (tester) async {
      final handle = tester.ensureSemantics();
      await _open(tester, _adapter(school: _busan), user: _user());
      final data = tester.getSemantics(find.bySemanticsLabel('공감 (현재 3명)')).getSemanticsData();
      expect(data.flagsCollection.isEnabled, Tristate.isFalse);
      expect(data.hasAction(SemanticsAction.tap), isFalse);
      handle.dispose();
    });
  });
}
