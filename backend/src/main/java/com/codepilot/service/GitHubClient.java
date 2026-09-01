package com.codepilot.service;

import com.codepilot.dto.ai.AiFileContent;
import com.codepilot.dto.ai.AiReviewFile;
import com.codepilot.dto.github.GitHubBlob;
import com.codepilot.dto.github.GitHubEmail;
import com.codepilot.dto.github.GitHubPullRequestFile;
import com.codepilot.dto.github.GitHubPullRequestInfo;
import com.codepilot.dto.github.GitHubRepoInfo;
import com.codepilot.dto.github.GitHubRepoSummary;
import com.codepilot.dto.github.GitHubTreeEntry;
import com.codepilot.dto.github.GitHubTreeResponse;
import com.codepilot.dto.github.GitHubUser;
import com.codepilot.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Real GitHub REST API client (https://api.github.com) used to fetch a repository's file tree,
 * file contents, and pull-request diffs for indexing and AI review.
 */
@Service
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private static final int MAX_FILES = 300;
    private static final long MAX_FILE_SIZE_BYTES = 200 * 1024L; // 200KB

    private static final Set<String> SKIP_DIR_SEGMENTS = Set.of(
            "node_modules", ".git", "dist", "build", "target", "out", "vendor",
            ".next", ".nuxt", ".venv", "venv", "env", "__pycache__", ".idea",
            ".vscode", "coverage", ".gradle", ".mvn", "bin", "obj"
    );

    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "svg", "webp", "tiff",
            "mp4", "mov", "avi", "mkv", "mp3", "wav", "flac", "ogg",
            "pdf", "zip", "tar", "gz", "tgz", "7z", "rar", "jar", "war", "ear",
            "class", "exe", "dll", "so", "dylib", "bin", "o", "a",
            "woff", "woff2", "ttf", "eot", "otf",
            "db", "sqlite", "sqlite3", "parquet", "pyc",
            // credential/key material -- must never be embedded/stored, let alone surfaced by RAG
            "pem", "key", "pfx", "p12", "jks", "keystore", "asc", "ppk"
    );

    // Exact (lowercased) basenames that conventionally hold live secrets, regardless of extension.
    private static final Set<String> SKIP_EXACT_FILENAMES = Set.of(
            ".npmrc", ".netrc", ".htpasswd", ".pgpass",
            "id_rsa", "id_rsa.pub", "id_dsa", "id_ecdsa", "id_ed25519",
            "credentials.json", "credentials.csv",
            // Machine-generated dependency lockfiles: huge, offer nothing for understanding a
            // codebase, and actively hurt keyword search -- a package name that happens to
            // substring-match a real keyword (e.g. "micromark-util-symbol" matching "symbol") can
            // outnumber every genuinely relevant chunk in the repo -- a real contributor to a
            // retrieval failure on the query "gold symbol".
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json",
            "composer.lock", "gemfile.lock", "cargo.lock", "poetry.lock", "pipfile.lock",
            "go.sum", "mix.lock"
    );

    // Committed templates that document env vars without real values -- safe (and useful) to index.
    private static final Set<String> ENV_TEMPLATE_BASENAMES = Set.of(
            ".env.example", ".env.sample", ".env.template", ".env.defaults"
    );

    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("py", "python"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("ts", "typescript"),
            Map.entry("tsx", "typescript"), Map.entry("go", "go"), Map.entry("rb", "ruby"),
            Map.entry("php", "php"), Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cpp", "cpp"), Map.entry("hpp", "cpp"), Map.entry("cs", "csharp"),
            Map.entry("rs", "rust"), Map.entry("swift", "swift"), Map.entry("scala", "scala"),
            Map.entry("sql", "sql"), Map.entry("sh", "shell"), Map.entry("bash", "shell"),
            Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"), Map.entry("json", "json"),
            Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("css", "css"),
            Map.entry("scss", "scss"), Map.entry("md", "markdown"), Map.entry("toml", "toml"),
            Map.entry("gradle", "groovy"), Map.entry("dockerfile", "dockerfile")
    );

    private final WebClient gitHubWebClient;

    public GitHubClient(@Qualifier("gitHubWebClient") WebClient gitHubWebClient) {
        this.gitHubWebClient = gitHubWebClient;
    }

    public GitHubUser fetchAuthenticatedUser(String accessToken) {
        try {
            return gitHubWebClient.get()
                    .uri("/user")
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(GitHubUser.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch GitHub user profile: " + e.getMessage(), e);
        }
    }

    /** GitHub only returns a user's `email` on /user if they've made it public -- this is the
     * reliable way to get their real (possibly private) primary verified email. */
    public List<GitHubEmail> fetchUserEmails(String accessToken) {
        try {
            List<GitHubEmail> emails = gitHubWebClient.get()
                    .uri("/user/emails")
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToFlux(GitHubEmail.class)
                    .collectList()
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
            return emails == null ? List.of() : emails;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch GitHub user emails: " + e.getMessage(), e);
        }
    }

    public List<GitHubRepoSummary> fetchUserRepositories(String accessToken) {
        try {
            List<GitHubRepoSummary> repos = gitHubWebClient.get()
                    .uri("/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator")
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToFlux(GitHubRepoSummary.class)
                    .collectList()
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
            return repos == null ? List.of() : repos;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch GitHub repositories: " + e.getMessage(), e);
        }
    }

    public GitHubRepoInfo fetchRepoInfo(String owner, String repo, String accessToken) {
        try {
            return gitHubWebClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(GitHubRepoInfo.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch repository info from GitHub: " + e.getMessage(), e);
        }
    }

    /** Recursively fetches the file tree, then downloads and decodes text-ish file contents. */
    public List<AiFileContent> fetchRepositoryFiles(String owner, String repo, String branch, String accessToken) {
        GitHubTreeResponse tree;
        try {
            tree = gitHubWebClient.get()
                    .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(GitHubTreeResponse.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch file tree from GitHub: " + e.getMessage(), e);
        }

        if (tree == null || tree.tree() == null) {
            return List.of();
        }

        List<AiFileContent> files = new ArrayList<>();
        int consideredCount = 0;

        for (GitHubTreeEntry entry : tree.tree()) {
            if (files.size() >= MAX_FILES) {
                log.info("Reached max file cap ({}) for {}/{}, skipping remaining entries", MAX_FILES, owner, repo);
                break;
            }
            if (!"blob".equals(entry.type()) || entry.path() == null) {
                continue;
            }
            if (shouldSkipPath(entry.path())) {
                continue;
            }
            if (entry.size() != null && entry.size() > MAX_FILE_SIZE_BYTES) {
                log.debug("Skipping {} ({} bytes) - exceeds {}-byte cap", entry.path(), entry.size(), MAX_FILE_SIZE_BYTES);
                continue;
            }

            consideredCount++;
            try {
                String content = fetchBlobContent(owner, repo, entry.sha(), accessToken);
                if (content == null) {
                    continue;
                }
                files.add(new AiFileContent(entry.path(), detectLanguage(entry.path()), content));
            } catch (Exception e) {
                log.warn("Skipping file {} due to fetch error: {}", entry.path(), e.getMessage());
            }
        }

        log.info("Fetched {} files (considered {}) for {}/{}", files.size(), consideredCount, owner, repo);
        return files;
    }

    private String fetchBlobContent(String owner, String repo, String sha, String accessToken) {
        GitHubBlob blob;
        try {
            blob = gitHubWebClient.get()
                    .uri("/repos/{owner}/{repo}/git/blobs/{sha}", owner, repo, sha)
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(GitHubBlob.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch blob from GitHub: " + e.getMessage(), e);
        }

        if (blob == null || blob.content() == null) {
            return null;
        }
        if (!"base64".equalsIgnoreCase(blob.encoding())) {
            log.debug("Skipping blob {} with unsupported encoding {}", sha, blob.encoding());
            return null;
        }

        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(blob.content());
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Skip likely-binary content (contains null bytes).
        for (byte b : decoded) {
            if (b == 0) {
                return null;
            }
        }

        return new String(decoded, StandardCharsets.UTF_8);
    }

    /** Fetches a single pull request's metadata (title, author, head/base SHA) -- used to manually
     * trigger a review for a PR that hasn't (yet) arrived via webhook, since webhook registration
     * on GitHub's side isn't automated by this app. */
    public GitHubPullRequestInfo fetchPullRequestInfo(String owner, String repo, int prNumber, String accessToken) {
        try {
            return gitHubWebClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pr}", owner, repo, prNumber)
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(GitHubPullRequestInfo.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch pull request from GitHub: " + e.getMessage(), e);
        }
    }

    /** Fetches the changed files (with unified diffs) for a pull request, plus their post-change full content. */
    public List<AiReviewFile> fetchPullRequestFiles(String owner, String repo, int prNumber, String headSha, String accessToken) {
        List<GitHubPullRequestFile> prFiles;
        try {
            prFiles = gitHubWebClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pr}/files?per_page=100", owner, repo, prNumber)
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToFlux(GitHubPullRequestFile.class)
                    .collectList()
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Failed to fetch PR files from GitHub: " + e.getMessage(), e);
        }

        if (prFiles == null) {
            return List.of();
        }

        List<AiReviewFile> result = new ArrayList<>();
        for (GitHubPullRequestFile f : prFiles) {
            // ai-service's request schema declares diff/fullContent as plain (non-optional)
            // strings defaulting to "" when the field is omitted -- but omission only helps if we
            // never send an explicit null, and GitHub genuinely gives us null patches for binary
            // files and null content for removed/unfetchable files. A single such file used to
            // 422 the whole review request; every path below now normalizes to "" instead.
            if (f.filename() == null || "removed".equals(f.status())) {
                result.add(new AiReviewFile(f.filename(), nullToEmpty(f.patch()), ""));
                continue;
            }
            String fullContent = null;
            try {
                fullContent = fetchFileContentAtRef(owner, repo, f.filename(), headSha, accessToken);
            } catch (Exception e) {
                log.warn("Could not fetch full content for {}: {}", f.filename(), e.getMessage());
            }
            result.add(new AiReviewFile(f.filename(), nullToEmpty(f.patch()), nullToEmpty(fullContent)));
        }
        return result;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String fetchFileContentAtRef(String owner, String repo, String path, String ref, String accessToken) {
        try {
            Map<?, ?> response = gitHubWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/repos/{owner}/{repo}/contents/{path}")
                            .queryParam("ref", ref)
                            .build(owner, repo, path))
                    .headers(h -> authHeaders(h, accessToken))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .retryWhen(AiServiceClient.retrySpec())
                    .block();

            if (response == null) {
                return null;
            }
            Object content = response.get("content");
            Object encoding = response.get("encoding");
            if (content == null || !"base64".equals(encoding)) {
                return null;
            }
            byte[] decoded = Base64.getMimeDecoder().decode((String) content);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void authHeaders(org.springframework.http.HttpHeaders headers, String accessToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            headers.set("Authorization", "Bearer " + accessToken);
        }
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
    }

    private boolean shouldSkipPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String segment : SKIP_DIR_SEGMENTS) {
            if (lower.contains("/" + segment + "/") || lower.startsWith(segment + "/")) {
                return true;
            }
        }

        int lastSlash = lower.lastIndexOf('/');
        String basename = lastSlash >= 0 ? lower.substring(lastSlash + 1) : lower;

        if (SKIP_EXACT_FILENAMES.contains(basename)) {
            return true;
        }
        // .env / .env.local / .env.production etc hold live secrets in most frameworks; the
        // extension-based check below can't catch these since their "extension" (last dot) is
        // "local"/"production"/etc, not "env". Committed no-secret templates stay indexable.
        if (basename.startsWith(".env") && !ENV_TEMPLATE_BASENAMES.contains(basename)) {
            return true;
        }

        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < lower.length() - 1) {
            String ext = lower.substring(dotIdx + 1);
            if (SKIP_EXTENSIONS.contains(ext)) {
                return true;
            }
        }
        return false;
    }

    private String detectLanguage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith("dockerfile")) {
            return "dockerfile";
        }
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == lower.length() - 1) {
            return "text";
        }
        String ext = lower.substring(dotIdx + 1);
        return LANGUAGE_BY_EXTENSION.getOrDefault(ext, "text");
    }
}
