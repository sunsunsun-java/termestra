package dev.termestra.terminal.adapter.in.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.termestra.terminal.application.port.in.TerminalChannelUseCase;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.server.WebsocketServerSpec;

import java.util.Map;

@Configuration
public class TerminalWebSocketConfiguration {
    public static final int MAX_WEBSOCKET_FRAME_BYTES = 1024 * 1024;
    @Bean WebSocketHandler terminalWebSocketHandler(TerminalChannelUseCase terminal, ObjectMapper json) {
        return new TerminalWebSocketHandler(terminal, json);
    }
    @Bean HandlerMapping terminalWebSocketMapping(WebSocketHandler terminalWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        mapping.setUrlMap(Map.of(
                "/ws/terminal/{runId}/io", terminalWebSocketHandler,
                "/ws/terminal/{runId}/control", terminalWebSocketHandler));
        return mapping;
    }
    @Bean WebSocketHandlerAdapter webSocketHandlerAdapter() {
        ReactorNettyRequestUpgradeStrategy upgrade = new ReactorNettyRequestUpgradeStrategy(
                () -> WebsocketServerSpec.builder().maxFramePayloadLength(MAX_WEBSOCKET_FRAME_BYTES));
        return new WebSocketHandlerAdapter(new HandshakeWebSocketService(upgrade));
    }
}
