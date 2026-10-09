package com.algo.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue"); // /queue for session-specific snapshots
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user"); // enables convertAndSendToUser
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-trading").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureWebSocketTransport(
            org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendBufferSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendTimeLimit(60000); // 60 seconds
    }

    @Override
    public boolean configureMessageConverters(java.util.List<org.springframework.messaging.converter.MessageConverter> messageConverters) {
        org.springframework.messaging.converter.ByteArrayMessageConverter byteArrayConverter = new org.springframework.messaging.converter.ByteArrayMessageConverter() {
            {
                addSupportedMimeTypes(
                    new org.springframework.util.MimeType("application", "x-protobuf"),
                    org.springframework.util.MimeTypeUtils.APPLICATION_OCTET_STREAM,
                    org.springframework.util.MimeTypeUtils.ALL
                );
            }
        };
        messageConverters.add(byteArrayConverter);
        return false;
    }
}
