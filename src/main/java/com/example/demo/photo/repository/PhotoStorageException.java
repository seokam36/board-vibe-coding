package com.example.demo.photo.repository;

// 사진 창고(PhotoStorage)가 파일을 저장·읽기·삭제하지 못했을 때 던지는 공통 예외.
// 구현이 로컬 디스크(IOException)든 S3(SDK 예외)든 호출하는 쪽은 이것 하나만 알면 된다.
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
