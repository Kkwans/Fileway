# Android external subtitle paths

The NAS picker and local SAF picker feed one `NativePlayer.addSubtitle` entry.
The authenticated lease or granted content URI supplies bytes; a media generation
and request number reject late reads after replacement, cancellation or subtitle off.
Changing external text/bitmap selection, delay or appearance does not reopen video.

| Format | Native rendering path |
| --- | --- |
| ASS / SSA | libass, preserving authored styles and embedded-media fonts |
| SRT / WebVTT / TTML / DFXP | Media3 parser, collected whole-file cues |
| SUP / PGS | Local file-backed display-set index plus on-demand Media3 PGS decoder |
| SMI / SAMI | SAMI timing/language parser, Android styled text, collected Media3 cues |

SAMI is parsed as millisecond sync points. Language classes become separate
selectable tracks with names/language codes from CSS metadata. Common paragraphs
apply to each language; source/speaker paragraphs persist until their next update.
Nonbreaking-space paragraphs clear text. The last event remains valid until a
later event or media end. Seek and positive/negative delay reuse the same complete
cue timeline, including while paused.

SAMI byte decoding accepts UTF-8/UTF-16 BOMs and an explicit charset declaration;
otherwise it requires valid UTF-8. It does not guess a legacy encoding from the
filename or replace invalid bytes silently. Paragraph HTML uses Android's text
conversion for inline formatting, not a WebView; scripts and remote images do not
execute or fetch resources. This does not implement every SAMI CSS/layout feature.

External text reads are bounded at 16 MiB. SUP input is copied to a private local
working file with a 500 MiB limit, then indexed without keeping the whole compressed
file in memory. Only the current display set is decoded. The index preserves
caption and clear events for whole-file seek; it does not use the legacy 20,000
collected-event limit. Index metadata and retained legacy/text cue data share a
32 MiB budget per media generation. Each decoded PGS display set also has a bounded
32 MiB bitmap budget, including transient crop decoding. Unsupported or over-budget
files fail explicitly rather than attaching a truncated timeline.

Subtitle off clears the visible caption while keeping an accepted SUP's local
index for reselection; it does not fetch the file again or reopen video. Import
cancellation, replacement before acceptance and media close delete owned SUP work
files. Cancellation after a seek or delay rejects an obsolete display set, while
normal clock ticks within the same display set reuse the decoded result.

Collected text/legacy cues are bounded at 20,000 events and share the 32 MiB
retained-data budget above. All SAMI languages are validated and attached
as a single bundle, so a quota failure cannot select a partially loaded language set.
There are at most 32 SAMI language classes. Backward/invalid times are rejected.
Parser expressions must run on both JVM and Android ICU; their regex rules differ.

The maintained timing reference is Microsoft's
[SAMI 1.0 specification](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/dnacc/understanding-sami-1.0).
Tests use owned files. Parser tests are separate from installed frame/cue tests,
audible switching continuity, NAS/SAF permissions and actual HDR acceptance.
