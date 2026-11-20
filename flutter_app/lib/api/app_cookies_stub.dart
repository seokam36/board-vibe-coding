// 웹·기본용: 브라우저가 쿠키를 다루니까 추가 인터셉터가 필요 없다
import 'package:dio/dio.dart';

Future<List<Interceptor>> createPlatformCookieInterceptors() async => [];

// 메모리 대체도 웹에서는 필요 없다
Future<List<Interceptor>> createMemoryCookieInterceptors() async => [];
