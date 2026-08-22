package com.apkfactory.v2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int PICK_ZIP = 1001;
    private static final int NOTIFY_PERMISSION = 2402;

    private EditText repoName, tokenField;
    private Switch privateSwitch;
    private Button chooseZip, buildButton, installButton, openRepoButton, forgetTokenButton;
    private TextView chosenFile, status, buildLog, tokenStatus;
    private ProgressBar progress;
    private Uri projectZipUri;
    private SharedPreferences statePrefs;
    private SecureTokenStore tokenStore;
    private boolean awaitingInstallPermission;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshUiFromState();
            handler.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        statePrefs = getSharedPreferences(BuildState.PREF, MODE_PRIVATE);
        tokenStore = new SecureTokenStore(this);
        buildUi();
        loadSavedToken();
        restoreProjectSelection();
        refreshUiFromState();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        if (awaitingInstallPermission && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            awaitingInstallPermission = false;
            launchInstallerFromState();
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.rgb(248,249,250));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(12));
        root.setBackgroundColor(Color.rgb(248,249,250));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = text("APK Factory 2.6.2", 32, true); root.addView(title);
        TextView sub = text("Resumable Android ZIP → GitHub Actions → APK", 17, false);
        sub.setTextColor(Color.rgb(85,93,99)); root.addView(sub);
        space(root, 12);

        chooseZip = new Button(this); chooseZip.setText("Choose Android Project ZIP"); chooseZip.setTextSize(17); root.addView(chooseZip, full(58));
        chosenFile = text("No ZIP selected", 15, false); chosenFile.setTextColor(Color.DKGRAY); root.addView(chosenFile);
        space(root, 8);

        root.addView(text("Repository name", 16, false));
        repoName = new EditText(this); repoName.setTextSize(19); repoName.setSingleLine(true); repoName.setHint("Lumi-APK-Factory-Build"); root.addView(repoName, full(52));
        repoName.setText(statePrefs.getString(BuildState.LAST_REPO, "Lumi-APK-Factory-Build"));

        privateSwitch = new Switch(this); privateSwitch.setText("Private GitHub repository"); privateSwitch.setTextSize(16); privateSwitch.setChecked(true); root.addView(privateSwitch, full(52));

        root.addView(text("GitHub personal access token", 16, false));
        tokenField = new EditText(this); tokenField.setSingleLine(true); tokenField.setTextSize(18);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenField.setHint("github_pat_… or ghp_…"); root.addView(tokenField, full(52));
        tokenStatus = text("Token is encrypted with Android Keystore and remembered only on this device.", 13, false);
        tokenStatus.setTextColor(Color.rgb(98,105,110)); root.addView(tokenStatus);
        forgetTokenButton = new Button(this); forgetTokenButton.setText("Forget saved token"); root.addView(forgetTokenButton, full(48));
        space(root, 8);

        root.addView(text("Build log", 16, false));
        buildLog = text("Waiting for a project…", 14, false); buildLog.setTypeface(Typeface.MONOSPACE); buildLog.setTextIsSelectable(true);
        buildLog.setBackgroundColor(Color.WHITE); buildLog.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(buildLog, fullWrap());

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(14), dp(8), dp(14), dp(10));
        dock.setBackgroundColor(Color.WHITE);
        buildButton = new Button(this); buildButton.setText("BUILD APK"); buildButton.setTextSize(20); buildButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dock.addView(buildButton, full(58));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); dock.addView(progress, full(12));
        status = text("Ready", 17, true); dock.addView(status);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        installButton = new Button(this); installButton.setText("Install APK"); installButton.setEnabled(false);
        openRepoButton = new Button(this); openRepoButton.setText("Open Repo"); openRepoButton.setEnabled(false);
        buttons.addView(installButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        spaceH(buttons, 10);
        buttons.addView(openRepoButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        dock.addView(buttons);
        screen.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        setContentView(screen);
        chooseZip.setOnClickListener(v -> pickZip());
        buildButton.setOnClickListener(v -> startBuild());
        installButton.setOnClickListener(v -> requestInstall());
        openRepoButton.setOnClickListener(v -> openRepo());
        forgetTokenButton.setOnClickListener(v -> forgetToken());
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        startActivityForResult(i, PICK_ZIP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || data == null) return;
        projectZipUri = data.getData();
        if (projectZipUri == null) return;
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(projectZipUri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        String name = queryName(projectZipUri);
        chosenFile.setText(name == null ? "Android project ZIP selected" : name);
        statePrefs.edit().putString(BuildState.PROJECT_URI, projectZipUri.toString())
                .putString(BuildState.PROJECT_NAME, name == null ? "Android project ZIP" : name).apply();
        if (repoName.getText().toString().trim().isEmpty() && name != null) {
            repoName.setText(cleanRepoName(name.replaceFirst("(?i)\\.zip$", "")));
        }
        setLocalStatus("Ready to build", 0);
    }

    private void startBuild() {
        if (statePrefs.getBoolean(BuildState.ACTIVE, false)) {
            toast("A build is already active."); return;
        }
        if (projectZipUri == null) restoreProjectSelection();
        if (projectZipUri == null) { toast("Choose an Android project ZIP first."); return; }
        String repo = cleanRepoName(repoName.getText().toString().trim());
        String token = tokenField.getText().toString().trim();
        if (repo.isEmpty()) { toast("Enter a repository name."); return; }
        if (token.length() < 20) { toast("Enter a valid GitHub personal access token."); return; }
        try {
            tokenStore.save(token);
            tokenStatus.setText("Token securely saved on this device.");
        } catch (Exception e) {
            toast("Could not securely save token: " + safeMessage(e)); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFY_PERMISSION);
        }
        statePrefs.edit().putString(BuildState.LAST_REPO, repo).apply();
        BuildKeepAliveService.startNewBuild(this, projectZipUri, repo, privateSwitch.isChecked());
        refreshUiFromState();
    }

    private void requestInstall() {
        String uri = statePrefs.getString(BuildState.OUTPUT_URI, null);
        if (uri == null || uri.isEmpty()) { toast("No downloaded APK is ready yet."); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            awaitingInstallPermission = true;
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }
        launchInstallerFromState();
    }

    private void launchInstallerFromState() {
        String raw = statePrefs.getString(BuildState.OUTPUT_URI, null);
        if (raw == null || raw.isEmpty()) return;
        try {
            Uri uri = Uri.parse(raw);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            statePrefs.edit().putBoolean(BuildState.COMPLETION_PENDING, false).apply();
        } catch (Exception e) {
            toast("Could not open APK installer: " + safeMessage(e));
        }
    }

    private void openRepo() {
        String url = statePrefs.getString(BuildState.REPO_URL, null);
        if (url != null && !url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void refreshUiFromState() {
        if (statePrefs == null || status == null) return;
        boolean active = statePrefs.getBoolean(BuildState.ACTIVE, false);
        status.setText(statePrefs.getString(BuildState.STATUS, active ? "Build running" : "Ready"));
        progress.setProgress(statePrefs.getInt(BuildState.PROGRESS, 0));
        String log = statePrefs.getString(BuildState.LOG, "");
        if (!log.isEmpty() && !log.equals(buildLog.getText().toString())) buildLog.setText(log);
        String repoUrl = statePrefs.getString(BuildState.REPO_URL, null);
        openRepoButton.setEnabled(repoUrl != null && !repoUrl.isEmpty());
        String output = statePrefs.getString(BuildState.OUTPUT_URI, null);
        installButton.setEnabled(output != null && !output.isEmpty() && !active);
        buildButton.setEnabled(!active);
        chooseZip.setEnabled(!active);
        repoName.setEnabled(!active);
        privateSwitch.setEnabled(!active);
    }

    private void restoreProjectSelection() {
        String raw = statePrefs.getString(BuildState.PROJECT_URI, null);
        if (raw != null && !raw.isEmpty()) {
            try { projectZipUri = Uri.parse(raw); } catch (Exception ignored) {}
        }
        String name = statePrefs.getString(BuildState.PROJECT_NAME, null);
        if (name != null && !name.isEmpty()) chosenFile.setText(name);
        String requested = statePrefs.getString(BuildState.REQUESTED_REPO, null);
        if (requested != null && !requested.isEmpty()) repoName.setText(requested);
        privateSwitch.setChecked(statePrefs.getBoolean(BuildState.PRIVATE_REPO, true));
    }

    private void loadSavedToken() {
        try {
            String token = tokenStore.load();
            if (token != null && !token.isEmpty()) {
                tokenField.setText(token);
                tokenStatus.setText("Saved token loaded securely from this device.");
            }
        } catch (Exception e) {
            tokenStatus.setText("Saved token could not be loaded. Enter it once to replace it.");
        }
    }

    private void forgetToken() {
        try { tokenStore.clear(); } catch (Exception ignored) {}
        tokenField.setText("");
        tokenStatus.setText("Saved token removed from this device.");
        toast("Saved token removed");
    }

    private void setLocalStatus(String s, int pct) {
        statePrefs.edit().putString(BuildState.STATUS, s).putInt(BuildState.PROGRESS, pct).apply();
        status.setText(s); progress.setProgress(pct);
    }

    private String queryName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) return c.getString(ix);
            }
        } catch (Exception ignored) {} finally { if (c != null) c.close(); }
        return null;
    }

    private String cleanRepoName(String s) { return s.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", ""); }
    private String safeMessage(Exception e) { String m = e.getMessage(); return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private TextView text(String s, float sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(40,47,52)); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(0, dp(5), 0, dp(5)); return v; }
    private LinearLayout.LayoutParams full(int h) { return new LinearLayout.LayoutParams(-1, dp(h)); }
    private LinearLayout.LayoutParams fullWrap() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(20)); return p; }
    private void space(LinearLayout l, int h) { l.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(h))); }
    private void spaceH(LinearLayout l, int w) { l.addView(new Space(this), new LinearLayout.LayoutParams(dp(w), 1)); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
}
