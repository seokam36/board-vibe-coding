// 앱용 바깥 링크 열기(AppExternalLinkOpener) 테스트: 진짜 브라우저 대신 가짜 "여는 함수"를 끼운다
import 'dart:async';

import 'package:board_web/common/app_external_link.dart';
import 'package:board_web/common/app_external_link_io.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('https 주소는 여는 함수를 그 주소로 정확히 1번 부른다', () async {
    final opened = <Uri>[];
    final opener = AppExternalLinkOpener(launch: (uri) async {
      opened.add(uri);
      return true;
    });

    opener.open('https://www.hikorea.go.kr/');
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(opened, [Uri.parse('https://www.hikorea.go.kr/')]);
  });

  test('대문자 HTTPS도 열린다 (주소 해석이 scheme을 소문자로 바꿔 줌)', () async {
    var count = 0;
    final opener = AppExternalLinkOpener(launch: (uri) async {
      count++;
      return true;
    });

    opener.open('HTTPS://WWW.HIKOREA.GO.KR/');
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(count, 1);
  });

  test('http·https가 아닌 주소·빈 글자·깨진 주소는 열기 0번', () async {
    var count = 0;
    final opener = AppExternalLinkOpener(launch: (uri) async {
      count++;
      return true;
    });

    for (final url in ['javascript:alert(1)', '', 'ftp://x', 'not a url', 'mailto:a@b', 'http://[bad']) {
      opener.open(url);
    }
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(count, 0);
  });

  test('여는 함수가 예외·늦은 오류·false를 내도 밖(zone)으로 새지 않는다', () async {
    final leaked = <Object>[];
    await runZonedGuarded(() async {
      AppExternalLinkOpener(launch: (uri) async => throw StateError('x')).open('https://a.com');
      AppExternalLinkOpener(launch: (uri) => Future<bool>.error('late')).open('https://a.com');
      AppExternalLinkOpener(launch: (uri) async => false).open('https://a.com');
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }, (e, s) => leaked.add(e));

    expect(leaked, isEmpty);
  });

  test('앱(VM)에서 입구 함수는 url_launcher를 쓰는 앱용 도구를 돌려준다', () {
    // main.dart가 앱 갈래에서 끼우는 도구가 아무것도 안 하는 stub이 아닌지 확인
    expect(createAppExternalLinkOpener(), isA<AppExternalLinkOpener>());
  });
}
