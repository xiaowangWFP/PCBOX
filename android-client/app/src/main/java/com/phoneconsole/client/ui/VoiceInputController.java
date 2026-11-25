package com.phoneconsole.client.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.R;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 触控板页底部的「按住说话」：按住开始语音识别，上滑取消，松手等待转写；
 * 识别结果以气泡展示，点文本或「发送」即把文字推到 PC 当前焦点窗口。
 */
public final class VoiceInputController {

    public interface Host {
        /** 把识别出的文字发送到 PC */
        void onVoiceText(@NonNull String text);

        /** 申请录音权限 */
        void onRequestRecordPermission();
    }

    private static final int MIN_RECORD_MS = 700;
    private static final int MAX_RECORD_MS = 60_000;
    private static final int CANCEL_DISTANCE_DP = 70;

    private final Context context;
    private final Host host;
    // 这些控件在字段初始化器的 Runnable 中被引用，不能声明为 blank final
    private TextView button;
    private TextView hint;
    private View resultPanel;
    private EditText resultText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private View rootView;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private boolean recording;
    private boolean awaitingResult;
    private boolean cancelled;
    private boolean cancelArmed;
    private long recordStartMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - recordStartMs;
            if (elapsed >= MAX_RECORD_MS) {
                stopRecording();
                return;
            }
            hint.setText(baseHint() + " · " + formatDuration(elapsed));
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable timeout = () -> {
        if (!awaitingResult) {
            return;
        }
        awaitingResult = false;
        destroyRecognizer();
        resetVoiceUi();
        hint.setVisibility(View.GONE);
        toast(R.string.touchpad_voice_empty);
    };

    public VoiceInputController(@NonNull Context context, @NonNull View root, @NonNull Host host) {
        this.context = context;
        this.host = host;
        this.button = root.findViewById(R.id.tpVoiceButton);
        this.hint = root.findViewById(R.id.tpVoiceHint);
        this.resultPanel = root.findViewById(R.id.tpVoiceResult);
        this.resultText = root.findViewById(R.id.tpVoiceText);

        bindButton();
        bindResultPanel(root);
    }

    public void onRecordPermissionResult(boolean granted) {
        toast(granted ? R.string.touchpad_voice_permission_ok : R.string.touchpad_voice_permission);
    }

    /** 释放全局布局监听、定时器与语音识别器，防止横竖屏重建时泄漏 */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        recording = false;
        awaitingResult = false;
        destroyRecognizer();
        if (rootView != null && layoutListener != null) {
            ViewTreeObserver observer = rootView.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(layoutListener);
            }
            layoutListener = null;
            rootView = null;
        }
    }

    // ---------------- 按住 / 上滑 ----------------

    private void bindButton() {
        if (button == null) {
            return;
        }
        final float[] downRawY = {0f};
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY[0] = event.getRawY();
                    if (!hasPermission()) {
                        host.onRequestRecordPermission();
                        return true;
                    }
                    startRecording();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (recording) {
                        float distance = downRawY[0] - event.getRawY();
                        setCancelArmed(distance > dp(CANCEL_DISTANCE_DP));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (recording) {
                        if (cancelArmed) {
                            cancelRecording();
                        } else {
                            stopRecording();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (recording) {
                        cancelRecording();
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void bindResultPanel(View root) {
        if (resultPanel == null) {
            return;
        }
        View send = root.findViewById(R.id.tpVoiceSend);
        View close = root.findViewById(R.id.tpVoiceClose);
        if (send != null) {
            send.setOnClickListener(v -> sendPendingText());
        }
        if (close != null) {
            close.setOnClickListener(v -> {
                if (resultText != null) {
                    resultText.setText("");
                }
                hideKeyboard();
                resultPanel.setTranslationY(0f);
                resultPanel.setVisibility(View.GONE);
            });
        }
        setupKeyboardAvoidance(root);
    }

    /** 键盘弹出时把结果条抬到键盘上方，避免编辑时被遮住 */
    private void setupKeyboardAvoidance(View root) {
        rootView = root.getRootView();
        layoutListener = () -> {
            if (resultPanel == null || resultPanel.getVisibility() != View.VISIBLE || rootView == null) {
                return;
            }
            Rect rect = new Rect();
            rootView.getWindowVisibleDisplayFrame(rect);
            int hidden = rootView.getHeight() - rect.bottom;
            float target = hidden > dp(120) ? -(hidden - dp(12)) : 0f;
            resultPanel.setTranslationY(target);
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void hideKeyboard() {
        if (resultText == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(resultText.getWindowToken(), 0);
        }
    }

    // ---------------- 识别流程 ----------------

    private void startRecording() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            toast(R.string.touchpad_voice_unavailable);
            return;
        }
        destroyRecognizer();
        cancelled = false;
        cancelArmed = false;
        recordStartMs = SystemClock.elapsedRealtime();

        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                handler.post(() -> handleError(error));
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = matches == null || matches.isEmpty() ? "" : matches.get(0);
                handler.post(() -> finishWithText(text));
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // 优先使用设备本地识别，避免没网时直接报网络错误
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60000L);
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            destroyRecognizer();
            toast(R.string.touchpad_voice_unavailable);
            return;
        }

        recording = true;
        awaitingResult = false;
        button.setText(R.string.touchpad_voice_release);
        button.setBackgroundResource(R.drawable.bg_voice_button_active);
        hint.setText(baseHint());
        hint.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        hint.setVisibility(View.VISIBLE);
        if (resultPanel != null) {
            resultPanel.setVisibility(View.GONE);
        }
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 500);
    }

    private void stopRecording() {
        if (!recording) {
            return;
        }
        long elapsed = SystemClock.elapsedRealtime() - recordStartMs;
        recording = false;
        handler.removeCallbacks(ticker);
        if (elapsed < MIN_RECORD_MS) {
            cancelRecording();
            toast(R.string.touchpad_voice_too_short);
            return;
        }
        awaitingResult = true;
        button.setText(R.string.touchpad_voice_recognizing);
        button.setBackgroundResource(R.drawable.bg_voice_button);
        hint.setText(R.string.touchpad_voice_recognizing);
        hint.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        hint.setVisibility(View.VISIBLE);
        if (recognizer != null) {
            recognizer.stopListening();
        }
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 8000);
    }

    private void cancelRecording() {
        cancelled = true;
        recording = false;
        awaitingResult = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(timeout);
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
                // 识别器已结束时忽略
            }
        }
        destroyRecognizer();
        resetVoiceUi();
        hint.setVisibility(View.GONE);
    }

    private void finishWithText(String text) {
        handler.removeCallbacks(timeout);
        awaitingResult = false;
        recording = false;
        destroyRecognizer();
        resetVoiceUi();

        String trimmed = text == null ? "" : text.trim();
        if (cancelled || trimmed.isEmpty()) {
            hint.setVisibility(View.GONE);
            if (!cancelled) {
                toast(R.string.touchpad_voice_empty);
            }
            return;
        }
        hint.setVisibility(View.GONE);
        if (resultText != null) {
            resultText.setText(trimmed);
            resultText.setSelection(trimmed.length());
        }
        if (resultPanel != null) {
            resultPanel.setTranslationY(0f);
            resultPanel.setVisibility(View.VISIBLE);
        }
    }

    private void handleError(int error) {
        android.util.Log.w("VOICE", "onError=" + error);
        if (cancelled) {
            return;
        }
        recording = false;
        awaitingResult = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(timeout);
        destroyRecognizer();
        resetVoiceUi();
        hint.setVisibility(View.GONE);

        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                toast(R.string.touchpad_voice_need_network);
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                toast(R.string.touchpad_voice_permission);
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                toast(R.string.touchpad_voice_busy);
                break;
            case SpeechRecognizer.ERROR_SERVER:
                toast(R.string.touchpad_voice_server_error);
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                toast(R.string.touchpad_voice_empty);
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                // 用户取消或识别器主动断开，不打扰
                break;
            default:
                Toast.makeText(context,
                        context.getString(R.string.touchpad_voice_failed, error),
                        Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void sendPendingText() {
        if (resultText == null) {
            return;
        }
        String text = resultText.getText().toString().trim();
        if (text.isEmpty()) {
            toast(R.string.touchpad_voice_empty_text);
            return;
        }
        host.onVoiceText(text);
        hideKeyboard();
        resultPanel.setTranslationY(0f);
        resultPanel.setVisibility(View.GONE);
        toast(R.string.touchpad_voice_sent);
    }

    // ---------------- 状态与工具 ----------------

    private void setCancelArmed(boolean armed) {
        if (cancelArmed == armed) {
            return;
        }
        cancelArmed = armed;
        if (button != null) {
            button.setBackgroundResource(armed
                    ? R.drawable.bg_voice_button_cancel
                    : R.drawable.bg_voice_button_active);
        }
        if (hint != null) {
            hint.setText(baseHint());
            hint.setTextColor(ContextCompat.getColor(context,
                    armed ? R.color.status_bad_text : R.color.text_secondary));
        }
    }

    private CharSequence baseHint() {
        return context.getString(cancelArmed
                ? R.string.touchpad_voice_release_cancel
                : R.string.touchpad_voice_cancel_hint);
    }

    private void resetVoiceUi() {
        if (button != null) {
            button.setText(R.string.touchpad_voice_hold);
            button.setBackgroundResource(R.drawable.bg_voice_button);
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
                // 已销毁
            }
            recognizer = null;
        }
    }

    private String formatDuration(long elapsedMs) {
        long seconds = Math.max(0, elapsedMs / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void toast(int resId) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }
}
