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

- Default software-engineering and code implementation work to GPT-5.6 Luna Max. Use
  the lowest suitable reasoning intensity for routine implementation and ordinary
  verification; reserve higher intensity for decisions that materially affect the
  architecture, data, permissions, release or product direction.
- Use GPT-6.1 Sol only when the Luna pass cannot solve the problem, produces an
  inadequate result, or a high-impact decision requires a stronger review. Visual
  screenshot acceptance and ordinary visual checks use Sol at low intensity; use
  high intensity only for difficult visual diagnosis or consequential design choices.
- Subagents are opt-in by scenario. Use the Luna worker for bounded software tasks;
  use a Sol worker only for the visual or difficult cases above. Do not spawn agents
  merely to parallelize routine work, and stop an agent when its result no longer
  justifies its token cost. Product quality takes precedence over token savings.
- Test by evidence class: focused unit/contract tests for deterministic rules and
  state machines, one complete-module build/lint batch for code integration, and a
  bounded Xiaomi 14 pass for layout, touch, IME, system dialogs, navigation and media
  behavior. Do not rerun an unaffected gate or repeat engine/package hashes after a
  previously verified source boundary; retain only the checks needed before a
  candidate install or release.
