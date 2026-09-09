package com.nauty.p3d;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nauty.p3d.engine.ExoEngine;
import com.nauty.p3d.engine.TrackInfo;
import com.nauty.p3d.engine.VideoEngine;
import com.nauty.p3d.gl.Stereo3DView;
import com.nauty.p3d.subtitle.SubtitleBitmap;
import com.nauty.p3d.subtitle.Subtitles;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends Activity
        implements Stereo3DView.Callback, VideoEngine.Listener {

    public static final String EXTRA_TITLE = "title";
    /**
     * 이 URI 가 사진이라는 표시.
     *
     * 사진도 같은 화면에서 본다. 3D 로 내보내는 길이 영상과 완전히 같기 때문이다 —
     * 레터박스, SBS/TB 크롭, 수렴, 인터레이스까지 그대로 쓴다. 다른 것은 프레임을
     * 만들어 넣는 쪽뿐이라 {@code PhotoEngine} 하나만 갈아 끼운다.
     */
    public static final String EXTRA_PHOTO = "photo";
    /** 이전/다음으로 넘길 범위가 되는 폴더 경로. 없으면 전체. */
    public static final String EXTRA_FOLDER = "folder";

    private static final String TAG        = "P3D";
    private static final String PREFS      = "p3d";
    private static final String KEY_FORMAT = "fmt:";
    private static final String KEY_SUB_SCALE = "sub_scale";
    private static final String KEY_SUB_Y     = "sub_y";
    private static final String KEY_SUB_DEPTH = "sub_depth";
    private static final String KEY_POS       = "pos:";
    private static final String KEY_ASPECT    = "aspect";
    /** 수렴은 파일마다 다르다 — 그 소스를 만들 때 쓴 설정의 문제이기 때문이다. */
    private static final String KEY_CONV      = "conv:";

    /** 끝에서 이 시간 안쪽이면 "다 봤다" 로 보고 이어보기를 하지 않는다. */
    private static final long END_MARGIN_MS  = 30_000;
    /** 이보다 앞이면 저장할 가치가 없다. */
    private static final long MIN_SAVE_MS    = 15_000;
    private static final long SAVE_EVERY_MS  = 5_000;

    /** 재생이 시작되고 길이가 확정되면 이 지점으로 이동한다. 0 이면 없음. */
    private long pendingResumeMs = 0;
    private long lastSavedAt     = 0;

    /** 사용자가 직접 고르거나 이전에 고른 값을 불러온 경우. 자동 판별보다 우선한다. */
    private boolean manualChoice = false;
    /** 픽셀 판별이 결과를 적용했는지. 해상도 기반 보정이 덮어쓰지 않게 한다. */
    private boolean detected = false;
    private String  mediaKey;

    // ---- 사진 모드
    private boolean isPhoto = false;
    private java.util.List<MediaLibrary.Item> photos;
    private int photoIndex = -1;

    /**
     * 재생을 시작해도 되는 조건.
     *
     * 영상은 배치 판별이 재생 중에 끝나도 되지만(끝나면 갈아끼운다), 사진은 굳이
     * 그럴 이유가 없다. 원본 해상도를 열기 전에 정확히 읽을 수 있어서 띄우기 전에
     * 배치를 확정할 수 있고, 그러면 2D 로 한 번 나왔다가 3D 로 바뀌는 깜빡임이 없다.
     */
    private boolean surfaceReady = false;
    private boolean formatReady  = true;   // 영상은 처음부터 참

    private Stereo3DView glView;
    private VideoEngine  engine;
    private Surface        videoSurface;
    private SurfaceTexture videoSurfaceTexture;
    private Uri pendingUri;
    private File videoFile;

    // 재생바
    private LinearLayout bottomBar;
    private Button btnPlay, btnList, btnSettings;
    private SeekBar seekBar;
    private TextView timeText;

    // 설정 패널
    private View settingsPanel;
    private Button btnSource, btnOutput, btnSwap, btnSubtitle, btnAspect, btnAudioTrack;
    private TextView statusText, subtitleName, convLabel;
    private SeekBar  convSeek;
    /** 마지막 시차 측정 결과를 상태창에 남겨둔다. */
    private String convMeasured = null;

    // 자막
    private Subtitles.Track subtitleTrack;
    private String lastCueText = null;
    private float subtitleScale = 1.0f;
    /** 내장 자막 트랙을 골랐을 때만 채워진다. 있으면 tick() 의 외부 자막 경로를 건너뛴다. */
    private TrackInfo selectedEmbeddedText;
    /** 지금 고른 오디오 트랙. null 이면 기기 기본값. 표시용으로만 들고 있는다. */
    private TrackInfo selectedAudioTrack;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seeking = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        pendingUri = getIntent().getData();
        if (pendingUri == null) {
            Toast.makeText(this, "재생할 영상이 지정되지 않았습니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new Stereo3DView(this);
        glView.setCallback(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 재생바는 아래, 설정 패널은 오른쪽 — 서로 겹치지 않는다.
        // 둘 다 네비게이션 바 높이만큼 띄운다 (navBarHeight() 주석 참고).
        int navH = navBarHeight();

        View bar = buildBottomBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        barLp.bottomMargin = navH;
        root.addView(bar, barLp);

        settingsPanel = buildSettingsPanel();
        settingsPanel.setVisibility(View.GONE);
        settingsPanel.setPadding(0, 0, 0, navH);
        root.addView(settingsPanel, new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        glView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideSystemUi();      // 네비게이션 바가 올라와 있으면 다시 내린다
                if (settingsPanel.getVisibility() == View.VISIBLE) {
                    settingsPanel.setVisibility(View.GONE);
                } else {
                    bottomBar.setVisibility(
                            bottomBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }
        });

        setContentView(root);

        String name = getIntent().getStringExtra(EXTRA_TITLE);
        if (name == null) name = pendingUri.getLastPathSegment();
        mediaKey = name == null ? "" : name;

        isPhoto = getIntent().getBooleanExtra(EXTRA_PHOTO, false) || looksLikePhoto(pendingUri);

        if (isPhoto) {
            // 이전/다음 사진으로 넘기려면 목록이 필요하다. 목록 화면과 같은 정렬을
            // 다시 돌려서 지금 사진의 자리를 찾는다 (MediaLibrary 주석 참고).
            String folder = getIntent().getStringExtra(EXTRA_FOLDER);
            if (folder == null) folder = MediaLibrary.folderOf(this, pendingUri);
            photos = MediaLibrary.list(this, MediaLibrary.Kind.IMAGE, folder);
            photoIndex = MediaLibrary.indexOf(photos, pendingUri);
            beginPhoto();
            applySavedSubtitlePrefs();
            videoFile = resolveVideoFile(pendingUri);
            refreshLabels();
            return;
        }

        SourceFormat saved = loadSavedFormat(mediaKey);
        if (saved != null) {
            // 전에 사용자가 직접 고른 값. 이건 무엇보다 우선한다.
            manualChoice = true;
            applySourceFormat(saved);
        } else {
            // 파일명은 참고만 한다 — 틀리게 붙어 있는 경우가 흔하다.
            // 판별이 끝날 때까지의 임시값으로만 쓰고, 결과가 나오면 픽셀 판별로 덮어쓴다.
            SourceFormat byName = SourceFormat.fromName(mediaKey);
            applySourceFormat(byName != null ? byName : SourceFormat.MONO_2D);
            startStereoDetection();
        }

        applySavedSubtitlePrefs();
        videoFile = resolveVideoFile(pendingUri);
        autoLoadSubtitle();
        refreshLabels();
    }

    /**
     * 저장된 자막 설정을 적용한다.
     * SeekBar 는 setProgress 시점에 리스너가 없어서 콜백이 안 오고,
     * 슬라이더 생성 도중엔 아직 만들어지지 않은 버튼이 있어 refreshLabels 도 못 부른다.
     * 그래서 화면 구성이 끝난 뒤 여기서 한 번에 적용한다.
     */
    private void applySavedSubtitlePrefs() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        subtitleScale = Math.max(0.4f, sp.getInt(KEY_SUB_SCALE, 100) / 100f);
        glView.setSubtitleY(sp.getInt(KEY_SUB_Y, 4) / 100f);
        glView.setSubtitleDepth(sp.getInt(KEY_SUB_DEPTH, 0));
        glView.setAspectOverride(sp.getFloat(KEY_ASPECT, 0f));
        applySavedGeometryPrefs();
    }

    /** 파일마다 따로 기억하는 값. 사진은 넘길 때마다 다시 읽어야 한다. */
    private void applySavedGeometryPrefs() {
        glView.setConvergence(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat(KEY_CONV + mediaKey, 0f));
        syncConvergenceUi();
    }

    // ------------------------------------------------------------ 사진

    /** 인텐트로 열렸을 때는 사진이라는 표시가 없다. MIME 과 확장자로 알아본다. */
    private boolean looksLikePhoto(Uri uri) {
        try {
            String t = getContentResolver().getType(uri);
            if (t != null) return t.startsWith("image/");
        } catch (Exception ignored) { }
        return MediaLibrary.isPhotoName(uri.getLastPathSegment());
    }

    /**
     * 사진의 스테레오 배치를 정하고, 정해지면 띄운다.
     *
     * 영상과 달리 재생을 먼저 시작하지 않는다. 사진은 원본 해상도를 여는 즉시
     * 정확히 읽을 수 있어서(inJustDecodeBounds) half/full 을 틀릴 일이 없고,
     * 판별도 프레임 한 장이면 끝난다. 확정한 뒤에 띄우면 화면이 한 번도 안 튄다.
     */
    private void beginPhoto() {
        SourceFormat saved = loadSavedFormat(mediaKey);
        if (saved != null) {
            manualChoice = true;
            applySourceFormat(saved);
            formatReady = true;
            maybeStartPlayback();
            return;
        }

        formatReady = false;
        final Uri uri = pendingUri;
        new Thread(new Runnable() {
            @Override public void run() {
                final SourceFormat f = StereoDetect.detectImage(PlayerActivity.this, uri);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (uri != pendingUri) return;   // 그 사이 다른 사진으로 넘어갔다
                        if (!manualChoice) {
                            detected = (f != null);
                            applySourceFormat(f == null ? SourceFormat.MONO_2D : f);
                        }
                        formatReady = true;
                        maybeStartPlayback();
                        refreshLabels();
                    }
                });
            }
        }, "photo-detect").start();
    }

    /** 목록에서 delta 만큼 떨어진 사진으로 넘어간다. 끝에서는 반대편으로 돈다. */
    private void showPhoto(int delta) {
        if (photos == null || photos.isEmpty()) return;
        if (photoIndex < 0) photoIndex = 0;
        int n = photos.size();
        photoIndex = ((photoIndex + delta) % n + n) % n;

        MediaLibrary.Item it = photos.get(photoIndex);
        pendingUri = it.uri;
        mediaKey   = it.name;

        if (engine != null) { engine.release(); engine = null; }

        manualChoice = false;
        detected     = false;
        convMeasured = null;
        selectedEmbeddedText = null;   // 사진에는 트랙이 없다 — 표시만 정리
        selectedAudioTrack   = null;
        applySavedGeometryPrefs();
        videoFile = resolveVideoFile(pendingUri);

        Toast.makeText(this, (photoIndex + 1) + " / " + n + "  " + it.name,
                Toast.LENGTH_SHORT).show();
        beginPhoto();
        refreshLabels();
    }

    private void maybeStartPlayback() {
        if (!surfaceReady || !formatReady) return;
        startPlayback();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- 이어보기

    /** 이 파일을 마지막으로 본 지점. 없으면 0. */
    private long loadResumeMs() {
        if (mediaKey == null || mediaKey.isEmpty()) return 0;
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_POS + mediaKey, 0);
    }

    /**
     * 재생 위치를 주기적으로 저장한다. 매 틱(150ms)마다 쓰면 낭비라 5초 간격으로만 기록한다.
     * 끝까지 본 파일은 기록을 지워서 다음에 처음부터 시작하게 한다.
     */
    private void savePositionPeriodically(long pos, long dur) {
        if (dur <= 0 || seeking) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSavedAt < SAVE_EVERY_MS) return;
        lastSavedAt = now;
        writePosition(pos, dur);
    }

    private void writePosition(long pos, long dur) {
        if (mediaKey == null || mediaKey.isEmpty() || dur <= 0) return;
        android.content.SharedPreferences.Editor e =
                getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (pos < MIN_SAVE_MS || pos >= dur - END_MARGIN_MS) {
            e.remove(KEY_POS + mediaKey);      // 초반이거나 다 봤으면 기억할 것이 없다
        } else {
            e.putLong(KEY_POS + mediaKey, pos);
        }
        e.apply();
    }

    /** 앱을 벗어나거나 닫을 때는 즉시 기록한다. */
    private void savePositionNow() {
        if (engine == null) return;
        writePosition(engine.getPosition(), engine.getDuration());
    }

    /** 현재 위치에서 상대 이동. 사진이면 이전/다음 장으로 넘어간다. */
    private void skip(long deltaMs) {
        if (isPhoto) {
            // 30초 -> 한 장, 5분 -> 열 장.
            int step = Math.abs(deltaMs) >= 300_000L ? 10 : 1;
            showPhoto(deltaMs > 0 ? step : -step);
            return;
        }
        if (engine == null) return;
        long dur = engine.getDuration();
        long target = engine.getPosition() + deltaMs;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur - 1000) target = Math.max(0, dur - 1000);
        engine.seekTo(target);
        lastCueText = null;                     // 자막 다시 계산
        Toast.makeText(this, (deltaMs > 0 ? "▶▶ " : "◀◀ ") + fmt(target), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 재생바

    private View buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.argb(200, 0, 0, 0));
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottomBar.setClickable(true);          // 터치가 glView 로 새지 않게

        btnList = new Button(this);
        btnList.setText("≡ 목록");
        btnList.setAllCaps(false);
        btnList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToList(); }
        });
        bottomBar.addView(btnList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 빠른 이동. 짧게 = 30초, 길게 = 5분.
        addSkipButton(bottomBar, "◀◀", -30_000L, -300_000L);

        btnPlay = new Button(this);
        btnPlay.setText("❚❚");
        btnPlay.setAllCaps(false);
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engine == null) return;
                if (engine.isPlaying()) engine.pause(); else engine.play();
                refreshLabels();
            }
        });
        bottomBar.addView(btnPlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addSkipButton(bottomBar, "▶▶", 30_000L, 300_000L);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (engine != null) engine.seekTo(s.getProgress() * 1000L);
                lastCueText = null;                 // 자막 다시 계산
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        sp.rightMargin = dp(12);
        bottomBar.addView(seekBar, sp);

        timeText = new TextView(this);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(14f);
        timeText.setText("00:00 / 00:00");
        bottomBar.addView(timeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        btnSettings = new Button(this);
        btnSettings.setText("⚙ 설정");
        btnSettings.setAllCaps(false);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = settingsPanel.getVisibility() != View.VISIBLE;
                if (show) {
                    // 재생바를 가리지 않도록 패널을 그 위에서 끝낸다.
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) settingsPanel.getLayoutParams();
                    lp.bottomMargin = bottomBar.getHeight();
                    settingsPanel.setLayoutParams(lp);
                }
                settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                refreshLabels();
            }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(12);
        bottomBar.addView(btnSettings, gp);

        return bottomBar;
    }

    /** 빠른 이동 버튼. 짧게 누르면 short, 길게 누르면 long 만큼 이동한다. */
    private void addSkipButton(LinearLayout parent, String label,
                               final long shortMs, final long longMs) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skip(shortMs); }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { skip(longMs); return true; }
        });
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void backToList() {
        savePositionNow();
        if (engine != null) { engine.pause(); }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    // ---------------------------------------------------------- 설정 패널

    private View buildSettingsPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.argb(230, 16, 20, 24));
        scroll.setClickable(true);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12f);
        p.addView(statusText);

        p.addView(header("3D"));
        btnSource = panelButton(p, "소스", new View.OnClickListener() {
            @Override public void onClick(View v) {
                manualChoice = true;
                applySourceFormat(glView.getSourceFormat().next());
                refreshLabels();
            }
        });
        btnOutput = panelButton(p, "출력", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Stereo3DView.Output[] cycle = {
                        Stereo3DView.Output.THREE_D,
                        Stereo3DView.Output.TWO_D,
                        Stereo3DView.Output.SBS_DEBUG};
                Stereo3DView.Output cur = glView.getOutput();
                int i = 0;
                for (int k = 0; k < cycle.length; k++) if (cycle[k] == cur) i = k;
                glView.setOutput(cycle[(i + 1) % cycle.length]);
                refreshLabels();
            }
        });
        btnSwap = panelButton(p, "좌우반전", new View.OnClickListener() {
            @Override public void onClick(View v) {
                glView.setSwapLR(!glView.isSwapLR());
                refreshLabels();
            }
        });

        // 소스를 잘못 건드리면 그 선택이 이 파일에 저장돼 이후 자동 판별이 막힌다.
        // 되돌릴 방법이 있어야 한다.
        panelButton(p, "↺ 자동 판별로 되돌리기", new View.OnClickListener() {
            @Override public void onClick(View v) { redetect(); }
        });

        p.addView(label("깊이 (2D→3D 시차 강도)"));
        p.addView(slider(300, 100, new OnValue() {
            @Override public void set(int v) { glView.setDepth(v / 100f); refreshLabels(); }
        }));

        // 수렴 보정.
        //
        // 게임에서 뽑은 SBS 는 만들 때의 화면과 convergence 설정이 픽셀 수로 굳어 있다.
        // 그것을 이 화면 폭에 맞춰 늘리거나 줄이면 시차도 같은 비율로 변해 소스마다
        // 입체가 다르게 느껴진다. 여기서 화면 기준으로 다시 맞춘다.
        convLabel = label(convText());
        p.addView(convLabel);
        convSeek = slider(CONV_STEPS, convSliderInit(), new OnValue() {
            @Override public void set(int v) { setConvergence(v - CONV_MID, true); }
        });
        p.addView(convSeek);

        panelButton(p, "수렴 자동 (장면 중심을 화면에)", new View.OnClickListener() {
            @Override public void onClick(View v) { autoConverge(); }
        });

        p.addView(header("자막"));
        subtitleName = new TextView(this);
        subtitleName.setTextColor(Color.LTGRAY);
        subtitleName.setTextSize(11f);
        p.addView(subtitleName);

        // 자막 선택과 오디오 트랙을 한 줄에 나란히 — 컨테이너 안의 두 트랙 종류를
        // 같은 자리에서 다루는 것이 자연스럽다.
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);

        btnSubtitle = new Button(this);
        btnSubtitle.setText("자막 선택");
        btnSubtitle.setAllCaps(false);
        btnSubtitle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickSubtitle(); }
        });
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        subRow.addView(btnSubtitle, subLp);

        btnAudioTrack = new Button(this);
        btnAudioTrack.setText("오디오 트랙");
        btnAudioTrack.setAllCaps(false);
        btnAudioTrack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAudioTrack(); }
        });
        LinearLayout.LayoutParams audioLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        audioLp.leftMargin = dp(8);
        subRow.addView(btnAudioTrack, audioLp);

        p.addView(subRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 자막 관련 값은 저장해 둔다. 매번 다시 맞추게 하면 안 된다.
        final android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        p.addView(label("자막 크기"));
        p.addView(slider(200, sp.getInt(KEY_SUB_SCALE, 100), new OnValue() {
            @Override public void set(int v) {
                subtitleScale = Math.max(0.4f, v / 100f);
                lastCueText = null;                 // 다시 그리게
                sp.edit().putInt(KEY_SUB_SCALE, v).apply();
            }
        }));

        p.addView(label("자막 위치 (아래에서 올림)"));
        p.addView(slider(40, sp.getInt(KEY_SUB_Y, 4), new OnValue() {
            @Override public void set(int v) {
                glView.setSubtitleY(v / 100f);
                sp.edit().putInt(KEY_SUB_Y, v).apply();
            }
        }));

        p.addView(label("자막 깊이 (앞으로 튀어나옴)"));
        p.addView(slider(60, sp.getInt(KEY_SUB_DEPTH, 0), new OnValue() {
            @Override public void set(int v) {
                glView.setSubtitleDepth(v);
                sp.edit().putInt(KEY_SUB_DEPTH, v).apply();
            }
        }));

        btnAspect = panelButton(p, "화면 비", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleAspect(); }
        });

        // 엔진 선택 버튼은 없다. 영상은 ExoPlayer 하나뿐이다 (VideoEngine 주석 참고).

        scroll.addView(p);
        return scroll;
    }

    private interface OnValue { void set(int v); }

    private SeekBar slider(int max, int initial, final OnValue cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(initial);
        s.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { cb.set(p); }
        });
        return s;
    }

    private TextView header(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#00D8FF"));
        tv.setTextSize(13f);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.LTGRAY);
        tv.setTextSize(11f);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private Button panelButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    // -------------------------------------------------------------- 자막

    /** content:// / file:// 에서 실제 파일 경로를 얻는다 (자막을 옆에서 찾기 위해). */
    private File resolveVideoFile(Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                return new File(uri.getPath());
            }
            if ("content".equals(uri.getScheme())) {
                Cursor c = getContentResolver().query(
                        uri, new String[]{MediaStore.Video.Media.DATA}, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            String path = c.getString(0);
                            if (path != null) return new File(path);
                        }
                    } finally { c.close(); }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void autoLoadSubtitle() {
        File sub = Subtitles.findSibling(videoFile);
        if (sub != null) loadSubtitle(sub);
        else updateSubtitleName();
    }

    private void loadSubtitle(File f) {
        subtitleTrack = Subtitles.load(f);
        lastCueText = null;
        glView.setSubtitleBitmap(null);
        if (subtitleTrack == null) {
            Toast.makeText(this, "자막을 읽지 못했습니다: " + f.getName(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "자막 " + subtitleTrack.name
                    + " (" + subtitleTrack.size() + "개)", Toast.LENGTH_SHORT).show();
        }
        updateSubtitleName();
    }

    private void updateSubtitleName() {
        if (subtitleName == null) return;
        String txt = selectedEmbeddedText != null ? selectedEmbeddedText.label
                : subtitleTrack == null ? "자막 없음" : subtitleTrack.name;
        subtitleName.setText(txt);
    }

    /**
     * 영상 폴더·흔한 폴더에서 찾은 외부 자막 파일과, 컨테이너 안의 내장 자막 트랙을
     * 한 목록에 같이 보여준다.
     */
    private void pickSubtitle() {
        final List<File> found = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        if (videoFile != null && videoFile.getParentFile() != null) dirs.add(videoFile.getParentFile());
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        dirs.add(new File(Environment.getExternalStorageDirectory(), "Subtitles"));

        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && Subtitles.isSubtitle(f.getName().toLowerCase(Locale.US))
                        && !found.contains(f)) {
                    found.add(f);
                }
            }
        }

        final List<TrackInfo> embedded = engine == null
                ? Collections.<TrackInfo>emptyList() : engine.textTracks();

        if (found.isEmpty() && embedded.isEmpty()) {
            Toast.makeText(this,
                    "자막 파일을 찾지 못했습니다.\n영상과 같은 폴더나 Movies/Download 에 .srt/.smi 를 두세요.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final int extCount = found.size();
        final String[] items = new String[1 + extCount + embedded.size()];
        items[0] = "자막 없음";
        for (int i = 0; i < extCount; i++) items[1 + i] = found.get(i).getName();
        for (int i = 0; i < embedded.size(); i++) {
            TrackInfo t = embedded.get(i);
            items[1 + extCount + i] = "[내장] " + t.label + (t.imageBased ? " (이미지, 미지원)" : "");
        }

        new AlertDialog.Builder(this)
                .setTitle("자막 선택")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        selectedEmbeddedText = null;
                        subtitleTrack = null;
                        lastCueText = null;
                        glView.setSubtitleBitmap(null);
                        if (engine != null) engine.selectTextTrack(null);
                        updateSubtitleName();
                    } else if (which <= extCount) {
                        // 외부 파일을 고르면 내장 자막은 반드시 꺼야 한다 — 안 그러면
                        // onEmbeddedCue 가 계속 들어와 외부 자막과 겹친다.
                        selectedEmbeddedText = null;
                        if (engine != null) engine.selectTextTrack(null);
                        loadSubtitle(found.get(which - 1));
                    } else {
                        TrackInfo t = embedded.get(which - 1 - extCount);
                        if (t.imageBased) {
                            Toast.makeText(PlayerActivity.this,
                                    "이미지 자막(PGS/VOBSUB)은 아직 지원하지 않습니다.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        subtitleTrack = null;
                        lastCueText = null;
                        glView.setSubtitleBitmap(null);
                        selectedEmbeddedText = t;
                        if (engine != null) engine.selectTextTrack(t);
                        updateSubtitleName();
                    }
                })
                .show();
    }

    /** 컨테이너 안의 오디오 트랙을 고른다. 사진이나 트랙이 없으면 안내만 한다. */
    private void pickAudioTrack() {
        if (engine == null) return;
        final List<TrackInfo> tracks = engine.audioTracks();
        if (tracks.isEmpty()) {
            Toast.makeText(this, "오디오 트랙이 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] items = new String[tracks.size() + 1];
        items[0] = "기본값";
        for (int i = 0; i < tracks.size(); i++) items[i + 1] = tracks.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle("오디오 트랙")
                .setItems(items, (d, which) -> {
                    TrackInfo t = which == 0 ? null : tracks.get(which - 1);
                    selectedAudioTrack = t;
                    if (engine != null) engine.selectAudioTrack(t);
                    Toast.makeText(PlayerActivity.this,
                            "오디오 트랙: " + items[which], Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // -------------------------------------------------------------- 갱신

    private void tick() {
        if (engine == null) return;

        long pos = engine.getPosition();
        long dur = engine.getDuration();

        // 이어보기: 길이가 확정된 뒤에야 이동할 수 있다.
        // 엔진에 따라 seek 가 비율 기반이라 길이를 모르면 무시되기도 한다.
        if (pendingResumeMs > 0 && dur > 0) {
            long target = pendingResumeMs;
            pendingResumeMs = 0;
            if (target < dur - END_MARGIN_MS) {
                engine.seekTo(target);
                lastCueText = null;                    // 자막 다시 계산
                Toast.makeText(this, "이어보기 " + fmt(target), Toast.LENGTH_SHORT).show();
            }
        }

        if (!seeking && dur > 0) {
            seekBar.setMax((int) (dur / 1000));
            seekBar.setProgress((int) (pos / 1000));
            timeText.setText(fmt(pos) + " / " + fmt(dur));
        }

        savePositionPeriodically(pos, dur);

        // 자막.
        //
        // 크기 기준을 뷰가 아니라 "눈 하나" 로 잡는다. 이 기기에서는 둘이 같지만
        // (우리가 화면까지 그리므로), 기준을 눈 상자로 두면 마지막 렌더 단계가
        // 다른 기기에서도 같은 규칙 — 화면 높이의 4.2% — 이 그대로 성립한다.
        int subW = glView.eyeWidthPx(), subH = glView.eyeHeightPx();
        if (subW <= 0 || subH <= 0) return;      // 아직 표면이 없다. 다음 틱에 다시.

        // 내장 자막을 골랐으면 그건 onEmbeddedCue() 가 이벤트로 그린다.
        // 여기서 같이 돌리면 subtitleTrack==null 인 cue=null 이 lastCueText 를
        // 지워버려 방금 그린 내장 자막이 바로 사라진다.
        if (selectedEmbeddedText != null) return;

        String cue = subtitleTrack == null ? null : subtitleTrack.textAt(pos);
        if (cue == null ? lastCueText != null : !cue.equals(lastCueText)) {
            lastCueText = cue;
            Bitmap bmp = cue == null ? null
                    : SubtitleBitmap.render(cue, subW, subH, subtitleScale);
            glView.setSubtitleBitmap(bmp);
        }
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%02d:%02d", m, s);
    }

    @SuppressLint("SetTextI18n")
    /**
     * 화면 비 선택지. 0 = 소스 해상도가 시키는 대로 (기본).
     *
     * 소스 비율 정보가 틀렸거나 (SBS 를 half/full 로 잘못 잡은 경우 등) 위아래 검은 띠가
     * 싫을 때 쓰라고 둔다. 자동 판별이 맞으면 손댈 일이 없다.
     */
    private static final float[] ASPECTS = {
            0f, 16f / 9f, 2.40f, 1.85f, 4f / 3f, Stereo3DView.ASPECT_FILL
    };

    private static String aspectLabel(float a) {
        if (a == 0f)                        return "자동";
        if (a == Stereo3DView.ASPECT_FILL)  return "꽉 채우기";
        if (Math.abs(a - 16f / 9f)  < 0.01f) return "16:9";
        if (Math.abs(a - 2.40f)     < 0.01f) return "2.40:1";
        if (Math.abs(a - 1.85f)     < 0.01f) return "1.85:1";
        if (Math.abs(a - 4f / 3f)   < 0.01f) return "4:3";
        return String.format(Locale.US, "%.2f:1", a);
    }

    // ---------------------------------------------------------- 수렴 보정

    /** 슬라이더 눈금. 화면 시차 -320 ~ +320 px. */
    private static final int CONV_MID   = (int) Stereo3DView.CONVERGENCE_MAX;
    private static final int CONV_STEPS = CONV_MID * 2;

    private String convText() {
        float c = glView == null ? 0f : glView.getConvergence();
        if (c == 0f) return "수렴 보정 — 소스 그대로";
        return String.format(Locale.US, "수렴 보정 — %+.0f px  (%s)",
                c, c > 0 ? "화면 뒤로" : "화면 앞으로");
    }

    private int convSliderInit() {
        float c = glView == null ? 0f : glView.getConvergence();
        return Math.max(0, Math.min(CONV_STEPS, Math.round(c) + CONV_MID));
    }

    private void setConvergence(float px, boolean save) {
        glView.setConvergence(px);
        if (save && mediaKey != null && !mediaKey.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat(KEY_CONV + mediaKey, glView.getConvergence()).apply();
        }
        if (convLabel != null) convLabel.setText(convText());
        refreshLabels();
    }

    private void syncConvergenceUi() {
        if (convLabel != null) convLabel.setText(convText());
        if (convSeek  != null) convSeek.setProgress(convSliderInit());
    }

    /**
     * 지금 그림의 시차를 재서 수렴을 맞춘다.
     *
     * 규칙은 <b>장면의 중심을 화면 평면에 놓는</b> 것이다. 어느 쪽이 앞인지 몰라도
     * 성립하는 규칙이라 기본으로 삼았다 — 게임 스크린샷은 HUD 를 화면 깊이에 고정해
     * 두는 일이 많아 "아래가 가깝다" 같은 상식적인 단서가 깨진다. 중심을 화면에 놓으면
     * 깊이 폭의 절반이 앞, 절반이 뒤로 갈려 어느 해석이든 편한 범위에 들어온다.
     */
    private void autoConverge() {
        final SourceFormat f = glView.getSourceFormat();
        if (f == SourceFormat.MONO_2D) {
            Toast.makeText(this, "2D 소스에는 잴 시차가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int eyeW = glView.eyeWidthPx();
        if (eyeW <= 0) {
            Toast.makeText(this, "화면이 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "시차를 재는 중…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap frame = currentFrame();
                final Disparity.Result r = Disparity.measure(frame, f);
                // 사진은 엔진이 들고 있는 원본이라 여기서 버리면 안 된다.
                if (frame != null && !isPhoto && !frame.isRecycled()) frame.recycle();

                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (r == null) {
                            convMeasured = null;
                            Toast.makeText(PlayerActivity.this,
                                    "시차를 재지 못했습니다. 무늬가 뚜렷한 장면에서 다시 눌러보세요.",
                                    Toast.LENGTH_LONG).show();
                            refreshLabels();
                            return;
                        }
                        convMeasured = String.format(Locale.US,
                                "측정 시차 %+d … %+d px (중앙 %+d, 표본 %d)",
                                r.nearPx(eyeW), r.farPx(eyeW), r.medianPx(eyeW), r.samples);
                        setConvergence(r.centerOnScreen(eyeW), true);
                        syncConvergenceUi();
                        Toast.makeText(PlayerActivity.this,
                                convMeasured + "\n장면 중심을 화면에 맞췄습니다.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "converge").start();
    }

    /** 시차를 잴 한 장. 사진은 이미 들고 있고, 영상은 지금 지점을 다시 뜯는다. */
    private Bitmap currentFrame() {
        if (isPhoto) {
            return (engine instanceof com.nauty.p3d.engine.PhotoEngine)
                    ? ((com.nauty.p3d.engine.PhotoEngine) engine).frame() : null;
        }
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, pendingUri);
            long at = engine == null ? 0 : engine.getPosition();
            return r.getFrameAtTime(at * 1000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private void cycleAspect() {
        float cur = glView.getAspectOverride();
        int i = 0;
        for (int k = 0; k < ASPECTS.length; k++) {
            if (ASPECTS[k] == cur) { i = k; break; }
        }
        float next = ASPECTS[(i + 1) % ASPECTS.length];
        glView.setAspectOverride(next);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat(KEY_ASPECT, next).apply();
        refreshLabels();
    }

    private void refreshLabels() {
        btnPlay.setText(engine != null && engine.isPlaying() ? "❚❚" : "▶");

        // 사진에는 시간축이 없다. 재생/탐색 자리에 몇 번째 장인지를 대신 보여준다.
        if (isPhoto) {
            btnPlay.setVisibility(View.GONE);
            seekBar.setVisibility(View.GONE);
            int n = photos == null ? 0 : photos.size();
            timeText.setText(n == 0 || photoIndex < 0 ? "사진" : (photoIndex + 1) + " / " + n);
        }

        if (btnSource == null || btnSwap == null) return;   // 패널 구성 전이면 건너뛴다

        btnSource.setText("소스: " + glView.getSourceFormat().label);

        String out;
        switch (glView.getOutput()) {
            case THREE_D: out = "3D 출력";   break;
            case TWO_D:   out = "2D 출력";   break;
            default:      out = "SBS 확인"; break;
        }
        btnOutput.setText("출력: " + out);
        btnSwap.setText(glView.isSwapLR() ? "좌우반전 ON" : "좌우반전 OFF");
        if (btnAspect != null) btnAspect.setText("화면 비: " + aspectLabel(glView.getAspectOverride()));
        updateSubtitleName();

        // 지금 소스 포맷이 어디서 왔는지 보여준다. 수동으로 잘못 고른 상태를 알아채야 하기 때문.
        String how = manualChoice ? "수동 선택 (저장됨)"
                                  : (detected ? "자동 판별" : "판별 중…");

        statusText.setText(String.format(Locale.US,
                "%s · %s · %s\n소스: %s\n깊이 %.2f · 수렴 %+.0f px%s",
                currentKind().label, glView.getSourceFormat().label, out,
                how, glView.getDepth(), glView.getConvergence(),
                convMeasured == null ? "" : "\n" + convMeasured));
    }

    /** 이 파일에 저장된 소스 선택을 지우고 픽셀 판별을 다시 돌린다. */
    private void redetect() {
        if (mediaKey != null && !mediaKey.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().remove(KEY_FORMAT + mediaKey).apply();
        }
        manualChoice = false;
        detected = false;
        Toast.makeText(this, "저장된 선택을 지우고 다시 판별합니다…", Toast.LENGTH_SHORT).show();
        startStereoDetection();
        refreshLabels();
    }

    /**
     * 프레임을 뜯어 스테레오 배치를 판별한다. 파일이 크면 몇 초 걸리므로 백그라운드로 돌린다.
     * 사용자가 그 사이 직접 골랐으면 결과를 버린다.
     */
    private void startStereoDetection() {
        final Uri uri = pendingUri;
        new Thread(new Runnable() {
            @Override public void run() {
                final SourceFormat f = StereoDetect.detect(PlayerActivity.this, uri);
                if (f == null) return;
                ui.post(new Runnable() {
                    @Override public void run() {
                        // 그 사이 사용자가 직접 골랐으면 그쪽이 우선.
                        // 파일명으로 임시 적용한 값은 여기서 덮어쓴다 (이름은 틀릴 수 있다).
                        if (isFinishing() || manualChoice) return;
                        detected = true;
                        SourceFormat before = glView.getSourceFormat();
                        glView.setSourceFormat(f);   // 자동 판별이므로 저장은 하지 않는다
                        refreshLabels();
                        if (f != before) {
                            Toast.makeText(PlayerActivity.this,
                                    "3D 자동 인식: " + f.label, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "stereo-detect").start();
    }

    private void applySourceFormat(SourceFormat f) {
        glView.setSourceFormat(f);
        if (manualChoice && mediaKey != null && !mediaKey.isEmpty()) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_FORMAT + mediaKey, f.name()).apply();
        }
    }

    private SourceFormat loadSavedFormat(String key) {
        if (key == null || key.isEmpty()) return null;
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FORMAT + key, null);
        if (v == null) return null;
        try {
            return SourceFormat.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 시스템 바를 숨기는 것만으로는 부족하다. LAYOUT_* 플래그가 없으면 뷰가 바를 뺀
     * 영역(2560x1456)에만 배치되고, 그러면 GL 표면도 1456 이 된다.
     *
     * 그런데 패널의 렌티큘러 마스크(/sdcard/3DKanKan/matrix, 2560x1600x2 바이트)는
     * 화면 전체 높이 1600 기준으로 만들어져 있다. 1456 으로 HolographyInit 을 하면
     * 마스크가 어긋나 화면 아래쪽에서 인터레이스가 끊기고, 그 경계가 가로줄로 보인다.
     * (증상: 자막 두 줄 사이에 투명한 가로선)
     *
     * LAYOUT_HIDE_NAVIGATION 과 LAYOUT_FULLSCREEN 을 넣어 레이아웃을 바 아래까지
     * 확장해야 GL 표면이 2560x1600 이 되어 마스크와 일치한다.
     */
    /**
     * 네비게이션 바가 차지하는 높이.
     *
     * hideSystemUi() 가 LAYOUT_HIDE_NAVIGATION 을 걸어서 레이아웃이 화면 끝(1600px)까지
     * 뻗는다 — 자막 이음매를 없애려면 GL 표면이 패널 전체를 덮어야 하기 때문이다.
     * 그런데 그 상태에서 네비게이션 바가 다시 올라오면 화면 맨 아래에 있는 재생바가
     * 그 밑에 깔려 보이지도, 눌리지도 않는다. 그래서 재생바만 이만큼 띄워둔다.
     */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 몰입 모드는 포커스를 잃으면 풀린다 (다이얼로그, 알림 내리기, 앱 전환 등).
     * 그대로 두면 네비게이션 바가 올라온 채로 남는다. 돌아올 때마다 다시 건다.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // -------------------------------------------------------------- 엔진

    /**
     * 소스 가로 해상도. 컨테이너 헤더만 읽으므로 프레임 디코딩보다 훨씬 싸다.
     * 네트워크 URL 에서는 시간이 걸릴 수 있어 로컬 스킴에서만 본다.
     */
    private int[] probeVideoSize(Uri uri) {
        String s = uri.getScheme();
        if (s != null && !"content".equals(s) && !"file".equals(s)) return null;
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            int w = metaInt(r, android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int h = metaInt(r, android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            return (w > 0 && h > 0) ? new int[]{w, h} : null;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static int metaInt(android.media.MediaMetadataRetriever r, int key) {
        try {
            String v = r.extractMetadata(key);
            return v == null ? 0 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 지금 무엇으로 재생 중인가. 영상은 ExoPlayer 하나뿐이고 사진은 PhotoEngine 이다. */
    private VideoEngine.Kind currentKind() {
        return engine != null ? engine.kind() : VideoEngine.Kind.EXO;
    }

    @Override
    public void onSurfaceReady(Surface surface, SurfaceTexture surfaceTexture) {
        videoSurface        = surface;
        videoSurfaceTexture = surfaceTexture;
        surfaceReady = true;
        maybeStartPlayback();
    }

    private void startPlayback() {
        if (videoSurface == null || pendingUri == null || engine != null) return;

        // 디버그: --ei conv N 이면 수렴 보정을 N px 로 고정한다 (슬라이더와 같은 눈금).
        int convPx = getIntent().getIntExtra("conv", Integer.MIN_VALUE);
        if (convPx != Integer.MIN_VALUE) setConvergence(convPx, false);

        // 사진은 디코딩할 것도, 고를 엔진도 없다. 한 장을 정지 프레임으로 흘려보내면
        // 그 아래 3D 경로는 영상과 완전히 같다 (PhotoEngine 주석 참고).
        if (isPhoto) {
            engine = new com.nauty.p3d.engine.PhotoEngine();
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            ui.removeCallbacks(ticker);
            ui.post(ticker);
            refreshLabels();
            if (getIntent().getBooleanExtra("autoconv", false)) {
                ui.postDelayed(new Runnable() {
                    @Override public void run() { autoConverge(); }
                }, 800);
            }
            return;
        }

        // 컨테이너 헤더에서 소스 크기를 미리 읽어 둔다.
        //
        // 크기 통지가 아예 오지 않는 소스가 있는데, 그러면 크기를 모른 채 기본값
        // 16:9 로 배치돼 화면이 눌린다. 미리 알아낸 값으로 먼저 맞춰 둔다.
        int[] size = probeVideoSize(pendingUri);
        if (size != null) {
            Log.i(TAG, "소스 " + size[0] + "x" + size[1]);
            onVideoSize(size[0], size[1]);
        }

        engine = new ExoEngine();
        try {
            engine.open(this, pendingUri, videoSurface, videoSurfaceTexture, this);
            engine.play();
        } catch (Throwable t) {
            Log.e(TAG, "재생 시작 실패", t);
            Toast.makeText(this, "재생 오류: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        // 이어보기 예약. 실제 이동은 길이가 확정된 뒤 tick() 에서 한다.
        pendingResumeMs = loadResumeMs();

        ui.removeCallbacks(ticker);
        ui.post(ticker);
        refreshLabels();

        // 디버그: --ei depth N 이면 2D→3D 시차 강도를 N% 로 시작한다 (슬라이더와 같은 단위).
        // 시어 램프를 측정하려면 값을 손으로 맞추지 않고 고정할 수 있어야 한다.
        int depthPct = getIntent().getIntExtra("depth", -1);
        if (depthPct >= 0) glView.setDepth(depthPct / 100f);

        // 디버그: --es output THREE_D|TWO_D|SBS_DEBUG.
        // SBS_DEBUG 는 인터레이스 전 FBO 를 좌우로 그대로 보여준다. 두 눈이 분리돼 나오므로
        // 눈별 시어량을 따로 잴 수 있다 — 인터레이스된 화면으로는 크로스토크 때문에 못 잰다.
        String outMode = getIntent().getStringExtra("output");
        if (outMode != null) {
            try {
                glView.setOutput(Stereo3DView.Output.valueOf(outMode.toUpperCase(Locale.US)));
            } catch (IllegalArgumentException ignored) { }
        }

        // 디버그: --ei freezems N 이면 그 지점으로 이동해 정지시킨다.
        // 마스크 반응을 측정하려면 매 캡처가 같은 프레임이어야 하기 때문.
        final int freezeMs = getIntent().getIntExtra("freezems", 0);
        if (freezeMs > 0) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (engine == null) return;
                    engine.seekTo(freezeMs);
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (engine != null) engine.pause();
                            android.util.Log.i("P3D", "디버그 정지: " + freezeMs + "ms");
                        }
                    }, 1500);
                }
            }, 2500);
        }
    }

    // --------------------------------------------------- VideoEngine.Listener

    @Override
    public void onVideoSize(final int width, final int height) {
        ui.post(new Runnable() {
            @Override public void run() {
                glView.setVideoSize(width, height);
                SourceFormat byAspect = SourceFormat.fromAspect(width, height);
                if (!manualChoice && byAspect != null) {
                    if (!detected) {
                        applySourceFormat(byAspect);
                    } else {
                        // 픽셀 판별은 배치(SBS/TB/2D)만 정하게 하고, half 냐 full 이냐는
                        // 디코더가 실제로 알려준 해상도가 정하게 한다. 판별용 썸네일은
                        // 축소돼 올 수 있어서 (3840x1080 이 1920x1080 으로) full 을
                        // half 로 잘못 잡는다. 눌린 화면으로 보이는 원인이었다.
                        SourceFormat cur = glView.getSourceFormat();
                        if (byAspect == SourceFormat.SBS_FULL && cur == SourceFormat.SBS_HALF) {
                            applySourceFormat(SourceFormat.SBS_FULL);
                        } else if (byAspect == SourceFormat.TB_FULL && cur == SourceFormat.TB_HALF) {
                            applySourceFormat(SourceFormat.TB_FULL);
                        }
                    }
                }
                refreshLabels();
            }
        });
    }

    @Override
    public void onAudioUnsupported() {
        ui.post(new Runnable() {
            @Override public void run() {
                // 다른 엔진으로 바꿔도 안 된다. 이 기기는 DTS 를 디코딩은 해도
                // 오디오 출력단에서 막혀서 결국 무음이다. 헛된 안내를 하지 않는다.
                Toast.makeText(PlayerActivity.this,
                        "재생할 수 없는 오디오 코덱입니다.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onError(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(PlayerActivity.this, "재생 오류: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 내장 자막 트랙을 골랐을 때만 온다 (ExoEngine.open() 에서 텍스트 트랙을
     * 기본으로 꺼 두었으므로). 외부 자막과 같은 비트맵 경로(SubtitleBitmap)를
     * 그대로 타야 3D 위빙 후에도 두 눈이 같은 글자를 본다.
     */
    @Override
    public void onEmbeddedCue(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (selectedEmbeddedText == null) return;   // 그 사이 꺼졌으면 무시
                if (text == null ? lastCueText != null : !text.equals(lastCueText)) {
                    lastCueText = text;
                    int subW = glView.eyeWidthPx(), subH = glView.eyeHeightPx();
                    Bitmap bmp = (text == null || subW <= 0 || subH <= 0) ? null
                            : SubtitleBitmap.render(text, subW, subH, subtitleScale);
                    glView.setSubtitleBitmap(bmp);
                }
            }
        });
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (settingsPanel != null && settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.setVisibility(View.GONE);
            return;
        }
        backToList();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePositionNow();
        if (engine != null) engine.pause();
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        glView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        savePositionNow();
        if (engine != null) { engine.release(); engine = null; }
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
