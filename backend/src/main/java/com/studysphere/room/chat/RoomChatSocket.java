package com.studysphere.room.chat;

import java.security.Principal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.event.TransactionalEventListener;

@Controller
class RoomChatSocket {
    private final RoomChatService chat;
    private final SimpMessagingTemplate delivery;

    RoomChatSocket(RoomChatService chat, SimpMessagingTemplate delivery) {
        this.chat = chat;
        this.delivery = delivery;
    }

    @MessageMapping("/rooms/{room}/messages")
    void message(@DestinationVariable String room, RoomChatController.Text body, Principal user) {
        chat.send(RoomChatController.id(user.getName()), RoomChatController.id(room), body.body());
    }

    @TransactionalEventListener
    void publish(RoomChatService.Event event) {
        String roomId = Long.toString(event.room());
        var signal = new Signal(event.type(), roomId, Long.toString(event.id()));
        for (long member : chat.members(event.room())) {
            delivery.convertAndSendToUser(Long.toString(member), "/queue/rooms/" + roomId, signal);
        }
    }

    record Signal(String type, String roomId, String messageId) {}
}
