package kr.pori.reader;

import android.Manifest;
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

public class MainActivity extends ComponentActivity {
    private static final int PAPER=0xffF8F9F2, INK=0xff183E33, GREEN=0xff184F40, LIGHT=0xffE3ECD9, LIME=0xffD4F399;
    private static final String[] STEPS={
        "읽고 싶은 글을 휴대폰 카메라에 비춰 주세요.",
        "검지로 읽고 싶은 줄 바로 아래를 가리키고 잠깐 멈춰 주세요.",
        "포리가 그 줄만 읽어 줘요. 화면에서 원하는 줄을 눌러도 돼요."
    };
    private enum Page { GUIDE, PERMISSION, READER }
    private Page page;
    private int step;
    private boolean foreground;
    private LinearLayout root, content;
    private TextView status, selectedText, voiceHint;
    private Button pause, replay, stop;
    private Voice voice;
    private CameraReader camera;
    private PreviewView preview;
    private ReaderOverlay overlay;
    private FrameReader.Frame frame;
    private FrameReader.Line candidate, selected;
    private long since, noPointSince;
    private boolean armed=true;
    private String lastText="";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
        endCamera(); voice.stop(); page=target; pause=null; replay=null; stop=null; voiceHint=null;
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
        if (target==Page.READER) header.addView(button("사용법",false,()->guide(0)));
        else header.addView(label("Point & Read",16,false));
        if (target!=Page.READER) {
            ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            content=column(); content.setPadding(dp(6),dp(20),dp(6),dp(6)); scroll.addView(content);
        }
    }
    private void hint(LinearLayout parent) {
        voiceHint=label("",16,false); add(parent,voiceHint,4);
        voiceHint.setOnClickListener(v->{
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        voiceControls();
    }
    private void guide(int n) {
        screen(Page.GUIDE); step=n;
        add(content,label("사용법   "+(n+1)+" / 3",16,true),0);
        GuideArt art=new GuideArt(this); art.setBackground(shape(LIGHT,24));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(174)); p.topMargin=dp(12); content.addView(art,p);
        add(content,label(STEPS[n],27,true),20);
        pause=button("일시정지",false,voice::toggle);
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
        screen(Page.READER); selected=null; lastText=""; armed=true;
        FrameLayout lens=new FrameLayout(this); lens.setBackground(shape(INK,18)); lens.setClipToOutline(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,0,1); p.topMargin=dp(10); root.addView(lens,p);
        preview=new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        lens.addView(preview); overlay=new ReaderOverlay(this,this::tap); lens.addView(overlay);
        int limit=(int)(getResources().getDisplayMetrics().heightPixels*.46f);
        ScrollView scroll=new ScrollView(this) {
            protected void onMeasure(int w,int h) { super.onMeasure(w,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST)); }
        };
        root.addView(scroll,new LinearLayout.LayoutParams(-1,-2)); LinearLayout panel=column(); scroll.addView(panel);
        status=label("카메라를 준비하고 있어요.",17,false); add(panel,status,8);
        selectedText=label("읽을 줄을 가리켜 주세요",23,true); selectedText.setMaxLines(3);
        selectedText.setEllipsize(android.text.TextUtils.TruncateAt.END); add(panel,selectedText,6);
        pause=button("일시정지",false,voice::toggle); replay=button("다시 듣기",false,()->voice.speak(lastText));
        pair(panel,pause,replay); stop=button("읽기 중지",true,()->{voice.stop(); status.setText("읽기를 멈췄어요. 다른 줄을 가리켜 주세요.");});
        add(panel,stop,8); hint(panel); startCamera();
    }
    private void startCamera() {
        if (!foreground || camera!=null || page!=Page.READER || !allowed()) return;
        camera=new CameraReader(this,preview,new CameraReader.Listener(){
            public void frame(FrameReader.Frame value) { update(value); }
            public void error(String message) { frame=null; candidate=null; overlay.line=null; overlay.tip=null; overlay.invalidate(); status.setText(message); }
        });
        camera.start();
    }
    private void endCamera() { if (camera!=null) {camera.close(); camera=null;} frame=null; candidate=null; }
    private void tap(float x,float y) {
        FrameReader.Line line=frame==null?null:frame.nearest(x,y,false);
        if (line==null) { status.setText("글자 위를 눌러 주세요."); return; }
        choose(line);
    }
    private void choose(FrameReader.Line line) {
        selected=line; lastText=line.text; armed=false; noPointSince=0;
        selectedText.setText(line.text); overlay.line=line; overlay.invalidate(); voice.speak(line.text); voiceControls();
    }
    private void update(FrameReader.Frame f) {
        frame=f; long now=SystemClock.elapsedRealtime();
        overlay.tip=f.tip; overlay.line=null;
        if (selected!=null) for (var line:f.lines) {
            if (line.tracks(selected) && line.text.equals(lastText)) { selected=line; overlay.line=line; break; }
        }
        var point=f.tip==null?null:f.nearest(f.tip.x,f.tip.y,true);
        boolean locked = voice.state==Voice.State.PAUSED;
        if (point==null) {
            candidate=null;
            if (noPointSince==0) noPointSince=now;
            if (now-noPointSince>1300 && f.tip==null) armed=true;
        } else {
            noPointSince=0;
            if (!point.tracks(candidate) || !point.text.equals(candidate.text)) since=now;
            candidate=point;
            if (!locked && now-since>=450 && (armed || !point.tracks(selected))) choose(point);
        }
        if (voice.state==Voice.State.PLAYING) status.setText("선택한 줄을 읽고 있어요.");
        else if (locked) status.setText("잠시 멈췄어요.");
        else if (f.lines.isEmpty()) status.setText("글이 선명하게 보이도록 비춰 주세요.");
        else if (!f.handAvailable) status.setText("손 인식이 어려워요. 글줄을 눌러 주세요.");
        else if (point==null) status.setText("줄 바로 아래를 가리키거나 글줄을 눌러 주세요.");
        else status.setText(!armed && point.tracks(selected)?"다른 줄을 가리키거나 다시 들을 수 있어요.":"손끝을 잠깐 멈춰 주세요.");
        overlay.invalidate();
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
            voiceHint.setText(s==Voice.State.MISSING?"음성 설정 열기 · 한국어 오프라인 음성을 설치해 주세요.":"음성 재생에 실패했어요. 다시 듣거나 음성 설정을 확인해 주세요.");
        }
    }
    @Override protected void onResume() {
        super.onResume(); foreground=true;
        if (voice.state==Voice.State.MISSING || voice.state==Voice.State.FAILED) {
            voice.close(); voice=new Voice(this,this::voiceControls);
            if (page==Page.GUIDE) { guide(step); return; }
            if (page==Page.READER) { reader(); return; }
        }
        if (page==Page.PERMISSION && allowed()) reader();
        else if (page==Page.READER && !allowed()) permission(false);
        else startCamera();
    }
    @Override protected void onPause() { foreground=false; endCamera(); voice.stop(); super.onPause(); }
    @Override protected void onDestroy() { endCamera(); voice.close(); super.onDestroy(); }
}
