package com.droneiq.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DroneCommandWebSocketService {

    private final ObjectMapper objectMapper;

    // Maps application drone ID to its authenticated Python bridge session.
    private final Map<String, WebSocketSession> droneSessions =
            new ConcurrentHashMap<>();

    public DroneCommandWebSocketService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final Logger log =
        LoggerFactory.getLogger(DroneCommandWebSocketService.class);

    /**
     * Register a bridge after WebSocket authentication and
     * authorization have succeeded.
     */
    public void registerDrone(
            String droneId,
            WebSocketSession session
    ) {
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId is required");
        }

        if (session == null || !session.isOpen()) {
            throw new IllegalStateException(
                    "WebSocket session is not open"
            );
        }

        droneSessions.put(droneId, session);

        log.info(
                "[DroneCommandWS] Registered bridge for " + droneId
        );
    }

    /**
     * Remove a bridge session when it disconnects.
     * Do not remove a newer session that replaced the old one.
     */
    public void unregisterSession(WebSocketSession session) {
        if (session == null) {
            return;
        }

        droneSessions.entrySet().removeIf(entry ->
                entry.getValue().getId().equals(session.getId())
        );
    }

    /**
     * Send a command to the bridge registered for the requested drone.
     */
    public void sendCommand(
            String droneId,
            String commandId,
            String commandType,
            Map<String, Object> parameters
    ) {
        System.out.println(
            "[DEBUG] Requested drone: " + droneId
                + ", registered drones: " + droneSessions.keySet()
        );
        WebSocketSession session = droneSessions.get(droneId);

        if (session == null) {
            throw new IllegalStateException(
                    "No connected bridge for drone: " + droneId
            );
        }

        if (!session.isOpen()) {
            droneSessions.remove(droneId, session);

            throw new IllegalStateException(
                    "Bridge is disconnected for drone: " + droneId
            );
        }

        Map<String, Object> message = Map.of(
                "type", "COMMAND",
                "commandId", commandId,
                "droneId", droneId,
                "commandType", commandType,
                "parameters",
                parameters == null ? Map.of() : parameters
        );

        try {
            String json = objectMapper.writeValueAsString(message);

            // Spring WebSocket sessions should not be written concurrently.
            synchronized (session) {
                if (!session.isOpen()) {
                    throw new IllegalStateException(
                            "Bridge WebSocket disconnected before dispatch"
                    );
                }

                session.sendMessage(new TextMessage(json));
            }

            log.info(
                    "[DroneCommandWS] Sent " + commandType
                    + " to " + droneId
                    + " commandId=" + commandId
            );

        } catch (IOException ex) {
            log.error(
                    "Failed to send command to droneId={}, commandId={}",
                    droneId,
                    commandId,
                    ex
            );

            throw new IllegalStateException(
                    "Failed to send command to drone " + droneId,
                    ex
            );
        }
    }
}