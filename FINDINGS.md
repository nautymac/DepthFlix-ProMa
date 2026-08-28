# ProMa P10 (MTK X20, Android 8.0) 무안경 3D 리버스엔지니어링 결과

## 기기
- ProMa P10 / x20l_wqxga_108_v4_4 / Android 8.0.0 (API 26) / arm64-v8a
- adb: C:\Users\nauty\platform-tools\adb.exe

## 앱 3종
| 라벨 | 패키지 | APK |
|---|---|---|
| Sight3D (3D看看) | com.innovate.cinema | /vendor/operator/app/3Dkankan_1110.apk |
| 3DFV | com.wztech.service3d | /vendor/operator/app/3DFV_1126.apk |
| 3DPlayer | com.android.future.video | /vendor/operator/app/3DPlayer_1114.apk |

## 3DFV = 시스템 3D 서비스 (com.wztech.service3d/.Service3D)
- onBind() → null. **공개 API는 브로드캐스트**.
- 시선추적: libeyecv_proc.so(3.3MB CV) + libK3DEyeTrack.so
- 패널 제어: libnative_wz2sf.so → SurfaceFlinger 바인더 코드 8001
  - send2sf(8001, (key<<16)+value, true)
  - send2sf2(8001, 19726336+windowType, activityName, true)
  - send2sf3(8001, 19791872, x,y,z,w,v, true)   // 눈 좌표
  - getActivities(8001, 19857408+count)
- SEND2SF_ACTIVITY_NAME=301 FILM_INFO=302 GET_ACTIVITES=303 SWITCH=304 LR=305
- SourceType: 0=SBS_HALF 1=SBS_FULL 2=TOP_BOTTOM 3=SBS_FULLX2

### 브로드캐스트 API (기기에서 동작 검증 완료)
```
com.wztech.service3d.Service3D.request   --es ActivityName <클래스명> --ei SourceType 0..3 (-1=해제)
com.wztech.service3d.Service3D.response  ← 응답 (SourceType+100, ActivityName)
com.wztech.service3d.Service3D.PING / .PONG
com.wztech.service.close_self
```
3D 켜지는 조건: 가로모드 && 화이트리스트 등록 && 잠금해제 → updateDisplayMode(1)

### 화이트리스트 파일
/sdcard/K3DX/config/.white_list2.config  (형식: <windowType><sourceType>@<액티비티클래스명>, !=비활성, #=주석)
windowType: 0=at 1=sv 2=at|sv 3=first layer
**YouTube 이미 등록됨**: 10@com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
Chrome: 30@org.chromium.chrome.browser.ChromeTabbedActivity
spacedesk: 10@ph.spacedesk...SAActivityDisplay
/sdcard/K3DX/config/.3d.properties → navflag=1, viewpoint=1

## 네이티브 렌더 라이브러리 (정적 JNI — 클래스명 그대로 맞추면 재사용 가능)
libholography.so → class com.future.Holography.Holography
  HolographyInit(int,int) HolographyInit2 HolographySetSize deinitHolography
  update(int,int) updateJZ setAngle startAutoSwitch stopAutoSwitch
  getx gety getdis getCurGS getEfficiency sendDelt
  ※ update()는 현재 바인드된 GL_TEXTURE_2D에 렌티큘러 마스크를 써넣음
libDrawVideoC.so → class com.future.Holography.RenderDrawByC
  drawRender(aPosLoc, aTexLoc)          ※ 인자는 width/height 가 아니라 어트리뷰트 로케이션
  drawRender2D / 2DR / 2DTop / 2DBottom  고정 크롭용 정점 세팅만 수행
  setPercent(float,float)
※ 3Dkankan APK가 arm64-v8a + armeabi-v7a 둘 다 보유 (3DPlayer는 arm64에 DrawVideoC 없음)

## 렌더 파이프라인 (3DPlayer VideoGLSurfaceView.onDrawFrame)
MediaPlayer → SurfaceTexture(OES 36197) → [FBO] → frag3D 인터레이스 → GLSurfaceView
- k = FBO 래퍼, h = SBS/TB 추출기(frag2d.sh), i = 2D→3D 시어(frag2dto3d.sh), j = 최종 인터레이스(frag3D.sh)
- B = 소스 포맷: 1=SBS, 2=TopBottom, 그 외=2D

### MODE_3D 드로우
k.a()                                    // FBO 바인드
 B==1(SBS): 좌뷰포트 h.b()  우뷰포트 h.c()
 B==2(TB) : 좌뷰포트 h.e()  우뷰포트 h.d()
 그 외(2D): 우뷰포트 i.a(2.0f)   ← is2dto3d=2.0 시어 적용
            좌뷰포트 i.a(0.0f)   ← 원본
k.b()
j.a(...)                                 // frag3D로 인터레이스

## 셰이더 (assets/*.sh, proma3d/shaders/ 에 추출됨)
### frag2dto3d.sh — 2D→3D 시차 생성
```glsl
if (is2dto3d > 1.0) {
  float p = vTextureCoord.y * screenHeight;
  vec2 tmp = vTextureCoord;
  tmp.x += 0.004 - p*0.0000122;   // 위=+0.004, 아래로 선형감소 (지면평면 가정)
  gl_FragColor = texture2D(sTexture, tmp);
} else gl_FragColor = texture2D(sTexture, vTextureCoord);
if (vTextureCoord.t > 0.990740741) gl_FragColor = vec4(0,0,0,1);  // 하단 밴드 블랙
```
### frag3D.sh — 렌티큘러 인터레이스 (Sampler0=SBS 이미지, Sampler1=Holography 마스크)
```glsl
coord2.s = vTextureCoord.s*0.5 + perOffset;        coll = texture2D(Sampler0, coord2);
coord2.s = vTextureCoord.s*0.5 + 0.5 - perOffset;  colr = texture2D(Sampler0, coord2);
dis_test = texture2D(Sampler1, vTextureCoord).r;
rgb = (1.0-dis_test)*colr + dis_test*coll;
if (fract(dis_test) != 0.0) rgb *= (abs(dis_test-0.5)+0.5);   // 크로스토크 억제
```
perOffset = 깊이 강도 (±0.015 로 제한)
frag3Dsx.sh = perOffset 없는 실스테레오용 변형

## 개발 환경
- JDK 21: C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
- Android SDK: C:\Android\sdk
- jadx: C:\Users\nauty\AppData\Local\Microsoft\WinGet\Packages\Skylot.jadx_*/lib/jadx-gui-1.5.6-all.jar
- 디컴파일 소스: proma3d/src/{3DPlayer,3DFV,3Dkankan}

## 정정 / 추가 (구현 중 확인)
- RenderDrawByC.* 의 두 인자는 width/height 가 아니라 glGetAttribLocation 결과(aPosition, aTextureCoord).
  네이티브가 정점/텍스처좌표 배열만 세팅해 주고, 실제 드로우는 자바가 glDrawArrays 로 한다.
  → 고정 크롭 정점 세팅에 불과하므로 **libDrawVideoC.so 는 불필요**. 자바에서 UV 로 크롭하면 된다.
  실제로 필요한 네이티브는 libholography.so 뿐 (렌티큘러 마스크 생성).
- 원본 VideoGLSurfaceView.onSurfaceChanged 에 스왑 버그:
      if (i >= i2) { i2 = i; i = i2; }     // temp 없이 스왑 시도 → 둘 다 i 가 됨
  결과적으로 가로모드에서 c = d = 2560 이 되고, frag2dto3d 의 screenHeight 유니폼에도 2560 이 들어간다.
  → 시어 범위: 상단 +0.004 ~ 하단 (0.004 - 2560*0.0000122) = -0.0272
- 3DPlayer 는 Holography.update(0,0) (고정 중앙 시점) 만 호출한다. setAngle/sendDelt 는 미사용.
  카메라 얼굴검출(RFF*.bmd, takee.camera.* 인텐트)은 이 렌더 경로에 연결돼 있지 않다.
  → 새 앱도 카메라 없이 동작 가능.
- 3DPlayer 는 화이트리스트에 없다. 자체 인터레이스를 렌더하므로 3DFV 에 등록하면 안 된다 (이중 처리).
