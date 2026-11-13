package com.example.demo.report.dto;

import java.time.Instant;
import java.util.List;

import com.example.demo.message.domain.Conversation;
import com.example.demo.message.domain.Message;
import com.example.demo.user.domain.User;
import com.example.demo.user.dto.AuthorResponse;

// 관리자가 신고된 대화를 읽을 때의 응답(S14-f2). 방의 최근 쪽지 200통을 id 오름차순으로 담는다(S14-g).
// 일부러 읽음 여부(read_at)와 "내가 보낸 것(mine)"은 넣지 않는다: 관리자는 참가자가 아니라 지켜보는 사람이다.
// buyer·seller·sender는 탈퇴했으면 null.
public record AdminConversationResponse(Long reportId, Long conversationId, String itemTitle, AuthorResponse buyer,
        AuthorResponse seller, List<MessageInfo> messages, boolean hasMore) {

    // 쪽지 한 통. sender = 보낸 사람(탈퇴했으면 null)
    public record MessageInfo(Long id, AuthorResponse sender, String content, Instant createdAt) {

        static MessageInfo from(Message message) {
            return new MessageInfo(message.getId(), author(message.getSender()), message.getContent(),
                    message.getCreatedAt());
        }
    }

    // 방과 쪽지에서 응답을 만든다. 구매자·판매자·보낸 사람은 저장소가 미리 같이 읽어 와서 여기서 추가 쿼리가 나가지 않는다.
    // messages는 이미 오름차순으로 정리된 최근 쪽지, hasMore는 더 오래된 쪽지가 잘렸는지(S14-g).
    public static AdminConversationResponse of(Long reportId, Conversation room, List<Message> messages,
            boolean hasMore) {
        return new AdminConversationResponse(reportId, room.getId(), room.getItemTitle(), author(room.getBuyer()),
                author(room.getSeller()), messages.stream().map(MessageInfo::from).toList(), hasMore);
    }

    private static AuthorResponse author(User user) {
        return user == null ? null : AuthorResponse.from(user);
    }
}
