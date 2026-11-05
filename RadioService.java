package com.aistudio.retroradio.kzvpwx;
import android.app.*; import android.content.*; import android.media.*; import android.media.session.MediaSession; import android.media.session.PlaybackState; import android.net.Uri; import android.os.*; import android.provider.MediaStore; import android.content.ContentUris; import android.database.Cursor;
public class RadioService extends Service {
    public static final String ACT_UPDATE = "com.aistudio.retroradio.kzvpwx.UPDATE";
    public static final String[] STATION_NAMES = {"WGTS 91.9","BRIGHT-FM 95.1","K-LOVE 90.7","K-LOVE 107.5","FEBC Korea-Seoul FM 106.9"};
    public static final String[] STATION_URLS = {"https://wgts-live-a.edge.audiocdn.com/10320","https://wrbs-hls.streamguys1.com/Shine-FM","https://maestro.emfcdn.com/stream_for/k-love/tunein","https://maestro.emfcdn.com/stream_for/k-love/tunein","https://mlive2.febc.net/live/seoulfm/playlist.m3u8"};
    public static final String[] STATION_SUBS = {"USA","USA","USA","USA","SEOUL, SOUTH KOREA"};
    public static String mode = "RADIO"; public static int stationIndex = 0; public static String curTitle = "WGTS 91.9"; public static String curSub = "USA"; public static String radioMeta = ""; public static boolean isCustomRadio = false; public static String lastError = "";
    public static boolean playing = false; public static boolean shuffleOn = true;
    public static java.util.List<String> musicUris = new java.util.ArrayList<>(); public static java.util.List<String> musicNames = new java.util.ArrayList<>();
    public static java.util.List<Integer> order = new java.util.ArrayList<>(); public static int orderPos = 0;
    private static MediaPlayer player; private Handler handler = new Handler(Looper.getMainLooper());
    private int consecutiveFailures = 0; private long playStartMs = 0; private long lastAutoAdvanceMs = 0; private int autoAdvanceBurst = 0; private String curPlayUri = ""; private int curPlayMethod = 0; private int musicErrorRetries = 0; private boolean skipCacheOnce = false; private int curRealIndex = -1; private String lastMediaStoreUri = ""; private int playGen = 0; private int radioMetaGen = 0; private String curRadioUrl = ""; private int radioErrorRetries = 0; private String icyActiveUrl = ""; private java.net.HttpURLConnection icyConn = null; private static boolean playerPrepared = false; private boolean radioPreparing = false;
    private MediaSession mediaSession;
    private void ensureMediaSession(){ try{
        if(mediaSession!=null) return;
        mediaSession = new MediaSession(this, "RetroCDRadio");
        mediaSession.setCallback(new MediaSession.Callback(){
            public void onPlay(){ resume(); }
            public void onPause(){ pause(); }
            public void onStop(){ stopPlayback(); }
            public void onSkipToNext(){ try{ if("MUSIC".equals(mode)) nextMusic(false); else playRadio((stationIndex+1)%STATION_URLS.length); }catch(Throwable t){} broadcast(); }
            public void onSkipToPrevious(){ try{ if("MUSIC".equals(mode)) prevMusic(); else playRadio((stationIndex-1+STATION_URLS.length)%STATION_URLS.length); }catch(Throwable t){} broadcast(); }
        });
        mediaSession.setActive(true);
    }catch(Throwable t){} }
    private AudioManager am; private boolean wasPlayingBeforeLoss = false; private float savedVolume = 1.0f;
    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener(){
        public void onAudioFocusChange(int f){
            try{
                if(f==AudioManager.AUDIOFOCUS_LOSS){
                    wasPlayingBeforeLoss = false;
                    if(player!=null && player.isPlaying()) player.pause();
                    playing=false; broadcast();
                } else if(f==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
                    wasPlayingBeforeLoss = playing;
                    if(player!=null && player.isPlaying()) player.pause();
                    playing=false; broadcast();
                } else if(f==AudioManager.AUDIOFOCUS_GAIN){
                    try{ if(player!=null) player.setVolume(savedVolume, savedVolume); }catch(Throwable t){}
                    // 2.33: Resume only on transient loss (wasPlayingBeforeLoss=true).
                    // Permanent loss sets wasPlayingBeforeLoss=false in 2.27, so it
                    // will NOT auto-resume and fight another app. Transient loss
                    // (notification, etc.) SHOULD resume, otherwise radio stays
                    // paused after every notification and looks like stop/start.
                    if(wasPlayingBeforeLoss && player!=null && playerPrepared && !player.isPlaying()){
                        try{ player.start(); playing=true; startFg(); }catch(Throwable t){
                            try{ if("MUSIC".equals(mode)){ loadMusic(); if(!musicUris.isEmpty()) playMusicAtOrderPos(orderPos); } else if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); }catch(Throwable t2){}
                        }
                    }
                    wasPlayingBeforeLoss=false; broadcast();
                } else if(f==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
                    try{ if(player!=null) player.setVolume(0.2f, 0.2f); }catch(Throwable t){}
                }
            }catch(Throwable t){}
        }
    };
    private final Runnable ticker = new Runnable(){ public void run(){ broadcast(); handler.postDelayed(this, 1000); } };
    public static int getPosition(){ try{ return (player!=null && playerPrepared) ? player.getCurrentPosition() : 0; }catch(Throwable t){ return 0; } }
    public static int getDuration(){ try{ return (player!=null && playerPrepared) ? player.getDuration() : 0; }catch(Throwable t){ return 0; } }
    private void broadcast(){ Intent i=new Intent(ACT_UPDATE); try{ i.setPackage(getPackageName()); }catch(Throwable t){} i.putExtra("title",curTitle); i.putExtra("sub",curSub); i.putExtra("meta",radioMeta); i.putExtra("err",lastError); i.putExtra("playing",playing); i.putExtra("mode",mode); i.putExtra("pos",getPosition()); i.putExtra("dur",getDuration()); i.putExtra("track", "MUSIC".equals(mode) ? orderPos+1 : (isCustomRadio ? STATION_NAMES.length+1 : stationIndex+1)); i.putExtra("total", "MUSIC".equals(mode) ? order.size() : STATION_NAMES.length); sendBroadcast(i); }
    private void freshPlayer(){ playerPrepared=false; try{ if(player!=null){ try{ player.reset(); }catch(Throwable t){} try{ player.release(); }catch(Throwable t){} player=null; } }catch(Throwable t){ player=null; } ensurePlayer(); }
    @Override public IBinder onBind(Intent i){ return null; }
    @Override public void onCreate(){ super.onCreate(); am=(AudioManager)getSystemService(AUDIO_SERVICE); ensureMediaSession(); try{ String lm=getSharedPreferences("retro_tape_deck_prefs",0).getString("cd_last_mode","RADIO"); if("MUSIC".equals(lm)) mode="MUSIC"; }catch(Throwable t){} handler.post(ticker); loadMusic(); }
    @Override public void onDestroy(){ super.onDestroy(); handler.removeCallbacks(ticker); try{ if(mediaSession!=null) mediaSession.release(); }catch(Throwable t){} try{ if(am!=null) am.abandonAudioFocus(focusListener); }catch(Throwable t){} }
    private void loadMusic(){ try{ SharedPreferences p=getSharedPreferences("retro_tape_deck_prefs",0); String us=p.getString("my_music_uris",""); String ns=p.getString("my_music_names",""); shuffleOn=p.getBoolean("my_music_shuffle_on",true);
        musicUris.clear(); musicNames.clear(); if(us!=null && us.length()>0){ String[] uu=us.split("\u001f",-1); String[] nn=ns==null?new String[0]:ns.split("\u001f",-1); for(int i=0;i<uu.length;i++){ if(uu[i].length()>0){ musicUris.add(uu[i]); musicNames.add(i<nn.length?nn[i]:uu[i]); } } } rebuildOrder(); }catch(Throwable t){} }
    public static void rebuildOrder(){ order.clear(); for(int i=0;i<musicUris.size();i++) order.add(i); if(shuffleOn) java.util.Collections.shuffle(order); orderPos=0; }
    private void requestFocus(){ try{ if(am!=null) am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); }catch(Throwable t){} }
    private String parseMeta(byte[] b){
        try{
            if(b==null || b.length<4) return "";
            String raw = new String(b, "ISO-8859-1");
            int off=0; String title="", artist="";
            if(raw.startsWith("ID3")) off=10;
            int id3ver = b.length>3 ? (b[3]&0xFF) : 3;
            while(off+10<=b.length){
                String id = new String(b, off, 4, "ISO-8859-1");
                int size = id3ver>=4 ? (((b[off+4]&0x7F)<<21)|((b[off+5]&0x7F)<<14)|((b[off+6]&0x7F)<<7)|(b[off+7]&0x7F)) : (((b[off+4]&0xFF)<<24)|((b[off+5]&0xFF)<<16)|((b[off+6]&0xFF)<<8)|(b[off+7]&0xFF));
                if(size<=0 || off+10+size>b.length) break;
                if(id.equals("TIT2") || id.equals("TPE1") || id.equals("TXXX")){
                    int enc = b[off+10]&0xFF; int ts=off+11;
                    if(id.equals("TXXX")){ while(ts<off+10+size && b[ts]!=0) ts++; ts++; }
                    String txt="";
                    try{
                        if(enc==1) txt=new String(b, ts, off+10+size-ts, "UTF-16");
                        else if(enc==3) txt=new String(b, ts, off+10+size-ts, "UTF-8");
                        else txt=new String(b, ts, off+10+size-ts, "ISO-8859-1");
                    }catch(Throwable t){}
                    txt=txt.replace("\u0000","").trim();
                    if(id.equals("TIT2") && !txt.isEmpty()) title=txt;
                    if(id.equals("TPE1") && !txt.isEmpty()) artist=txt;
                    if(id.equals("TXXX") && !txt.isEmpty() && title.isEmpty()) title=txt;
                }
                off += 10+size;
            }
            if(!title.isEmpty()) return artist.isEmpty()?title:(artist+" - "+title);
            int st=raw.indexOf("StreamTitle=");
            if(st>=0){ int e=raw.indexOf(';', st); if(e<0) e=raw.length(); String v=raw.substring(st+12, e).trim(); if(v.length()>=3) return v; }
            StringBuilder cur=new StringBuilder(), best=new StringBuilder();
            for(int i=0;i<raw.length();i++){ char c=raw.charAt(i); if(c>=32 && c<127){ cur.append(c); if(cur.length()>best.length()) best=new StringBuilder(cur); } else cur.setLength(0); }
            String bs=best.toString().trim();
            if(bs.length()>=4 && !bs.equals(curTitle)) return bs;
        }catch(Throwable t){}
        return "";
    }
    private void ensurePlayer(){ if(player==null){ player=new MediaPlayer(); player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setVolume(1.0f,1.0f);
        player.setOnPreparedListener(mp->{ try{ playerPrepared=true; radioPreparing=false; mp.setVolume(savedVolume, savedVolume); if(!mp.isPlaying()) mp.start(); playing=true; lastError=""; playStartMs=System.currentTimeMillis(); consecutiveFailures=0; musicErrorRetries=0; radioErrorRetries=0; startFg(); broadcast(); }catch(Throwable t){} });
        player.setOnCompletionListener(mp->{ try{ if("MUSIC".equals(mode)){ long elapsed=System.currentTimeMillis()-playStartMs; if(elapsed<2000){ if(tryNextMethodForSameSong()) return; consecutiveFailures++; if(order.isEmpty() || orderPos>=order.size()-1 || consecutiveFailures>=order.size()){ consecutiveFailures=0; stopPlayback(); return; } final int g=playGen; handler.postDelayed(new Runnable(){ public void run(){ if(g!=playGen) return; nextMusic(true); } }, 200); } else { consecutiveFailures=0; nextMusic(true); } } else { playing=false; broadcast(); } }catch(Throwable t){} });
        player.setOnErrorListener((mp,w,e)->{ try{ playerPrepared=false; radioPreparing=false; if("MUSIC".equals(mode)){ lastError="재생 오류 "+w+"/"+e; if(musicErrorRetries<3 && curPlayUri!=null && curPlayUri.length()>0){ musicErrorRetries++; skipCacheOnce=true; playLocal(curPlayUri); return true; } musicErrorRetries=0; if(tryNextMethodForSameSong()) return true; consecutiveFailures++; if(order.isEmpty() || orderPos>=order.size()-1 || consecutiveFailures>=order.size()){ consecutiveFailures=0; stopPlayback(); return true; } final int g=playGen; handler.postDelayed(new Runnable(){ public void run(){ if(g!=playGen) return; nextMusic(true); } }, 200); } else { if(radioErrorRetries<2){ radioErrorRetries++; handler.postDelayed(new Runnable(){ public void run(){ try{ if("RADIO".equals(mode)){ if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); } }catch(Throwable t){} } }, 500); } else { radioErrorRetries=0; lastError="재생 오류 "+w+"/"+e; playing=false; broadcast(); } } }catch(Throwable t){} return true; });
        try{ player.setOnTimedMetaDataAvailableListener((mp, data)->{ try{ String m=parseMeta(data.getMetaData()); if(m!=null && m.length()>=3 && !m.equals(radioMeta) && !m.equals(curTitle)){ radioMeta=m; broadcast(); } }catch(Throwable t){} }); }catch(Throwable t){}
    } }
    private void startFg(){ try{ NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE); if(Build.VERSION.SDK_INT>=26){ try{ nm.deleteNotificationChannel("radio-media"); }catch(Throwable t){} NotificationChannel ch=new NotificationChannel("radio-media-quiet","Radio Controls",NotificationManager.IMPORTANCE_LOW); try{ ch.setSound(null,null); }catch(Throwable t){} try{ ch.enableVibration(false); }catch(Throwable t){} nm.createNotificationChannel(ch); }
        Intent pi=new Intent(this, MainActivity.class); PendingIntent cpi=PendingIntent.getActivity(this,0,pi,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent pa=new Intent(this, RadioService.class); pa.setAction("TOGGLE"); PendingIntent ppi=PendingIntent.getService(this,1,pa,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent na=new Intent(this, RadioService.class); na.setAction("NEXT"); PendingIntent npi=PendingIntent.getService(this,2,na,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent va=new Intent(this, RadioService.class); va.setAction("PREV"); PendingIntent vpi=PendingIntent.getService(this,3,va,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent all=new Intent(this, RadioService.class); all.setAction("PLAY_ALL"); PendingIntent api=PendingIntent.getService(this,4,all,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String nt = ("RADIO".equals(mode) && radioMeta!=null && radioMeta.length()>=3) ? radioMeta : curTitle;
        Notification.Builder b=new Notification.Builder(this,"radio-media-quiet").setContentTitle(nt).setContentText(curSub).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(cpi).addAction(android.R.drawable.ic_media_previous, "이전 Prev", vpi).addAction(android.R.drawable.ic_media_pause, playing?"일시정지 Pause":"재생 Play", ppi).addAction(android.R.drawable.ic_media_next, "다음 Next", npi).addAction(android.R.drawable.ic_media_play, "전체 재생 Play All", api).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC).setSound(null);
        try{ ensureMediaSession(); if(mediaSession!=null){
            mediaSession.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, nt).putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, curSub).build());
            mediaSession.setPlaybackState(new PlaybackState.Builder().setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED, getPosition(), 1.0f).setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_STOP).build());
            Notification.MediaStyle st=new Notification.MediaStyle(); st.setMediaSession(mediaSession.getSessionToken()); st.setShowActionsInCompactView(0,1,2); b.setStyle(st);
        } }catch(Throwable t){}
        Notification n=b.build();
        try{ if(Build.VERSION.SDK_INT>=29) startForeground(1,n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(1,n); }catch(Throwable t){ try{ startForeground(1,n); }catch(Throwable t2){} } }catch(Throwable t){} }
    @Override public int onStartCommand(Intent intent, int flags, int startId){
        try{ startFg(); }catch(Throwable t){}
        if(intent==null) return START_STICKY; String a=intent.getAction(); if(a==null) return START_STICKY;
        try{
            if(a.equals("PLAY_RADIO")){ int idx=intent.getIntExtra("idx",0); playRadio(idx); }
            else if(a.equals("PLAY_CUSTOM_RADIO")){ String nm=intent.getStringExtra("name"); String u=intent.getStringExtra("url"); String sb=intent.getStringExtra("sub"); if(nm!=null && u!=null && nm.length()>0 && u.length()>0) playCustomRadio(nm, u, sb==null?"WORLD":sb); }
            else if(a.equals("PLAY_MUSIC")){ loadMusic(); int idx=intent.getIntExtra("idx",0); playMusicIndex(idx); }
            else if(a.equals("PLAY_ALL")){ shuffleOn=false; consecutiveFailures=0; autoAdvanceBurst=0; getSharedPreferences("retro_tape_deck_prefs",0).edit().putBoolean("my_music_shuffle_on",false).apply(); loadMusic(); if(!musicUris.isEmpty()) playMusicAtOrderPos(0); }
            else if(a.equals("PLAY_ALL_SHUFFLE")){ shuffleOn=true; getSharedPreferences("retro_tape_deck_prefs",0).edit().putBoolean("my_music_shuffle_on",true).apply(); loadMusic(); if(!musicUris.isEmpty()) playMusicAtOrderPos(0); }
            else if(a.equals("PLAY_CURRENT")){ if("MUSIC".equals(mode)){ loadMusic(); if(!musicUris.isEmpty()) playMusicAtOrderPos(orderPos); } else if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); }
            else if(a.equals("TOGGLE")){ if(playing) pause(); else resume(); }
            else if(a.equals("PAUSE")) pause();
            else if(a.equals("RESUME")) resume();
            else if(a.equals("NEXT")){ String m=intent.getStringExtra("mode"); if("MUSIC".equals(m)||"RADIO".equals(m)) mode=m; if("MUSIC".equals(mode)) nextMusic(false); else playRadio((stationIndex+1)%STATION_URLS.length); }
            else if(a.equals("PREV")){ String m=intent.getStringExtra("mode"); if("MUSIC".equals(m)||"RADIO".equals(m)) mode=m; if("MUSIC".equals(mode)) prevMusic(); else playRadio((stationIndex-1+STATION_URLS.length)%STATION_URLS.length); }
            else if(a.equals("STOP")){ stopPlayback(); }
            else if(a.equals("SEEK")){ try{ if(player!=null && playerPrepared) player.seekTo(intent.getIntExtra("pos",0)); }catch(Throwable t){} }
            else if(a.equals("SHUFFLE")){ shuffleOn=intent.getBooleanExtra("on",true); getSharedPreferences("retro_tape_deck_prefs",0).edit().putBoolean("my_music_shuffle_on",shuffleOn).apply(); rebuildOrder(); }
            else if(a.equals("RELOAD")) loadMusic();
        }catch(Throwable t){}
        broadcast(); return START_STICKY;
    }
    private void setRadioDataSource(String url){ try{ player.setDataSource(url); }catch(Throwable t){ throw new RuntimeException(t); } }
    private void playRadio(int idx){ mode="RADIO"; boolean sameStation = !isCustomRadio && stationIndex==idx && curRadioUrl!=null && curRadioUrl.equals(STATION_URLS[idx]); if(sameStation && radioPreparing) return; isCustomRadio=false; try{ getSharedPreferences("retro_tape_deck_prefs",0).edit().putString("cd_last_mode","RADIO").apply(); }catch(Throwable t){} stationIndex=idx; curTitle=STATION_NAMES[idx]; curSub=STATION_SUBS[idx]; if(!sameStation) radioMeta=""; curRadioUrl=STATION_URLS[idx]; lastError=""; freshPlayer(); requestFocus(); startFg(); try{ setRadioDataSource(STATION_URLS[idx]); radioPreparing=true; player.prepareAsync(); playing=true; startFg(); startIcyMeta(STATION_URLS[idx]); }catch(Throwable t){ radioPreparing=false; playing=false; lastError="라디오 재생 실패"; } broadcast(); }
    private void playCustomRadio(String nm, String u, String sb){ mode="RADIO"; boolean sameStation = isCustomRadio && curRadioUrl!=null && curRadioUrl.equals(u); if(sameStation && radioPreparing) return; isCustomRadio=true; try{ getSharedPreferences("retro_tape_deck_prefs",0).edit().putString("cd_last_mode","RADIO").apply(); }catch(Throwable t){} stationIndex=STATION_NAMES.length; curTitle=nm; curSub=sb; if(!sameStation) radioMeta=""; curRadioUrl=u; lastError=""; freshPlayer(); requestFocus(); startFg(); try{ setRadioDataSource(u); radioPreparing=true; player.prepareAsync(); playing=true; startFg(); startIcyMeta(u); }catch(Throwable t){ radioPreparing=false; playing=false; lastError="라디오 재생 실패"; } broadcast(); }
    private void startIcyMeta(final String url){
        // 2.28: ICY second connection removed on purpose. It doubled data and
        // caused WGTS (audiocdn) to drop/skip, while the Muse app with no ICY
        // played WGTS fine. Timed metadata (HLS) still works.
        return;
        /*
        try{
            if(url==null || url.contains(".m3u8")) return;
            if(url.equals(icyActiveUrl) && icyConn!=null) return;
            try{ if(icyConn!=null) icyConn.disconnect(); }catch(Throwable t){}
            icyConn=null; icyActiveUrl=url; radioMetaGen++; final int gen=radioMetaGen;
            new Thread(new Runnable(){ public void run(){
                try{
                    java.net.URL u=new java.net.URL(url); java.net.HttpURLConnection c=(java.net.HttpURLConnection)u.openConnection(); icyConn=c; c.setRequestMethod("GET"); c.setRequestProperty("Icy-MetaData","1"); c.setRequestProperty("User-Agent","RetroCDRadio/2.26"); c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    String mi=c.getHeaderField("icy-metaint"); if(mi==null) mi=c.getHeaderField("Icy-Metaint"); if(mi==null) return; int metaint=Integer.parseInt(mi.trim()); if(metaint<=0) return;
                    java.io.InputStream in=c.getInputStream(); byte[] buf=new byte[8192]; int remaining=metaint;
                    while(gen==radioMetaGen){
                        if(remaining>0){ int toRead=Math.min(buf.length, remaining); int n=in.read(buf,0,toRead); if(n<0) break; remaining-=n; continue; }
                        int lb=in.read(); if(lb<0) break; int metaLen=lb*16; remaining=metaint;
                        if(metaLen>0){ byte[] mb=new byte[metaLen]; int read=0; while(read<metaLen){ int n=in.read(mb,read,metaLen-read); if(n<0) break; read+=n; } String ms=new String(mb,"UTF-8"); int s=ms.indexOf("StreamTitle='"); if(s>=0){ int e=ms.indexOf("';", s+13); if(e>0){ String title=ms.substring(s+13,e).trim(); if(title.length()>=3 && !title.equals(radioMeta) && !title.equals(curTitle)){ radioMeta=title; broadcast(); } } } }
                    }
                    try{ in.close(); }catch(Throwable t){} try{ c.disconnect(); }catch(Throwable t){} if(icyConn==c) icyConn=null;
                }catch(Throwable t){ if(icyConn!=null && gen!=radioMetaGen){ try{ icyConn.disconnect(); }catch(Throwable t2){} } }
            }}).start();
        }catch(Throwable t){}
        */
    }
    private void playMusicIndex(int idx){ if(musicUris.isEmpty()) return; mode="MUSIC"; radioMeta=""; if(order.isEmpty()) rebuildOrder();
        int pos=0; for(int i=0;i<order.size();i++) if(order.get(i)==idx) pos=i; orderPos=pos; playMusicAtOrderPos(pos); }
    private void playMusicAtOrderPos(int pos){ if(order.isEmpty()||musicUris.isEmpty()) return; mode="MUSIC"; try{ getSharedPreferences("retro_tape_deck_prefs",0).edit().putString("cd_last_mode","MUSIC").apply(); }catch(Throwable t){} orderPos=((pos%order.size())+order.size())%order.size(); int real=order.get(orderPos); curRealIndex=real; String uri=musicUris.get(real); curTitle=musicNames.get(real); curSub="MY MUSIC"; playGen++; playLocal(uri); }
    private void nextMusic(boolean auto){ if(musicUris.isEmpty()) return; if(!auto){ consecutiveFailures=0; autoAdvanceBurst=0; } else { long now=System.currentTimeMillis(); if(now-lastAutoAdvanceMs<10000) autoAdvanceBurst++; else autoAdvanceBurst=1; lastAutoAdvanceMs=now; if(autoAdvanceBurst>100){ autoAdvanceBurst=0; consecutiveFailures=0; stopPlayback(); return; } } playMusicAtOrderPos(orderPos+1); }
    private void prevMusic(){ if(musicUris.isEmpty()) return; playMusicAtOrderPos(orderPos-1); }
    private void pause(){ try{ if(player!=null && player.isPlaying()) player.pause(); }catch(Throwable t){} playing=false; startFg(); broadcast(); }
    private void resume(){ try{
        if(player==null){ if("MUSIC".equals(mode)){ loadMusic(); if(!musicUris.isEmpty()){ playMusicAtOrderPos(0); return; } } if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); return; }
        if("RADIO".equals(mode)){ if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); return; }
        if(!playerPrepared){ loadMusic(); if(!musicUris.isEmpty()) playMusicAtOrderPos(orderPos); return; }
        requestFocus(); player.start(); playing=true; startFg(); }catch(Throwable t){
        try{ if("MUSIC".equals(mode)){ loadMusic(); if(!musicUris.isEmpty()){ playMusicAtOrderPos(0); return; } } if(isCustomRadio && curRadioUrl!=null && curRadioUrl.length()>0) playCustomRadio(curTitle, curRadioUrl, curSub); else playRadio(stationIndex); }catch(Throwable t2){}
    } broadcast(); }
    private void stopPlayback(){ radioMetaGen++; radioPreparing=false; try{ if(icyConn!=null) icyConn.disconnect(); }catch(Throwable t){} icyConn=null; icyActiveUrl=""; try{ if(player!=null && player.isPlaying()) player.pause(); }catch(Throwable t){} playing=false; try{ stopForeground(true); }catch(Throwable t){} broadcast(); }
    private String extOf(String uriString, String name){
        try{ String src = name!=null && name.contains(".") ? name : uriString; int d=src.lastIndexOf('.'); if(d>=0 && src.length()-d<=6){ String e=src.substring(d).toLowerCase(); if(e.matches("\\.[a-z0-9]+")) return e; } }catch(Throwable t){}
        return ".tmp";
    }
    private boolean playLocalMethod(String uriString, int method){
        playerPrepared=false;
        try{
            if(method==0){ Uri uri=Uri.parse(uriString); java.io.InputStream in=getContentResolver().openInputStream(uri); if(in!=null){ java.io.File out=new java.io.File(getCacheDir(),"music_play"+extOf(uriString, curTitle)); java.io.FileOutputStream fos=new java.io.FileOutputStream(out); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>=0) fos.write(buf,0,n); fos.close(); in.close(); if(out.length()>0){ player.reset(); player.setDataSource(out.getAbsolutePath()); return true; } } }
            else if(method==1){ player.reset(); player.setDataSource(getApplicationContext(), Uri.parse(uriString)); return true; }
            else if(method==2){ android.os.ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(Uri.parse(uriString),"r"); if(pfd!=null){ player.reset(); player.setDataSource(pfd.getFileDescriptor()); try{pfd.close();}catch(Throwable t){} return true; } }
            else if(method==3){ String name=curTitle; Cursor c=getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media._ID}, MediaStore.Audio.Media.DISPLAY_NAME+"=?", new String[]{name}, null); if(c!=null){ try{ if(c.moveToFirst()){ long id=c.getLong(0); Uri mu=ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id); lastMediaStoreUri=mu.toString(); player.reset(); player.setDataSource(getApplicationContext(), mu); return true; } }finally{ c.close(); } } }
        }catch(Throwable t){}
        return false;
    }
    private boolean tryNextMethodForSameSong(){
        try{ if(!"MUSIC".equals(mode) || curPlayUri==null || curPlayUri.length()==0) return false; for(int m=curPlayMethod+1;m<=3;m++){ if(playLocalMethod(curPlayUri,m)){ curPlayMethod=m; try{ player.prepareAsync(); playing=true; startFg(); broadcast(); }catch(Throwable t){ continue; } return true; } } }catch(Throwable t){}
        return false;
    }
    private void playLocal(String uriString){
        freshPlayer(); requestFocus(); playGen++; boolean ok=false; curPlayUri=uriString; curPlayMethod=1;
        int[] methods={1,2,0,3};
        for(int m: methods){ if(m==0 && skipCacheOnce){ skipCacheOnce=false; continue; } if(playLocalMethod(uriString,m)){ curPlayMethod=m; ok=true; if(m==3 && curRealIndex>=0 && curRealIndex<musicUris.size() && lastMediaStoreUri!=null && lastMediaStoreUri.length()>0){ try{ musicUris.set(curRealIndex, lastMediaStoreUri); SharedPreferences p=getSharedPreferences("retro_tape_deck_prefs",0); StringBuilder b=new StringBuilder(); for(int i=0;i<musicUris.size();i++){ if(i>0) b.append('\u001f'); b.append(musicUris.get(i)); } p.edit().putString("my_music_uris", b.toString()).apply(); }catch(Throwable t){} } break; } }
        if(!ok){ playing=false; broadcast(); if("MUSIC".equals(mode)){ if(musicErrorRetries<3){ musicErrorRetries++; skipCacheOnce=true; final String retryUri=uriString; final int g2=playGen; handler.postDelayed(new Runnable(){ public void run(){ if(g2!=playGen) return; playLocal(retryUri); } }, 200); return; } musicErrorRetries=0; if(!order.isEmpty() && orderPos<order.size()-1){ final int g=playGen; handler.postDelayed(new Runnable(){ public void run(){ if(g!=playGen) return; nextMusic(true); } }, 200); } else { stopPlayback(); } } return; }
        try{ player.prepareAsync(); playing=true; startFg(); }catch(Throwable t){ playing=false; }
        broadcast();
    }
}
