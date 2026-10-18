# Testing Infrastructure Architecture: Self-Hosted Runner, Maestro, Automated Result Analysis

**Version:** 2.1
**Date:** 2026-09-28
**Scope:** Design of a CI/CD pipeline for end-to-end mobile application testing (Maestro) with automated failure analysis (Claude Code, headless mode), optimized to minimize AI compute costs.

---

## 1. Architecture Overview

### 1.1 Goal

Separate mechanical test execution (deterministic) from failure analysis (non-deterministic) so that AI compute costs are incurred only where analysis is genuinely needed, and so that the AI component never determines the resulting test status.

### 1.2 Core Principles

1. **The authoritative test result is produced exclusively by Maestro.** AI analysis must not alter or mask the pass/fail status. The final CI pipeline status equals the Maestro result, not the AI result.
2. **AI is invoked only on failure**, never on a successful run.
3. **Determinism before probability.** Known, pattern-recognizable failure classes (infrastructure, device offline, timeout) are classified by a deterministic parser before any AI call.

### 1.3 Data Flow

```
                  GitHub Actions
                        │
                        ▼
                Build application
                        │
               ┌────────┴────────┐
               ▼                 ▼
            Android             iOS
               │                 │
               └────────┬────────┘
                        ▼
                  Maestro suite
                        │
                ┌───────┴───────┐
                │               │
              PASS             FAIL
                │               │
                ▼               ▼
             finish       Collect artifacts
                                │
                                ▼
                     Deterministic classifier
                                │
                    ┌───────────┴───────────┐
                    │                       │
              known failure             unknown
              (no AI call)                  │
                    │                       ▼
                    │                  AI analysis
                    │                       │
                    └───────────┬───────────┘
                                ▼
                       normalized JSON
                                │
                         ┌──────┴──────┐
                         ▼             ▼
                    PR / Slack     Metrics/history

Final CI status = Maestro result, NOT AI result.
```

---

## 2. Workflow Step Implementation (GitHub Actions)

### 2.1 Quality-Gate Pattern — Chosen Approach

Two mechanisms were considered for guaranteeing that a failed test genuinely fails the CI job, while still allowing cleanup/artifact steps to run regardless of outcome.

**Rejected (unnecessarily complex):**
```yaml
- name: Run Maestro tests
  id: maestro
  continue-on-error: true
  run: maestro test ./e2e --format junit --output build/report.xml

- name: Enforce CI quality gate
  if: steps.maestro.outcome == 'failure'
  run: exit 1
```
This pattern requires `continue-on-error: true` to prevent the job from failing immediately, then an explicit compensating step to re-introduce that failure. If the compensating step is ever omitted, misconfigured, or has an incorrect condition, the job silently reports success despite a real test failure — the exact "AI analyzes failures, workflow reports SUCCESS" danger this architecture is designed to prevent.

**Adopted (simpler, lower risk):**
```yaml
- name: Run Maestro tests
  run: |
    maestro test ./e2e --format junit --output build/report.xml \
      --test-output-dir results/artifacts --debug-output results/debug

- name: Prepare artifacts for upload
  if: failure()
  run: |
    # stage only this run's new output (marker-file + find -newer pattern)

- name: Upload artifacts
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    retention-days: 3
```
No `continue-on-error` is used at all. A failing Maestro step fails the job through GitHub Actions' own default behavior — nothing suppresses or needs to re-assert that failure. Cleanup and artifact-upload steps use `if: always()` / `if: failure()`, which run unconditionally regardless of any earlier step's outcome, independent of whether `continue-on-error` was used anywhere. This achieves the identical guarantee (job fails on test failure; cleanup still runs either way) with strictly less surface area for misconfiguration.

**Implication for a future AI-analysis step:** if the AI analysis step needs to run only on failure, `if: failure()` on that step alone is sufficient — `continue-on-error` plus an explicit re-assertion step is not required unless a later step in the same job needs to make an independent pass/fail determination distinct from Maestro's own result, which is not currently the case in this design.

### 2.2 Maestro CLI Syntax

`--output` in the Maestro CLI designates the path to a JUnit report **file**, not an artifact directory. Artifacts (screenshots, video, command JSON records) and debug data (including `maestro.log`) are specified via the separate `--test-output-dir` and `--debug-output` flags. Meaningful AI analysis requires providing all three output categories, not the JUnit report alone.

### 2.3 First Implementation — Current State (2026-09-28)

The first working version of `.github/workflows/maestro-simulator.yml` is deliberately scoped to **P0 only** (section 12): mechanical test execution (two jobs, `android-tests` and `ios-tests` — see section 6.4) plus artifact-upload logic. It does **not** yet include an AI-analysis step. Rationale: the implementation sequence (section 12) explicitly establishes reliable, deterministic test execution as a prerequisite before adding an AI layer — AI analysis is planned as a separate, subsequent addition, not part of the first deployment.

Test coverage in this first version is limited to a single, live-verified flow (`.maestro/kmp/issuance/tc-01-pid-issuance-common.yaml`). The bulk-copied reference material (71 files carried over from the sibling native iOS project) is not wired in, since most of it contains unverified or confirmed-incorrect selectors (see the separate static-analysis correction pass from the same date).

A storage/quota audit was performed before first deployment (motivated by a recurring quota problem on the sibling iOS project) and confirmed: no implicit caching (`actions/cache`, `setup-java`/`setup-gradle` with default caching), shallow checkout (`fetch-depth` at its default), and artifact upload gated on `if: failure()` with an explicit `retention-days: 3` and symmetric exclusion of large, low-diagnostic-value log files (`device-logcat.txt` on the Android side, mirroring the equivalent exclusion on the iOS side) on both platforms.

---

## 3. Deterministic Pre-Filter (Classification Before AI)

### 3.1 Rationale

A significant share of failures in a mobile E2E context stem from recognizable, recurring patterns that do not require probabilistic analysis:

| Log pattern | Classification | Source |
|---|---|---|
| `adb: device offline` | DEVICE_FAILURE | regex on known string |
| `Unable to boot simulator` | DEVICE_FAILURE | regex on known string |
| Timeout exceeding a defined threshold with no UI state change | INFRA_FAILURE (candidate) | pattern in commands JSON |
| App installation failure | INFRA_FAILURE | regex on known string |
| Network timeout on a known backend endpoint | NETWORK_FAILURE | regex + list of monitored hosts |

### 3.2 Decision Logic

```
FAIL
 │
 ▼
Deterministic classifier (regex/parser over the debug log)
 │
 ├─ matches a known pattern → classify WITHOUT calling AI
 │
 └─ matches no known pattern → forward for AI analysis
```

This step directly extends the cost-efficiency principle beyond simply "AI only on failure" — it eliminates AI calls even in failure cases where the cause is deterministically identifiable.

---

## 4. AI Analysis — Output Contract and Taxonomy

### 4.1 Limitation of the Naive Approach

The `--output-format json` flag alone does not guarantee a specific domain schema — it provides Claude Code's response envelope/metadata, not automatically a structure such as `{"tests": [...]}`. A controlled schema requires `--json-schema` with an explicitly defined schema.

### 4.2 Output Schema

```json
{
  "summary": {
    "passed": 0,
    "failed": 0
  },
  "failures": [
    {
      "test": "string (path to flow file)",
      "classification": "PRODUCT_BUG | TEST_BUG | INFRA_FAILURE | DEVICE_FAILURE | NETWORK_FAILURE | UNKNOWN",
      "confidence": 0.0,
      "evidence": ["string"],
      "rootCause": "string | null",
      "alternativeExplanations": ["string"],
      "recommendedAction": "string"
    }
  ]
}
```

### 4.3 Failure Taxonomy

To enable long-term trending (not just individual reports), failures are classified into a fixed set of categories:

- `APPLICATION_FAILURE` — defect in the application under test
- `TEST_AUTOMATION_FAILURE` — defect in the test script/selector
- `TEST_DATA_FAILURE` — incorrect/invalid test data
- `ENVIRONMENT_FAILURE` — test-environment problem
- `DEVICE_SIMULATOR_FAILURE` — device/simulator problem
- `NETWORK_BACKEND_FAILURE` — network or backend problem
- `UNKNOWN` — insufficient evidence to classify

This taxonomy enables aggregation over time (e.g., "over the last 30 days: 17 product regressions, 21 flaky automation, 8 environment, 4 device, 2 unknown"), which has greater long-term value than an isolated text comment on a single run.

### 4.4 AI Guardrails

The prompt for AI analysis must explicitly allow and prefer an "unknown" response over an unsupported conclusion:

```
Determine the most likely failure classification and root cause only
when supported by available evidence. If evidence is insufficient,
return classification UNKNOWN with confidence below 0.5.

For every conclusion, provide:
- evidence (specific log lines / screenshot references)
- confidence score (0.0–1.0)
- alternative explanations, if any exist
```

This formulation reduces the risk of the model producing a confident-sounding but unsupported root-cause explanation instead of acknowledging insufficient evidence.

---

## 5. Flaky Test Strategy

The absence of a defined retry strategy in a mobile E2E context practically inevitably leads to eroded CI trust ("the build is red again, just re-run it"). The architecture must explicitly define:

| Parameter | Question to answer |
|---|---|
| Retry scope | Which failures are automatically retried (e.g., only DEVICE_FAILURE/INFRA_FAILURE, never APPLICATION_FAILURE) |
| Retry count | How many times a test is retried before being marked a confirmed failure |
| Effect on quality gate | Does a successful retry change the pipeline's final status, or is it recorded separately |
| Flaky-test evidence | Where and how instability history per test is recorded |
| Escalation threshold | At what instability rate a test is treated as requiring intervention |
| Ownership | Who is responsible for resolving a given flaky test |

Unrestricted, blanket retry of the entire suite risks masking genuine regressions — retry logic is therefore applied selectively, not uniformly.

---

## 6. Self-Hosted Runner — Configuration

### 6.0 Implementation Status (confirmed 2026-09-28)

A runner is registered and functional: `maestro-mac-01`, with labels `self-hosted, macOS, ARM64, maestro, mobile-e2e`. Registration is scoped to a single repository (`compote-eu/eudi-app-android-wallet-ui` — confirmed directly from the `.runner` configuration file's `gitHubUrl` field), satisfying the baseline repo-scoping requirement from section 7.2.

**Label decision:** Rather than introducing a new, dedicated label (originally considered: `kmp-maestro`), the workflow file was adjusted to use the runner's **existing** labels (`self-hosted, macOS, maestro`). Rationale: the primary security protection (single-repository scoping) is already established at runner-registration level, independent of the specific label name — introducing a new label would require de-registering/re-registering the runner with no corresponding security benefit. This choice is appropriate for the current state (one runner, one assigned project); if additional runners for distinct projects are added to the same physical machine in the future, more precise labels would improve GitHub UI clarity, though not security per se.

The runner operates on the same physical machine as the local development environment (same user account, shared filesystem) — concrete environment values (Android SDK paths, AVD/simulator names) are therefore directly verifiable from a local development shell session, which was used during initial workflow configuration instead of guessing.

### 6.1 Registration

1. Navigate to `https://github.com/<org>/<repo>/settings/actions/runners`
2. Select "New self-hosted runner"
3. Choose the target machine's operating system and architecture
4. The system generates installation commands with a one-time, time-limited token

### 6.2 Installation

```bash
mkdir -p <path>/actions-runner
cd <path>/actions-runner

curl -o actions-runner-osx-arm64.tar.gz -L \
  https://github.com/actions/runner/releases/download/v<VERSION>/actions-runner-osx-arm64-<VERSION>.tar.gz
tar xzf ./actions-runner-osx-arm64.tar.gz

./config.sh --url https://github.com/<org>/<repo> --token <TOKEN>
```

### 6.3 Operating Mode — Production Recommendation

Launching via `nohup ./run.sh &` is acceptable for proof-of-concept use, not for production operation. GitHub supports registering the runner as a system service (on macOS via `launchd`), with status verifiable through `./svc.sh status`. A production configuration should include:

- installation as a `launchd` service (`./svc.sh install`, `./svc.sh start`)
- automatic startup on system boot
- health-check monitoring
- centralized logging of runner status

**Known limitation:** `./svc.sh install` may require `sudo` privileges without a configured `NOPASSWD` sudoers entry, causing the process to block waiting for interactive password input. This must be resolved before production deployment, not worked around with an alternative launch mechanism.

### 6.4 Parallelism — Two Distinct Levels, and the Chosen Approach

Two distinct types of parallelism must be distinguished, which are easily conflated in design discussions:

**GitHub job-level parallelism:** A single runner agent executes one GitHub Actions job at a time. Concurrent execution of multiple jobs requires multiple available, idle runner instances — GitHub routes jobs to runners matching defined labels, and one runner cannot process two jobs simultaneously.

**Maestro/device-level parallelism:** Within a single job, the orchestration layer (Maestro or a wrapping script) can parallelize work across multiple connected devices. This is independent of GitHub job-level parallelism and requires no additional runner instances.

**Actually implemented approach:** Given a single available runner (section 6.0), true concurrent execution of both platforms in this sense is neither possible nor desirable in the first phase. Chosen solution: two **separate, sequential** jobs (`android-tests`, `ios-tests`), where the second uses `needs: android-tests` combined with `if: always()`. This is deliberately **not** a hard dependency (where failure of the first job would prevent the second from running) — the reason is preserving the diagnostic signal described in section 10: failure on both platforms indicates a different class of problem (likely in `commonMain`) than failure on a single platform only. Without `if: always()`, a failure in the Android job would make it impossible to determine whether iOS is affected by the same issue.

Ordering (Android first, iOS second) was chosen based on the existing, live-verified baseline on the Android side and the typically faster Gradle build/emulator-boot cycle compared to the Xcode build/simulator-boot cycle, providing faster initial feedback.

---

## 7. Self-Hosted Runner — Security Model

### 7.1 Rationale

A self-hosted runner is not guaranteed to be an isolated, ephemeral environment — GitHub explicitly warns of the risk of runner compromise via executed workflow code, and practically advises against self-hosted runner use for public repositories. The risk also exists for private repositories if a workflow processes untrusted code (e.g., from external pull requests).

The following coexist on a single machine in this architecture:
- repository source code
- GitHub authentication token
- mobile signing/build environment
- connected physical/virtual devices
- Anthropic API credentials

### 7.2 Required Security Measures

| Measure | Description |
|---|---|
| Private repository only | Self-hosted runner is not used on public repositories |
| Restricted runner group | Runner group is scoped to specific repositories/workflow files, not globally available |
| Minimal `GITHUB_TOKEN` permissions | Token is granted only the permissions required for the given workflow |
| No secrets for untrusted PRs | Workflows triggered from external/forked pull requests have no access to secrets |
| Dedicated CI machine | Runner operates on a machine dedicated to CI purposes, not a regular developer workstation with personal credentials |
| Secrets isolation | Access credentials (GitHub token, Anthropic API key) are isolated from personal developer credentials |
| Artifact retention policy | Defined retention period for test artifacts (screenshots, logs, video) |
| Workspace cleanup | Defined cleanup procedure for the workspace between runs |
| Network policy | Restriction of the runner's outbound network communication to necessary endpoints |

### 7.3 Confirmed Status (2026-09-28)

Single-repository scoping (section 7.2) is confirmed in place — verified directly from the runner's local `.runner` configuration file, not assumed. Trigger configuration is `workflow_dispatch` only, with no `pull_request`/`pull_request_target` triggers on self-hosted jobs, eliminating the untrusted-PR-code risk for the current setup. Remaining items from the table above (dedicated CI machine vs. shared development machine, secrets isolation, explicit `permissions:` block, network policy) are open items — see section 11.

---

## 8. Claude Code Authentication in a CI Context

A CI environment requires a non-interactive authentication mechanism, distinct from interactive login in a development environment. The specific mechanism (a static API key as a GitHub Secret, an OAuth token, or Workload Identity Federation) is chosen according to the selected deployment model — the official GitHub Action supports several of these mechanisms, including an option that avoids holding a static API key as a secret at all.

---

## 9. Choice of Integration Mechanism — CLI vs. GitHub Action

For this use case (input artifacts → inference → structured JSON output, with no need to modify repository code), invoking `claude -p ...` directly represents a simpler and more transparent alternative to the full `anthropics/claude-code-action`. The principle of least privilege suggests granting the agent only the capabilities required for the task — artifact analysis does not require code-modification capabilities that a full Action may provide. The choice between the two approaches is an architectural decision with trade-offs on both sides, not a case where one is unambiguously superior.

---

## 10. Execution Topology for a KMP Project

Given shared Kotlin Multiplatform code with platform-specific execution, the pipeline must distinguish:

```
                shared build (commonMain)
                        │
               ┌────────┴────────┐
               ▼                 ▼
            Android             iOS
          (emulator/           (simulator/
           device)              device)
               │                 │
            Maestro            Maestro
               │                 │
               └────────┬────────┘
                        ▼
              Failures classified by scope:
              - shared regression (both platforms)
              - Android-specific regression
              - iOS-specific regression
```

This distinction has direct diagnostic value — a failure on both platforms following a change in `commonMain` indicates a different class of problem than a failure exclusive to one platform. The failure classifier (sections 3–4) should treat this as an input signal.

---

## 11. Open Implementation Questions

| Area | Question |
|---|---|
| Distribution channel | Define the target system for the report (Slack webhook, e-mail, PR comment) |
| Trigger strategy | Conditions under which the pipeline runs (every push / PR only / scheduled) |
| `classify-failure.sh` schema | Exact implementation of the deterministic classifier — the list of recognized patterns and their maintenance |
| Sensitive-data redaction | Procedure for removing potentially sensitive information from screenshots/logs before submission to AI analysis |
| Input artifact budget | Maximum volume of data (screenshot count/size, log length) sent in a single AI call |
| Dedicated CI machine | Whether the runner should move to a machine dedicated solely to CI, separate from local development use (section 7.3) |
| Secrets isolation | Formal separation of CI credentials from personal developer credentials on the shared machine |

---

## 12. Implementation Sequence by Priority

**P0 — critical, before any deployment:**
1. ~~Correct the AI-analysis trigger condition~~ — not yet applicable; no AI step exists in the first deployment (deferred, see item 4 below)
2. Explicit step enforcing the CI quality gate independent of AI result — **achieved via the simpler default-failure-behavior pattern** (section 2.1), not the originally proposed `continue-on-error` + explicit `exit 1` pattern
3. Define the self-hosted runner's security model (section 7) — **partially confirmed**: repository scoping and trigger restriction verified in place (section 7.3); remaining items open (section 11)

**Status:** Mechanical Maestro execution (Android + iOS, sequential with `if: always()`) is implemented and pending its first live CI run, scoped to a single verified flow.

**P1 — before production deployment:**
4. Artifact collection implementation (JUnit report, test-output-dir, debug-output) — **implemented**, including a storage/quota audit confirming no implicit caching and bounded, gated artifact upload
5. Define the JSON output schema and failure taxonomy (section 4) — **not yet implemented**; no AI-analysis step exists yet
6. Define the flaky-test/retry strategy (section 5) — **not yet implemented**
7. Implement the deterministic pre-filter before any AI call (section 3) — **not yet implemented**

**P2 — extensions for a more mature system:**
8. KMP/iOS/Android execution topology and differentiated classification (section 10) — architecturally reflected in the two-job structure (section 6.4); classifier-level differentiation not yet implemented
9. Observability, artifact retention, historical failure metrics
10. AI guardrails: explicit UNKNOWN classification support, confidence scores, input budget limits (section 4.4)

---

*This document serves as an architectural planning artifact. Version 2.1 incorporates corrections from a technical review focused on production readiness, and reflects the actual first implementation of `.github/workflows/maestro-simulator.yml` as of 2026-09-28. GitHub Actions and Maestro CLI syntax verified against documentation available as of the document's creation date; Claude Code CLI details (particularly the `--json-schema` output schema) should be verified against the current version of the documentation at `docs.claude.com` before implementing the AI-analysis phase (sections 3–4).*
