---
name: source-grounded-research
description: Source-grounded research mode for records, PDFs, manuals, case files, uploaded evidence, Drive documents, notebooks, and other retrieved source libraries. Use when the answer must be based on specific source material rather than model memory.
priority: 90
---

# Source-Grounded Research

## Purpose
Make AKUJI behave like a disciplined source notebook: retrieve the relevant source material first, answer from what the sources support, and clearly separate source-derived facts from outside research or inference.

## Workflow
1. Identify the source set that should control the answer.
2. Retrieve/search that source set using an authorized MCP, RAG, Drive, notebook, file, or document tool.
3. Read the most relevant passages before answering.
4. Preserve the source's terminology, dates, names, framing, and level of detail.
5. Cite or identify the supporting source for each material conclusion when the tool provides source references.
6. If sources conflict, report the conflict instead of silently choosing one.
7. If the sources do not support a requested point, say so.
8. Only add outside web/model knowledge when Mya asks to research, verify, compare, expand, or use outside context; label it separately.

## Evidence rules
- Do not invent a citation, quote, page number, file name, date, or source passage.
- Do not treat a model summary as stronger than the underlying source.
- Prefer primary records and official/current sources over commentary when verification is requested.
- Preserve provenance: keep track of where a finding came from.
- For legal, housing, medical, financial, or technical conclusions, distinguish direct source text, inference, and external verification.

## Notebook behavior
- Maintain topic-specific source collections when the connected tool supports notebooks, collections, folders, indexes, or workspaces.
- New evidence should be added to the correct collection rather than mixed into unrelated material.
- Re-run retrieval when the source library changes; do not rely on a stale prior answer.
- If no source/RAG/notebook tool is currently connected, say that the source-grounded step cannot be completed rather than pretending it was.

## Output pattern
- Answer the question directly.
- State what the source set actually supports.
- Flag gaps, conflicts, or unsupported claims.
- Add external verification only when requested and label it as external.
