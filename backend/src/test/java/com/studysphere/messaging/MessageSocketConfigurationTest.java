package com.studysphere.messaging;

import com.studysphere.auth.AuthService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageSocketConfigurationTest {
    private final AuthService auth = mock(AuthService.class);
    private final MessageService messages = mock(MessageService.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final MessageSocketConfiguration config = new MessageSocketConfiguration(auth, messages,
            mock(com.studysphere.room.RoomService.class), "http://localhost:5173");

    @Test
    void requiresMembershipForSubscribeAndRevalidatesOutboundTokenAndAcl() {
        var inbound = new Registration();
        var outbound = new Registration();
        config.configureClientInboundChannel(inbound);
        config.configureClientOutboundChannel(outbound);
        var connect = frame(StompCommand.CONNECT, null);
        connect.addNativeHeader("Authorization", "Bearer active");
        when(auth.authenticate("active")).thenReturn(Optional.of(new AuthService.Identity(7, "test@example.test", List.of(), Instant.EPOCH)));
        inbound.interceptor().preSend(message(connect), channel);
        var subscribe = frame(StompCommand.SUBSCRIBE, "/user/queue/conversations/9");
        subscribe.setUser(connect.getUser());
        when(messages.peer(7, 9)).thenThrow(MessageService.missing());
        assertThatThrownBy(() -> inbound.interceptor().preSend(message(subscribe), channel))
                .isInstanceOf(MessageDeliveryException.class);
        var signal = frame(StompCommand.MESSAGE, "/queue/conversations/9-userexample");
        assertThat(outbound.interceptor().preSend(message(signal), channel)).isNull();
        doReturn(8L).when(messages).peer(7, 9);
        assertThat(inbound.interceptor().preSend(message(subscribe), channel)).isNotNull();
        assertThat(outbound.interceptor().preSend(message(signal), channel)).isNotNull();
        when(auth.authenticate("active")).thenReturn(Optional.empty());
        var send = frame(StompCommand.SEND, "/app/conversations/9/messages");
        send.setUser(connect.getUser());
        assertThatThrownBy(() -> inbound.interceptor().preSend(message(send), channel))
                .isInstanceOf(MessageDeliveryException.class);
        assertThat(outbound.interceptor().preSend(message(signal), channel)).isNull();
        when(auth.authenticate("active")).thenReturn(Optional.of(new AuthService.Identity(7, "test@example.test", List.of(), Instant.EPOCH)));
        config.disconnect(new SessionDisconnectEvent(this, message(signal), "example",
                org.springframework.web.socket.CloseStatus.NORMAL));
        assertThat(outbound.interceptor().preSend(message(signal), channel)).isNull();
    }

    private static StompHeaderAccessor frame(StompCommand command, String destination) {
        var headers = StompHeaderAccessor.create(command);
        headers.setSessionId("example");
        if (destination != null) headers.setDestination(destination);
        headers.setLeaveMutable(true);
        return headers;
    }

    private static Message<byte[]> message(StompHeaderAccessor headers) {
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static final class Registration extends ChannelRegistration {
        ChannelInterceptor interceptor() { return getInterceptors().getFirst(); }
    }
}
