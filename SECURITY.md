# Security policy

## Reporting a vulnerability

If you believe you have found a security issue in TAK-XVoice — for
example a way to leak an operator's Bluetooth MAC, TAK server URL,
signing key, or audio content off-device without their knowledge —
please **do not open a public issue.** Reports of that shape are best
handled privately so the fix can ship before the details are widely
known.

Use GitHub's **Private Vulnerability Reporting** instead:

1. Open the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Fill in the form; it goes directly to the maintainer.

That flow keeps the report private, associates it with a tracking
issue only the maintainer can see, and lets the maintainer publish a
security advisory (with CVE, if appropriate) when the fix ships.

## Scope

Reasonable security-report topics include:

- Any way to force TAK-XVoice into transmitting when the operator did
  not press PTT.
- Any way to make TAK-XVoice send authentication material to a server
  other than the one the operator enrolled with.
- Any way to make TAK-XVoice retain, log, or export operator PII
  (Bluetooth MAC addresses, TAK server URLs, GPS position, callsigns)
  in a way that survives a normal uninstall / clear-data cycle.
- Any way for a peer on the same Mumble channel to obtain audio the
  operator did not transmit, or to inject audio that appears to come
  from the operator.
- Any way to bypass the pre-commit / gitleaks discipline documented in
  `CLAUDE.md` and land operator-identifying content on `main`.

Non-security bugs (crashes, UI glitches, feature requests, hardware
compatibility) are welcome as normal public GitHub Issues.

## What to expect

Reports are triaged by a single maintainer. Response time is best-effort
— TAK-XVoice is a volunteer / hobbyist project, not a company product.
When a fix is ready, it lands via the normal PR workflow; the reporter
is credited in the release notes unless they ask otherwise.

Please **do not** include real operator-identifying content in a
security report — no real Bluetooth MAC addresses, TAK server URLs, or
callsigns. If a proof of concept requires one of those to demonstrate,
say so in the report and the maintainer will arrange a channel for the
sensitive bits.

## No warranty

Per `LICENSE` and `NOTICE`, TAK-XVoice is Apache-2.0, distributed
**as-is, without warranty**. See the "Status and intended use" section
of the README — this plugin is not intended as a sole means of
communication for any life-safety, public-safety, military, or other
mission-critical application. Reporting a vulnerability is welcome;
expecting a service-level commitment on top of it is not the
relationship the license establishes.
