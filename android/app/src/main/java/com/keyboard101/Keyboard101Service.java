package com.keyboard101;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class Keyboard101Service extends InputMethodService implements KeyboardView.OnActionListener {

    private KeyboardView keyboardView;

    @Override
    public View onCreateInputView() {
        keyboardView = new KeyboardView(this);
        keyboardView.setOnActionListener(this);
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // Each time the keyboard appears for a new (or re-focused) field,
        // drop any pinyin still buffered from a previous session. Users
        // expect a fresh slate, not their last half-typed syllable.
        if (keyboardView != null) keyboardView.resetTransientState();
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null) return;
        ic.commitText(text, 1);
    }

    @Override
    public void onReplaceLast(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text == null) return;
        ic.deleteSurroundingText(1, 0);
        ic.commitText(text, 1);
    }

    @Override
    public void onBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    @Override
    public void onEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    @Override
    public void onSwitchIme() {
        switchToNextInputMethod(false);
    }

    @Override
    public CharSequence getTextBefore(int n) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence text = ic.getTextBeforeCursor(n, 0);
        return text != null ? text : "";
    }
}
