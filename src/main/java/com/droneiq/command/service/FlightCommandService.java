package com.droneiq.command.service;

import com.droneiq.command.dto.FlightCommandRequest;
import com.droneiq.command.dto.FlightCommandResponse;
import com.droneiq.command.entity.FlightCommand;
import com.droneiq.command.repository.FlightCommandRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class FlightCommandService {

    private final FlightCommandRepository commandRepository;
    private final DroneCommandWebSocketService droneCommandWebSocketService;
    private final ObjectMapper objectMapper;

    public FlightCommandService(
            FlightCommandRepository commandRepository,
            DroneCommandWebSocketService droneCommandWebSocketService,
            ObjectMapper objectMapper
    ) {
        this.commandRepository = commandRepository;
        this.droneCommandWebSocketService = droneCommandWebSocketService;
        this.objectMapper = objectMapper;
    }

    public FlightCommandResponse executeRoutineCommand(
            FlightCommandRequest request,
            String pilotUsername
    ) {
        String droneId = request.droneId();

        String commandType = request.commandType()
                .trim()
                .toUpperCase(java.util.Locale.ROOT);

        // Validate and decode parameters before dispatch.
        Map<String, Object> parameters = parseParameters(
                request.getEffectivePayloadJson()
        );

        // 1. Persist the command as PENDING in its own transaction.
        FlightCommand saved = createPendingCommand(
                request,
                pilotUsername,
                commandType
        );

        try {
            // 2. Preserve the existing WebSocket command format.
            droneCommandWebSocketService.sendCommand(
                    droneId,
                    String.valueOf(saved.getId()),
                    commandType,
                    parameters
            );

            // 3. Mark SENT only after the WebSocket send succeeds.
            return updateStatusAndGetResponse(
                    saved.getId(),
                    "SENT"
            );

        } catch (RuntimeException ex) {

            // A send exception can sometimes leave delivery uncertain.
            // FAILED here means dispatch failed locally; it does not
            // necessarily prove that the drone never received the command.
            try {
                updateStatusAndGetResponse(
                        saved.getId(),
                        "FAILED"
                );
            } catch (RuntimeException statusException) {
                ex.addSuppressed(statusException);
            }

            throw ex;
        }
    }

    @Transactional
    public FlightCommand createPendingCommand(
            FlightCommandRequest request,
            String username,
            String commandType
    ) {
        FlightCommand command = new FlightCommand(
                request.droneId(),
                commandType,
                false,
                username,
                "PENDING",
                request.getEffectivePayloadJson()
        );

        return commandRepository.save(command);
    }

    @Transactional
    public FlightCommandResponse updateStatusAndGetResponse(
            Long commandId,
            String status
    ) {
        FlightCommand command = commandRepository.findById(commandId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Command not found: " + commandId
                ));

        command.setStatus(status);

        FlightCommand updated = commandRepository.save(command);

        return FlightCommandResponse.fromEntity(updated);
    }

    private Map<String, Object> parseParameters(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(
                    payloadJson,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Invalid command payload JSON",
                    ex
            );
        }
    }

    @Transactional
    public FlightCommandResponse executeOverrideCommand(
            FlightCommandRequest request,
            String managerUsername
    ) {
        FlightCommand command = new FlightCommand(
                request.droneId(),
                request.commandType()
                        .trim()
                        .toUpperCase(java.util.Locale.ROOT),
                true,
                managerUsername,
                "OVERRIDE_EXECUTED",
                request.getEffectivePayloadJson()
        );

        FlightCommand saved = commandRepository.save(command);

        return FlightCommandResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<FlightCommandResponse> getCommandHistory(String droneId) {
        List<FlightCommand> commands =
                (droneId != null && !droneId.isBlank())
                        ? commandRepository
                                .findByDroneIdOrderByExecutedAtDesc(droneId)
                        : commandRepository.findAll();

        return commands.stream()
                .map(FlightCommandResponse::fromEntity)
                .toList();
    }
}