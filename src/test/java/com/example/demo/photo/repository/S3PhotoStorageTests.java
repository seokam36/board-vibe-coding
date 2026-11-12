package com.example.demo.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// 진짜 S3 없이 가짜 S3Client(Mockito)로 S3PhotoStorage가 약속(PhotoStorage)을 지키는지 본다.
// 버킷 이름은 가짜(example-bucket)다.
class S3PhotoStorageTests {

    private static final String BUCKET = "example-bucket";

    private final S3Client s3 = mock(S3Client.class);
    private final S3PhotoStorage storage = new S3PhotoStorage(s3, BUCKET);

    @Test
    void put은_버킷_key_contentType_바이트를_S3에_넘긴다() throws Exception {
        byte[] data = {1, 2, 3};

        storage.put("market/1/a.jpg", data, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(req.capture(), body.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("market/1/a.jpg");
        assertThat(req.getValue().contentType()).isEqualTo("image/jpeg");
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(data);
    }

    @Test
    void put_실패는_PhotoStorageException으로_감싼다() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("연결 실패"));

        assertThatThrownBy(() -> storage.put("a.png", new byte[] {1}, "image/png"))
                .isInstanceOf(PhotoStorageException.class)
                .hasCauseInstanceOf(SdkClientException.class);
    }

    @Test
    void get은_S3가_준_바이트를_돌려준다() {
        byte[] data = {9, 8, 7};
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), data));

        assertThat(storage.get("a.png")).hasValueSatisfying(b -> assertThat(b).isEqualTo(data));

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("a.png");
    }

    @Test
    void get은_없는_key면_빈_값이고_다른_오류는_감싼다() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        assertThat(storage.get("없음.png")).isEmpty();

        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(SdkClientException.create("타임아웃"));
        assertThatThrownBy(() -> storage.get("a.png")).isInstanceOf(PhotoStorageException.class);
    }

    @Test
    void open은_스트림과_길이를_돌려준다() throws Exception {
        byte[] data = new byte[1000];
        new java.util.Random(3).nextBytes(data);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response(data, data.length));

        StoredPhoto stored = storage.open("market/1/a.jpg").orElseThrow();
        try (var in = stored.stream()) {
            assertThat(stored.length()).isEqualTo(1000);
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("market/1/a.jpg");
    }

    @Test
    void open은_없는_key면_빈_값이고_다른_오류는_감싼다() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertThat(storage.open("없음.png")).isEmpty();

        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("연결 실패"));
        assertThatThrownBy(() -> storage.open("a.png")).isInstanceOf(PhotoStorageException.class);
    }

    @Test
    void open은_길이를_모르면_실패로_보고_스트림을_abort한다() {
        AtomicBoolean aborted = new AtomicBoolean(false);
        ResponseInputStream<GetObjectResponse> noLength = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[] {1}), () -> aborted.set(true)));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(noLength);

        assertThatThrownBy(() -> storage.open("a.png")).isInstanceOf(PhotoStorageException.class);
        // 연결을 풀에 돌려주려고 abort가 불려야 한다 (abortQuietly를 지우면 여기서 실패)
        assertThat(aborted).isTrue();
    }

    @Test
    void open에서_SDK_예외가_나면_이미_받은_스트림이_있어도_abort_경로가_안전하다() {
        // getObject 자체가 던지면 abort할 스트림이 없다(null) -> NPE 없이 PhotoStorageException으로만 감싸야 한다
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("연결 실패"));

        assertThatThrownBy(() -> storage.open("a.png"))
                .isInstanceOf(PhotoStorageException.class)
                .hasCauseInstanceOf(SdkClientException.class);
    }

    @Test
    void open에서_길이를_읽다_SDK_예외가_나면_스트림을_abort한다() {
        // response()를 부르면 SdkClientException이 나는 가짜 스트림: 스트림을 받은 뒤에 SDK 예외가 나는 경로를 흉내 낸다
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> broken = mock(ResponseInputStream.class);
        when(broken.response()).thenThrow(SdkClientException.create("응답 해석 실패"));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(broken);

        assertThatThrownBy(() -> storage.open("a.png")).isInstanceOf(PhotoStorageException.class);
        verify(broken).abort(); // abortQuietly를 지우면 여기서 실패
    }

    @Test
    void 버킷_이름에_공백이_섞여도_요청에는_잘린_이름이_쓰인다() {
        S3PhotoStorage spaced = new S3PhotoStorage(s3, "  example-bucket ");

        spaced.put("a.png", new byte[] {1}, "image/png");
        spaced.delete("a.png");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("example-bucket");
        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(del.capture());
        assertThat(del.getValue().bucket()).isEqualTo("example-bucket");
    }

    @Test
    void delete는_버킷과_key로_지우고_없다고_해도_예외_없다() {
        storage.delete("market/1/a.jpg");

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(req.getValue().key()).isEqualTo("market/1/a.jpg");

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        storage.delete("없음.png"); // 예외가 나면 테스트 실패
    }

    @Test
    void delete_실패는_PhotoStorageException으로_감싼다() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("연결 실패"));

        assertThatThrownBy(() -> storage.delete("a.png")).isInstanceOf(PhotoStorageException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../evil.txt", "a/../../evil.txt", "/etc/passwd", "..\\evil.txt", "a\\b.png",
            "C:/evil.txt", "..", ".", "a\0b.png", "a//b.png", "a/"})
    void 나쁜_key는_S3를_부르기_전에_거부한다(String key) {
        assertThatThrownBy(() -> storage.put(key, new byte[] {1}, "image/png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3);
    }

    private static ResponseInputStream<GetObjectResponse> response(byte[] data, long length) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(data)));
    }
}
