"""
Runs the four review agents CONCURRENTLY (asyncio.gather) against the PR's
changed files, then merges their findings plus an LLM-generated summary
paragraph into the final ReviewResponse.
"""
from __future__ import annotations

import asyncio

from app.agents.bug_agent import BugDetectionAgent
from app.agents.quality_agent import CodeQualityAgent
from app.agents.security_agent import SecurityAgent
from app.agents.test_agent import TestCoverageAgent
from app.models.schemas import Finding, ReviewFileInput, ReviewFindings, ReviewResponse
from app.services.llm import UNTRUSTED_CONTENT_NOTICE, LLMClient

SUMMARY_SYSTEM_PROMPT = """You are CodePilot, summarizing an automated multi-agent pull request \
review for a human developer. You will be given the list of findings (bugs, security issues, \
code smells, missing tests, performance issues) already discovered by specialist review agents. \
Write ONE concise paragraph (3-6 sentences) summarizing the overall state of the PR: highlight \
the most important issues (especially any high severity ones), note if the PR looks generally \
sound, and give the developer a sense of what to prioritize. Do not repeat every finding \
verbatim -- synthesize. Respond with plain prose only, no JSON, no markdown headers."""


class ReviewOrchestrator:
    def __init__(self) -> None:
        self.security_agent = SecurityAgent()
        self.bug_agent = BugDetectionAgent()
        self.test_agent = TestCoverageAgent()
        self.quality_agent = CodeQualityAgent()

    async def run(self, llm: LLMClient, files: list[ReviewFileInput]) -> ReviewResponse:
        # Run all four agents concurrently -- each makes its own independent LLM call.
        security_task = self.security_agent.review(llm, files)
        bug_task = self.bug_agent.review(llm, files)
        test_task = self.test_agent.review(llm, files)
        quality_task = self.quality_agent.review_categorized(llm, files)

        security_findings, bug_findings, missing_tests, (code_smells, performance) = await asyncio.gather(
            security_task, bug_task, test_task, quality_task
        )

        findings = ReviewFindings(
            bugs=bug_findings,
            security=security_findings,
            codeSmells=code_smells,
            missingTests=missing_tests,
            performance=performance,
        )

        summary = await self._summarize(llm, findings)

        return ReviewResponse(summary=summary, findings=findings)

    async def _summarize(self, llm: LLMClient, findings: ReviewFindings) -> str:
        total = (
            len(findings.bugs)
            + len(findings.security)
            + len(findings.code_smells)
            + len(findings.missing_tests)
            + len(findings.performance)
        )
        if total == 0:
            return (
                "No significant issues were found by the automated review agents across "
                "security, correctness, test coverage, or code quality checks."
            )

        lines = []
        for label, items in (
            ("Bugs", findings.bugs),
            ("Security", findings.security),
            ("Code smells", findings.code_smells),
            ("Missing tests", findings.missing_tests),
            ("Performance", findings.performance),
        ):
            for f in items:
                loc = f"{f.file}:{f.line}" if f.line else f.file
                lines.append(f"- [{label} / {f.severity}] {loc}: {f.description}")

        prompt = "FINDINGS:\n" + "\n".join(lines) + "\n\nWrite the summary paragraph now."

        try:
            # 700, not 400: live testing with 7 findings across categories showed 400 was tight
            # enough to truncate the summary mid-sentence and append the truncation note to it.
            return (await llm.complete(
                system=SUMMARY_SYSTEM_PROMPT + UNTRUSTED_CONTENT_NOTICE, user=prompt, max_tokens=700
            )).strip()
        except Exception:
            # Fall back to a simple deterministic summary if the LLM call fails
            # (e.g. not configured) -- findings themselves are still returned.
            sev_counts: dict[str, int] = {}
            for f in (
                findings.bugs + findings.security + findings.code_smells
                + findings.missing_tests + findings.performance
            ):
                sev_counts[f.severity] = sev_counts.get(f.severity, 0) + 1
            return (
                f"Automated review found {total} finding(s) "
                f"({', '.join(f'{v} {k}' for k, v in sev_counts.items())}). "
                "See the findings list for details."
            )
