package kr.pori.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

final class Voice {
    enum State { STARTING, READY, PLAYING, PAUSED, MISSING, FAILED }
    final Handler main = new Handler(Looper.getMainLooper());
    final Runnable changed;
    TextToSpeech engine;
    State state = State.STARTING;
    String text = "", pending = "";
    int version, offset, base;
    boolean closed;
    Voice(Context context, Runnable changed) {
        this.changed = changed;
        engine = new TextToSpeech(context, status -> main.post(() -> initialize(status)));
    }
    private void initialize(int status) {
        if (closed) return;
        state = State.MISSING;
        if (status == TextToSpeech.SUCCESS && engine.getVoices() != null) {
            var voices = engine.getVoices();
            for (var voice : voices) {
                if (!voice.getLocale().getLanguage().equals(Locale.KOREAN.getLanguage()) ||
                    voice.isNetworkConnectionRequired() || (voice.getFeatures() != null && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))) continue;
                if (engine.setVoice(voice) == TextToSpeech.SUCCESS) { state = State.READY; break; }
            }
        }
        if (state == State.READY) {
            engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) {}
                public void onDone(String id) { finish(id, State.READY); }
                public void onError(String id) { finish(id, State.FAILED); }
                public void onRangeStart(String id, int start, int end, int frame) {
                    main.post(() -> { if (current(id)) offset = base + start; });
                }
            });
            if (!pending.isEmpty()) { String queued = pending; pending = ""; speak(queued); return; }
        }
        changed.run();
    }
    private boolean current(String id) { return !closed && Integer.toString(version).equals(id); }
    private void finish(String id, State result) {
        main.post(() -> { if (current(id)) { state = result; changed.run(); } });
    }
    void speak(String message) {
        if (closed || message.isEmpty()) return;
        text = message; offset = 0;
        if (state == State.STARTING) { pending = message; return; }
        if (state != State.MISSING) play();
    }
    private void play() {
        base = offset; state = State.PLAYING;
        if (engine.speak(text.substring(base), TextToSpeech.QUEUE_FLUSH, null, Integer.toString(++version)) == TextToSpeech.ERROR)
            state = State.FAILED;
        changed.run();
    }
    void toggle() {
        if (state == State.PAUSED) play();
        else if (state == State.PLAYING) { ++version; engine.stop(); state = State.PAUSED; changed.run(); }
    }
    void stop() {
        pending = ""; ++version;
        if (state == State.PLAYING || state == State.PAUSED || state == State.FAILED) { engine.stop(); state = State.READY; }
        changed.run();
    }
    void close() { closed = true; ++version; engine.stop(); engine.shutdown(); }
}
