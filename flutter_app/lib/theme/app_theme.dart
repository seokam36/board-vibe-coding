import 'package:material_ui/material_ui.dart';

import 'app_colors.dart';

// 앱 전체 테마: 항상 어두운 청자 계열 (Material 3 기본 모양에 색·폭·크기만 React 디자인에 맞춘다)
ThemeData buildAppTheme() {
  // 색 토큰을 Material 색 역할에 하나씩 꽂는다
  const scheme = ColorScheme.dark(
    primary: AppColors.celadon,
    onPrimary: AppColors.canvas, // 밝은 청자 바탕 위엔 어두운 글자
    primaryContainer: AppColors.celadonSoft,
    onPrimaryContainer: AppColors.celadonInk,
    secondary: AppColors.celadon,
    onSecondary: AppColors.canvas,
    surface: AppColors.paper,
    onSurface: AppColors.ink,
    onSurfaceVariant: AppColors.muted,
    surfaceContainerHighest: AppColors.hover,
    outline: AppColors.line,
    outlineVariant: AppColors.line,
    error: AppColors.danger,
    onError: AppColors.canvas,
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
    brightness: Brightness.dark,
    colorScheme: scheme,
    scaffoldBackgroundColor: AppColors.canvas,
    // 모든 OS에서 같은 글꼴(번들한 IBM Plex Sans KR, pubspec에서 'Roboto' 이름으로 등록)을 쓰게 못 박는다.
    fontFamily: 'Roboto',
    textTheme: textTheme,
    // 주요 버튼: 청자 바탕 + 어두운 글자, 높이 40, 모서리 6
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: AppColors.celadon,
        foregroundColor: AppColors.canvas,
        disabledBackgroundColor: AppColors.celadon.withValues(alpha: 0.5),
        disabledForegroundColor: AppColors.canvas.withValues(alpha: 0.7),
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
        side: const BorderSide(color: AppColors.line),
        minimumSize: const Size(0, 40),
        padding: btnPadding,
        shape: btnShape,
        textStyle: btnText,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: AppColors.celadon,
        minimumSize: const Size(0, 40),
        padding: btnPadding,
        shape: btnShape,
        textStyle: btnText,
      ),
    ),
    // 입력칸: 높이 40 이상, 모서리 8, 테두리 line, 포커스는 청자
    inputDecorationTheme: InputDecorationThemeData(
      filled: true,
      fillColor: AppColors.paper,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(
        horizontal: AppSizes.space3,
        vertical: AppSizes.space3,
      ),
      constraints: const BoxConstraints(minHeight: 40),
      border: inputBorder(AppColors.line),
      enabledBorder: inputBorder(AppColors.line),
      focusedBorder: inputBorder(AppColors.celadon),
      labelStyle: const TextStyle(fontSize: AppSizes.fsSmall2, color: AppColors.muted),
      floatingLabelStyle: const TextStyle(fontSize: AppSizes.fsSmall2, color: AppColors.celadonInk),
    ),
    tooltipTheme: const TooltipThemeData(
      textStyle: TextStyle(fontSize: AppSizes.fsSmall, color: AppColors.ink),
      decoration: BoxDecoration(
        color: AppColors.hover,
        borderRadius: BorderRadius.all(Radius.circular(AppSizes.radiusBtn)),
      ),
    ),
  );
}
