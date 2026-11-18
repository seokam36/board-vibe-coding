package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.repository.PermitRequestRepository;
import com.example.demo.permit.service.AdminPermitService;
import com.example.demo.permit.service.PermitAutoReview;
import com.example.demo.permit.service.PermitJudge;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.permit.service.PermitVisionDailyLimiter;

// S21-d (권장5-d): 하루 상한 limiter가 false면 PermitAutoReview는 판독기를 부르지 않고 UNREADABLE로 기록한다(스프링 없이 가짜로).
class PermitAutoReviewLimiterUnitTests {

	@Test
	void limiter가_false면_판독기를_부르지_않고_UNREADABLE을_기록한다() {
		PermitTextReader reader = mock(PermitTextReader.class);
		PermitJudge judge = mock(PermitJudge.class);
		AdminPermitService adminService = mock(AdminPermitService.class);
		PermitRequestRepository repo = mock(PermitRequestRepository.class);
		PermitVisionDailyLimiter limiter = mock(PermitVisionDailyLimiter.class);
		when(limiter.tryAcquire()).thenReturn(false);
		PermitAutoReview review = new PermitAutoReview(reader, judge, adminService, repo, limiter);

		var result = review.review(1L, 2L, new byte[] { 1 }, "image/jpeg");

		assertThat(result).isEmpty(); // 자동 승인 없음 = 신청은 PENDING 그대로
		verifyNoInteractions(reader, judge, adminService); // 구글(판독기)도 판정도 자동 승인도 안 부름
		verify(repo).recordMachineResult(eq(2L), eq("UNREADABLE"), eq(null), eq(PermitStatus.PENDING));
	}

	@Test
	void limiter가_true면_판독기를_부른다() {
		PermitTextReader reader = mock(PermitTextReader.class);
		when(reader.read(any(), anyString())).thenReturn(java.util.Optional.empty());
		PermitVisionDailyLimiter limiter = mock(PermitVisionDailyLimiter.class);
		when(limiter.tryAcquire()).thenReturn(true);
		PermitAutoReview review = new PermitAutoReview(reader, mock(PermitJudge.class), mock(AdminPermitService.class),
				mock(PermitRequestRepository.class), limiter);

		review.review(1L, 2L, new byte[] { 1 }, "image/jpeg");

		verify(reader).read(any(), anyString());
	}
}
