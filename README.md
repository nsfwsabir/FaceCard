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
| Clustering | **Smile DBSCAN** (`com.github.haifengl:smile-core:2.6.0`, LGPL-3.0, pure JVM, offline) over cosine distance, eps 0.45 · minPts 3 — density chaining heals gradual drift, native noise labels isolate false hits |
| Appearances | Per-person segments: ≤1500 ms gap bridged (mid-appearance detection holes), ≥3 frames (0.6 s) to count — flicker/whip-pans don't inflate counts; shared frames count once per person |
| Best shot | Solo-frame candidates preferred (shared frames drag neighbours into the tile); 0.30 frontality + 0.30 sharpness + 0.20 eyes-open + 0.15 smile + 0.05 size, with vetoes (closed eyes ×0.2, clipped ×0.3, profile ×0.5, tiny face ×0.5). Full-res re-extract, generous crop (2.4× face box, 1.8× fallback for shared frames — never a tight face crop) |
| Collage | 1080×1920 story canvas (hero/split/editorial/mosaic by headcount). The preview displays the exact export bitmap |

### Similarity threshold chosen

**Cosine eps 0.45, minPts 3** (`Clusterer.COSINE_THRESHOLD = 0.55`,
`MIN_FACES_PER_CLUSTER = 3`). Embeddings are L2-unit-norm, so cosine
distance equals Euclidean distance up to scale (d² = 2(1−cos)) — Smile's
KD-tree `DBSCAN.fit` runs directly on the vectors with radius
√(2·0.45) ≈ 0.95, no custom distance code. Density chaining reunites
gradual drift (medium shot → close-up across frames) when intermediate
frames exist; lone noise in a big cast is dropped, while tiny casts keep
everything (recall over precision). Tune at the knee of a k-NN distance
plot (standard DBSCAN procedure) and re-verify against Sample 1 (5 / 20):
lower eps merges lookalikes, higher eps splits one person in two.
Merge/prune decisions log to logcat under `FaceCard` for tuning.

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
