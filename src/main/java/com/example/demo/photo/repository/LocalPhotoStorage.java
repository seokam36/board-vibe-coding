package com.example.demo.photo.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

// 사진을 내 컴퓨터 폴더에 파일로 저장하는 구현 (개발·테스트용, S13-g6 전까지 유일한 구현).
// key가 곧 폴더 안의 파일 경로다. 폴더 밖으로 나가는 key는 거부한다.
@Repository
public class LocalPhotoStorage implements PhotoStorage {

    private final Path root;

    // app.photo.local-dir 설정으로 폴더를 바꿀 수 있고, 기본값은 ./uploads
    public LocalPhotoStorage(@Value("${app.photo.local-dir:./uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        // contentType은 따로 저장하지 않는다: get()에서 파일 맨 앞 바이트(JPEG/PNG)로 다시 알아낸다
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 파일을 저장하지 못했습니다", e);
        }
    }

    @Override
    public Optional<StoredPhoto> get(String key) {
        Path path = resolve(key);
        try {
            byte[] bytes = Files.readAllBytes(path);
            return Optional.of(new StoredPhoto(bytes, guessContentType(bytes)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("사진 파일을 읽지 못했습니다", e);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key);
        try {
            Files.deleteIfExists(path); // 없어도 예외 없이 지나간다
        } catch (IOException e) {
            throw new UncheckedIOException("사진 파일을 지우지 못했습니다", e);
        }
    }

    // key를 안전한 실제 경로로 바꾼다. 폴더 밖으로 나가는 key는 IllegalArgumentException.
    private Path resolve(String key) {
        if (key == null || key.isBlank()
                || key.indexOf('\\') >= 0   // 역슬래시(윈도우식 경로)
                || key.indexOf(':') >= 0    // C: 같은 드라이브 지정
                || key.indexOf('\0') >= 0   // 널 문자
                || key.startsWith("/")) {   // 절대경로
            throw new IllegalArgumentException("사진 key가 올바르지 않습니다");
        }
        try {
            Path path = root.resolve(key).normalize();
            // ../ 로 root 밖에 나가거나 root 자체를 가리키면 거부
            if (!path.startsWith(root) || path.equals(root)) {
                throw new IllegalArgumentException("사진 key가 올바르지 않습니다");
            }
            return path;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("사진 key가 올바르지 않습니다", e);
        }
    }

    private String guessContentType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
