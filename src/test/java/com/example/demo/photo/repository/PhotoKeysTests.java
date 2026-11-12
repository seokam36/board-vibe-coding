package com.example.demo.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

// 완료 조건 3·4: key 검증이 Local과 S3에서 같은 규칙(PhotoKeys)인지, 서비스가 만드는 key 모양이 통과하는지,
// open이 연 스트림을 닫으면 S3 응답 스트림도 닫히는지 확인한다.
class PhotoKeysTests {

    @TempDir
    Path tempDir;

    @Test
    void 서비스가_만드는_key_모양은_검증을_통과한다() {
        // MarketItemPhotoService: "market/" + itemId + "/" + UUID + (".jpg"|".png" 또는 "_thumb.jpg"|"_thumb.png")
        String base = "market/" + 123L + "/" + UUID.randomUUID();
        for (String key : new String[] {base + ".jpg", base + ".png", base + "_thumb.jpg", base + "_thumb.png"}) {
            assertThat(PhotoKeys.requireSafe(key)).isEqualTo(key);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../evil.txt", "a/../../evil.txt", "/etc/passwd", "..\\evil.txt", "a\\b.png",
            "C:/evil.txt", "..", ".", "a\0b.png", "a//b.png", "a/", "a/./b.png"})
    void 나쁜_key는_Local과_S3_모두_같은_예외로_거부한다(String key) {
        S3Client s3 = mock(S3Client.class);
        LocalPhotoStorage local = new LocalPhotoStorage(tempDir.toString());
        S3PhotoStorage s3Storage = new S3PhotoStorage(s3, "example-bucket");

        for (PhotoStorage storage : new PhotoStorage[] {local, s3Storage}) {
            assertThatThrownBy(() -> storage.put(key, new byte[] {1}, "image/png"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void open이_연_스트림을_닫으면_S3_응답_스트림도_닫힌다() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream raw = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(3L).build(), AbortableInputStream.create(raw)));

        StoredPhoto stored = new S3PhotoStorage(s3, "example-bucket").open("a.png").orElseThrow();
        assertThat(closed).isFalse();
        stored.stream().close();

        assertThat(closed).as("받은 스트림을 닫으면 S3 응답 스트림도 닫혀야 한다").isTrue();
    }
}
