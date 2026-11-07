package com.example.demo.like.dto;

// 공감 누르기/취소 결과: 처리 후 공감 수와 내가 공감한 상태
public record LikeResponse(long likeCount, boolean liked) {
}
