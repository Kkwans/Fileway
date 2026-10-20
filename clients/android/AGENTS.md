# Client engineering rules

- Android first; Windows client is a later goal. Reuse `clients/shared/core` and the
  shared server API. Do not change `server/` unless the active task includes it.
- Work on `main`; one independently verified feature per commit, push immediately.
- Native playback and embedded tsnet are mandatory. Never claim device/HDR/network acceptance from mocks or builds.
- No passwords, JWTs, node state, signing keys, personal server addresses or copyrighted media in this public repository.
- Keep native versions pinned; preserve original HTTP Range and opaque wire paths.
- Run the affected tests, lint/build and `git diff --check` before committing.
- Real-device gates may be blocked by resources; record exact evidence and do not present a checkpoint as completion.

## Model routing and verification economy

- The main Agent model is controlled by the user. The main Agent must remain on the
  user's selected GPT-5.6 Luna Max and must never switch or route the main model on
  its own. This rule describes subagent routing only.
- Subagents are opt-in by scenario. Use the Luna worker for bounded routine software
  engineering. Use a GPT-6.1 Sol subagent for visual work, difficult problems, or a
  Luna attempt that cannot solve the issue or produces inadequate quality or speed.
  Visual acceptance and ordinary visual checks use Sol at low reasoning intensity;
  use high intensity only for difficult visual diagnosis or consequential decisions.
- Do not spawn agents merely to parallelize routine work, and stop an agent when its
  result no longer justifies its token cost. Product quality takes precedence over
  token savings.
- Test by evidence class: focused unit/contract tests for deterministic rules and
  state machines, one complete-module build/lint batch for code integration, and a
  bounded Xiaomi 14 pass for layout, touch, IME, system dialogs, navigation and media
  behavior. Do not rerun an unaffected gate or repeat engine/package hashes after a
  previously verified source boundary; retain only the checks needed before a
  candidate install or release.

## 0.8 delivery override (user, 2026-10-16)

- Prioritize remaining implementation and crash fixes. The user performs visual,
  media and interaction acceptance; do not run full device journeys or add routine
  unit tests to mirror UI changes. Keep necessary compilation/package checks and
  focused diagnosis of actual blocking failures. Do not repeat baseline hashes.
- The user explicitly authorized the 0.8 release. Preserve the original package,
  signing identity and user data, and bind the release to an exact pushed source.
  Report known issues honestly; perfect bug-free acceptance is not a release gate.

## Android 1.0 product contract (user, 2026-10-19)

- Implement the confirmed 0.8.4-to-1.0 plan. Existing capabilities only: exclude the unrelated EPUB preparation and new background music, shuffle/repeat or automatic next-item behavior.
- Ordinary pages share blue #1767E8, background #F5F7FA, white content surfaces and existing bottom-navigation selection. The player uses stable graphite #14181E / #1B1F27 with restrained artwork accents; never recolor the whole player from hue. Image canvas remains black.
- Cards, ordinary buttons, fields, menus and floating dialogs explicitly consume 12dp shapes. Attached bottom panels use 12dp visible top corners and connect to system navigation insets without a gap. Semantic circles and rectangular content surfaces are exceptions. Keep >=48dp touch targets.
- Reuse shared page chrome, typography, spacing, search, menu, filter, feedback and motion components. Standard rhythm is 4/8/12/16/24dp. Main tabs change by click only; small tabs support continuous pager gestures and animated indicators.
- Menus measure their longest actual content plus real icon/check slots, constrained by window space. Start-aligned selection uses a fixed check column; centered selection uses row/block styling without an offset check. Never choose arbitrary universal menu widths.
- Thumbnail loading uses one shared centered ring with delayed appearance and coordinated placeholder; preserve the common cache, leases, identity and natural-aspect geometry.
- Cover: two columns, centered artwork/name, one name line. Compact: artwork/name only, centered; small four columns/two name lines, medium three/two, large two/one. Unbounded: natural aspect, two columns, max two name lines and compact metadata. Keep full name/path access and stable anchors.
- Remove application-added connection server/user names from ordinary pages, drawer, cards, details and confirmations. Keep them in connection and My login-information areas, and keep managed usernames in user administration or explicit admin account selection. Trash owner is not a display exception. Never change internal owner/scope/permission/wire checks or rewrite user filenames, paths, document authors or content.
- Reuse server GET/PUT /api/settings tokenExpirationTime and POST /api/renew for sliding expiry, 10 minutes to 30 days. Settings belong to each service instance; preserve existing values. Deduplicate renewal by bound account/generation/token; retain acknowledged writes on renewal/persistence failure, never replay a mutation.
- Player panels own one title, share media tokens and 12dp controls. Separate decoder from source information; queue count belongs in its header. Keep SurfaceView stable for HUD/panels and do not emulate video backdrop blur with frame-copy loops.
- Execute verified logical slices on main with exact staging and immediate fast-forward push. Preserve original package/signing/data and the four legacy tracked changes/thirteen EPUB files. Version candidates use explicit pushed source. User authorized preview milestones through final 1.0 release, subject to the confirmed quality gates.
- Batch necessary compilation/lint, high-value contracts and bounded real-device checks; do not repeat unaffected tests or baseline hashes. New UI contracts need real visual evidence; final 1.0 key journeys remain a gate.

## Verification override (user, 2026-10-20)

- Run only necessary checks. The user performs manual visual/interaction acceptance; do not launch routine screenshot journeys or repeat broad lint/JVM/contract suites for a small style or layout condition change.
- Keep required compilation/package identity and focused diagnosis of actual failures. Reuse unchanged valid evidence, record skipped/interrupted checks honestly, and spend development time on product implementation. Do not recheck legacy baseline hashes.
