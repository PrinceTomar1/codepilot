package com.codepilot.controller;

import com.codepilot.dto.ai.AiSearchRequest;
import com.codepilot.dto.ai.AiSearchResponse;
import com.codepilot.dto.search.SearchQueryRequest;
import com.codepilot.dto.search.SearchResponseDto;
import com.codepilot.dto.search.SearchResultDto;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.AiServiceClient;
import com.codepilot.service.RepositoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Direct code search: distinct from /ask (Q&A chat) -- no LLM call, just the same hybrid
 * vector+keyword retrieval ai-service already uses for grounding chat answers, returned straight
 * to the caller as matched snippets. No caching layer (unlike QaService): search is already cheap
 * (no LLM round trip), so there's nothing expensive here worth caching.
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final RepositoryService repositoryService;
    private final AiServiceClient aiServiceClient;

    @PostMapping("/api/repositories/{id}/search")
    public ResponseEntity<SearchResponseDto> search(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable("id") UUID repositoryId,
                                                      @Valid @RequestBody SearchQueryRequest request) {
        repositoryService.findOwned(principal.getId(), repositoryId);

        AiSearchResponse response = aiServiceClient.search(new AiSearchRequest(repositoryId, request.query(), null));

        return ResponseEntity.ok(new SearchResponseDto(
                response.results().stream()
                        .map(r -> new SearchResultDto(
                                r.filePath(), r.language(), r.startLine(), r.endLine(),
                                r.snippet(), r.symbolName(), r.matchType(), r.relevanceScore()))
                        .toList()
        ));
    }
}
