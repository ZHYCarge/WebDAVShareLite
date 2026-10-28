package com.zhycarge.webdavsharelite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** API 16+, no third-party libraries, no background service. */
public final class MainActivity extends Activity {
    private static final String TAG = "WebDavLite";
    private static final String PREFS = "webdav_settings";
    private static final String K_URL = "url", K_USER = "user", K_PASSWORD = "password", K_UNIQUE = "unique";
    private static final int MAX_LOG_CHARS = 12000;

    private EditText urlField, userField, passwordField;
    private CheckBox uniqueField;
    private Button saveButton, browseButton;
    private TextView selection, logText;
    private ProgressBar progress;
    private final ArrayList<Uri> pendingFiles = new ArrayList<Uri>();
    private final StringBuilder diagnostics = new StringBuilder();
    private volatile boolean busy = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        urlField = (EditText) findViewById(R.id.url);
        userField = (EditText) findViewById(R.id.user);
        passwordField = (EditText) findViewById(R.id.password);
        uniqueField = (CheckBox) findViewById(R.id.unique_name);
        saveButton = (Button) findViewById(R.id.save_button);
        browseButton = (Button) findViewById(R.id.upload_button);
        selection = (TextView) findViewById(R.id.selection);
        logText = (TextView) findViewById(R.id.log_text);
        progress = (ProgressBar) findViewById(R.id.progress);
        loadConfig();
        addLog("应用启动，Android API " + android.os.Build.VERSION.SDK_INT);

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busy) return;
                if (saveConfig() && !pendingFiles.isEmpty()) loadDirectory("");
            }
        });
        browseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (pendingFiles.isEmpty()) {
                    addLog("请先从相册或文件管理器分享照片/文件。\n");
                    return;
                }
                loadDirectory("");
            }
        });
        findViewById(R.id.copy_log_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("WebDAV 诊断", diagnostics.toString()));
                    Toast.makeText(MainActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        receiveShare(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (busy) {
            addLog("有任务在进行中，请任务完成后重新分享文件。 ");
            return;
        }
        setIntent(intent);
        receiveShare(intent);
    }

    private void receiveShare(Intent intent) {
        pendingFiles.clear();
        if (intent == null || (!Intent.ACTION_SEND.equals(intent.getAction()) &&
                !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            updateSelection();
            return;
        }
        LinkedHashSet<Uri> uris = new LinkedHashSet<Uri>();
        try {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                Object one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one instanceof Uri) uris.add((Uri) one);
            } else {
                ArrayList<Parcelable> extras = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (extras != null) for (Parcelable item : extras) {
                    if (item instanceof Uri) uris.add((Uri) item);
                }
            }
            ClipData clip = intent.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
            if (uris.isEmpty() && intent.getData() != null) uris.add(intent.getData());
        } catch (Exception e) {
            addLog("解析系统分享失败：" + brief(e));
            Log.e(TAG, "Share parse error", e);
        }
        for (Uri uri : uris) if (uri != null &&
                ("content".equalsIgnoreCase(uri.getScheme()) ||
                 "file".equalsIgnoreCase(uri.getScheme()))) pendingFiles.add(uri);

        updateSelection();
        if (pendingFiles.isEmpty()) {
            addLog("未收到文件 URI，请使用相册或文件管理器的“分享”功能。");
        } else {
            addLog("收到 " + pendingFiles.size() + " 个文件，URI 授权=" +
                    ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0));
            if (hasSavedConfig()) loadDirectory("");
            else addLog("首次使用请填写服务器地址、用户名、密码，点击“保存配置”。");
        }
    }

    private void updateSelection() {
        if (pendingFiles.isEmpty()) {
            selection.setText("未选择文件。打开相册 → 选照片 → 分享 → WebDAV 快传。");
            saveButton.setText("保存配置");
        } else {
            selection.setText("收到 " + pendingFiles.size() + " 个文件。请选择云端目录，确认后开始上传。");
            saveButton.setText("保存配置并选择目录");
        }
    }

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        urlField.setText(p.getString(K_URL, ""));
        userField.setText(p.getString(K_USER, ""));
        passwordField.setText(p.getString(K_PASSWORD, ""));
        uniqueField.setChecked(p.getBoolean(K_UNIQUE, true));
    }

    private boolean hasSavedConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return !p.getString(K_URL, "").trim().isEmpty() &&
               !p.getString(K_USER, "").trim().isEmpty();
    }

    private boolean saveConfig() {
        WebDavClient.Settings s = settingsFor("");
        try { s.validate(); }
        catch (IOException e) { addLog("配置错误：" + e.getMessage()); return false; }
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(K_URL, s.baseUrl).putString(K_USER, s.username)
              .putString(K_PASSWORD, s.password).putBoolean(K_UNIQUE, s.addTimestamp);
        if (!editor.commit()) {
            addLog("配置保存失败，手机存储可能不可写。");
            return false;
        }
        addLog("已保存连接信息（上传目录不固定，每次分享时选择）。");
        return true;
    }

    private WebDavClient.Settings settingsFor(String folder) {
        return new WebDavClient.Settings(urlField.getText().toString(),
                userField.getText().toString(), passwordField.getText().toString(),
                folder, uniqueField.isChecked());
    }

    /** PROPFIND runs on a worker thread; every tap starts at root or selected folder. */
    private void loadDirectory(final String path) {
        if (busy) return;
        final WebDavClient.Settings settings = settingsFor(path);
        try { settings.validate(); }
        catch (IOException e) { addLog("连接配置错误：" + e.getMessage()); return; }
        setBusy(true);
        addLog("正在读取云端文件夹：/" + path);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<String> names = WebDavBrowser.listDirectories(settings);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            setBusy(false);
                            if (!isFinishing()) showFolderPicker(path, names);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "PROPFIND failed", e);
                    postLog("读取文件夹失败：" + brief(e));
                    runOnUiThread(new Runnable() {
                        @Override public void run() { setBusy(false); }
                    });
                }
            }
        }, "WebDAV-PROPFIND").start();
    }

    private void showFolderPicker(final String path, final List<String> names) {
        final boolean hasParent = path.length() > 0;
        final int indexFolders = hasParent ? 3 : 2;
        final String[] options = new String[names.size() + indexFolders];
        options[0] = "✓ 上传到当前文件夹";
        options[1] = "＋ 新建文件夹";
        if (hasParent) options[2] = "↑ 返回上一级";
        for (int i = 0; i < names.size(); i++) options[indexFolders + i] = "📁 " + names.get(i);

        new AlertDialog.Builder(this)
                .setTitle("云端目录：/" + path)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startUpload(path);
                    } else if (which == 1) {
                        showNewFolderDialog(path);
                    } else if (hasParent && which == 2) {
                        int slash = path.lastIndexOf('/');
                        loadDirectory(slash < 0 ? "" : path.substring(0, slash));
                    } else {
                        String child = names.get(which - indexFolders);
                        loadDirectory(path.length() == 0 ? child : path + "/" + child);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNewFolderDialog(final String parent) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("新文件夹名称");
        new AlertDialog.Builder(this)
                .setTitle("在 /" + parent + " 新建文件夹")
                .setView(input)
                .setNegativeButton("取消", (d, w) -> loadDirectory(parent))
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!WebDavBrowser.validFolderName(name)) {
                        addLog("文件夹名不可为空，也不能包含 /、\\ 或为 .、..");
                        loadDirectory(parent);
                        return;
                    }
                    createDirectory(parent, name);
                }).show();
    }

    private void createDirectory(final String parent, final String name) {
        if (busy) return;
        final WebDavClient.Settings settings = settingsFor(parent);
        setBusy(true);
        addLog("正在创建云端文件夹：/" + parent + (parent.isEmpty() ? "" : "/") + name);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean created = false;
                try {
                    WebDavBrowser.makeDirectory(settings, name);
                    created = true;
                    postLog("新建文件夹成功：" + name);
                } catch (Exception e) {
                    Log.e(TAG, "MKCOL failed", e);
                    postLog("创建文件夹失败：" + brief(e));
                }
                final boolean done = created;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(false);
                        if (!isFinishing()) {
                            String next = (done ? (parent.isEmpty() ? name : parent + "/" + name) : parent);
                            loadDirectory(next);
                        }
                    }
                });
            }
        }, "WebDAV-MKCOL").start();
    }

    private void startUpload(String folder) {
        if (busy) return;
        if (pendingFiles.isEmpty()) {
            addLog("没有待上传文件，请重新从相册分享。");
            return;
        }
        final WebDavClient.Settings settings = settingsFor(folder);
        try { settings.validate(); }
        catch (IOException e) { addLog("上传配置错误：" + e.getMessage()); return; }
        final List<Uri> files = new ArrayList<Uri>(pendingFiles);
        setBusy(true);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        addLog("开始上传：" + files.size() + " 个文件 → /" + folder);
        new Thread(new Runnable() {
            @Override public void run() {
                int success = 0, failed = 0;
                for (int i = 0; i < files.size(); i++) {
                    try {
                        WebDavClient.SourceFile source = WebDavClient.inspect(MainActivity.this, files.get(i), i + 1);
                        String remoteName = settings.addTimestamp ?
                                WebDavClient.timestampedName(source.name, i + 1) : source.name;
                        postLog("[" + (i + 1) + "/" + files.size() + "] 上传 " + source.name);
                        int code = WebDavClient.upload(MainActivity.this, settings, source, remoteName);
                        success++;
                        postLog("上传成功 HTTP " + code + "：" + remoteName);
                    } catch (Exception e) {
                        failed++;
                        postLog("第 " + (i + 1) + " 个文件失败：" + brief(e));
                        Log.e(TAG, "Upload failed", e);
                    }
                    final int pct = (int) (((long) (i + 1) * 100L) / files.size());
                    runOnUiThread(new Runnable() {
                        @Override public void run() { progress.setProgress(pct); }
                    });
                }
                final int ok = success, error = failed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(false);
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        addLog("上传结束：成功 " + ok + "，失败 " + error + "。点击“复制诊断日志”可反馈错误。");
                        Toast.makeText(MainActivity.this, error == 0 ? "全部上传成功" :
                                "上传结束，失败 " + error + " 个", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "WebDAV-PUT").start();
    }

    private void setBusy(boolean value) {
        busy = value;
        saveButton.setEnabled(!value);
        browseButton.setEnabled(!value);
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private void postLog(final String msg) {
        runOnUiThread(new Runnable() { @Override public void run() { addLog(msg); } });
    }

    private void addLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        diagnostics.append('[').append(time).append("] ").append(msg).append('\n');
        if (diagnostics.length() > MAX_LOG_CHARS)
            diagnostics.delete(0, diagnostics.length() - MAX_LOG_CHARS);
        logText.setText(diagnostics.toString());
    }
}
