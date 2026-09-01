"""
RAG orchestration: builds grounded prompts from retrieved chunks and parses
the model's response back into the API's response shape.

Hallucination-mitigation strategy (implemented, not just claimed):
  1. The system prompt instructs the model to answer ONLY using the supplied
     context chunks.
  2. Every chunk is labeled with its file path + line range, and the model is
     told to cite them inline (e.g. "(src/Foo.java:12-30)").
  3. If the context doesn't cover the question, the model is instructed to
     say so verbatim rather than guessing.
  4. Citations returned to the caller are derived from the actual retrieved
     chunks (not free-text parsed from the model), so citation metadata is
     always accurate even if the model's inline citation text is imperfect.
"""
from __future__ import annotations

import re

from app.models.schemas import Citation, QaTurn, SearchResult
from app.services.llm import UNTRUSTED_CONTENT_NOTICE, LLMClient, LLMRateLimitedError
from app.services.vector_store import RetrievedChunk

NO_CONTEXT_ANSWER = "I don't have enough information in the indexed code to answer that."

# Nudge used to retry a refusal that shouldn't have happened -- see the retry in answer_question().
_REFUSAL_RETRY_NUDGE = """

Reminder: the CONTEXT you were given above already contains real code from this repository -- \
you are not missing it. If your instinct is to refuse because a literal, exhaustive version of \
the request isn't feasible in one response (e.g. "line by line" across an entire multi-file \
repository), that is NOT the same as having no basis to answer -- give the best grounded answer \
you can from what's shown instead (e.g. a representative walkthrough of the most important parts, \
noting it's a subset if the full scope doesn't fit). Reserve the "I don't have enough information" \
refusal for when the context is genuinely about something other than what's being asked."""

# The model is instructed to start a response with exactly this marker (own first line) when it's
# answering from general knowledge rather than the repository's indexed content. Detected via
# answer_question() so citations from the (irrelevant) retrieved chunks never get attached to a
# general-knowledge answer -- that would misleadingly imply the repo was the source.
GENERAL_KNOWLEDGE_MARKER = "[General knowledge, not from this repository's code]"

# QUERY_SYSTEM_PROMPT tells the model to treat chitchat (greetings, thanks, goodbyes) as not
# needing the "not enough information" refusal -- and gives "thanks" as a literal example word.
# In practice this wasn't reliable: a smaller local (Ollama) model correctly recognized "hi"/
# "hello" as chitchat, but still refused "thanks", "okay thanks", and "ok cool" with the strict
# no-context answer, even with "thanks" spelled out in the prompt. Model instruction-following for
# this is provider-dependent (larger cloud models handled it fine); detecting it in code instead
# makes the behavior 100% reliable regardless of which LLM is active, and skips an LLM call
# entirely for what's always a throwaway exchange anyway.
_CHITCHAT_WORDS = frozenset({
    "hi", "hey", "hello", "yo", "sup", "hiya",
    "thanks", "thank", "thx", "ty", "you",
    "ok", "okay", "k", "kk", "cool", "nice", "great", "awesome", "good", "perfect", "sounds", "one",
    "bye", "goodbye", "later", "cya", "see",
    "there", "a", "lot", "so", "much", "very", "really", "man", "dude",
})
_FAREWELL_WORDS = frozenset({"bye", "goodbye", "later", "cya"})


def is_chitchat(question: str) -> bool:
    """A short message made up entirely of greeting/acknowledgment/farewell words and filler --
    "hi", "thanks", "okay thanks", "ok cool", "thanks a lot" -- as opposed to an actual question
    about the repository, even a short one ("what is 4sum" isn't chitchat despite being short)."""
    words = re.findall(r"[a-zA-Z']+", question.lower())
    if not words or len(words) > 5:
        return False
    return all(w in _CHITCHAT_WORDS for w in words)


def chitchat_response(question: str) -> str:
    if set(re.findall(r"[a-zA-Z']+", question.lower())) & _FAREWELL_WORDS:
        return "See you around! Come back anytime you have questions about this repo."
    return (
        "Happy to help! Ask me anything about this codebase -- I can point you to specific "
        "files, functions, and line numbers."
    )


_OFF_TOPIC_CLASSIFIER_PROMPT = """Decide whether the QUESTION below could plausibly be about a \
software repository or codebase -- even if you don't personally know the answer, even if it's \
about setup, architecture, code quality, authorship, or anything code-adjacent -- versus being \
about something else entirely (a general-knowledge fact, an unrelated topic, an expletive or \
nonsense input).

Respond with exactly one word: INSCOPE or OFFTOPIC. Nothing else -- no punctuation, no \
explanation."""

GENERAL_KNOWLEDGE_SYSTEM_PROMPT = """You are a helpful, knowledgeable assistant. Answer the \
user's question directly and concisely from your own general knowledge. This question is NOT \
about any codebase or repository, so don't mention one, don't apologize for lacking repository \
context, and don't hedge -- just answer it the way any good assistant would."""


async def _looks_off_topic(llm: LLMClient, question: str) -> bool:
    """A second, much simpler and narrower opinion, used only as a fallback when the main
    grounded-answer prompt already refused -- see the call site for why this exists. Fails closed
    (treats an error here as "not off-topic," i.e. keeps the original refusal) since this is a
    fallback path, not core functionality; a broken classifier should never turn into a crash."""
    try:
        verdict = await llm.complete(
            system=_OFF_TOPIC_CLASSIFIER_PROMPT,
            user=question,
            max_tokens=10,
            temperature=0.0,
        )
    except Exception:
        return False
    return "offtopic" in verdict.strip().lower().replace(" ", "").replace("_", "").replace("-", "")

# ---------------------------------------------------------------------------
# Hybrid retrieval: keyword/symbol/filename search as a supplement to vector
# similarity search (see keyword_search() in vector_store.py for the DB side).
# ---------------------------------------------------------------------------

_STOPWORDS = frozenset({
    "the", "is", "are", "a", "an", "this", "that", "does", "do", "how", "what",
    "where", "when", "why", "which", "who", "and", "or", "for", "in", "on", "of",
    "to", "it", "its", "with", "about", "explain", "find", "show", "me", "give",
    "tell", "can", "you", "your", "yours", "i", "we", "project", "code", "file",
    "files", "does", "there", "here", "have", "has", "had", "these", "those",
})

_FILENAME_RE = re.compile(r"\b[\w-]+\.[a-zA-Z]\w{1,5}\b")
_IDENTIFIER_RE = re.compile(r"\b(?:[A-Z][a-z0-9]+){2,}\b|\b[a-z0-9]+(?:_[a-z0-9]+)+\b|\b[a-z]+[A-Z]\w*\b")
# Every pattern above requires a LEADING LETTER, so a digit-first identifier is invisible to all
# three -- real bug: "explain the approach used in 4SUM" extracted only
# ["approach", "used"], completely missing "4SUM" itself, on a repo whose actual file is
# `4sum.cpp`. This is a common naming convention specifically in DSA/LeetCode-style repos ("2Sum",
# "3Sum", "4Sum", "132Pattern", "01Matrix"...), not an edge case. Requires at least one letter
# (not just `\d+`) so a plain number like "42" or "100" -- meaningless as a search keyword -- still
# doesn't get swept in.
_DIGIT_LED_IDENTIFIER_RE = re.compile(r"\b[0-9][A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*\b")


def extract_keywords(question: str) -> list[str]:
    """Pulls filenames, code identifiers (CamelCase/snake_case/mixedCase/digit-led), and
    otherwise-significant plain words out of a question -- the keyword/exact-match side of hybrid
    retrieval, supplementing vector similarity search. A query naming a specific class/function/file
    should find it via a literal match even if the local hashing-based embedding provider ranks it
    poorly by cosine distance."""
    keywords: list[str] = []
    for m in _FILENAME_RE.finditer(question):
        keywords.append(m.group(0))
    for m in _IDENTIFIER_RE.finditer(question):
        keywords.append(m.group(0))
    for m in _DIGIT_LED_IDENTIFIER_RE.finditer(question):
        keywords.append(m.group(0))
    for word in re.findall(r"[A-Za-z][A-Za-z0-9]{3,}", question):
        if word.lower() not in _STOPWORDS:
            keywords.append(word)

    seen: set[str] = set()
    deduped: list[str] = []
    for kw in keywords:
        lw = kw.lower()
        if lw not in seen:
            seen.add(lw)
            deduped.append(kw)
    return deduped[:8]


# A regex over the general SHAPE of a broad question, not an enumerated list of exact phrases.
# Real bug, hit twice: "explain the code" was added as an exact marker, then the very next
# report was "explain code" (no article) -- a different phrasing of the identical request that an
# exact-phrase list can never keep up with. `.{0,30}` lets a few words sit between the verb and
# its object ("explain the whole project", "walk me quickly through the code") without turning
# into a near-universal match.
_BROAD_QUESTION_RE = re.compile(
    r"\b(explain|describe|summarize|overview\s+of|walk\s+(me\s+)?through|tell\s+me\s+about)\b"
    r".{0,30}\b(code|codebase|project|repo|repository|architecture|everything)\b"
    r"|\bwhat\s+does\s+(this|the)?\s*(code|codebase|project|repo|repository)\s+do\b"
    r"|\b(entire|whole)\s+(project|repo|repository|codebase)\b"
    r"|\b(data|control)\s+flow\b"
    r"|\b(architecture|overview|summary)\b",
    re.IGNORECASE,
)


def adaptive_top_k(question: str, requested_top_k: int) -> int:
    """Broad questions ("explain the architecture") genuinely need context spread across more
    files than narrow ones ("where is X called") -- one fixed top_k for every question shape
    under-serves the broad ones. Never reduces below what was explicitly requested."""
    if _BROAD_QUESTION_RE.search(question):
        return max(requested_top_k, 15)
    return requested_top_k


def merge_retrieved_chunks(
    vector_chunks: list[RetrievedChunk], keyword_chunks: list[RetrievedChunk], limit: int
) -> list[RetrievedChunk]:
    """Combines vector-similarity and keyword/symbol-match results, deduped by (file, line
    range). Priority: chunks found by BOTH methods first (strongest signal), then keyword-only
    matches (a literal hit the embedding may have ranked poorly), then vector-only matches
    ordered by distance."""
    def key(c: RetrievedChunk) -> tuple[str, int, int]:
        return (c.file_path, c.start_line, c.end_line)

    vector_by_key = {key(c): c for c in vector_chunks}
    keyword_by_key = {key(c): c for c in keyword_chunks}

    both = [vector_by_key[k] for k in vector_by_key if k in keyword_by_key]
    keyword_only = [c for k, c in keyword_by_key.items() if k not in vector_by_key]
    vector_only = sorted(
        (c for k, c in vector_by_key.items() if k not in keyword_by_key),
        key=lambda c: c.distance,
    )

    return (both + keyword_only + vector_only)[:limit]


def build_search_results(
    merged: list[RetrievedChunk], keyword_chunks: list[RetrievedChunk]
) -> list[SearchResult]:
    """Converts merged hybrid-retrieval chunks into the /search endpoint's response shape. A
    result found via keyword/symbol/filename match is labeled "exact" with no relevanceScore --
    it has no meaningful cosine distance to report, and a fabricated number there would just be
    noise. A vector-only result gets "similarity" with relevanceScore = 1 - distance, clamped to
    [0, 1] since raw cosine distance can exceed that range."""
    keyword_keys = {(c.file_path, c.start_line, c.end_line) for c in keyword_chunks}
    results = []
    for c in merged:
        is_exact = (c.file_path, c.start_line, c.end_line) in keyword_keys
        results.append(
            SearchResult(
                filePath=c.file_path,
                language=c.language,
                startLine=c.start_line,
                endLine=c.end_line,
                snippet=c.content,
                symbolName=c.symbol_name,
                matchType="exact" if is_exact else "similarity",
                relevanceScore=None if is_exact else round(max(0.0, min(1.0, 1 - c.distance)), 3),
            )
        )
    return results


# Reuses the same "what's a good starting point" heuristic as onboarding.py's entry-point
# prioritization, applied here to rescue broad questions from a real retrieval failure mode.
# Includes Next.js App Router conventions (page/layout/route) alongside the older
# main/app/index style -- a real gap: a Next.js repo's actual application files
# (app/[lang]/(home)/page.tsx, app/[lang]/layout.tsx) matched none of the original hint words,
# so the fallback picked up nothing but nested sub-projects' READMEs and a bare top-level README.
_ENTRY_POINT_HINTS = re.compile(
    r"(^|/)readme\.md$"
    r"|(^|/)(main|app|index|application|program|startup|server|settings|config|page|layout|route)"
    r"\.(java|py|js|ts|jsx|tsx|go|rb)$"
    r"|(^|/)(package\.json|pom\.xml|build\.gradle|requirements\.txt|dockerfile|docker-compose\.ya?ml)$",
    re.IGNORECASE,
)


def prioritize_representative_chunks(
    candidates: list[RetrievedChunk], limit: int
) -> list[RetrievedChunk]:
    """For broad questions ("explain the code"), pure similarity search can come back with
    NOTHING relevant, not just imperfectly-ranked results: observed live on a 1300+ chunk repo
    where even the top 20 vector matches were all LICENSE text and an unrelated nested project's
    README -- the generic phrasing shares no real vocabulary with actual application code, and the
    local hashing-based embedding provider has no semantic understanding to bridge that gap.
    README/entry-point/config files are a genuine starting point regardless of embedding quality,
    so this is meant to be blended in ALONGSIDE similarity search for broad questions, not to
    replace it. `candidates` is expected to already be at most one chunk per file (e.g. from
    VectorStore.sample_chunks_per_file), so this only needs to pick and order, not dedupe files."""
    prioritized = sorted(
        candidates,
        key=lambda c: (0 if _ENTRY_POINT_HINTS.search(c.file_path) else 1, c.file_path, c.start_line),
    )
    return prioritized[:limit]

QUERY_SYSTEM_PROMPT = f"""You are CodePilot, a codebase Q&A assistant. You answer questions about a \
specific software repository using ONLY the provided code context -- never your general \
knowledge or assumptions about what the code "probably" does. The one exception is questions with \
no connection to the codebase at all -- see the general-knowledge rule below.

Rules:
- If the user's message is a greeting, thanks, or casual chitchat rather than an actual question \
about the repository (e.g. "hey", "hi", "thanks", "who are you"), respond briefly and naturally \
-- e.g. invite them to ask about the codebase. Do NOT use the "not enough information" refusal \
below for these; that refusal is only for genuine code questions the context can't answer.
- For an actual question about the code: base your answer strictly on the context chunks given \
below. Do not invent file names, functions, or behavior that isn't shown.
- For setup/run/dependency questions ("how do I run this", "what does this need to work"), a \
reasonable inference FROM WHAT'S VISIBLE in the context counts as grounded, not guessing -- e.g. \
seeing `import requests` and `from PyQt5.QtWidgets import ...` at the top of a `.py` file \
legitimately supports "install PyQt5 and requests, then run `python <filename>.py`", even with no \
README chunk. The line is: describe what the visible imports/entry point/structure actually imply, \
but don't invent specifics that aren't shown (e.g. don't state an exact package version, a config \
flag, or an installation command your context gives no basis for).
- When you reference code, cite the specific file path and line range it came from, in the \
form (path:startLine-endLine).
- For subjective/qualitative requests ("rate this", "is this good code", "what do you think of \
this", "does this look copied/AI-generated/written by a beginner", "is this original"), give a \
real opinion grounded in specific things you can point to in the context (e.g. "hardcoded API key \
at line X", "no error handling around Y", "clear separation of concerns in Z", "tutorial-style \
placeholder text at line W", "inconsistent naming between file A and file B") -- don't refuse just \
because the question isn't asking for a plain fact, and don't treat "asks about the code's \
origin/authorship" as a different, off-limits category from "asks for an opinion about the code" \
-- it's the same category. You can never know FOR CERTAIN whether code was copied or who wrote \
it, but you can and should point out concrete signals visible in the context that bear on the \
question (hardcoded test credentials, tutorial-style comments/branding, inconsistent style \
between files, generic boilerplate vs. project-specific logic) -- that is a grounded, legitimate \
answer, not a guess, as long as you're citing what you actually see rather than asserting a \
verdict you can't support. A flat refusal is only correct when the context truly gives you \
nothing to point to either way. A rating with no supporting evidence from the context is still a \
guess and should be avoided; a rating that cites specific observations is a legitimate grounded \
answer.
- Every question is being asked about THIS repository unless it's unambiguously about something \
else entirely (general knowledge, an unrelated topic). Ambiguous phrasing that could mean either \
"about this codebase" or "something else" -- e.g. "suggest me a better project" (could mean "make \
this project better" or "recommend a different project") -- should be read as being about this \
codebase, since that's what the whole conversation is about. Answer the in-scope reading (in this \
example: concrete improvements to the current code, grounded in specific things you see) rather \
than refusing over the ambiguity.
- If the question IS clearly about this codebase (or ambiguously could be) but the provided \
context gives no basis for answering it -- not a fact, not a reasonable inference, not even a \
groundable opinion -- respond with exactly: "I don't have enough information in the indexed code \
to answer that." Do not guess beyond what the visible context supports. This is for a genuine gap \
in what's indexed (e.g. asking about a file/feature that isn't in the context), not for questions \
that are simply unrelated to the codebase -- see the next rule for those.
- If the question is clearly NOT about this codebase at all (general knowledge, an unrelated \
topic -- e.g. "what's the police number", "capital of France") -- don't refuse. Answer it using \
your own general knowledge, starting your response with exactly this marker on its own first \
line, then a blank line, then your answer: {GENERAL_KNOWLEDGE_MARKER}
  For facts that vary by place/context you have no way to know here (emergency numbers, legal or \
tax specifics, etc.), say so and give the general guidance rather than asserting one answer as \
universal (e.g. "call your local emergency number -- e.g. 911 in the US, 112 in the EU, 100/112 \
in India" rather than just picking one).
- You may also be given prior turns from this conversation. Use them ONLY to understand what \
the user is asking now -- e.g. resolving "it"/"that"/"the one you mentioned" or a follow-up \
like "does it handle errors?". Never treat a prior answer as a source of fact; every claim in \
your new answer must still come from the CONTEXT chunks given for this question.
- Be concise and technical. Standard markdown (headers, bold, lists, fenced code blocks) is \
supported and encouraged where it aids clarity. Do not use LaTeX/math notation ($$...$$) -- \
write formulas in plain text or code instead, e.g. `f = (k * 9/5) - 459.67`."""

MAX_HISTORY_TURNS = 6


def build_query_prompt(question: str, chunks: list[RetrievedChunk], history: list[QaTurn] | None = None) -> str:
    if not chunks:
        context_block = "(no relevant code was found in the index)"
    else:
        pieces = []
        for c in chunks:
            symbol_suffix = f", symbol: {c.symbol_name}" if c.symbol_name else ""
            pieces.append(
                f"--- {c.file_path} (lines {c.start_line}-{c.end_line}, {c.language or 'unknown'}"
                f"{symbol_suffix}) ---\n"
                f"{c.content}"
            )
        context_block = "\n\n".join(pieces)

    history_block = ""
    if history:
        recent = history[-MAX_HISTORY_TURNS:]
        turns = "\n\n".join(f"Q: {t.question}\nA: {t.answer}" for t in recent)
        history_block = f"PRIOR CONVERSATION (for resolving references only, not as a source of fact):\n{turns}\n\n"

    return (
        f"{history_block}"
        f"CONTEXT:\n{context_block}\n\n"
        f"QUESTION:\n{question}\n\n"
        "Answer the question using only the context above, citing file paths and line "
        "ranges. If the context is insufficient, say so exactly as instructed."
    )


def _fallback_answer_from_chunks(chunks: list[RetrievedChunk]) -> str:
    """A deterministic, no-LLM-call answer: just presents the retrieved chunks directly. Used
    only as the last resort in answer_question() when both the main pass and the corrective retry
    wrongly refused an already-confirmed in-scope, non-empty-context question -- see the call
    site. Grouped by file (a chunk-per-bullet list reads worse than a per-file summary when
    several chunks come from the same file, which is common for a focused question)."""
    by_file: dict[str, list[RetrievedChunk]] = {}
    for c in chunks:
        by_file.setdefault(c.file_path, []).append(c)

    lines = [
        "I wasn't able to put together a full written explanation, but here's the code this "
        "question matched most closely:",
        "",
    ]
    for file_path, file_chunks in by_file.items():
        ranges = ", ".join(f"{c.start_line}-{c.end_line}" for c in file_chunks)
        lines.append(f"- **{file_path}** (lines {ranges})")
    return "\n".join(lines)


async def answer_question(
    llm: LLMClient, question: str, chunks: list[RetrievedChunk], history: list[QaTurn] | None = None
) -> tuple[str, list[Citation]]:
    if is_chitchat(question):
        return chitchat_response(question), []

    citations = [
        Citation(
            filePath=c.file_path,
            startLine=c.start_line,
            endLine=c.end_line,
            snippet=c.content[:400],
        )
        for c in chunks
    ]

    if not chunks:
        return NO_CONTEXT_ANSWER, []

    prompt = build_query_prompt(question, chunks, history)
    # 2048, not 1024: a broad question ("explain this codebase") legitimately needs room for a
    # multi-part answer with citations -- 1024 was cutting real answers off mid-sentence.
    # temperature=0.0, not 0.2 (or the 0.1 this was first tightened to): live testing caught TWO
    # separate real instances of this exact failure mode -- the identical question ("reasons to
    # skip classes", later "explain code") non-deterministically got the strict refusal on one
    # call and a correct grounded answer on a retry, with nothing else different. Confirmed at 0.1
    # a rare refusal still slipped through (1 real case out of 5 live attempts on "explain code").
    # This prompt is a classification decision (grounded / refuse / general-knowledge) more than
    # creative writing, so it should run as close to deterministic as Gemini allows -- there's no
    # real creativity being traded away here, only consistency being bought.
    # fast=True: chatbot latency was dominated by Gemini's internal "thinking" step (observed
    # spending reasoning tokens even on trivial prompts) -- answering from given context is
    # synthesis, not the kind of exploratory problem-solving thinking mode is for.
    try:
        answer = await llm.complete(
            system=QUERY_SYSTEM_PROMPT + UNTRUSTED_CONTENT_NOTICE,
            user=prompt,
            max_tokens=2048,
            temperature=0.0,
            fast=True,
        )
    except LLMRateLimitedError:
        # Real problem, hit live: a free-tier LLM quota (e.g. Gemini's 20 requests/day) runs out
        # under completely normal testing/demo usage well before a day is up. Before this, a
        # rate-limited call propagated all the way out as a raw 429 to the frontend -- a dead end
        # even though `chunks` is right here, non-empty and just as usable as it is for the
        # refusal-recovery fallback below. Skip straight to it: no point spending a second and
        # third quota-exhausted call on the off-topic-check/retry dance first, since those would
        # only hit the identical rate limit again.
        return _fallback_answer_from_chunks(chunks), citations

    # If the model itself declares insufficient context, don't attach
    # citations that would misleadingly imply grounding was used.
    if NO_CONTEXT_ANSWER.lower() in answer.lower():
        # QUERY_SYSTEM_PROMPT already instructs the model to use general knowledge instead of
        # refusing for clearly off-topic questions ("what's the capital of France" etc.) -- but
        # in practice, that single mega-prompt (grounded-answer rules + chitchat carve-out +
        # subjective-opinion carve-out + general-knowledge carve-out, all at once) isn't reliably
        # followed by a smaller model: "give me some details on nepal" got the flat refusal
        # instead of a real answer. Before trusting the refusal, ask again with a much simpler,
        # single-purpose prompt -- a smaller model is far more reliable at one focused
        # classification than at juggling several rules in one pass (the same reasoning behind
        # moving chitchat detection out of the prompt entirely).
        try:
            if await _looks_off_topic(llm, question):
                gk_answer = await llm.complete(
                    system=GENERAL_KNOWLEDGE_SYSTEM_PROMPT,
                    user=question,
                    max_tokens=1024,
                    temperature=0.3,
                    fast=True,
                )
                return f"_{GENERAL_KNOWLEDGE_MARKER}_\n\n{gk_answer.strip()}", []

            # Confirmed off-topic-recheck says this question IS about the repo, and `chunks` is
            # non-empty (the early "if not chunks" return above already handled the truly-empty
            # case) -- so the refusal is wrong, not correct-but-unwelcome. Reproduced live against
            # a real indexed repository: "explain me the code line by line" retrieved 23 real,
            # relevant chunks (e.g. dotnet/src/Client.cs) yet still got the flat refusal -- the
            # model over-generalizes "I can't do this literally" (an exhaustive line-by-line
            # account of an entire multi-file repo) into "I have no basis at all," which isn't
            # true. One retry with an explicit corrective nudge resolves it in practice (same
            # non-determinism the 0.0 temperature comment above already documents); only give up
            # and show the refusal if the model insists a second time.
            retry_answer = await llm.complete(
                system=QUERY_SYSTEM_PROMPT + UNTRUSTED_CONTENT_NOTICE + _REFUSAL_RETRY_NUDGE,
                user=prompt,
                max_tokens=2048,
                temperature=0.0,
                fast=True,
            )
        except LLMRateLimitedError:
            # Same reasoning as the main pass's rate-limit handling above: quota exhaustion mid-
            # recovery is still not the same as "genuinely no basis to answer" -- fall back to the
            # deterministic, grounded chunk listing instead of surfacing a raw 429 partway through
            # what was otherwise a legitimate refusal-recovery attempt.
            return _fallback_answer_from_chunks(chunks), citations
        if NO_CONTEXT_ANSWER.lower() in retry_answer.lower():
            # Live testing showed the retry above, while it resolves most cases, is still a
            # second roll of the same non-deterministic dice -- it can refuse twice in a row on a
            # stubborn phrasing ("line by line" reproduced this exact sequence). At this point
            # in-scope and non-empty-context are already CONFIRMED, not guessed, so showing the
            # user a dead-end refusal would be actively wrong, not just unhelpful. Fall back to a
            # deterministic, non-LLM answer built directly from the chunks themselves -- it can't
            # refuse because no model call is involved, and it's still fully grounded (every line
            # comes from a real retrieved chunk with a real citation).
            return _fallback_answer_from_chunks(chunks), citations
        answer = retry_answer

    # A general-knowledge answer (question unrelated to the repo) isn't grounded in the retrieved
    # chunks at all -- attaching them as citations would misleadingly imply the repo was the
    # source. Strip the marker itself out of the visible answer text.
    stripped = answer.strip()
    if stripped.lower().startswith(GENERAL_KNOWLEDGE_MARKER.lower()):
        remainder = stripped[len(GENERAL_KNOWLEDGE_MARKER):].lstrip()
        return f"_{GENERAL_KNOWLEDGE_MARKER}_\n\n{remainder}", []

    return answer.strip(), citations


ONBOARDING_SYSTEM_PROMPT = """You are CodePilot, generating onboarding documentation for a new \
engineer joining a repository. You are given a sample of representative code chunks from the \
repository (not the full codebase). Using only what is shown, produce a JSON object with these \
exact keys:

{
  "architectureOverview": "<2-4 paragraph prose overview of the system's architecture>",
  "importantModules": [{"path": "<file path>", "description": "<1-2 sentence description>"}],
  "setupInstructions": "<how to get this project running locally, based on any config/build files shown>",
  "dataFlow": "<prose description of how data/requests flow through the system>",
  "readFirst": ["<file path>", "..."]
}

Respond with ONLY the JSON object, no markdown fences, no commentary. If some information isn't \
evident from the sample, make a reasonable, clearly-hedged inference rather than fabricating specifics."""


def build_onboarding_prompt(chunks: list[RetrievedChunk]) -> str:
    pieces = []
    for c in chunks:
        pieces.append(
            f"--- {c.file_path} (lines {c.start_line}-{c.end_line}, {c.language or 'unknown'}) ---\n"
            f"{c.content}"
        )
    context_block = "\n\n".join(pieces) if pieces else "(no chunks available)"
    return f"REPOSITORY CODE SAMPLE:\n{context_block}\n\nGenerate the onboarding JSON object now."
