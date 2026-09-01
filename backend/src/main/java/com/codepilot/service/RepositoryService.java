package com.codepilot.service;

import com.codepilot.dto.github.GitHubRepoSummary;
import com.codepilot.dto.repo.CreateRepositoryFromGitHubRequest;
import com.codepilot.dto.repo.CreateRepositoryRequest;
import com.codepilot.dto.repo.GitHubRepoOptionDto;
import com.codepilot.dto.repo.RepositoryDto;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.User;
import com.codepilot.exception.ApiException;
import com.codepilot.repository.CodeRepositoryRepository;
import com.codepilot.repository.IndexJobRepository;
import com.codepilot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepositoryService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final CodeRepositoryRepository codeRepositoryRepository;
    private final UserRepository userRepository;
    private final CacheService cacheService;
    private final IndexingService indexingService;
    private final EncryptionService encryptionService;
    private final GitHubClient gitHubClient;
    private final IndexJobRepository indexJobRepository;

    @Transactional
    public RepositoryDto createRepository(UUID userId, CreateRepositoryRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));

        CodeRepository repo = CodeRepository.builder()
                .user(user)
                .githubOwner(request.githubOwner())
                .githubRepo(request.githubRepo())
                .webhookSecret(generateWebhookSecret())
                .accessTokenEncrypted(encryptionService.encrypt(request.accessToken()))
                .status(CodeRepository.RepositoryStatus.PENDING)
                .build();
        repo = codeRepositoryRepository.save(repo);
        UUID repositoryId = repo.getId();

        // Only kick off the async job once this transaction has actually committed - otherwise the
        // background thread could look up the repository before it's visible to other connections.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexingService.indexRepositoryAsync(repositoryId);
                }
            });
        } else {
            indexingService.indexRepositoryAsync(repositoryId);
        }

        return toDto(repo);
    }

    /** Lists the user's own GitHub repos via their stored OAuth token, so they can pick one to
     * connect instead of pasting a PAT by hand. Requires having signed in with GitHub. */
    @Transactional(readOnly = true)
    public List<GitHubRepoOptionDto> listAvailableFromGitHub(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (user.getGithubAccessTokenEncrypted() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Sign in with GitHub first to list your repositories, or connect one manually with a token.");
        }
        String token = encryptionService.decrypt(user.getGithubAccessTokenEncrypted());
        List<GitHubRepoSummary> repos = gitHubClient.fetchUserRepositories(token);
        return repos.stream()
                .map(r -> new GitHubRepoOptionDto(
                        r.owner() != null ? r.owner().login() : null,
                        r.name(), r.isPrivate(), r.defaultBranch()))
                .toList();
    }

    /** Same as createRepository, but sources the access token from the user's own stored GitHub
     * OAuth token instead of a pasted PAT -- the frontend never sees the raw token either way. */
    @Transactional
    public RepositoryDto createFromGitHub(UUID userId, CreateRepositoryFromGitHubRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (user.getGithubAccessTokenEncrypted() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Sign in with GitHub first.");
        }

        CodeRepository repo = CodeRepository.builder()
                .user(user)
                .githubOwner(request.githubOwner())
                .githubRepo(request.githubRepo())
                .webhookSecret(generateWebhookSecret())
                .accessTokenEncrypted(user.getGithubAccessTokenEncrypted())
                .status(CodeRepository.RepositoryStatus.PENDING)
                .build();
        repo = codeRepositoryRepository.save(repo);
        UUID repositoryId = repo.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexingService.indexRepositoryAsync(repositoryId);
                }
            });
        } else {
            indexingService.indexRepositoryAsync(repositoryId);
        }

        return toDto(repo);
    }

    @Transactional(readOnly = true)
    public List<RepositoryDto> listForUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User not found"));
        return codeRepositoryRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public RepositoryDto getById(UUID userId, UUID repositoryId) {
        var cached = cacheService.getRepository(repositoryId);
        if (cached.isPresent()) {
            // Still verify ownership even on a cache hit.
            findOwned(userId, repositoryId);
            return cached.get();
        }

        CodeRepository repo = findOwned(userId, repositoryId);
        RepositoryDto dto = toDto(repo);
        cacheService.putRepository(dto);
        return dto;
    }

    /** Loads the entity and verifies the requesting user owns it. Used by other repo-scoped services. */
    @Transactional(readOnly = true)
    public CodeRepository findOwned(UUID userId, UUID repositoryId) {
        CodeRepository repo = codeRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Repository not found"));
        if (!repo.getUser().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this repository");
        }
        return repo;
    }

    private String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    RepositoryDto toDto(CodeRepository repo) {
        // Only looked up for FAILED repos -- the generic frontend "couldn't index this
        // repository, check the token" message used to show regardless of actual cause,
        // including for real reasons that have nothing to do with the token (a repository too
        // large to fetch in one API response being a real one (torvalds/linux). No need to pay
        // for this lookup on the common (non-failed) path.
        String lastIndexError = repo.getStatus() == CodeRepository.RepositoryStatus.FAILED
                ? indexJobRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repo.getId())
                        .map(job -> job.getError())
                        .orElse(null)
                : null;
        return new RepositoryDto(
                repo.getId(),
                repo.getGithubOwner(),
                repo.getGithubRepo(),
                repo.getDefaultBranch(),
                repo.getStatus().name(),
                repo.getIndexedAt(),
                repo.getCreatedAt(),
                lastIndexError
        );
    }
}
