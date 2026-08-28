package com.codepilot.controller;

import com.codepilot.dto.qa.AskRequest;
import com.codepilot.dto.qa.AskResponse;
import com.codepilot.dto.qa.QaHistoryDto;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.QaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/repositories/{id}")
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable("id") UUID repositoryId,
                                            @Valid @RequestBody AskRequest request) {
        return ResponseEntity.ok(qaService.ask(principal.getId(), repositoryId, request.question()));
    }

    @GetMapping("/qa-history")
    public ResponseEntity<List<QaHistoryDto>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable("id") UUID repositoryId) {
        return ResponseEntity.ok(qaService.history(principal.getId(), repositoryId));
    }
}
