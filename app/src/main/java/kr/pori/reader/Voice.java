package kr.pori.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.Set;

final class Voice {
    enum State { STARTING, READY, PLAYING, PAUSED, MISSING, FAILED }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final TextToSpeech engine;
    State state = State.STARTING;
    private String text = "";
    private Runnable completed;
    private int version, offset, base;
    private boolean queued, closed;

    Voice(Context context, Runnable changed) {
        this.changed = changed;
        engine = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> initialize(status)));
    }

    private static boolean localKorean(android.speech.tts.Voice v) {
        return v != null && Locale.KOREAN.getLanguage().equals(v.getLocale().getLanguage())
                && !v.isNetworkConnectionRequired() && (v.getFeatures() == null
                || !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
    }

    private boolean selectVoice() {
        // Some engines only expose their language's default voice after setLanguage.
        if (engine.isLanguageAvailable(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE
                && engine.setLanguage(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE
                && localKorean(engine.getVoice())) return true;
        Set<android.speech.tts.Voice> voices = engine.getVoices();
        if (voices != null) {
            for (var v : voices) {
                if (localKorean(v) && engine.setVoice(v) == TextToSpeech.SUCCESS) return true;
            }
        }
        return false;
    }

    private void initialize(int status) {
        if (closed) return;
        state = State.MISSING;
        if (status == TextToSpeech.SUCCESS && selectVoice()) {
            engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { finish(id, State.READY, true); }
                @Override public void onError(String id) { finish(id, State.FAILED, false); }
                @Override public void onStop(String id, boolean interrupted) { finish(id, State.READY, false); }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    main.post(() -> {
                        if (current(id) && start >= 0 && base + start < text.length()) offset = base + start;
                    });
                }
            });
            state = State.READY;
        }
        boolean shouldPlay = queued && state == State.READY;
        queued = false;
        if (shouldPlay) play();
        else changed.run();
    }

    private boolean current(String id) { return !closed && Integer.toString(version).equals(id); }

    private void finish(String id, State result, boolean spokenToEnd) {
        main.post(() -> {
            if (!current(id) || state != State.PLAYING) return;
            state = result;
            Runnable done = completed;
            completed = null;
            if (spokenToEnd && done != null) done.run();
            changed.run();
        });
    }

    void speak(String message) { speak(message, null); }

    // Only a camera selection supplies a completion action. Guide and history playback have none.
    void speak(String message, Runnable onCompleted) {
        if (closed || message.isEmpty() || state == State.MISSING) return;
        text = message;
        completed = onCompleted;
        offset = 0;
        if (state == State.STARTING) { queued = true; return; }
        play();
    }

    private void play() {
        base = offset;
        Bundle options = new Bundle();
        options.putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true");
        state = State.PLAYING;
        if (engine.speak(text.substring(base), TextToSpeech.QUEUE_FLUSH, options,
                Integer.toString(++version)) != TextToSpeech.SUCCESS) {
            completed = null;
            state = State.FAILED;
        }
        changed.run();
    }

    void toggle() {
        if (state == State.PAUSED) play();
        else if (state == State.PLAYING) {
            ++version;
            state = State.PAUSED;
            engine.stop();
            changed.run();
        }
    }

    void stop() {
        queued = false;
        completed = null;
        ++version;
        if (state == State.PLAYING || state == State.PAUSED || state == State.FAILED) {
            engine.stop();
            state = State.READY;
        }
        changed.run();
    }

    void close() {
        closed = true;
        completed = null;
        ++version;
        engine.stop();
        engine.shutdown();
    }
}
