// S30-c 사진 자동 검사 화면 쪽 테스트:
// 1) 사진 올리기 422 문구 (수정 화면 / 글쓰기) 2) 가운데 한 장만 422여도 나머지는 계속 올라간다
// 3~5) 관리자 신고 목록의 '자동 탐지' 표시 (AUTO / USER / 이상한 값)  6) 사진 칸 아래 구글 전송 안내
import 'package:board_web/admin/models.dart';
import 'package:board_web/admin/report_meta_row.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/common/file_picker.dart';
import 'package:board_web/errors/error_messages.dart';
import 'package:board_web/market/market_photo_picker.dart';
import 'package:board_web/market/photo_upload.dart';
import 'package:board_web/permit/permit_verification_section.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/market_json.dart';
import 'support/photo_support.dart';
import 'support/screen.dart';

const _rejected = '자동 검사에서 올릴 수 없는 사진으로 판단되었습니다. 다른 사진을 골라 주세요';
const _photosPath = '/market-items/7/photos';
const _photos9 = '/market-items/9/photos';
const _list = '/admin/reports';

late FakePhotoPicker _picker;

void _photoTest(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    _picker = installFakePicker();
    await body(tester);
  });
}

final _addButton = find.byKey(const ValueKey('photo-add-button'));
Finder _heading(int n) => find.text('사진 ($n/10)');

Future<void> _pickFiles(WidgetTester tester, List<PickedPhoto> files) async {
  await tester.ensureVisible(_addButton);
  await tester.tap(_addButton);
  await tester.pump();
  _picker.choose(files);
  await tester.pumpAndSettle();
}

FakeAdapter _editAdapter({List<int> photoIds = const [1]}) =>
    FakeAdapter()..on('GET', '/market-items/7', FakeReply(200, marketDetailJson(photoIds: photoIds)));

Future<TestApp> _openEdit(WidgetTester tester, FakeAdapter a) =>
    pumpApp(tester, location: '/market/7/edit', user: userJson(id: 1, school: marketSeoul), adapter: a);

Future<TestApp> _openCreate(WidgetTester tester, FakeAdapter a) =>
    pumpApp(tester, location: '/market/new', user: userJson(id: 1, school: marketSeoul), adapter: a);

Future<void> _fillAndSave(WidgetTester tester) async {
  await tester.enterText(find.byKey(const ValueKey('title-field')), '새 자전거');
  await tester.enterText(find.byKey(const ValueKey('content-field')), '설명');
  await tester.enterText(find.byKey(const ValueKey('price-field')), '5000');
  await tester.pump();
  await tester.tap(find.widgetWithText(FilledButton, '저장'));
  await tester.pumpAndSettle();
}

// ---- 관리자 신고 목록 ----
Future<TestApp> _openReports(WidgetTester tester, List<Map<String, dynamic>> reports, {String location = _list}) {
  final adapter = FakeAdapter()..on('GET', _list, FakeReply(200, reports));
  return pumpApp(tester, location: location, user: adminUser(), adapter: adapter);
}

Finder _row(int id) => find.byKey(ValueKey('report-$id'));
Finder _in(int id, String text) => find.descendant(of: _row(id), matching: find.text(text));

// 실제 서버 응답 모양의 AUTO 신고: 중고 글 대상, reason SEXUAL, detail 자동 탐지 설명, reporter 없음(기본)
Map<String, dynamic> _autoReport(int id, {Object? reporter}) => {
  ...marketReport(id: id),
  'source': 'AUTO',
  'reporter': reporter,
  'reason': {'code': 'SEXUAL', 'name': '음란물'},
  'detail': '자동 탐지: 사진 7, 성인물 LIKELY',
};

// 행 안의 "자동 탐지" 배지(AppBadge)만 / 신고자 줄(ReportMetaRow) 중 값이 value인 것만
Finder _autoBadge(int id) => find.descendant(
  of: _row(id),
  matching: find.byWidgetPredicate((w) => w is AppBadge && w.text == '자동 탐지'),
);
Finder _reporterRow(int id, String value) => find.descendant(
  of: _row(id),
  matching: find.byWidgetPredicate((w) => w is ReportMetaRow && w.label == '신고자' && w.value == value),
);

void main() {
  group('1~2. 사진 올리기 422', () {
    _photoTest('수정 화면에서 사진 올리기가 422면 자동 검사 거절 문구가 보이고 목록은 그대로다', (tester) async {
      final adapter = _editAdapter()..on('POST', _photosPath, FakeReply(422));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg')]);
      expect(find.text(_rejected), findsOneWidget);
      expect(_heading(1), findsOneWidget);
    });

    _photoTest('회귀: 수정 화면: 3장 중 가운데만 422면 요청은 3번, 첫 장·끝 장은 붙고 문구가 보인다', (tester) async {
      final adapter = _editAdapter(photoIds: [])
        ..on('POST', _photosPath, FakeReply(201, photoJson(101)))
        ..on('POST', _photosPath, FakeReply(422))
        ..on('POST', _photosPath, FakeReply(201, photoJson(103)));
      await _openEdit(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')]);
      // (회귀: 이번 리뷰 반영 전에도 통과하던 검사. 422가 나와도 중단하지 않는 동작이 유지되는지 본다)
      expect(adapter.count('POST', _photosPath), 3); // 422는 멈추지 않는다
      expect(_heading(2), findsOneWidget); // 성공 2장만 붙는다
      expect(find.text(_rejected), findsOneWidget); // 장수 접미사 없이 문구 그대로
    });

    _photoTest('글쓰기: 3장 중 가운데만 422여도 3번 다 시도하고 상세로 가며 "자동 검사에서 올릴 수 없는 사진으로 판단된 사진이 1장 있습니다"가 뜬다', (tester) async {
      final adapter = FakeAdapter()
        ..on('POST', '/market-items', FakeReply(201, marketDetailJson(id: 9)))
        ..on('GET', '/market-items/9', FakeReply(200, marketDetailJson(id: 9)))
        ..on('POST', _photos9, FakeReply(201, photoJson(201)))
        ..on('POST', _photos9, FakeReply(422))
        ..on('POST', _photos9, FakeReply(201, photoJson(203)));
      final app = await _openCreate(tester, adapter);
      await _pickFiles(tester, [fakePhoto('a.jpg'), fakePhoto('b.jpg'), fakePhoto('c.jpg')]);
      await _fillAndSave(tester);
      expect(adapter.count('POST', _photos9), 3);
      expect(app.router.routerDelegate.currentConfiguration.uri.toString(), '/market/9');
      // 거절(422)은 다시 올려도 또 거절이라 "다시 올려 주세요" 없이 따로 알린다
      expect(find.text('자동 검사에서 올릴 수 없는 사진으로 판단된 사진이 1장 있습니다'), findsOneWidget);
      expect(find.textContaining('다시 올려 주세요'), findsNothing);
    });

    test('글쓰기·수정이 같이 쓰는 문구 만들기: 422 → 같은 문구, 2장 422면 "(2장 실패)"가 붙는다', () {
      DioException e422() => DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(requestOptions: RequestOptions(path: '/x'), statusCode: 422),
        type: DioExceptionType.badResponse,
      );
      expect(photoUploadErrors[422], ErrorMessages.marketPhotoRejected);
      final one = uploadFailureText(UploadResult(1, e422())) as ErrorMessage;
      expect(one.message, _rejected);
      final two = uploadFailureText(UploadResult(2, e422())) as ErrorMessage;
      expect(two.message, '$_rejected (2장 실패)');
    });
  });

  group('3~5. 관리자 신고 목록 자동 탐지', () {
    for (final location in [_list, '$_list?status=DISMISSED']) {
      testWidgets('source=AUTO 신고(실제 응답 모양)는 신고자 줄이 "자동 탐지"이고 회색 배지가 보인다 ($location)', (tester) async {
        useTallScreen(tester);
        await _openReports(tester, [_autoReport(1)], location: location);
        // 배지(AppBadge)와 신고자 줄(ReportMetaRow)을 따로 찾아 각각 한 개씩인지 확인한다
        expect(_autoBadge(1), findsOneWidget);
        final badge = tester.widget<AppBadge>(_autoBadge(1));
        expect(badge.ink, AppColors.muted); // 글자색 회색
        expect(badge.bg, AppColors.badgeChatBg); // 배경 연회색
        expect(_reporterRow(1, '자동 탐지'), findsOneWidget); // 신고자 줄 값
        expect(_in(1, '탈퇴한 회원'), findsNothing); // reporter가 null이어도 AUTO는 탈퇴 표기가 아니다
        expect(_in(1, '신고자'), findsOneWidget); // 라벨
        expect(find.textContaining('자동 탐지: 사진 7, 성인물 LIKELY'), findsOneWidget); // 상세 내용도 보인다
      });
    }

    testWidgets('AUTO 한 건과 USER 한 건이 섞이면 AUTO 행에만 배지가 있다', (tester) async {
      useTallScreen(tester);
      await _openReports(tester, [
        _autoReport(1, reporter: const {'id': 2, 'nickname': '신고자'}),
        {...marketReport(id: 2), 'source': 'USER'},
      ]);
      expect(_autoBadge(1), findsOneWidget);
      expect(_reporterRow(1, '자동 탐지'), findsOneWidget);
      expect(_autoBadge(2), findsNothing);
      expect(_reporterRow(2, '자동 탐지'), findsNothing);
      expect(_in(2, '자동 탐지'), findsNothing);
      expect(_in(2, '신고자'), findsNWidgets(2)); // 라벨 + 닉네임(테스트 데이터가 같은 글자)
    });

    testWidgets('USER + reporter 없음이면 "탈퇴한 회원", 배지는 없다', (tester) async {
      useTallScreen(tester);
      await _openReports(tester, [
        {...reportJson(id: 1, reporter: null), 'source': 'USER'},
      ]);
      expect(_in(1, '탈퇴한 회원'), findsOneWidget);
      expect(find.text('자동 탐지'), findsNothing);
    });

    testWidgets('USER + reporter 있으면 닉네임이 보이고 "자동 탐지"는 어디에도 없다', (tester) async {
      useTallScreen(tester);
      await _openReports(tester, [
        {...reportJson(id: 1, reporter: {'id': 2, 'nickname': '민수'}), 'source': 'USER'},
      ]);
      expect(_in(1, '민수'), findsOneWidget);
      expect(find.text('자동 탐지'), findsNothing);
    });

    testWidgets('source가 없거나 이상한 값이면 USER로 보고 화면이 안 깨진다', (tester) async {
      useTallScreen(tester);
      await _openReports(tester, [
        reportJson(id: 1, reporter: {'id': 2, 'nickname': '민수'}), // source 없음
        {...reportJson(id: 2, reporter: {'id': 2, 'nickname': '철수'}), 'source': 'WEIRD'},
        {...reportJson(id: 3, reporter: {'id': 2, 'nickname': '영희'}), 'source': null},
        {...reportJson(id: 4, reporter: {'id': 2, 'nickname': '지민'}), 'source': 'auto'}, // 소문자도 USER
      ]);
      expect(tester.takeException(), isNull);
      expect(find.text('자동 탐지'), findsNothing);
      expect(_in(1, '민수'), findsOneWidget);
      expect(_in(2, '철수'), findsOneWidget);
      expect(_in(3, '영희'), findsOneWidget);
      expect(_in(4, '지민'), findsOneWidget);
    });

    test('AdminReport 모델: AUTO만 isAuto, 나머지는 USER', () {
      bool auto(Object? s) => AdminReport.fromJson({...reportJson(), 'source': s}).isAuto;
      expect(auto('AUTO'), isTrue);
      expect(auto('USER'), isFalse);
      expect(auto('x'), isFalse);
      expect(auto(null), isFalse);
      expect(AdminReport.fromJson(reportJson()).source, 'USER');
    });
  });

  group('6. 사진 칸 아래 구글 전송 안내', () {
    _photoTest('글쓰기 화면의 사진 칸에 안내 한 줄이 보인다', (tester) async {
      await _openCreate(tester, FakeAdapter());
      expect(find.text(marketPhotoPrivacyNotice), findsOneWidget);
      expect(marketPhotoPrivacyNotice, '올린 사진은 자동 검사를 위해 구글로 전송돼요.');
    });

    _photoTest('수정 화면의 사진 칸에도 안내 한 줄이 보인다', (tester) async {
      await _openEdit(tester, _editAdapter());
      expect(find.text(marketPhotoPrivacyNotice), findsOneWidget);
    });

    testWidgets('허가서 신청(유학생 인증) 화면에는 이 안내가 없다', (tester) async {
      useTallScreen(tester);
      final fake = FakeAdapter()..on('GET', '/users/me/permit-verification', FakeReply(200, {'status': 'NONE'}));
      final auth = LoadedAuthState(adapter: fake, user: userJson());
      addTearDown(auth.dispose);
      await tester.pumpWidget(
        Provider<ApiClient>.value(
          value: auth.api,
          child: ChangeNotifierProvider<AuthState>.value(
            value: auth,
            child: MaterialApp(
              theme: buildAppTheme(),
              home: const Scaffold(body: SingleChildScrollView(child: PermitVerificationSection())),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(PermitVerificationSection), findsOneWidget);
      expect(find.text('인증 신청'), findsWidgets); // 화면이 실제로 떴다는 확인 (가짜 통과 방지)
      expect(find.text(marketPhotoPrivacyNotice), findsNothing);
      expect(find.textContaining('구글'), findsNothing);
    });
  });
}
