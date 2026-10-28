# Fileway TV engineering

- Confirmed product: API28+, landscape/remote-first, direct networking only; embedded Tailscale is excluded from this app.
- Reuse clients/shared/android and clients/shared/core; retain the phone's package, signing, schema and network behavior.
- One player, stable SurfaceView, original-source timeline and subtitle identity. SDR compatibility must preserve source ASS/fonts and bitmap subtitles.
- Native HDR signal, decoder output, display capability and physical HDR acceptance are distinct evidence.
- Default dark and optional light Fileway themes, 12dp controls, coherent static icon groups, no live blur or continuous decorative animation.
- No source-scraping/media-library metadata system in this version.
- Keep private plans, receipts, APKs and device logs outside Git. Never commit credentials or private addresses.
- Main agent only until the user explicitly authorizes delegation. Test complete modules in batches.
