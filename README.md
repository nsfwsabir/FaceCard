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
| Blur gate | Laplacian variance, no OpenCV: frame < 40 → whip-pan drop (counts for nobody); face < 60 → dropped from counting *and* best shots |
| Embedding | **MobileFaceNet, 112×112 → 192-d float32** (`assets/mobilefacenet.tflite`, via MCarlomagno/FaceRecognitionAuth, BSD-3-Clause; MobileFaceNet architecture by deepinsight). Pixels to [-1, 1]; output is unit-norm → cosine = dot. Validated with LiteRT: `input[1,112,112,3] fp32 → embeddings[1,192] fp32`, ‖emb‖ ≈ 1.0 |
| Clustering | **Smile DBSCAN** (`com.github.haifengl:smile-core:2.6.0`, LGPL-3.0, pure JVM, offline), sim τ 0.70 · minPts 2, plus constrained merge (0.60, disjoint screen time only) and never-alone dissolve — see below |
| Appearances | Per-person segments: ≤1500 ms gap bridged (mid-appearance detection holes), ≥3 frames (0.6 s) to count — flicker/whip-pans don't inflate counts; shared frames count once per person |
| Best shot | Solo-frame candidates preferred (shared frames drag neighbours into the tile); 0.30 frontality + 0.30 sharpness + 0.20 eyes-open + 0.15 smile + 0.05 size, with vetoes (closed eyes ×0.2, clipped ×0.3, profile ×0.5, tiny face ×0.5). Full-res re-extract, generous crop (2.4× face box, 1.8× fallback for shared frames — never a tight face crop) |
| Collage | 1080×1920 story canvas (hero/split/editorial/mosaic by headcount). The preview displays the exact export bitmap |

### Similarity threshold chosen

**Cosine sim τ = 0.70, minPts 2, merge 0.60** (`Clusterer`). Note what
minPts means here: it is the minimum number of *face detections* (across
frames) that seed one person's cluster — it is NOT a cap on the number of
people. DBSCAN discovers as many people as the footage holds (2, 5, 10…);
each person just needs ≥3 mutually-close faces (Smile counts neighbours
excluding self, so minPts=2 ⟺ triplets). A 10-person video yields 10
clusters with these exact settings — nothing is tuned to "5". Sample 1's
5 × 4 = 20 is used only as a *verification target*, never as an input:
there is no k, no sample name, and no count anywhere in the pipeline.
Embeddings are L2-unit-norm, so cosine distance equals Euclidean distance up to scale
(d² = 2(1−cos)) — Smile's KD-tree `DBSCAN.fit` runs directly on the vectors
with radius √(2·0.30) ≈ 0.77, no custom distance code. The fragment bar sits
deliberately tight: device logcat proved same/cross-identity similarities
overlap substantially on this footage (cross pairs reach ~0.55, drift spans
~0.5–0.9), and a 0.55 bar chained all 145 faces into one person while 0.62
still fused pairs. Splits are recoverable, fusions are not — so DBSCAN
over-splits into high-precision fragments, and a constrained agglomerative
pass reunites pairs with centroid sim ≥ 0.60 that NEVER share screen time
(the brief's shared frames hold distinct people: cannot-link). A small
fragment with zero solo screen time dissolves sample-by-sample into the
nearest established cluster (≥ 0.55) or is dropped. Separation comes from
the tight eps, not from minPts: minPts only sets the recall floor (triplets
seed a person; anything smaller can't form a countable appearance anyway).
The app logs `embed_qc` (pairwise similarity histogram) plus clustering
histogram) plus clustering decisions under `FaceCard` — tune at the knee of
that distribution and re-verify against Sample 1 (5 / 20).

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
