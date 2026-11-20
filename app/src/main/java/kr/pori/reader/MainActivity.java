package kr.pori.reader;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.camera.view.transform.OutputTransform;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final int PAPER=0xffF8F9F2, INK=0xff183E33, GREEN=0xff184F40, LIGHT=0xffE3ECD9, LIME=0xffD4F399;
    private static final String[] STEPS={
        "읽고 싶은 글을 휴대폰 카메라에 비춰 주세요.",
        "손바닥과 검지가 함께 보이게 해 주세요. 읽고 싶은 줄 바로 아래를 가리키고 잠깐 멈춰 주세요.",
        "포리가 그 줄만 읽어 줘요. 화면에서 글줄을 눌러도 돼요. 끝까지 들은 글은 ‘읽은 글’에 저장돼요."
    };
    private enum Page { GUIDE, PERMISSION, READER, HISTORY }
    private Page page;
    private int step;
    private boolean foreground, returningFromVoiceSettings;
    private LinearLayout root, content;
    private TextView status, selectedText, handStatus, voiceHint;
    private Button pause, replay, stop;
    private Voice voice;
    private CameraReader camera;
    private PreviewView preview;
    private ReaderOverlay overlay;
    private FrameReader.Frame frame;
    private FrameReader.Hand latestHand;
    private FrameReader.Line selected, point;
    private OutputTransform frameSource;
    private PointingSelection pointing;
    private long selectedSeen;
    private boolean selectionSaved;
    private String lastText = "", cameraError;
    private ReadingHistory history;
    private HistoryAdapter historyAdapter;
    private TextView historyMessage;
    private Button clearHistory;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        history = new ReadingHistory(this, () -> {
            if (!isDestroyed()) Toast.makeText(this, "기기에서 읽은 글 기록을 처리하지 못했어요.", Toast.LENGTH_LONG).show();
        });
        voice = new Voice(this,this::voiceControls);
        if (getPreferences(0).getBoolean("guideDone",false)) nextPage();
        else guide(0);
    }

    private int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private TextView label(String value,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(INK);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setLineSpacing(dp(2),1f); return t;
    }
    private Button button(String value,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(17); b.setMinWidth(0);
        b.setMinimumWidth(0); b.setMinHeight(dp(50)); b.setPadding(dp(10),dp(6),dp(10),dp(6));
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x332F6650),shape(primary?GREEN:LIGHT,14),null));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{0xff68766D,primary?0xffffffff:INK}));
        b.setOnClickListener(v->action.run()); return b;
    }
    private void add(LinearLayout parent,View v,int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(top); parent.addView(v,p);
    }
    private void pair(LinearLayout parent, Button a, Button b) {
        LinearLayout row=new LinearLayout(this); add(parent,row,8);
        row.addView(a,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); p.leftMargin=dp(8); row.addView(b,p);
    }
    private void screen(Page target) {
        endCamera();
        page=target; pause=null; replay=null; stop=null; voiceHint=null;
        status=null; selectedText=null; handStatus=null; overlay=null; historyAdapter=null;
        voice.stop();
        root=column(); root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(i.getSystemWindowInsetLeft()+dp(16),i.getSystemWindowInsetTop()+dp(8),
                i.getSystemWindowInsetRight()+dp(16),i.getSystemWindowInsetBottom()+dp(10)); return i;
        });
        setContentView(root);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); add(root,header,0);
        ImageView icon=new ImageView(this); icon.setImageResource(R.drawable.pori);
        header.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView word=label("포리",24,true); word.setPadding(dp(9),0,0,0);
        header.addView(word,new LinearLayout.LayoutParams(0,-2,1));
        if (target==Page.READER || target==Page.HISTORY) header.addView(button("사용법",false,()->guide(0)));
        else header.addView(label("Point & Read",16,false));
        if (target != Page.GUIDE) {
            boolean inHistory = target == Page.HISTORY;
            Button cameraTab = button("카메라", !inHistory, () -> {
                if (page == Page.HISTORY || (page == Page.READER && cameraError != null)) nextPage();
            });
            Button historyTab = button("읽은 글", inHistory, () -> { if (page != Page.HISTORY) showHistory(); });
            cameraTab.setSelected(!inHistory);
            historyTab.setSelected(inHistory);
            pair(root, cameraTab, historyTab);
        }
        if (target==Page.GUIDE || target==Page.PERMISSION) {
            ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            content=column(); content.setPadding(dp(6),dp(20),dp(6),dp(6)); scroll.addView(content);
        }
    }
    private void hint(LinearLayout parent) {
        voiceHint=button("",false,()->{
            returningFromVoiceSettings = true;
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        add(parent,voiceHint,4);
        voiceControls();
    }
    private void guide(int n) {
        screen(Page.GUIDE); step=n;
        add(content,label("사용법   "+(n+1)+" / 3",16,true),0);
        GuideArt art=new GuideArt(this); art.setBackground(shape(LIGHT,24));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(174)); p.topMargin=dp(12); content.addView(art,p);
        add(content,label(STEPS[n],27,true),20);
        pause=button("일시정지",false,()->voice.toggle());
        replay=button("다시 듣기",false,()->voice.speak(STEPS[step]));
        pair(content,pause,replay); hint(content);
        add(content,button(n==2?"읽기 시작":"다음",true,()->{ if (step<2) guide(step+1); else finishGuide(); }),18);
        add(content,button("건너뛰기",false,this::finishGuide),8);
        voice.speak(STEPS[n]);
    }
    private void finishGuide() { getPreferences(0).edit().putBoolean("guideDone",true).apply(); nextPage(); }
    private boolean allowed() { return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED; }
    private void nextPage() { if (allowed()) reader(); else permission(false); }
    private void permission(boolean rejected) {
        screen(Page.PERMISSION);
        String message="가리킨 글을 보려면 카메라가 필요해요. 카메라 화면은 휴대폰 안에서만 처리해요.";
        add(content,label("카메라 사용",30,true),12);
        add(content,label(message,23,false),18);
        boolean settings=rejected && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        if (rejected) add(content,label("읽기를 시작하려면 카메라 접근을 허용해 주세요.",18,false),12);
        add(content,button(settings?"설정에서 카메라 허용":"카메라 권한 요청",true,()->{
            if (settings) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
            else requestPermissions(new String[]{Manifest.permission.CAMERA},7);
        }),24);
        add(content,button("사용법 다시 보기",false,()->guide(0)),8);
        hint(content); voice.speak(message);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if (code==7) { if (allowed()) reader(); else permission(true); }
    }
    private void reader() {
        screen(Page.READER);
        selected = null;
        lastText = "";
        FrameLayout lens = new FrameLayout(this);
        lens.setBackground(shape(INK,18));
        lens.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1.35f);
        lp.topMargin = dp(10);
        root.addView(lens, lp);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        lens.addView(preview);
        overlay = new ReaderOverlay(this, preview, this::tap);
        lens.addView(overlay);

        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout panel = column();
        scroll.addView(panel);
        LinearLayout indicators = new LinearLayout(this);
        indicators.setGravity(Gravity.CENTER_VERTICAL);
        add(panel, indicators, 3);
        handStatus = label("손가락을 찾는 중",17,true);
        indicators.addView(handStatus,new LinearLayout.LayoutParams(0,-2,1));
        Switch marker = new Switch(this);
        marker.setText("손끝 표시");
        marker.setTextSize(16);
        marker.setTextColor(INK);
        marker.setSwitchPadding(dp(6));
        marker.setMinHeight(dp(48));
        marker.setChecked(getPreferences(0).getBoolean("showTip", true));
        overlay.showTip(marker.isChecked());
        marker.setOnCheckedChangeListener((v, on) -> {
            overlay.showTip(on);
            getPreferences(0).edit().putBoolean("showTip", on).apply();
        });
        indicators.addView(marker);
        status = label("카메라를 준비하고 있어요.",17,false);
        add(panel,status,0);
        selectedText = label("읽을 줄을 가리켜 주세요",23,true);
        selectedText.setMaxLines(3);
        selectedText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(panel,selectedText,4);
        hint(panel);
        pause = button("일시정지",false,()->voice.toggle());
        replay = button("다시 듣기",false,this::speakSelection);
        pair(panel,pause,replay);
        stop = button("읽기 중지",true,()->voice.stop());
        add(panel,stop,8);
        voiceControls();
        startCamera();
    }

    private void startCamera() {
        if (!foreground || camera != null || page != Page.READER || !allowed()) return;
        pointing = new PointingSelection();
        cameraError = null;
        camera = new CameraReader(this, preview, new CameraReader.Listener() {
            public void hand(FrameReader.Hand hand, OutputTransform source, long capturedAt) {
                latestHand = hand;
                pointing.hand(hand.tip, capturedAt);
                overlay.hand(hand.tip, source);
                String message;
                switch (hand.state) {
                    case FOUND: message = "검지 끝 인식됨"; break;
                    case UNAVAILABLE: message = "손 인식 사용 불가"; break;
                    default: message = "손가락을 찾는 중";
                }
                setLabel(handStatus, message);
            }
            public void frame(FrameReader.Frame value, OutputTransform source) { update(value, source); }
            public void error(String message) {
                frame = null; point = null; latestHand = null; cameraError = message;
                pointing.stable(null, SystemClock.elapsedRealtime());
                overlay.line(null, null); overlay.hand(null, null);
                setLabel(handStatus, "손가락 영상을 기다리는 중");
                setLabel(status, message);
            }
        });
        camera.start();
    }

    private void endCamera() {
        if (camera != null) { camera.close(); camera = null; }
        frame = null; frameSource = null; point = null; latestHand = null;
        if (overlay != null) { overlay.line(null, null); overlay.hand(null, null); }
    }

    private static void setLabel(TextView view, String message) {
        if (!android.text.TextUtils.equals(view.getText(), message)) view.setText(message);
    }

    private void tap(float x, float y) {
        if (frame == null || SystemClock.elapsedRealtime() - frame.capturedAt > 2000) {
            setLabel(status, "글자를 찾는 중이에요. 잠시 후 글줄을 눌러 주세요.");
            return;
        }
        FrameReader.Line line = frame.nearest(x, y, false);
        if (line == null) { setLabel(status, "읽고 싶은 글줄 위를 눌러 주세요."); return; }
        choose(line);
    }

    private void choose(FrameReader.Line line) {
        pointing.select(line);
        selected = line;
        selectedSeen = SystemClock.elapsedRealtime();
        lastText = line.text;
        selectionSaved = false;
        setLabel(selectedText, line.text);
        overlay.line(line, frameSource);
        speakSelection();
    }

    private void speakSelection() {
        if (lastText.isEmpty()) return;
        String sentence = lastText;
        voice.speak(sentence, () -> {
            if (!selectionSaved) {
                selectionSaved = true;
                history.add(sentence, System.currentTimeMillis());
            }
        });
        voiceControls();
    }

    private void update(FrameReader.Frame value, OutputTransform source) {
        cameraError = null;
        frame = value;
        frameSource = source;
        long now = SystemClock.elapsedRealtime();
        FrameReader.Line match = frame.match(selected);
        if (match != null) { selected = match; selectedSeen = now; }

        point = null;
        if (value.hand.tip != null && latestHand != null && latestHand.tip != null
                && now - value.capturedAt < 2000) {
            point = value.nearest(value.hand.tip.x, value.hand.tip.y, true);
            // A completed OCR result must still agree with the user's current fingertip.
            if (point != null && value.nearest(latestHand.tip.x, latestHand.tip.y, true) != point) point = null;
        }
        if (pointing.stable(point, value.capturedAt) && voice.state != Voice.State.PAUSED) choose(point);

        boolean speaking = voice.state == Voice.State.PLAYING || voice.state == Voice.State.PAUSED;
        FrameReader.Line visible = speaking && now - selectedSeen <= 650 ? selected : point;
        if (visible == null) visible = match;
        overlay.line(visible, source);
        readerStatus();
    }

    private void readerStatus() {
        if (page != Page.READER || status == null) return;
        String message;
        if (cameraError != null) message = cameraError;
        else if (voice.state == Voice.State.PLAYING) message = "선택한 줄을 읽고 있어요.";
        else if (voice.state == Voice.State.PAUSED) message = "잠시 멈췄어요.";
        else if (voice.state == Voice.State.MISSING && !lastText.isEmpty()) message = "줄을 찾았어요. 한국어 음성을 준비해 주세요.";
        else if (voice.state == Voice.State.FAILED) message = "음성이 재생되지 않았어요. 다시 듣기를 눌러 주세요.";
        else if (frame == null) message = "카메라를 준비하고 있어요.";
        else if (!frame.ocrAvailable) message = "글자 인식을 다시 시도하고 있어요.";
        else if (frame.lines.isEmpty()) message = "글자를 찾는 중이에요. 글이 선명하게 보이도록 비춰 주세요.";
        else if (latestHand == null || latestHand.tip == null) message = "손바닥과 검지를 함께 비추거나 글줄을 눌러 주세요.";
        else if (point == null) message = "손끝 가까이에 글이 없어요. 줄 바로 아래를 가리켜 주세요.";
        else if (voice.state == Voice.State.STARTING) message = "한국어 음성을 준비하고 있어요.";
        else if (point.tracks(selected)) message = "다른 줄을 가리키거나 다시 들을 수 있어요.";
        else message = "그 줄에서 손끝을 잠깐 멈춰 주세요.";
        setLabel(status, message);
    }

    private void showHistory() {
        screen(Page.HISTORY);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        add(root, actions, 12);
        TextView heading = label("읽은 글",25,true);
        actions.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        clearHistory = button("전체 삭제", false, () -> new AlertDialog.Builder(this)
                .setMessage("읽은 글을 모두 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("전체 삭제", (dialog, which) -> {
                    voice.stop();
                    history.clearEntries();
                    loadHistory();
                }).show());
        clearHistory.setEnabled(false);
        actions.addView(clearHistory);
        add(root, label("글을 누르면 다시 들을 수 있어요.",18,false), 8);
        historyMessage = label("기록을 불러오는 중이에요.",20,false);
        add(root, historyMessage, 16);
        ListView list = new ListView(this);
        list.setDivider(null);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        historyAdapter = new HistoryAdapter();
        list.setAdapter(historyAdapter);
        pause = button("일시정지",false,()->voice.toggle());
        stop = button("읽기 중지",false,()->voice.stop());
        pair(root,pause,stop);
        hint(root);
        loadHistory();
    }

    private void loadHistory() {
        HistoryAdapter adapter = historyAdapter;
        history.list(entries -> {
            if (isDestroyed() || adapter != historyAdapter) return;
            adapter.entries = entries;
            adapter.notifyDataSetChanged();
            clearHistory.setEnabled(!entries.isEmpty());
            historyMessage.setText("아직 읽은 글이 없어요");
            historyMessage.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private final class HistoryAdapter extends BaseAdapter {
        List<ReadingHistory.Entry> entries = Collections.emptyList();
        final DateFormat date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.KOREA);
        public int getCount() { return entries.size(); }
        public ReadingHistory.Entry getItem(int i) { return entries.get(i); }
        public long getItemId(int i) { return getItem(i).id; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            HistoryRow row = recycled == null ? new HistoryRow() : (HistoryRow) recycled;
            ReadingHistory.Entry entry = getItem(i);
            row.entry = entry;
            String time = date.format(new Date(entry.readAt));
            android.text.SpannableString label = new android.text.SpannableString(entry.sentence + "\n" + time);
            label.setSpan(new android.text.style.RelativeSizeSpan(.78f), entry.sentence.length() + 1,
                    label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.listen.setText(label);
            row.listen.setContentDescription(entry.sentence + ", " + time + ", 다시 듣기");
            row.remove.setContentDescription(entry.sentence + ", 기록 삭제");
            return row;
        }
    }

    private final class HistoryRow extends LinearLayout {
        ReadingHistory.Entry entry;
        final Button listen, remove;
        HistoryRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0,dp(5),0,dp(5));
            listen = button("", false, () -> voice.speak(entry.sentence));
            listen.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            listen.setTextSize(21);
            addView(listen, new LinearLayout.LayoutParams(0,-2,1));
            remove = button("삭제",false,()->{ history.remove(entry.id); loadHistory(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2);
            p.leftMargin = dp(8);
            addView(remove,p);
        }
    }

    private void voiceControls() {
        if (voice==null) return;
        var s=voice.state;
        if (pause!=null) {
            pause.setText(s==Voice.State.PAUSED?"계속 듣기":"일시정지");
            pause.setEnabled(s==Voice.State.PLAYING || s==Voice.State.PAUSED);
        }
        if (replay!=null) replay.setEnabled(s!=Voice.State.MISSING && (page==Page.GUIDE || !lastText.isEmpty()));
        if (stop!=null) stop.setEnabled(s==Voice.State.PLAYING || s==Voice.State.PAUSED);
        if (voiceHint!=null) {
            voiceHint.setVisibility(s==Voice.State.MISSING || s==Voice.State.FAILED?View.VISIBLE:View.GONE);
            voiceHint.setText(s==Voice.State.MISSING?"음성 설정 열기 · 한국어 오프라인 음성을 설치해 주세요.":"음성 설정 열기 · 재생에 사용할 한국어 음성을 확인해 주세요.");
        }
        readerStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground=true;
        if (returningFromVoiceSettings || voice.state==Voice.State.MISSING || voice.state==Voice.State.FAILED) {
            returningFromVoiceSettings = false;
            voice.close();
            voice=new Voice(this,this::voiceControls);
            if (page==Page.GUIDE) { guide(step); return; }
            if (page==Page.PERMISSION && !allowed()) { permission(false); return; }
            if (page==Page.READER && allowed()) { reader(); return; }
            voiceControls();
        }
        if (page==Page.PERMISSION && allowed()) reader();
        else if (page==Page.READER && !allowed()) permission(false);
        else startCamera();
    }
    @Override protected void onPause() { foreground=false; endCamera(); voice.stop(); super.onPause(); }
    @Override protected void onDestroy() { endCamera(); voice.close(); history.closeWhenIdle(); super.onDestroy(); }
}
