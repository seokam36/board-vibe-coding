package com.example.demo.market.service;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.common.ApiErrors;
import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.MarketItemPhoto;
import com.example.demo.market.dto.MarketItemPhotoContent;
import com.example.demo.market.dto.MarketItemPhotoResponse;
import com.example.demo.market.repository.MarketItemPhotoRepository;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.service.ImageProcessor;
import com.example.demo.photo.service.InvalidImageException;
import com.example.demo.photo.service.ProcessedImage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 중고 글 사진 올리기 (S13-g2). 흐름(트랜잭션은 맨 마지막 짧은 구간에만 건다):
//   1) 싼 확인: 글 있음 404 -> 본인 403 -> 거래완료 409 -> 이미 10장 409   (무거운 처리 전에 거절. 잠금 없이 세는 미리보기)
//   2) 이미지 처리(검사·축소·재인코딩): 동시에 2장까지만, 기다리다 시간이 지나면 503. 파일 바이트도 자리를 얻은 뒤에야 읽는다
//   3) 창고에 본 사진·썸네일 저장
//   4) 짧은 트랜잭션: 글 행을 FOR UPDATE로 잠그고 1)을 다시 확인 + 10장 검사 + 사진 행 INSERT
//   3)~4)가 실패하면 창고에 넣은 파일 2개를 지운다.
// 이미지 처리와 창고 저장은 오래 걸려서 트랜잭션(= DB 연결 점유) 밖에서 한다. 연결 풀이 3개뿐이라 특히 중요하다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketItemPhotoService {

    static final int MAX_PHOTOS_PER_ITEM = 10; // 글당 최대 사진 수

    private final MarketItemRepository marketItemRepository;
    private final MarketItemPhotoRepository photoRepository;
    private final ImageProcessor imageProcessor;
    private final PhotoStorage photoStorage;
    private final PlatformTransactionManager txManager;

    // 이미지 처리 동시 2장 제한. 사진 하나를 처리하는 동안 메모리를 많이 쓰므로(디코딩된 픽셀) 작은 서버가 버티게 한다.
    // 대기는 최대 5초: 처리 한 장이 보통 1초 안쪽이라 몇 장 밀려도 기다릴 만하고, 그보다 길게 붙잡으면 요청 스레드만 쌓인다.
    // 시간이 지나면 503(서버가 지금 바쁨, 잠시 뒤 다시)으로 돌려준다.
    // (테스트가 필드 이름으로 접근해 permit을 쥐고 대기 시간을 줄여 본다. 테스트는 다른 패키지라 ReflectionTestUtils로 접근한다)
    final Semaphore processPermits = new Semaphore(2);
    long processWaitMillis = 5_000;

    // 사진 한 장을 올리고 결과를 돌려준다. 검사 순서: 파일 없음 400 -> 글 없음 404 -> 본인 아님 403 -> 거래완료 409 -> 이미 10장 409
    // -> (처리) 사진 깨짐 400 -> (잠금 뒤) 글이 사라짐 404 / 거래완료 409 / 10장 꽉 참 409. 관리자도 남의 글에는 못 올린다.
    // 학교 인증은 요구하지 않는다(수정 규칙과 같다).
    public MarketItemPhotoResponse upload(Long itemId, LoginUser loginUser, MultipartFile file) {
        // 입력 400을 먼저 본다 (수정 API가 가격 400을 404·403보다 먼저 보는 것과 같다)
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 파일이 필요합니다.");
        }

        // 1) 싼 확인 (트랜잭션 없이 읽기만). 여기서 걸러야 남의 글/거래완료 글/꽉 찬 글에 대해 무거운 처리를 안 한다.
        checkUploadable(marketItemRepository.findById(itemId).orElseThrow(ApiErrors::marketItemNotFound), loginUser);
        // 10장 미리보기: 잠금 없이 세므로 동시 요청 사이에서는 틀릴 수 있다(그래서 잠금 안의 최종 검사는 그대로 둔다).
        // 이미 꽉 찬 글은 여기서 거절해 이미지 처리·창고 저장을 헛수고하지 않는다.
        if (photoRepository.countByMarketItemId(itemId) >= MAX_PHOTOS_PER_ITEM) {
            throw photoLimitExceeded();
        }

        // 2) 이미지 처리 (트랜잭션 밖, 동시 2장). 파일 바이트는 자리를 얻은 뒤 그 안에서 읽는다.
        ProcessedImage image = processLimited(file);

        // 3)~4) 창고 저장 + DB 기록. 어디서 실패하든 창고에 넣은 파일을 치운다.
        String ext = "JPEG".equals(image.main().format()) ? "jpg" : "png";
        String base = "market/" + itemId + "/" + UUID.randomUUID(); // 키는 UUID 기반이라 추측·충돌이 없다
        String key = base + "." + ext;
        String thumbKey = base + "_thumb." + ext;
        try {
            photoStorage.put(key, image.main().data(), image.main().contentType());
            photoStorage.put(thumbKey, image.thumbnail().data(), image.thumbnail().contentType());
            MarketItemPhoto saved = new TransactionTemplate(txManager)
                    .execute(status -> insertLocked(itemId, loginUser, key, thumbKey, image));
            return MarketItemPhotoResponse.from(saved);
        } catch (RuntimeException | Error e) {
            // 원래 예외(e)가 항상 우선이다: 지우기가 실패해도 그건 로그만 남기고 e를 덮지 않는다
            deleteQuietly(key, e);
            deleteQuietly(thumbKey, e);
            throw e;
        }
    }

    // 사진 파일 보기 (S13-g3). 로그인 불필요. size: null=본 사진, "thumb"=썸네일, 그 외 400.
    // 순서: size 400 -> 사진 없음/다른 글의 사진 404 -> 창고에 파일 없음 404.
    // DB 조회만 읽기 전용 트랜잭션으로 짧게 하고, 파일 읽기는 트랜잭션 밖에서 한다(DB 연결 풀이 3개뿐이라 오래 잡지 않으려고).
    public MarketItemPhotoContent getPhoto(Long itemId, Long photoId, String size) {
        boolean thumb;
        if (size == null) {
            thumb = false;
        } else if (size.equals("thumb")) {
            thumb = true;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 비우거나 thumb만 쓸 수 있습니다.");
        }

        // 사진 번호와 글 번호가 둘 다 맞아야 한다 (남의 글 번호로 이 글 사진을 보는 일을 막는다)
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        MarketItemPhoto photo = readOnly.execute(status -> photoRepository.findByIdAndMarketItemId(photoId, itemId)
                .orElseThrow(MarketItemPhotoService::photoNotFound));

        // 트랜잭션 밖: 창고에서 파일 읽기. 장부(DB)엔 있는데 파일이 없으면 404 (업로드 도중 비정상 종료 등 드문 경우)
        byte[] bytes = photoStorage.get(thumb ? photo.getThumbKey() : photo.getStorageKey())
                .orElseThrow(MarketItemPhotoService::photoNotFound);
        return new MarketItemPhotoContent(bytes, photo.getContentType());
    }

    // 사진 지우기 (S13-g3). 순서: 글 없음 404 -> 본인 아님 403(관리자도) -> 거래완료 409 -> 이 글의 사진 아님 404 -> 장부 행 삭제.
    // 글 행을 잠그고(FOR UPDATE) 확인하는 이유: 같은 글의 거래완료 전환·사진 올리기·글 삭제와 줄을 세우려고.
    // 창고 파일은 DB 삭제가 "커밋된 뒤에" 지운다. 먼저 지우면 커밋이 실패했을 때 장부엔 있는데 파일이 없는 사진이 남는다.
    // (거꾸로 파일 지우기가 실패하면 파일만 고아로 남지만 화면에는 영향이 없다. 정리는 S13-g4 몫)
    public void deletePhoto(Long itemId, Long photoId, LoginUser loginUser) {
        MarketItemPhoto removed = new TransactionTemplate(txManager).execute(status -> {
            MarketItem item = marketItemRepository.findByIdForUpdate(itemId).orElseThrow(ApiErrors::marketItemNotFound);
            if (!item.isSoldBy(loginUser.id())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 올린 글의 사진만 지울 수 있습니다.");
            }
            if (item.getStatus() == ItemStatus.DONE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "거래완료된 글의 사진은 지울 수 없습니다.");
            }
            MarketItemPhoto photo = photoRepository.findByIdAndMarketItemId(photoId, itemId)
                    .orElseThrow(MarketItemPhotoService::photoNotFound);
            photoRepository.delete(photo);
            return photo;
        });

        // 여기는 트랜잭션이 커밋된 뒤다. 파일 지우기가 실패해도 이미 장부에서 지워졌으니 204는 그대로 돌려준다(로그만 남김).
        deleteFileAfterCommit(removed.getStorageKey());
        deleteFileAfterCommit(removed.getThumbKey());
    }

    private static ResponseStatusException photoNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없습니다.");
    }

    // 커밋 뒤 창고 파일 지우기. 실패는 로그만 남기고 삼킨다 (하나가 실패해도 다음 파일은 계속 지운다)
    private void deleteFileAfterCommit(String key) {
        try {
            photoStorage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("사진 삭제 뒤 창고 파일 정리에 실패했습니다: {}", key, ex);
        }
    }

    // 4) 짧은 트랜잭션: 글을 잠그고(FOR UPDATE) 다시 확인한 뒤 사진 수를 세고 넣는다.
    // 잠그는 이유: 9장 상태에서 두 요청이 동시에 와도, 한 요청이 끝날 때까지 다른 요청은 여기서 기다렸다가 10장을 보고 409가 된다.
    // 같은 이유로 그 사이 거래완료 전환·글 삭제와도 줄이 선다.
    private MarketItemPhoto insertLocked(Long itemId, LoginUser loginUser, String key, String thumbKey,
            ProcessedImage image) {
        MarketItem item = marketItemRepository.findByIdForUpdate(itemId).orElseThrow(ApiErrors::marketItemNotFound);
        checkUploadable(item, loginUser); // 1)과 처리 사이에 바뀌었을 수 있어 다시 본다
        if (photoRepository.countByMarketItemId(itemId) >= MAX_PHOTOS_PER_ITEM) {
            throw photoLimitExceeded();
        }
        return photoRepository.save(new MarketItemPhoto(itemId, key, thumbKey, image.main().contentType(),
                image.main().width(), image.main().height(), image.thumbnail().width(), image.thumbnail().height()));
    }

    // 본인 글인지(403), 거래완료가 아닌지(409) 확인한다 (관리자도 예외 없음)
    private void checkUploadable(MarketItem item, LoginUser loginUser) {
        if (!item.isSoldBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 올린 글에만 사진을 올릴 수 있습니다.");
        }
        if (item.getStatus() == ItemStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "거래완료된 글에는 사진을 올릴 수 없습니다.");
        }
    }

    private ResponseStatusException photoLimitExceeded() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "사진은 글당 최대 10장까지 올릴 수 있습니다.");
    }

    // 처리기를 부르되 동시에 2장까지만. 자리가 날 때까지 최대 processWaitMillis 기다리고, 안 나면 503.
    // 파일 바이트(최대 10MB)는 자리를 얻은 뒤에 읽는다: 먼저 읽으면 자리를 기다리는 요청마다 10MB를 힙에 쥐고 있게 되어
    // 동시 2장으로 메모리를 아끼려는 제한이 소용없어진다.
    private ProcessedImage processLimited(MultipartFile file) {
        boolean acquired;
        try {
            acquired = processPermits.tryAcquire(processWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 중단 신호를 되살려 둔다
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try {
            return imageProcessor.process(readBytes(file));
        } catch (InvalidImageException e) {
            // 처리기의 "받을 수 없는 사진"은 모두 400. 서버 메시지는 브라우저에 가지 않으니 이유만 짧게 적는다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, switch (e.getReason()) {
                case UNSUPPORTED_FORMAT -> "JPEG·PNG 사진만 올릴 수 있습니다.";
                case TOO_LARGE -> "사진 크기(픽셀 수)가 너무 큽니다.";
                case CORRUPTED -> "깨진 사진 파일입니다.";
            }, e);
        } finally {
            processPermits.release(); // 성공·실패 상관없이 자리를 돌려준다
        }
    }

    private ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사진 처리가 밀려 있습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // 손님이 잘못 보낸 게 아니라 서버가 받아 둔 임시 파일을 못 읽는 것이므로 500
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "사진 파일을 읽을 수 없습니다.", e);
        }
    }

    // 창고 파일을 지우되, 실패해도 원래 예외(cause)를 덮지 않는다 (실패는 로그와 suppressed로만 남김)
    private void deleteQuietly(String key, Throwable cause) {
        try {
            photoStorage.delete(key);
        } catch (RuntimeException ex) {
            log.warn("사진 업로드 실패 뒤 창고 파일 정리에 실패했습니다: {}", key, ex);
            cause.addSuppressed(ex);
        }
    }
}
