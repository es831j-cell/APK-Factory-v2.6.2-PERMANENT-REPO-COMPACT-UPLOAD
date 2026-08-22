package com.apkfactory.v2;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BuildKeepAliveService extends Service {
    private static final String API = "https://api.github.com";
    private static final String CHANNEL_ID = "apk_factory_builds_v262";
    private static final int NOTIFICATION_ID = 2621;
    private static final String FACTORY_WORKFLOW_PATH = ".github/workflows/apk-factory-build.yml";
    private static final String PROJECT_ARCHIVE_PATH = "apkfactory-project.zip";
    private static final long BUILD_WAIT_MS = 45L * 60L * 1000L;
    private static final long RUN_DISCOVERY_MS = 4L * 60L * 1000L;
    private static final long ARTIFACT_WAIT_MS = 5L * 60L * 1000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private SharedPreferences prefs;

    public static void startNewBuild(Context context, Uri projectUri, String repoName, boolean privateRepo) {
        SharedPreferences p = context.getSharedPreferences(BuildState.PREF, Context.MODE_PRIVATE);
        String projectName = p.getString(BuildState.PROJECT_NAME, "Android project ZIP");
        p.edit()
                .putBoolean(BuildState.ACTIVE, true)
                .putInt(BuildState.STAGE, BuildState.NEW)
                .putString(BuildState.PROJECT_URI, projectUri.toString())
                .putString(BuildState.PROJECT_NAME, projectName)
                .putString(BuildState.REQUESTED_REPO, repoName)
                .putString(BuildState.JOB_ID, java.util.UUID.randomUUID().toString())
                .putBoolean(BuildState.PRIVATE_REPO, privateRepo)
                .putString(BuildState.STATUS, "Starting build")
                .putInt(BuildState.PROGRESS, 1)
                .putString(BuildState.LOG, "")
                .remove(BuildState.OWNER)
                .remove(BuildState.REPO)
                .remove(BuildState.BRANCH)
                .remove(BuildState.REPO_URL)
                .remove(BuildState.COMMIT_SHA)
                .remove(BuildState.WORKFLOW_ID)
                .remove(BuildState.RUN_ID)
                .remove(BuildState.OUTPUT_URI)
                .remove(BuildState.OUTPUT_NAME)
                .remove(BuildState.FAILURE)
                .putBoolean(BuildState.COMPLETION_PENDING, false)
                .apply();
        Intent i = new Intent(context, BuildKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(BuildState.PREF, MODE_PRIVATE);
        ensureChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String status = prefs.getString(BuildState.STATUS, "Build running");
        int progress = prefs.getInt(BuildState.PROGRESS, 1);
        startForeground(NOTIFICATION_ID, buildNotification(status, progress, true, false));

        if (prefs.getBoolean(BuildState.ACTIVE, false) && workerRunning.compareAndSet(false, true)) {
            executor.submit(() -> {
                try {
                    runBuild();
                } finally {
                    workerRunning.set(false);
                }
            });
        } else if (!prefs.getBoolean(BuildState.ACTIVE, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Deliberately do not stop the job. The foreground service owns it, not the Activity/task.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onTimeout(int startId, int fgsType) {
        executor.shutdownNow();
        fail("Android stopped APK Factory's foreground data-sync window before the job completed. The saved GitHub job state is retained in the log.");
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        // Do not clear ACTIVE here. START_STICKY may recreate this service after process death.
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private void runBuild() {
        try {
            String token = new SecureTokenStore(this).load();
            if (token == null || token.trim().length() < 20) throw new Exception("Saved GitHub token is missing. Reopen APK Factory and enter the token again.");

            String projectRaw = prefs.getString(BuildState.PROJECT_URI, null);
            String requestedRepo = prefs.getString(BuildState.REQUESTED_REPO, null);
            if (projectRaw == null || requestedRepo == null || requestedRepo.trim().isEmpty()) throw new Exception("Build recovery data is incomplete. Choose the project ZIP again.");
            Uri projectUri = Uri.parse(projectRaw);
            boolean privateRepo = prefs.getBoolean(BuildState.PRIVATE_REPO, true);
            String jobId = prefs.getString(BuildState.JOB_ID, null);
            if (jobId == null || jobId.isEmpty()) throw new Exception("Build recovery job ID is missing.");
            int stage = prefs.getInt(BuildState.STAGE, BuildState.NEW);

            String owner = prefs.getString(BuildState.OWNER, null);
            String repo = prefs.getString(BuildState.REPO, requestedRepo);
            String branch = prefs.getString(BuildState.BRANCH, null);
            String commitSha = prefs.getString(BuildState.COMMIT_SHA, null);
            long workflowId = prefs.getLong(BuildState.WORKFLOW_ID, 0L);
            long runId = prefs.getLong(BuildState.RUN_ID, 0L);

            List<ProjectFile> files = null;
            if (stage < BuildState.REPO_READY) {
                setStatus("Reading project", 5);
                files = readZip(projectUri);
                validateProject(files);
                log("Project looks buildable: " + files.size() + " files.");

                setStatus("Connecting to GitHub", 10);
                owner = githubUser(token);
                log("GitHub account: " + owner);
                JSONObject repoJson = findExistingRepo(token, owner, requestedRepo);
                if (repoJson == null) {
                    repoJson = createRepo(token, requestedRepo, privateRepo, jobId);
                    log("Created permanent build repository: " + owner + "/" + requestedRepo);
                } else {
                    log("Reusing permanent build repository: " + owner + "/" + requestedRepo);
                }
                repo = repoJson.getString("name");
                branch = repoJson.optString("default_branch", "main");
                String repoUrl = repoJson.optString("html_url", "https://github.com/" + owner + "/" + repo);
                prefs.edit().putString(BuildState.OWNER, owner)
                        .putString(BuildState.REPO, repo)
                        .putString(BuildState.BRANCH, branch)
                        .putString(BuildState.REPO_URL, repoUrl)
                        .putInt(BuildState.STAGE, BuildState.REPO_READY).apply();
                stage = BuildState.REPO_READY;
                log("Repository ready: " + owner + "/" + repo);
            } else {
                validateRecovery(owner, repo, branch, "repository");
                logOnce("Recovered repository state: " + owner + "/" + repo);
            }

            if (stage < BuildState.COMMIT_READY) {
                if (files == null) {
                    setStatus("Re-reading project for recovery", 8);
                    files = readZip(projectUri);
                    validateProject(files);
                }
                files.removeIf(f -> FACTORY_WORKFLOW_PATH.equals(f.path) || PROJECT_ARCHIVE_PATH.equals(f.path));
                log("Prepared normalized project archive + dedicated build workflow. GitHub upload uses two blobs instead of one API call per source file.");

                setStatus("Uploading compact project", 15);
                commitSha = uploadProjectSingleCommit(token, owner, repo, branch, files);
                prefs.edit().putString(BuildState.COMMIT_SHA, commitSha)
                        .putInt(BuildState.STAGE, BuildState.COMMIT_READY).apply();
                stage = BuildState.COMMIT_READY;
                log("Entire project committed in one Git commit: " + shortSha(commitSha));
            } else {
                validateRecovery(commitSha, "commit SHA");
                logOnce("Recovered commit state: " + shortSha(commitSha));
            }

            if (stage < BuildState.WORKFLOW_READY) {
                setStatus("Verifying workflow", 67);
                verifyWorkflowFile(token, owner, repo, branch, FACTORY_WORKFLOW_PATH);
                WorkflowInfo workflow = waitForWorkflowRegistration(token, owner, repo, FACTORY_WORKFLOW_PATH);
                workflowId = workflow.id;
                prefs.edit().putLong(BuildState.WORKFLOW_ID, workflowId)
                        .putInt(BuildState.STAGE, BuildState.WORKFLOW_READY).apply();
                stage = BuildState.WORKFLOW_READY;
                log("✓ GitHub registered workflow: " + workflow.name + " (ID " + workflow.id + ").");
            } else if (workflowId <= 0) {
                throw new Exception("Saved workflow ID is missing; recovery cannot safely identify the APK Factory workflow.");
            }

            if (stage < BuildState.RUN_READY) {
                setStatus("Locating build run", 72);
                runId = findExistingExactRun(token, owner, repo, workflowId, branch, commitSha);
                if (runId <= 0) {
                    setStatus("Starting GitHub Actions build", 74);
                    dispatchWorkflow(token, owner, repo, workflowId, branch);
                    log("✓ Build dispatch accepted by GitHub.");
                    runId = waitForDispatchedRun(token, owner, repo, workflowId, branch, commitSha, RUN_DISCOVERY_MS);
                } else {
                    log("Recovered an already-dispatched APK Factory run: #" + runId);
                }
                prefs.edit().putLong(BuildState.RUN_ID, runId)
                        .putInt(BuildState.STAGE, BuildState.RUN_READY).apply();
                stage = BuildState.RUN_READY;
                log("✓ Tracking exact build run ID " + runId + ".");
            } else if (runId <= 0) {
                throw new Exception("Saved GitHub run ID is missing; recovery cannot safely continue.");
            }

            if (stage < BuildState.BUILD_SUCCEEDED) {
                JSONObject run = waitForCompletion(token, owner, repo, runId, BUILD_WAIT_MS);
                String conclusion = run.optString("conclusion", "unknown");
                if (!"success".equals(conclusion)) {
                    throw new Exception("GitHub Actions finished with: " + conclusion + ". Tap Open Repo to inspect the exact run log.");
                }
                prefs.edit().putInt(BuildState.STAGE, BuildState.BUILD_SUCCEEDED).apply();
                stage = BuildState.BUILD_SUCCEEDED;
                log("✓ Exact tracked GitHub run completed successfully.");
            }

            if (stage < BuildState.APK_SAVED) {
                setStatus("Downloading APK artifact", 95);
                byte[] artifactZip = downloadExactArtifactWithRetry(token, owner, repo, runId, ARTIFACT_WAIT_MS);
                ApkFile apk = extractFirstApk(artifactZip);
                Uri output = saveApkToDownloads(apk);
                prefs.edit().putString(BuildState.OUTPUT_URI, output.toString())
                        .putString(BuildState.OUTPUT_NAME, apk.name)
                        .putInt(BuildState.STAGE, BuildState.APK_SAVED).apply();
                log("✓ APK automatically saved to Downloads/APK Factory: " + apk.name);
            }

            prefs.edit().putInt(BuildState.STAGE, BuildState.DONE).apply();
            succeed();
        } catch (Exception e) {
            fail("Build stopped: " + safeMessage(e));
        }
    }

    private void validateRecovery(String owner, String repo, String branch, String what) throws Exception {
        if (owner == null || owner.isEmpty() || repo == null || repo.isEmpty() || branch == null || branch.isEmpty())
            throw new Exception("Saved " + what + " recovery state is incomplete.");
    }

    private void validateRecovery(String value, String what) throws Exception {
        if (value == null || value.trim().isEmpty()) throw new Exception("Saved " + what + " is missing.");
    }

    private List<ProjectFile> readZip(Uri uri) throws Exception {
        List<ProjectFile> raw = new ArrayList<>();
        long total = 0;
        InputStream base = getContentResolver().openInputStream(uri);
        if (base == null) throw new Exception("Android could not reopen the selected project ZIP. Choose it again.");
        try (InputStream in = base; ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                String path = ze.getName().replace('\\', '/');
                if (path.startsWith("/") || path.contains("../") || path.contains("/..") || path.indexOf('\0') >= 0) continue;
                if (path.startsWith("__MACOSX/") || path.contains("/.git/") || path.startsWith(".git/") || path.contains("/build/")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int r;
                while ((r = zis.read(buf)) != -1) {
                    out.write(buf, 0, r); total += r;
                    if (total > 120L * 1024L * 1024L) throw new Exception("Project ZIP is too large for this APK Factory build (120 MB source limit).");
                }
                raw.add(new ProjectFile(path, out.toByteArray()));
            }
        }
        if (raw.isEmpty()) throw new Exception("ZIP contains no project files.");
        String prefix = commonTopFolder(raw);
        if (prefix != null) for (ProjectFile f : raw) f.path = f.path.substring(prefix.length());
        raw.removeIf(f -> f.path.trim().isEmpty());
        return raw;
    }

    private String commonTopFolder(List<ProjectFile> files) {
        String first = files.get(0).path; int slash = first.indexOf('/'); if (slash < 1) return null;
        String top = first.substring(0, slash + 1);
        for (ProjectFile f : files) if (!f.path.startsWith(top)) return null;
        return top;
    }

    private void validateProject(List<ProjectFile> files) throws Exception {
        boolean gradle = false, app = false;
        for (ProjectFile f : files) {
            String p = f.path;
            if (p.equals("settings.gradle") || p.equals("settings.gradle.kts") || p.equals("build.gradle") || p.equals("build.gradle.kts")) gradle = true;
            if (p.equals("app/build.gradle") || p.equals("app/build.gradle.kts")) app = true;
        }
        if (!gradle && !app) throw new Exception("This ZIP does not look like a Gradle Android project. Expected settings.gradle or app/build.gradle.");
    }

    private String githubUser(String token) throws Exception {
        return requestJson("GET", API + "/user", token, null).getString("login");
    }

    private JSONObject createRepo(String token, String repo, boolean priv, String jobId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", repo);
        body.put("private", priv);
        body.put("auto_init", true);
        body.put("description", "APK Factory 2.6.2 job " + jobId);
        try { return requestJson("POST", API + "/user/repos", token, body.toString()); }
        catch (HttpError h) {
            if (h.code == 422) {
                String owner = githubUser(token);
                JSONObject existing = findExistingRepo(token, owner, repo);
                if (existing != null) return existing;
                throw new Exception("GitHub reported the repository name is unavailable, but APK Factory could not access an existing repository with that name.");
            }
            throw h;
        }
    }

    private JSONObject findExistingRepo(String token, String owner, String repo) throws Exception {
        try {
            return requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo), token, null);
        } catch (HttpError h) {
            if (h.code == 404) return null;
            throw h;
        }
    }

    private JSONObject findRecoverableRepo(String token, String owner, String repo, String jobId) throws Exception {
        try {
            JSONObject existing = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo), token, null);
            String expected = "APK Factory 2.6.2 job " + jobId;
            return expected.equals(existing.optString("description", "")) ? existing : null;
        } catch (HttpError h) {
            if (h.code == 404) return null;
            throw h;
        }
    }

    private String uploadProjectSingleCommit(String token, String owner, String repo, String branch, List<ProjectFile> files) throws Exception {
        JSONObject ref = null; Exception last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                ref = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/ref/heads/" + enc(branch), token, null);
                break;
            } catch (Exception e) { last = e; Thread.sleep(1000); }
        }
        if (ref == null) throw new Exception("GitHub repository initialized, but its default branch was not ready: " + (last == null ? "unknown error" : safeMessage(last)));

        String baseCommitSha = ref.getJSONObject("object").getString("sha");
        byte[] projectArchive = buildNormalizedProjectZip(files);
        if (projectArchive.length > 90L * 1024L * 1024L) throw new Exception("Project archive is too large for APK Factory's compact GitHub upload path.");

        JSONArray entries = new JSONArray();
        entries.put(makeBlobTreeEntry(token, owner, repo, PROJECT_ARCHIVE_PATH, projectArchive, "100644"));
        setStatus("Uploading project archive", 38);
        entries.put(makeBlobTreeEntry(token, owner, repo, FACTORY_WORKFLOW_PATH, defaultWorkflow().getBytes(StandardCharsets.UTF_8), "100644"));
        setStatus("Uploading build workflow", 58);

        // No base_tree: this creates an exact replacement tree, so stale files from a previous build cannot leak into this build.
        JSONObject treeBody = new JSONObject(); treeBody.put("tree", entries);
        JSONObject tree = requestJson("POST", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/trees", token, treeBody.toString());
        JSONObject commitBody = new JSONObject(); commitBody.put("message", "APK Factory build " + System.currentTimeMillis()); commitBody.put("tree", tree.getString("sha"));
        JSONArray parents = new JSONArray(); parents.put(baseCommitSha); commitBody.put("parents", parents);
        JSONObject commit = requestJson("POST", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/commits", token, commitBody.toString());
        String commitSha = commit.optString("sha", "");
        if (commitSha.isEmpty()) throw new Exception("GitHub created a commit without returning its SHA.");
        updateBranchRefWithRetry(token, owner, repo, branch, commitSha);
        return commitSha;
    }

    private JSONObject makeBlobTreeEntry(String token, String owner, String repo, String path, byte[] data, String mode) throws Exception {
        JSONObject blobBody = new JSONObject();
        blobBody.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        blobBody.put("encoding", "base64");
        JSONObject blob = requestJson("POST", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/blobs", token, blobBody.toString());
        JSONObject entry = new JSONObject();
        entry.put("path", path); entry.put("mode", mode); entry.put("type", "blob"); entry.put("sha", blob.getString("sha"));
        return entry;
    }

    private byte[] buildNormalizedProjectZip(List<ProjectFile> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ProjectFile f : files) {
                if (FACTORY_WORKFLOW_PATH.equals(f.path) || PROJECT_ARCHIVE_PATH.equals(f.path)) continue;
                ZipEntry e = new ZipEntry(f.path);
                zip.putNextEntry(e);
                zip.write(f.data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void updateBranchRefWithRetry(String token, String owner, String repo, String branch, String commitSha) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                JSONObject update = new JSONObject(); update.put("sha", commitSha); update.put("force", false);
                if (update.optString("sha", "").isEmpty()) throw new Exception("Internal error: branch update SHA was empty.");
                requestJson("PATCH", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/git/refs/heads/" + enc(branch), token, update.toString());
                return;
            } catch (HttpError h) {
                last = h;
                if (h.code != 404 && h.code != 409 && h.code != 422) throw h;
                log("GitHub branch update is not ready yet; retrying with commit SHA… (" + attempt + "/8)");
            } catch (Exception e) { last = e; }
            Thread.sleep(1000L * attempt);
        }
        throw new Exception("GitHub would not advance the default branch after retries: " + (last == null ? "unknown error" : safeMessage(last)));
    }

    private void verifyWorkflowFile(String token, String owner, String repo, String branch, String path) throws Exception {
        Exception last = null;
        for (int i = 0; i < 20; i++) {
            try {
                JSONObject j = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/contents/" + encodePath(path) + "?ref=" + enc(branch), token, null);
                if ("file".equals(j.optString("type")) && path.equals(j.optString("path", ""))) return;
            } catch (Exception e) { last = e; }
            if (i == 0) log("Workflow file is committed; waiting for GitHub's contents API to catch up…");
            Thread.sleep(2000);
        }
        throw new Exception("Repository upload completed, but GitHub could not verify " + path + " on branch " + branch + ". " + (last == null ? "" : safeMessage(last)));
    }

    private WorkflowInfo waitForWorkflowRegistration(String token, String owner, String repo, String path) throws Exception {
        Exception last = null;
        for (int i = 0; i < 60; i++) {
            try {
                JSONObject j = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/actions/workflows?per_page=100", token, null);
                JSONArray a = j.optJSONArray("workflows");
                if (a != null) {
                    for (int n = 0; n < a.length(); n++) {
                        JSONObject w = a.getJSONObject(n);
                        if (path.equals(w.optString("path", ""))) {
                            String state = w.optString("state", "unknown");
                            if (!"active".equals(state)) throw new Exception("GitHub found the APK Factory workflow, but its state is '" + state + "'. Enable Actions for this repository and try again.");
                            return new WorkflowInfo(w.getLong("id"), w.optString("name", "APK Factory Build"));
                        }
                    }
                }
            } catch (HttpError h) {
                if (h.code == 403) throw new Exception("GitHub would not let APK Factory read Actions workflows. Give the token Actions read/write access, then try again.");
                last = h;
            } catch (Exception e) { last = e; }
            if (i == 0 || i == 20 || i == 40) log("Waiting for GitHub to register the dedicated workflow…");
            Thread.sleep(2000);
        }
        throw new Exception("The workflow file exists, but GitHub did not register it within 2 minutes. Open Repo → Actions and confirm Actions is enabled. " + (last == null ? "" : safeMessage(last)));
    }

    private void dispatchWorkflow(String token, String owner, String repo, long workflowId, String branch) throws Exception {
        JSONObject body = new JSONObject(); body.put("ref", branch);
        try {
            requestJson("POST", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/actions/workflows/" + workflowId + "/dispatches", token, body.toString());
        } catch (HttpError h) {
            if (h.code == 403) throw new Exception("GitHub rejected the build start request. The token needs Actions read/write access for this repository.");
            if (h.code == 404) throw new Exception("GitHub registered the workflow but could not dispatch it. Confirm GitHub Actions is enabled for the repository.");
            if (h.code == 422) throw new Exception("GitHub could not start the workflow on branch '" + branch + "'. Confirm it is the repository default branch.");
            throw h;
        }
    }

    private long findExistingExactRun(String token, String owner, String repo, long workflowId, String branch, String commitSha) throws Exception {
        JSONObject j = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/actions/workflows/" + workflowId + "/runs?branch=" + enc(branch) + "&event=workflow_dispatch&per_page=50", token, null);
        JSONArray a = j.optJSONArray("workflow_runs");
        if (a == null) return 0L;
        for (int n = 0; n < a.length(); n++) {
            JSONObject run = a.getJSONObject(n);
            if (commitSha.equals(run.optString("head_sha", "")) && "workflow_dispatch".equals(run.optString("event", ""))) return run.getLong("id");
        }
        return 0L;
    }

    private long waitForDispatchedRun(String token, String owner, String repo, long workflowId, String branch, String commitSha, long timeoutMs) throws Exception {
        long started = System.currentTimeMillis(); long lastLog = 0;
        while (System.currentTimeMillis() - started < timeoutMs) {
            long id = findExistingExactRun(token, owner, repo, workflowId, branch, commitSha);
            if (id > 0) return id;
            long now = System.currentTimeMillis();
            if (now - lastLog >= 12000) {
                log("Build request accepted; waiting for the exact workflow run… " + ((now - started) / 1000) + "s");
                lastLog = now;
            }
            Thread.sleep(3000);
        }
        throw new Exception("GitHub accepted the workflow dispatch, but the exact run did not appear within 4 minutes. Open Repo → Actions to check Actions policy or account limits.");
    }

    private JSONObject waitForCompletion(String token, String owner, String repo, long runId, long timeoutMs) throws Exception {
        long started = System.currentTimeMillis(); long lastLog = 0;
        while (System.currentTimeMillis() - started < timeoutMs) {
            JSONObject r = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/actions/runs/" + runId, token, null);
            String st = r.optString("status", "unknown");
            if ("completed".equals(st)) return r;
            long elapsed = System.currentTimeMillis() - started;
            int pct = 76 + (int)Math.min(17, (elapsed * 17L) / Math.max(1L, BUILD_WAIT_MS));
            setStatus("GitHub Actions: " + st, pct);
            if (System.currentTimeMillis() - lastLog >= 30000) {
                log("Exact run " + runId + " still " + st + " (" + (elapsed / 60000) + "m elapsed).");
                lastLog = System.currentTimeMillis();
            }
            Thread.sleep(10000);
        }
        throw new Exception("GitHub Actions did not finish within 45 minutes. The workflow itself has a 40-minute timeout, so this is treated as an abnormal/stuck run.");
    }

    private byte[] downloadExactArtifactWithRetry(String token, String owner, String repo, long runId, long timeoutMs) throws Exception {
        long started = System.currentTimeMillis(); int attempt = 0; Exception last = null;
        while (System.currentTimeMillis() - started < timeoutMs) {
            attempt++;
            try {
                Artifact a = artifactFromRun(token, owner, repo, runId);
                if (a == null) throw new Exception("Artifact metadata is not visible yet for tracked run " + runId + ".");
                log("Downloading exact-run artifact: " + a.name + " (attempt " + attempt + ")");
                return getBytes(a.downloadUrl, token);
            } catch (Exception e) {
                last = e;
                String m = safeMessage(e).toLowerCase(Locale.US);
                boolean retryable = m.contains("404") || m.contains("403") || m.contains("artifact") || m.contains("redirect") || m.contains("timed out") || m.contains("timeout");
                if (!retryable) throw e;
                log("Artifact is not downloadable yet; refreshing exact-run metadata…");
                Thread.sleep(Math.min(15000L, 2500L * attempt));
            }
        }
        throw new Exception("The tracked build succeeded, but its APK artifact did not become downloadable within 5 minutes: " + (last == null ? "unknown error" : safeMessage(last)));
    }

    private Artifact artifactFromRun(String token, String owner, String repo, long runId) throws Exception {
        JSONObject j = requestJson("GET", API + "/repos/" + enc(owner) + "/" + enc(repo) + "/actions/runs/" + runId + "/artifacts?per_page=100", token, null);
        JSONArray a = j.optJSONArray("artifacts");
        if (a == null || a.length() == 0) return null;
        JSONObject fallback = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (x.optBoolean("expired", false)) continue;
            String name = x.optString("name", "artifact");
            String lower = name.toLowerCase(Locale.US);
            if (lower.contains("apk") || lower.contains("android") || lower.contains("lumi")) return new Artifact(x.getString("archive_download_url"), name);
            if (fallback == null) fallback = x;
        }
        if (fallback != null) return new Artifact(fallback.getString("archive_download_url"), fallback.optString("name", "artifact"));
        return null;
    }

    private ApkFile extractFirstApk(byte[] zip) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (!ze.isDirectory() && ze.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
                    while ((n = zis.read(b)) != -1) out.write(b, 0, n);
                    return new ApkFile(new File(ze.getName()).getName(), out.toByteArray());
                }
            }
        }
        throw new Exception("Artifact downloaded, but it did not contain an APK.");
    }

    private Uri saveApkToDownloads(ApkFile apk) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, apk.name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/APK Factory");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Android could not create the APK in Downloads.");
        boolean success = false;
        try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new Exception("Android could not open the APK destination for writing.");
            out.write(apk.bytes); success = true;
        } finally {
            if (!success) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
        }
        ContentValues done = new ContentValues(); done.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private byte[] getBytes(String url, String token) throws Exception {
        URL current = new URL(url);
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "APK-Factory-v2.6.2");
            String host = current.getHost() == null ? "" : current.getHost().toLowerCase(Locale.US);
            if ("api.github.com".equals(host)) {
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.trim().isEmpty()) throw new Exception("Artifact redirect did not include a location.");
                current = new URL(current, loc); continue;
            }
            if (code < 200 || code >= 300) {
                String body = ""; try { body = readText(c.getErrorStream()); } catch (Exception ignored) {}
                throw new Exception("Artifact download failed: HTTP " + code + " from " + host + (body.trim().isEmpty() ? "" : " — " + body.trim()));
            }
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[16384]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
            }
        }
        throw new Exception("Too many redirects while downloading artifact.");
    }

    private JSONObject requestJson(String method, String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(20000); c.setReadTimeout(45000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "APK-Factory-v2.6.2");
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        String txt = readText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        if (code < 200 || code >= 300) throw new HttpError(code, githubError(code, txt));
        return txt.trim().isEmpty() ? new JSONObject() : new JSONObject(txt);
    }

    private String defaultWorkflow() {
        return "name: APK Factory 2.6.2 Reuse Build\n\non:\n  workflow_dispatch:\n\npermissions:\n  contents: read\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n    timeout-minutes: 40\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '17'\n      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: '8.10.2'\n      - uses: android-actions/setup-android@v3\n      - name: Expand project\n        shell: bash\n        run: |\n          rm -rf _apkfactory_project\n          mkdir _apkfactory_project\n          unzip -q apkfactory-project.zip -d _apkfactory_project\n      - name: Build APK\n        shell: bash\n        working-directory: _apkfactory_project\n        run: |\n          if [ -f ./gradlew ] && [ -f ./gradle/wrapper/gradle-wrapper.jar ]; then chmod +x ./gradlew && ./gradlew assembleDebug --stacktrace; else gradle assembleDebug --stacktrace; fi\n      - name: Upload APK\n        uses: actions/upload-artifact@v4\n        with:\n          name: apk-factory-output-${{ github.run_id }}\n          path: '_apkfactory_project/**/build/outputs/apk/**/*.apk'\n          if-no-files-found: error\n";
    }

    private void setStatus(String s, int pct) {
        prefs.edit().putString(BuildState.STATUS, s).putInt(BuildState.PROGRESS, Math.max(0, Math.min(100, pct))).apply();
        updateNotification(s, pct);
    }

    private void log(String s) {
        synchronized (this) {
            String old = prefs.getString(BuildState.LOG, "");
            String next = old.isEmpty() ? s : old + "\n" + s;
            if (next.length() > 70000) next = next.substring(next.length() - 70000);
            prefs.edit().putString(BuildState.LOG, next).apply();
        }
    }

    private void logOnce(String s) {
        String old = prefs.getString(BuildState.LOG, "");
        if (!old.contains(s)) log(s);
    }

    private void succeed() {
        prefs.edit().putBoolean(BuildState.ACTIVE, false)
                .putInt(BuildState.STAGE, BuildState.DONE)
                .putString(BuildState.STATUS, "Build complete — APK saved")
                .putInt(BuildState.PROGRESS, 100)
                .putBoolean(BuildState.COMPLETION_PENDING, true)
                .remove(BuildState.FAILURE).apply();
        log("GREEN PATH COMPLETE: repository, exact workflow run, artifact download, and automatic APK save all completed.");
        showTerminalNotification("APK ready", "Build complete. Tap to install the downloaded APK.", true);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void fail(String message) {
        if (prefs == null) prefs = getSharedPreferences(BuildState.PREF, MODE_PRIVATE);
        log("ERROR: " + message);
        prefs.edit().putBoolean(BuildState.ACTIVE, false)
                .putString(BuildState.STATUS, "Build stopped")
                .putInt(BuildState.PROGRESS, 0)
                .putString(BuildState.FAILURE, message).apply();
        showTerminalNotification("Build stopped", message, false);
        try { stopForeground(STOP_FOREGROUND_DETACH); } catch (Exception ignored) {}
        stopSelf();
    }

    private void updateNotification(String status, int progress) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && prefs.getBoolean(BuildState.ACTIVE, false)) nm.notify(NOTIFICATION_ID, buildNotification(status, progress, true, false));
    }

    private void showTerminalNotification(String title, String text, boolean success) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).setOngoing(false)
                .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error);
        nm.notify(NOTIFICATION_ID, b.build());
    }

    private Notification buildNotification(String status, int progress, boolean ongoing, boolean alert) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setContentTitle("APK Factory 2.6.2")
                .setContentText(status == null ? "Build running" : status)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(!alert)
                .setContentIntent(pi)
                .setProgress(100, Math.max(0, Math.min(100, progress)), false);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel c = new NotificationChannel(CHANNEL_ID, "APK Factory builds", NotificationManager.IMPORTANCE_LOW);
                c.setDescription("Resumable APK Factory upload, GitHub build, and artifact retrieval status.");
                nm.createNotificationChannel(c);
            }
        }
    }

    private String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream x = in; ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = x.read(b)) != -1) o.write(b, 0, n); return o.toString("UTF-8");
        }
    }

    private String githubError(int code, String txt) {
        try { JSONObject j = new JSONObject(txt); return "GitHub HTTP " + code + ": " + j.optString("message", txt); }
        catch (Exception e) { return "GitHub HTTP " + code + ": " + txt; }
    }

    private String enc(String s) { try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; } }
    private String encodePath(String p) { String[] a = p.split("/"); StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i++) { if (i > 0) b.append('/'); b.append(enc(a[i])); } return b.toString(); }
    private String safeMessage(Exception e) { String m = e.getMessage(); return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m; }
    private String shortSha(String sha) { return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length())); }

    static class ProjectFile { String path; byte[] data; ProjectFile(String p, byte[] d) { path = p; data = d; } }
    static class WorkflowInfo { long id; String name; WorkflowInfo(long i, String n) { id = i; name = n; } }
    static class Artifact { String downloadUrl, name; Artifact(String u, String n) { downloadUrl = u; name = n; } }
    static class ApkFile { String name; byte[] bytes; ApkFile(String n, byte[] b) { name = n; bytes = b; } }
    static class HttpError extends Exception { int code; HttpError(int c, String m) { super(m); code = c; } }
}
