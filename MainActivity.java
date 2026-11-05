package com.aistudio.retroradio.kzvpwx;
import android.app.*; import android.content.*; import android.media.AudioManager; import android.net.Uri; import android.os.*; import android.view.*; import android.widget.*; import java.util.*;
public class MainActivity extends Activity {
    private CdView cd; private TextView titleT, subT, metaT, timeT, liveT, volT, displayT; private SeekBar progress; private DialView dial; private LinearLayout songList; private EditText searchText; private LinearLayout searchResults; private int songShowCount = 50; private String lastSongSig = "";
    private Button btnRadio, btnMusic, btnPlayAll, btnShuffle, btnPick, btnClear, btnSettings;
    private AudioManager am; private boolean seeking=false; private String mode="RADIO";
    private int langMode(){ try{ return getSharedPreferences("retro_tape_deck_prefs",0).getInt("ui_language_mode",0); }catch(Throwable t){ return 0; } }
    private String L(String ko, String en){ int m=langMode(); if(m==1) return ko; if(m==2) return en; return ko+" "+en; }
    private void applyLang(){ try{
        btnRadio.setText(L("라디오","Radio")); btnMusic.setText(L("내 음악","My Music")); btnPlayAll.setText(L("전체 재생","Play All")); btnShuffle.setText(L("셔플","Shuffle")+": "+(RadioService.shuffleOn?"ON":"OFF")); btnPick.setText("＋ "+L("음악 추가","Add Music")); btnClear.setText(L("목록 지우기","Clear")); btnSettings.setText(L("설정","Settings"));
    }catch(Throwable t){} }
    private final BroadcastReceiver upd = new BroadcastReceiver(){ public void onReceive(Context c, Intent i){
        try{
            String title=i.getStringExtra("title"); String sub=i.getStringExtra("sub"); String meta=i.getStringExtra("meta"); String err=i.getStringExtra("err"); mode=i.getStringExtra("mode");
            boolean playing=i.getBooleanExtra("playing",false);
            if(err!=null && err.length()>0 && !playing){ titleT.setText(title); subT.setText(sub); metaT.setText(err); }
            else if("RADIO".equals(mode) && meta!=null && meta.length()>=3){ titleT.setText(meta); subT.setText((title==null?"":title)+"  "+(sub==null?"":sub)); metaT.setText(L("지금 나오는 곡","Now playing")+": "+meta); }
            else { titleT.setText(title); subT.setText(sub); metaT.setText("RADIO".equals(mode)?L("메타데이터 오는 중","Waiting for metadata"):L("내 음악","My Music")); }
            cd.setLabel(title); cd.setPlaying(playing);
            ((Button)findViewById(R.id.btnPlay)).setText(playing?"❚❚\n"+L("일시정지","Pause"):"▶\n"+L("재생","Play"));
            liveT.setText("RADIO".equals(mode)?"LIVE":"MUSIC");
            int pos=i.getIntExtra("pos",0), dur=i.getIntExtra("dur",0);
            int track=i.getIntExtra("track",1), total=i.getIntExtra("total",0);
            timeT.setText(dur>0?fmt(pos)+" / "+fmt(dur):fmt(pos));
            if(displayT!=null){
                String state = playing ? "▶ PLAY" : "❚❚ PAUSED";
                if("MUSIC".equals(mode) && dur>0) displayT.setText(state+"  TRACK "+track+(total>0?" / "+total:"")+"  "+fmt(pos)+" / "+fmt(dur));
                else if("MUSIC".equals(mode)) displayT.setText(state+"  TRACK "+track+(total>0?" / "+total:""));
                else displayT.setText(state+"  RADIO P"+track+"  LIVE");
            }
            if(!seeking && dur>0){ progress.setMax(dur); progress.setProgress(pos); } else if(dur<=0){ progress.setMax(100); progress.setProgress(0); }
        }catch(Throwable t){}
    }};
    private static String fmt(int ms){ int s=ms/1000; return (s/60)+":"+String.format("%02d", s%60); }
    private void doPick(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("audio/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(Intent.createChooser(i,"Pick music"), 2001); }
    @Override protected void onActivityResult(int req, int res, Intent data){ super.onActivityResult(req,res,data); if(req!=2001||res!=RESULT_OK||data==null) return; try{
        java.util.List<Uri> uris=new java.util.ArrayList<>();
        if(data.getClipData()!=null){ for(int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri()); }
        else if(data.getData()!=null) uris.add(data.getData());
        if(uris.isEmpty()) return;
        SharedPreferences p=getSharedPreferences("retro_tape_deck_prefs",0);
        String us=p.getString("my_music_uris",""); String ns=p.getString("my_music_names","");
        ArrayList<String> uu=new ArrayList<>(), nn=new ArrayList<>();
        if(us.length()>0){ uu.addAll(Arrays.asList(us.split("\u001f",-1))); nn.addAll(Arrays.asList(ns.split("\u001f",-1))); }
        for(Uri u: uris){ try{ getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }catch(Throwable th){}
            String s=u.toString(); if(!uu.contains(s)){ uu.add(s); String nm=null; try{ android.database.Cursor cu=getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null); if(cu!=null){ try{ if(cu.moveToFirst()) nm=cu.getString(0); }finally{ cu.close(); } } }catch(Throwable th){} if(nm==null || nm.length()==0){ nm=u.getLastPathSegment(); if(nm!=null && nm.contains("/")) nm=nm.substring(nm.lastIndexOf('/')+1); try{ if(nm!=null && nm.contains("%2F")) nm=nm.substring(nm.lastIndexOf("%2F")+4); nm=java.net.URLDecoder.decode(nm, "UTF-8"); }catch(Throwable th){} } nn.add(nm==null?s:nm); } }
        p.edit().putString("my_music_uris", join(uu)).putString("my_music_names", join(nn)).apply();
        sendSvc("RELOAD", null); refreshSongList(); Toast.makeText(this, L("추가됨","Added")+" "+uris.size(),Toast.LENGTH_SHORT).show();
    }catch(Throwable th){ Toast.makeText(this, L("선택 실패","Pick failed"),Toast.LENGTH_SHORT).show(); } }
    private static String join(List<String> l){ StringBuilder b=new StringBuilder(); for(int i=0;i<l.size();i++){ if(i>0) b.append('\u001f'); b.append(l.get(i)); } return b.toString(); }
    @Override protected void onCreate(Bundle b){ super.onCreate(b);
        try{ if(Build.VERSION.SDK_INT>=28){ android.view.WindowManager.LayoutParams lp=getWindow().getAttributes(); lp.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER; getWindow().setAttributes(lp); } }catch(Throwable t){}
        setContentView(R.layout.activity_main);
        am=(AudioManager)getSystemService(AUDIO_SERVICE);
        cd=findViewById(R.id.cdview); titleT=findViewById(R.id.titleText); subT=findViewById(R.id.subText); metaT=findViewById(R.id.metaText); timeT=findViewById(R.id.timeText); liveT=findViewById(R.id.liveText); volT=findViewById(R.id.volText); displayT=findViewById(R.id.displayText); progress=findViewById(R.id.progress); dial=findViewById(R.id.dial); songList=findViewById(R.id.songList);
        btnRadio=findViewById(R.id.btnRadio); btnMusic=findViewById(R.id.btnTapes); btnPlayAll=findViewById(R.id.btnPlayAll); btnShuffle=findViewById(R.id.btnShuffle); btnPick=findViewById(R.id.btnPick); btnClear=findViewById(R.id.btnClear); btnSettings=findViewById(R.id.btnSettings);
        searchText=findViewById(R.id.searchText); searchResults=findViewById(R.id.searchResults);
        setBtn(R.id.btnSearch, v->{ String q=searchText.getText().toString().trim(); if(q.length()==0){ Toast.makeText(this, L("검색어를 입력하세요","Enter a search term"),Toast.LENGTH_SHORT).show(); return; } doRadioSearch(q, false); });
        setBtn(R.id.btnWorldTop, v->{ doRadioSearch("", true); });
        int max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); int cur=am.getStreamVolume(AudioManager.STREAM_MUSIC); dial.setVolume(cur,max); volT.setText(String.valueOf(cur));
        dial.setListener(v->{ am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); volT.setText(String.valueOf(v)); });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean u){} public void onStartTrackingTouch(SeekBar s){ seeking=true; } public void onStopTrackingTouch(SeekBar s){ seeking=false; Intent i=new Intent(MainActivity.this, RadioService.class); i.setAction("SEEK"); i.putExtra("pos", s.getProgress()); startSvc(i); } });
        setBtn(R.id.btnPlay, v->{ if(RadioService.playing){ sendSvc("PAUSE",null); cd.setPlaying(false); } else { if("MUSIC".equals(mode) && RadioService.musicUris.isEmpty()){ Toast.makeText(this, L("내 음악이 없어요, 음악 추가를 눌러주세요","No My Music, tap Add Music"),Toast.LENGTH_SHORT).show(); return; } sendSvc("PLAY_CURRENT",null); cd.setPlaying(true); } });
        setBtn(R.id.btnPrev, v->{ Intent it=new Intent(this, RadioService.class); it.setAction("PREV"); it.putExtra("mode", mode); startSvc(it); cd.setPlaying(true); });
        setBtn(R.id.btnNext, v->{ Intent it=new Intent(this, RadioService.class); it.setAction("NEXT"); it.putExtra("mode", mode); startSvc(it); cd.setPlaying(true); });
        setBtn(R.id.btnEject, v->{ sendSvc("STOP",null); cd.setPlaying(false); Toast.makeText(this, L("정지됨","Stopped"),Toast.LENGTH_SHORT).show(); });
        setBtn(R.id.btnRadio, v->{ mode="RADIO"; });
        setBtn(R.id.btnTapes, v->{ mode="MUSIC"; });
        setBtn(R.id.btnPlayAll, v->{ Intent i=new Intent(this, RadioService.class); i.setAction("PLAY_ALL"); startSvc(i); mode="MUSIC"; cd.setPlaying(true); applyLang(); });
        setBtn(R.id.btnShuffle, v->{ boolean on=!RadioService.shuffleOn; Intent i=new Intent(this, RadioService.class); i.setAction("SHUFFLE"); i.putExtra("on",on); startSvc(i); applyLang(); Toast.makeText(this, on?L("셔플 켜짐","Shuffle on"):L("셔플 꺼짐","Shuffle off"),Toast.LENGTH_SHORT).show(); });
        setBtn(R.id.btnPick, v->{ doPick(); });
        setBtn(R.id.btnClear, v->{ getSharedPreferences("retro_tape_deck_prefs",0).edit().putString("my_music_uris","").putString("my_music_names","").apply(); sendSvc("RELOAD",null); refreshSongList(); Toast.makeText(this, L("목록 지움","Cleared"),Toast.LENGTH_SHORT).show(); });
        setBtn(R.id.btnSettings, v->{ showSettings(); });
        int[] pids={R.id.p1,R.id.p2,R.id.p3,R.id.p4,R.id.p5};
        for(int i=0;i<pids.length;i++){ final int idx=i; setBtn(pids[i], v->{ Intent it=new Intent(this, RadioService.class); it.setAction("PLAY_RADIO"); it.putExtra("idx",idx); startSvc(it); mode="RADIO"; cd.setPlaying(true); }); }
        applyLang(); refreshSongList();
        try{ java.util.ArrayList<String> perms=new java.util.ArrayList<>(); if(Build.VERSION.SDK_INT>=33){ perms.add("android.permission.POST_NOTIFICATIONS"); perms.add("android.permission.READ_MEDIA_AUDIO"); } if(Build.VERSION.SDK_INT>=31){ perms.add("android.permission.BLUETOOTH_CONNECT"); } if(!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 1001); }catch(Throwable t){}
    }
    private void showSettings(){ try{
        int m=langMode(); String cur = m==0?L("한국어 + English","Korean + English"):m==1?"한국어":"English";
        new AlertDialog.Builder(this).setTitle(L("설정","Settings")).setMessage(L("언어","Language")+": "+cur+"\n\n"+L("안정성 완료: 백그라운드 재생, 오디오 포커스 복귀, 스킵/오류 시 다음 곡으로 이어짐, 블루투스 끊김에도 멈추지 않음","Stability done: background play, audio-focus resume, skip/error continues to next song, no stop on Bluetooth disconnect")).setPositiveButton(L("언어 바꾸기","Change Language"), (d,w)->{ int nm=(langMode()+1)%3; getSharedPreferences("retro_tape_deck_prefs",0).edit().putInt("ui_language_mode",nm).apply(); recreate(); }).setNegativeButton(L("닫기","Close"),null).show();
    }catch(Throwable t){} }
    private void setBtn(int id, View.OnClickListener l){ findViewById(id).setOnClickListener(l); }
    private void startSvc(Intent i){ try{ if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); }catch(Throwable t){ try{ startService(i); }catch(Throwable t2){} } }
    private void sendSvc(String act, Intent extra){ Intent i=new Intent(this, RadioService.class); i.setAction(act); startSvc(i); }
    private static String jsonStr(String json, String key, int from){
        try{ int k=json.indexOf("\""+key+"\"", from); if(k<0) return ""; int c=json.indexOf(':', k); if(c<0) return ""; int i=c+1; while(i<json.length() && (json.charAt(i)==' '||json.charAt(i)=='\n'||json.charAt(i)=='\r'||json.charAt(i)=='\t')) i++; if(i>=json.length() || json.charAt(i)!='"') return ""; StringBuilder b=new StringBuilder(); i++; while(i<json.length()){ char ch=json.charAt(i); if(ch=='\\' && i+1<json.length()){ char n=json.charAt(i+1); if(n=='u' && i+5<json.length()){ try{ b.append((char)Integer.parseInt(json.substring(i+2,i+6),16)); }catch(Throwable t){} i+=6; continue; } if(n=='n'){ b.append('\n'); i+=2; continue; } if(n=='t'){ b.append('\t'); i+=2; continue; } b.append(n); i+=2; continue; } if(ch=='"') break; b.append(ch); i++; } return b.toString(); }catch(Throwable t){ return ""; }
    }
    private void doRadioSearch(final String query, final boolean top){
        try{
            searchResults.removeAllViews(); TextView loading=new TextView(this); loading.setText(L("검색 중...","Searching...")); loading.setTextColor(0xFF333333); searchResults.addView(loading);
            new Thread(new Runnable(){ public void run(){
                final java.util.List<String[]> results=new java.util.ArrayList<>();
                try{
                    String urlStr = top ? "https://de1.api.radio-browser.info/json/stations/topvote/20" : "https://de1.api.radio-browser.info/json/stations/search?name="+java.net.URLEncoder.encode(query,"UTF-8")+"&limit=20&hidebroken=true&order=votes&reverse=true";
                    String resp = fetchUrl(urlStr);
                    parseStations(resp, results);
                    if(!top && results.isEmpty()){
                        String resp2 = fetchUrl("https://de1.api.radio-browser.info/json/stations/search?country="+java.net.URLEncoder.encode(query,"UTF-8")+"&limit=20&hidebroken=true&order=votes&reverse=true");
                        parseStations(resp2, results);
                    }
                    if(!top && results.isEmpty()){
                        String resp3 = fetchUrl("https://de1.api.radio-browser.info/json/stations/search?tag="+java.net.URLEncoder.encode(query,"UTF-8")+"&limit=20&hidebroken=true&order=votes&reverse=true");
                        parseStations(resp3, results);
                    }
                }catch(Throwable t){}
                final java.util.List<String[]> out=results;
                runOnUiThread(new Runnable(){ public void run(){
                    try{
                        searchResults.removeAllViews();
                        if(out.isEmpty()){ TextView t=new TextView(MainActivity.this); t.setText(L("결과 없음, 다른 이름으로 검색하세요","No results, try another name")); t.setTextColor(0xFF333333); searchResults.addView(t); Button retry=new Button(MainActivity.this); retry.setText(L("전세계 인기 라디오 보기","Show World Top")); retry.setOnClickListener(v->{ doRadioSearch("", true); }); searchResults.addView(retry); return; }
                        for(final String[] r: out){ Button b=new Button(MainActivity.this); String nm=r[0]; String ct=r[2]; b.setText("▶ "+nm+(ct.length()>0?"  ("+ct+")":"")); b.setOnClickListener(v->{ Intent it=new Intent(MainActivity.this, RadioService.class); it.setAction("PLAY_CUSTOM_RADIO"); it.putExtra("name", r[0]); it.putExtra("url", r[1]); it.putExtra("sub", r[2].length()>0?r[2]:"WORLD"); startSvc(it); mode="RADIO"; cd.setPlaying(true); }); searchResults.addView(b); }
                    }catch(Throwable t){}
                }});
            }}).start();
        }catch(Throwable t){ Toast.makeText(this, L("검색 실패","Search failed"),Toast.LENGTH_SHORT).show(); }
    }
    private String fetchUrl(String urlStr) throws Exception {
        java.net.URL u=new java.net.URL(urlStr); java.net.HttpURLConnection c=(java.net.HttpURLConnection)u.openConnection(); c.setRequestMethod("GET"); c.setRequestProperty("User-Agent","RetroCDRadio/2.23"); c.setRequestProperty("Accept","application/json"); c.setConnectTimeout(10000); c.setReadTimeout(10000); java.io.InputStream in=c.getInputStream(); java.io.ByteArrayOutputStream baos=new java.io.ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>=0) baos.write(buf,0,n); in.close(); return new String(baos.toByteArray(),"UTF-8");
    }
    private void parseStations(String resp, java.util.List<String[]> out){
        try{ if(resp==null||resp.length()==0) return; int idx=0; while(out.size()<20){ int n=resp.indexOf("\"name\"", idx); if(n<0) break; String nm=jsonStr(resp,"name",n); String url=jsonStr(resp,"url_resolved",n); if(url.length()==0) url=jsonStr(resp,"url",n); String ct=jsonStr(resp,"country",n); if(nm.length()>0 && url.length()>0 && url.startsWith("http")){ boolean dup=false; for(String[] r: out) if(r[1].equals(url)) dup=true; if(!dup) out.add(new String[]{nm,url,ct}); } idx=n+6; } }catch(Throwable t){}
    }
    private void refreshSongList(){ refreshSongListInternal(false); }
    private void refreshSongListInternal(boolean force){ SharedPreferences p=getSharedPreferences("retro_tape_deck_prefs",0); String us=p.getString("my_music_uris",""); String ns=p.getString("my_music_names","");
        String sig = us.length()+":"+us.hashCode()+":"+ns.hashCode();
        if(!force && sig.equals(lastSongSig) && songList.getChildCount()>0) return;
        boolean isNewList = !sig.equals(lastSongSig);
        lastSongSig = sig; if(isNewList) songShowCount = 50;
        songList.removeAllViews();
        if(us.length()==0){ TextView t=new TextView(this); t.setText(L("아직 내 음악이 없어요","No My Music yet.")); t.setTextColor(0xFF333333); songList.addView(t); Button ab=new Button(this); ab.setText("＋ "+L("음악 추가","Add Music")); ab.setOnClickListener(v->{ doPick(); }); songList.addView(ab); return; }
        String[] uu=us.split("\u001f",-1); String[] nn=ns.split("\u001f",-1);
        int shown=0;
        for(int i=0;i<uu.length && shown<songShowCount;i++){ if(uu[i].length()==0) continue; final int idx=i; Button b=new Button(this); b.setText("▶ "+(i<nn.length?nn[i]:uu[i])); b.setOnClickListener(v->{ Intent it=new Intent(this, RadioService.class); it.setAction("PLAY_MUSIC"); it.putExtra("idx",idx); startSvc(it); mode="MUSIC"; cd.setPlaying(true); }); songList.addView(b); shown++; }
        if(shown<uu.length){ final int remaining=uu.length-shown; Button more=new Button(this); more.setText(L("더 보기","Show more")+" ("+remaining+L("곡 남음"," left")+")"); more.setOnClickListener(v->{ songShowCount+=50; refreshSongListInternal(true); }); songList.addView(more); } }
    @Override protected void onResume(){ super.onResume(); registerReceiver(upd, new IntentFilter(RadioService.ACT_UPDATE), Context.RECEIVER_NOT_EXPORTED); refreshSongList(); applyLang(); }
    @Override protected void onPause(){ super.onPause(); try{ unregisterReceiver(upd); }catch(Throwable t){} }
}
