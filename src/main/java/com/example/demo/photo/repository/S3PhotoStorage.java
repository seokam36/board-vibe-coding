package com.example.demo.photo.repository;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// 사진을 AWS S3 버킷에 저장하는 구현 (S13-g6-3). app.photo.storage=s3 일 때만 빈으로 등록된다.
// 버킷이 "사진 폴더", key가 그 안의 파일 이름이다. 약속(PhotoStorage)은 로컬 구현과 똑같이 지킨다.
@Repository
@ConditionalOnProperty(name = "app.photo.storage", havingValue = "s3")
public class S3PhotoStorage implements PhotoStorage {

    private final S3Client s3;
    private final String bucket;

    // 버킷 이름은 설정(환경변수 PHOTO_S3_BUCKET)에서 받는다. 비었는지는 S3ClientConfig가 시작할 때 검사한다.
    // 테스트는 가짜 S3Client를 끼워 이 생성자를 직접 쓴다.
    public S3PhotoStorage(S3Client s3, @Value("${app.photo.s3.bucket}") String bucket) {
        this.s3 = s3;
        // S3ClientConfig가 HeadBucket에 쓴 값(trim한 이름)과 똑같이 맞춘다: 공백이 섞여도 시작 검사와 실제 요청이 같은 이름을 쓰게
        this.bucket = bucket.trim();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        PhotoKeys.requireSafe(key);
        try {
            // contentType 꼬리표를 함께 달아 둔다(우리 서비스는 DB 값을 쓰지만, S3 콘솔에서 보기 편하도록)
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new PhotoStorageException("사진 파일을 저장하지 못했습니다", e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        PhotoKeys.requireSafe(key);
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(getRequest(key));
            return Optional.of(bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new PhotoStorageException("사진 파일을 읽지 못했습니다", e);
        }
    }

    // 스트림으로 연다. 이 스트림이 닫히거나 끝까지 읽힐 때까지 S3 연결 1개를 붙잡으므로 받은 쪽이 꼭 닫아야 한다.
    @Override
    public Optional<StoredPhoto> open(String key) {
        PhotoKeys.requireSafe(key);
        ResponseInputStream<GetObjectResponse> in = null;
        try {
            in = s3.getObject(getRequest(key));
            Long length = in.response().contentLength();
            if (length == null) {
                // S3는 항상 길이를 주지만, 혹시 없으면 Content-Length 응답을 만들 수 없으니 실패로 본다
                abortQuietly(in);
                throw new PhotoStorageException("사진 길이를 알 수 없습니다", null);
            }
            return Optional.of(new StoredPhoto(in, length));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            abortQuietly(in); // 연결을 풀에 돌려준다
            throw new PhotoStorageException("사진 파일을 열지 못했습니다", e);
        }
    }

    // S3는 없는 key를 지워도 성공으로 답한다(예외 없음) -> "이미 없어도 예외 없음" 약속과 같다
    @Override
    public void delete(String key) {
        PhotoKeys.requireSafe(key);
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            // 혹시 없다고 답해도 목표(없는 상태)는 이뤄졌으니 무시
        } catch (SdkException e) {
            throw new PhotoStorageException("사진 파일을 지우지 못했습니다", e);
        }
    }

    private GetObjectRequest getRequest(String key) {
        return GetObjectRequest.builder().bucket(bucket).key(key).build();
    }

    // 읽다 만 스트림은 close 대신 abort로 연결을 버려 남은 본문을 다 받지 않게 한다
    private static void abortQuietly(ResponseInputStream<?> in) {
        if (in != null) {
            in.abort();
        }
    }
}
