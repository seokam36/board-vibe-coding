// S20-g3 관리자 "인증 심사" 화면 테스트: 메뉴·권한, 탭(?status), 한 줄 표시, 사진 보기(bytes), 빈 목록·실패.
// 사진은 직접 만든 1x1 PNG만 쓴다(실물 허가서 아님).
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:board_web/common/format_date.dart';
import 'package:board_web/theme/app_colors.dart';
import 'package:board_web/widgets/loading.dart';
import 'package:board_web/widgets/notice.dart';
import 'package:board_web/widgets/relogin_notice.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'support/admin_json.dart';
import 'support/fake_adapter.dart';
import 'support/loaded_auth.dart';
import 'support/screen.dart';

const _list = '/admin/permit-requests';
String _photoPath(int id) => '/admin/permit-requests/$id/photo';

// 직접 만든 1x1 PNG (빨간 점 하나)
final Uint8List _png = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==',
);

// 사진 요청에는 JSON이 아닌 "생(raw) 바이트"를 돌려 주고, 요청에 걸린 responseType을 기록하는 가짜 서버
class _PhotoAdapter extends FakeAdapter {
  final Map<int, List<_PhotoReply>> photos = {};
  final List<ResponseType> photoResponseTypes = [];
  int cancelSignals = 0; // 사진 요청에 "취소" 신호가 온 횟수

  void photo(int id, _PhotoReply r) => photos.putIfAbsent(id, () => []).add(r);

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    final m = RegExp(r'/photo$').hasMatch(options.uri.path)
        ? RegExp(r'permit-requests/(\d+)/photo').firstMatch(options.uri.path)
        : null;
    if (m == null) return super.fetch(options, requestStream, cancelFuture);
    calls.add(FakeCall(options.method, options.uri.path, Map.of(options.headers), options.uri.queryParameters, options.receiveTimeout));
    photoResponseTypes.add(options.responseType);
    cancelFuture?.then((_) => cancelSignals++);
    final r = photos[int.parse(m.group(1)!)]!.removeAt(0);
    if (r.gate != null) await r.gate!.future;
    if (r.networkError) {
      throw DioException(requestOptions: options, type: DioExceptionType.connectionError, error: 'fake');
    }
    return ResponseBody.fromBytes(r.bytes ?? Uint8List(0), r.status, headers: {
      Headers.contentTypeHeader: [r.status == 200 ? 'image/png' : 'application/json'],
    });
  }
}

class _PhotoReply {
  _PhotoReply(this.status, {this.bytes, this.gate}) : networkError = false;
  _PhotoReply.network() : status = 0, bytes = null, gate = null, networkError = true;
  final int status;
  final Uint8List? bytes;
  final Completer<void>? gate;
  final bool networkError;
}

Map<String, dynamic> _req({
  int id = 1,
  int userId = 100,
  String nickname = '민수',
  String status = 'PENDING',
  String createdAt = '2026-11-01T03:04:05Z',
  String? schoolName,
  String? rejectReason,
  String? detectedSchoolName,
  String? machineResult = 'UNREADABLE',
  bool autoDecided = false,
  bool hasPhoto = true,
}) => {
  'id': id,
  'userId': userId,
  'nickname': nickname,
  'status': status,
  'createdAt': createdAt,
  'schoolName': schoolName,
  'rejectReason': rejectReason,
  'detectedSchoolId': null,
  'detectedSchoolName': detectedSchoolName,
  'machineResult': machineResult,
  'autoDecided': autoDecided,
  'hasPhoto': hasPhoto,
};

Future<TestApp> _open(WidgetTester tester, FakeAdapter a, {Map<String, dynamic>? user, bool asAdmin = true}) =>
    pumpApp(tester, location: '/admin/permits', user: asAdmin ? (user ?? adminUser()) : user, adapter: a);

void _test(String name, Future<void> Function(WidgetTester tester) body) {
  testWidgets(name, (tester) async {
    useTallScreen(tester);
    await body(tester);
  });
}

Finder _row(int id) => find.byKey(ValueKey('permit-$id'));
Finder _btnIn(int id, String text) => find.descendant(of: _row(id), matching: find.widgetWithText(OutlinedButton, text));
Finder _textIn(int id, String text) => find.descendant(of: _row(id), matching: find.text(text));
Finder _retry() => find.widgetWithText(OutlinedButton, '다시 시도');
Finder _noticeOf(String m) => find.ancestor(of: find.text(m), matching: find.byType(Notice));
String? _lastStatus(FakeAdapter a) => a.callsTo('GET', _list).last.query['status'];
Color? _tabColor(WidgetTester t, String label) => t.widget<Text>(find.text(label).first).style?.color;

// 사진 디코딩은 실제 비동기라서 runAsync로 기다린 뒤 화면을 갱신한다
Future<void> _settleImage(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 300)));
  await tester.pumpAndSettle();
}

void main() {
  group('조건1 메뉴·권한', () {
    _test('관리자 상단바에 "인증 심사"가 있고 누르면 /admin/permits로 가며 목록을 부른다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', '/admin/reports', FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, []));
      final app = await pumpApp(tester, location: '/admin/reports', user: adminUser(), adapter: a);
      expect(find.text('인증 심사'), findsOneWidget);
      await tester.tap(find.text('인증 심사'));
      await tester.pumpAndSettle();
      expect(app.router.routerDelegate.currentConfiguration.uri.path, '/admin/permits');
      expect(a.count('GET', _list), 1);
    });

    for (final (name, user) in <(String, Map<String, dynamic>?)>[('일반 회원', userJson()), ('비로그인', null)]) {
      testWidgets('$name은 "관리자만 볼 수 있습니다"만 보이고 API는 부르지 않으며 메뉴 링크도 없다', (tester) async {
        final a = FakeAdapter();
        await pumpApp(tester, location: '/admin/permits', user: user, adapter: a);
        expect(find.text('관리자만 볼 수 있습니다'), findsOneWidget);
        expect(a.calls.where((c) => c.path.contains('/admin')), isEmpty);
        expect(find.text('대기'), findsNothing);
        expect(find.text('신고 관리'), findsNothing); // 기존 관리자 링크와 같은 규칙
        expect(find.text('인증 심사'), findsNothing);
      });
    }

    _test('경로 대소문자가 달라도(/ADMIN/Permits) 같은 화면이 열린다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, []));
      await pumpApp(tester, location: '/ADMIN/Permits', user: adminUser(), adapter: a);
      expect(a.count('GET', _list), 1);
    });
  });

  group('조건2 탭', () {
    _test('기본은 대기 탭이고 ?status=PENDING으로 한 번 부른다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, [_req()]));
      await _open(tester, a);
      expect(a.count('GET', _list), 1);
      expect(_lastStatus(a), 'PENDING');
      expect(_tabColor(tester, '대기'), AppColors.accent);
      expect(_tabColor(tester, '승인'), AppColors.muted);
      expect(_tabColor(tester, '반려'), AppColors.muted);
    });

    _test('승인·반려 탭을 누르면 각각 APPROVED·REJECTED로 부르고 목록이 바뀐다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1)]))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, status: 'APPROVED', schoolName: '가나대')]))
        ..on('GET', _list, FakeReply(200, [_req(id: 3, status: 'REJECTED', rejectReason: 'BLURRY')]));
      await _open(tester, a);
      await tester.tap(find.text('승인').first);
      await tester.pumpAndSettle();
      expect(_lastStatus(a), 'APPROVED');
      expect(_row(2), findsOneWidget);
      expect(_row(1), findsNothing);
      expect(_tabColor(tester, '승인'), AppColors.accent);
      await tester.tap(find.text('반려'));
      await tester.pumpAndSettle();
      expect(_lastStatus(a), 'REJECTED');
      expect(_row(3), findsOneWidget);
      expect(a.count('GET', _list), 3);
    });

    _test('같은 탭을 다시 눌러도 다시 부르지 않는다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, []));
      await _open(tester, a);
      await tester.tap(find.text('대기'));
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 1);
    });

    _test('늦게 온 옛(대기) 응답이 새(승인) 탭 목록을 덮어쓰지 않는다', (tester) async {
      final gate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, nickname: '옛대기')], gate))
        ..on('GET', _list, FakeReply(200, [_req(id: 2, nickname: '새승인', status: 'APPROVED')]));
      await _open(tester, a);
      await tester.pump(); // 첫 요청이 붙잡힌 상태
      await tester.tap(find.text('승인').first);
      await tester.pumpAndSettle();
      expect(find.text('새승인'), findsOneWidget);
      gate.complete(); // 이제 옛 응답이 도착
      await tester.pumpAndSettle();
      expect(find.text('새승인'), findsOneWidget);
      expect(find.text('옛대기'), findsNothing);
    });
  });

  group('조건3 한 줄 표시', () {
    _test('닉네임·신청 시각·"자동" 배지(autoDecided)가 보이고, 자동이 아니면 배지가 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [
          _req(id: 1, nickname: '자동이', autoDecided: true, hasPhoto: false),
          _req(id: 2, nickname: '수동이'),
        ]));
      await _open(tester, a);
      expect(_textIn(1, '자동이'), findsOneWidget);
      expect(_textIn(1, '자동'), findsOneWidget);
      expect(_textIn(2, '수동이'), findsOneWidget);
      expect(_textIn(2, '자동'), findsNothing);
      expect(_textIn(1, formatDate('2026-11-01T03:04:05Z')), findsOneWidget);
    });

    const labels = <String, String>{
      'UNREADABLE': '글자를 못 읽음',
      'NO_TITLE': '허가서 제목 없음',
      'NATIONALITY_UNCLEAR': '국적 불명확',
      'SCHOOL_NOT_MATCHED': '학교 불일치',
      'NUMBER_NOT_FOUND': '번호 못 찾음',
      'NUMBER_DUPLICATE': '이미 쓰인 번호',
      'ALL_PASSED': '4가지 모두 통과',
    };
    _test('기계 결과 코드 7가지가 각각 한글 문구로 보인다', (tester) async {
      final entries = labels.entries.toList();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [
          for (var i = 0; i < entries.length; i++) _req(id: i + 1, machineResult: entries[i].key),
        ]));
      await _open(tester, a);
      for (var i = 0; i < entries.length; i++) {
        expect(_textIn(i + 1, entries[i].value), findsOneWidget, reason: entries[i].key);
      }
    });

    _test('기계 결과가 null이면 "판정 없음", 모르는 코드는 그대로 보인다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [
          _req(id: 1, machineResult: null),
          _req(id: 2, machineResult: 'NEW_CODE_X'),
        ]));
      await _open(tester, a);
      expect(_textIn(1, '판정 없음'), findsOneWidget);
      expect(_textIn(2, 'NEW_CODE_X'), findsOneWidget);
    });

    _test('찾은 학교(detectedSchoolName)가 있으면 보이고 null이면 줄이 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [
          _req(id: 1, detectedSchoolName: '서울대'),
          _req(id: 2),
        ]));
      await _open(tester, a);
      expect(_textIn(1, '서울대'), findsOneWidget);
      expect(_textIn(1, '찾은 학교'), findsOneWidget);
      expect(_textIn(2, '찾은 학교'), findsNothing);
    });

    _test('승인 건은 승인 학교(schoolName)를 보여 준다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [])) // 대기 탭
        ..on('GET', _list, FakeReply(200, [_req(id: 5, status: 'APPROVED', schoolName: '가나대', hasPhoto: false)]));
      await _open(tester, a);
      await tester.tap(find.text('승인').first);
      await tester.pumpAndSettle();
      expect(_textIn(5, '승인 학교'), findsOneWidget);
      expect(_textIn(5, '가나대'), findsOneWidget);
    });

    _test('반려 건은 사유 코드의 문구를 보이고, 모르는 코드는 그대로, null은 "-"로 보인다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, []))
        ..on('GET', _list, FakeReply(200, [
          _req(id: 1, status: 'REJECTED', rejectReason: 'BLURRY'),
          _req(id: 2, status: 'REJECTED', rejectReason: 'NOT_PERMIT'),
          _req(id: 3, status: 'REJECTED', rejectReason: 'NOT_MASKED'),
          _req(id: 4, status: 'REJECTED', rejectReason: 'UNSUPPORTED_SCHOOL'),
          _req(id: 5, status: 'REJECTED', rejectReason: 'DUPLICATE'),
          _req(id: 6, status: 'REJECTED', rejectReason: 'EXPIRED'),
          _req(id: 7, status: 'REJECTED', rejectReason: 'OTHER'),
          _req(id: 8, status: 'REJECTED', rejectReason: 'WEIRD'),
          _req(id: 9, status: 'REJECTED', rejectReason: null),
        ]));
      await _open(tester, a);
      await tester.tap(find.text('승인').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('반려'));
      await tester.pumpAndSettle();
      expect(_textIn(1, '사진이 흐려서 글자를 읽을 수 없어요'), findsOneWidget);
      expect(_textIn(2, '표준입학허가서가 아니에요'), findsOneWidget);
      expect(_textIn(3, '가려야 할 정보가 보여요. 가리고 다시 올려 주세요'), findsOneWidget);
      expect(_textIn(4, '아직 지원하지 않는 학교예요'), findsOneWidget);
      expect(_textIn(5, '이미 다른 계정에서 인증한 허가서예요'), findsOneWidget);
      expect(_textIn(6, '14일 안에 확인하지 못했어요. 다시 신청해 주세요'), findsOneWidget);
      expect(_textIn(7, '확인할 수 없었어요. 다시 신청해 주세요'), findsOneWidget);
      expect(_textIn(8, 'WEIRD'), findsOneWidget);
      expect(_textIn(9, '-'), findsOneWidget);
    });
  });

  group('조건4 사진', () {
    _test('hasPhoto=false인 줄에는 [사진 보기] 버튼이 없고, true인 줄에는 있다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 1, hasPhoto: false), _req(id: 2)]));
      await _open(tester, a);
      expect(_btnIn(1, '사진 보기'), findsNothing);
      expect(_btnIn(2, '사진 보기'), findsOneWidget);
    });

    _test('[사진 보기]를 누르면 로딩 표시 후 Image.memory로 펼쳐지고 [사진 접기]로 접힌다. 요청은 bytes·receiveTimeout', (tester) async {
      final gate = Completer<void>();
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 7)]))
        ..photo(7, _PhotoReply(200, bytes: _png, gate: gate));
      await _open(tester, a);
      await tester.tap(_btnIn(7, '사진 보기'));
      await tester.pump();
      expect(find.text('사진 불러오는 중...'), findsOneWidget); // 로딩 중
      expect(_btnIn(7, '사진 접기'), findsOneWidget);
      gate.complete();
      await tester.pump();
      await _settleImage(tester);
      expect(find.text('사진 불러오는 중...'), findsNothing);
      final img = tester.widget<Image>(find.byType(Image));
      expect(img.image, isA<MemoryImage>());
      expect((img.image as MemoryImage).bytes, _png);
      expect(find.text('사진을 표시할 수 없어요'), findsNothing);
      // 요청 옵션
      expect(a.photoResponseTypes, [ResponseType.bytes]);
      final call = a.callsTo('GET', _photoPath(7)).single;
      expect(call.receiveTimeout, isNotNull);
      expect(call.receiveTimeout! > Duration.zero, isTrue);
      // 접기
      await tester.tap(_btnIn(7, '사진 접기'));
      await tester.pumpAndSettle();
      expect(find.byType(Image), findsNothing);
      expect(_btnIn(7, '사진 보기'), findsOneWidget);
    });

    // 사진 요청 오류별 문구
    final errors = <(String, _PhotoReply, String)>[
      ('404', _PhotoReply(404), '사진이 없어요(이미 처리되었을 수 있어요)'),
      ('403', _PhotoReply(403), '관리자만 볼 수 있습니다'),
      ('500', _PhotoReply(500), '사진을 불러오지 못했어요'),
      ('네트워크', _PhotoReply.network(), '서버에 연결할 수 없습니다'),
    ];
    for (final (name, reply, message) in errors) {
      _test('사진 요청이 $name이면 "$message"(error)가 보이고 이미지는 없다', (tester) async {
        final a = _PhotoAdapter()
          ..on('GET', _list, FakeReply(200, [_req(id: 3)]))
          ..on('GET', _list, FakeReply(200, [_req(id: 3)])) // 사진이 404면 목록을 다시 읽는다
          ..photo(3, reply);
        await _open(tester, a);
        await tester.tap(_btnIn(3, '사진 보기'));
        await tester.pumpAndSettle();
        expect(find.text(message), findsOneWidget);
        expect(tester.widget<Notice>(_noticeOf(message)).kind, NoticeKind.error);
        expect(find.byType(Image), findsNothing);
        expect(find.text('사진 불러오는 중...'), findsNothing);
      });
    }

    _test('사진 요청이 401이면 ReloginNotice("다시 로그인해 주세요" + 로그인 링크)가 보인다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 3)]))
        ..photo(3, _PhotoReply(401));
      // 401은 자동 재발급을 시도하므로 그 요청도 실패로 준비한다
      a.on('POST', '/auth/refresh', FakeReply(401, {}));
      await _open(tester, a);
      await tester.tap(_btnIn(3, '사진 보기'));
      await tester.pumpAndSettle();
      // 기대: 재발급까지 401이면 앱이 로그아웃 처리한다. 그래도 화면은 "관리자만"이 아니라 ReloginNotice를 보여야 한다.
      // 로그아웃되면 상단바에도 "로그인" 링크가 생기므로, 링크는 ReloginNotice 안에서만 센다.
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.descendant(of: find.byType(ReloginNotice), matching: find.text('로그인')), findsOneWidget);
      expect(find.text('관리자만 볼 수 있습니다'), findsNothing);
    });

    _test('재발급은 성공했는데 다시 보낸 사진 요청이 또 401이면 사진 칸에 ReloginNotice가 보인다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 3)]))
        ..photo(3, _PhotoReply(401))
        ..photo(3, _PhotoReply(401))
        ..on('POST', '/auth/refresh', FakeReply(200, adminUser()));
      await _open(tester, a);
      await tester.tap(_btnIn(3, '사진 보기'));
      await tester.pumpAndSettle();
      expect(a.callsTo('GET', _photoPath(3)).length, 2);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(find.text('로그인'), findsOneWidget);
      expect(_textIn(3, '다시 로그인해 주세요'), findsOneWidget); // 안내는 줄 안(사진 칸)에만 한 번
      expect(_btnIn(3, '사진 접기'), findsOneWidget); // 줄은 그대로 남아 있다
    });

    _test('사진이 PNG가 아닌 깨진 바이트면 "사진을 표시할 수 없어요"가 보인다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 3)]))
        ..photo(3, _PhotoReply(200, bytes: Uint8List.fromList([1, 2, 3, 4, 5])));
      await _open(tester, a);
      await tester.tap(_btnIn(3, '사진 보기'));
      await tester.pump();
      await tester.pump();
      await _settleImage(tester);
      expect(find.text('사진을 표시할 수 없어요'), findsOneWidget);
    });

    _test('접은 뒤 다시 펼치면, 먼저 보낸 요청1이 늦게 도착해도 버려지고 요청2를 기다린다', (tester) async {
      final gate1 = Completer<void>();
      final gate2 = Completer<void>();
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..on('GET', _list, FakeReply(200, [_req(id: 4)])) // 사진이 404면 목록을 다시 읽는다
        ..photo(4, _PhotoReply(200, bytes: _png, gate: gate1))
        ..photo(4, _PhotoReply(404, gate: gate2));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기')); // 요청1
      await tester.pump();
      // 요청1이 가짜 서버(gate1에서 대기)까지 가도록 화면을 몇 번 갱신한다. 안 그러면 취소돼 서버에 닿지도 않는다
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(a.callsTo('GET', _photoPath(4)).length, 1); // 요청1은 가짜 서버까지 갔다
      await tester.tap(_btnIn(4, '사진 접기')); // 로딩 중 접기 (요청1 취소)
      await tester.pump();
      await tester.tap(_btnIn(4, '사진 보기')); // 요청2
      await tester.pump();
      gate1.complete(); // 요청1의 PNG가 뒤늦게 도착
      await _settleImage(tester);
      expect(find.byType(Image), findsNothing);
      expect(find.text('사진 불러오는 중...'), findsOneWidget);
      gate2.complete();
      await tester.pumpAndSettle();
      expect(a.callsTo('GET', _photoPath(4)).length, 2);
      expect(find.text('사진이 없어요(이미 처리되었을 수 있어요)'), findsOneWidget);
    });

    _test('사진은 접으면 이미지 캐시에서도 사라진다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..photo(4, _PhotoReply(200, bytes: _png));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      await tester.pump();
      await tester.pump();
      await _settleImage(tester);
      expect(find.byType(Image), findsOneWidget);
      expect(imageCache.currentSize + imageCache.liveImageCount, greaterThan(0));
      await tester.tap(_btnIn(4, '사진 접기'));
      await _settleImage(tester);
      expect(find.byType(Image), findsNothing);
      expect(imageCache.currentSize, 0);
      expect(imageCache.liveImageCount, 0);
    });

    _test('사진을 펼친 채 화면을 떠나도(탭 전환) 이미지 캐시에서 사라진다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..on('GET', _list, FakeReply(200, []))
        ..photo(4, _PhotoReply(200, bytes: _png));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      await tester.pump();
      await tester.pump();
      await _settleImage(tester);
      expect(imageCache.currentSize + imageCache.liveImageCount, greaterThan(0));
      await tester.tap(find.text('승인').first); // 줄이 사라진다
      await _settleImage(tester);
      expect(imageCache.currentSize, 0);
      expect(imageCache.liveImageCount, 0);
    });

    _test('사진이 있으면 "허가서 사진" 설명이 붙고, 큰 사진도 화면 높이의 80%를 넘지 않는다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..photo(4, _PhotoReply(200, bytes: _png));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      await tester.pump();
      await tester.pump();
      await _settleImage(tester);
      expect(tester.widget<Image>(find.byType(Image)).semanticLabel, '허가서 사진');
      final box = tester.getSize(find.byType(Image));
      expect(box.height, lessThanOrEqualTo(tester.view.physicalSize.height / tester.view.devicePixelRatio * 0.8));
    });

    _test('접으면 진행 중인 사진 요청에 취소 신호가 가고, 오류 문구는 뜨지 않는다', (tester) async {
      final gate = Completer<void>();
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..photo(4, _PhotoReply(200, bytes: _png, gate: gate));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(a.callsTo('GET', _photoPath(4)).length, 1);
      expect(a.cancelSignals, 0);
      await tester.tap(_btnIn(4, '사진 접기'));
      await tester.pump();
      await tester.pump();
      expect(a.cancelSignals, 1);
      gate.complete();
      await _settleImage(tester);
      expect(find.text('사진을 불러오지 못했어요'), findsNothing);
      expect(find.text('서버에 연결할 수 없습니다'), findsNothing);
      expect(find.byType(Image), findsNothing);
      expect(tester.takeException(), isNull);
    });

    _test('사진을 부르는 중에 탭을 바꿔 줄이 사라져도 취소 신호가 가고, 오류 문구가 안 뜬다', (tester) async {
      final gate = Completer<void>();
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..on('GET', _list, FakeReply(200, []))
        ..photo(4, _PhotoReply(200, bytes: _png, gate: gate));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      for (var i = 0; i < 5; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.tap(find.text('승인').first);
      await tester.pumpAndSettle();
      expect(a.cancelSignals, 1);
      gate.complete();
      await _settleImage(tester);
      expect(find.text('사진을 불러오지 못했어요'), findsNothing);
      expect(find.text('승인된 신청이 없어요'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    _test('사진 칸에는 높이 제한(화면의 80%)이 걸려 있다. 위젯 테스트에서는 사진이 실제로 디코딩되지 않아 제한값으로 확인한다', (tester) async {
      final a = _PhotoAdapter()
        ..on('GET', _list, FakeReply(200, [_req(id: 4)]))
        ..photo(4, _PhotoReply(200, bytes: _png));
      await _open(tester, a);
      await tester.tap(_btnIn(4, '사진 보기'));
      await tester.pump();
      await tester.pump();
      await _settleImage(tester);
      final screenH = tester.view.physicalSize.height / tester.view.devicePixelRatio;
      final limits = tester
          .widgetList<ConstrainedBox>(find.ancestor(of: find.byType(Image), matching: find.byType(ConstrainedBox)))
          .map((c) => c.constraints.maxHeight)
          .where((h) => h.isFinite);
      expect(limits, contains(screenH * 0.8));
    });
  });

  group('조건5 빈 목록·목록 실패', () {
    for (final (tab, text) in [('대기', '대기 중인 신청이 없어요'), ('승인', '승인된 신청이 없어요'), ('반려', '반려된 신청이 없어요')]) {
      _test('$tab 탭이 비면 "$text"가 보인다', (tester) async {
        final a = FakeAdapter()
          ..on('GET', _list, FakeReply(200, []))
          ..on('GET', _list, FakeReply(200, []))
          ..on('GET', _list, FakeReply(200, []));
        await _open(tester, a);
        if (tab != '대기') {
          await tester.tap(find.text(tab));
          await tester.pumpAndSettle();
        }
        expect(find.text(text), findsOneWidget);
      });
    }

    _test('목록 500이면 실패 문구와 [다시 시도]가 뜨고 누르면 다시 불러온다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(500, {}))
        ..on('GET', _list, FakeReply(200, [_req(id: 1, nickname: '복구됨')]));
      await _open(tester, a);
      expect(find.text('인증 신청 목록을 불러오지 못했습니다'), findsOneWidget);
      expect(_retry(), findsOneWidget);
      await tester.tap(_retry());
      await tester.pumpAndSettle();
      expect(a.count('GET', _list), 2);
      expect(find.text('복구됨'), findsOneWidget);
      expect(_retry(), findsNothing);
    });

    _test('네트워크 오류면 "서버에 연결할 수 없습니다"와 [다시 시도]가 뜬다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply.networkError());
      await _open(tester, a);
      expect(find.text('서버에 연결할 수 없습니다'), findsOneWidget);
      expect(_retry(), findsOneWidget);
    });

    _test('목록 403이면 "관리자만 볼 수 있습니다"만 보이고 [다시 시도]는 없다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(403, {}));
      await _open(tester, a);
      expect(find.text('관리자만 볼 수 있습니다'), findsOneWidget);
      expect(_retry(), findsNothing);
    });

    _test('목록 401이면 ReloginNotice가 보이고 [다시 시도]는 없다', (tester) async {
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(401, {}))
        ..on('POST', '/auth/refresh', FakeReply(401, {}));
      await _open(tester, a);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      expect(_retry(), findsNothing);
    });

    _test('목록 401(재발급 성공 뒤 또 401)로 안내가 뜬 채 다른 탭을 누르면 안내가 사라지고 로딩이 보인다', (tester) async {
      final gate = Completer<void>();
      final a = FakeAdapter()
        ..on('GET', _list, FakeReply(401, {}))
        ..on('GET', _list, FakeReply(401, {}))
        ..on('POST', '/auth/refresh', FakeReply(200, adminUser()))
        ..on('GET', _list, FakeReply(200, [], gate));
      await _open(tester, a);
      expect(find.text('다시 로그인해 주세요'), findsOneWidget);
      await tester.tap(find.text('승인').first);
      await tester.pump();
      expect(find.text('다시 로그인해 주세요'), findsNothing);
      expect(find.byType(Loading), findsOneWidget);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('승인된 신청이 없어요'), findsOneWidget);
    });

    _test('목록 요청에도 receiveTimeout이 걸린다', (tester) async {
      final a = FakeAdapter()..on('GET', _list, FakeReply(200, []));
      await _open(tester, a);
      expect(a.callsTo('GET', _list).single.receiveTimeout, isNotNull);
    });
  });
}
