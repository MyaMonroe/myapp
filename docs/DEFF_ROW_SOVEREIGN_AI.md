# DEFF ROW Sovereign AI Build Record

**Owner:** DEFF ROW  
**Persistent AI identity:** AKUJI  
**Interface:** DEFF ROW website and AKUJI Android app  
**Status date:** 2026-08-29  
**Working branch:** `akuji-android-foundation`  
**Pull request:** #2 — draft, open, and not approved for merge

## Plain-language rule

AKUJI is the permanent system. Gemma, Qwen, DeepSeek, Gemini, and other models are replaceable brains AKUJI may use. Changing a model must not erase AKUJI's identity, memory, permissions, tools, work history, or projects.

Mya does not have to code or translate ordinary requests into technical commands. AKUJI must handle the technical work and ask Mya only for account sign-ins, approvals, identity checks, physical-device actions, or decisions that cannot be completed without the owner.

## Required system shape

1. The DEFF ROW website and AKUJI Android app are the owner-facing controls.
2. The DEFF ROW control plane owns identity, work, permissions, history, and routing.
3. The orchestrator chooses and supervises work.
4. Models are accessed through replaceable adapters.
5. Specialist agents perform bounded jobs.
6. Memory and project state live outside model chat sessions.
7. Tools and provider APIs are accessed only through the controlled tool gateway.
8. An external permission controller decides whether sensitive actions may run.
9. Models may request permission. Models may never grant themselves permission.
10. Every request, approval, denial, tool call, result, failure, retry, and model change is recorded.

## Verified existing foundation

### AKUJI Android

- Android package: `com.deffrow.akuji`
- Phone interface, voice, local memory, searchable imported archives, and bundled skills exist on the branch.
- Local memory can preserve facts, the active task, checkpoints, parked items, and transcripts.
- Gemma can run through AKUJI's local LiteRT-LM connector when its model file is installed.
- Google Live is currently available as a conversation and voice layer.
- The model connector is replaceable; the app body, local memory, and identity do not have to change with it.

### Direct tool bridge

- Existing service: `bridge/app.py`
- Authenticated status endpoint: `GET /v1/status`
- Authenticated tool endpoint: `POST /v1/operator/tool`
- Execution is disabled by default.
- Dry-run validates a requested tool without performing the external action.
- Current tools:
  - `bridge_echo`
  - `picsart_remove_background` when Picsart is deliberately connected
- The bridge is a tool gateway, not AKUJI's identity or brain.
- Deployment binds the bridge to `127.0.0.1:8787` and uses a Cloudflare tunnel.

### Build state

- Latest checked branch commit: `f804846aef5a2896386fafc3599745c91dd50fd1`
- "Build AKUJI Android APK" run 176: passed.
- "Build permanently signed AKUJI APK" run 160: passed.
- PR #2 remains a draft and must not be merged until the live bridge and phone tests pass.

## Current boundaries

- The current bridge is not yet the full sovereign control plane.
- Shared external memory, agent-to-agent communication, the durable job queue, model routing, centralized approval records, and full failure recovery are not yet implemented.
- The live VM deployment has not been re-verified from this record.
- The full phone-to-bridge dry-run has not been re-verified from this record.
- Harness remains an available development resource. Hermes is not an active dependency in the current PR architecture.
- No passwords, private signing material, API keys, tokens, or owner secrets belong in this file or repository.

## Permanent control-plane components

The control plane will be added as a separate service boundary so the Android app, website, models, agents, and tools can change independently.

- **Identity registry:** AKUJI's identity, rules, ownership, and version.
- **Project registry:** DEFF ROW projects, status, checkpoints, and dependencies.
- **Orchestrator:** accepts work, creates tasks, delegates jobs, and replans failures.
- **Agent registry:** specialist roles, capabilities, limits, and active assignments.
- **Model router:** Qwen, Gemma, DeepSeek, Gemini, and later-model adapters.
- **Memory service:** durable facts, decisions, task state, summaries, and source links.
- **Task and event queue:** scheduled and long-running work, retries, leases, and recovery.
- **Tool gateway:** approved browser, file, API, website, and provider actions.
- **Permission controller:** owner rules and human approval gates outside all models.
- **Audit ledger:** append-only record of decisions, permissions, actions, and results.

## Permission rule

Every external action is classified before execution:

- **Read:** may run when the owner policy permits it.
- **Write:** requires an explicit stored rule or owner approval.
- **Sensitive:** always pauses for the owner's approval.
- **Forbidden:** cannot run even if a model asks.

The orchestrator, specialist agents, and model adapters can create permission requests. Only the permission controller can return an authorization decision.

## Implementation order

1. Keep this record current.
2. Re-verify the live bridge and phone connection.
3. Define the shared request, task, permission, result, and audit-event formats.
4. Build the external permission controller and append-only audit ledger.
5. Add persistent external memory and the project registry.
6. Add the durable task/event queue, worker leases, retries, and recovery.
7. Add the orchestrator and specialist-agent registry.
8. Add the model router and Qwen, Gemma, and DeepSeek adapters.
9. Move every tool behind the controlled gateway.
10. Connect the website and Android app to the control plane.
11. Test model replacement, restart recovery, denied permissions, and complete audit history.

## Next verified action

Inspect the current bridge deployment and complete an authenticated dry-run from AKUJI Android through the bridge. Do not enable real provider execution during this test.

## Owner action needed now

None. GitHub repository access is already available through the connected GitHub account. A forgotten GitHub password does not block the current repository work.
