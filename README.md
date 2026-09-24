# audio_waveforms (MRE fork)

Vendored [audio_waveforms](https://pub.dev/packages/audio_waveforms) 2.0.2
with iOS/macOS Swift Package Manager support. Upstream pub release has no
`Package.swift`.

## Use from the app

Until the GitHub remote is connected:

```yaml
audio_waveforms:
  path: ../audio_waveforms
```

Then:

```yaml
audio_waveforms:
  git:
    url: https://github.com/elshoram974/audio_waveforms.git
    ref: main
```

## Provenance

- Base: Simform `audio_waveforms` 2.0.2 (MIT) — see `LICENSE`
- SPM layout + Flutter `>=3.41` from
  [PR #511](https://github.com/SimformSolutionsPvtLtd/audio_waveforms/pull/511)
  (`6a0ad798610e72a60379e84202e85276d869640a`)

Drop this fork when upstream publishes SPM on pub.dev.
