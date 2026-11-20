package kr.pori.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ReadingHistory extends SQLiteOpenHelper {
    static final class Entry {
        final long id, readAt;
        final String sentence;
        Entry(long id, String sentence, long readAt) {
            this.id = id; this.sentence = sentence; this.readAt = readAt;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onError;

    ReadingHistory(Context context, Runnable onError) {
        super(context.getApplicationContext(),
                new File(context.getNoBackupFilesDir(), "reading-history.db").getAbsolutePath(), null, 1);
        this.onError = onError;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings (sentence TEXT NOT NULL, read_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported history schema");
    }

    private void run(Runnable work) {
        worker.execute(() -> {
            try { work.run(); }
            catch (SQLiteException e) { main.post(onError); }
        });
    }

    void add(String sentence, long readAt) {
        run(() -> {
            ContentValues values = new ContentValues();
            values.put("sentence", sentence);
            values.put("read_at", readAt);
            getWritableDatabase().insertOrThrow("readings", null, values);
        });
    }

    void list(Consumer<List<Entry>> loaded) {
        run(() -> {
            List<Entry> entries = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT rowid, sentence, read_at FROM readings ORDER BY read_at DESC, rowid DESC", null)) {
                while (c.moveToNext()) entries.add(new Entry(c.getLong(0), c.getString(1), c.getLong(2)));
            }
            main.post(() -> loaded.accept(entries));
        });
    }

    void remove(long id) {
        run(() -> getWritableDatabase().delete("readings", "rowid = ?", new String[]{Long.toString(id)}));
    }

    void clearEntries() { run(() -> getWritableDatabase().delete("readings", null, null)); }

    void closeWhenIdle() {
        worker.execute(super::close);
        worker.shutdown();
    }
}
