package com.example.demo.post.dto;

// 글 id별 개수 한 줄 (댓글 수·공감 수를 group by로 한꺼번에 셀 때 쓴다)
public record PostCount(Long postId, long count) {
}
