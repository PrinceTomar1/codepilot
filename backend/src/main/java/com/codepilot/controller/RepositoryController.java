package com.codepilot.controller;

import com.codepilot.dto.repo.CreateRepositoryFromGitHubRequest;
import com.codepilot.dto.repo.CreateRepositoryRequest;
import com.codepilot.dto.repo.GitHubRepoOptionDto;
import com.codepilot.dto.repo.RepositoryDto;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.RepositoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryService repositoryService;

    @GetMapping
    public ResponseEntity<List<RepositoryDto>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(repositoryService.listForUser(principal.getId()));
    }

    @PostMapping
    public ResponseEntity<RepositoryDto> create(@AuthenticationPrincipal UserPrincipal principal,
                                                 @Valid @RequestBody CreateRepositoryRequest request) {
        RepositoryDto dto = repositoryService.createRepository(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepositoryDto> get(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable("id") UUID id) {
        return ResponseEntity.ok(repositoryService.getById(principal.getId(), id));
    }

    @GetMapping("/github/available")
    public ResponseEntity<List<GitHubRepoOptionDto>> listAvailableFromGitHub(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(repositoryService.listAvailableFromGitHub(principal.getId()));
    }

    @PostMapping("/from-github")
    public ResponseEntity<RepositoryDto> createFromGitHub(@AuthenticationPrincipal UserPrincipal principal,
                                                            @Valid @RequestBody CreateRepositoryFromGitHubRequest request) {
        RepositoryDto dto = repositoryService.createFromGitHub(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }
}
