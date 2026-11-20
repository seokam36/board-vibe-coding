// 앱용: url_launcher로 기기의 기본 브라우저를 열어 바깥 주소를 보여 준다.
import 'package:url_launcher/url_launcher.dart';

import 'external_link.dart';

// 주소를 여는 함수의 모양. 테스트에서는 진짜 브라우저 대신 가짜 함수를 끼운다
typedef LaunchFunction = Future<bool> Function(Uri uri);

// 기본값: 앱 안이 아니라 기기의 바깥 앱(브라우저)으로 연다.
// canLaunchUrl은 안 쓴다(쓰면 Android 11+에서 매니페스트에 <queries>를 따로 적어야 한다)
Future<bool> _defaultLaunch(Uri uri) => launchUrl(uri, mode: LaunchMode.externalApplication);

class AppExternalLinkOpener implements ExternalLinkOpener {
  // 부를 때 이름은 launch: (밑줄 없이). 안 넣으면 진짜 브라우저로 여는 _defaultLaunch를 쓴다
  AppExternalLinkOpener({this._launch = _defaultLaunch});

  final LaunchFunction _launch;

  @override
  void open(String url) {
    // http·https 주소만 연다 (javascript:, 빈 글자, 깨진 주소는 조용히 무시)
    final uri = Uri.tryParse(url);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return;
    }
    // open은 값을 돌려주지 않는 void라서, 여는 일은 뒤에서 따로 처리한다
    _launchQuietly(uri);
  }

  // 열기에 실패(false)하거나 예외가 나도 화면이 깨지면 안 되니 조용히 넘어간다 (주소·오류는 로그에 안 남긴다)
  Future<void> _launchQuietly(Uri uri) async {
    try {
      await _launch(uri);
    } catch (_) {
      // 일부러 무시
    }
  }
}

ExternalLinkOpener createPlatformExternalLinkOpener() => AppExternalLinkOpener();
