package com.studysphere.messaging;

import java.security.Principal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Controller
class MessageSocket {
    private final MessageService messages;
    private final SimpMessagingTemplate delivery;
    private final SimpUserRegistry users;

    MessageSocket(MessageService messages, SimpMessagingTemplate delivery, SimpUserRegistry users) {
        this.messages = messages;
        this.delivery = delivery;
        this.users = users;
    }

    @MessageMapping("/conversations/{id}/messages")
    void message(@DestinationVariable String id, MessageController.Text input, Principal user) {
        messages.send(MessageController.id(user.getName()), MessageController.id(id), input.body(), null);
    }

    @MessageMapping("/conversations/{id}/typing")
    void typing(@DestinationVariable String id, Principal user) {
        messages.signal("TYPING", MessageController.id(id), MessageController.id(user.getName()));
    }

    @MessageMapping("/conversations/{id}/presence")
    void presence(@DestinationVariable String id, Principal user) {
        long peer = messages.peer(MessageController.id(user.getName()), MessageController.id(id));
        String peerId = Long.toString(peer);
        delivery.convertAndSendToUser(user.getName(), "/queue/conversations/" + id,
                new Signal("PRESENCE", id, peerId, null, users.getUser(peerId) != null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void publish(MessageService.Event event) {
        long recipient = event.recipientId();
        try {
            messages.peer(recipient, MessageController.id(event.conversationId()));
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            return;
        }
        String path = "/queue/conversations/" + event.conversationId();
        var signal = new Signal(event.type(), event.conversationId(), event.userId(), event.messageId(), null);
        delivery.convertAndSendToUser(Long.toString(recipient), path, signal);
        if (!event.type().equals("TYPING")) delivery.convertAndSendToUser(event.userId(), path, signal);
    }

    record Signal(String type, String conversationId, String userId, String messageId, Boolean online) {}
}
