package com.keyboard101;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Self-hosted over-the-air update checker.
 *
 * HOW TO USE:
 * 1. Host a JSON file at UPDATE_JSON_URL containing:
 *      {
 *        "version_code": 2,
 *        "version_name": "1.1.0",
 *        "apk_url": "https://your-server.com/keyboard101.apk",
 *        "release_notes": "Bug fixes and performance improvements"
 *      }
 * 2. Each time you ship a new build, increment versionCode in build.gradle,
 *    upload the signed APK, then update the JSON file.
 * 3. The app checks on every launch; if version_code > installed build,
 *    it shows the update card in SettingsActivity.
 *
 * The signed APK MUST use the same keystore as the installed app —
 * Android will reject an update signed with a different key.
 */
public class UpdateChecker {

    // -------------------------------------------------------------------------
    // TODO: Replace with your actual URL (must be HTTPS).
    //
    // Recommended free option: store latest.json in your GitHub repo and
    // point to the raw URL:
    //   https://raw.githubusercontent.com/YOUR_USER/YOUR_REPO/main/latest.json
    // -------------------------------------------------------------------------
    public static final String UPDATE_JSON_URL =
        "https://YOUR_UPDATE_SERVER/keyboard101/latest.json";

    private static final String APK_FILENAME = "keyboard101_update.apk";

    public interface Callback {
        void onUpdateAvailable(String versionName, String apkUrl, String releaseNotes);
        void onUpToDate();
        void onError();
    }

    /**
     * Fetches UPDATE_JSON_URL on a background thread and compares version_code
     * against the installed build. Posts result to the main thread via callback.
     */
    public static void check(Context context, Callback callback) {
        new Thread(() -> {
            Handler main = new Handler(Looper.getMainLooper());
            try {
                URL url = new URL(UPDATE_JSON_URL);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(8_000);
                conn.setRequestMethod("GET");

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }

                JSONObject json = new JSONObject(sb.toString());
                int latestCode   = json.getInt("version_code");
                String latestName = json.getString("version_name");
                String apkUrl    = json.getString("apk_url");
                String notes     = json.optString("release_notes", "");

                if (latestCode > BuildConfig.VERSION_CODE) {
                    main.post(() -> callback.onUpdateAvailable(latestName, apkUrl, notes));
                } else {
                    main.post(callback::onUpToDate);
                }
            } catch (Exception e) {
                main.post(callback::onError);
            }
        }).start();
    }

    /**
     * Enqueues an APK download via DownloadManager and triggers the system
     * installer when the download completes.
     *
     * Call only after verifying the user has granted REQUEST_INSTALL_PACKAGES
     * (the SettingsActivity handles that check before calling this method).
     */
    public static void downloadAndInstall(Context context, String apkUrl) {
        // Remove any leftover APK from a previous attempt
        File dest = new File(context.getExternalFilesDir(null), APK_FILENAME);
        if (dest.exists()) dest.delete();

        DownloadManager dm =
            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Keyboard101 update")
            .setDescription("Downloading…")
            .setDestinationInExternalFilesDir(context, null, APK_FILENAME)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        long downloadId = dm.enqueue(req);

        // Register a one-shot receiver that fires when this specific download finishes
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long finished =
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (finished == downloadId) {
                    ctx.unregisterReceiver(this);
                    installDownloadedApk(ctx);
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // Android 13+ requires explicit exported flag for dynamic receivers
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private static void installDownloadedApk(Context context) {
        File apkFile = new File(context.getExternalFilesDir(null), APK_FILENAME);
        if (!apkFile.exists()) return;

        Uri apkUri = FileProvider.getUriForFile(
            context,
            context.getPackageName() + ".fileprovider",
            apkFile);

        context.startActivity(
            new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
