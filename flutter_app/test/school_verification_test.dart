// S20-f2 학교 인증 섹션 테스트: 메일 인증 폼을 걷어낸 뒤의 동작을 본다.
// 미인증 회원은 안내 한 줄만(입력칸·버튼 없음, 서버 요청 없음), 인증 회원은 뱃지를 본다.
import 'package:board_web/api/api_client.dart';
import 'package:board_web/auth/auth_state.dart';
import 'package:board_web/theme/app_theme.dart';
import 'package:board_web/widgets/app_badge.dart';
import 'package:board_web/widgets/school_verification_section.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _notice = '유학생 인증(표준입학허가서) 화면을 준비 중이에요';
const _school = {'id': 1, 'name': '서울대'};

// 섹션 하나를 띄우고, 요청 기록을 볼 수 있게 어댑터를 돌려준다
Future<FakeAdapter> _pump(WidgetTester tester, {Map<String, dynamic>? user}) async {
  useTallScreen(tester);
  final fake = FakeAdapter(); // 요청이 나가면 fake.calls에 기록된다 → 아래에서 비었는지 확인한다
  final auth = LoadedAuthState(adapter: fake, user: user ?? userJson());
  addTearDown(auth.dispose);
  await tester.pumpWidget(
    Provider<ApiClient>.value(
      value: auth.api,
      child: ChangeNotifierProvider<AuthState>.value(
        value: auth,
        child: MaterialApp(
          theme: buildAppTheme(),
          home: const Scaffold(body: SingleChildScrollView(child: SchoolVerificationSection())),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return fake;
}

void main() {
  group('미인증 회원', () {
    testWidgets('제목과 준비 중 안내만 보이고, 입력칸·버튼은 없다', (tester) async {
      await _pump(tester);
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text(_notice), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
      expect(find.byType(OutlinedButton), findsNothing);
      expect(find.text('코드 보내기'), findsNothing);
      expect(find.textContaining('학교 인증 완료'), findsNothing);
    });

    testWidgets('서버 요청을 보내지 않는다', (tester) async {
      final fake = await _pump(tester);
      expect(fake.calls, isEmpty);
    });
  });

  group('인증 회원', () {
    testWidgets('뱃지 "학교 인증 완료 · 학교명"과 "졸업 후에도 유지돼요"가 보인다', (tester) async {
      final fake = await _pump(tester, user: userJson(school: _school));
      expect(find.text('학교 인증'), findsOneWidget);
      expect(find.text('학교 인증 완료 · 서울대'), findsOneWidget);
      expect(find.ancestor(of: find.text('학교 인증 완료 · 서울대'), matching: find.byType(AppBadge)), findsOneWidget);
      expect(find.text('졸업 후에도 유지돼요'), findsOneWidget);
      expect(find.text(_notice), findsNothing);
      expect(fake.calls, isEmpty);
    });
  });
}
