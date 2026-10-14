package com.droneiq.telemetry.websocket;

import com.droneiq.telemetry.dto.TelemetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryWebSocketServiceTest {

    private TelemetryWebSocketService webSocketService;
    private WebSocketSession session1;
    private WebSocketSession session2;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        webSocketService = new TelemetryWebSocketService(objectMapper);

        session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("sess-1");
        when(session1.isOpen()).thenReturn(true);

        session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("sess-2");
        when(session2.isOpen()).thenReturn(true);
    }

    private TelemetryMessage createMessage(String droneId) {
        return TelemetryMessage.builder()
                .droneId(droneId)
                .timestamp(Instant.now())
                .latitude(BigDecimal.valueOf(37.77))
                .longitude(BigDecimal.valueOf(-122.41))
                .altitude(BigDecimal.valueOf(50.0))
                .heading(BigDecimal.valueOf(90.0))
                .speed(BigDecimal.valueOf(12.0))
                .batteryPercentage(BigDecimal.valueOf(88.0))
                .flightMode("AUTO")
                .armed(true)
                .build();
    }

    @Test
    @DisplayName("Should broadcast telemetry to session subscribed to all drones")
    void testBroadcastToAll() throws IOException {
        webSocketService.registerSession(session1);
        webSocketService.subscribeAll(session1);

        TelemetryMessage message = createMessage("DRONE-001");
        webSocketService.broadcastTelemetry(message);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session1, times(1)).sendMessage(captor.capture());
        assertTrue(captor.getValue().getPayload().contains("DRONE-001"));
        assertTrue(captor.getValue().getPayload().contains("TELEMETRY"));
        assertTrue(captor.getValue().getPayload().contains("\"liveDroneConnected\":true"));
    }

    @Test
    @DisplayName("Should only send to sessions subscribed to the specific drone")
    void testSpecificSubscription() throws IOException {
        webSocketService.registerSession(session1);
        webSocketService.subscribe(session1, "DRONE-001");

        webSocketService.registerSession(session2);
        webSocketService.subscribe(session2, "DRONE-002");

        // Broadcast DRONE-001 telemetry
        webSocketService.broadcastTelemetry(createMessage("DRONE-001"));

        verify(session1, times(1)).sendMessage(any(TextMessage.class));
        verify(session2, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("Should cleanly unregister session on disconnect")
    void testUnregisterSession() {
        webSocketService.registerSession(session1);
        assertEquals(1, webSocketService.getConnectedSessionsCount());

        webSocketService.unregisterSession(session1);
        assertEquals(0, webSocketService.getConnectedSessionsCount());
    }

    private static TextMessage any(Class<TextMessage> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
