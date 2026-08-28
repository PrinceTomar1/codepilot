package com.codepilot.controller;

import com.codepilot.dto.auth.AuthResponse;
import com.codepilot.dto.auth.LoginRequest;
import com.codepilot.dto.auth.RegisterRequest;
import com.codepilot.dto.auth.RegisterResponse;
import com.codepilot.dto.auth.UserDto;
import com.codepilot.security.JwtAuthFilter;
import com.codepilot.security.RateLimitFilter;
import com.codepilot.security.RestAuthenticationEntryPoint;
import com.codepilot.service.AuthService;
import com.codepilot.service.GitHubOAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice test for the auth endpoints. Security filters are disabled (addFilters = false)
 * and JwtAuthFilter/AuthService are mocked so this runs without a database or Redis.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private GitHubOAuthService gitHubOAuthService;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @MockBean
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @Test
    void register_returnsUserAndConfirmationMessage() throws Exception {
        UUID userId = UUID.randomUUID();
        RegisterResponse response = new RegisterResponse(
                new UserDto(userId, "alice@example.com", "Alice"),
                "Account created. Check your email to verify your address before signing in.");
        when(authService.register(any())).thenReturn(response);

        RegisterRequest request = new RegisterRequest("alice@example.com", "password123", "Alice");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void register_rejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_returnsTokenAndUser() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthResponse response = new AuthResponse("fake-jwt-token", new UserDto(userId, "bob@example.com", "Bob"));
        when(authService.login(any())).thenReturn(response);

        LoginRequest request = new LoginRequest("bob@example.com", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-jwt-token"))
                .andExpect(jsonPath("$.user.email").value("bob@example.com"));
    }
}
