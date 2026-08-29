# DEFF ROW Sovereign AI Build Record

**Owner:** DEFF ROW  
**Persistent AI identity:** AKUJI  
**Status date:** 2026-08-29  
**Working branch:** `akuji-android-foundation`  
**Pull request:** #2 — draft, open, and not approved for merge

## Truth standard

A file, script, screen mockup, workflow, or successful code compilation is not proof that a working AI system or cloud deployment exists.

Something is marked **working** only after it is run in the intended environment and its result is directly verified. Planned or generated code must be labeled as code only.

## Plain-language rule

AKUJI is intended to become the permanent DEFF ROW system. Gemma, Qwen, DeepSeek, Gemini, and other models will be replaceable reasoning engines. Changing a model must not erase AKUJI's identity, memory, permissions, tools, work history, or projects.

Mya does not code. AKUJI must handle technical implementation and ask Mya only for account sign-ins, approvals, identity checks, physical-device actions, or owner decisions that cannot be completed without her.

## Verified right now

- The private GitHub repository `MyaMonroe/myapp` exists and is accessible.
- The branch `akuji-android-foundation` exists.
- PR #2 exists, is open, remains a draft, and is not merged.
- The repository contains non-empty Android, bridge, workflow, and deployment-source files.
- GitHub reports that Android build run 176 and signed-build run 160 completed successfully for commit `f804846aef5a2896386fafc3599745c91dd50fd1`.

These facts prove that source files exist and GitHub compiled a build. They do not prove that the APK works on Mya's phone or that any server, model, tool, or cloud system is live.

## Not verified and not to be claimed as existing

- No Google Cloud project or VM has been verified.
- No live AKUJI bridge has been verified.
- No Cloudflare tunnel has been verified.
- No working DEFF ROW control plane exists yet.
- No shared external agent memory exists yet.
- No agent-to-agent communication, durable job queue, model router, external permission controller, or audit ledger exists yet.
- No current phone-to-server connection has been verified.
- No Picsart execution has been verified.
- No Gemma model installation or inference has been verified on Mya's phone.
- No Google Live/Firebase runtime connection has been verified.
- The files under `deploy/` are unexecuted deployment instructions unless a real host is later created and tested.
- The files under `bridge/` are source code, not evidence of a running bridge.

## Required system shape

1. DEFF ROW website/app
2. DEFF ROW-owned control plane
3. Primary orchestrator
4. Replaceable model adapters for Qwen, Gemma, DeepSeek, and other models
5. Specialist agents
6. Shared external memory and project state
7. Controlled tools and APIs
8. Permission controller outside every model
9. Append-only audit and event history
10. Human approval gates for sensitive actions

Models may request permission. Models may never grant themselves permission.

## Permanent components to build

- Identity registry
- Project registry
- Orchestrator
- Agent registry and agent communication
- Model router and provider adapters
- External memory service
- Durable task/event queue with retries and recovery
- Tool gateway
- External permission controller
- Append-only audit ledger
- DEFF ROW website/app interface

## Implementation order

1. Inspect and validate the APK produced by the successful GitHub build.
2. Install and test that exact APK on the phone.
3. Record which phone features actually work.
4. Build the first runnable DEFF ROW control-plane service in the repository.
5. Add the permission controller and audit ledger.
6. Add persistent external memory and project records.
7. Add the durable queue, retries, and recovery.
8. Add the orchestrator and specialist-agent registry.
9. Add replaceable Qwen, Gemma, and DeepSeek adapters.
10. Put every tool behind the permission-controlled gateway.
11. Connect the Android app and DEFF ROW website.
12. Create cloud infrastructure only when a real deployment is ready to be run and verified.

## Next verified action

Download and inspect the APK artifact that GitHub says it built. Do not ask Mya to create a cloud project or run code.

## Owner action needed now

None. GitHub access is already connected. Mya will only be asked to install the verified APK on the phone after the artifact itself has been checked.
