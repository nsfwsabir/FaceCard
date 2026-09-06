# FaceCard — *Every face, once. Your video's best story.*

On-device Android app that turns a portrait group video into a shareable
story collage: it detects faces, groups repeat appearances of the same
person, picks the most flattering shot per person, and composes one
1080×1920 collage per video — then saves/shares it.

Kotlin + Jetpack Compose + ML Kit, minSdk 26, 100% on-device, no backend.

## Demo flow

Home → **Choose video** (any portrait mp4, incl. the 3 test samples) →
honest staged progress (Extract → Detect → Embed → Cluster → Best shots →
Collage) → **N people · M appearances** with per-person timelines →
**View collage** → **Save to gallery** / **Share**.

## Build & setup

Requirements: JDK 17, Android SDK with `compileSdk 34`
(`ANDROID_HOME`/`ANDROID_SDK_ROOT` set — no extra SDK downloads needed).

```bash
./gradlew :app:testDebugUnitTest   # 37 JVM unit tests
./gradlew :app:assembleDebug       # APK → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No API keys, no network, no model downloads — everything ships in the APK.

## How it works

| Stage | Implementation |
|-------|----------------|
| Frame sampling | `MediaMetadataRetriever` @ 5 fps (cap 200), rotation applied, 640px detection copies; streaming `Flow`, one bitmap alive at a time |
| Face detection | **ML Kit face detection, bundled model** (`com.google.mlkit:face-detection:16.1.5`) — accurate mode, all classifications (eyes + smile), tracking IDs, min face size 0.12. Bundled (not the Play-Services thin client) so it works offline on first launch |
| Blur gate | Laplacian variance, no OpenCV: frame < 40 → whip-pan drop (counts for nobody); face < 60 → dropped from counting *and* best shots. Only landmark-verified full faces (eyes, nose, mouth inside frame) are embedded — partial faces match arbitrarily |
| Embedding | **MobileFaceNet, 112×112 → 192-d float32** (`assets/mobilefacenet.tflite`, via MCarlomagno/FaceRecognitionAuth, BSD-3-Clause; MobileFaceNet architecture by deepinsight). Pixels to [-1, 1]; output is unit-norm → cosine = dot. Validated with LiteRT: `input[1,112,112,3] fp32 → embeddings[1,192] fp32`, ‖emb‖ ≈ 1.0 |
| Clustering | Online competitive assignment (time-ordered, clear best match wins), join τ 0.50 + contest margin 0.05 · merge 0.55 disjoint-only + same screen region · never-alone dissolve — see below |
| Appearances | Per-person segments: ≤1500 ms gap bridged (mid-appearance detection holes), ≥3 frames (0.6 s) to count — flicker/whip-pans don't inflate counts; shared frames count once per person |
| Best shot | Solo-frame candidates preferred (shared frames drag neighbours into the tile); 0.30 frontality + 0.30 sharpness + 0.20 eyes-open + 0.15 smile + 0.05 size, with vetoes (closed eyes ×0.2, clipped ×0.3, profile ×0.5, tiny face ×0.5). Full-res re-extract, generous crop (2.4× face box, 1.8× fallback for shared frames clamped to its frame half — never a tight face crop) |
| Collage | 1080×1920 story canvas (hero/split/editorial/mosaic by headcount). The preview displays the exact export bitmap |

### Similarity threshold chosen

### Similarity threshold chosen

**Competitive assignment, join τ = 0.50, merge 0.55** (`Clusterer`). Each
face joins its BEST-matching identity at/above the floor, but only when
the match is uncontested (clear of the runner-up by 0.05): contested
faces seed fragments for the merge/dissolve passes to adjudicate instead
of silently polluting a centroid (near-miss absorption starves true runs
and bridges phantom segments on interleaved edits); a constrained
agglomerative pass reunites fragment pairs with centroid sim ≥ 0.55 that
NEVER share screen time (the brief's shared frames hold distinct people:
cannot-link). Pairs of positionally steady clusters living in clearly
different screen regions are refused the merge even with disjoint time
(split-screen guests are not drift). A small fragment with zero solo screen
time dissolves into
the nearest established cluster (≥ 0.55) or is dropped; lone singletons in
big casts are pruned. There is no k and no person cap: a 10-person video
yields 10 clusters with these exact settings. Sample 1's 5 × 4 = 20 is used
only as a *verification target*, never as an input: no sample name and no
count appear anywhere in the pipeline.
Why competition instead of threshold linkage: on this footage same-person
and cross-person similarities overlap substantially (cross pairs reach
~0.55), so linking on ANY sufficient match fuses distinct people — a whole
145-face run chained into one person that way. Competition keeps errors
local (a misassigned face) instead of fusing identities. Samples themselves
are gated to full faces only: both eyes, nose base and mouth must be
detected inside the frame (ML Kit landmarks), because partial faces embed
arbitrarily — measured matching the wrong person at 0.54 while scoring 0.17
against their own. Benchmarking the bundled model on real footage frames
showed clean same-person pairs at ≥0.565 and clean cross-person pairs at
≤0.293. The app logs `embed_qc` (pairwise similarity histogram) plus
clustering decisions under `FaceCard` for tuning.

### Counting contract

One continuous clearly-visible segment = one appearance. Blurred whip-pan
passes count for nobody. Two clearly-visible people in one segment = one
appearance each. Reference: Sample 1 → 5 people × 4 appearances = 20.

## Project structure

```
app/src/main/java/app/facecard/
  ui/{home,processing,results,collage,about,nav,theme}/  Compose + ViewModels
  domain/{model,pipeline}/   pure-Kotlin: clusterer, segmenter, scorer, layout
  data/{video,face,export}/  retriever, ML Kit, TFLite, blur, renderer, save/share
  data/ResultStore.kt        app-scoped latest result (no bitmaps in nav args)
app/src/main/assets/mobilefacenet.tflite
app/src/test/                37 unit tests (no device needed)
```

## Permissions

- Video picking uses the system file picker (no storage permission needed).
- Gallery save needs nothing on API 29+ (scoped storage); `WRITE_EXTERNAL_STORAGE` (maxSdk 28) is requested at save time on older devices.
- Sharing uses `FileProvider` + cache — no permission needed.

## Known limits

- Crowded frames cap at the 6 largest faces (perf rule).
- Heavy occlusion / extreme profile is best-effort; embeddings degrade there.
- 6+ person collages fit one 9:16 page (tiles shrink).
- No identity naming across videos (clustering only, per brief).

## Manual QA matrix (device)

Sample 1/2/3 end-to-end · API 26 emulator + API 34+ device · rotation +
cancel mid-run · dark mode · save opens from gallery · share chooser works ·
no-faces video shows the empty state (never an empty collage).
