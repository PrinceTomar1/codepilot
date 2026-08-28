package com.codepilot.service;

import com.codepilot.dto.ai.AiFileContent;
import com.codepilot.dto.ai.AiIndexRequest;
import com.codepilot.dto.ai.AiIndexResponse;
import com.codepilot.dto.github.GitHubRepoInfo;
import com.codepilot.entity.CodeRepository;
import com.codepilot.entity.IndexJob;
import com.codepilot.repository.CodeRepositoryRepository;
import com.codepilot.repository.IndexJobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs repository indexing in the background: pulls files from GitHub, ships them to the Python
 * AI service's /index endpoint, and records progress/results on the repository + index_jobs rows.
 */
@Service
@RequiredArgsConstructor
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final CodeRepositoryRepository codeRepositoryRepository;
    private final IndexJobRepository indexJobRepository;
    private final GitHubClient gitHubClient;
    private final AiServiceClient aiServiceClient;
    private final EncryptionService encryptionService;
    private final CacheService cacheService;
    private final RepositoryAccessTokenResolver accessTokenResolver;

    @Async("indexingExecutor")
    public void indexRepositoryAsync(UUID repositoryId) {
        Optional<CodeRepository> repoOpt = codeRepositoryRepository.findById(repositoryId);
        if (repoOpt.isEmpty()) {
            log.error("indexRepositoryAsync: repository {} not found", repositoryId);
            return;
        }
        CodeRepository repo = repoOpt.get();

        IndexJob job = IndexJob.builder()
                .repository(repo)
                .status(IndexJob.JobStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        job = indexJobRepository.save(job);

        repo.setStatus(CodeRepository.RepositoryStatus.INDEXING);
        codeRepositoryRepository.save(repo);

        try {
            String accessToken = encryptionService.decrypt(accessTokenResolver.resolve(repo));

            GitHubRepoInfo repoInfo = gitHubClient.fetchRepoInfo(repo.getGithubOwner(), repo.getGithubRepo(), accessToken);
            String branch = (repoInfo != null && repoInfo.defaultBranch() != null) ? repoInfo.defaultBranch() : "main";
            Long githubRepoId = repoInfo != null ? repoInfo.id() : null;

            List<AiFileContent> files = gitHubClient.fetchRepositoryFiles(
                    repo.getGithubOwner(), repo.getGithubRepo(), branch, accessToken);

            AiIndexResponse response = aiServiceClient.index(new AiIndexRequest(repositoryId, files));

            repo.setDefaultBranch(branch);
            repo.setGithubRepoId(githubRepoId);
            repo.setStatus(CodeRepository.RepositoryStatus.INDEXED);
            repo.setIndexedAt(Instant.now());
            codeRepositoryRepository.save(repo);

            job.setStatus(IndexJob.JobStatus.COMPLETED);
            job.setFilesIndexed(response != null && response.filesIndexed() != null ? response.filesIndexed() : files.size());
            job.setChunksCreated(response != null && response.chunksCreated() != null ? response.chunksCreated() : 0);
            job.setFinishedAt(Instant.now());
            indexJobRepository.save(job);

            log.info("Indexing completed for repository {} ({} files, {} chunks)",
                    repositoryId, job.getFilesIndexed(), job.getChunksCreated());
        } catch (Exception e) {
            log.error("Indexing failed for repository {}: {}", repositoryId, e.getMessage(), e);

            repo.setStatus(CodeRepository.RepositoryStatus.FAILED);
            codeRepositoryRepository.save(repo);

            job.setStatus(IndexJob.JobStatus.FAILED);
            job.setError(truncate(e.getMessage(), 4000));
            job.setFinishedAt(Instant.now());
            indexJobRepository.save(job);
        } finally {
            cacheService.evictRepository(repositoryId);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "Unknown error";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
