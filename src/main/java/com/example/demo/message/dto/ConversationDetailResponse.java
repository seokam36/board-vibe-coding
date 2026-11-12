package com.example.demo.message.dto;

import java.util.List;

// 대화 한 건 보기 (GET /conversations/{id}). messages는 쪽지 id 오름차순(오래된 것부터).
public record ConversationDetailResponse(Long id, ConversationItemInfo item, ConversationPartnerInfo partner,
        List<MessageResponse> messages) {
}
