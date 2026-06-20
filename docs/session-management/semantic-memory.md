# Semantic Memory Tier — Design Notes & Sources

## What we're adding

A second memory tier alongside the existing event-sourced (episodic) one. The first slice is small on purpose: an SPI, an in-memory–friendly adapter over Spring AI's `VectorStore`, and a `MemoryExtractor` hook for turning session events into long-lived facts. Working-memory tier and advisor integration come later.

## Why this shape

- **Wrap, don't duplicate.** The repo already follows this rule — `SessionEvent` is a thin wrapper over Spring AI's `Message` rather than a parallel type. Semantic memory does the same on top of `VectorStore` / `EmbeddingModel` / `Document`, so every backend Spring AI supports (pgvector, Redis, Chroma, Milvus, in-memory) works for free.
- **Separate module.** New code lives in `spring-ai-session-semantic` so the core management module stays free of an embedding-model dependency. Mirrors how `spring-ai-session-jdbc` is split out.
- **Episodic stays the source of truth.** Semantic records reference the originating `SessionEvent.id`. The episodic log is canonical; semantic memory is a derived, lossy index.

## Inspirations as asked

- **MemGPT** — the tiered-memory framing (working / recall / archival) and the idea that the agent itself can move information between tiers. [Packer et al., 2023, arXiv:2310.08560](https://arxiv.org/abs/2310.08560)
- **Mem0** — write-time fact extraction and dedupe between user turns and stored memories. [github.com/mem0ai/mem0](https://github.com/mem0ai/mem0)
- **CrewAI memory** — practical split of short-term, long-term, and entity memory for multi-agent crews. [docs.crewai.com/concepts/memory](https://docs.crewai.com/concepts/memory)

## Spring AI primitives reused

- `VectorStore` — storage and similarity search.
- `EmbeddingModel` — text → vectors.
- `Document` — record container the `VectorStore` already understands.

Please checkout the Spring AI reference docs for the current API: [docs.spring.io/spring-ai/reference](https://docs.spring.io/spring-ai/reference/).

## Project context

- Discussion PR: [issue #9](https://github.com/spring-ai-community/spring-ai-session/issues/9)
- Original PR for this issue: [spring-projects/spring-ai#5458](https://github.com/spring-projects/spring-ai/issues/5458)


Please correct me if I'm wrong! I'm open to all kinds of feedback.
