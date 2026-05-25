package com.keyboard101;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS   = "keyboard101";
    private static final String KEY_DARK = "dark_theme";

    private LinearLayout updateCard;
    private TextView     tvUpdateTitle;
    private TextView     tvUpdateNotes;
    private Button       btnUpdate;

    // Stored when the update check finds a newer version
    private String pendingApkUrl = null;
    // Ensures we only fetch the update JSON once per launch
    private boolean updateChecked = false;
    // One-shot guard so the handwriting model is pre-downloaded a single time per launch
    private boolean preloadStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button enableBtn = findViewById(R.id.btn_enable);
        Button switchBtn = findViewById(R.id.btn_switch);
        Button themeBtn  = findViewById(R.id.btn_theme);
        TextView status  = findViewById(R.id.tv_status);

        enableBtn.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        switchBtn.setOnClickListener(v -> {
            InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            } else {
                Toast.makeText(this, "InputMethodManager unavailable",
                    Toast.LENGTH_SHORT).show();
            }
        });

        updateThemeButton(themeBtn);
        themeBtn.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean currentlyDark = prefs.getBoolean(KEY_DARK, true);
            prefs.edit().putBoolean(KEY_DARK, !currentlyDark).apply();
            updateThemeButton(themeBtn);
            Toast.makeText(this, "Theme changed — reopen keyboard to apply",
                Toast.LENGTH_SHORT).show();
        });

        status.setText(getString(R.string.setup_status_template));

        // Update card
        updateCard    = findViewById(R.id.update_card);
        tvUpdateTitle = findViewById(R.id.tv_update_title);
        tvUpdateNotes = findViewById(R.id.tv_update_notes);
        btnUpdate     = findViewById(R.id.btn_update);

        btnUpdate.setOnClickListener(v -> onInstallTapped());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!updateChecked) {
            updateChecked = true;
            checkForUpdate();
        }
        if (!preloadStarted) {
            preloadStarted = true;
            preloadHandwritingModel();
        }
    }

    /**
     * Fires a silent ML Kit handwriting model download in the background.
     * Why: the model is fetched lazily by ML Kit on first use, which surprises
     * users who just sideloaded the APK and crashes the IME if callbacks fire
     * after view detach. Pre-warming here means by the time the user reaches
     * handwriting mode the model is already on disk.
     */
    private void preloadHandwritingModel() {
        try {
            DigitalInkRecognitionModelIdentifier id =
                DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hans-CN");
            if (id == null) return;
            DigitalInkRecognitionModel model =
                DigitalInkRecognitionModel.builder(id).build();
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener(downloaded -> {
                    if (Boolean.FALSE.equals(downloaded)) {
                        try {
                            RemoteModelManager.getInstance()
                                .download(model, new DownloadConditions.Builder().build());
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------

    private void checkForUpdate() {
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onUpdateAvailable(String versionName, String apkUrl,
                                          String releaseNotes) {
                pendingApkUrl = apkUrl;
                tvUpdateTitle.setText("Update available — v" + versionName);
                if (releaseNotes.isEmpty()) {
                    tvUpdateNotes.setVisibility(View.GONE);
                } else {
                    tvUpdateNotes.setText(releaseNotes);
                    tvUpdateNotes.setVisibility(View.VISIBLE);
                }
                btnUpdate.setEnabled(true);
                btnUpdate.setText("Download & Install");
                updateCard.setVisibility(View.VISIBLE);
            }

            @Override public void onUpToDate() { /* nothing to show */ }
            @Override public void onError()     { /* silent fail — no network, etc. */ }
        });
    }

    private void onInstallTapped() {
        if (pendingApkUrl == null) return;

        // Android 8+ requires the user to explicitly allow "Install unknown apps"
        // for this app. If they haven't, send them to the system setting for it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this,
                "Enable \"Install unknown apps\" for Keyboard101, then tap again",
                Toast.LENGTH_LONG).show();
            startActivity(
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return; // user taps the button again after granting
        }

        btnUpdate.setEnabled(false);
        btnUpdate.setText("Downloading…");
        UpdateChecker.downloadAndInstall(this, pendingApkUrl);
        // The system installer dialog appears automatically when the download finishes
    }

    private void updateThemeButton(Button btn) {
        boolean isDark =
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true);
        btn.setText(isDark ? R.string.btn_theme_dark : R.string.btn_theme_light);
    }
}
