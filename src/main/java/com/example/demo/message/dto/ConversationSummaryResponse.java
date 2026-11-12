package com.example.demo.message.dto;

// "내 대화 목록"의 한 줄 (GET /conversations)
public record ConversationSummaryResponse(Long id, ConversationItemInfo item, ConversationPartnerInfo partner,
        LastMessageInfo lastMessage, long unreadCount) {
}
