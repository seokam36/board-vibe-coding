// S16-i1 비자 일정 화면(/visa-plan) 테스트: React e2e(visa-plan.spec.ts)의 시나리오를 위젯 테스트로 옮겼다.
// (게이트, 빈 상태·저장, D-n·할 일 줄, 저장 실패, 삭제, 불러오기 실패, 로그인 풀림, 진입 때 다시 불러오기.)
// 시계는 서울 2026-11-10으로 고정한다 (BoardApp의 now).
import 'dart:async';

import 'package:board_web/app.dart';
import 'package:board_web/common/external_link.dart';
import 'package:board_web/router.dart' show normalizePath;
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';
import 'support/visa_json.dart';
import 'support/visa_plan_json.dart';

const _path = '/users/me/visa-plan';

const _expiredNotice = '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요';
const _guideText = '일반 안내이며 개인 상황은 ';
const _relogin = '다시 로그인해 주세요';
const _invalid400 = '만료일을 확인해 주세요 (오늘부터 5년 이내의 날짜만 등록할 수 있어요)';
const _school403 = '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요';

// 시계 (서울 2026-11-10 낮). 자정 넘김 테스트는 이 값을 바꾼다
DateTime _clock = DateTime.utc(2026, 11, 10, 3);

Map<String, dynamic> _verified() => userJson(school: visaSeoul);

// 열려는 주소만 기록하는 가짜 열기 도구
class _FakeOpener implements ExternalLinkOpener {
  final List<String> opened = [];

  @override
  void open(String url) => opened.add(url);
}

final _field = find.byKey(const ValueKey('visa-expiry-field'));
final _dday = find.byKey(const ValueKey('visa-dday'));
final _empty = find.byKey(const ValueKey('visa-empty'));
final _saveButton = find.widgetWithText(FilledButton, '저장');
final _changeButton = find.widgetWithText(FilledButton, '만료일 변경');
final _deleteButton = find.widgetWithText(OutlinedButton, '삭제');
final _errorNotices = find.byWidgetPredicate((w) => w is Notice && w.kind == NoticeKind.error);

void main() {
  late _FakeOpener opener;
  late ExternalLinkOpener originalOpener;

  setUp(() {
    _clock = DateTime.utc(2026, 11, 10, 3);
    originalOpener = externalLinkOpener;
    opener = _FakeOpener();
    externalLinkOpener = opener;
  });
  tearDown(() => externalLinkOpener = originalOpener);

  // 폼이 길어서 화면을 길게 만든 뒤 시험하는 testWidgets
  void test(String name, Future<void> Function(WidgetTester tester) body) {
    testWidgets(name, (tester) async {
      useTallScreen(tester);
      await body(tester);
    });
  }

  // /visa-plan으로 앱을 띄운다. user가 null이면 비로그인
  Future<TestApp> pump(WidgetTester tester, {Map<String, dynamic>? user, FakeAdapter? adapter}) async {
    final auth = LoadedAuthState(
      adapter: adapter,
      user: user,
      currentPath: () => normalizePath(routerOf(tester).routerDelegate.currentConfiguration.uri.path),
    );
    addTearDown(auth.dispose);
    await tester.pumpWidget(BoardApp(authState: auth, initialLocation: '/visa-plan', now: () => _clock));
    await tester.pumpAndSettle();
    return TestApp(auth, routerOf(tester));
  }

  // 인증 회원 + GET 응답 하나
  Future<TestApp> pumpWithPlan(WidgetTester tester, Map<String, dynamic> body, {FakeAdapter? adapter}) {
    final a = adapter ?? FakeAdapter();
    a.on('GET', _path, FakeReply(200, body));
    return pump(tester, user: _verified(), adapter: a);
  }

  String fieldText(WidgetTester tester) => tester.widget<TextField>(_field).controller!.text;

  // 날짜 칸을 눌러 달력을 열고 (nextMonths번 다음 달로 넘긴 뒤) day일을 골라 [확인]
  Future<void> pickDay(WidgetTester tester, int day, {int nextMonths = 0}) async {
    await tester.tap(_field);
    await tester.pumpAndSettle();
    for (var i = 0; i < nextMonths; i++) {
      await tester.tap(find.byTooltip('다음 달'));
      await tester.pumpAndSettle();
    }
    await tester.tap(find.text('$day'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
  }

  // 2027-03-01을 고른다 (2026-11 → 4달 뒤 1일)
  Future<void> pickMarch1(WidgetTester tester) => pickDay(tester, 1, nextMonths: 4);

  // 삭제 버튼 → 확인 창의 [삭제]
  Future<void> confirmDelete(WidgetTester tester) async {
    await tester.tap(_deleteButton);
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '삭제')));
    await tester.pumpAndSettle();
  }

  // PUT 본문을 기록한다
  List<Object?> recordPutBodies(FakeAdapter adapter) {
    final bodies = <Object?>[];
    adapter.onRequestBody = (data) {
      if (data is Map && data.containsKey('expiryDate')) bodies.add(data);
    };
    return bodies;
  }

  // 할 일 줄의 글자들
  String todoDday(WidgetTester tester, int i) => tester.widget<Text>(find.byKey(ValueKey('visa-todo-dday-$i'))).data!;
  String todoDate(WidgetTester tester, int i) => tester.widget<Text>(find.byKey(ValueKey('visa-todo-date-$i'))).data!;
  String todoStatus(WidgetTester tester, int i) => tester.widget<Text>(find.byKey(ValueKey('visa-todo-status-$i'))).data!;
  Color? todoDdayColor(WidgetTester tester, int i) =>
      tester.widget<Text>(find.byKey(ValueKey('visa-todo-dday-$i'))).style!.color;
  Color? todoStatusColor(WidgetTester tester, int i) =>
      tester.widget<Text>(find.byKey(ValueKey('visa-todo-status-$i'))).style!.color;
  Color? ddayColor(WidgetTester tester) => tester.widget<Text>(_dday).style!.color;
  String ddayText(WidgetTester tester) => tester.widget<Text>(_dday).data!;

  const todoDdays = ['만료 120일 전부터', '만료 30일 전부터', '만료 7일 전부터'];

  group('학교 미인증', () {
    test('학교 인증 안내만 보이고 일정을 부르지 않는다', (tester) async {
      final app = await pump(tester, user: userJson());
      expect(find.text('비자 일정'), findsWidgets); // 제목 (상단바 링크는 인증 회원에게만)
      expect(find.text('비자 일정은 학교 인증 후에 등록할 수 있어요'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'), findsOneWidget);
      expect(_field, findsNothing);
      expect(app.adapter.count('GET', _path), 0);
    });

    test('[내 정보에서 학교 인증하기]를 누르면 /me', (tester) async {
      final app = await pump(tester, user: userJson());
      await tester.tap(find.widgetWithText(FilledButton, '내 정보에서 학교 인증하기'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/me');
    });

    test('미인증 화면에도 일반 안내와 하이코리아 링크가 있다', (tester) async {
      await pump(tester, user: userJson());
      expect(find.byType(Notice), findsOneWidget);
      expect(find.textContaining('일반 안내이며 개인 상황은', findRichText: true), findsOneWidget);
      await tester.tap(find.text('하이코리아'));
      expect(opener.opened, ['https://www.hikorea.go.kr/']);
    });
  });

  test('비로그인으로 /visa-plan에 들어가면 로그인 화면으로 간다', (tester) async {
    final app = await pump(tester);
    expect(app.router.routerDelegate.currentConfiguration.uri.path, '/login');
  });

  group('일정 없음과 저장', () {
    test('빈 안내와 입력칸이 보이고, 저장하면 PUT 1번 뒤 D-n·할 일 3줄이 보인다', (tester) async {
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(200, planJson()));
      final bodies = recordPutBodies(adapter);
      await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      expect(_empty, findsOneWidget);
      expect(find.text('등록된 일정 없음'), findsOneWidget);
      expect(find.text('체류 만료일'), findsOneWidget);
      expect(_dday, findsNothing);
      expect(_deleteButton, findsNothing); // 일정이 없으면 삭제 버튼도 없다
      expect(_saveButton, findsOneWidget);

      await pickMarch1(tester);
      expect(fieldText(tester), '2027-03-01');
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();

      expect(ddayText(tester), 'D-111');
      expect(find.text('체류 만료일 2027년 3월 1일'), findsOneWidget);
      expect(find.text('할 일'), findsOneWidget);
      for (var i = 0; i < 3; i++) {
        expect(find.byKey(ValueKey('visa-todo-$i')), findsOneWidget);
        expect(todoDday(tester, i), todoDdays[i]);
      }
      expect(find.text('지남'), findsNothing);
      // 현재 단계(PREPARE) 줄만 "지금 확인할 때", 나머지는 "n일 뒤 시작"
      expect(todoStatus(tester, 0), '지금 확인할 때');
      expect(todoStatus(tester, 1), '81일 뒤 시작');
      expect(todoStatus(tester, 2), '104일 뒤 시작');
      expect(find.text('연장 준비를 시작하세요'), findsOneWidget);
      // 요청은 PUT 한 번, 본문은 만료일 하나. 다시 GET 하지 않는다
      expect(adapter.count('PUT', _path), 1);
      expect(bodies, [
        {'expiryDate': '2027-03-01'},
      ]);
      expect(adapter.count('GET', _path), 1);
      // 일정이 생기면 버튼이 "만료일 변경", 삭제 버튼이 생긴다
      expect(_changeButton, findsOneWidget);
      expect(_deleteButton, findsOneWidget);
    });

    test('날짜를 비우고 저장하면 "만료일을 입력해 주세요"가 보이고 PUT은 나가지 않는다', (tester) async {
      final app = await pumpWithPlan(tester, emptyPlanJson);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text('만료일을 입력해 주세요'), findsOneWidget);
      expect(_errorNotices, findsOneWidget);
      expect(app.adapter.count('PUT', _path), 0);
    });

    test('일정이 있으면 버튼이 "만료일 변경"이고 칸에 현재 만료일이 채워져 있다', (tester) async {
      await pumpWithPlan(tester, planJson());
      expect(_saveButton, findsNothing);
      expect(_changeButton, findsOneWidget);
      expect(fieldText(tester), '2027-03-01'); // 현재 값이 한 번 채워진다
    });

    test('바꾼 날짜가 본문으로 나가고 응답으로 D-n이 바뀐다', (tester) async {
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(200, laterPlanJson()));
      final bodies = recordPutBodies(adapter);
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      // 일정이 있을 때 달력은 현재 값(2027-03)에서 열린다. 거기서 31일을 고른다
      await tester.tap(_field);
      await tester.pumpAndSettle();
      await tester.tap(find.text('31'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      expect(fieldText(tester), '2027-03-31');
      await tester.tap(_changeButton);
      await tester.pumpAndSettle();
      expect(ddayText(tester), 'D-141');
      expect(bodies, [
        {'expiryDate': '2027-03-31'},
      ]);
      expect(adapter.count('GET', _path), 1);
    });

    test('저장 중에는 버튼이 "저장 중..."으로 잠기고 삭제 버튼도 잠긴다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(200, laterPlanJson(), gate));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await tester.tap(_changeButton);
      await tester.pump();
      await tester.pump();
      final saving = find.widgetWithText(FilledButton, '저장 중...');
      expect(saving, findsOneWidget);
      expect(tester.widget<FilledButton>(saving).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(_deleteButton).onPressed, isNull);
      gate.complete();
      await tester.pumpAndSettle();
      expect(_changeButton, findsOneWidget);
      expect(tester.widget<FilledButton>(_changeButton).onPressed, isNotNull);
    });
  });

  group('저장 실패', () {
    test('400이면 형식·범위 문구가 보이고 입력값은 그대로다', (tester) async {
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(400));
      await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text(_invalid400), findsOneWidget);
      expect(_errorNotices, findsOneWidget);
      expect(fieldText(tester), '2027-03-01');
    });

    test('403이면 학교 인증 안내 문구가 보이고 입력값은 그대로다', (tester) async {
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(403));
      await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text(_school403), findsOneWidget);
      expect(fieldText(tester), '2027-03-01');
    });

    test('그 밖의 실패(500)는 "저장하지 못했습니다"', (tester) async {
      final adapter = FakeAdapter()..on('PUT', _path, FakeReply(500));
      await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text('저장하지 못했습니다'), findsOneWidget);
      expect(find.text('등록된 일정 없음'), findsOneWidget);
    });

    test('저장 실패 문구는 폼(판) 안에 있고, 삭제 실패 문구는 폼 밖에 있다', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', _path, FakeReply(400))
        ..on('DELETE', _path, FakeReply(500));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await tester.tap(_changeButton);
      await tester.pumpAndSettle();
      // 폼 안: 날짜 칸과 같은 판 안에 있다
      final formPanel = find.ancestor(of: _field, matching: find.byType(Container)).last;
      expect(find.descendant(of: formPanel, matching: find.text(_invalid400)), findsOneWidget);
      await confirmDelete(tester);
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      // 폼 밖: 같은 판 안에 없다
      expect(find.descendant(of: formPanel, matching: find.text('삭제하지 못했습니다')), findsNothing);
    });
  });

  group('삭제', () {
    test('확인 창에서 취소하면 DELETE가 나가지 않고 일정이 그대로다', (tester) async {
      final app = await pumpWithPlan(tester, planJson());
      await tester.tap(_deleteButton);
      await tester.pumpAndSettle();
      expect(find.text('등록한 비자 일정을 삭제할까요?'), findsOneWidget);
      await tester.tap(find.descendant(of: find.byType(Dialog), matching: find.widgetWithText(OutlinedButton, '취소')));
      await tester.pumpAndSettle();
      expect(ddayText(tester), 'D-111');
      expect(app.adapter.count('DELETE', _path), 0);
    });

    test('삭제를 확인하면 DELETE 1번 뒤 빈 상태가 되고 날짜 칸이 비워진다', (tester) async {
      final adapter = FakeAdapter()..on('DELETE', _path, FakeReply(204));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      expect(fieldText(tester), '2027-03-01');
      await confirmDelete(tester);
      expect(find.text('등록된 일정 없음'), findsOneWidget);
      expect(fieldText(tester), '');
      expect(_dday, findsNothing);
      expect(adapter.count('DELETE', _path), 1);
      expect(adapter.count('GET', _path), 1); // 다시 GET 하지 않는다
      expect(_deleteButton, findsNothing);
    });

    test('404면 "이미 삭제된 일정입니다"가 보이고 빈 상태가 된다', (tester) async {
      final adapter = FakeAdapter()..on('DELETE', _path, FakeReply(404));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await confirmDelete(tester);
      expect(find.text('이미 삭제된 일정입니다'), findsOneWidget);
      expect(_errorNotices, findsOneWidget);
      expect(find.text('등록된 일정 없음'), findsOneWidget);
      expect(fieldText(tester), '');
    });

    test('500이면 "삭제하지 못했습니다"가 보이고 일정은 그대로다', (tester) async {
      final adapter = FakeAdapter()..on('DELETE', _path, FakeReply(500));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await confirmDelete(tester);
      expect(find.text('삭제하지 못했습니다'), findsOneWidget);
      expect(ddayText(tester), 'D-111');
      expect(fieldText(tester), '2027-03-01');
    });

    test('삭제 404 문구는 새 날짜를 저장하면 사라진다', (tester) async {
      final adapter = FakeAdapter()
        ..on('DELETE', _path, FakeReply(404))
        ..on('PUT', _path, FakeReply(200, planJson()));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await confirmDelete(tester);
      expect(find.text('이미 삭제된 일정입니다'), findsOneWidget);

      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(ddayText(tester), 'D-111');
      expect(_errorNotices, findsNothing);
    });
  });

  group('일반 안내', () {
    test('일반 안내 한 줄과 하이코리아 링크가 있다', (tester) async {
      await pumpWithPlan(tester, emptyPlanJson);
      expect(find.text('등록된 일정 없음'), findsOneWidget);
      expect(find.textContaining(_guideText, findRichText: true), findsOneWidget);
      expect(find.textContaining('나 관할 출입국·외국인청에서 확인하세요', findRichText: true), findsOneWidget);
      await tester.tap(find.text('하이코리아'));
      expect(opener.opened, ['https://www.hikorea.go.kr/']);
    });
  });

  group('D-Day·D+n·지난 단계·만료 안내', () {
    test('만료 당일이면 큰 글씨가 D-Day이고 지난 단계 2줄은 흐리게, 현재 단계(URGENT) 줄은 "지금 확인할 때"다', (tester) async {
      await pumpWithPlan(tester, dDayPlanJson());
      expect(ddayText(tester), 'D-Day');
      expect(ddayColor(tester), AppColors.celadon); // URGENT
      for (var i = 0; i < 3; i++) {
        expect(todoDday(tester, i), todoDdays[i]);
      }
      expect(todoStatus(tester, 0), '지난 단계');
      expect(todoStatus(tester, 1), '지난 단계');
      expect(todoStatus(tester, 2), '지금 확인할 때');
      // 지난 줄은 흐림(muted), 현재 줄은 청자색
      expect(todoDdayColor(tester, 0), AppColors.muted);
      expect(todoDdayColor(tester, 1), AppColors.muted);
      expect(todoDdayColor(tester, 2), AppColors.celadon);
      expect(todoStatusColor(tester, 2), AppColors.celadon);
      expect(find.text('지남'), findsNothing);
      // 아직 지나지 않았으니(0일) 만료 안내는 없다
      expect(find.text(_expiredNotice), findsNothing);
    });

    test('만료일이 3일 지났으면 D+3과 중립 안내가 보이고 할 일 줄은 전부 지난 단계다', (tester) async {
      await pumpWithPlan(tester, expiredPlanJson());
      expect(ddayText(tester), 'D+3');
      expect(ddayColor(tester), AppColors.muted); // 빨강이 아니라 흐리게
      expect(find.text(_expiredNotice), findsOneWidget);
      for (var i = 0; i < 3; i++) {
        expect(todoDday(tester, i), todoDdays[i]);
        expect(todoStatus(tester, i), '지난 단계');
        expect(todoDdayColor(tester, i), AppColors.muted);
      }
      expect(find.text('지난 단계'), findsNWidgets(3));
      expect(find.text('지금 확인할 때'), findsNothing);
    });

    test('만료 전 일정에는 만료 안내가 없고 큰 글씨는 본문색, 할 일 줄이 "만료 N일 전부터"로 보인다', (tester) async {
      await pumpWithPlan(tester, planJson());
      expect(ddayText(tester), 'D-111');
      expect(ddayColor(tester), AppColors.ink); // PREPARE는 강조 없음
      for (var i = 0; i < 3; i++) {
        expect(todoDday(tester, i), todoDdays[i]);
      }
      expect(find.text(_expiredNotice), findsNothing);
    });

    test('만료 D-15(CHECK)면 CHECK 줄이 "지금 확인할 때"(흐리지 않음), PREPARE 줄은 "지난 단계"(흐림), URGENT 줄은 "8일 뒤 시작"이다', (tester) async {
      await pumpWithPlan(tester, d15PlanJson());
      expect(ddayText(tester), 'D-15');
      expect(ddayColor(tester), AppColors.celadon); // CHECK
      for (var i = 0; i < 3; i++) {
        expect(todoDday(tester, i), todoDdays[i]);
      }
      // PREPARE: 이미 지난 단계, 흐리게
      expect(todoDate(tester, 0), '2026년 7월 28일');
      expect(todoStatus(tester, 0), '지난 단계');
      expect(todoDdayColor(tester, 0), AppColors.muted);
      // CHECK: 지금 들어와 있는 단계. 날짜는 지났어도(-15) 흐리지 않고 "지금 확인할 때"
      expect(todoDate(tester, 1), '2026년 10월 26일');
      expect(todoStatus(tester, 1), '지금 확인할 때');
      expect(todoStatusColor(tester, 1), AppColors.celadon);
      expect(todoDdayColor(tester, 1), AppColors.celadon);
      expect(find.text('지난 단계'), findsOneWidget); // 흐린 줄은 PREPARE 하나뿐
      // URGENT: 아직 안 온 단계
      expect(todoDate(tester, 2), '2026년 11월 18일');
      expect(todoStatus(tester, 2), '8일 뒤 시작');
      expect(todoDdayColor(tester, 2), AppColors.ink);
      expect(todoStatusColor(tester, 2), AppColors.muted);
    });

    test('121일 이상 남아 단계가 없어도 D-n과 할 일이 보이고 만료 안내는 없다', (tester) async {
      await pumpWithPlan(tester, farPlanJson());
      expect(ddayText(tester), 'D-201');
      for (var i = 0; i < 3; i++) {
        expect(todoDday(tester, i), todoDdays[i]);
      }
      expect(todoStatus(tester, 0), '81일 뒤 시작');
      expect(todoStatus(tester, 1), '171일 뒤 시작');
      expect(todoStatus(tester, 2), '194일 뒤 시작');
      expect(find.text('지난 단계'), findsNothing);
      expect(find.text('지금 확인할 때'), findsNothing);
      expect(find.text(_expiredNotice), findsNothing);
    });

    test('할 일이 비어 있으면 "할 일" 제목도 없다', (tester) async {
      await pumpWithPlan(tester, {...farPlanJson(), 'todos': <dynamic>[]});
      expect(ddayText(tester), 'D-201');
      expect(find.text('할 일'), findsNothing);
    });
  });

  group('불러오기', () {
    test('500이면 "일정을 불러오지 못했습니다"가 보이고 폼은 숨는다. 다시 부르지 않는다 (GET 1번)', (tester) async {
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(500));
      final app = await pump(tester, user: _verified(), adapter: adapter);
      expect(find.text('일정을 불러오지 못했습니다'), findsOneWidget);
      expect(_field, findsNothing);
      expect(_empty, findsNothing);
      await tester.pumpAndSettle();
      expect(app.adapter.count('GET', _path), 1);
    });

    test('불러오는 중에는 "불러오는 중..." 문구만 보이고 폼은 없다', (tester) async {
      final gate = Completer<void>();
      final adapter = FakeAdapter()..on('GET', _path, FakeReply(200, planJson(), gate));
      await pump(tester, user: _verified(), adapter: adapter);
      await tester.pump();
      expect(find.text('불러오는 중...'), findsOneWidget);
      expect(_field, findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('불러오는 중...'), findsNothing);
      expect(ddayText(tester), 'D-111');
    });

    test('불러오기 중 로그인이 풀리면 다시 로그인 안내만 보이고 폼은 없다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pump(tester, user: _verified(), adapter: adapter);
      expect(find.text(_relogin), findsOneWidget);
      expect(find.text('로그인'), findsWidgets);
      expect(_field, findsNothing);
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
    });

    test('실패한 채로 다른 화면에 갔다가 돌아오면 한 번 다시 부른다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(500))
        ..on('GET', _path, FakeReply(200, planJson()));
      final app = await pump(tester, user: _verified(), adapter: adapter);
      expect(find.text('일정을 불러오지 못했습니다'), findsOneWidget);
      app.router.go('/nowhere');
      await tester.pumpAndSettle();
      app.router.go('/visa-plan');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _path), 2);
      expect(ddayText(tester), 'D-111');
      expect(find.text('일정을 불러오지 못했습니다'), findsNothing);
    });

    test('같은 날 다시 들어오면 부르지 않고, 자정을 넘기면 한 번 다시 부른다', (tester) async {
      final adapter = FakeAdapter()
        ..on('GET', _path, FakeReply(200, planJson()))
        ..on('GET', _path, FakeReply(200, {...planJson(), 'daysLeft': 110}));
      final app = await pump(tester, user: _verified(), adapter: adapter);
      expect(ddayText(tester), 'D-111');
      // 같은 날: 나갔다 와도 GET은 그대로
      app.router.go('/nowhere');
      await tester.pumpAndSettle();
      app.router.go('/visa-plan');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _path), 1);
      // 서울 날짜가 바뀐 뒤: 들어오면 한 번 다시 부른다
      app.router.go('/nowhere');
      await tester.pumpAndSettle();
      _clock = DateTime.utc(2026, 11, 11, 3);
      app.router.go('/visa-plan');
      await tester.pumpAndSettle();
      expect(adapter.count('GET', _path), 2);
      expect(ddayText(tester), 'D-110');
    });
  });

  group('로그인이 풀릴 때', () {
    test('저장 중 로그인이 풀리면 다시 로그인 안내가 보이고 입력값과 주소는 그대로다', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      final app = await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text(_relogin), findsOneWidget);
      expect(fieldText(tester), '2027-03-01');
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/visa-plan');
    });

    test('일정이 있을 때 저장 중 로그인이 풀려도 D-n과 폼이 남는다', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await pumpWithPlan(tester, planJson(), adapter: adapter);
      await tester.tap(_field);
      await tester.pumpAndSettle();
      await tester.tap(find.text('31'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      await tester.tap(_changeButton);
      await tester.pumpAndSettle();
      expect(find.text(_relogin), findsOneWidget);
      expect(ddayText(tester), 'D-111');
      expect(fieldText(tester), '2027-03-31');
    });

    test('로그인이 풀린 뒤 다시 저장해 성공하면 화면이 새 일정으로 바뀌고 재로그인 안내가 내려간다', (tester) async {
      final adapter = FakeAdapter()
        ..on('PUT', _path, FakeReply(401))
        ..on('POST', '/auth/refresh', FakeReply(401));
      await pumpWithPlan(tester, emptyPlanJson, adapter: adapter);
      await pickMarch1(tester);
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(find.text(_relogin), findsOneWidget);
      // 다른 탭에서 다시 로그인해 쿠키가 살아났다 → 이 탭에서 다시 저장하면 성공
      adapter.on('PUT', _path, FakeReply(200, planJson()));
      await tester.tap(_saveButton);
      await tester.pumpAndSettle();
      expect(ddayText(tester), 'D-111');
      expect(find.text(_relogin), findsNothing);
      expect(_errorNotices, findsNothing);
    });
  });

  group('날짜 달력', () {
    test('날짜 칸은 직접 쓰지 않는 읽기 전용 칸이고 힌트는 yyyy-MM-dd', (tester) async {
      await pumpWithPlan(tester, emptyPlanJson);
      final field = tester.widget<TextField>(_field);
      expect(field.readOnly, isTrue);
      expect(field.decoration!.hintText, 'yyyy-MM-dd');
      expect(fieldText(tester), '');
    });

    test('달력은 서울 오늘부터 오늘+5년까지만 고르게 한다', (tester) async {
      await pumpWithPlan(tester, emptyPlanJson);
      await tester.tap(_field);
      await tester.pumpAndSettle();
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(dialog.firstDate, DateTime(2026, 11, 10));
      expect(dialog.lastDate, DateTime(2031, 11, 10));
      expect(dialog.initialDate, DateTime(2026, 11, 10)); // 값이 없으면 오늘에서 열린다
    });

    test('이미 지난 만료일이 칸에 있어도 달력은 오늘에서 열리고, 오늘 이후 날을 고를 수 있다', (tester) async {
      await pumpWithPlan(tester, expiredPlanJson());
      expect(fieldText(tester), '2026-11-07');
      await tester.tap(_field);
      await tester.pumpAndSettle();
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(dialog.initialDate, DateTime(2026, 11, 10)); // 범위 밖이라 오늘로 당겨진다
      await tester.tap(find.text('25'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('확인'));
      await tester.pumpAndSettle();
      expect(fieldText(tester), '2026-11-25');
    });

    test('키보드: 칸에 포커스를 두고 Enter나 Space를 누르면 달력이 열리고, "오늘" 표시는 서울 오늘이다', (tester) async {
      await pumpWithPlan(tester, emptyPlanJson);
      final focus = tester.widget<EditableText>(find.descendant(of: _field, matching: find.byType(EditableText))).focusNode;
      focus.requestFocus();
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      final dialog = tester.widget<DatePickerDialog>(find.byType(DatePickerDialog));
      expect(dialog.currentDate, DateTime(2026, 11, 10));
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsNothing);

      focus.requestFocus();
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.space);
      await tester.pumpAndSettle();
      expect(find.byType(DatePickerDialog), findsOneWidget);
    });

    test('달력에서 [취소]하면 칸이 바뀌지 않는다', (tester) async {
      await pumpWithPlan(tester, planJson());
      await tester.tap(_field);
      await tester.pumpAndSettle();
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(fieldText(tester), '2027-03-01');
    });
  });

  test('제목과 일정 판의 이름표가 스크린리더에 전달된다', (tester) async {
    final handle = tester.ensureSemantics();
    await pumpWithPlan(tester, planJson());
    // 일정 판은 이름표 뒤에 안의 글자가 이어 붙어 읽힌다
    expect(find.bySemanticsLabel(RegExp('^내 비자 일정')), findsOneWidget);
    expect(find.bySemanticsLabel('체류 만료일'), findsOneWidget);
    handle.dispose();
  });
}
