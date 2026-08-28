package com.codepilot.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@OpenAPIDefinition(
        info = @Info(
                title = "CodePilot API",
                version = "0.1.0",
                description = "AI codebase intelligence & developer assistant. "
                        + "Auth, GitHub integration, repository/indexing management, chat, PR review, and onboarding-doc endpoints. "
                        + "AI/RAG work itself (embeddings, retrieval, agents) is delegated to the internal ai-service and is not part of this public API."
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
}
