package com.codepilot.dto.auth;

public record AuthResponse(String token, UserDto user) {
}
