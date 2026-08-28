package com.codepilot.dto.auth;

import java.util.UUID;

public record UserDto(UUID id, String email, String name) {
}
