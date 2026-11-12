package com.example.demo.photo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

// 스프링 전체 없이 가벼운 ApplicationContextRunner로 "설정 스위치"와 시작 검사를 본다. 진짜 S3에는 접속하지 않는다.
class S3ClientConfigTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3ClientConfig.class, S3PhotoStorage.class, LocalPhotoStorage.class);

    @Test
    void 기본값이면_로컬_창고만_생기고_S3는_안_생긴다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(LocalPhotoStorage.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
            assertThat(ctx).doesNotHaveBean(S3PhotoStorage.class);
        });
    }

    @Test
    void storage가_local이어도_같다() {
        runner.withPropertyValues("app.photo.storage=local").run(ctx -> {
            assertThat(ctx).hasSingleBean(LocalPhotoStorage.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void s3인데_버킷이_비면_시작에_실패한다() {
        runner.withPropertyValues("app.photo.storage=s3", "app.photo.s3.bucket=", "app.photo.s3.region=ap-northeast-2")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("PHOTO_S3_BUCKET"));
    }

    @Test
    void s3인데_리전이_비면_시작에_실패한다() {
        runner.withPropertyValues("app.photo.storage=s3", "app.photo.s3.bucket=example-bucket", "app.photo.s3.region=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("PHOTO_S3_REGION"));
    }

    @Test
    void s3인데_설정이_아예_없어도_시작에_실패한다() {
        runner.withPropertyValues("app.photo.storage=s3")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void s3일_때는_S3_창고만_생긴다() {
        // S3ClientConfig 대신 가짜 S3Client를 직접 등록해 "창고 선택"만 확인
        new ApplicationContextRunner()
                .withUserConfiguration(S3PhotoStorage.class, LocalPhotoStorage.class)
                .withBean(S3Client.class, () -> mock(S3Client.class))
                .withPropertyValues("app.photo.storage=s3", "app.photo.s3.bucket=example-bucket")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(S3PhotoStorage.class);
                    assertThat(ctx).doesNotHaveBean(LocalPhotoStorage.class);
                    assertThat(ctx.getBean(PhotoStorage.class)).isInstanceOf(S3PhotoStorage.class);
                });
    }

    @Test
    void 버킷_확인은_HeadBucket을_해당_버킷으로_부른다() {
        S3Client s3 = mock(S3Client.class);

        S3ClientConfig.verifyBucket(s3, "example-bucket");

        ArgumentCaptor<HeadBucketRequest> captor = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(s3).headBucket(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("example-bucket");
    }

    @Test
    void 버킷_확인이_실패하면_시작_실패용_예외가_난다() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().build());
        assertThatThrownBy(() -> S3ClientConfig.verifyBucket(s3, "example-bucket"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("버킷");

        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(SdkClientException.create("연결 불가"));
        assertThatThrownBy(() -> S3ClientConfig.verifyBucket(s3, "example-bucket"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실제_클라이언트를_만들고_닫을_수_있고_호출_제한_시간이_적용된다() {
        // 만드는 것만으로는 네트워크를 타지 않는다. 풀 크기는 밖에서 읽을 수 없어 생성·종료와 API 타임아웃만 확인한다
        try (S3Client client = S3ClientConfig.createClient("ap-northeast-2")) {
            var config = client.serviceClientConfiguration();
            assertThat(config.region().id()).isEqualTo("ap-northeast-2");
            assertThat(config.overrideConfiguration().apiCallTimeout()).hasValue(S3ClientConfig.API_CALL_TIMEOUT);
            assertThat(config.overrideConfiguration().apiCallAttemptTimeout())
                    .hasValue(S3ClientConfig.API_CALL_ATTEMPT_TIMEOUT);
        }
    }
}
