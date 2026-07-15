# CLAUDE.md — Operator handoff for Claude Code sessions

This file is the standing brief for any Claude Code (or other agentic
assistant) session working on this repository. Read it before making
edits, opening shells, or composing commit messages.

## Project identity

TAK-XVoice (XV) is a **public, Apache-2.0-licensed ATAK plugin**. The
repository at `https://github.com/TX-RX/TAK-XVoice` is public and the
default branch is `main`. Anything that lands on `main` is, for
practical purposes, world-readable forever — assume git history is a
publication surface, not a private workspace.

## Sensitive-content rules

The following content categories MUST NEVER appear in committed files,
commit messages, PR descriptions, code comments, log output captured
into the repo, or any message text that gets pushed to a remote (this
includes chat replies the assistant emits while pairing with the
operator):

- **Credentials of any kind**
  - Personal access tokens (GitHub PATs, GitLab PATs, anything similar)
  - API keys, service account keys, OAuth client secrets, OAuth tokens
    (access or refresh)
  - Android/JKS signing keys, keystore passwords, key aliases tied to
    a production keystore
  - SSH private keys, GPG private keys
- **Bluetooth MAC addresses tied to a specific operator's hardware.**
  Use the redacted placeholder `XX:XX:XX:XX:XX:XX` in examples,
  docstrings, log lines, and tests. Realistic-looking fake MACs are
  also fine (e.g. `AA:BB:CC:DD:EE:FF`) — the point is never to ship a
  real operator's device address.
- **TAK server hostnames, FQDNs, URLs, or IP addresses.** This
  includes staging/dev servers as well as production. Use
  `tak.example` / `tak.example.com` / RFC 5737 (`192.0.2.x`,
  `198.51.100.x`, `203.0.113.x`) for examples. Multicast group
  literals derived from server cert fingerprints are also off-limits
  even when they "look" generic.
- **Customer, agency, or unit names.** No identifying organization
  strings, no real operator callsigns, no team identifiers from live
  deployments. Use `Alpha`/`Bravo`/`Charlie` or numbered placeholders
  in examples.
- **Operator GPS coordinates** of any precision. If a bug repro
  involves a real location, replace with a coordinate inside a known
  uninhabited region (e.g. `0.0, 0.0`) or a published city centroid
  before pasting.
- **Internal IP ranges.** No RFC 1918 prefixes that identify a real
  internal network. Generic illustrations of `10.0.0.0/8` etc. are
  fine; specific subnets observed in the field are not.

If the assistant is unsure whether a string falls into one of the
above categories, the default is **redact and ask** — never commit
and apologize later. Operator deployments depend on this; the public
history of an ATAK plugin is one of the first things an adversary
will read.

## Commit-message style

- Subject line: terse, conventional-commit-style prefix
  (`feat:` / `fix:` / `chore:` / `docs:` / `refactor:` / `test:`),
  imperative mood, under ~72 characters.
- Body: technical and descriptive. Explain *why* the change was made,
  what subsystem it touches, and any constraints a future reader
  needs to know. The single squashed `Initial public commit —
  TAK-XVoice` commit is the canonical tone reference: dense,
  factual, organized by subsystem, zero operator-identifying detail.
- Never name customers, agencies, units, real operators, or specific
  field deployments in commit messages, even at a high level.
- Do not paste log excerpts, device serials, or BT MACs into commit
  bodies. Summarize ("verified against an AINA V2 puck") instead of
  quoting raw evidence.

## README maintenance

The top-level `README.md` is the project's public-facing description
of what TAK-XVoice does, who it's for, and what hardware and features
are supported. Because this repo is public, the README is often the
first thing an operator, contributor, or downstream packager reads —
keep it current alongside the code.

- When a change lands that affects user-visible behavior — new
  supported hardware, a new UX affordance, a new transport, a new
  build task, a changed default, a retired feature — update the
  relevant README section in the same PR that ships the code.
  Do not treat README updates as a follow-up task; a merged feature
  that isn't in the README is functionally invisible to new
  operators.
- The **curated-hardware policy** is load-bearing: nothing is listed
  under "Hardware tested" as supported until it has been integrated
  and validated end-to-end against real event traffic. Do not add
  speculative, aspirational, or "should work" entries. When a device
  graduates to supported (or is retired), update both "What's
  different" and "Hardware tested" together.
- When shipped work materially reshapes the roadmap
  ("Now / Next / Later"), promote or retire bullets to reflect
  reality rather than intent. A roadmap that reads like a wish list
  loses signal.
- The **status-and-intended-use disclaimer**, the
  **hardware-philosophy** section, and the **reporting-issues**
  guidance are load-bearing legal / policy language. Do not reword
  them unilaterally — flag proposed changes to the operator first.
- Vendor names for downstream / interop targets that the operator has
  chosen to keep out of the README stay out. If unsure whether a
  vendor or product name belongs, treat it like sensitive content:
  redact and ask.
- README updates are subject to the same sensitive-content rules as
  commits and code (no real MACs, TAK URLs, callsigns, unit or
  organization names, or operator GPS coordinates), and they go
  through the same PR workflow described below.

## Branching + PR workflow

- `main` is protected by convention — **never push directly**.
- Feature work: `feat/<short-slug>`
- Bug fixes: `fix/<short-slug>`
- Chores / tooling / docs: `chore/<short-slug>`
- Open a pull request against `main` for every change. Even one-line
  fixes go through a PR so the public history has a reviewable
  audit trail.
- Squash-merge is the expected merge style; keep the PR description
  in the same technical-and-descriptive tone as commits.

## Build commands the user expects to be green before merge

Run all three from the repository root before considering a branch
ready for review:

```sh
./gradlew ktlintCheck
./gradlew assembleCivDebug
./gradlew testCivDebugUnitTest
```

Or just `./scripts/verify-gates.ps1`, which runs all three and is
wired to a pre-push hook.

`ktlintCheck` enforces the formatting baseline. `assembleCivDebug`
exercises the takdev SDK wiring + manifest, which is the most common
way a refactor breaks plugin loading. `testCivDebugUnitTest` is the
JUnit/MockK/Robolectric unit suite for the state machines listed in
the initial commit.

**These gates are local-only, and that is load-bearing.** GitHub CI
cannot compile this project — no redistributable ATAK SDK on public
runners — so `.github/workflows/ci.yml` runs ktlint and nothing more.
ktlint parses Kotlin without resolving symbols, so it cannot see an
unresolved reference. Never infer build health from a green PR check
or a merged PR; the only evidence that `main` builds is someone
running these locally and reading the output.

Two traps this has already sprung:

- **`assembleCivDebug` does not compile the test source set.** A
  broken test symbol is invisible to it. Run `testCivDebugUnitTest`
  explicitly — it is the gate that catches this class of break.
- **A red `main` is a real state, not a hypothetical.** PR #72
  (e12dc3b) landed a non-compiling test source set; CI was green and
  `main` stayed red through #80 and #81. When asked to "fix the
  build," reproduce all three gates first — the reported symptom may
  live in a different gate than the one named.

## Scripting recurring workflows

If a multi-step command sequence gets run more than a handful of
times in a session (build + install to phone, package for TPP, pull
+ scrub + summarize field logs, etc.), turn it into a script under
`scripts/` rather than re-running the raw commands each time. The
script is faster for the operator, less mistake-prone (correct
`.gitignore`-respecting archive command, correct baseline flag,
correct install path), and gives Claude Code a durable artifact
to reason about instead of reconstructing the sequence per session.

Script conventions:

- PowerShell 7+ (`pwsh`) is the primary shell — matches the operator's
  desktop. Target `pwsh`, **not** Windows PowerShell 5.1
  (`powershell.exe`); give each script a `#requires -Version 7.0` so a
  5.1 invocation fails fast. Bash-only scripts should be the exception,
  not the default.
- Per-clone / operator-specific values (ATAK SDK paths, real TAK
  server hostnames, callsign redaction lists) go in
  `scripts/config.json` (gitignored). A `scripts/config.example.json`
  template is committed so a fresh clone knows the schema. Shared
  config-loader logic lives in `scripts/lib/`.
- The tooling is generic across TAK plugins — nothing plugin-specific
  should be hardcoded in `scripts/*.ps1` files. If a script is only
  useful to this plugin, that's a smell; extract the specific bits
  to config.
- Any script that produces a public artifact (TPP zip, PR body,
  screenshot bundle) MUST run a sensitive-content scan before
  declaring success. `scripts/package-tpp.ps1` is the reference
  pattern.

See `scripts/README.md` for the current inventory and usage.

## ATAK SDK jar

`app/libs/main.jar` is gitignored and is **not** vendored in this
repository. The build resolves it from the local SDK distribution
per `build.gradle.kts` — the default search path is
`../ATAK-CIV-5.6.0.17-SDK/atak-gradle-takdev.jar` relative to the
repo root. If a Claude session reports a missing-jar error, the
correct response is to point the operator at the SDK installation
step, **not** to try to commit a copy of the jar into `app/libs/`.

## logs/ and capture directories

Anything under `logs/`, `.logs/`, `.tmp/`, or `diagnostics/` is
local-only field-capture material — logcat dumps, `dumpsys`
snapshots, batterystats pulls, post-mortem traces. **Never commit
anything from these directories**, even if it looks innocuous.
Field captures routinely contain Bluetooth MACs, device serials,
TAK server URLs, and call metadata that fall under the sensitive-
content rules above. The `.gitignore` already excludes them; do not
add `git add -f` overrides.

## Pre-commit hooks

This repo uses the `pre-commit` framework (configured in
`.pre-commit-config.yaml`) plus `gitleaks` (configured in
`.gitleaks.toml`) as belt-and-suspenders against accidental secret
commits. Install once per clone:

```sh
pipx install pre-commit
pre-commit install --hook-type pre-commit --hook-type pre-push
ls .git/hooks/pre-commit .git/hooks/pre-push   # verify — see below
```

After that, `git commit` runs `gitleaks` + `end-of-file-fixer` and
refuses commits that trip either hook, and `git push` runs the
build gate (`scripts/verify-gates.ps1`) so a red tree can't reach the
remote. If a hook trips, **investigate the underlying content** — do
not bypass with `--no-verify` unless the operator explicitly approves
it for a specific commit, and even then prefer fixing the content.

**Verify the install actually took.** `pre-commit install` refuses to
do anything while `core.hooksPath` is set, and the failure is one line
that is trivial to miss. This clone sat in exactly that state until
2026-07-14: `.pre-commit-config.yaml` and `.gitleaks.toml` were
committed and looked authoritative, `core.hooksPath` was pointing at
`.git/hooks` (git's own default — a no-op that still tripped the
check), and no hook had ever run. An uninstalled hook fails open and
protects nothing. If `git config --get core.hooksPath` returns
anything, find out what owns it before clearing it.
