# Samsung Active Key (ruggedized tablets & phones)

Samsung ruggedized hardware ships with a programmable **Active Key** — a
physical side button — that TAK-XVoice can use as a Push-To-Talk source
alongside (or instead of) a Bluetooth speakermic.

## Supported chassis

The Active Key PTT toggle appears **only** on hardware that actually has
the key. XV recognizes:

| Family            | `Build.MODEL` prefix      |
| ----------------- | ------------------------- |
| Galaxy Tab Active5 | `SM-X300` / `SM-X306` / `SM-X308` |
| Galaxy Tab Active4 Pro | `SM-T630` / `SM-T636` |
| Galaxy Tab Active3 | `SM-T570` / `SM-T575` / `SM-T577` |
| Galaxy XCover7    | `SM-G556`                 |
| Galaxy XCover6 Pro | `SM-G736`                |

On any other device the toggle is hidden and this feature is a no-op.

## The two keys

- **Active Key (side)** — keyCode `1015`. This is the PTT source.
- **Top / Emergency key** — keyCode `1079`. **Not** wired to PTT or the
  emergency alert today; it keeps its Samsung-configured behavior.

## Foreground PTT — works out of the box

While ATAK holds the top window, the Active Key arrives as an ordinary
`KeyEvent` (down on press, up on release) and XV keys PTT directly. No
setup beyond enabling the **Active Key** row in XV settings.

> Note: Samsung's *Advanced features → Active Key* screen lets the OS map
> the key to "launch an app." Leave it unmapped (or mapped to XV) if you
> want XV to see the raw key press. A "launch app" mapping emits a single
> intent on press with no release, which is unusable as a PTT trigger.

## Background PTT — optional accessibility service

The foreground `KeyEvent` only reaches the top activity, so PTT stops the
moment ATAK is backgrounded. On the validated firmware the Samsung
`HARD_KEY_REPORT` broadcast is **not** emitted, so the only way to catch
the key while backgrounded is an accessibility service.

XV ships a **tightly-scoped** accessibility service that:

- acts **only** on the Active Key (keyCode 1015); every other key is
  passed through untouched,
- reads **no** screen content, typed text, or window data
  (`canRetrieveWindowContent="false"`), and
- exists solely to receive the key event
  (`flagRequestFilterKeyEvents`).

### Enable it

1. In XV settings, tap **Use Active Key while ATAK is in the
   background**. XV deep-links you to the system Accessibility page.
2. Enable **TAK-XVoice** under *Installed services* and accept the
   system dialog.

Android does not allow an app to enable its own accessibility service, so
this is a one-time manual step. The XV settings row is read-only and
reflects the live OS permission state — it is not a persisted preference.

> The OS-generated Accessibility consent dialog ("can observe your
> actions") cannot be customized by XV. The service's description string
> spells out exactly what it does and does not access.

## On-device validation

- **Galaxy Tab Active5 (SM-X308U)** — foreground Active Key PTT and the
  background accessibility path are both field-verified. The descriptor
  requires `android:canRequestFilterKeyEvents="true"`; without it the
  flag is silently ignored and `onKeyEvent` is never dispatched, even
  though the service binds.

Other listed chassis are gated on by model prefix but have not yet been
validated end-to-end — see the curated-hardware policy in the top-level
README.

## Troubleshooting

- **Toggle missing:** the device model isn't in the supported list above,
  or `Build.MANUFACTURER` isn't `samsung`.
- **Works foreground, not backgrounded:** the accessibility service isn't
  enabled (or was disabled by the OS). Re-check the Accessibility page.
- **Nothing happens at all:** confirm Samsung *Advanced features → Active
  Key* isn't set to "launch app," which swallows the key press.
