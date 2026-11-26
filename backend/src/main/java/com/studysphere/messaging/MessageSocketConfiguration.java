package com.studysphere.messaging;

import com.studysphere.auth.AuthService;
import com.studysphere.room.RoomService;
import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class MessageSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private static final Pattern SUBSCRIBE = Pattern.compile("/user/queue/(conversations|rooms)/([1-9][0-9]{0,18})");
    private static final Pattern SEND = Pattern.compile("/app/(conversations|rooms)/([1-9][0-9]{0,18})/(typing|messages|presence)");
    private static final Pattern OUTBOUND = Pattern.compile("/(?:user/)?queue/(conversations|rooms)/([1-9][0-9]{0,18})(?:-user.*)?");
    private final AuthService auth;
    private final MessageService messages;
    private final RoomService rooms;
    private final String origin;
    private final ConcurrentHashMap<String, SocketIdentity> sessions = new ConcurrentHashMap<>();

    MessageSocketConfiguration(AuthService auth, MessageService messages, RoomService rooms,
            @Value("${studysphere.auth.frontend-origin:http://localhost:5173}") String origin) {
        this.auth = auth;
        this.messages = messages;
        this.rooms = rooms;
        this.origin = origin.replaceFirst("/$", "");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/messages").setAllowedOrigins(origin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration transport) {
        transport.setMessageSizeLimit(16_384).setSendBufferSizeLimit(65_536);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers == null || headers.getCommand() == null) return message;
                StompCommand command = headers.getCommand();
                if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
                    String header = headers.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) throw denied();
                    String token = header.substring(7);
                    var identity = auth.authenticate(token).orElseThrow(MessageSocketConfiguration::denied);
                    var socketIdentity = new SocketIdentity(identity.id(), token);
                    if (headers.getSessionId() == null || sessions.putIfAbsent(headers.getSessionId(), socketIdentity) != null) {
                        throw denied();
                    }
                    headers.setUser(socketIdentity);
                    headers.removeNativeHeader("Authorization");
                    return message;
                }
                if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) return message;
                if (!(headers.getUser() instanceof SocketIdentity identity) || !valid(identity)) throw denied();
                String destination = headers.getDestination();
                if (destination == null) throw denied();
                var matcher = (command == StompCommand.SUBSCRIBE ? SUBSCRIBE : SEND).matcher(destination);
                if ((command != StompCommand.SUBSCRIBE && command != StompCommand.SEND) || !matcher.matches()) throw denied();
                if (command == StompCommand.SEND && matcher.group(1).equals("rooms") && !matcher.group(3).equals("messages")) throw denied();
                try {
                    requireAccess(identity.id, matcher.group(1), MessageController.id(matcher.group(2)));
                } catch (RuntimeException failure) {
                    throw denied();
                }
                return message;
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) return message;
                String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
                String session = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
                if (destination == null || session == null) return null;
                var target = OUTBOUND.matcher(destination);
                var identity = sessions.get(session);
                if (!target.matches() || identity == null || !valid(identity)) return null;
                try {
                    requireAccess(identity.id, target.group(1), MessageController.id(target.group(2)));
                    return message;
                } catch (RuntimeException denied) {
                    return null;
                }
            }
        });
    }

    private void requireAccess(long user, String channel, long id) {
        if (channel.equals("rooms")) rooms.requireMember(user, id);
        else messages.peer(user, id);
    }

    @EventListener
    void disconnect(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    boolean valid(SocketIdentity identity) {
        return auth.authenticate(identity.token).filter(user -> user.id() == identity.id).isPresent();
    }

    private static MessageDeliveryException denied() { return new MessageDeliveryException("Messaging access denied."); }

    static final class SocketIdentity implements Principal {
        final long id;
        private final String token;
        SocketIdentity(long id, String token) { this.id = id; this.token = token; }
        @Override public String getName() { return Long.toString(id); }
        @Override public String toString() { return getName(); }
    }
}
