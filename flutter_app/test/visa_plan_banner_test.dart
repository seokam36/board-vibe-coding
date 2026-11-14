// S16-i2 비자 일정 띠(VisaPlanBanner) 테스트: React e2e(visa-banner.spec.ts)의 시나리오를 위젯 테스트로 옮겼다.
// (표시 조건, 단계별 문구, /visa-plan에서 숨김·GET 1번, 닫기 기록, 즉시 반영, 로그아웃·다른 회원, 모르는 stage, 저장소 예외.)
// 시계는 서울 2026-11-10으로 고정한다 (BoardApp의 now). 저장소는 테스트마다 새 메모리 저장소로 바꿔 끼운다.
import 'package:board_web/app.dart';
import 'package:board_web/auth/auth_user.dart';
import 'package:board_web/common/local_store.dart';
import 'package:board_web/router.dart' show normalizePath;
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/visa/visa_banner.dart';
import 'package:board_web/visa/visa_plan.dart';
import 'package:board_web/visa/visa_plan_store.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/post_json.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';
import 'support/visa_plan_json.dart';

const _planUrl = '/users/me/visa-plan';

const _checkText = '체류 만료 D-28 — 신청 방법을 확인하세요';

// 단계별 일정 (할 일 목록은 띠와 상관없어 비워 둔다)
Map<String, dynamic> _plan(String expiryDate, int daysLeft, String? stage) => {
  'expiryDate': expiryDate,
  'daysLeft': daysLeft,
  'stage': stage,
  'todos': <dynamic>[],
};

Map<String, dynamic> _checkPlan() => _plan('2026-12-08', 28, 'CHECK');
Map<String, dynamic> _urgentPlan() => _plan('2026-12-08', 5, 'URGENT');

Map<String, dynamic> _verified({int id = 1}) => userJson(id: id, school: visaSeoul);

// 메모리 저장소 (data로 안을 들여다본다)
class _MemStore implements LocalStore {
  final Map<String, String> data = {};

  @override
  String? getItem(String key) => data[key];

  @override
  void setItem(String key, String value) => data[key] = value;
}

// 읽기·쓰기가 모두 예외를 던지는 저장소 (시크릿 모드·차단 브라우저)
class _BlockedStore implements LocalStore {
  @override
  String? getItem(String key) => throw StateError('blocked');

  @override
  void setItem(String key, String value) => throw StateError('blocked');
}

final _banner = find.byKey(const ValueKey('visa-banner'));
final _bannerText = find.byKey(const ValueKey('visa-banner-text'));
final _closeButton = find.byKey(const ValueKey('visa-banner-close'));
final _errorNotices = find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.error);

void main() {
  late LocalStore original;
  late _MemStore store;

  setUp(() {
    original = localStore;
    store = _MemStore();
    localStore = store;
  });
  tearDown(() => localStore = original);

  // 앱을 띄우는 testWidgets (일정 화면의 달력·폼이 길어서 화면을 길게 한다)
  void test(String name, Future<void> Function(WidgetTester tester) body) {
    testWidgets(name, (tester) async {
      useTallScreen(tester);
      await body(tester);
    });
  }

  // 시계를 고정해서 앱을 띄운다. user가 null이면 비로그인
  Future<TestApp> pump(
    WidgetTester tester, {
    String location = '/',
    Map<String, dynamic>? user,
    FakeAdapter? adapter,
  }) async {
    // 글 목록 화면(홈)이 부르는 응답을 넉넉히 줄 세워 둔다 (화면을 오갈 때마다 다시 부르고, 안 쓴 건 그냥 남는다)
    final fake = adapter ?? FakeAdapter();
    for (var i = 0; i < 8; i++) {
      fake
        ..on('GET', '/schools', FakeReply(200, visaSchools))
        ..on('GET', '/categories', FakeReply(200, []))
        ..on('GET', '/posts', FakeReply(200, []));
    }
    final auth = LoadedAuthState(
      adapter: fake,
      user: user,
      currentPath: () => normalizePath(routerOf(tester).routerDelegate.currentConfiguration.uri.path),
    );
    addTearDown(auth.dispose);
    await tester.pumpWidget(
      BoardApp(authState: auth, initialLocation: location, now: () => DateTime.utc(2026, 11, 10, 3)),
    );
    await tester.pumpAndSettle();
    return TestApp(auth, routerOf(tester));
  }

  // 인증 회원 + GET 응답 하나로 띄운다
  Future<TestApp> pumpWith(
    WidgetTester tester,
    Map<String, dynamic> body, {
    String location = '/',
    FakeAdapter? adapter,
  }) {
    final a = adapter ?? FakeAdapter();
    a.on('GET', _planUrl, FakeReply(200, body));
    return pump(tester, user: _verified(), adapter: a, location: location);
  }

  String bannerText(WidgetTester tester) => tester.widget<Text>(_bannerText).data!;

  Future<void> closeBanner(WidgetTester tester) async {
    await tester.tap(_closeButton);
    await tester.pumpAndSettle();
  }

  // 앱을 껐다 켠다(새로고침): 저장소는 그대로, 보관소·화면 상태는 새로 만든다
  Future<TestApp> reload(WidgetTester tester, Map<String, dynamic> body, {String location = '/'}) async {
    await tester.pumpWidget(const SizedBox());
    return pumpWith(tester, body, location: location);
  }

  group('언제 보이나', () {
    test('30일 이내 일정이면 글 목록 위(상단바 아래)에 띠가 보이고 링크는 /visa-plan', (tester) async {
      final app = await pumpWith(tester, _checkPlan());
      expect(_banner, findsOneWidget);
      expect(bannerText(tester), _checkText);
      expect(find.text('비자 일정 보기'), findsOneWidget);
      // 상단바 바로 아래에 놓인다 (상단바의 "로그아웃" 줄보다 아래, 본문보다 위)
      expect(tester.getTopLeft(_banner).dy, greaterThanOrEqualTo(tester.getBottomLeft(find.text('로그아웃')).dy));
      expect(tester.getTopLeft(_banner).dy, lessThan(tester.getTopLeft(find.text('전체 게시판')).dy));
      // 띠는 에러가 아니므로 에러 안내(alert)가 없다
      expect(_errorNotices, findsNothing);
      // 링크를 누르면 /visa-plan
      await tester.tap(find.text('비자 일정 보기'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
    });

    test('모양: 안내색 배경, 역할 status(alert 아님), 닫기는 글자 ×·40px·시맨틱 "띠 닫기"', (tester) async {
      final handle = tester.ensureSemantics();
      await pumpWith(tester, _checkPlan());
      expect((tester.widget<Container>(_banner)).color, AppColors.infoSoft);
      final data = tester.getSemantics(_banner).getSemanticsData();
      expect(data.role, SemanticsRole.status);
      expect(find.text('×'), findsOneWidget);
      expect(tester.getSize(find.descendant(of: _closeButton, matching: find.byType(SizedBox)).first), const Size(40, 40));
      final closeData = tester.getSemantics(_closeButton).getSemanticsData();
      expect(closeData.label, '띠 닫기');
      expect(closeData.flagsCollection.isButton, isTrue);
      handle.dispose();
    });

    test('단계별 문구가 맞다 (120일 이내·7일 이내·당일·지남)', (tester) async {
      final cases = <(Map<String, dynamic>, String)>[
        (planJson(), '체류 만료 D-111 — 연장·자격 변경 준비를 확인하세요'),
        (_urgentPlan(), '체류 만료 D-5 — 체류 상태를 확인하세요'),
        (dDayPlanJson(), '체류 만료 D-Day — 체류 상태를 확인하세요'),
        (expiredPlanJson(), '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요'),
      ];
      for (final (body, text) in cases) {
        await tester.pumpWidget(const SizedBox()); // 새로 열면 보관소가 다시 부른다
        await pumpWith(tester, body);
        expect(bannerText(tester), text);
      }
    });

    test('121일 이상 남아 단계가 없으면 띠가 없다', (tester) async {
      await pumpWith(tester, farPlanJson());
      expect(find.text('전체 게시판'), findsOneWidget);
      expect(_banner, findsNothing);
    });

    test('일정이 없으면 띠가 없다', (tester) async {
      await pumpWith(tester, emptyPlanJson);
      expect(_banner, findsNothing);
    });

    test('일정 불러오기가 500이면 띠도 에러 문구도 없다', (tester) async {
      final adapter = FakeAdapter()..on('GET', _planUrl, FakeReply(500));
      await pump(tester, user: _verified(), adapter: adapter);
      expect(find.text('전체 게시판'), findsOneWidget);
      expect(_banner, findsNothing);
      expect(_errorNotices, findsNothing);
    });

    test('미인증 회원은 띠가 없고 일정을 부르지 않는다', (tester) async {
      final app = await pump(tester, user: userJson());
      expect(_banner, findsNothing);
      expect(app.adapter.count('GET', _planUrl), 0);
    });

    test('비로그인이면 띠가 없고 일정을 부르지 않는다', (tester) async {
      final app = await pump(tester);
      expect(find.text('로그인'), findsWidgets);
      expect(_banner, findsNothing);
      expect(app.adapter.count('GET', _planUrl), 0);
    });
  });

  group('어디서 보이나 + 몇 번 부르나', () {
    test('/visa-plan에서는 숨고, 다른 화면으로 오면 다시 보이며, 일정은 한 번만 부른다', (tester) async {
      final app = await pumpWith(tester, _checkPlan());
      expect(_banner, findsOneWidget);
      // 띠 링크로 일정 화면 → 띠 숨김
      await tester.tap(find.text('비자 일정 보기'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
      expect(find.byKey(const ValueKey('visa-dday')), findsOneWidget);
      expect(_banner, findsNothing);
      // 글 목록으로 돌아오면 다시 보임
      app.router.go('/');
      await tester.pumpAndSettle();
      expect(_banner, findsOneWidget);
      // 화면 이동(새로고침 없이)으로는 다시 부르지 않는다
      expect(app.adapter.count('GET', _planUrl), 1);
    });

    test('/visa-plan으로 바로 들어가도 띠는 없다 (경로는 대소문자·끝 / 무시)', (tester) async {
      await pumpWith(tester, _checkPlan(), location: '/Visa-Plan/');
      expect(find.byKey(const ValueKey('visa-dday')), findsOneWidget);
      expect(_banner, findsNothing);
    });

    test('글 상세 화면에도 띠가 보인다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', '/posts/7', FakeReply(200, postJson()))
        ..on('GET', '/posts/7/comments', FakeReply(200, []))
        ..on('GET', '/posts/7/likes/me', FakeReply(200, {'liked': false}));
      await pumpWith(tester, _checkPlan(), location: '/posts/7', adapter: adapter);
      // 글이 뜬 뒤에야 공감 버튼이 요청을 보낸다. 그 요청의 타이머가 끝나도록 한 번 더 흘려 보낸다
      await tester.pump(const Duration(milliseconds: 50));
      await tester.pumpAndSettle();
      expect(find.text('제목입니다'), findsOneWidget);
      expect(bannerText(tester), _checkText);
    });
  });

  group('닫기', () {
    test('×로 닫으면 사라지고, 새로고침해도 같은 단계에서는 안 뜬다', (tester) async {
      await pumpWith(tester, _checkPlan());
      await closeBanner(tester);
      expect(_banner, findsNothing);
      await reload(tester, _checkPlan());
      expect(find.text('전체 게시판'), findsOneWidget);
      expect(_banner, findsNothing);
    });

    test('닫은 뒤 단계가 바뀌면(30일 → 7일 이내) 다시 뜬다', (tester) async {
      await pumpWith(tester, _checkPlan());
      await closeBanner(tester);
      await reload(tester, _urgentPlan()); // 같은 만료일인데 7일 이내 단계
      expect(bannerText(tester), '체류 만료 D-5 — 체류 상태를 확인하세요');
    });

    test('닫은 뒤 같은 단계라도 만료일이 바뀌면 다시 뜬다', (tester) async {
      await pumpWith(tester, _checkPlan());
      await closeBanner(tester);
      await reload(tester, _plan('2026-12-09', 29, 'CHECK')); // 만료일만 하루 늦췄다
      expect(bannerText(tester), '체류 만료 D-29 — 신청 방법을 확인하세요');
    });

    test('7일 이내 띠도 닫을 수 있다', (tester) async {
      await pumpWith(tester, _urgentPlan());
      await closeBanner(tester);
      expect(_banner, findsNothing);
    });

    test('저장소에는 회원당 키 하나만 남고, 키·값 어디에도 만료일이 없다', (tester) async {
      await pumpWith(tester, _checkPlan());
      await closeBanner(tester);
      await reload(tester, _urgentPlan());
      await closeBanner(tester); // 두 번 닫아도 기록은 하나
      expect(store.data.length, 1);
      final entry = store.data.entries.single;
      expect(entry.key, 'visaBannerClosed:1');
      expect(entry.value, dismissValue('URGENT', '2026-12-08'));
      expect('${entry.key}=${entry.value}', isNot(contains('2026')));
    });

    test('저장소가 막혀도 닫으면 사라지고, 화면을 옮겨 다녀도 이번 실행에서는 다시 안 뜬다', (tester) async {
      localStore = _BlockedStore();
      final app = await pumpWith(tester, _checkPlan());
      expect(_banner, findsOneWidget); // 읽기 예외 = 안 닫힘
      await closeBanner(tester);
      expect(_banner, findsNothing);
      expect(tester.takeException(), isNull);
      app.router.go('/visa-plan');
      await tester.pumpAndSettle();
      app.router.go('/');
      await tester.pumpAndSettle();
      expect(_banner, findsNothing);
    });
  });

  group('보관소를 같이 쓰니 바로 반영', () {
    test('일정 화면에서 저장하면 새로고침 없이 띠가 생기고, 삭제하면 사라진다 (GET은 1번)', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', _planUrl, FakeReply(200, d15PlanJson()))
        ..on('DELETE', _planUrl, FakeReply(204));
      final app = await pumpWith(tester, emptyPlanJson, location: '/visa-plan', adapter: adapter);
      expect(find.text('등록된 일정 없음'), findsOneWidget);

      // 달력에서 11월 25일을 골라 저장
      await tester.tap(find.byKey(const ValueKey('visa-expiry-field')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('25'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, '저장'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('visa-dday')), findsOneWidget);

      // 글 목록으로 가면 저장한 일정으로 띠가 보인다 (다시 부르지 않고 보관소 값으로)
      app.router.go('/');
      await tester.pumpAndSettle();
      expect(bannerText(tester), '체류 만료 D-15 — 신청 방법을 확인하세요');

      // 다시 일정 화면에서 삭제 → 글 목록에 띠 없음
      await tester.tap(find.text('비자 일정 보기'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(OutlinedButton, '삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '삭제')));
      await tester.pumpAndSettle();
      expect(find.text('등록된 일정 없음'), findsOneWidget);
      app.router.go('/');
      await tester.pumpAndSettle();
      expect(find.text('전체 게시판'), findsOneWidget);
      expect(_banner, findsNothing);
      expect(adapter.count('GET', _planUrl), 1);
    });

    test('보관소 값이 바뀌면(setPlan) 그 자리에서 띠 문구가 바뀐다', (tester) async {
      await pumpWith(tester, _checkPlan());
      final plans = tester.element(_banner).read<VisaPlanStore>();
      plans.setPlan(VisaPlan.fromJson(_urgentPlan()));
      await tester.pumpAndSettle();
      expect(bannerText(tester), '체류 만료 D-5 — 체류 상태를 확인하세요');
      plans.setPlan(VisaPlan.empty);
      await tester.pumpAndSettle();
      expect(_banner, findsNothing);
    });
  });

  group('로그인 상태가 바뀔 때', () {
    test('로그아웃하면 띠가 사라진다', (tester) async {
      final adapter = FakeAdapter()..on('POST', '/auth/logout', FakeReply(200));
      await pumpWith(tester, _checkPlan(), adapter: adapter);
      expect(_banner, findsOneWidget);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(find.text('로그인'), findsWidgets);
      expect(_banner, findsNothing);
    });

    test('로그인 화면에서 로그인한 직후 띠가 뜨고 일정은 한 번만 부른다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/auth/login', FakeReply(200, _verified()))
        ..on('GET', _planUrl, FakeReply(200, _checkPlan()));
      final app = await pump(tester, location: '/login', adapter: adapter);
      await tester.enterText(find.widgetWithText(TextField, '아이디'), 'user01');
      await tester.enterText(find.widgetWithText(TextField, '비밀번호'), 'pw1234');
      await tester.tap(find.widgetWithText(FilledButton, '로그인'));
      await tester.pumpAndSettle();
      expect(bannerText(tester), _checkText);
      expect(adapter.count('GET', _planUrl), 1);
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/');
    });

    test('학교 인증을 마치면(미인증 → 인증) 그때 일정을 불러 띠가 뜬다', (tester) async {
      final adapter = FakeAdapter()..on('GET', _planUrl, FakeReply(200, _checkPlan()));
      final app = await pump(tester, user: userJson(), adapter: adapter);
      expect(_banner, findsNothing);
      expect(adapter.count('GET', _planUrl), 0); // 인증 전에는 부르지 않는다
      app.auth.updateUser(AuthUser.fromJson(_verified()));
      await tester.pumpAndSettle();
      expect(bannerText(tester), _checkText);
      expect(adapter.count('GET', _planUrl), 1);
    });

    test('앞 회원이 닫은 띠는 다른 회원으로 로그인하면 넘어오지 않는다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', _planUrl, FakeReply(200, _checkPlan()))
        ..on('POST', '/auth/logout', FakeReply(200))
        ..on('POST', '/auth/login', FakeReply(200, _verified(id: 2)))
        ..on('GET', _planUrl, FakeReply(200, _checkPlan()));
      await pump(tester, user: _verified(), adapter: adapter);
      await closeBanner(tester);
      expect(_banner, findsNothing);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('로그인'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, '아이디'), 'other02');
      await tester.enterText(find.widgetWithText(TextField, '비밀번호'), 'pw1234');
      await tester.tap(find.widgetWithText(FilledButton, '로그인'));
      await tester.pumpAndSettle();
      // 같은 일정이어도 회원이 다르니 띠가 보여야 한다
      expect(bannerText(tester), _checkText);
      expect(store.data.keys, ['visaBannerClosed:1']);
    });
  });

  group('모르는 stage', () {
    test('서버가 모르는 stage(NEW_STAGE)를 보내도 글 목록은 정상이고 띠는 없으며 에러가 없다', (tester) async {
      final unknown = {...planJson(), 'stage': 'NEW_STAGE', 'daysLeft': 28};
      await pumpWith(tester, unknown);
      expect(find.text('전체 게시판'), findsOneWidget);
      expect(_banner, findsNothing);
      expect(tester.takeException(), isNull);
    });
  });
}
