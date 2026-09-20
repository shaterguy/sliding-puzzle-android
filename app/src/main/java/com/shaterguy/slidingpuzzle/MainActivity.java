package com.shaterguy.slidingpuzzle;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Offline sliding puzzle. Images are copied only after explicit pick/share. */
public class MainActivity extends Activity {
 static final int INK=0xff243449,TEAL=0xff087F8C,CREAM=0xffFFF9EE,PEACH=0xffFFB98F,GOLD=0xffE5A500;
 private static final ExecutorService IO=Executors.newSingleThreadExecutor();
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 static class Session {
  String screen="home",photo="",mode="number";int size=3,starBaseline=0;Puzzle puzzle;
  Bitmap bitmap;long elapsed=0;boolean started=false,pending=false;int generation=0;MainActivity activity;
 }
 Session s; private android.content.SharedPreferences prefs;private PhotoStore photos;
 private LinearLayout content;private FrameLayout root;private BoardView board;private TextView stats;private StarMeterView starMeter;
 private long runningSince=0;private boolean foreground=false,moving=false;private ConfettiView confetti;
 private final Runnable ticker=new Runnable(){public void run(){updateStats();if(foreground&&runningSince>0)MAIN.postDelayed(this,1000);}};
 @Override public void onCreate(Bundle state){
  super.onCreate(state);prefs=getSharedPreferences("puzzle",MODE_PRIVATE);photos=new PhotoStore(this);
  Object retained=getLastNonConfigurationInstance();
  if(retained instanceof Session)s=(Session)retained;else{s=new Session();restore();}
  s.activity=this;render();
  if(state==null&&!s.pending)receive(getIntent());
  if(!s.photo.isEmpty()&&s.bitmap==null&&!s.pending)loadPhoto(s.photo,false);
 }
 private void restore(){
  s.screen=prefs.getString("screen","home");s.mode=prefs.getString("mode","number");s.photo=prefs.getString("photo","");s.size=prefs.getInt("size",3);s.starBaseline=Math.max(0,prefs.getInt("starBaseline",0));
  if(s.size<3||s.size>6)s.size=3;
  s.elapsed=Math.max(0,prefs.getLong("elapsed",0));s.started=prefs.getBoolean("started",false);
  String encoded=prefs.getString("tiles","");
  if(!encoded.isEmpty())try{s.puzzle=Puzzle.restore(s.size,encoded,prefs.getInt("moves",0));}catch(RuntimeException bad){s.screen="home";}
  if(s.puzzle==null&&(s.screen.equals("game")||s.screen.equals("done")))s.screen="home";
  if(s.puzzle!=null&&s.starBaseline<=0)s.starBaseline=StarRating.legacyBaseline(s.puzzle);
 }
 @Override public Object onRetainNonConfigurationInstance(){return s;}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);receive(intent);}
 private void receive(Intent intent){
  if(intent==null||!Intent.ACTION_SEND.equals(intent.getAction()))return;
  String type=intent.getType();if(type==null||!type.startsWith("image/")){toast("사진 파일을 공유해 주세요.");return;}
  Uri uri;
  try {
   uri=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class):intent.getParcelableExtra(Intent.EXTRA_STREAM);
   if(uri==null&&intent.getClipData()!=null&&intent.getClipData().getItemCount()>0)uri=intent.getClipData().getItemAt(0).getUri();
  } catch(RuntimeException malformed) {toast("공유된 사진을 읽을 수 없습니다.");return;}
  if(uri==null){toast("공유된 사진을 읽을 수 없습니다.");return;}
  final Uri chosen=uri;confirmDiscard(()->importPhoto(chosen));
 }
 @Override protected void onResume(){super.onResume();foreground=true;resumeClock();}
 @Override protected void onPause(){pauseClock();foreground=false;if(confetti!=null)confetti.stop();if(moving)render();save();super.onPause();}
 @Override protected void onDestroy(){if(s.activity==this)s.activity=null;MAIN.removeCallbacks(ticker);super.onDestroy();}
 private void resumeClock(){if(foreground&&!s.pending&&s.started&&s.screen.equals("game")&&s.puzzle!=null&&!s.puzzle.solved()&&runningSince==0){runningSince=SystemClock.elapsedRealtime();MAIN.post(ticker);}}
 private void pauseClock(){if(runningSince>0){s.elapsed+=SystemClock.elapsedRealtime()-runningSince;runningSince=0;}MAIN.removeCallbacks(ticker);}
 private long elapsed(){return s.elapsed+(runningSince>0?SystemClock.elapsedRealtime()-runningSince:0);}
 private void save(){saveSession(s,prefs,elapsed());}
 private static void saveSession(Session state,android.content.SharedPreferences preferences,long elapsed){
  android.content.SharedPreferences.Editor e=preferences.edit().putString("screen",state.screen).putString("mode",state.mode).putString("photo",state.photo).putInt("size",state.size).putLong("elapsed",elapsed).putBoolean("started",state.started);
  if(state.puzzle!=null)e.putString("tiles",state.puzzle.encode()).putInt("moves",state.puzzle.moves).putInt("starBaseline",state.starBaseline);else e.remove("tiles").remove("moves").remove("starBaseline");
  e.apply();
 }
 int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
 private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 private TextView text(String value,int sp){TextView v=new TextView(this);v.setText(value);v.setTextColor(INK);v.setTextSize(sp);v.setPadding(0,dp(5),0,dp(5));return v;}
 private Button button(String title,int id,Runnable action){
  Button b=new Button(this);b.setId(id);b.setText(title);b.setTextColor(INK);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(52));b.setBackground(shape(0xffF1E9DB,16));
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));b.setLayoutParams(p);b.setOnClickListener(v->action.run());return b;
 }
 private void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(shape(TEAL,16));}
 private void render(){
  moving=false;starMeter=null;if(confetti!=null){confetti.stop();confetti=null;}
  root=new FrameLayout(this);root.setBackgroundColor(CREAM);
  root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
  content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(16),dp(22),dp(24));content.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
  setContentView(root);root.requestApplyInsets();
  if(s.pending){content.addView(text("사진을 준비하고 있어요",24));content.addView(new ProgressBar(this));content.addView(text("사진을 안전하게 저장하고 조각을 준비합니다.",15));return;}
  if(s.screen.equals("game")||s.screen.equals("done"))renderGame();else if(s.screen.equals("prepare"))renderPrepare();else renderHome();
 }
 private void heading(String title,String sub){
  TextView h=text(title,30);h.setTypeface(null,Typeface.BOLD);content.addView(h);if(sub!=null)content.addView(text(sub,16));
 }
 private void sizes(){
  content.addView(text("퍼즐 크기",17));LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);content.addView(row,new LinearLayout.LayoutParams(-1,-2));
  for(int n=3;n<=6;n++){final int size=n;Button b=button(n+" × "+n+"\n"+(n*n-1)+"조각",300+n,()->{s.size=size;s.puzzle=null;s.starBaseline=0;save();render();});b.setTextSize(13);if(n==s.size)primary(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(70),1);p.setMargins(dp(2),dp(4),dp(2),dp(4));row.addView(b,p);}
 }
 private void renderHome(){
  heading("슬라이딩 퍼즐","한 칸씩, 제자리로!");
  content.addView(text("어떤 퍼즐을 맞춰 볼까요?",18));
  Button number=button("123   숫자 퍼즐",101,()->{s.mode="number";s.photo="";s.bitmap=null;save();render();});
  Button photo=button("▧   사진 퍼즐",102,()->{s.mode="photo";s.screen="prepare";save();render();});
  if(s.mode.equals("number"))primary(number);else primary(photo);content.addView(number);content.addView(photo);
  sizes();Button start=button("숫자 퍼즐 시작",103,()->{s.mode="number";s.photo="";s.bitmap=null;startGame();});primary(start);content.addView(start);
  content.addView(button("친구와 멀티 대결",120,()->startActivity(new Intent(this,MultiplayerActivity.class))));
  content.addView(button("설정",104,this::settings));
  content.addView(text("빈칸 옆 조각을 빈칸 쪽으로 밀어 보세요.\n설정에서 터치 이동도 켤 수 있어요.",15));
 }
 private void renderPrepare(){
  heading("사진 퍼즐","내 사진이 나만의 퍼즐로!");
  content.addView(button("‹ 처음으로",105,()->{s.screen="home";save();render();}));
  if(s.bitmap!=null&&!s.photo.isEmpty()){
   ImageView preview=new ImageView(this);preview.setContentDescription("퍼즐로 사용할 중앙 정사각형 사진 미리보기");preview.setImageBitmap(s.bitmap);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);content.addView(preview,new LinearLayout.LayoutParams(-1,dp(240)));
   content.addView(text("사진의 가운데를 정사각형으로 사용합니다.",14));sizes();
   Button start=button("이 사진으로 시작",106,this::startGame);primary(start);content.addView(start);
  }else{content.addView(text("휴대폰이나 포토에서 사진을 골라 주세요.",16));sizes();}
  Button pick=button("사진 선택",107,this::pickPhoto);if(s.bitmap==null)primary(pick);content.addView(pick);
  content.addView(text("다시 플레이할 사진",19));File[] saved=photos.list();
  if(saved.length==0)content.addView(text("선택한 사진이 여기에 저장됩니다.",14));
  for(File file:saved){
   String name=file.getName();LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);content.addView(row,new LinearLayout.LayoutParams(-1,dp(82)));
   ImageView thumb=new ImageView(this);thumb.setContentDescription("저장한 사진 선택");thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(thumb,new LinearLayout.LayoutParams(dp(68),dp(68)));
   thumb.setOnClickListener(v->loadPhoto(name,true));loadThumbnail(file,thumb);
   Button use=button("이 사진 사용",View.generateViewId(),()->loadPhoto(name,true));row.addView(use,new LinearLayout.LayoutParams(0,dp(58),1));
   Button delete=button("삭제",View.generateViewId(),()->new AlertDialog.Builder(this).setTitle("저장한 사진 삭제").setMessage("게임에 저장된 사진만 삭제합니다. 휴대폰 원본은 그대로 남습니다.").setNegativeButton("취소",null).setPositiveButton("삭제",(d,w)->deletePhoto(name)).show());row.addView(delete,new LinearLayout.LayoutParams(dp(72),dp(58)));
  }
 }
 private void loadThumbnail(File file,ImageView view){IO.execute(()->{BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=8;Bitmap b=BitmapFactory.decodeFile(file.getPath(),options);MAIN.post(()->{if(!isDestroyed()&&view.isAttachedToWindow())view.setImageBitmap(b);});});}
 private void deletePhoto(String name){
  try{photos.delete(name);if(name.equals(s.photo)){s.photo="";s.bitmap=null;s.puzzle=null;s.starBaseline=0;}save();render();}catch(Exception e){toast("사진을 삭제하지 못했습니다. 다시 시도해 주세요.");}
 }
 private void pickPhoto(){
  Intent intent=Build.VERSION.SDK_INT>=33?new Intent(MediaStore.ACTION_PICK_IMAGES):new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
  intent.setType("image/*");
  try{startActivityForResult(intent,10);}catch(ActivityNotFoundException e){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),10);}catch(ActivityNotFoundException missing){toast("사진을 선택할 앱이 없습니다.");}}
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10&&result==RESULT_OK&&data!=null&&data.getData()!=null)importPhoto(data.getData());}
 private void importPhoto(Uri uri){
  pauseClock();final Session state=s;final int generation=++state.generation;
  state.pending=true;render();final PhotoStore store=photos;final android.content.SharedPreferences preferences=prefs;
  IO.execute(()->{
   String name=null;Bitmap bitmap=null;String error=null;
   try{
    if(!"content".equals(uri.getScheme()))throw new Exception("URI");
    String mime=getContentResolver().getType(uri);if(mime!=null&&!mime.startsWith("image/"))throw new Exception("MIME");
    name=store.importPhoto(uri);bitmap=store.load(name);
   }catch(Exception|OutOfMemoryError failure){error="사진을 불러오지 못했습니다. 다른 사진을 선택해 주세요. (최대 80MB)";}
   final String result=name,message=error;final Bitmap image=bitmap;
   MAIN.post(()->{
    if(generation!=state.generation)return;
    state.pending=false;
    if(message==null){state.photo=result;state.bitmap=image;state.mode="photo";state.screen="prepare";state.puzzle=null;state.starBaseline=0;state.started=false;state.elapsed=0;}
    saveSession(state,preferences,state.elapsed);
    MainActivity activity=state.activity;if(activity!=null){activity.render();if(message!=null){activity.toast(message);activity.resumeClock();}}
   });
  });
 }
 private void loadPhoto(String name,boolean prepare){
  pauseClock();final Session state=s;final int generation=++state.generation;state.pending=true;render();final PhotoStore store=photos;final android.content.SharedPreferences preferences=prefs;
  IO.execute(()->{Bitmap b=null;try{b=store.load(name);}catch(Exception|OutOfMemoryError ignored){}final Bitmap result=b;
   MAIN.post(()->{if(generation!=state.generation)return;state.pending=false;
    if(result!=null){state.photo=name;state.bitmap=result;state.mode="photo";if(prepare){state.screen="prepare";state.puzzle=null;state.starBaseline=0;state.started=false;state.elapsed=0;}}
    else{state.photo="";state.bitmap=null;state.screen="prepare";state.puzzle=null;state.starBaseline=0;state.started=false;}
    saveSession(state,preferences,state.elapsed);MainActivity a=state.activity;if(a!=null){a.render();a.resumeClock();if(result==null)a.toast("저장한 사진을 읽을 수 없습니다. 사진을 다시 선택해 주세요.");}
   });
  });
 }
 private void startGame(){
  if(s.mode.equals("photo")&&s.bitmap==null){toast("먼저 사진을 선택해 주세요.");return;}
  pauseClock();s.puzzle=new Puzzle(s.size);s.puzzle.shuffle(new Random());s.starBaseline=StarRating.minimumMoveBaseline(s.puzzle);s.elapsed=0;s.started=false;s.screen="game";save();render();
 }
 private StarRating rating(){if(s.puzzle==null)throw new IllegalStateException("puzzle");if(s.starBaseline<=0)s.starBaseline=StarRating.legacyBaseline(s.puzzle);return StarRating.fromBaseline(s.size,s.starBaseline);}
 private String starString(int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append('★');return out.toString();}
 private int earnedStars(){return rating().starsForMoves(s.puzzle.moves);}
 private void renderGame(){
  if(s.puzzle==null){s.screen="home";render();return;}
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);content.addView(top,new LinearLayout.LayoutParams(-1,-2));
  Button back=button("‹",110,()->confirmDiscard(()->{pauseClock();s.screen="home";s.puzzle=null;s.starBaseline=0;save();render();}));back.setContentDescription("다른 퍼즐 선택");top.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));
  TextView title=text(s.mode.equals("photo")?"사진 퍼즐":"숫자 퍼즐",22);title.setGravity(Gravity.CENTER);title.setTypeface(null,Typeface.BOLD);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  Button setting=button("설정",111,this::settings);top.addView(setting,new LinearLayout.LayoutParams(dp(72),dp(48)));
  stats=text("",17);stats.setId(201);content.addView(stats);
  starMeter=new StarMeterView(this,rating(),s.puzzle.moves);content.addView(starMeter,new LinearLayout.LayoutParams(-1,dp(78)));updateStats();
  board=new BoardView(this,s.puzzle,s.mode.equals("photo")?s.bitmap:null,prefs.getBoolean("tap",false),this::move);
  content.addView(board,new LinearLayout.LayoutParams(-1,-2));
  if(s.puzzle.solved()){
   int earned=earnedStars();TextView reward=text(starString(earned),52);reward.setId(203);reward.setTextColor(GOLD);reward.setGravity(Gravity.CENTER);reward.setTypeface(null,Typeface.BOLD);reward.setContentDescription("별 "+earned+"개 획득");content.addView(reward,new LinearLayout.LayoutParams(-1,-2));
   TextView done=text("완성했어요!",30);done.setId(202);done.setTypeface(null,Typeface.BOLD);done.setGravity(Gravity.CENTER);content.addView(done);TextView earnedText=text("별 "+earned+"개 획득!",20);earnedText.setGravity(Gravity.CENTER);content.addView(earnedText);content.addView(text("한 칸씩, 멋지게 해냈어요!",17));
   Button again=button("다시 도전",112,this::startGame);primary(again);content.addView(again);
   content.addView(button("다른 퍼즐",113,()->{s.screen="home";s.puzzle=null;s.starBaseline=0;save();render();}));
  }else{
   content.addView(text(prefs.getBoolean("tap",false)?"빈칸 옆 조각을 누르거나 밀어 주세요.":"빈칸 옆 조각을 빈칸 쪽으로 밀어 주세요.",15));
   content.addView(button("새로 섞기",114,()->confirmDiscard(this::startGame)));
  }
  if(s.mode.equals("photo"))content.addView(button("원본 보기",115,this::preview));
 }
 void move(int index){
  if(moving||s.pending||s.puzzle==null||s.puzzle.solved()||!s.puzzle.adjacent(index))return;
  int blank=s.puzzle.blank();moving=true;if(!s.started){s.started=true;resumeClock();}s.puzzle.move(index);
  boolean completed=s.puzzle.solved();if(completed){pauseClock();s.screen="done";}
  save();updateStats();
  board.animateMove(index,blank,()->{if(isDestroyed())return;render();if(completed&&foreground)celebrate();});
 }
 private void celebrate(){
  int earned=earnedStars();confetti=new ConfettiView(this,earned);root.addView(confetti,new FrameLayout.LayoutParams(-1,-1));confetti.start();root.announceForAccessibility("완성했어요! 별 "+earned+"개를 획득했습니다. "+s.puzzle.moves+"번 이동했습니다.");
 }
 private void updateStats(){if(stats==null||s.puzzle==null)return;long seconds=elapsed()/1000;stats.setText(String.format(Locale.KOREAN,"%d × %d     •     %02d:%02d     •     %d번 이동",s.size,s.size,seconds/60,seconds%60,s.puzzle.moves));if(starMeter!=null)starMeter.setMoves(s.puzzle.moves);}
 private void preview(){
  if(s.bitmap==null)return;ImageView image=new ImageView(this);image.setImageBitmap(s.bitmap);image.setAdjustViewBounds(true);image.setContentDescription("퍼즐 완성 사진");
  new AlertDialog.Builder(this).setTitle("완성 사진").setView(image).setPositiveButton("계속하기",null).show();
 }
 private void settings(){
  pauseClock();LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(10),dp(24),dp(10));
  Switch toggle=new Switch(this);toggle.setId(400);toggle.setText("터치로 이동");toggle.setTextColor(INK);toggle.setTextSize(18);toggle.setMinHeight(dp(56));toggle.setChecked(prefs.getBoolean("tap",false));panel.addView(toggle);
  TextView help=text("켜면 빈칸 옆 조각을 눌러 이동할 수 있어요.\n스와이프는 항상 사용할 수 있어요.",15);panel.addView(help);
  toggle.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("tap",on).apply());
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("설정").setView(panel).setPositiveButton("완료",null).create();dialog.setOnDismissListener(d->{render();resumeClock();});dialog.show();
 }
 private void confirmDiscard(Runnable yes){
  if(s.puzzle!=null&&!s.puzzle.solved()&&s.puzzle.moves>0){pauseClock();AlertDialog d=new AlertDialog.Builder(this).setTitle("진행 중인 퍼즐을 그만둘까요?").setMessage("현재 퍼즐의 진행 상황이 사라집니다.").setNegativeButton("계속하기",null).setPositiveButton("새로 시작",(dialog,w)->yes.run()).create();d.setOnDismissListener(dialog->resumeClock());d.show();}else yes.run();
 }
 @Override public void onBackPressed(){if(s.pending){toast("사진 준비가 끝날 때까지 잠시 기다려 주세요.");return;}if(s.screen.equals("home")){super.onBackPressed();return;}confirmDiscard(()->{pauseClock();s.screen="home";s.puzzle=null;s.starBaseline=0;save();render();});}
 private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
