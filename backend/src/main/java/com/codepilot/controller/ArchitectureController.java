package com.codepilot.controller;

import com.codepilot.dto.ai.AiArchitectureRequest;
import com.codepilot.dto.ai.AiArchitectureResponse;
import com.codepilot.dto.repo.ArchitectureEdgeDto;
import com.codepilot.dto.repo.ArchitectureGraphDto;
import com.codepilot.dto.repo.ArchitectureNodeDto;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.AiServiceClient;
import com.codepilot.service.RepositoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * No caching/persistence layer (unlike onboarding, which is LLM-generated and expensive) -- this
 * graph is a cheap regex-based scan over already-indexed content, so it's recomputed fresh on
 * every request and always reflects the latest indexed state.
 */
@RestController
@RequiredArgsConstructor
public class ArchitectureController {

    private final RepositoryService repositoryService;
    private final AiServiceClient aiServiceClient;

    @GetMapping("/api/repositories/{id}/architecture")
    public ResponseEntity<ArchitectureGraphDto> get(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable("id") UUID repositoryId) {
        repositoryService.findOwned(principal.getId(), repositoryId);

        AiArchitectureResponse response = aiServiceClient.architecture(new AiArchitectureRequest(repositoryId));

        return ResponseEntity.ok(new ArchitectureGraphDto(
                response.nodes().stream().map(n -> new ArchitectureNodeDto(n.id(), n.language())).toList(),
                response.edges().stream().map(e -> new ArchitectureEdgeDto(e.source(), e.target())).toList()
        ));
    }
}
