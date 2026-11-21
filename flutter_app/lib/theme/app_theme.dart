import 'package:material_ui/material_ui.dart';

import 'app_colors.dart';

// 전환 효과 없이 새 화면을 그대로 보여 주는 빌더
class _NoTransitionBuilder extends PageTransitionsBuilder {
  const _NoTransitionBuilder();

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) => child;
}

// 앱 전체 테마: 밝은 테마 (여권 남색 + 노랑. Material 3 기본 모양에 색·폭·크기만 React 디자인에 맞춘다)
ThemeData buildAppTheme() {
  // 색 토큰을 Material 색 역할에 하나씩 꽂는다
  const scheme = ColorScheme.light(
    primary: AppColors.accent,
    onPrimary: AppColors.paper, // 파랑 바탕 위엔 흰 글자
    primaryContainer: AppColors.accentSoft,
    onPrimaryContainer: AppColors.accentInk,
    secondary: AppColors.accent,
    onSecondary: AppColors.paper,
    surface: AppColors.paper,
    onSurface: AppColors.ink,
    onSurfaceVariant: AppColors.muted,
    surfaceContainerHighest: AppColors.hover,
    outline: AppColors.fieldBorder, // 입력칸·메뉴 테두리
    outlineVariant: AppColors.line,
    error: AppColors.danger,
    onError: AppColors.paper, // 빨강 바탕 위엔 흰 글자
    errorContainer: AppColors.dangerSoft,
    onErrorContainer: AppColors.danger,
  );

  const ink = AppColors.ink;
  // 글자 크기 13/14/15/17/22
  const textTheme = TextTheme(
    bodyLarge: TextStyle(fontSize: AppSizes.fsBody, color: ink),
    bodyMedium: TextStyle(fontSize: AppSizes.fsBody, color: ink),
    bodySmall: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.muted),
    labelLarge: TextStyle(fontSize: AppSizes.fsSmall2, color: ink),
    labelMedium: TextStyle(fontSize: AppSizes.fsSmall, color: ink),
    titleMedium: TextStyle(fontSize: AppSizes.fsRow, fontWeight: FontWeight.w700, color: ink),
    titleLarge: TextStyle(fontSize: AppSizes.fsTitle, fontWeight: FontWeight.w700, color: ink),
  );

  final btnShape = RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(AppSizes.radiusBtn),
  );
  const btnText = TextStyle(fontSize: AppSizes.fsSmall2);
  const btnPadding = EdgeInsets.symmetric(horizontal: AppSizes.space4);

  OutlineInputBorder inputBorder(Color color) => OutlineInputBorder(
    borderRadius: BorderRadius.circular(AppSizes.radiusPanel),
    borderSide: BorderSide(color: color),
  );

  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.canvas,
    // 모든 OS에서 같은 글꼴(번들한 IBM Plex Sans KR, pubspec에서 'Roboto' 이름으로 등록)을 쓰게 못 박는다.
    fontFamily: 'Roboto',
    textTheme: textTheme,
    // 화면 전환 애니메이션 없음(React처럼 바로 바뀐다). 상단바가 본문 틀(PageFrame) 안에 들어 있어
    // 전환 효과가 있으면 상단바까지 같이 번쩍이기 때문이다.
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: _NoTransitionBuilder(),
        TargetPlatform.iOS: _NoTransitionBuilder(),
        TargetPlatform.linux: _NoTransitionBuilder(),
        TargetPlatform.macOS: _NoTransitionBuilder(),
        TargetPlatform.windows: _NoTransitionBuilder(),
        TargetPlatform.fuchsia: _NoTransitionBuilder(),
      },
    ),
    // 주요 버튼: 남색(ink) 바탕 + 흰 글자, 높이 40, 모서리 6
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.ink,
        foregroundColor: AppColors.paper,
        disabledBackgroundColor: AppColors.ink.withValues(alpha: 0.6), // 흰 글자와 3:1 이상 (color_contrast_test)
        disabledForegroundColor: AppColors.paper, // 비활성이어도 글자는 흰색 그대로 (읽히게)
        minimumSize: const Size(0, 40),
        padding: btnPadding,
        shape: btnShape,
        textStyle: btnText,
      ),
    ),
    // 보통 버튼: 선만 있는 모양
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: AppColors.ink,
        side: const BorderSide(color: AppColors.fieldBorder), // 흰 바탕에서 버튼 테두리가 보이게
        minimumSize: const Size(0, 40),
        padding: btnPadding,
        shape: btnShape,
        textStyle: btnText,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.accent,
        minimumSize: const Size(0, 40),
        padding: btnPadding,
        shape: btnShape,
        textStyle: btnText,
      ),
    ),
    // 입력칸: 높이 40 이상, 모서리 8, 테두리 fieldBorder(흰 바탕에서 보이는 진하기), 포커스는 포인트
    inputDecorationTheme: InputDecorationThemeData(
      filled: true,
      fillColor: AppColors.paper,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(
        horizontal: AppSizes.space3,
        vertical: AppSizes.space3,
      ),
      constraints: const BoxConstraints(minHeight: 40),
      border: inputBorder(AppColors.fieldBorder),
      enabledBorder: inputBorder(AppColors.fieldBorder),
      focusedBorder: inputBorder(AppColors.accent),
      labelStyle: const TextStyle(fontSize: AppSizes.fsSmall2, color: AppColors.muted),
      floatingLabelStyle: const TextStyle(fontSize: AppSizes.fsSmall2, color: AppColors.accentInk),
    ),
    tooltipTheme: const TooltipThemeData(
      textStyle: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.paper), // 남색 상자 + 흰 글자 (흰 판 위에서도 보이게)
      decoration: BoxDecoration(
        color: AppColors.ink,
        borderRadius: BorderRadius.all(Radius.circular(AppSizes.radiusBtn)),
      ),
    ),
  );
}
