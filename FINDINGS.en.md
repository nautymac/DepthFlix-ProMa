# ProMa P10 (MTK X20, Android 8.0) glasses-free 3D — reverse engineering notes

*[한국어](FINDINGS.md) · English*

## Device
- ProMa P10 / x20l_wqxga_108_v4_4 / Android 8.0.0 (API 26) / arm64-v8a
- adb: C:\Users\nauty\platform-tools\adb.exe

## The three stock apps
| Label | Package | APK |
|---|---|---|
| Sight3D (3D看看) | com.innovate.cinema | /vendor/operator/app/3Dkankan_1110.apk |
| 3DFV | com.wztech.service3d | /vendor/operator/app/3DFV_1126.apk |
| 3DPlayer | com.android.future.video | /vendor/operator/app/3DPlayer_1114.apk |

## 3DFV = the system 3D service (com.wztech.service3d/.Service3D)
- `onBind()` returns null. **The public API is broadcasts.**
- Eye tracking: libeyecv_proc.so (3.3MB CV engine) + libK3DEyeTrack.so
- Panel control: libnative_wz2sf.so → SurfaceFlinger binder code 8001
  - send2sf(8001, (key<<16)+value, true)
  - send2sf2(8001, 19726336+windowType, activityName, true)
  - send2sf3(8001, 19791872, x,y,z,w,v, true)   // eye coordinates
  - getActivities(8001, 19857408+count)
- SEND2SF_ACTIVITY_NAME=301 FILM_INFO=302 GET_ACTIVITES=303 SWITCH=304 LR=305
- SourceType: 0=SBS_HALF 1=SBS_FULL 2=TOP_BOTTOM 3=SBS_FULLX2

### Broadcast API (verified working on the device)
```
com.wztech.service3d.Service3D.request   --es ActivityName <class name> --ei SourceType 0..3 (-1 = unregister)
com.wztech.service3d.Service3D.response  ← reply (SourceType+100, ActivityName)
com.wztech.service3d.Service3D.PING / .PONG
com.wztech.service.close_self
```
3D turns on when: landscape && activity in whitelist && keyguard gone → `updateDisplayMode(1)`

### Whitelist file
`/sdcard/K3DX/config/.white_list2.config`
Format: `<windowType><sourceType>@<activity class name>`, `!` = disabled, `#` = comment
windowType: 0=at, 1=sv, 2=at|sv, 3=first layer

**YouTube is already registered**: `10@com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity`
Chrome: `30@org.chromium.chrome.browser.ChromeTabbedActivity`
spacedesk: `10@ph.spacedesk...SAActivityDisplay`

`/sdcard/K3DX/config/.3d.properties` → navflag=1, viewpoint=1

## Native render libraries (static JNI — reusable if the class name matches exactly)
```
libholography.so → class com.future.Holography.Holography
  HolographyInit(int,int) HolographyInit2 HolographySetSize deinitHolography
  update(int,int) updateJZ setAngle startAutoSwitch stopAutoSwitch
  getx gety getdis getCurGS getEfficiency sendDelt
  NOTE: update() writes the lenticular mask into the currently bound GL_TEXTURE_2D

libDrawVideoC.so → class com.future.Holography.RenderDrawByC
  drawRender(aPosLoc, aTexLoc)          NOTE: args are attribute locations, not width/height
  drawRender2D / 2DR / 2DTop / 2DBottom  only set up vertices for fixed crops
  setPercent(float,float)
```
The 3Dkankan APK ships both arm64-v8a and armeabi-v7a (3DPlayer has no arm64 DrawVideoC).

## Render pipeline (3DPlayer VideoGLSurfaceView.onDrawFrame)
```
MediaPlayer → SurfaceTexture(OES 36197) → [FBO] → frag3D interlace → GLSurfaceView
```
- `k` = FBO wrapper, `h` = SBS/TB extractor (frag2d.sh), `i` = 2D→3D shear (frag2dto3d.sh),
  `j` = final interlace (frag3D.sh)
- `B` = source layout: 1=SBS, 2=TopBottom, anything else=2D

### MODE_3D draw
```
k.a()                                    // bind FBO
 B==1 (SBS): left viewport h.b()   right viewport h.c()
 B==2 (TB) : left viewport h.e()   right viewport h.d()
 otherwise (2D): right viewport i.a(2.0f)   ← is2dto3d=2.0, shear applied
                 left  viewport i.a(0.0f)   ← original
k.b()
j.a(...)                                 // interlace via frag3D
```

## Shaders (assets/*.sh, extracted to proma3d/shaders/)

### frag2dto3d.sh — synthesising 2D→3D parallax
```glsl
if (is2dto3d > 1.0) {
  float p = vTextureCoord.y * screenHeight;
  vec2 tmp = vTextureCoord;
  tmp.x += 0.004 - p*0.0000122;   // +0.004 at top, decreasing linearly (ground-plane assumption)
  gl_FragColor = texture2D(sTexture, tmp);
} else gl_FragColor = texture2D(sTexture, vTextureCoord);
if (vTextureCoord.t > 0.990740741) gl_FragColor = vec4(0,0,0,1);  // black bottom band
```

### frag3D.sh — lenticular interlace (Sampler0 = SBS image, Sampler1 = Holography mask)
```glsl
coord2.s = vTextureCoord.s*0.5 + perOffset;        coll = texture2D(Sampler0, coord2);
coord2.s = vTextureCoord.s*0.5 + 0.5 - perOffset;  colr = texture2D(Sampler0, coord2);
dis_test = texture2D(Sampler1, vTextureCoord).r;
rgb = (1.0-dis_test)*colr + dis_test*coll;
if (fract(dis_test) != 0.0) rgb *= (abs(dis_test-0.5)+0.5);   // crosstalk suppression
```
`perOffset` = depth strength (clamped to ±0.015).
`frag3Dsx.sh` is the variant without perOffset, for genuine stereo pairs.

## Development environment
- JDK 21: C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
- Android SDK: C:\Android\sdk
- jadx: C:\Users\nauty\AppData\Local\Microsoft\WinGet\Packages\Skylot.jadx_*/lib/jadx-gui-1.5.6-all.jar
- Decompiled sources: proma3d/src/{3DPlayer,3DFV,3Dkankan}

## Corrections and additions (found during implementation)

- The two arguments to `RenderDrawByC.*` are `glGetAttribLocation` results
  (`aPosition`, `aTextureCoord`), not width/height. The native side only sets up the vertex
  and texture-coordinate arrays; Java issues the actual `glDrawArrays`.
  → Since that is nothing more than vertex setup for fixed crops, **libDrawVideoC.so is
  unnecessary**. Cropping with UVs in Java does the same job.
  The only native library actually needed is libholography.so (lenticular mask generation).

- There is a swap bug in the stock `VideoGLSurfaceView.onSurfaceChanged`:
  ```java
  if (i >= i2) { i2 = i; i = i2; }     // swap attempted without a temp → both become i
  ```
  In landscape this makes `c = d = 2560`, and 2560 also lands in the `screenHeight` uniform
  of frag2dto3d.
  → Shear range: +0.004 at the top to (0.004 - 2560*0.0000122) = -0.0272 at the bottom.

- 3DPlayer only ever calls `Holography.update(0,0)` (fixed centre viewpoint).
  `setAngle`/`sendDelt` are unused, and the camera face detection (RFF*.bmd,
  `takee.camera.*` intents) is not wired into this render path.
  → A new app can work without the camera.

- 3DPlayer is not in the whitelist. It renders its own interlacing, so it must not be
  registered with 3DFV (that would process it twice).

## Eye tracking — not possible with libholography (measured, investigation closed)

**The goal:** when the tablet is handheld and moves off-axis, the left/right views mix and
the 3D breaks. Feeding the viewer's eye position into the mask would fix it.

**Conclusion: `libholography.so` has no view-steering capability.**

### Evidence 1 — the stock apps do not use it
```java
// Holography.java in Sight3D (3Dkankan)
public static void startFaceDetector() { }   // empty stub
public static void stopFaceDetector()  { }   // empty stub
// Render3D.java:146
Holography.update(0, 0);                     // fixed centre
```
3DPlayer does the same — `update(0, 0)` only.

### Evidence 2 — the getters always return 0
Polling `getx/gety/getdis` after calling `startAutoSwitch()`:
```
startAutoSwitch: succeeded (no crash — the no-arg void/int signatures are safe)
probe 0: x=0 y=0 dis=0
probe 1: x=0 y=0 dis=0
```
The symbols exist but nothing populates them.

### Evidence 3 — update(x, y) ignores its arguments
Rendering the same frame with only the eye coordinate changed, then comparing pixels:
```
eye_300 vs eye_900   mean diff 0.000  max diff 0   ← bit-identical
```
Changing x from 300 to 900 produces exactly the same output. The mask does not move.

### Eye tracking lives only in 3DFV
3DFV tracks eyes with `libeyecv_proc.so` (a 3.3MB CV engine) and sends the coordinates
straight to SurfaceFlinger (`send2sf3(8001, 19791872, x, y, ...)`). That path serves
whitelisted apps only, so a player that renders its own interlacing gets no benefit.
(Adding our app to the whitelist would double-process and break the image.)

### What would remain, and what it costs
We would have to track eyes ourselves **and generate the mask without libholography** —
reverse engineering the format of `/sdcard/3DKanKan/matrix` (8,192,000 = 2560×1600×2 bytes),
reproducing it in GLSL, and shifting its phase with eye position. Days of work, and it may
not pan out.

**Practical alternative:** view it on a stand, or press `Swap L/R` when the view flips.
