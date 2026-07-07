<!--
Fill this template in fully before flipping the PR out of draft.
Delete this comment block; leave section headers even if a section is
"n/a" so reviewers can tell the section was considered.

Sensitive-content reminder: per CLAUDE.md, no real Bluetooth MAC
addresses, TAK server hostnames / URLs, customer / agency / unit /
operator callsign names, or operator GPS coordinates go into the PR
title, body, or any file this PR touches. Use `XX:XX:XX:XX:XX:XX` /
`AA:BB:CC:DD:EE:FF`, `tak.example`, `Alpha` / `Bravo` in examples.
-->

## Summary

<!-- One paragraph: what changed, why. Focus on *why*. Keep it
     technical and factual. -->

## What changed

<!-- Bullet the concrete changes, ideally grouped by subsystem.
     Reference file paths so reviewers can jump straight to them. -->

## Root cause / motivation

<!-- For a fix: the underlying defect, ideally with a link into the
     source code where it lives. For a feature: the operator need
     that drove it. -->

## Test plan

<!-- Check the boxes you actually verified. Unchecked = not verified,
     which is fine — reviewers just need to know. -->

- [ ] `./gradlew ktlintCheck` — clean.
- [ ] `./gradlew assembleCivDebug` — clean.
- [ ] `./gradlew testCivDebugUnitTest` — passes; note any newly-added tests.
- [ ] On-device smoke on the target hardware. Describe what "smoke" meant.
- [ ] Regression watch: what nearby behaviors did you keep an eye on that this PR could have broken?

## Related but not in scope

<!-- Optional. Note any follow-ups this PR uncovered but doesn't
     include, so they're captured for a later branch. -->

## Reviewer notes

<!-- Optional. Anything reviewers should specifically look at (a
     tricky branch, a threading assumption, a UX judgment call). -->
