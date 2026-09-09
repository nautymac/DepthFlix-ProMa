package com.nauty.p3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.nauty.p3d.net.Dlna;
import com.nauty.p3d.net.SmbBrowser;
import com.nauty.p3d.net.SmbCredentials;
import com.nauty.p3d.net.SmbDiscovery;
import com.nauty.p3d.net.SmbUri;
import com.nauty.p3d.net.Ssdp;

import java.util.ArrayList;
import java.util.List;

/**
 * 목록 화면. 폴더 -> 파일 두 단계다.
 *
 * 한 줄로 늘어놓던 것을 폴더로 나눈 이유는 단순하다. 파일이 수십·수백 개가 되면
 * 평평한 목록으로는 찾을 수가 없다. 사진은 특히 그렇다.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    private final List<String> titles = new ArrayList<>();
    private final List<Uri>    uris   = new ArrayList<>();
    private List<MediaLibrary.Folder> folders = new ArrayList<>();

    private ListView list;
    private TextView empty, crumb;
    private Button   btnMode;

    /** 영상 목록이냐 사진 목록이냐. 목록만 갈릴 뿐 여는 화면은 같다. */
    private MediaLibrary.Kind kind = MediaLibrary.Kind.VIDEO;

    /** null 이면 폴더 목록, 아니면 그 폴더의 파일 목록. */
    private String openFolder = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnMode = addButton(row, "사진 보기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                kind = (kind == MediaLibrary.Kind.VIDEO)
                        ? MediaLibrary.Kind.IMAGE : MediaLibrary.Kind.VIDEO;
                openFolder = null;
                reload();
            }
        });
        addButton(row, "URL/스트리밍 열기", new View.OnClickListener() {
            @Override public void onClick(View v) { chooseNetworkSource(); }
        });
        addButton(row, "3D 컨트롤 센터", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Fv3dControlActivity.class));
            }
        });
        root.addView(row);

        crumb = new TextView(this);
        crumb.setTextColor(Color.parseColor("#00D8FF"));
        crumb.setTextSize(13f);
        crumb.setPadding(0, dp(8), 0, dp(4));
        root.addView(crumb);

        empty = new TextView(this);
        empty.setTextColor(Color.GRAY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 48, 0, 48);
        empty.setText("찾는 중…");
        root.addView(empty);

        list = new ListView(this);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (openFolder == null) {
                    if (pos >= 0 && pos < folders.size()) {
                        openFolder = folders.get(pos).path;
                        reload();
                    }
                } else if (pos >= 0 && pos < uris.size()) {
                    open(uris.get(pos), titles.get(pos));
                }
            }
        });
        // 자주 여는 폴더는 위로 고정한다. 길게 눌러 켜고 끈다.
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (openFolder != null || pos < 0 || pos >= folders.size()) return false;
                String path = folders.get(pos).path;
                boolean on = MediaLibrary.toggleFavorite(MainActivity.this, path);
                Toast.makeText(MainActivity.this,
                        MediaLibrary.shortPath(MainActivity.this, path)
                                + (on ? " 고정" : " 고정 해제"),
                        Toast.LENGTH_SHORT).show();
                reload();
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        } else {
            reload();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) reload();
    }

    /** 폴더 목록에서 뒤로 = 앱 종료, 파일 목록에서 뒤로 = 폴더 목록. */
    @Override
    public void onBackPressed() {
        if (openFolder != null) {
            openFolder = null;
            reload();
            return;
        }
        super.onBackPressed();
    }

    /**
     * 돌아올 때마다 다시 읽는다. 다른 앱으로 영상을 받아온 직후에도 목록에 나와야 한다.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        reload();
        scanUnindexed();
    }

    private void reload() {
        boolean photo = (kind == MediaLibrary.Kind.IMAGE);
        btnMode.setText(photo ? "영상 보기" : "사진 보기");

        if (openFolder == null) loadFolders(photo);
        else                    loadFiles(photo);
    }

    private void loadFolders(boolean photo) {
        folders = MediaLibrary.folders(this, kind);
        titles.clear();
        uris.clear();

        // 폴더가 하나뿐이면 한 단계를 아낄 이유가 있다. 눌러 들어갈 곳이 하나뿐이다.
        if (folders.size() == 1) {
            openFolder = folders.get(0).path;
            loadFiles(photo);
            return;
        }

        crumb.setText((photo ? "사진" : "영상") + " 폴더  ·  길게 눌러 위로 고정");
        List<String> display = new ArrayList<>();
        for (MediaLibrary.Folder f : folders) display.add(f.display(this));
        show(display, photo ? "기기에서 사진을 찾지 못했습니다."
                            : "기기에서 영상을 찾지 못했습니다.\n위의 URL/스트리밍 열기 를 쓰세요.");
    }

    private void loadFiles(boolean photo) {
        titles.clear();
        uris.clear();
        List<MediaLibrary.Item> items = MediaLibrary.list(this, kind, openFolder);
        List<String> display = new ArrayList<>();
        for (MediaLibrary.Item it : items) {
            titles.add(it.name);
            uris.add(it.uri);
            // 영상은 이름으로 3D 를 추측해 같이 보여준다. 사진은 그럴 필요가 없다 —
            // 원본 해상도를 정확히 읽을 수 있어서 뷰어가 열면서 바로 판별한다.
            SourceFormat f = photo ? null : SourceFormat.fromName(it.name);
            display.add(f == null ? it.name : it.name + "   [" + f.label + "]");
        }
        crumb.setText("◀ " + MediaLibrary.shortPath(this, openFolder)
                + "   (" + items.size() + ")");
        show(display, "이 폴더가 비었습니다.");
    }

    /**
     * MediaStore 가 모르는 영상·사진 파일을 찾아 미디어 스캐너에 넘긴다.
     *
     * 목록은 MediaStore 를 조회해서 만드는데, 파일을 만든 앱이 스캔을 요청하지 않으면
     * 그 파일은 색인되지 않아 목록에 나오지 않는다. 안드로이드 8 의 스캐너는 부팅 때와
     * MEDIA_SCANNER_SCAN_FILE 브로드캐스트에만 도는데, 다운로더가 둘 다 보내지 않으면
     * 파일이 있어도 보이지 않는다.
     *
     * 색인만 시켜주면 그 뒤로는 평소 경로(content://)로 열리므로, 자막 탐색이나
     * 이어보기 키 같은 나머지 동작은 손댈 필요가 없다.
     */
    private void scanUnindexed() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> missing = new ArrayList<>();
                try {
                    java.util.Set<String> known = new java.util.HashSet<>();
                    collectKnown(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Video.Media.DATA, known);
                    collectKnown(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            MediaStore.Images.Media.DATA, known);
                    collect(android.os.Environment.getExternalStorageDirectory(),
                            0, known, missing);
                } catch (Throwable t) {
                    return;   // 색인은 보조 기능이다. 실패해도 조용히 넘어간다
                }
                if (missing.isEmpty()) return;

                final String[] paths = missing.toArray(new String[0]);
                final int[] done = {0};
                android.media.MediaScannerConnection.scanFile(
                        MainActivity.this, paths, null,
                        new android.media.MediaScannerConnection.OnScanCompletedListener() {
                            @Override public void onScanCompleted(String path, Uri uri) {
                                synchronized (done) {
                                    if (++done[0] < paths.length) return;
                                }
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        reload();
                                        Toast.makeText(MainActivity.this,
                                                "새 파일 " + paths.length + "개를 목록에 넣었습니다",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
            }
        }).start();
    }

    private void collectKnown(Uri content, String dataCol, java.util.Set<String> out) {
        Cursor c = getContentResolver().query(content, new String[]{dataCol}, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String d = c.getString(0);
                if (d != null) out.add(d);
            }
        } finally { c.close(); }
    }

    /** 영상·사진 확장자를 가진 파일 중 MediaStore 에 없는 것을 모은다. */
    private void collect(java.io.File dir, int depth,
                         java.util.Set<String> known, List<String> out) {
        if (dir == null || depth > 6 || out.size() > 4000) return;
        // Android/ 밑은 앱 전용 데이터라 볼 이유가 없고, .nomedia 는 사용자가 숨긴 것이다.
        if (depth > 0 && ("Android".equals(dir.getName())
                || new java.io.File(dir, ".nomedia").exists())) return;

        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) {
                collect(f, depth + 1, known, out);
            } else if (!known.contains(f.getAbsolutePath())
                    && (MediaLibrary.isVideoName(f.getName())
                        || MediaLibrary.isPhotoName(f.getName()))) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void show(List<String> display, String emptyText) {
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, display));
        empty.setVisibility(display.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(emptyText);
    }

    /**
     * "URL/스트리밍 열기" 를 DLNA·SMB 까지 넓힌다.
     * URL 을 직접 넣는 기존 방식과, IP/포트만 넣으면 되는 DLNA·SMB 탐색을 고르게 한다.
     */
    private void chooseNetworkSource() {
        final String[] items = { "URL", "DLNA", "SMB" };
        new AlertDialog.Builder(this)
                .setTitle("URL/스트리밍 열기")
                .setItems(items, (d, which) -> {
                    if (which == 0) askUrl();
                    else if (which == 1) askDlnaHost();
                    else askSmbHost();
                })
                .show();
    }

    private void askUrl() {
        final EditText in = new EditText(this);
        in.setHint("http(s)://… .mp4 / .m3u8 / .mpd / rtsp://…");
        new AlertDialog.Builder(this)
                .setTitle("스트리밍 주소 열기")
                .setView(in)
                .setPositiveButton("재생", (d, w) -> {
                    String u = in.getText().toString().trim();
                    if (!u.isEmpty()) open(Uri.parse(u), u);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ------------------------------------------------------------ DLNA
    //
    // SSDP(M-SEARCH) 로 같은 네트워크의 미디어 서버를 먼저 찾아 보여준다 — IP 를
    // 몰라도 이름만 보고 고르면 된다. 광고하지 않는 서버나 다른 서브넷에 있는
    // 서버를 위해 "직접 입력" 도 남겨 둔다. description.xml 을 읽어 ContentDirectory
    // 의 controlURL 을 찾고, Browse 로 목록을 받아 재생 URL(평범한 http 주소)을
    // 그대로 연다 — http 는 기본적으로 ExoPlayer 가 열므로 재생 엔진 선택 로직도
    // 손댈 것이 없다.

    private void askDlnaHost() {
        Toast.makeText(this, "DLNA 서버 찾는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Ssdp.Device> devices = Ssdp.discover(MainActivity.this, 3000);
                for (Ssdp.Device d : devices) {
                    try { d.friendlyName = Dlna.fetchFriendlyName(d.location); }
                    catch (Exception ignored) { }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { showDlnaHostPicker(devices); }
                });
            }
        }, "ssdp-discover").start();
    }

    private void showDlnaHostPicker(final List<Ssdp.Device> devices) {
        final String[] items = new String[devices.size() + 1];
        for (int i = 0; i < devices.size(); i++) {
            Ssdp.Device d = devices.get(i);
            items[i] = d.friendlyName != null ? d.friendlyName : d.location;
        }
        items[devices.size()] = "주소 직접 입력…";

        new AlertDialog.Builder(this)
                .setTitle(devices.isEmpty() ? "DLNA — 찾은 서버 없음" : "DLNA")
                .setItems(items, (d, which) -> {
                    if (which == devices.size()) {
                        askDlnaHostManual();
                    } else {
                        Ssdp.Device dev = devices.get(which);
                        startDlnaBrowseFromLocation(dev.location, items[which]);
                    }
                })
                .show();
    }

    private void askDlnaHostManual() {
        final EditText in = new EditText(this);
        in.setHint("IP 주소[:포트]");
        new AlertDialog.Builder(this)
                .setTitle("DLNA")
                .setMessage("흔한 포트: Plex 32469 · Serviio 8895 · Jellyfin 8096 · "
                        + "Windows Media Player 2869 · MiniDLNA 8200")
                .setView(in)
                .setPositiveButton("다음", (d, w) -> {
                    String hp = in.getText().toString().trim();
                    if (hp.isEmpty()) return;
                    String host = hp;
                    int port = 8200;                    // MiniDLNA 기본값
                    int c = hp.lastIndexOf(':');
                    if (c > 0) {
                        host = hp.substring(0, c);
                        try { port = Integer.parseInt(hp.substring(c + 1)); }
                        catch (NumberFormatException ignored) { }
                    }
                    startDlnaBrowse(host, port);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startDlnaBrowseFromLocation(final String descriptionUrl, final String title) {
        Toast.makeText(this, "DLNA 에 연결하는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String controlUrl = Dlna.findControlUrl(descriptionUrl);
                    dlnaBrowseTo(controlUrl, "0", title);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { dlnaFailed(e); }
                    });
                }
            }
        }, "dlna-connect").start();
    }

    /** "직접 입력" 경로. description.xml 이 "/description.xml" 에 있다고 가정한다. */
    private void startDlnaBrowse(final String host, final int port) {
        Toast.makeText(this, "DLNA 에 연결하는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String controlUrl = Dlna.findControlUrl(host, port);
                    dlnaBrowseTo(controlUrl, "0", "DLNA");
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { dlnaFailed(e); }
                    });
                }
            }
        }, "dlna-connect").start();
    }

    /** id 아래 목록을 받아 대화상자로 보여준다. 폴더를 고르면 재귀적으로 더 들어간다. */
    private void dlnaBrowseTo(final String controlUrl, final String objectId, final String title) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Dlna.Item> items = Dlna.browse(controlUrl, objectId);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showDlnaItems(controlUrl, title, items); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { dlnaFailed(e); }
                    });
                }
            }
        }, "dlna-browse").start();
    }

    private void dlnaFailed(Exception e) {
        Toast.makeText(this, "DLNA 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void showDlnaItems(final String controlUrl, String title, final List<Dlna.Item> items) {
        if (items.isEmpty()) {
            Toast.makeText(this, "이 폴더가 비었습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Dlna.Item it = items.get(i);
            names[i] = it.container ? "📁 " + it.title : it.title;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(names, (d, which) -> {
                    Dlna.Item it = items.get(which);
                    if (it.container) dlnaBrowseTo(controlUrl, it.id, it.title);
                    else open(Uri.parse(it.url), it.title);
                })
                .show();
    }

    // ------------------------------------------------------------ SMB
    //
    // mDNS(_smb._tcp.) 로 서버를 먼저 찾는다. Synology·QNAP·macOS 공유는 기본으로
    // 이걸 광고한다 (평범한 Windows 공유 PC 는 안 할 수 있어 "직접 입력" 도 둔다).
    // 서버를 고르면 계정만 물어보고, 공유 이름은 로그인한 계정이 접근 가능한
    // 목록을 SRVSVC 로 직접 받아 고르게 한다 — 사용자가 공유 이름을 몰라도 된다.
    // 재생 자체는 libVLC 의 libdsm/smb2 가 맡는다 — smb:// URI 를 그대로 열면
    // PlayerActivity 의 defaultKind() 가 알아서 VLC 를 골라준다 (proma3d 는
    // ExoPlayer 가 smb 를 못 열어 원래부터 그렇게 정해져 있었다).

    private void askSmbHost() {
        Toast.makeText(this, "SMB 서버 찾는 중…", Toast.LENGTH_SHORT).show();
        SmbDiscovery.discover(this, 3000, new SmbDiscovery.Callback() {
            @Override public void onFinished(List<SmbDiscovery.Host> hosts) {
                showSmbHostPicker(hosts);
            }
        });
    }

    private void showSmbHostPicker(final List<SmbDiscovery.Host> hosts) {
        final String[] items = new String[hosts.size() + 1];
        for (int i = 0; i < hosts.size(); i++) {
            SmbDiscovery.Host h = hosts.get(i);
            items[i] = h.name + "  (" + h.address + ")";
        }
        items[hosts.size()] = "주소 직접 입력…";

        new AlertDialog.Builder(this)
                .setTitle(hosts.isEmpty() ? "SMB — 찾은 서버 없음" : "SMB")
                .setItems(items, (d, which) -> {
                    if (which == hosts.size()) {
                        askSmbHostManual();
                    } else {
                        SmbDiscovery.Host h = hosts.get(which);
                        askSmbCredentials(h.address, h.port > 0 ? h.port : 445);
                    }
                })
                .show();
    }

    private void askSmbHostManual() {
        final EditText in = new EditText(this);
        in.setHint("호스트 / IP 주소[:포트]");
        new AlertDialog.Builder(this)
                .setTitle("SMB")
                .setView(in)
                .setPositiveButton("다음", (d, w) -> {
                    String hp = in.getText().toString().trim();
                    if (hp.isEmpty()) return;
                    String host = hp;
                    int port = 445;
                    int c = hp.lastIndexOf(':');
                    if (c > 0) {
                        host = hp.substring(0, c);
                        try { port = Integer.parseInt(hp.substring(c + 1)); }
                        catch (NumberFormatException ignored) { }
                    }
                    askSmbCredentials(host, port);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void askSmbCredentials(final String host, final int port) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, dp(8));

        final EditText user = addField(form, "사용자 이름 (비우면 익명)", false);
        final EditText pass = addField(form, "비밀번호", true);

        // 이 호스트로 전에 로그인한 적이 있으면 미리 채워 둔다 — 매번 다시
        // 입력하지 않아도 되게 (사용자 요청).
        SmbCredentials.Entry saved = SmbCredentials.find(this, host);
        if (saved != null) {
            user.setText(saved.user);
            pass.setText(saved.pass);
        }

        new AlertDialog.Builder(this)
                .setTitle(host)
                .setView(form)
                .setPositiveButton("연결", (d, w) -> {
                    String u = user.getText().toString().trim();
                    String pw = pass.getText().toString();
                    if (!u.isEmpty()) SmbCredentials.save(this, host, u, pw, null);
                    startSmbShareList(host, port, u, pw);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void startSmbShareList(final String host, final int port,
                                    final String user, final String pass) {
        Toast.makeText(this, "SMB 에 연결하는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> shares = SmbBrowser.listShares(host, port, user, pass);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showSmbShareList(host, port, user, pass, shares); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                    "SMB 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "smb-shares").start();
    }

    private void showSmbShareList(final String host, final int port,
                                   final String user, final String pass, final List<String> shares) {
        if (shares.isEmpty()) {
            Toast.makeText(this, "이 계정으로 접근 가능한 공유가 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("공유 선택")
                .setItems(shares.toArray(new String[0]), (d, which) -> {
                    String share = shares.get(which);
                    // 계정은 이미 askSmbCredentials() 에서 저장했다 — 여기선 공유만 고른다.
                    startSmbBrowse(host, port, share, user, pass, "");
                })
                .show();
    }

    private EditText addField(LinearLayout parent, String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (password) {
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(e);
        return e;
    }

    private void startSmbBrowse(final String host, final int port, final String share,
                                 final String user, final String pass, final String path) {
        Toast.makeText(this, "SMB 에 연결하는 중…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<SmbBrowser.Entry> entries =
                            SmbBrowser.list(host, port, share, user, pass, path);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            showSmbItems(host, port, share, user, pass, path, entries);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                    "SMB 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "smb-browse").start();
    }

    private void showSmbItems(final String host, final int port, final String share,
                               final String user, final String pass, final String path,
                               final List<SmbBrowser.Entry> entries) {
        if (entries.isEmpty()) {
            Toast.makeText(this, "이 폴더가 비었습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            SmbBrowser.Entry e = entries.get(i);
            names[i] = e.directory ? "📁 " + e.name : e.name;
        }
        new AlertDialog.Builder(this)
                .setTitle(path.isEmpty() ? share : path)
                .setItems(names, (d, which) -> {
                    SmbBrowser.Entry e = entries.get(which);
                    String childPath = path.isEmpty() ? e.name : path + "\\" + e.name;
                    if (e.directory) {
                        startSmbBrowse(host, port, share, user, pass, childPath);
                    } else {
                        Uri uri = SmbUri.build(host, port, share, childPath, user, pass);
                        open(uri, e.name);
                    }
                })
                .show();
    }

    private void open(Uri uri, String title) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(uri);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        if (kind == MediaLibrary.Kind.IMAGE) {
            i.putExtra(PlayerActivity.EXTRA_PHOTO, true);
            // 이전/다음은 지금 보고 있는 폴더 안에서만 돈다.
            i.putExtra(PlayerActivity.EXTRA_FOLDER, openFolder);
        }
        startActivity(i);
    }
}
