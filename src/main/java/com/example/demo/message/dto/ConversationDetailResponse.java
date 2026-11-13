package com.example.demo.message.dto;

import java.util.List;

// 대화 한 건 보기 (GET /conversations/{id}). messages는 최근 200통을 쪽지 id 오름차순(오래된 것부터)으로.
// hasMore = 200통보다 더 오래된 쪽지가 있어 잘렸는가.
public record ConversationDetailResponse(Long id, ConversationItemInfo item, ConversationPartnerInfo partner,
        List<MessageResponse> messages, boolean hasMore) {
}
