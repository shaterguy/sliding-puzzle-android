package com.shaterguy.slidingpuzzle;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Internal multiplayer UI. All network state is transient and never stored in solo preferences. */
public final class MultiplayerActivity extends Activity implements MultiplayerSession.Callback {
 static final int INK=0xff243449,TEAL=0xff087F8C,CREAM=0xffFFF9EE,GOLD=0xffE5A500;
 private static final int REQ_DIRECT=31,REQ_PHOTO=32;
 private static final ExecutorService IO=Executors.newSingleThreadExecutor();
 private static final Handler MAIN=new Handler(Looper.getMainLooper());

 static final class Retained {
  MultiplayerSession engine;String role="",setupMode="number",setupPhoto="",pendingDirectRole="";int setupSize=3;Bitmap setupBitmap;
 }
 private Retained r;private PhotoStore photos;private LinearLayout content;private FrameLayout root;private BoardView board;private TextView matchStats;private boolean moving=false;
 private final Runnable ticker=new Runnable(){public void run(){if(matchStats!=null&&r!=null&&r.engine!=null&&MultiplayerSession.MATCH.equals(r.engine.state)){matchStats.setText(localStatsText());MAIN.postDelayed(this,1000);}}};

 @Override public void onCreate(Bundle state){
  super.onCreate(state);photos=new PhotoStore(this);Object kept=getLastNonConfigurationInstance();r=kept instanceof Retained?(Retained)kept:new Retained();
  if(r.engine==null)r.engine=new MultiplayerSession(this);r.engine.attach(this);render();
 }
 @Override public Object onRetainNonConfigurationInstance(){return r;}
 @Override protected void onDestroy(){MAIN.removeCallbacks(ticker);r.engine.attach(null);if(isFinishing())r.engine.close();super.onDestroy();}
 @Override public void onChanged(){if(!isFinishing()&&!isDestroyed())render();}

 int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
 private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 private TextView text(String value,int sp){TextView v=new TextView(this);v.setText(value);v.setTextColor(INK);v.setTextSize(sp);v.setPadding(0,dp(5),0,dp(5));return v;}
 private Button button(String title,int id,Runnable action){Button b=new Button(this);b.setId(id);b.setText(title);b.setTextColor(INK);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(52));b.setBackground(shape(0xffF1E9DB,16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));b.setLayoutParams(p);b.setOnClickListener(v->action.run());return b;}
 private void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(shape(TEAL,16));}
 private void heading(String title,String sub){TextView h=text(title,30);h.setTypeface(null,Typeface.BOLD);content.addView(h);if(sub!=null)content.addView(text(sub,16));}
 private void render(){
  MAIN.removeCallbacks(ticker);matchStats=null;moving=false;root=new FrameLayout(this);root.setBackgroundColor(CREAM);root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(16),dp(22),dp(24));content.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));setContentView(root);root.requestApplyInsets();
  MultiplayerSession e=r.engine;
  if(e==null){finish();return;}
  if(r.role.isEmpty()){renderRole();return;}
  if(MultiplayerSession.IDLE.equals(e.state)){renderTransport();return;}
  if(isError(e.state)){renderError();return;}
  if(MultiplayerSession.WAITING.equals(e.state)||MultiplayerSession.DISCOVERING.equals(e.state)||MultiplayerSession.CONNECTING.equals(e.state)){renderDiscovery();return;}
  if(MultiplayerSession.PAIRING.equals(e.state)){renderPairing();return;}
  if(MultiplayerSession.HOST_SETUP.equals(e.state)){renderSetup();return;}
  if(MultiplayerSession.WAIT_CONFIG.equals(e.state)||MultiplayerSession.CONFIGURING.equals(e.state)||MultiplayerSession.PHOTO_RECEIVING.equals(e.state)){renderWaitingConfig();return;}
  if(MultiplayerSession.CONFIGURED.equals(e.state)||MultiplayerSession.READY.equals(e.state)){renderReady();return;}
  if(MultiplayerSession.COUNTDOWN.equals(e.state)){renderCountdown();return;}
  if(MultiplayerSession.MATCH.equals(e.state)){renderMatch();return;}
  if(MultiplayerSession.RESULT.equals(e.state)){renderResult();return;}
  renderError();
 }
 private boolean isError(String state){return MultiplayerSession.DISCONNECTED.equals(state)||MultiplayerSession.PAIRING_MISMATCH.equals(state)||MultiplayerSession.PROTOCOL_ERROR.equals(state)||MultiplayerSession.CRYPTO_ERROR.equals(state)||MultiplayerSession.PHOTO_TRANSFER_ERROR.equals(state)||MultiplayerSession.PERMISSION_DENIED.equals(state)||MultiplayerSession.P2P_UNSUPPORTED.equals(state);}
 private void renderRole(){
  heading("멀티 대결","서버 없이 두 기기를 연결해 같은 퍼즐로 겨룹니다.");content.addView(text("이 기기에서 무엇을 할까요?",18));
  Button host=button("방 만들기",500,()->{r.role=MultiplayerSession.HOST;render();});primary(host);content.addView(host);content.addView(button("방 참가하기",501,()->{r.role=MultiplayerSession.GUEST;render();}));content.addView(button("‹ 처음으로",502,this::finish));
 }
 private void renderTransport(){
  heading("연결 방식","외부 서버를 거치지 않고 가까운 기기끼리 연결합니다.");content.addView(text(MultiplayerSession.HOST.equals(r.role)?"방 만들기":"방 참가하기",18));
  content.addView(button("가까운 기기와 직접 연결",510,()->beginTransport(MultiplayerSession.DIRECT)));content.addView(text("Wi‑Fi Direct를 사용합니다. 공유기나 인터넷 연결이 없어도 됩니다.",14));
  content.addView(button("같은 Wi‑Fi에서 연결",511,()->beginTransport(MultiplayerSession.LAN)));content.addView(text("두 기기가 같은 Wi‑Fi 또는 한 기기의 핫스팟에 연결되어 있어야 합니다.",14));
  content.addView(button("‹ 역할 다시 선택",512,()->{r.role="";render();}));
 }
 private void beginTransport(String transport){if(MultiplayerSession.DIRECT.equals(transport)){requestDirectIfNeeded();return;}startTransport(transport);}
 private void requestDirectIfNeeded(){
  String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
  if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){r.pendingDirectRole=r.role;requestPermissions(new String[]{permission},REQ_DIRECT);return;}
  LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);if(lm!=null&&!lm.isLocationEnabled()){showLocationRequired();return;}
  startTransport(MultiplayerSession.DIRECT);
 }
 private void showLocationRequired(){new AlertDialog.Builder(this).setTitle("기기 설정이 필요합니다").setMessage("Wi‑Fi Direct로 가까운 기기를 찾으려면 기기의 위치 기능이 켜져 있어야 합니다. 앱은 위치값을 저장하거나 전송하지 않습니다.").setNegativeButton("같은 Wi‑Fi 사용",(d,w)->startTransport(MultiplayerSession.LAN)).setPositiveButton("기기 설정 열기",(d,w)->{try{startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}catch(ActivityNotFoundException ignored){}}).show();}
 private void startTransport(String transport){if(MultiplayerSession.HOST.equals(r.role))r.engine.startHost(transport);else r.engine.startGuest(transport);}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request!=REQ_DIRECT)return;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){requestDirectIfNeeded();}else{r.engine.externalError(MultiplayerSession.PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}}

 private void renderDiscovery(){
  heading(MultiplayerSession.WAITING.equals(r.engine.state)?"방을 열었어요":"방 찾기",null);content.addView(status(r.engine.errorMessage));
  if(MultiplayerSession.DISCOVERING.equals(r.engine.state)){List<MultiplayerSession.Room> rooms=r.engine.rooms();if(rooms.isEmpty())content.addView(text("아직 주변 방이 없습니다.",15));int id=520;for(MultiplayerSession.Room room:rooms)content.addView(button(room.label,id++,()->r.engine.connect(room)));}
  content.addView(button("취소하고 처음으로",529,this::confirmExit));
 }
 private TextView status(String value){TextView t=text(value==null||value.isEmpty()?"연결 중…":value,17);t.setId(590);return t;}
 private void renderPairing(){
  heading("연결 확인","상대 화면에도 같은 숫자가 보이면 일치해요를 눌러 주세요.");String code=r.engine.sas;TextView sas=text(groupSas(code),46);sas.setId(530);sas.setGravity(Gravity.CENTER);sas.setTypeface(null,Typeface.BOLD);sas.setContentDescription("연결 확인 코드 "+spokenDigits(code));content.addView(sas,new LinearLayout.LayoutParams(-1,-2));
  Button yes=button("일치해요",531,()->r.engine.confirmPairing(true));primary(yes);content.addView(yes);content.addView(button("다릅니다",532,()->r.engine.confirmPairing(false)));content.addView(button("멀티 종료",533,this::confirmExit));
 }
 private String groupSas(String value){return value!=null&&value.length()==8?value.substring(0,4)+"  "+value.substring(4):value;}
 private String spokenDigits(String value){if(value==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<value.length();i++){if(i>0)b.append(' ');b.append(value.charAt(i));}return b.toString();}

 private void setupSizes(){content.addView(text("퍼즐 크기",17));LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);content.addView(row,new LinearLayout.LayoutParams(-1,-2));for(int n=3;n<=6;n++){final int size=n;Button b=button(n+" × "+n+"\n"+(n*n-1)+"조각",550+n,()->{r.setupSize=size;render();});b.setTextSize(13);if(n==r.setupSize)primary(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(70),1);p.setMargins(dp(2),dp(4),dp(2),dp(4));row.addView(b,p);}}
 private void renderSetup(){
  heading("대결 설정","방장이 두 기기에서 사용할 같은 퍼즐을 정합니다.");LinearLayout modes=new LinearLayout(this);modes.setGravity(Gravity.CENTER);content.addView(modes,new LinearLayout.LayoutParams(-1,-2));Button number=button("숫자",540,()->{r.setupMode="number";render();}),photo=button("사진",541,()->{r.setupMode="photo";render();});if("number".equals(r.setupMode))primary(number);else primary(photo);modes.addView(number,new LinearLayout.LayoutParams(0,dp(58),1));modes.addView(photo,new LinearLayout.LayoutParams(0,dp(58),1));setupSizes();
  if("photo".equals(r.setupMode)){if(r.setupBitmap!=null){ImageView preview=new ImageView(this);preview.setImageBitmap(r.setupBitmap);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("상대에게 보낼 게임 사진 미리보기");content.addView(preview,new LinearLayout.LayoutParams(-1,dp(240)));}Button pick=button(r.setupBitmap==null?"사진 선택":"다른 사진 선택",542,this::pickPhoto);if(r.setupBitmap==null)primary(pick);content.addView(pick);}
  Button go=button("이 설정으로 계속",560,this::confirmSetup);primary(go);content.addView(go);content.addView(button("멀티 종료",561,this::confirmExit));if(r.engine.rematchRequested)content.addView(text("상대가 재대결을 요청했습니다.",15));
 }
 private void pickPhoto(){Intent intent=Build.VERSION.SDK_INT>=33?new Intent(MediaStore.ACTION_PICK_IMAGES):new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/*");try{startActivityForResult(intent,REQ_PHOTO);}catch(ActivityNotFoundException e){try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),REQ_PHOTO);}catch(ActivityNotFoundException missing){toast("사진을 선택할 앱이 없습니다.");}}}
 @Override protected void onActivityResult(int request,int resultCode,Intent data){super.onActivityResult(request,resultCode,data);if(request!=REQ_PHOTO||resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();content.removeAllViews();heading("대결 설정","게임 사진을 준비하고 있습니다.");content.addView(new ProgressBar(this));IO.execute(()->{String name="";Bitmap bitmap=null;try{name=photos.importPhoto(uri);bitmap=photos.load(name);}catch(Exception|OutOfMemoryError ignored){}String finalName=name;Bitmap finalBitmap=bitmap;MAIN.post(()->{if(isFinishing()||isDestroyed())return;if(finalBitmap==null)toast("사진을 준비하지 못했습니다. 다른 사진을 선택해 주세요.");else{r.setupPhoto=finalName;r.setupBitmap=finalBitmap;}render();});});}
 private void confirmSetup(){
  if("number".equals(r.setupMode)){r.engine.configure(r.setupSize,"number",null,null);return;}
  if(r.setupBitmap==null||r.setupPhoto.isEmpty()){toast("먼저 사진을 선택해 주세요.");return;}
  new AlertDialog.Builder(this).setTitle("게임 사진 전송").setMessage("게임용으로 만든 사진을 상대 기기로 직접 전송합니다. 원본 파일·파일명·위치 정보는 보내지 않습니다.").setNegativeButton("취소",null).setPositiveButton("전송하고 계속",(d,w)->sendPhotoSetup()).show();
 }
 private void sendPhotoSetup(){content.removeAllViews();heading("대결 설정","게임 사진을 안전하게 전송하고 있습니다.");content.addView(new ProgressBar(this));IO.execute(()->{try{byte[] bytes=photos.readForPeer(r.setupPhoto);MAIN.post(()->{if(!isFinishing()&&!isDestroyed())r.engine.configure(r.setupSize,"photo",bytes,r.setupBitmap);});}catch(Exception e){MAIN.post(()->r.engine.externalError(MultiplayerSession.PHOTO_TRANSFER_ERROR,"게임 사진을 전송용으로 준비하지 못했습니다."));}});}

 private void renderWaitingConfig(){heading("대결 준비",null);content.addView(status(r.engine.errorMessage));if(r.engine.localPuzzle!=null)content.addView(summary());content.addView(button("멀티 종료",569,this::confirmExit));}
 private TextView summary(){String m="photo".equals(r.engine.mode)?"사진 퍼즐":"숫자 퍼즐";return text(m+" · "+r.engine.size+" × "+r.engine.size,18);}
 private void renderReady(){heading("준비 확인","두 기기의 설정을 확인한 뒤 준비 완료를 눌러 주세요.");content.addView(summary());if("photo".equals(r.engine.mode)&&r.engine.photoBitmap!=null){ImageView preview=new ImageView(this);preview.setImageBitmap(r.engine.photoBitmap);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("이번 대결 게임 사진");content.addView(preview,new LinearLayout.LayoutParams(-1,dp(220)));}content.addView(text(r.engine.localReady?"나는 준비됨":"나는 준비 전",16));content.addView(text(r.engine.remoteReady?"상대 준비됨":"상대 준비 중",16));Button ready=button(r.engine.localReady?"준비 완료됨":"준비 완료",570,r.engine::ready);ready.setEnabled(!r.engine.localReady);if(!r.engine.localReady)primary(ready);content.addView(ready);content.addView(button("멀티 종료",571,this::confirmExit));}
 private void renderCountdown(){heading("곧 시작합니다","두 기기에서 같은 퍼즐이 시작됩니다.");String value=r.engine.countdown>0?String.valueOf(r.engine.countdown):"시작!";TextView count=text(value,64);count.setId(580);count.setGravity(Gravity.CENTER);count.setTypeface(null,Typeface.BOLD);content.addView(count,new LinearLayout.LayoutParams(-1,dp(220)));root.announceForAccessibility(value);}

 private void renderMatch(){
  if(r.engine.localPuzzle==null){r.engine.externalError(MultiplayerSession.PROTOCOL_ERROR,"대결 퍼즐 상태를 확인할 수 없습니다.");return;}
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);content.addView(top,new LinearLayout.LayoutParams(-1,-2));Button back=button("‹",581,this::confirmExit);back.setContentDescription("대결 나가기");top.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));TextView title=text("photo".equals(r.engine.mode)?"멀티 사진 퍼즐":"멀티 숫자 퍼즐",22);title.setGravity(Gravity.CENTER);title.setTypeface(null,Typeface.BOLD);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button settings=button("설정",582,this::settings);top.addView(settings,new LinearLayout.LayoutParams(dp(72),dp(48)));
  matchStats=localStats();content.addView(matchStats);MAIN.postDelayed(ticker,1000);TextView opponent=text(r.engine.opponentStatus(),16);opponent.setId(590);opponent.setBackground(shape(0xffF1E9DB,12));opponent.setPadding(dp(12),dp(10),dp(12),dp(10));content.addView(opponent,new LinearLayout.LayoutParams(-1,-2));
  board=new BoardView(this,r.engine.localPuzzle,"photo".equals(r.engine.mode)?r.engine.photoBitmap:null,getSharedPreferences("puzzle",MODE_PRIVATE).getBoolean("tap",false),this::move);content.addView(board,new LinearLayout.LayoutParams(-1,-2));content.addView(text(getSharedPreferences("puzzle",MODE_PRIVATE).getBoolean("tap",false)?"빈칸 옆 조각을 누르거나 밀어 주세요.":"빈칸 옆 조각을 빈칸 쪽으로 밀어 주세요.",15));if("photo".equals(r.engine.mode))content.addView(button("원본 보기",583,this::preview));
 }
 private String localStatsText(){long seconds=r.engine.elapsed()/1000;return String.format(Locale.KOREAN,"%d × %d     •     %02d:%02d     •     %d번 이동",r.engine.size,r.engine.size,seconds/60,seconds%60,r.engine.localPuzzle==null?0:r.engine.localPuzzle.moves);}
 private TextView localStats(){return text(localStatsText(),17);}
 private void move(int index){if(moving||r.engine.localPuzzle==null||!r.engine.localPuzzle.adjacent(index))return;int blank=r.engine.localPuzzle.blank();if(!r.engine.move(index))return;moving=true;board.animateMove(index,blank,()->{moving=false;if(!isFinishing()&&!isDestroyed())render();});}
 private void preview(){if(r.engine.photoBitmap==null)return;ImageView image=new ImageView(this);image.setImageBitmap(r.engine.photoBitmap);image.setAdjustViewBounds(true);image.setContentDescription("이번 대결 게임 사진");new AlertDialog.Builder(this).setTitle("게임 사진").setView(image).setPositiveButton("계속하기",null).show();}
 private void settings(){android.content.SharedPreferences prefs=getSharedPreferences("puzzle",MODE_PRIVATE);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(10),dp(24),dp(10));Switch toggle=new Switch(this);toggle.setText("터치로 이동");toggle.setTextColor(INK);toggle.setTextSize(18);toggle.setMinHeight(dp(56));toggle.setChecked(prefs.getBoolean("tap",false));panel.addView(toggle);panel.addView(text("켜면 빈칸 옆 조각을 눌러 이동할 수 있어요. 대결 시간과 연결은 계속 진행됩니다.",15));toggle.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("tap",on).apply());AlertDialog d=new AlertDialog.Builder(this).setTitle("설정").setView(panel).setPositiveButton("완료",null).create();d.setOnDismissListener(x->render());d.show();}

 private void renderResult(){heading("대결 결과",null);String title="WIN".equals(r.engine.result)?"이겼어요!":"LOSS".equals(r.engine.result)?"상대가 먼저 완성했어요":"동시에 완성했어요!";TextView result=text(title,30);result.setTypeface(null,Typeface.BOLD);result.setGravity(Gravity.CENTER);content.addView(result);content.addView(text("내 기록 · "+(r.engine.localPuzzle==null?0:r.engine.localPuzzle.moves)+"번 이동\n상대 기록 · "+r.engine.remoteMoves()+"번 이동",17));Button rematch=button("다시 대결",600,r.engine::rematch);primary(rematch);content.addView(rematch);content.addView(button("멀티 종료",601,this::exit));if(r.engine.rematchRequested)content.addView(text(MultiplayerSession.HOST.equals(r.engine.role)?"상대가 재대결을 요청했습니다. 설정을 선택해 주세요.":"재대결 요청을 보냈습니다. 방장 설정을 기다립니다.",15));root.announceForAccessibility(title);}
 private void renderError(){heading("멀티 연결 종료",null);String message=r.engine.errorMessage==null||r.engine.errorMessage.isEmpty()?"연결을 계속할 수 없습니다. 이번 대결은 승패 없이 종료되었습니다.":r.engine.errorMessage;content.addView(text(message,17));Button retry=button("다시 연결",610,this::resetSession);primary(retry);content.addView(retry);content.addView(button("처음으로",611,this::exit));root.announceForAccessibility(message);}
 private void resetSession(){r.engine.close();r.engine=new MultiplayerSession(this);r.engine.attach(this);r.role="";r.pendingDirectRole="";render();}
 private void confirmExit(){String msg=MultiplayerSession.MATCH.equals(r.engine.state)?"대결에서 나갈까요? 이번 경기는 승패 없이 종료됩니다.":"멀티 방을 나갈까요?";new AlertDialog.Builder(this).setTitle("멀티 종료").setMessage(msg).setNegativeButton("계속하기",null).setPositiveButton("나가기",(d,w)->exit()).show();}
 private void exit(){r.engine.close();finish();}
 @Override public void onBackPressed(){if(r.role.isEmpty()){finish();return;}if(MultiplayerSession.IDLE.equals(r.engine.state)){r.role="";render();return;}confirmExit();}
 private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
