// 쪽지 화면에 쓰는 글자 (React의 messageText.ts)

// 쪽지함 행 배지의 글자: 1~99는 그 숫자, 100 이상은 "99+", 0 이하는 빈 글자 (배지를 안 그린다)
String formatUnreadBadge(int count) {
  if (count <= 0) return '';
  return count > 99 ? '99+' : '$count';
}

// 서버가 최근 200통만 줬을 때(hasMore) 목록 위에 보여 주는 안내
const messagesTruncated = '이전 쪽지는 표시되지 않아요';
