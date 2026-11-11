package com.example.demo.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// 스프링 없이 임시 폴더(@TempDir)에서 LocalPhotoStorage만 검사한다
class LocalPhotoStorageTests {

    @TempDir
    Path tempDir;

    private LocalPhotoStorage storage() {
        return new LocalPhotoStorage(tempDir.toString());
    }

    @Test
    void 저장_조회_삭제_왕복() {
        LocalPhotoStorage storage = storage();
        byte[] jpegLike = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

        storage.put("market/1/a.jpg", jpegLike, "image/jpeg");

        var stored = storage.get("market/1/a.jpg");
        assertThat(stored).isPresent();
        assertThat(stored.get().bytes()).isEqualTo(jpegLike);
        assertThat(stored.get().contentType()).isEqualTo("image/jpeg");

        storage.delete("market/1/a.jpg");
        assertThat(storage.get("market/1/a.jpg")).isEmpty();
    }

    @Test
    void 없는_키_조회는_빈_값이고_삭제는_예외_없이_지나간다() {
        LocalPhotoStorage storage = storage();

        assertThat(storage.get("없음.png")).isEmpty();
        storage.delete("없음.png"); // 예외가 나면 테스트 실패
    }

    @Test
    void 같은_키로_다시_저장하면_덮어쓴다() {
        LocalPhotoStorage storage = storage();
        storage.put("a.png", new byte[] {1}, "image/png");
        storage.put("a.png", new byte[] {2, 3}, "image/png");

        assertThat(storage.get("a.png").orElseThrow().bytes()).containsExactly(2, 3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../evil.txt", "a/../../evil.txt", "/etc/passwd", "..\\evil.txt", "a\\b.png",
            "C:/evil.txt", "C:\\evil.txt", "..", ".", "a\0b.png"})
    void 폴더_밖으로_나가거나_이상한_키는_거부한다(String key) {
        LocalPhotoStorage storage = storage();

        assertThatThrownBy(() -> storage.put(key, new byte[] {1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 거부된_키로는_폴더_밖에_파일이_만들어지지_않는다() throws Exception {
        Path root = tempDir.resolve("root");
        LocalPhotoStorage storage = new LocalPhotoStorage(root.toString());

        assertThatThrownBy(() -> storage.put("../evil.txt", new byte[] {1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.exists(tempDir.resolve("evil.txt"))).isFalse();
    }
}
