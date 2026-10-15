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
