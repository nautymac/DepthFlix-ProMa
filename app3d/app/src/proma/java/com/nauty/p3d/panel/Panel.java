package com.nauty.p3d.panel;

import android.app.Activity;
import android.view.View;

import com.nauty.p3d.gl.Stereo3DView;

/**
 * ProMa P10 용 — 할 일이 없다.
 *
 * 이 패널은 렌티큘러라 마스크가 고정이고, 우리가 만든 인터레이스를 그대로 화면에 그리면
 * 된다. 즉 {@link Stereo3DView} 자체가 출력이다.
 */
public final class Panel {

    public static PanelBackend create() { return new Passthrough(); }

    private Panel() {}

    private static final class Passthrough implements PanelBackend {
        @Override public View outputView(Activity a)                 { return null; }
        @Override public void attach(Activity a, Stereo3DView gl)     { }
        @Override public boolean useHolography()                      { return true; }
        @Override public void onResume()                              { }
        @Override public void onPause()                               { }
        @Override public void onDestroy()                             { }
    }
}
