# Contributing to TAK-XVoice

Thanks for the interest. TAK-XVoice is Apache-2.0 and welcomes
contributions from operators, developers, and hardware enthusiasts.

## Before you start

Read `CLAUDE.md` at the repository root. It is the standing brief for
the project — it covers:

- **Sensitive-content rules.** No real Bluetooth MAC addresses, TAK
  server hostnames / URLs, customer or agency or unit names, real
  operator callsigns, operator GPS coordinates, or credentials in any
  committed file, commit message, PR body, or code comment. The repo
  is public and its history is permanent, so redact-and-ask is the
  default posture for anything ambiguous. Placeholders like
  `XX:XX:XX:XX:XX:XX` / `AA:BB:CC:DD:EE:FF`, `tak.example`, and
  `Alpha`/`Bravo` are the standard substitutions.
- **Commit and PR style.** Conventional-commit-style subject prefix
  (`feat:` / `fix:` / `chore:` / `docs:` / `refactor:` / `test:`),
  imperative mood, technical-and-descriptive body focused on *why*,
  never any operator-identifying detail.
- **Branching + PR workflow.** Never push directly to `main`.
  `feat/<short-slug>` for features, `fix/<short-slug>` for bug fixes,
  `chore/<short-slug>` for tooling and docs. Every change goes through
  a PR — even one-line fixes — so the public history has a reviewable
  audit trail.
- **README maintenance.** User-visible behavior changes (new supported
  hardware, new UX, new transport, new build task, changed default,
  retired feature) update the relevant README section in the same PR
  that ships the code.

## Build and test

Before opening a PR, run the three commands the repo expects to be
green:

```sh
./gradlew ktlintCheck
./gradlew assembleCivDebug
./gradlew testCivDebugUnitTest
```

`ktlintCheck` enforces the formatting baseline (the same style CI
runs). `assembleCivDebug` exercises the takdev SDK wiring plus the
manifest — the fastest way to catch a refactor that broke plugin
loading. `testCivDebugUnitTest` runs the JUnit / MockK / Robolectric
unit suite for the state machines the plugin ships.

### ATAK SDK

`app/libs/main.jar` is gitignored and not vendored. `build.gradle.kts`
resolves it from the local SDK distribution — the default search path
is `../ATAK-CIV-5.6.0.21-SDK/atak-gradle-takdev.jar` relative to the
repo root. If your build reports a missing-jar error, point your
setup at a locally-installed ATAK CIV SDK; do not attempt to commit a
copy of the jar into `app/libs/`.

### Pre-commit hooks

The repo uses the [`pre-commit`](https://pre-commit.com/) framework
plus [`gitleaks`](https://github.com/gitleaks/gitleaks) as a secondary
guard against accidental credential commits. Install once per clone:

```sh
pipx install pre-commit
pre-commit install
```

After that, `git commit` runs the hooks locally and refuses commits
that trip either one. If a hook trips, **investigate the underlying
content** — do not bypass with `--no-verify` unless the maintainer
explicitly approves it for a specific commit.

## Opening a pull request

1. Fork the repository and cut your branch from `main`.
2. Make small, self-contained commits with descriptive bodies.
3. Push your branch and open a PR against `main`. The PR template
   walks you through the summary + test plan we expect. Fill in the
   test plan honestly — an unchecked box that says "not exercised on
   BT SCO" is more useful than a checked box that isn't true.
4. Squash-merge is the expected merge style once a PR is approved,
   so keep the PR description in the same tone as the target commit
   message.

## Reporting bugs and feature requests

Open a normal GitHub Issue for non-security bugs, UX regressions,
hardware compatibility asks, or feature ideas. For anything that
looks like a security vulnerability, use the **Private Vulnerability
Reporting** flow in the Security tab — see `SECURITY.md`.

## Reviewing others' PRs

Reviews are welcome from anyone with the context to give one. The
review bar is: does the code do what the PR body says, is it
consistent with the rest of the codebase, does it respect the
sensitive-content rules in `CLAUDE.md`, and does it have enough test
coverage that a future regression would be caught before merge.
