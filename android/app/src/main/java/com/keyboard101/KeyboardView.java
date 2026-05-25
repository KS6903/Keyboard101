package com.keyboard101;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionCandidate;
import com.google.mlkit.vision.digitalink.RecognitionContext;
import com.google.mlkit.vision.digitalink.WritingArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuppressLint("ViewConstructor")
public class KeyboardView extends LinearLayout {

    public interface OnActionListener {
        void onText(CharSequence text);
        void onReplaceLast(CharSequence text);
        void onBackspace();
        void onEnter();
        void onSwitchIme();
        CharSequence getTextBefore(int n);
    }

    private enum Mode { QWERTY, NUMPAD, SYMBOLS, EMOJI, PINYIN, HANDWRITING }

    private static final String PREFS = "keyboard101";
    private static final String KEY_HEIGHT_DP = "kbd_height_dp";
    private static final int DEFAULT_HEIGHT_DP = 260;
    private static final int MIN_HEIGHT_DP = 160;
    private static final int MAX_HEIGHT_DP = 460;

    // Dark theme colors
    private static final int DARK_PANEL    = 0xFF1F2937;
    private static final int DARK_KEY      = 0xFF374151;
    private static final int DARK_KEY_DARK = 0xFF4B5563;
    private static final int DARK_TEXT     = 0xFFF9FAFB;
    private static final int DARK_TEXT_DIM = 0xFF9CA3AF;
    private static final int DARK_RESIZER  = 0xFF6B7280;
    private static final int DARK_BG       = 0xFF0F172A;
    // Light theme colors
    private static final int LIGHT_PANEL    = 0xFFE2E8F0;
    private static final int LIGHT_KEY      = 0xFFF8FAFC;
    private static final int LIGHT_KEY_DARK = 0xFF94A3B8;
    private static final int LIGHT_TEXT     = 0xFF0F172A;
    private static final int LIGHT_TEXT_DIM = 0xFF64748B;
    private static final int LIGHT_RESIZER  = 0xFF94A3B8;
    private static final int LIGHT_BG       = 0xFFF1F5F9;
    // Always-same
    private static final int BG_ACCENT     = 0xFF2563EB;

    private int cPanel()   { return darkTheme ? DARK_PANEL    : LIGHT_PANEL;    }
    private int cKey()     { return darkTheme ? DARK_KEY      : LIGHT_KEY;      }
    private int cKeyDark() { return darkTheme ? DARK_KEY_DARK : LIGHT_KEY_DARK; }
    private int cText()    { return darkTheme ? DARK_TEXT     : LIGHT_TEXT;     }
    private int cTextDim() { return darkTheme ? DARK_TEXT_DIM : LIGHT_TEXT_DIM; }
    private int cBg()      { return darkTheme ? DARK_BG       : LIGHT_BG;       }
    private int cResizer() { return darkTheme ? DARK_RESIZER  : LIGHT_RESIZER;  }

    private final OnActionListener noopListener = new OnActionListener() {
        @Override public void onText(CharSequence text) {}
        @Override public void onReplaceLast(CharSequence text) {}
        @Override public void onBackspace() {}
        @Override public void onEnter() {}
        @Override public void onSwitchIme() {}
        @Override public CharSequence getTextBefore(int n) { return ""; }
    };

    private OnActionListener listener = noopListener;
    private Mode mode = Mode.QWERTY;
    private Mode prevAlphaMode = Mode.QWERTY;
    private boolean shift = false;
    private boolean shiftLock = false;
    private int symbolsPage = 1;
    private boolean darkTheme = true;
    private boolean autoCapSentence = true;
    private String currentEmojiCategory = "Smileys";
    private int kbdHeightDp = DEFAULT_HEIGHT_DP;

    private LinearLayout keyboardArea;
    private LinearLayout candidateBar;
    private HorizontalScrollView candidateScroll;
    private String pinyinBuffer = "";
    private final List<String> pinyinCandidates = new ArrayList<>();
    private final Handler pinyinFetchHandler = new Handler(Looper.getMainLooper());
    private int chineseModeIdx = 0;
    // Slot 0 is dynamic: returns whichever English layout the user last used
    // (QWERTY or NUMPAD/T9). Slots 1 and 2 are Pinyin and Handwriting.
    private static final int CHINESE_MODE_COUNT = 3;
    private Mode lastEnglishMode = Mode.QWERTY;

    private Mode chineseModeAt(int idx) {
        switch (idx) {
            case 1: return Mode.PINYIN;
            case 2: return Mode.HANDWRITING;
            default: return lastEnglishMode;
        }
    }

    // Handwriting recognition state (persists across render() calls)
    private final List<String> hwCandidates = new ArrayList<>();
    private boolean hwModelReady = false;
    private DigitalInkRecognizer hwRecognizer;
    private boolean hwInitializing = false;
    // Set true once the user has lifted a stroke that ran through recognition;
    // controls whether the candidate bar should say "no match" vs "draw…".
    private boolean hwHasAttempt = false;
    private final Handler hwUiHandler = new Handler(Looper.getMainLooper());
    private final Handler autoCommitHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoCommitRunnable = () -> {
        try {
            if (mode != Mode.HANDWRITING) return;
            if (hwCandidates.isEmpty()) return;
            if (!isAttachedToWindow()) return;
            String top = hwCandidates.get(0);
            listener.onText(top);
            // Reset the handwriting surface and candidate state for the next char.
            for (int i = 0; keyboardArea != null && i < keyboardArea.getChildCount(); i++) {
                View v = keyboardArea.getChildAt(i);
                if (v instanceof HandwritingView) {
                    ((HandwritingView) v).clear();
                    break;
                }
            }
            hwCandidates.clear();
            hwHasAttempt = false;
            updateCandidates();
        } catch (Throwable ignored) {}
    };
    private void cancelAutoCommit() {
        autoCommitHandler.removeCallbacks(autoCommitRunnable);
    }
    private void scheduleAutoCommit() {
        autoCommitHandler.removeCallbacks(autoCommitRunnable);
        autoCommitHandler.postDelayed(autoCommitRunnable, 2000);
    }

    // Backspace hold-to-repeat
    private final Handler bkRepeatHandler = new Handler(Looper.getMainLooper());
    private Runnable bkRepeatRunnable;
    private boolean bkRepeating = false;

    // Key press preview popup
    private PopupWindow keyPreviewPopup;

    // Mode-swipe popup carousel
    private PopupWindow modeSwipePopup;
    private LinearLayout modeSwipeTrack;
    private final TextView[] modeSlots = new TextView[3];

    // Space hold-to-swipe state
    private final Handler spaceHoldHandler = new Handler(Looper.getMainLooper());
    private Runnable spaceHoldRunnable;
    private boolean spaceInSwipeMode = false;

    private View resizerBar;

    // T9 state
    private String t9LastKey = null;
    private int t9Index = 0;
    private final Handler t9Handler = new Handler(Looper.getMainLooper());
    private final Runnable t9Reset = () -> {
        t9LastKey = null;
        t9Index = 0;
        CharSequence before = listener.getTextBefore(1);
        if (before != null && ".".contentEquals(before)) listener.onText(" ");
        boolean changed = (shift && !shiftLock);
        if (changed) shift = false;
        if (autoCapCheck() || changed) render();
    };

    public KeyboardView(Context context) {
        super(context);
        try {
            darkTheme = prefs().getBoolean("dark_theme", true);
            autoCapSentence = prefs().getBoolean("auto_cap", true);
            setOrientation(VERTICAL);
            setBackgroundColor(cPanel());
            buildResizer();
            buildCandidateBar();
            buildKeyboardArea();
            applySavedHeight();
            autoCapCheck();
            render();
            // Start the handwriting model load silently so it's ready by the
            // time the user (maybe) opens Handwriting mode.
            initHandwritingRecognizer();
        } catch (Throwable t) {
            Log.w("KeyS", "KeyboardView init failed", t);
        }
    }

    private void buildCandidateBar() {
        candidateScroll = new HorizontalScrollView(getContext());
        candidateScroll.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        candidateScroll.setBackgroundColor(cPanel());
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setVisibility(GONE);

        candidateBar = new LinearLayout(getContext());
        candidateBar.setOrientation(HORIZONTAL);
        candidateBar.setGravity(Gravity.CENTER_VERTICAL);
        candidateBar.setPadding(dp(8), 0, dp(8), 0);
        candidateScroll.addView(candidateBar);
        addView(candidateScroll);
    }

    public void setDarkTheme(boolean dark) {
        darkTheme = dark;
        prefs().edit().putBoolean("dark_theme", dark).apply();
        setBackgroundColor(cPanel());
        rebuildResizer();
        render();
    }

    public boolean isDarkTheme() { return darkTheme; }

    /**
     * Clear per-session state that the user expects to vanish when the
     * keyboard hides — pinyin buffer, in-progress handwriting strokes,
     * pending fetches. Called by the IME service from onStartInputView.
     */
    public void resetTransientState() {
        cancelAutoCommit();
        pinyinBuffer = "";
        pinyinCandidates.clear();
        pinyinFetchHandler.removeCallbacksAndMessages(null);
        hwCandidates.clear();
        hwHasAttempt = false;
        // Clear any in-progress strokes if a HandwritingView is mounted.
        if (keyboardArea != null) {
            for (int i = 0; i < keyboardArea.getChildCount(); i++) {
                View v = keyboardArea.getChildAt(i);
                if (v instanceof HandwritingView) {
                    ((HandwritingView) v).clear();
                    break;
                }
            }
        }
        if (candidateBar != null) updateCandidates();
    }

    public void setOnActionListener(OnActionListener l) {
        this.listener = (l == null) ? noopListener : l;
        if (!shiftLock) {
            boolean prev = shift;
            shift = false;
            autoCapCheck();
            if (shift != prev) render();
        }
    }

    // ---------- Layout scaffolding ----------
    private void buildResizer() {
        RelativeLayout wrapper = new RelativeLayout(getContext());
        wrapper.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));
        wrapper.setBackgroundColor(cPanel());

        // Left side: drag handle to resize the keyboard.
        Button handle = new Button(getContext());
        handle.setText("⠿");
        handle.setBackground(null);
        handle.setTextColor(cResizer());
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        handle.setPadding(dp(12), 0, dp(16), 0);
        handle.setAllCaps(false);
        handle.setStateListAnimator(null);
        RelativeLayout.LayoutParams handleLp = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        handleLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        handleLp.addRule(RelativeLayout.CENTER_VERTICAL);
        handle.setLayoutParams(handleLp);

        final float[] startY = {0};
        final int[]   startH = {0};
        final boolean[] wasDrag = {false};
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0]  = event.getRawY();
                    startH[0]  = prefs().getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP);
                    wasDrag[0] = false;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = startY[0] - event.getRawY();
                    if (Math.abs(dy) > dp(6)) wasDrag[0] = true;
                    if (wasDrag[0]) {
                        int deltaDp = (int)(dy / getResources().getDisplayMetrics().density);
                        setKeyboardHeightDp(startH[0] + deltaDp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (wasDrag[0]) prefs().edit().putInt(KEY_HEIGHT_DP, kbdHeightDp).apply();
                    wasDrag[0] = false;
                    return true;
            }
            return false;
        });
        wrapper.addView(handle);

        // Right side: inline controls (theme, auto-cap, system settings).
        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        RelativeLayout.LayoutParams controlsLp = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        controlsLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        controlsLp.addRule(RelativeLayout.CENTER_VERTICAL);
        controls.setLayoutParams(controlsLp);

        Button themeBtn = makeToolbarButton(darkTheme ? "🌙" : "☀️");
        themeBtn.setOnClickListener(v -> {
            setDarkTheme(!darkTheme);
            // setDarkTheme rebuilds the bar, so no need to update label here.
        });
        controls.addView(themeBtn);

        Button autoCapBtn = makeToolbarButton("Aa");
        autoCapBtn.setAlpha(autoCapSentence ? 1f : 0.4f);
        autoCapBtn.setOnClickListener(v -> {
            autoCapSentence = !autoCapSentence;
            prefs().edit().putBoolean("auto_cap", autoCapSentence).apply();
            autoCapBtn.setAlpha(autoCapSentence ? 1f : 0.4f);
            autoCapCheck();
            render();
        });
        controls.addView(autoCapBtn);

        Button settingsBtn = makeToolbarButton("⚙");
        settingsBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            } catch (Throwable ignored) {}
        });
        controls.addView(settingsBtn);

        wrapper.addView(controls);

        resizerBar = wrapper;
        addView(wrapper);
    }

    private Button makeToolbarButton(String label) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setBackground(null);
        b.setTextColor(cTextDim());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        b.setIncludeFontPadding(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), LayoutParams.MATCH_PARENT);
        b.setLayoutParams(lp);
        return b;
    }

    private void rebuildResizer() {
        if (resizerBar != null) removeView(resizerBar);
        buildResizer();
        // buildResizer appends to the end; lift it back to index 0 so it
        // remains above the candidate bar and keyboard area.
        removeView(resizerBar);
        addView(resizerBar, 0);
        setBackgroundColor(cPanel());
    }

    private void buildKeyboardArea() {
        keyboardArea = new LinearLayout(getContext());
        keyboardArea.setOrientation(VERTICAL);
        keyboardArea.setPadding(dp(2), dp(2), dp(2), dp(4));
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        keyboardArea.setLayoutParams(lp);
        addView(keyboardArea);
    }

    private void applySavedHeight() {
        int saved = prefs().getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP);
        setKeyboardHeightDp(saved);
    }

    private int maxHeightDp() {
        int halfScreenPx = getResources().getDisplayMetrics().heightPixels / 2;
        return (int) (halfScreenPx / getResources().getDisplayMetrics().density);
    }

    private void setKeyboardHeightDp(int dp) {
        if (dp < MIN_HEIGHT_DP) dp = MIN_HEIGHT_DP;
        int max = maxHeightDp();
        if (dp > max) dp = max;
        kbdHeightDp = dp;
        ViewGroup.LayoutParams lp = getLayoutParams();
        int total = dp(dp) + dp(28); // include resizer toolbar
        if (lp == null) {
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, total));
        } else {
            lp.height = total;
            setLayoutParams(lp);
        }
    }

    // ---------- Rendering ----------
    private void render() {
        keyboardArea.removeAllViews();
        updateCandidates();
        switch (mode) {
            case QWERTY:  renderQwerty();  break;
            case SYMBOLS: renderSymbols(); break;
            case NUMPAD:  renderNumpad();  break;
            case EMOJI:   renderEmoji();   break;
            case PINYIN:  renderPinyin();  break;
            case HANDWRITING: renderHandwriting(); break;
        }
    }

    private void renderPinyin() {
        keyboardArea.addView(letterRow("qwertyuiop"));
        keyboardArea.addView(letterRow("asdfghjkl"));

        LinearLayout row3 = newRow();
        row3.addView(shiftKey(1.5f));
        for (char c : "zxcvbnm".toCharArray()) row3.addView(letterKey(String.valueOf(c)));
        row3.addView(backspaceKey(1.5f));
        keyboardArea.addView(row3);

        LinearLayout row4 = newRow();
        row4.addView(specialKey("123", 1.5f, v -> { prevAlphaMode = mode; mode = Mode.SYMBOLS; render(); }));
        row4.addView(specialKey("🌐", 1f, v -> cycleMode()));
        row4.addView(letterKey(","));
        row4.addView(spaceKey(5f));
        row4.addView(letterKey("."));
        row4.addView(specialKey("😀", 1f, v -> { prevAlphaMode = mode; mode = Mode.EMOJI; render(); }));
        row4.addView(accentKey("↵", 1.5f, v -> { listener.onEnter(); afterEnter(); }));
        keyboardArea.addView(row4);
    }

    private void renderHandwriting() {
        if (!hwModelReady && hwRecognizer == null && !hwInitializing) {
            initHandwritingRecognizer();
        }
        // Fixed height: keyboard content minus bottom row (48dp) and candidate bar (40dp)
        int hwPx = Math.max(dp(80), dp(kbdHeightDp) - dp(88));
        HandwritingView hw = new HandwritingView(getContext());
        hw.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, hwPx));
        keyboardArea.addView(hw);

        LinearLayout row4 = newRow();
        row4.addView(specialKey("ABC", 1.5f, v -> { cancelAutoCommit(); mode = Mode.QWERTY; lastEnglishMode = Mode.QWERTY; render(); }));
        row4.addView(spaceKey(5f));
        row4.addView(specialKey("Clear", 1.5f, v -> {
            cancelAutoCommit();
            hw.clear();
            hwCandidates.clear();
            hwHasAttempt = false;
            updateCandidates();
        }));
        row4.addView(backspaceKey(1.5f));
        keyboardArea.addView(row4);
    }

    /**
     * Fires the ML Kit handwriting model load entirely in the background.
     * No UI status updates — the only observable side-effect is hwModelReady
     * flipping to true once the recognizer is built. Recognition naturally
     * no-ops while the flag is false (see runRecognition), so the user just
     * sees an empty candidate bar with the static "Draw a character below…"
     * hint until the model finishes loading.
     */
    private void initHandwritingRecognizer() {
        if (hwInitializing || hwModelReady) return;
        hwInitializing = true;
        try {
            DigitalInkRecognitionModelIdentifier modelId =
                DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hans-CN");
            if (modelId == null) { hwInitializing = false; return; }
            DigitalInkRecognitionModel model =
                DigitalInkRecognitionModel.builder(modelId).build();
            RemoteModelManager.getInstance().isModelDownloaded(model)
                .addOnSuccessListener(isDownloaded -> {
                    if (Boolean.TRUE.equals(isDownloaded)) {
                        createHwRecognizer(model);
                    } else {
                        try {
                            RemoteModelManager.getInstance()
                                .download(model, new DownloadConditions.Builder().build())
                                .addOnSuccessListener(unused -> createHwRecognizer(model))
                                .addOnFailureListener(e -> hwInitializing = false);
                        } catch (Throwable t) {
                            hwInitializing = false;
                        }
                    }
                })
                .addOnFailureListener(e -> hwInitializing = false);
        } catch (Throwable t) {
            hwInitializing = false;
            Log.w("KeyS", "Handwriting init failed", t);
        }
    }

    private void createHwRecognizer(DigitalInkRecognitionModel model) {
        try {
            hwRecognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build());
            hwModelReady = true;
        } catch (Throwable t) {
            Log.w("KeyS", "Failed to create recognizer", t);
        }
        hwInitializing = false;
        // If the user is sitting in handwriting mode while we initialized,
        // refresh the candidate bar so the "Loading…" hint flips to "Draw…".
        hwUiHandler.post(() -> {
            try {
                if (mode == Mode.HANDWRITING && isAttachedToWindow()) updateCandidates();
            } catch (Throwable ignored) {}
        });
    }

    private void updateCandidates() {
        candidateBar.removeAllViews();
        boolean isChinese = (mode == Mode.PINYIN || mode == Mode.HANDWRITING);
        if (isChinese) {
            candidateScroll.setVisibility(VISIBLE);
            if (mode == Mode.PINYIN && pinyinBuffer.length() > 0) {
                TextView preedit = new TextView(getContext());
                preedit.setText(pinyinBuffer + "|");
                preedit.setTextColor(BG_ACCENT);
                preedit.setTypeface(null, android.graphics.Typeface.BOLD);
                preedit.setPadding(dp(4), 0, dp(12), 0);
                candidateBar.addView(preedit);

                if (pinyinCandidates.isEmpty()) {
                    TextView loading = new TextView(getContext());
                    loading.setText("Fetching…");
                    loading.setTextColor(cTextDim());
                    loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    candidateBar.addView(loading);
                } else {
                    for (String cand : pinyinCandidates) {
                        candidateBar.addView(makeCandidateView(cand));
                        if (candidateBar.getChildCount() > 30) break;
                    }
                }
            } else if (mode == Mode.HANDWRITING) {
                if (!hwCandidates.isEmpty()) {
                    for (String cand : hwCandidates) {
                        candidateBar.addView(makeCandidateView(cand));
                    }
                } else {
                    TextView hint = new TextView(getContext());
                    String label;
                    if (!hwModelReady)      label = "Loading handwriting…";
                    else if (hwHasAttempt)  label = "No match — try again";
                    else                    label = "Draw a character below...";
                    hint.setText(label);
                    hint.setTextColor(cTextDim());
                    hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    candidateBar.addView(hint);
                }
            } else {
                TextView hint = new TextView(getContext());
                hint.setText("Type pinyin...");
                hint.setTextColor(cTextDim());
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                candidateBar.addView(hint);
            }
            
        } else {
            candidateScroll.setVisibility(GONE);
        }
    }

    private View makeCandidateView(final String cand) {
        TextView tv = new TextView(getContext());
        tv.setText(cand);
        tv.setTextColor(cText());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setPadding(dp(12), 0, dp(12), 0);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> {
            pinyinFetchHandler.removeCallbacksAndMessages(null);
            cancelAutoCommit();
            listener.onText(cand);
            pinyinBuffer = "";
            pinyinCandidates.clear();
            hwCandidates.clear();
            hwHasAttempt = false;
            // Trigger clear in HandwritingView
            for (int i = 0; i < keyboardArea.getChildCount(); i++) {
                View hv = keyboardArea.getChildAt(i);
                if (hv instanceof HandwritingView) {
                    ((HandwritingView) hv).clear();
                    break;
                }
            }
            render();
        });
        return tv;
    }

    private void renderQwerty() {
        keyboardArea.addView(letterRow("1234567890", true)); // dedicated number row
        keyboardArea.addView(letterRow("qwertyuiop"));
        keyboardArea.addView(letterRow("asdfghjkl"));

        LinearLayout row3 = newRow();
        row3.addView(shiftKey(1.5f));
        for (char c : "zxcvbnm".toCharArray()) row3.addView(letterKey(String.valueOf(c)));
        row3.addView(backspaceKey(1.5f));
        keyboardArea.addView(row3);

        LinearLayout row4 = newRow();
        row4.addView(specialKey("123", 1.5f, v -> { prevAlphaMode = mode; mode = Mode.SYMBOLS; render(); }));
        row4.addView(specialKey("🌐", 1f, v -> cycleMode()));
        row4.addView(letterKey(","));
        row4.addView(spaceKey(5f));
        row4.addView(letterKey("."));
        row4.addView(specialKey("😀", 1f, v -> { prevAlphaMode = mode; mode = Mode.EMOJI; render(); }));
        row4.addView(accentKey("↵", 1.5f, v -> { listener.onEnter(); afterEnter(); }));
        keyboardArea.addView(row4);
    }

    private void renderSymbols() {
        if (symbolsPage == 2) { renderSymbols2(); return; }

        keyboardArea.addView(letterRow("1234567890", true));
        keyboardArea.addView(letterRow("@#$_&-+()/", true));

        LinearLayout row3 = newRow();
        row3.addView(specialKey("=\\<", 1.5f, v -> { symbolsPage = 2; render(); }));
        for (char c : "*\"':;!?".toCharArray()) {
            row3.addView(charKey(String.valueOf(c)));
        }
        row3.addView(backspaceKey(1.5f));
        keyboardArea.addView(row3);

        keyboardArea.addView(symbolsBottomRow());
    }

    private void renderSymbols2() {
        keyboardArea.addView(charRow("{}[]<>|^~`"));
        keyboardArea.addView(charRow("\\/=+%€£¥¢°"));

        LinearLayout row3 = newRow();
        row3.addView(specialKey("1/2", 1.5f, v -> { symbolsPage = 1; render(); }));
        for (char c : "!?~`|\\^".toCharArray()) {
            row3.addView(charKey(String.valueOf(c)));
        }
        row3.addView(backspaceKey(1.5f));
        keyboardArea.addView(row3);

        keyboardArea.addView(symbolsBottomRow());
    }

    private LinearLayout symbolsBottomRow() {
        LinearLayout row4 = newRow();
        row4.addView(specialKey("ABC", 1.5f, v -> { mode = prevAlphaMode; symbolsPage = 1; render(); }));
        row4.addView(specialKey("🌐", 1f, v -> cycleMode()));
        row4.addView(charKey(","));
        row4.addView(spaceKey(5f));
        row4.addView(charKey("."));
        row4.addView(specialKey("😀", 1f, v -> { prevAlphaMode = mode; mode = Mode.EMOJI; render(); }));
        row4.addView(accentKey("↵", 1.5f, v -> { listener.onEnter(); afterEnter(); }));
        return row4;
    }

    private void renderNumpad() {
        keyboardArea.addView(t9Row(
            t9Key("1", " ", new String[]{" ",".",",","?","!","1"}),
            t9Key("2", "ABC", new String[]{"a","b","c","2"}),
            t9Key("3", "DEF", new String[]{"d","e","f","3"}),
            backspaceKey(1f)
        ));
        keyboardArea.addView(t9Row(
            t9Key("4", "GHI", new String[]{"g","h","i","4"}),
            t9Key("5", "JKL", new String[]{"j","k","l","5"}),
            t9Key("6", "MNO", new String[]{"m","n","o","6"}),
            specialKey("😀", 1f, v -> { prevAlphaMode = mode; mode = Mode.EMOJI; render(); })
        ));
        keyboardArea.addView(t9Row(
            t9Key("7", "PQRS", new String[]{"p","q","r","s","7"}),
            t9Key("8", "TUV", new String[]{"t","u","v","8"}),
            t9Key("9", "WXYZ", new String[]{"w","x","y","z","9"}),
            shiftKey(1f)
        ));
        keyboardArea.addView(t9Row(
            specialKey("🌐", 1f, v -> cycleMode()), // globe is the only QWERTY↔T9 switch
            t9Key("0", "+", new String[]{"0","+"}),
            spaceKey(1.8f),
            specialKey("!?=", 1f, v -> { prevAlphaMode = mode; mode = Mode.SYMBOLS; symbolsPage = 1; render(); }),
            accentKey("↵", 1f, v -> { listener.onEnter(); afterEnter(); })
        ));
    }

    private void renderEmoji() {
        // Top: category tabs
        HorizontalScrollView tabsScroll = new HorizontalScrollView(getContext());
        tabsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(getContext());
        tabs.setOrientation(HORIZONTAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        final java.util.Map<String, String> catIcons = new java.util.HashMap<>();
        catIcons.put("Smileys", "😀"); catIcons.put("People", "👋");
        catIcons.put("Animals", "🐶"); catIcons.put("Food", "🍔");
        catIcons.put("Activities", "⚽"); catIcons.put("Travel", "🚗");
        catIcons.put("Objects", "📱"); catIcons.put("Symbols", "❤️");
        catIcons.put("Flags", "🚩");

        for (final String cat : EmojiData.CATEGORIES.keySet()) {
            String[] emojis = EmojiData.CATEGORIES.get(cat);
            String label = catIcons.containsKey(cat) ? catIcons.get(cat)
                         : (emojis != null && emojis.length > 0) ? emojis[0] : cat;
            Button tab = makeButton(label, cKey(), cText());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(40));
            lp.setMarginEnd(dp(4));
            tab.setLayoutParams(lp);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tab.setBackground(roundedBg(cat.equals(currentEmojiCategory) ? BG_ACCENT : cKeyDark()));
            tab.setOnClickListener(v -> { currentEmojiCategory = cat; render(); });
            tabs.addView(tab);
        }
        tabsScroll.addView(tabs);
        tabsScroll.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        keyboardArea.addView(tabsScroll);

        // Middle: scrollable grid (8 columns), scrollbar hidden
        ScrollView scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(VERTICAL);
        grid.setPadding(dp(2), dp(2), dp(2), dp(2));
        grid.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        String[] emojis = EmojiData.CATEGORIES.get(currentEmojiCategory);
        if (emojis != null) {
            final int cols = 8;
            LinearLayout row = null;
            for (int i = 0; i < emojis.length; i++) {
                if (i % cols == 0) {
                    row = new LinearLayout(getContext());
                    row.setOrientation(HORIZONTAL);
                    row.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
                    grid.addView(row);
                }
                final String em = emojis[i];
                Button cell = makeButton(em, Color.TRANSPARENT, cText());
                float emojiSp = Math.min(28, Math.max(16, kbdHeightDp * 0.085f));
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, emojiSp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                cell.setLayoutParams(lp);
                cell.setBackground(roundedBg(Color.TRANSPARENT));
                cell.setOnClickListener(v -> { listener.onText(em); haptic(v); });
                row.addView(cell);
            }
            // pad last row so cells stay equal width
            if (row != null) {
                int filled = emojis.length % cols;
                if (filled != 0) {
                    for (int i = filled; i < cols; i++) {
                        View spacer = new View(getContext());
                        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
                        row.addView(spacer);
                    }
                }
            }
        }
        scroll.addView(grid);
        keyboardArea.addView(scroll);

        // Bottom controls — fixed height so it always looks comfortable
        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        blp.setMargins(dp(2), dp(2), dp(2), dp(2));
        bottom.setLayoutParams(blp);
        String backLabel = prevAlphaMode == Mode.NUMPAD ? "123" : "ABC";
        bottom.addView(specialKey(backLabel, 1.5f, v -> { mode = prevAlphaMode; symbolsPage = 1; render(); }));
        bottom.addView(specialKey("space", 5f, v -> { listener.onText(" "); }));
        bottom.addView(backspaceKey(1f));
        bottom.addView(accentKey("↵", 1.5f, v -> listener.onEnter()));
        keyboardArea.addView(bottom);
    }

    // ---------- Row helpers ----------
    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout letterRow(String letters) { return letterRow(letters, false); }

    private LinearLayout letterRow(String letters, boolean literal) {
        LinearLayout row = newRow();
        for (char c : letters.toCharArray()) {
            String s = String.valueOf(c);
            if (literal) row.addView(charKey(s));
            else row.addView(letterKey(s));
        }
        return row;
    }

    private LinearLayout t9Row(View... keys) {
        LinearLayout row = newRow();
        for (View k : keys) row.addView(k);
        return row;
    }

    private LinearLayout charRow(String chars) {
        LinearLayout row = newRow();
        for (int i = 0; i < chars.length(); ) {
            int cp = chars.codePointAt(i);
            row.addView(charKey(new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
        return row;
    }

    // ---------- Key factories ----------
    private Button baseKey(String text, int bg, float weight) {
        Button b = makeButton(text, bg, cText());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setBackground(roundedBg(bg));
        return b;
    }

    private Button shiftKey(float weight) {
        boolean isUpper = shift || shiftLock;
        int bg = shiftLock ? BG_ACCENT : (shift ? 0xFF3B82F6 : cKeyDark());
        Button b = baseKey(shiftLock ? "⇪" : "⇧", bg, weight);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        if (shiftLock) {
            // White outline to signal caps lock
            GradientDrawable d = roundedBg(bg);
            d.setStroke(dp(2), 0xFFFFFFFF);
            b.setBackground(d);
        }
        b.setOnClickListener(v -> {
            haptic(v);
            // 3-state: off → shift → caps lock → off
            if (!shift && !shiftLock)      { shift = true;  shiftLock = false; }
            else if (shift && !shiftLock)  { shift = false; shiftLock = true;  }
            else                           { shift = false; shiftLock = false;  }
            render();
        });
        return b;
    }

    private Button letterKey(final String letter) {
        boolean isUpper = shift || shiftLock;
        final String display = isUpper ? letter.toUpperCase() : letter;
        Button b = baseKey(display, cKey(), 1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        addKeyPreview(b, display);
        b.setOnClickListener(v -> {
            if (mode == Mode.PINYIN) {
                pinyinBuffer += letter.toLowerCase();
                pinyinCandidates.clear();
                schedulePinyinFetch();
                render();
                return;
            }
            boolean upper = shift || shiftLock;
            String out = upper ? letter.toUpperCase() : letter;
            listener.onText(out);
            if (letter.equals(".")) listener.onText(" ");
            haptic(v);
            resetT9();
            boolean changed = (shift && !shiftLock);
            if (changed) shift = false;
            if (autoCapCheck() || changed) render();
        });
        return b;
    }

    private Button spaceKey(float weight) {
        String label;
        if (mode == Mode.PINYIN) label = "拼音 Pinyin";
        else if (mode == Mode.HANDWRITING) label = "手写 Handwriting";
        else if (mode == Mode.NUMPAD) label = "九宫格 T9";
        else label = "English";

        Button b = baseKey(label, cKeyDark(), weight);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        b.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private int targetIdx;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        startX = event.getRawX();
                        spaceInSwipeMode = false;
                        targetIdx = chineseModeIdx;
                        v.setPressed(true);
                        haptic(v);
                        // Show carousel only after holding 300 ms
                        spaceHoldHandler.removeCallbacks(spaceHoldRunnable);
                        final View anchor = v;
                        spaceHoldRunnable = () -> {
                            spaceInSwipeMode = true;
                            createAndShowModePopup(anchor);
                        };
                        spaceHoldHandler.postDelayed(spaceHoldRunnable, 300);
                        return true;
                    }

                    case MotionEvent.ACTION_MOVE: {
                        if (!spaceInSwipeMode) return true;
                        float dx = event.getRawX() - startX;
                        int dir = dx > dp(30) ? 1 : dx < -dp(30) ? -1 : 0;
                        targetIdx = (chineseModeIdx + dir + CHINESE_MODE_COUNT) % CHINESE_MODE_COUNT;
                        updateModePopup(targetIdx, dx);
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        spaceHoldHandler.removeCallbacks(spaceHoldRunnable);
                        dismissModePopup();
                        if (!spaceInSwipeMode) {
                            // Quick tap — commit space / pinyin
                            if (mode == Mode.PINYIN && pinyinBuffer.length() > 0) {
                                String toCommit = pinyinCandidates.isEmpty() ? pinyinBuffer : pinyinCandidates.get(0);
                                listener.onText(toCommit);
                                pinyinBuffer = "";
                                pinyinCandidates.clear();
                                pinyinFetchHandler.removeCallbacksAndMessages(null);
                                render();
                            } else {
                                listener.onText(" ");
                                afterChar();
                            }
                        } else if (targetIdx != chineseModeIdx) {
                            // Held + swiped — switch mode, no space
                            cancelAutoCommit();
                            chineseModeIdx = targetIdx;
                            mode = chineseModeAt(chineseModeIdx);
                            render();
                        }
                        // Held but released at center → dismiss only, no space
                        spaceInSwipeMode = false;
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        spaceHoldHandler.removeCallbacks(spaceHoldRunnable);
                        dismissModePopup();
                        spaceInSwipeMode = false;
                        return true;
                }
                return false;
            }
        });
        return b;
    }

    /** Build and show the carousel popup above the spacebar. Called after 300 ms hold. */
    private void createAndShowModePopup(View anchor) {
        dismissModePopup();

        String englishLabel = (lastEnglishMode == Mode.NUMPAD) ? "九宫格 T9" : "English";
        String[] names = {englishLabel, "拼音 Pinyin", "手写 Handwriting"};

        // Card background
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(darkTheme ? 0xED0F172A : 0xEDF1F5F9);
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);

        // Sliding track holds the 3 slots
        modeSwipeTrack = new LinearLayout(getContext());
        modeSwipeTrack.setOrientation(LinearLayout.HORIZONTAL);
        modeSwipeTrack.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < 3; i++) {
            TextView slot = new TextView(getContext());
            slot.setText(names[i]);
            slot.setTextSize(TypedValue.COMPLEX_UNIT_SP, i == chineseModeIdx ? 15 : 13);
            slot.setTypeface(null, i == chineseModeIdx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            slot.setTextColor(i == chineseModeIdx ? 0xFFFFFFFF : (darkTheme ? 0x66FFFFFF : 0x66000000));
            slot.setPadding(dp(14), dp(8), dp(14), dp(8));
            slot.setMinWidth(dp(100));
            slot.setGravity(Gravity.CENTER);
            if (i == chineseModeIdx) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(BG_ACCENT);
                pill.setCornerRadius(dp(14));
                slot.setBackground(pill);
            }
            modeSlots[i] = slot;
            modeSwipeTrack.addView(slot);
        }
        card.addView(modeSwipeTrack);

        card.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        modeSwipePopup = new PopupWindow(card,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        modeSwipePopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        modeSwipePopup.setOutsideTouchable(false);
        modeSwipePopup.setFocusable(false);

        int popW = card.getMeasuredWidth();
        int popH = card.getMeasuredHeight();
        int xOff = (anchor.getWidth() - popW) / 2;
        int yOff = -(popH + anchor.getHeight() + dp(8));
        modeSwipePopup.showAsDropDown(anchor, xOff, yOff, Gravity.NO_GRAVITY);
    }

    /** Update which slot is highlighted and slide the track. Called on ACTION_MOVE. */
    private void updateModePopup(int targetIdx, float dx) {
        if (modeSwipePopup == null || !modeSwipePopup.isShowing()) return;

        for (int i = 0; i < 3; i++) {
            boolean active = (i == targetIdx);
            modeSlots[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? 15 : 13);
            modeSlots[i].setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            modeSlots[i].setTextColor(active ? 0xFFFFFFFF : (darkTheme ? 0x66FFFFFF : 0x66000000));
            if (active) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(BG_ACCENT);
                pill.setCornerRadius(dp(14));
                modeSlots[i].setBackground(pill);
            } else {
                modeSlots[i].setBackground(null);
            }
        }
        // Damped track slide — max ±dp(32)
        float slide = Math.max(-dp(32), Math.min(dp(32), dx * 0.22f));
        modeSwipeTrack.setTranslationX(slide);
    }

    private void dismissModePopup() {
        if (modeSwipePopup != null && modeSwipePopup.isShowing()) {
            modeSwipePopup.dismiss();
        }
        modeSwipePopup = null;
    }

    private void showKeyPreview(View anchor, String text) {
        hideKeyPreview();
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(cText());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundedBg(darkTheme ? 0xFF4B5563 : 0xFFCBD5E1));
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popW = Math.max(tv.getMeasuredWidth(), anchor.getWidth());
        int popH = tv.getMeasuredHeight();
        keyPreviewPopup = new PopupWindow(tv, popW, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyPreviewPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        keyPreviewPopup.setOutsideTouchable(false);
        keyPreviewPopup.setFocusable(false);
        int xOff = (anchor.getWidth() - popW) / 2;
        int yOff = -(popH + anchor.getHeight() + dp(4));
        keyPreviewPopup.showAsDropDown(anchor, xOff, yOff, Gravity.NO_GRAVITY);
    }

    private void hideKeyPreview() {
        if (keyPreviewPopup != null && keyPreviewPopup.isShowing()) keyPreviewPopup.dismiss();
        keyPreviewPopup = null;
    }

    private void schedulePinyinFetch() {
        pinyinFetchHandler.removeCallbacksAndMessages(null);
        pinyinFetchHandler.postDelayed(() -> {
            // Snapshot the buffer at fetch time. If the user has typed or
            // deleted by the time the network response returns, discard it —
            // otherwise stale candidates can overwrite fresh ones out of order.
            final String queryAtFetch = pinyinBuffer;
            GoogleInputEngine.getCandidates(queryAtFetch, candidates -> {
                if (!queryAtFetch.equals(pinyinBuffer)) return;
                pinyinCandidates.clear();
                pinyinCandidates.addAll(candidates);
                updateCandidates();
            });
        }, 200);
    }

    private void addKeyPreview(View key, String label) {
        key.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:  showKeyPreview(v, label); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: hideKeyPreview(); break;
            }
            return false;
        });
    }

    private void doBackspace() {
        cancelAutoCommit();
        if (mode == Mode.PINYIN && pinyinBuffer.length() > 0) {
            pinyinBuffer = pinyinBuffer.substring(0, pinyinBuffer.length() - 1);
            pinyinCandidates.clear();
            if (pinyinBuffer.length() > 0) schedulePinyinFetch();
            else pinyinFetchHandler.removeCallbacksAndMessages(null);
            if (!bkRepeating) render();
        } else {
            listener.onBackspace();
            if (!bkRepeating) resetT9();
        }
    }

    private Button backspaceKey(float weight) {
        Button b = baseKey("⌫", cKeyDark(), weight);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    haptic(v);
                    bkRepeating = false;
                    bkRepeatHandler.removeCallbacks(bkRepeatRunnable); // clear any stale repeat
                    doBackspace();   // first delete (may call render, but bkRepeating=false)
                    bkRepeatRunnable = new Runnable() {
                        @Override public void run() {
                            bkRepeating = true;   // suppress render inside doBackspace
                            doBackspace();
                            bkRepeatHandler.postDelayed(this, 40);
                        }
                    };
                    bkRepeatHandler.postDelayed(bkRepeatRunnable, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    boolean wasRepeating = bkRepeating;
                    bkRepeating = false;
                    bkRepeatHandler.removeCallbacks(bkRepeatRunnable);
                    bkRepeatRunnable = null;
                    if (wasRepeating) render(); // one final sync after rapid delete
                    return true;
            }
            return false;
        });
        return b;
    }

    private Button charKey(final String ch) {
        Button b = baseKey(ch, cKey(), 1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        addKeyPreview(b, ch);
        b.setOnClickListener(v -> {
            listener.onText(ch);
            haptic(v);
            resetT9();
            if (ch.equals(".")) {
                listener.onText(" ");
                boolean changed = (shift && !shiftLock);
                if (changed) shift = false;
                if (autoCapCheck() || changed) render();
            }
        });
        return b;
    }

    private Button specialKey(String label, float weight, final View.OnClickListener onClick) {
        Button b = baseKey(label, cKeyDark(), weight);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setOnClickListener(v -> { onClick.onClick(v); haptic(v); });
        return b;
    }

    private Button accentKey(String label, float weight, final View.OnClickListener onClick) {
        Button b = baseKey(label, BG_ACCENT, weight);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(v -> { onClick.onClick(v); haptic(v); });
        return b;
    }

    private View t9Key(final String label, final String sub, final String[] options) {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(VERTICAL);
        container.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        container.setLayoutParams(lp);
        container.setBackground(roundedBg(cKey()));
        container.setClickable(true);
        container.setFocusable(true);

        TextView main = new TextView(getContext());
        main.setText(label);
        main.setTextColor(cText());
        main.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        main.setGravity(Gravity.CENTER);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        container.addView(main);

        TextView subView = new TextView(getContext());
        subView.setText(sub);
        subView.setTextColor(cTextDim());
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        subView.setGravity(Gravity.CENTER);
        subView.setLetterSpacing(0.1f);
        container.addView(subView);

        // Short tap → multi-tap cycle through letters
        // Long press (≥500 ms) → type the digit directly, skip cycling
        // pdReceived guard prevents ghost-tap when mode switches on ACTION_DOWN
        final boolean[] longFired = {false};
        final boolean[] pdReceived = {false};
        final Runnable longRunnable = () -> {
            longFired[0] = true;
            listener.onText(label); // insert just the digit
            resetT9();
            haptic(container);
        };
        container.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pdReceived[0] = true;
                    longFired[0] = false;
                    t9Handler.postDelayed(longRunnable, 500);
                    break;
                case MotionEvent.ACTION_UP:
                    if (!pdReceived[0]) return true; // orphan UP from layout switch
                    pdReceived[0] = false;
                    t9Handler.removeCallbacks(longRunnable);
                    if (!longFired[0]) {
                        handleT9(label, options);
                        haptic(v);
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pdReceived[0] = false;
                    t9Handler.removeCallbacks(longRunnable);
                    break;
            }
            return true;
        });
        return container;
    }

    // ---------- T9 logic ----------
    private void handleT9(String keyId, String[] options) {
        if (keyId.equals(t9LastKey)) {
            t9Index = (t9Index + 1) % options.length;
            String ch = options[t9Index];
            if (shift && ch.length() == 1 && Character.isLetter(ch.charAt(0))) ch = ch.toUpperCase();
            listener.onReplaceLast(ch);
        } else {
            t9LastKey = keyId;
            t9Index = 0;
            String ch = options[0];
            if (shift && ch.length() == 1 && Character.isLetter(ch.charAt(0))) ch = ch.toUpperCase();
            listener.onText(ch);
            // Clear one-shot shift after the first letter of a new key sequence
            if (shift && !shiftLock) {
                shift = false;
                render();
            }
        }
        t9Handler.removeCallbacks(t9Reset);
        t9Handler.postDelayed(t9Reset, 900);
    }

    private void resetT9() {
        t9Handler.removeCallbacks(t9Reset);
        t9LastKey = null;
        t9Index = 0;
    }

    private boolean autoCapCheck() {
        if (!autoCapSentence || shift || shiftLock) return false;
        CharSequence before = listener.getTextBefore(4);
        String s = before.toString();
        if (s.isEmpty() || s.matches(".*[.?!\n]\\s*")) {
            shift = true;
            return true;
        }
        return false;
    }

    private void afterChar() {
        resetT9();
        boolean changed = (shift && !shiftLock);
        if (changed) shift = false;
        if (autoCapCheck() || changed) render();
    }

    private void afterEnter() {
        resetT9();
        if (shift && !shiftLock) shift = false;
        autoCapCheck();
        render();
    }

    private void cycleMode() {
        // Globe strictly toggles between QWERTY and T9 (3x4); no 3-way cycle
        mode = (mode == Mode.NUMPAD) ? Mode.QWERTY : Mode.NUMPAD;
        lastEnglishMode = mode;
        render();
    }

    // ---------- UI helpers ----------
    private Button makeButton(String text, int bg, int textColor) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        b.setIncludeFontPadding(false);
        b.setBackground(roundedBg(bg));
        return b;
    }

    private GradientDrawable roundedBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private void haptic(View v) {
        try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Handwriting View ----------
    private class HandwritingView extends View {
        private final List<Ink.Stroke.Builder> completedStrokes = new ArrayList<>();
        private Ink.Stroke.Builder activeStroke;
        private final android.graphics.Path drawPath = new android.graphics.Path();
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final Handler recognizeHandler = new Handler(Looper.getMainLooper());
        private float viewWidth, viewHeight;

        public HandwritingView(Context context) {
            super(context);
            // Required on some Android versions so the view registers as a
            // touch target and onTouchEvent is dispatched to this view.
            setClickable(true);
            setFocusableInTouchMode(true);
            paint.setAntiAlias(true);
            paint.setColor(cText());
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(5));
            setBackgroundColor(cKey());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            viewWidth = w;
            viewHeight = h;
        }

        public void clear() {
            completedStrokes.clear();
            activeStroke = null;
            drawPath.reset();
            recognizeHandler.removeCallbacksAndMessages(null);
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            recognizeHandler.removeCallbacksAndMessages(null);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawPath(drawPath, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            try {
                float x = event.getX();
                float y = event.getY();
                long t = event.getEventTime();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        activeStroke = Ink.Stroke.builder();
                        activeStroke.addPoint(Ink.Point.create(x, y, t));
                        drawPath.moveTo(x, y);
                        recognizeHandler.removeCallbacksAndMessages(null);
                        cancelAutoCommit();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (activeStroke != null) activeStroke.addPoint(Ink.Point.create(x, y, t));
                        drawPath.lineTo(x, y);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (activeStroke != null) {
                            activeStroke.addPoint(Ink.Point.create(x, y, t));
                            completedStrokes.add(activeStroke);
                            activeStroke = null;
                        }
                        recognizeHandler.postDelayed(this::runRecognition, 800);
                        break;
                }
                invalidate();
            } catch (Throwable t) {
                Log.w("KeyS", "HandwritingView touch error", t);
            }
            return true;
        }

        private void runRecognition() {
            if (mode != Mode.HANDWRITING) return;
            if (!hwModelReady || hwRecognizer == null || completedStrokes.isEmpty()) return;
            float w = viewWidth > 0 ? viewWidth : getWidth();
            float h = viewHeight > 0 ? viewHeight : getHeight();
            if (w <= 0 || h <= 0) {
                // Layout hasn't completed yet; retry on the next frame.
                recognizeHandler.postDelayed(this::runRecognition, 100);
                return;
            }
            try {
                Ink.Builder inkBuilder = Ink.builder();
                for (Ink.Stroke.Builder sb : completedStrokes) inkBuilder.addStroke(sb.build());
                RecognitionContext ctx = RecognitionContext.builder()
                    .setWritingArea(new WritingArea(w, h))
                    .build();
                hwRecognizer.recognize(inkBuilder.build(), ctx)
                    .addOnSuccessListener(result -> {
                        try {
                            if (!isAttachedToWindow()) return;
                            hwCandidates.clear();
                            for (RecognitionCandidate c : result.getCandidates()) {
                                String text = c.getText();
                                if (text != null && !text.trim().isEmpty()) hwCandidates.add(text);
                                if (hwCandidates.size() >= 15) break;
                            }
                            hwHasAttempt = true;
                            updateCandidates();
                            // Arm the 2-second auto-commit. Any new stroke, candidate
                            // tap, mode switch, or Clear cancels this timer.
                            if (!hwCandidates.isEmpty()) scheduleAutoCommit();
                        } catch (Throwable t) {
                            Log.w("KeyS", "Ink recognition handler failed", t);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w("KeyS", "Ink recognition failed: " + e.getMessage());
                        hwHasAttempt = true;
                        hwUiHandler.post(() -> { try { updateCandidates(); } catch (Throwable ignored) {} });
                    });
            } catch (Throwable t) {
                // Soft fail — log and let the next stroke try again. Do NOT
                // latch a disabled state; transient ML Kit hiccups (e.g. on the
                // very first call while the recognizer warms up) shouldn't kill
                // the feature for the whole session.
                Log.w("KeyS", "Handwriting recognition error (will retry)", t);
            }
        }
    }

}
