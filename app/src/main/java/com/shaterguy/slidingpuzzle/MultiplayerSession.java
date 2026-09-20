package com.shaterguy.slidingpuzzle;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.p2p.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** One-peer, serverless multiplayer session. No account, relay, cloud server, or persistent trust. */
final class MultiplayerSession {
 interface Callback {void onChanged();}
 static final String IDLE="IDLE",WAITING="WAITING",DISCOVERING="DISCOVERING",CONNECTING="CONNECTING",PAIRING="PAIRING",HOST_SETUP="HOST_SETUP",WAIT_CONFIG="WAIT_CONFIG",CONFIGURING="CONFIGURING",PHOTO_RECEIVING="PHOTO_RECEIVING",CONFIGURED="CONFIGURED",READY="READY",COUNTDOWN="COUNTDOWN",MATCH="MATCH",RESULT="RESULT",DISCONNECTED="DISCONNECTED",PAIRING_MISMATCH="PAIRING_MISMATCH",PROTOCOL_ERROR="PROTOCOL_ERROR",CRYPTO_ERROR="CRYPTO_ERROR",PHOTO_TRANSFER_ERROR="PHOTO_TRANSFER_ERROR",PERMISSION_DENIED="PERMISSION_DENIED",P2P_UNSUPPORTED="P2P_UNSUPPORTED";
 static final String HOST="HOST",GUEST="GUEST",DIRECT="DIRECT",LAN="LAN";
 private static final int DIRECT_PORT=28771;
 private static final String SERVICE_TYPE="_slidepuzzle._tcp.";
 private static final long TIE_WINDOW_MS=250;

 static final class Room {
  final String label,transport,address,deviceAddress;final int port;
  Room(String label,String transport,String address,int port,String deviceAddress){this.label=label;this.transport=transport;this.address=address;this.port=port;this.deviceAddress=deviceAddress;}
 }

 private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newCachedThreadPool();
 private volatile Callback callback;private final ArrayList<Room> rooms=new ArrayList<>();
 volatile String state=IDLE,role="",transport="",sas="",errorMessage="",mode="number",configHash="",result="";
 volatile int size=3,countdown=0;volatile boolean localReady=false,remoteReady=false,rematchRequested=false;
 volatile Puzzle localPuzzle,remotePuzzle;volatile Bitmap photoBitmap;volatile long matchStartedAt=0,matchEndedAt=0,localFinishAt=0,remoteFinishAt=0;
 private volatile boolean localPaired=false,remotePaired=false,closing=false,socketConnecting=false,resultScheduled=false;
 private SecurePeer peer;private Socket socket;private ServerSocket server;
 private NsdManager nsd;private NsdManager.RegistrationListener registrationListener;private NsdManager.DiscoveryListener discoveryListener;
 private WifiP2pManager p2p;private WifiP2pManager.Channel p2pChannel;private BroadcastReceiver p2pReceiver;
 private ByteArrayOutputStream photoReceive;private int expectedPhotoBytes=0;private String expectedPhotoHash="";

 MultiplayerSession(Context context){this.context=context.getApplicationContext();}
 void attach(Callback callback){this.callback=callback;notifyChanged();}
 List<Room> rooms(){synchronized(rooms){return new ArrayList<>(rooms);}}
 boolean isConnectedPhase(){return peer!=null&&!(DISCONNECTED.equals(state)||PAIRING_MISMATCH.equals(state)||PROTOCOL_ERROR.equals(state)||CRYPTO_ERROR.equals(state)||PHOTO_TRANSFER_ERROR.equals(state));}
 long elapsed(){if(matchStartedAt<=0)return 0;long end=matchEndedAt>0?matchEndedAt:SystemClock.elapsedRealtime();return Math.max(0,end-matchStartedAt);}
 int remoteMoves(){Puzzle p=remotePuzzle;return p==null?0:p.moves;}
 String opponentStatus(){if(DISCONNECTED.equals(state))return "상대 연결 끊김";if(remotePuzzle!=null&&remotePuzzle.solved())return "상대 완성";return remoteReady?"상대 준비됨 · "+remoteMoves()+"번 이동":"상대 연결됨 · "+remoteMoves()+"번 이동";}

 void startHost(String transport){if(!IDLE.equals(state))return;this.role=HOST;this.transport=transport;if(DIRECT.equals(transport))startDirectHost();else startLanHost();}
 void startGuest(String transport){if(!IDLE.equals(state))return;this.role=GUEST;this.transport=transport;if(DIRECT.equals(transport))startDirectGuest();else startLanGuest();}

 private void startLanHost(){
  setState(WAITING,"같은 Wi-Fi에서 상대를 기다리는 중…");
  io.execute(()->{try{ServerSocket created=new ServerSocket(0);created.setReuseAddress(true);server=created;startAccept(created);main.post(()->registerLan(created.getLocalPort()));}catch(IOException e){fail(DISCONNECTED,"로컬 연결 방을 열 수 없습니다.");}});
 }
 private void registerLan(int port){
  if(closing)return;nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);if(nsd==null){fail(DISCONNECTED,"이 기기에서는 같은 Wi-Fi 검색을 사용할 수 없습니다.");return;}
  NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("SlidePuzzle-"+(100+new SecureRandom().nextInt(900)));info.setServiceType(SERVICE_TYPE);info.setPort(port);
  registrationListener=new NsdManager.RegistrationListener(){public void onRegistrationFailed(NsdServiceInfo i,int c){fail(DISCONNECTED,"같은 Wi-Fi 방을 알릴 수 없습니다.");}public void onUnregistrationFailed(NsdServiceInfo i,int c){}public void onServiceRegistered(NsdServiceInfo i){notifyChanged();}public void onServiceUnregistered(NsdServiceInfo i){}};
  try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registrationListener);}catch(RuntimeException e){fail(DISCONNECTED,"같은 Wi-Fi 방을 알릴 수 없습니다.");}
 }
 private void startLanGuest(){
  setState(DISCOVERING,"같은 Wi-Fi의 방을 찾는 중…");nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);if(nsd==null){fail(DISCONNECTED,"이 기기에서는 같은 Wi-Fi 검색을 사용할 수 없습니다.");return;}
  discoveryListener=new NsdManager.DiscoveryListener(){
   public void onDiscoveryStarted(String t){}
   public void onStartDiscoveryFailed(String t,int c){fail(DISCONNECTED,"같은 Wi-Fi 검색을 시작할 수 없습니다.");}
   public void onStopDiscoveryFailed(String t,int c){}
   public void onDiscoveryStopped(String t){}
   public void onServiceFound(NsdServiceInfo service){if(closing||service==null||!SERVICE_TYPE.equals(service.getServiceType()))return;resolveLan(service);}
   public void onServiceLost(NsdServiceInfo service){if(service==null)return;String name=service.getServiceName();synchronized(rooms){rooms.removeIf(r->r.label.equals(labelForService(name)));}notifyChanged();}
  };
  try{nsd.discoverServices(SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,discoveryListener);}catch(RuntimeException e){fail(DISCONNECTED,"같은 Wi-Fi 검색을 시작할 수 없습니다.");}
 }
 private void resolveLan(NsdServiceInfo service){
  try{nsd.resolveService(service,new NsdManager.ResolveListener(){public void onResolveFailed(NsdServiceInfo s,int c){}public void onServiceResolved(NsdServiceInfo resolved){
   if(closing||resolved.getHost()==null||resolved.getPort()<1)return;String label=labelForService(resolved.getServiceName());String address=resolved.getHost().getHostAddress();if(address==null)return;
   synchronized(rooms){boolean exists=false;for(Room r:rooms)if(r.label.equals(label)&&r.address.equals(address)&&r.port==resolved.getPort())exists=true;if(!exists)rooms.add(new Room(label,LAN,address,resolved.getPort(),""));}notifyChanged();
  }});}catch(RuntimeException ignored){}
 }
 private String labelForService(String serviceName){if(serviceName!=null&&serviceName.startsWith("SlidePuzzle-"))return "방 "+serviceName.substring("SlidePuzzle-".length());return "같은 Wi-Fi 방";}

 private void startDirectHost(){
  if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}
  initP2p();if(p2p==null)return;setState(WAITING,"가까운 기기의 참가를 기다리는 중…");
  io.execute(()->{try{ServerSocket created=new ServerSocket();created.setReuseAddress(true);created.bind(new InetSocketAddress(DIRECT_PORT));server=created;startAccept(created);}catch(IOException e){fail(DISCONNECTED,"직접 연결 방을 열 수 없습니다.");}});
  try{p2p.createGroup(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"직접 연결 방을 열 수 없습니다. 같은 Wi-Fi 연결을 이용해 주세요.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 private void startDirectGuest(){
  if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}
  initP2p();if(p2p==null)return;setState(DISCOVERING,"가까운 방을 찾는 중…");
  try{p2p.discoverPeers(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"가까운 기기를 찾을 수 없습니다. 같은 Wi-Fi 연결을 이용해 주세요.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 private void initP2p(){
  p2p=(WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);if(p2p==null){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}p2pChannel=p2p.initialize(context,Looper.getMainLooper(),()->fail(DISCONNECTED,"직접 연결을 다시 시작해 주세요."));
  IntentFilter filter=new IntentFilter();filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
  p2pReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(closing)return;String action=intent.getAction();if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)&&GUEST.equals(role))requestPeers();else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action))requestConnectionInfo();}};
  try{if(Build.VERSION.SDK_INT>=33)context.registerReceiver(p2pReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(p2pReceiver,filter);}catch(RuntimeException e){fail(P2P_UNSUPPORTED,"직접 연결 상태를 확인할 수 없습니다.");}
 }
 private void requestPeers(){try{p2p.requestPeers(p2pChannel,list->{synchronized(rooms){rooms.clear();int i=1;for(WifiP2pDevice device:list.getDeviceList())rooms.add(new Room("근처 방 "+(i++),DIRECT,"",DIRECT_PORT,device.deviceAddress));}notifyChanged();});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}}
 private void requestConnectionInfo(){try{p2p.requestConnectionInfo(p2pChannel,info->{if(info==null||!info.groupFormed||closing)return;if(GUEST.equals(role)&&!info.isGroupOwner&&info.groupOwnerAddress!=null&&!socketConnecting){socketConnecting=true;connectAddress(info.groupOwnerAddress.getHostAddress(),DIRECT_PORT);}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}}

 void connect(Room room){
  if(room==null||!GUEST.equals(role)||(peer!=null))return;setState(CONNECTING,"상대 기기에 연결하는 중…");
  if(LAN.equals(room.transport)){stopLanDiscovery();connectAddress(room.address,room.port);return;}
  if(DIRECT.equals(room.transport)){
   WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=room.deviceAddress;
   try{p2p.connect(p2pChannel,config,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){socketConnecting=false;fail(DISCONNECTED,"직접 연결에 실패했습니다.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
  }
 }
 private void connectAddress(String address,int port){
  if(address==null||port<1)return;io.execute(()->{try{Socket s=new Socket();s.connect(new InetSocketAddress(address,port),8000);s.setTcpNoDelay(true);establish(s);}catch(IOException e){socketConnecting=false;fail(DISCONNECTED,"상대 기기에 연결할 수 없습니다.");}});
 }
 private void startAccept(ServerSocket server){io.execute(()->{try{Socket accepted=server.accept();accepted.setTcpNoDelay(true);establish(accepted);}catch(IOException e){if(!closing)fail(DISCONNECTED,"상대 연결을 받을 수 없습니다.");}});}

 private void establish(Socket connected){
  if(closing){try{connected.close();}catch(IOException ignored){}return;}socket=connected;stopDiscoveryOnly();
  io.execute(()->{try{SecurePeer p=new SecurePeer(connected,HOST.equals(role));connected.setSoTimeout(10000);p.handshake();connected.setSoTimeout(0);peer=p;sas=p.keys.sas;setState(PAIRING,"양쪽 화면의 8자리 확인 코드를 비교해 주세요.");main.postDelayed(()->{synchronized(MultiplayerSession.this){if(PAIRING.equals(state)&&!closing)fail(DISCONNECTED,"연결 확인 시간이 지나 새로 연결해야 합니다.");}},60000);p.readLoop();if(!closing)fail(DISCONNECTED,"상대와의 연결이 끊겼습니다. 이번 대결은 승패 없이 종료되었습니다.");}
   catch(GeneralSecurityException e){if(!closing)fail(CRYPTO_ERROR,"연결을 안전하게 확인할 수 없어 종료했습니다.");}
   catch(IOException|RuntimeException e){if(!closing)fail(PROTOCOL_ERROR,"받은 데이터를 확인할 수 없어 연결을 종료했습니다.");}});
 }

 synchronized void confirmPairing(boolean matches){
  if(!PAIRING.equals(state)||peer==null)return;if(!matches){try{peer.send(MultiplayerProtocol.LEAVE,new byte[0]);}catch(Exception ignored){}fail(PAIRING_MISMATCH,"확인 코드가 다릅니다. 안전하게 연결을 종료했습니다.");return;}
  if(localPaired)return;localPaired=true;try{peer.send(MultiplayerProtocol.PAIR_CONFIRM,new byte[0]);activateIfPaired();}catch(Exception e){fail(CRYPTO_ERROR,"연결 확인을 전송할 수 없어 종료했습니다.");}
 }
 private synchronized void activateIfPaired(){if(localPaired&&remotePaired){setState(HOST.equals(role)?HOST_SETUP:WAIT_CONFIG,HOST.equals(role)?"대결 설정을 선택해 주세요.":"방장이 대결을 설정하는 중…");}}

 void configure(int size,String mode,byte[] photoBytes,Bitmap hostBitmap){
  if(!HOST.equals(role)||!HOST_SETUP.equals(state)||peer==null)return;if(size<3||size>6||!("number".equals(mode)||"photo".equals(mode)))return;
  if("photo".equals(mode)&&(photoBytes==null||photoBytes.length<1||photoBytes.length>MultiplayerProtocol.MAX_PHOTO||hostBitmap==null)){externalError(PHOTO_TRANSFER_ERROR,"전송할 게임 사진을 준비하지 못했습니다.");return;}
  setState(CONFIGURING,"대결 설정을 안전하게 전송하는 중…");
  io.execute(()->{try{
   Puzzle initial=new Puzzle(size);initial.shuffle(new SecureRandom());String board=initial.encode();String photoHash="photo".equals(mode)?MultiplayerProtocol.sha256Hex(photoBytes):"";String hash=MultiplayerProtocol.configHash(size,mode,board,photoHash);
   peer.send(MultiplayerProtocol.CONFIG,MultiplayerProtocol.packConfig(size,mode,board,photoHash,hash));
   if("photo".equals(mode)){
    peer.send(MultiplayerProtocol.PHOTO_BEGIN,MultiplayerProtocol.packPhotoBegin(photoHash,photoBytes.length));
    for(int offset=0;offset<photoBytes.length;offset+=MultiplayerProtocol.PHOTO_CHUNK){int n=Math.min(MultiplayerProtocol.PHOTO_CHUNK,photoBytes.length-offset);peer.send(MultiplayerProtocol.PHOTO_CHUNK_MSG,Arrays.copyOfRange(photoBytes,offset,offset+n));}
    peer.send(MultiplayerProtocol.PHOTO_END,MultiplayerProtocol.packString(photoHash,64));
   }
   synchronized(this){this.size=size;this.mode=mode;this.configHash=hash;this.localPuzzle=Puzzle.restore(size,board,0);this.remotePuzzle=Puzzle.restore(size,board,0);this.photoBitmap=hostBitmap;resetReadyAndResult();}
   setState(CONFIGURED,"양쪽 설정이 준비되었습니다.");
  }catch(Exception e){fail("photo".equals(mode)?PHOTO_TRANSFER_ERROR:PROTOCOL_ERROR,"대결 설정을 전송하지 못했습니다.");}});
 }

 synchronized void ready(){if(!(CONFIGURED.equals(state)||READY.equals(state))||peer==null||localReady||configHash.isEmpty())return;try{peer.send(MultiplayerProtocol.READY,MultiplayerProtocol.packString(configHash,64));localReady=true;setState(READY,remoteReady?"상대도 준비되었습니다.":"상대 준비를 기다리는 중…");maybeStart();}catch(Exception e){fail(PROTOCOL_ERROR,"준비 상태를 전송하지 못했습니다.");}}
 private synchronized void maybeStart(){if(!HOST.equals(role)||!localReady||!remoteReady||peer==null)return;try{peer.send(MultiplayerProtocol.START,MultiplayerProtocol.packString(configHash,64));startCountdown();}catch(Exception e){fail(PROTOCOL_ERROR,"대결 시작 신호를 전송하지 못했습니다.");}}
 private synchronized void startCountdown(){countdown=3;setState(COUNTDOWN,"3");for(int step=1;step<=3;step++){final int s=step;main.postDelayed(()->{synchronized(MultiplayerSession.this){if(!COUNTDOWN.equals(state)||closing)return;if(s<3){countdown=3-s;notifyChanged();}else{countdown=0;matchStartedAt=SystemClock.elapsedRealtime();setState(MATCH,"대결 중");}}},step*1000L);}}

 synchronized boolean move(int index){
  if(!MATCH.equals(state)||localPuzzle==null||peer==null||!localPuzzle.adjacent(index)||localPuzzle.solved())return false;
  if(!localPuzzle.move(index))return false;try{peer.send(MultiplayerProtocol.MOVE,MultiplayerProtocol.packMove(configHash,index));}catch(Exception e){fail(PROTOCOL_ERROR,"이동 정보를 전송하지 못했습니다.");return false;}
  if(localPuzzle.solved()){localFinishAt=SystemClock.elapsedRealtime();if(HOST.equals(role))scheduleResult();}return true;
 }

 synchronized void rematch(){
  if(!RESULT.equals(state)||peer==null)return;try{if(HOST.equals(role)){peer.send(MultiplayerProtocol.REMATCH,new byte[]{1});resetRound();setState(HOST_SETUP,"다음 대결 설정을 선택해 주세요.");}else{peer.send(MultiplayerProtocol.REMATCH,new byte[]{0});rematchRequested=true;notifyChanged();}}catch(Exception e){fail(PROTOCOL_ERROR,"재대결 요청을 전송하지 못했습니다.");}
 }

 private void onMessage(byte type,byte[] payload) throws Exception {
  synchronized(this){
   if(type==MultiplayerProtocol.LEAVE){fail(DISCONNECTED,"상대가 나갔습니다. 이번 대결은 승패 없이 종료되었습니다.");return;}
   if(!(localPaired&&remotePaired)){
    if(type!=MultiplayerProtocol.PAIR_CONFIRM)throw new IOException("message before pair confirm");if(remotePaired)throw new IOException("duplicate pair confirm");remotePaired=true;activateIfPaired();return;
   }
   switch(type){
    case MultiplayerProtocol.PAIR_CONFIRM: throw new IOException("late pair confirm");
    case MultiplayerProtocol.CONFIG: handleConfig(payload);break;
    case MultiplayerProtocol.PHOTO_BEGIN: handlePhotoBegin(payload);break;
    case MultiplayerProtocol.PHOTO_CHUNK_MSG: handlePhotoChunk(payload);break;
    case MultiplayerProtocol.PHOTO_END: handlePhotoEnd(payload);break;
    case MultiplayerProtocol.READY: handleReady(payload);break;
    case MultiplayerProtocol.START: handleStart(payload);break;
    case MultiplayerProtocol.MOVE: handleMove(payload);break;
    case MultiplayerProtocol.RESULT: handleResult(payload);break;
    case MultiplayerProtocol.REMATCH: handleRematch(payload);break;
    default: throw new IOException("unknown message");
   }
  }
 }
 private void handleConfig(byte[] payload) throws Exception {
  if(!GUEST.equals(role)||!(WAIT_CONFIG.equals(state)||CONFIGURED.equals(state)||READY.equals(state)||RESULT.equals(state)))throw new IOException("unexpected config");
  MultiplayerProtocol.Config c=MultiplayerProtocol.unpackConfig(payload);if(c.size<3||c.size>6||!("number".equals(c.mode)||"photo".equals(c.mode)))throw new IOException("config values");Puzzle lp=Puzzle.restore(c.size,c.board,0);Puzzle rp=Puzzle.restore(c.size,c.board,0);String expected=MultiplayerProtocol.configHash(c.size,c.mode,c.board,c.photoHash);if(!expected.equals(c.hash))throw new IOException("config hash");
  this.size=c.size;this.mode=c.mode;this.configHash=c.hash;this.localPuzzle=lp;this.remotePuzzle=rp;this.photoBitmap=null;resetReadyAndResult();expectedPhotoHash=c.photoHash;
  if("photo".equals(c.mode)){if(c.photoHash.length()!=64)throw new IOException("photo hash");setState(PHOTO_RECEIVING,"게임 사진을 받는 중…");}else{if(!c.photoHash.isEmpty())throw new IOException("unexpected photo hash");setState(CONFIGURED,"대결 설정을 받았습니다.");}
 }
 private void handlePhotoBegin(byte[] payload) throws Exception {if(!GUEST.equals(role)||!PHOTO_RECEIVING.equals(state)||!"photo".equals(mode)||photoReceive!=null)throw new IOException("photo begin state");expectedPhotoBytes=MultiplayerProtocol.unpackPhotoBegin(payload,expectedPhotoHash);photoReceive=new ByteArrayOutputStream(Math.min(expectedPhotoBytes,256*1024));}
 private void handlePhotoChunk(byte[] payload) throws Exception {if(photoReceive==null||payload.length<1||payload.length>MultiplayerProtocol.PHOTO_CHUNK||photoReceive.size()+payload.length>expectedPhotoBytes)throw new IOException("photo chunk");photoReceive.write(payload);}
 private void handlePhotoEnd(byte[] payload) throws Exception {
  if(photoReceive==null)throw new IOException("photo end state");String hash=MultiplayerProtocol.unpackString(payload,64);byte[] data=photoReceive.toByteArray();photoReceive=null;if(data.length!=expectedPhotoBytes||!expectedPhotoHash.equals(hash)||!hash.equals(MultiplayerProtocol.sha256Hex(data)))throw new IOException("photo integrity");
  Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length);if(bitmap==null||bitmap.getWidth()!=bitmap.getHeight()||bitmap.getWidth()>1440||bitmap.getWidth()<16){if(bitmap!=null)bitmap.recycle();throw new IOException("photo decode");}photoBitmap=bitmap;setState(CONFIGURED,"대결 설정과 게임 사진을 받았습니다.");
 }
 private void handleReady(byte[] payload) throws Exception {String hash=MultiplayerProtocol.unpackString(payload,64);if(!configHash.equals(hash)||localPuzzle==null)throw new IOException("ready config");if(remoteReady)throw new IOException("duplicate ready");remoteReady=true;if(CONFIGURED.equals(state)||READY.equals(state))setState(READY,localReady?"양쪽 모두 준비되었습니다.":"상대가 준비되었습니다.");maybeStart();}
 private void handleStart(byte[] payload) throws Exception {if(!GUEST.equals(role)||!localReady||!remoteReady||!READY.equals(state)||!configHash.equals(MultiplayerProtocol.unpackString(payload,64)))throw new IOException("start state");startCountdown();}
 private void handleMove(byte[] payload) throws Exception {if(!(MATCH.equals(state)||COUNTDOWN.equals(state))||remotePuzzle==null)throw new IOException("move state");int index=MultiplayerProtocol.unpackMove(payload,configHash);if(!remotePuzzle.move(index))throw new IOException("illegal move");if(remotePuzzle.solved()){remoteFinishAt=SystemClock.elapsedRealtime();if(HOST.equals(role))scheduleResult();}notifyChanged();}
 private void scheduleResult(){if(resultScheduled)return;resultScheduled=true;main.postDelayed(()->{synchronized(MultiplayerSession.this){if(!HOST.equals(role)||RESULT.equals(state)||closing)return;boolean local=localPuzzle!=null&&localPuzzle.solved(),remote=remotePuzzle!=null&&remotePuzzle.solved();if(!local&&!remote){resultScheduled=false;return;}String code;if(local&&remote&&Math.abs(localFinishAt-remoteFinishAt)<=TIE_WINDOW_MS)code="TIE";else if(local&&(!remote||localFinishAt<remoteFinishAt))code="HOST";else code="GUEST";try{peer.send(MultiplayerProtocol.RESULT,packResult(configHash,code));applyResult(code);}catch(Exception e){fail(PROTOCOL_ERROR,"대결 결과를 전송하지 못했습니다.");}}},TIE_WINDOW_MS);}
 private void handleResult(byte[] payload) throws Exception {if(!GUEST.equals(role)||(localPuzzle==null||remotePuzzle==null))throw new IOException("result state");String[] pair=unpackResult(payload);if(!configHash.equals(pair[0]))throw new IOException("stale result");String code=pair[1];if("HOST".equals(code)&&!remotePuzzle.solved())throw new IOException("false host result");if("GUEST".equals(code)&&!localPuzzle.solved())throw new IOException("false guest result");if("TIE".equals(code)&&!(localPuzzle.solved()&&remotePuzzle.solved()))throw new IOException("false tie");applyResult(code);}
 private void applyResult(String code){if(matchEndedAt==0)matchEndedAt=SystemClock.elapsedRealtime();if("TIE".equals(code))result="TIE";else if((HOST.equals(role)&&"HOST".equals(code))||(GUEST.equals(role)&&"GUEST".equals(code)))result="WIN";else result="LOSS";setState(RESULT,"WIN".equals(result)?"이겼어요!":"LOSS".equals(result)?"상대가 먼저 완성했어요":"동시에 완성했어요!");}
 private void handleRematch(byte[] payload) throws Exception {if(payload.length!=1||!RESULT.equals(state))throw new IOException("rematch state");if(HOST.equals(role)){if(payload[0]!=0)throw new IOException("rematch direction");rematchRequested=true;notifyChanged();}else{if(payload[0]!=1)throw new IOException("rematch direction");resetRound();setState(WAIT_CONFIG,"방장이 다음 대결을 설정하는 중…");}}
 private static byte[] packResult(String hash,String code) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);byte[] h=MultiplayerProtocol.packString(hash,64),c=MultiplayerProtocol.packString(code,8);o.writeShort(h.length);o.write(h);o.writeShort(c.length);o.write(c);o.flush();return b.toByteArray();}
 private static String[] unpackResult(byte[] data) throws IOException {DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));int a=in.readUnsignedShort();if(a<1||a>70||a>in.available())throw new IOException("result hash");byte[] ab=new byte[a];in.readFully(ab);int b=in.readUnsignedShort();if(b<1||b>16||b>in.available())throw new IOException("result code");byte[] bb=new byte[b];in.readFully(bb);if(in.available()!=0)throw new IOException("result trailing");return new String[]{MultiplayerProtocol.unpackString(ab,64),MultiplayerProtocol.unpackString(bb,8)};}

 private void resetReadyAndResult(){localReady=false;remoteReady=false;result="";rematchRequested=false;countdown=0;matchStartedAt=0;matchEndedAt=0;localFinishAt=0;remoteFinishAt=0;resultScheduled=false;}
 private void resetRound(){resetReadyAndResult();localPuzzle=null;remotePuzzle=null;configHash="";mode="number";size=3;if(photoBitmap!=null&&GUEST.equals(role)){photoBitmap.recycle();}photoBitmap=null;photoReceive=null;expectedPhotoBytes=0;expectedPhotoHash="";}

 void externalError(String state,String message){fail(state,message);}
 void close(){closing=true;try{if(peer!=null)peer.send(MultiplayerProtocol.LEAVE,new byte[0]);}catch(Exception ignored){}shutdownTransport();io.shutdownNow();}
 private void fail(String state,String message){if(closing)return;this.state=state;this.errorMessage=message;closing=true;shutdownTransport();notifyChanged();}
 private void setState(String state,String message){if(closing&&!(DISCONNECTED.equals(state)||PAIRING_MISMATCH.equals(state)||PROTOCOL_ERROR.equals(state)||CRYPTO_ERROR.equals(state)||PHOTO_TRANSFER_ERROR.equals(state)||PERMISSION_DENIED.equals(state)||P2P_UNSUPPORTED.equals(state)))return;this.state=state;this.errorMessage=message;notifyChanged();}
 private void notifyChanged(){Callback cb=callback;if(cb!=null)main.post(()->{Callback current=callback;if(current!=null)current.onChanged();});}

 private void stopDiscoveryOnly(){stopLanDiscovery();if(p2p!=null&&p2pChannel!=null)try{p2p.stopPeerDiscovery(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){};}
 private void stopLanDiscovery(){if(nsd!=null&&discoveryListener!=null)try{nsd.stopServiceDiscovery(discoveryListener);}catch(RuntimeException ignored){}discoveryListener=null;}
 private void shutdownTransport(){
  stopLanDiscovery();if(nsd!=null&&registrationListener!=null)try{nsd.unregisterService(registrationListener);}catch(RuntimeException ignored){}registrationListener=null;
  if(p2pReceiver!=null)try{context.unregisterReceiver(p2pReceiver);}catch(RuntimeException ignored){}p2pReceiver=null;
  if(p2p!=null&&p2pChannel!=null)try{p2p.removeGroup(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){}
  try{if(socket!=null)socket.close();}catch(IOException ignored){}try{if(server!=null)server.close();}catch(IOException ignored){}socket=null;server=null;
 }

 private final class SecurePeer {
  final Socket socket;final boolean host;final DataInputStream in;final DataOutputStream out;MultiplayerProtocol.Keys keys;long sendSeq=1,recvSeq=1;
  SecurePeer(Socket socket,boolean host)throws IOException{this.socket=socket;this.host=host;in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
  void handshake() throws IOException,GeneralSecurityException {
   KeyPair pair=MultiplayerProtocol.newKeyPair();byte[] nonce=MultiplayerProtocol.newNonce(),localPublic=pair.getPublic().getEncoded();MultiplayerProtocol.Hello remote;
   if(host){writeHandshake(MultiplayerProtocol.encodeHello(localPublic,nonce));remote=readHandshake();keys=MultiplayerProtocol.derive(true,pair.getPrivate(),localPublic,remote.publicKey,nonce,remote.nonce);}
   else{remote=readHandshake();writeHandshake(MultiplayerProtocol.encodeHello(localPublic,nonce));keys=MultiplayerProtocol.derive(false,pair.getPrivate(),remote.publicKey,localPublic,remote.nonce,nonce);}
  }
  private void writeHandshake(byte[] data)throws IOException{synchronized(out){out.writeInt(data.length);out.write(data);out.flush();}}
  private MultiplayerProtocol.Hello readHandshake()throws IOException{int length=in.readInt();if(length<1||length>MultiplayerProtocol.MAX_HANDSHAKE)throw new IOException("handshake size");byte[] data=new byte[length];in.readFully(data);return MultiplayerProtocol.decodeHello(data);}
  synchronized void send(byte type,byte[] payload)throws IOException,GeneralSecurityException {if(keys==null)throw new IOException("not secure");byte[] frame=MultiplayerProtocol.seal(keys.sendKey,keys.sendMarker,sendSeq++,type,payload);if(frame.length>MultiplayerProtocol.MAX_CONTROL+64)throw new IOException("frame too large");out.writeInt(frame.length);out.write(frame);out.flush();}
  void readLoop()throws IOException,GeneralSecurityException {while(!closing){int length;try{length=in.readInt();}catch(EOFException eof){return;}if(length<25||length>MultiplayerProtocol.MAX_CONTROL+64)throw new IOException("frame size");byte[] frame=new byte[length];in.readFully(frame);MultiplayerProtocol.Decoded decoded=MultiplayerProtocol.open(keys.recvKey,keys.recvMarker,recvSeq++,frame);try{onMessage(decoded.type,decoded.payload);}catch(GeneralSecurityException e){throw e;}catch(IOException e){throw e;}catch(Exception e){throw new IOException("message",e);}}}
 }
}
