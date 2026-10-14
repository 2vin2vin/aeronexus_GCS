package com.droneiq.video.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        String loginJson = """
                {
                    "username": "admin",
                    "password": "admin123"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        authToken = response.get("data").get("token").asText();
    }

    @Test
    @DisplayName("Unauthenticated request to /api/video/streams returns 401 Unauthorized")
    void testGetStreams_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/video/streams"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated request to /api/video/streams returns 200 OK and stream list")
    void testGetStreams_Authenticated_Returns200() throws Exception {
        mockMvc.perform(get("/api/video/streams")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.streams", notNullValue()))
                .andExpect(jsonPath("$.data.streams.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.streams[0].streamId", is("drone-001-camera")))
                .andExpect(jsonPath("$.data.streams[0].protocol", is("WEBRTC")));
    }

    @Test
    @DisplayName("Authenticated request for existing stream returns 200 OK and WebRTC WHEP connection details")
    void testGetStreamDetails_Found_Returns200() throws Exception {
        mockMvc.perform(get("/api/video/streams/drone-001-camera")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.streamId", is("drone-001-camera")))
                .andExpect(jsonPath("$.data.webrtcUrl", is("http://localhost:8889/drone-001-camera/whep")))
                .andExpect(jsonPath("$.data.status", is("AVAILABLE")));
    }

    @Test
    @DisplayName("Authenticated request for non-existent stream returns 404 Not Found")
    void testGetStreamDetails_NotFound_Returns404() throws Exception {
        mockMvc.perform(get("/api/video/streams/unknown-stream-id")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    @DisplayName("Authenticated request to /api/video/health returns 200 OK and subsystem health")
    void testGetVideoHealth_Returns200() throws Exception {
        mockMvc.perform(get("/api/video/health")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.mediaServer", is("MediaMTX")));
    }

    @Test
    @DisplayName("HUD overlay configuration returns 200 OK (Backward Compatibility)")
    void testGetHudOverlay_Returns200() throws Exception {
        mockMvc.perform(get("/api/video/streams/DRONE-001/hud")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.droneId", is("DRONE-001")));
    }
}
