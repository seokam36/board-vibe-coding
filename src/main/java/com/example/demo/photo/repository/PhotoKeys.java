package com.example.demo.photo.repository;

// 사진 key(창고 안의 이름)가 안전한지 검사하는 공용 규칙. 로컬·S3 구현이 같은 코드를 써서 규칙이 어긋나지 않게 한다.
// 안전하지 않은 key는 호출하는 쪽의 버그라서 IllegalArgumentException으로 알린다 (PhotoStorage 약속).
final class PhotoKeys {

    private PhotoKeys() {
    }

    // 통과하면 그대로 돌려주고, 이상하면 IllegalArgumentException
    static String requireSafe(String key) {
        if (key == null || key.isBlank()
                || key.indexOf('\\') >= 0  // 역슬래시(윈도우식 경로)
                || key.indexOf(':') >= 0    // C: 같은 드라이브 지정
                || key.indexOf('\0') >= 0   // 널 문자
                || key.startsWith("/")) {   // 절대경로
            throw new IllegalArgumentException("사진 key가 올바르지 않습니다");
        }
        // "." ".." 빈 칸 조각("a//b", 끝 "/")이 있으면 폴더 밖으로 나가거나 이름이 모호해지므로 거부
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("사진 key가 올바르지 않습니다");
            }
        }
        return key;
    }
}
