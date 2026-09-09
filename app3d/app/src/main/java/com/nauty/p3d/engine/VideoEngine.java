package com.nauty.p3d.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;

import java.util.List;

/**
 * 재생 엔진 추상화.
 *
 * 3D 렌더 파이프라인은 "디코딩된 프레임이 SurfaceTexture 로 들어온다"는 것만 알면 되므로,
 * 그 아래는 갈아끼울 수 있다.
 *   ExoPlayer : 기기 MediaCodec 사용. 가볍고 HLS/DASH 에 강함.
 *   libVLC    : 자체 FFmpeg 내장. MKV / DTS / AC3 등 MediaCodec 이 못 하는 것을 커버.
 */
public interface VideoEngine {

    interface Listener {
        /** 실제 영상 해상도가 확정됐을 때. 3D 크롭/종횡비 계산에 쓴다. */
        void onVideoSize(int width, int height);
        void onError(String message);
        /** 오디오 트랙은 있는데 이 기기에 디코더가 없어 무음이 되는 경우. */
        void onAudioUnsupported();
        /**
         * 선택된 내장 자막 트랙의 지금 자막. null 이면 지울 자막이 없다는 뜻이다.
         * 텍스트 자막만 온다 — 이미지 자막(PGS/VOBSUB)은 선택 자체를 막아 여기로 오지 않는다.
         * ExoEngine 만 실제로 이 콜백을 쓴다 (아래 트랙 선택 메서드들 참고).
         */
        void onEmbeddedCue(String text);
    }

    /** 엔진 종류. 설정 저장과 UI 표시에 쓴다. */
    enum Kind {
        EXO("ExoPlayer"),
        VLC("libVLC"),
        /** 사진 한 장을 정지 프레임으로 흘려보낸다 (PhotoEngine). 엔진 순환에는 넣지 않는다. */
        PHOTO("사진");

        public final String label;
        Kind(String l) { label = l; }
    }

    /**
     * @param surface        ExoPlayer 처럼 Surface 를 받는 엔진용
     * @param surfaceTexture libVLC 처럼 SurfaceTexture 를 직접 받는 엔진용
     */
    void open(Context ctx, Uri uri, Surface surface, SurfaceTexture surfaceTexture, Listener l);

    void play();
    void pause();
    boolean isPlaying();

    void seekTo(long ms);
    long getPosition();
    long getDuration();

    void release();

    Kind kind();

    // --------------------------------------------------------- 트랙 선택
    //
    // mkv/mp4 컨테이너 안에 여러 오디오·자막 트랙이 들어 있을 수 있다. ExoPlayer 로만
    // 지원한다 — libVLC 는 --no-spu 로 자막 디코딩 자체를 꺼 뒀고(VlcEngine 주석 참고),
    // 오디오 트랙도 별도 선택 UI 없이 기기가 고르는 것을 그대로 쓴다. PhotoEngine 과
    // VlcEngine 은 전부 빈 목록/빈 구현으로 둔다.

    /** 컨테이너 안의 오디오 트랙. 아직 안 열렸거나 없으면 빈 목록. */
    List<TrackInfo> audioTracks();

    /** 컨테이너 안의 자막 트랙. 아직 안 열렸거나 없으면 빈 목록. */
    List<TrackInfo> textTracks();

    /** null 이면 기기가 고르는 기본값으로 되돌린다. */
    void selectAudioTrack(TrackInfo track);

    /**
     * null 이면 내장 자막을 끈다.
     * 외부 .srt/.smi 를 쓸 때도 반드시 null 로 꺼야 한다 — 안 그러면 내장 자막이
     * {@link Listener#onEmbeddedCue} 로 계속 들어와 외부 자막과 겹쳐 보인다.
     */
    void selectTextTrack(TrackInfo track);
}
