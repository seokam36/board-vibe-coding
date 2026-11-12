package com.example.demo.message.dto;

import com.example.demo.message.domain.Conversation;

// 대화방이 가리키는 중고 글 정보. 글이 지워졌으면 id는 null, deleted는 true.
// 제목은 항상 방을 만들 때 복사해 둔 값(item_title)이다(글이 지워져도 보여 주려고, 글을 고쳐도 대화의 제목은 그대로).
public record ConversationItemInfo(Long id, String title, boolean deleted) {

    public static ConversationItemInfo of(Conversation room) {
        // 글 칸이 NULL이면 글이 지워진 방. 프록시의 getId()는 글을 따로 읽어 오지 않는다(추가 쿼리 없음).
        Long itemId = room.getMarketItem() == null ? null : room.getMarketItem().getId();
        return new ConversationItemInfo(itemId, room.getItemTitle(), itemId == null);
    }
}
