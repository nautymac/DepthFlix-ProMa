# DepthFlix (ProMa) — 기능 안내

[한국어](#한국어) · [English](#english)

## 한국어

ProMa P10에서 **3D 영상·사진을 제대로 보기 위한** 뷰어. 기기에 원래 들어 있던 3DPlayer / Sight3D / 3DFV를 분석해서 만든 앱이라, 기기가 3D를 만드는 방식을 그대로 활용하면서 훨씬 많은 걸 할 수 있다.

### 무엇을 볼 수 있나

- 기기에 저장된 영상
- 네트워크 스트리밍 — `http(s)`, HLS(`.m3u8`), DASH(`.mpd`), RTSP

### 3D 배치 5종을 지원

2D / 좌우 반반(SBS-half) / 좌우 전체(SBS-full) / 상하 반반(TB-half) / 상하 전체(TB-full) — 원본 앱이 못 하던 조합까지 다 된다.

### 2D 영상도 강제로 3D 변환

원본 3DPlayer의 "2D/3D 버튼"과 같은 방식으로, 평범한 2D 영상도 3D로 바꿔 볼 수 있다.

### 초점·깊이를 실시간으로 조절

- 깊이와 수렴점(초점)을 재생하면서 바로바로 조절할 수 있다 — 원본 앱에는 없던 기능.
- 좌우가 바뀌어 보이면 반전 토글로 즉시 고친다.
- 한 번 맞춘 값은 파일마다 저장돼서 다음에 자동으로 적용된다.

### 이어보기·빠른 이동

- 보던 영상을 다시 열면 멈췄던 지점부터 이어서 재생.
- `◀◀`/`▶▶`을 짧게 누르면 30초, 길게 누르면 5분씩 이동.

### 화면 비율

자동 / 16:9 / 2.40:1 / 1.85:1 / 4:3 / 꽉 채우기 중 고른다.

### 재생 엔진을 고를 수 있다

기본은 ExoPlayer, 필요하면 libVLC로 바꿀 수 있다(파일마다 저장). AC3·E-AC3·DTS·TrueHD 오디오도 FFmpeg 확장을 직접 넣어 소리 나게 재생한다.

### 3D 컨트롤 센터 — 다른 앱도 3D로

유튜브 같은 다른 앱을 ProMa의 3D 화이트리스트에 등록·해제할 수 있다. 등록하면 그 앱을 켤 때 패널이 자동으로 3D로 전환된다.

---

## English

A viewer built to watch **3D video and photos properly** on the ProMa P10. Built by reverse-engineering the device's original 3DPlayer / Sight3D / 3DFV apps, it uses the same on-device 3D mechanism while doing considerably more.

### What it plays

- Video stored on the device
- Network streaming — `http(s)`, HLS (`.m3u8`), DASH (`.mpd`), RTSP

### Five 3D layouts supported

2D / side-by-side half / side-by-side full / over-under half / over-under full — including combinations the original apps couldn't handle.

### Forced 2D-to-3D conversion

Same approach as the original 3DPlayer's "2D/3D button" — ordinary 2D video can be turned into 3D too.

### Adjust focus and depth live

- Depth and convergence (focus point) can be adjusted while playing — not possible in the original apps.
- A flip toggle instantly fixes reversed left/right.
- Once set, values are remembered per file and applied automatically next time.

### Resume and quick seek

- Reopening a video picks up where you left off.
- `◀◀`/`▶▶` short-press moves 30 seconds, long-press moves 5 minutes.

### Aspect ratio

Choose auto, 16:9, 2.40:1, 1.85:1, 4:3, or fill screen.

### Choice of playback engine

ExoPlayer by default, switchable to libVLC (remembered per file). AC3, E-AC3, DTS and TrueHD audio all play, via a hand-built FFmpeg audio extension.

### 3D control center — bring other apps into 3D

Other apps, like YouTube, can be registered or removed from the ProMa's 3D whitelist. Once registered, the panel switches to 3D automatically whenever that app is in front.
