package com.codepilot.controller;

import com.codepilot.dto.onboarding.OnboardingDto;
import com.codepilot.security.UserPrincipal;
import com.codepilot.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/api/repositories/{id}/onboarding")
    public ResponseEntity<OnboardingDto> get(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable("id") UUID repositoryId) {
        return ResponseEntity.ok(onboardingService.getOrGenerate(principal.getId(), repositoryId));
    }
}
