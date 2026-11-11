package com.example.demo.photo.service;

// 업로드된 사진을 받아들일 수 없을 때 던지는 예외. 이유(Reason)로 구분한다.
// 나중(S13-g2)에 서비스/컨트롤러에서 400으로 바꾼다. 지금은 던지기만 한다.
public class InvalidImageException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_FORMAT, // JPEG·PNG가 아님 (HEIC·WebP·GIF·가짜 파일 포함)
        TOO_LARGE,          // 픽셀 수가 상한을 넘음 (압축 폭탄 방지)
        CORRUPTED           // 형식은 맞는데 열리지 않는 깨진 파일
    }

    private final Reason reason;

    public InvalidImageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public InvalidImageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
