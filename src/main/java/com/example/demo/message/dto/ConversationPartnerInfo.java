package com.example.demo.message.dto;

import com.example.demo.message.domain.Conversation;
import com.example.demo.user.domain.User;

// 대화 상대. 상대가 탈퇴했으면 응답에서 partner 자체가 null이다(이 클래스는 만들어지지 않는다).
public record ConversationPartnerInfo(Long id, String nickname) {

    // 내(myId) 기준 상대를 돌려준다. 탈퇴했으면 null.
    public static ConversationPartnerInfo of(Conversation room, Long myId) {
        User partner = room.partnerOf(myId);
        return partner == null ? null : new ConversationPartnerInfo(partner.getId(), partner.getNickname());
    }
}
