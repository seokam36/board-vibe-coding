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
        // get은 바이트만 돌려준다: content-type은 DB 값만 믿는 약속이다
        assertThat(stored.get()).isEqualTo(jpegLike);

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

        assertThat(storage.get("a.png").orElseThrow()).containsExactly(2, 3);
    }

    @Test
    void 파일_읽기_쓰기_실패는_PhotoStorageException으로_감싸진다() throws Exception {
        LocalPhotoStorage storage = storage();
        // 폴더가 있는 자리를 파일 키로 쓰면 읽기·쓰기가 IOException으로 실패한다 (구현이 IOException을 그대로 내보내지 않는지 확인)
        storage.put("dir/a.png", new byte[] {1}, "image/png");
        // 파일을 "폴더 자리"로 쓰려는 키: dir/a.png 아래에 또 만들 수 없다
        assertThatThrownBy(() -> storage.put("dir/a.png/b.png", new byte[] {1}, "image/png"))
                .isInstanceOf(PhotoStorageException.class);
        // 폴더를 파일처럼 읽으려는 키
        assertThatThrownBy(() -> storage.get("dir")).isInstanceOf(PhotoStorageException.class);
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

    // S13-g6-2 완료 조건 5: open은 스트림과 길이를 돌려준다
    @Test
    void open은_있는_키의_길이와_내용이_파일과_일치한다() throws Exception {
        LocalPhotoStorage storage = storage();
        byte[] data = new byte[100_000];
        new java.util.Random(7).nextBytes(data);
        storage.put("market/1/open.jpg", data, "image/jpeg");

        StoredPhoto stored = storage.open("market/1/open.jpg").orElseThrow();
        try (var in = stored.stream()) {
            assertThat(stored.length()).isEqualTo(data.length);
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void open은_없는_키면_빈_값이다() {
        assertThat(storage().open("없음/none.png")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../evil.txt", "a/../../evil.txt", "/etc/passwd", "..\\evil.txt", "C:/evil.txt", ".."})
    void open은_폴더_밖으로_나가는_키를_거부한다(String key) {
        assertThatThrownBy(() -> storage().open(key)).isInstanceOf(IllegalArgumentException.class);
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
