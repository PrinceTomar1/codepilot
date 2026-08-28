package com.codepilot.service;

import com.codepilot.dto.ai.AiOnboardingRequest;
import com.codepilot.dto.ai.AiOnboardingResponse;
import com.codepilot.dto.onboarding.OnboardingDto;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.OnboardingDoc;
import com.codepilot.repository.OnboardingDocRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final RepositoryService repositoryService;
    private final OnboardingDocRepository onboardingDocRepository;
    private final AiServiceClient aiServiceClient;

    @Transactional
    public OnboardingDto getOrGenerate(UUID userId, UUID repositoryId) {
        CodeRepository repo = repositoryService.findOwned(userId, repositoryId);

        return onboardingDocRepository.findFirstByRepositoryIdOrderByGeneratedAtDesc(repositoryId)
                .map(this::toDto)
                .orElseGet(() -> generate(repo));
    }

    private OnboardingDto generate(CodeRepository repo) {
        AiOnboardingResponse response = aiServiceClient.onboarding(new AiOnboardingRequest(repo.getId()));

        OnboardingDoc doc = OnboardingDoc.builder()
                .repository(repo)
                .architectureOverview(response.architectureOverview())
                .importantModules(response.importantModules())
                .setupInstructions(response.setupInstructions())
                .dataFlow(response.dataFlow())
                .readFirst(response.readFirst())
                .build();
        doc = onboardingDocRepository.save(doc);
        return toDto(doc);
    }

    private OnboardingDto toDto(OnboardingDoc d) {
        return new OnboardingDto(
                d.getId(),
                d.getRepository().getId(),
                d.getArchitectureOverview(),
                d.getImportantModules(),
                d.getSetupInstructions(),
                d.getDataFlow(),
                d.getReadFirst(),
                d.getGeneratedAt()
        );
    }
}
