package com.shaterguy.slidingpuzzle;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.*;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** One-peer, serverless multiplayer session. No account, relay, cloud server, or persistent trust. */
final class MultiplayerSession {
 interface Callback {void onChanged();}
 static final String IDLE="IDLE",WAITING="WAITING",DISCOVERING="DISCOVERING",CONNECTING="CONNECTING",PAIRING="PAIRING",HOST_SETUP="HOST_SETUP",WAIT_CONFIG="WAIT_CONFIG",CONFIGURING="CONFIGURING",PHOTO_RECEIVING="PHOTO_RECEIVING",CONFIGURED="CONFIGURED",READY="READY",COUNTDOWN="COUNTDOWN",MATCH="MATCH",RESULT="RESULT",DISCONNECTED="DISCONNECTED",PAIRING_MISMATCH="PAIRING_MISMATCH",PROTOCOL_ERROR="PROTOCOL_ERROR",CRYPTO_ERROR="CRYPTO_ERROR",PHOTO_TRANSFER_ERROR="PHOTO_TRANSFER_ERROR",PERMISSION_DENIED="PERMISSION_DENIED",P2P_UNSUPPORTED="P2P_UNSUPPORTED";
 static final String HOST="HOST",GUEST="GUEST",DIRECT="DIRECT",LAN="LAN";
 private static final String LAN_SERVICE_TYPE="_slidepuzzle._tcp.";
 private static final String P2P_SERVICE_TYPE="_slidepuzzle._tcp";
 private static final long TIE_WINDOW_MS=250;
 private static final long HEARTBEAT_INTERVAL_MS=5000;
 private static final long HEARTBEAT_TIMEOUT_MS=15000;

 static final class Room {
  final String label,transport,address,deviceAddress,sessionIdHex;final int port;
  Room(String label,String transport,String address,int port,String deviceAddress,String sessionIdHex){this.label=label;this.transport=transport;this.address=address;this.port=port;this.deviceAddress=deviceAddress;this.sessionIdHex=sessionIdHex;}
 }

 private static final class SerialExecutor {
  private final Executor executor;private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();private Runnable active;
  SerialExecutor(Executor executor){this.executor=executor;}
  synchronized boolean execute(Runnable command){tasks.offer(()->{try{command.run();}finally{finished();}});if(active!=null)return true;return scheduleNext();}
  private synchronized void finished(){active=null;scheduleNext();}
  private boolean scheduleNext(){Runnable next=tasks.poll();if(next==null)return true;active=next;try{executor.execute(next);return true;}catch(RejectedExecutionException rejected){active=null;tasks.clear();return false;}}
  synchronized void clear(){tasks.clear();}
 }

 private final Context context;private final ExecutorService io=Executors.newCachedThreadPool();private final SerialExecutor outbound=new SerialExecutor(io);private final Handler main=new Handler(Looper.getMainLooper());private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
 private volatile Callback callback;private final ArrayList<Room> rooms=new ArrayList<>();
 volatile String state=IDLE,role="",transport="",sas="",errorMessage="",mode="number",configHash="",result="";
 volatile int size=3,countdown=0;volatile boolean localReady=false,remoteReady=false,rematchRequested=false;
 volatile Puzzle localPuzzle,remotePuzzle;volatile Bitmap photoBitmap;volatile long matchStartedAt=0,matchEndedAt=0,localFinishAt=0,remoteFinishAt=0;
 private volatile boolean localPaired=false,remotePaired=false,closing=false,socketConnecting=false,resultScheduled=false,localStarted=false,remoteStarted=false;private boolean pairingSendPending=false,readySendPending=false,rematchSendPending=false;
 private volatile long lastPeerTrafficAt=0;private ScheduledFuture<?> heartbeatFuture;
 private SecurePeer peer;private Socket socket;private ServerSocket server;private byte[] sessionId,matchId;
 private int pendingDirectPort=0;
 private NsdManager nsd;private NsdManager.RegistrationListener registrationListener;private NsdManager.DiscoveryListener discoveryListener;
 private WifiP2pManager p2p;private WifiP2pManager.Channel p2pChannel;private BroadcastReceiver p2pReceiver;private WifiP2pDnsSdServiceInfo p2pService;private WifiP2pDnsSdServiceRequest p2pRequest;
 private File photoReceiveFile;private OutputStream photoReceiveOut;private MessageDigest photoReceiveDigest;private int expectedPhotoBytes=0,receivedPhotoBytes=0,expectedPhotoWidth=0,expectedPhotoHeight=0;private String expectedPhotoHash="";

 MultiplayerSession(Context context){this.context=context.getApplicationContext();cleanupStaleMultiplayerCache();}
 void attach(Callback callback){this.callback=callback;notifyChanged();}
 List<Room> rooms(){synchronized(rooms){return new ArrayList<>(rooms);}}
 boolean isConnectedPhase(){return peer!=null&&!(DISCONNECTED.equals(state)||PAIRING_MISMATCH.equals(state)||PROTOCOL_ERROR.equals(state)||CRYPTO_ERROR.equals(state)||PHOTO_TRANSFER_ERROR.equals(state));}
 long elapsed(){if(matchStartedAt<=0)return 0;long end=matchEndedAt>0?matchEndedAt:SystemClock.elapsedRealtime();return Math.max(0,end-matchStartedAt);}
 int remoteMoves(){Puzzle p=remotePuzzle;return p==null?0:p.moves;}
 String opponentStatus(){if(DISCONNECTED.equals(state))return "상대 연결 끊김";if(remotePuzzle!=null&&remotePuzzle.solved())return "상대 완성";return remoteReady?"상대 준비됨 · "+remoteMoves()+"번 이동":"상대 연결됨 · "+remoteMoves()+"번 이동";}

 void startHost(String transport){if(!IDLE.equals(state))return;this.role=HOST;this.transport=transport;this.sessionId=MultiplayerProtocol.newId();if(DIRECT.equals(transport))startDirectHost();else startLanHost();}
 void startGuest(String transport){if(!IDLE.equals(state))return;this.role=GUEST;this.transport=transport;this.sessionId=null;if(DIRECT.equals(transport))startDirectGuest();else startLanGuest();}

 private void startLanHost(){
  setState(WAITING,"같은 Wi-Fi에서 상대를 기다리는 중…");
  io.execute(()->{try{ServerSocket created=new ServerSocket(0);created.setReuseAddress(true);server=created;startAccept(created);main.post(()->registerLan(created.getLocalPort()));}catch(IOException e){fail(DISCONNECTED,"로컬 연결 방을 열 수 없습니다.");}});
 }
 private void registerLan(int port){
  if(closing||!MultiplayerProtocol.isValidId(sessionId))return;nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);if(nsd==null){fail(DISCONNECTED,"이 기기에서는 같은 Wi-Fi 검색을 사용할 수 없습니다.");return;}
  NsdServiceInfo info=new NsdServiceInfo();String sid=MultiplayerProtocol.idHex(sessionId);info.setServiceName("SlidePuzzle-"+sid.substring(0,8));info.setServiceType(LAN_SERVICE_TYPE);info.setPort(port);info.setAttribute("v",String.valueOf(MultiplayerProtocol.VERSION));info.setAttribute("sid",sid);
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
   public void onServiceFound(NsdServiceInfo service){if(closing||service==null||!LAN_SERVICE_TYPE.equals(service.getServiceType()))return;resolveLan(service);}
   public void onServiceLost(NsdServiceInfo service){if(service==null)return;String lost=labelForService(service.getServiceName());synchronized(rooms){rooms.removeIf(r->LAN.equals(r.transport)&&lost.startsWith(r.label));}notifyChanged();}
  };
  try{nsd.discoverServices(LAN_SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD,discoveryListener);}catch(RuntimeException e){fail(DISCONNECTED,"같은 Wi-Fi 검색을 시작할 수 없습니다.");}
 }
 private void resolveLan(NsdServiceInfo service){
  try{nsd.resolveService(service,new NsdManager.ResolveListener(){public void onResolveFailed(NsdServiceInfo s,int c){}public void onServiceResolved(NsdServiceInfo resolved){
   if(closing||resolved.getHost()==null||resolved.getPort()<1||resolved.getPort()>65535)return;Map<String,byte[]> attrs=resolved.getAttributes();String version=attr(attrs,"v"),sidText=attr(attrs,"sid");byte[] sid;try{if(!String.valueOf(MultiplayerProtocol.VERSION).equals(version))return;sid=MultiplayerProtocol.parseId(sidText);}catch(IOException bad){return;}String address=resolved.getHost().getHostAddress();if(address==null)return;String label=labelForSession(sid);
   synchronized(rooms){boolean exists=false;for(Room r:rooms)if(LAN.equals(r.transport)&&r.sessionIdHex.equals(sidText)&&r.address.equals(address)&&r.port==resolved.getPort())exists=true;if(!exists)rooms.add(new Room(label,LAN,address,resolved.getPort(),"",sidText));}notifyChanged();
  }});}catch(RuntimeException ignored){}
 }
 private String attr(Map<String,byte[]> attrs,String key){if(attrs==null)return "";byte[] value=attrs.get(key);return value==null?"":new String(value,StandardCharsets.UTF_8);}
 private String labelForService(String serviceName){if(serviceName!=null&&serviceName.startsWith("SlidePuzzle-"))return "방 "+serviceName.substring("SlidePuzzle-".length());return "같은 Wi-Fi 방";}
 private String labelForSession(byte[] sid){String hex=MultiplayerProtocol.idHex(sid);return "방 "+hex.substring(0,6);}

 private void startDirectHost(){
  if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}
  initP2p();if(p2p==null)return;setState(WAITING,"가까운 기기의 참가를 기다리는 중…");
  try{p2p.createGroup(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){requestDirectHostEndpoint(0);}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"직접 연결 방을 열 수 없습니다. 같은 Wi-Fi 연결을 이용해 주세요.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 private void requestDirectHostEndpoint(int attempt){
  if(closing||p2p==null||p2pChannel==null)return;try{p2p.requestConnectionInfo(p2pChannel,info->{if(closing)return;if(info!=null&&info.groupFormed&&info.isGroupOwner&&info.groupOwnerAddress!=null){openDirectHostServer(info.groupOwnerAddress);return;}if(attempt<9)main.postDelayed(()->requestDirectHostEndpoint(attempt+1),200);else fail(P2P_UNSUPPORTED,"직접 연결 방의 로컬 주소를 확인할 수 없습니다.");});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 private void openDirectHostServer(InetAddress bindAddress){
  io.execute(()->{try{ServerSocket created=new ServerSocket();created.setReuseAddress(true);created.bind(new InetSocketAddress(bindAddress,0));server=created;int port=created.getLocalPort();startAccept(created);main.post(()->advertiseDirect(port));}catch(IOException e){fail(DISCONNECTED,"직접 연결 방을 열 수 없습니다.");}});
 }
 private void advertiseDirect(int port){
  if(closing||p2p==null||!MultiplayerProtocol.isValidId(sessionId))return;String sid=MultiplayerProtocol.idHex(sessionId);Map<String,String> record=new HashMap<>();record.put("v",String.valueOf(MultiplayerProtocol.VERSION));record.put("sid",sid);record.put("port",String.valueOf(port));p2pService=WifiP2pDnsSdServiceInfo.newInstance("SlidePuzzle-"+sid.substring(0,8),P2P_SERVICE_TYPE,record);
  try{p2p.addLocalService(p2pChannel,p2pService,new WifiP2pManager.ActionListener(){public void onSuccess(){notifyChanged();}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"직접 연결 방을 알릴 수 없습니다.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 static boolean isDirectServiceDomain(String fullDomain){if(fullDomain==null)return false;String domain=fullDomain.toLowerCase(Locale.US);String suffix="."+P2P_SERVICE_TYPE.toLowerCase(Locale.US)+".local";return domain.endsWith(suffix)||domain.endsWith(suffix+".");}
 private void startDirectGuest(){
  if(!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}
  initP2p();if(p2p==null)return;setState(DISCOVERING,"가까운 방을 찾는 중…");
  p2p.setDnsSdResponseListeners(p2pChannel,(instance,registration,device)->{},(fullDomain,record,device)->{
   if(closing||device==null||device.deviceAddress==null||record==null||!isDirectServiceDomain(fullDomain))return;String version=record.get("v"),sidText=record.get("sid"),portText=record.get("port");byte[] sid;int port;try{if(!String.valueOf(MultiplayerProtocol.VERSION).equals(version))return;sid=MultiplayerProtocol.parseId(sidText);port=Integer.parseInt(portText);if(port<1||port>65535)return;}catch(Exception bad){return;}String label=labelForSession(sid);synchronized(rooms){boolean exists=false;for(Room r:rooms)if(DIRECT.equals(r.transport)&&r.deviceAddress.equals(device.deviceAddress)&&r.sessionIdHex.equals(sidText)&&r.port==port)exists=true;if(!exists)rooms.add(new Room(label,DIRECT,"",port,device.deviceAddress,sidText));}notifyChanged();
  });
  p2pRequest=WifiP2pDnsSdServiceRequest.newInstance(P2P_SERVICE_TYPE);
  try{p2p.addServiceRequest(p2pChannel,p2pRequest,new WifiP2pManager.ActionListener(){public void onSuccess(){try{p2p.discoverServices(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"가까운 방을 찾을 수 없습니다. 같은 Wi-Fi 연결을 이용해 주세요.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}}public void onFailure(int reason){fail(P2P_UNSUPPORTED,"가까운 방 검색을 준비할 수 없습니다.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
 }
 private void initP2p(){
  p2p=(WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);if(p2p==null){fail(P2P_UNSUPPORTED,"이 기기에서는 직접 연결을 사용할 수 없습니다.");return;}p2pChannel=p2p.initialize(context,Looper.getMainLooper(),()->fail(DISCONNECTED,"직접 연결을 다시 시작해 주세요."));
  IntentFilter filter=new IntentFilter();filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
  p2pReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){if(closing)return;if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(intent.getAction())&&GUEST.equals(role))requestConnectionInfo();}};
  try{if(Build.VERSION.SDK_INT>=33)context.registerReceiver(p2pReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(p2pReceiver,filter);}catch(RuntimeException e){fail(P2P_UNSUPPORTED,"직접 연결 상태를 확인할 수 없습니다.");}
 }
 private void requestConnectionInfo(){try{p2p.requestConnectionInfo(p2pChannel,info->{if(info==null||!info.groupFormed||closing)return;if(GUEST.equals(role)&&!info.isGroupOwner&&info.groupOwnerAddress!=null&&!socketConnecting&&pendingDirectPort>0){socketConnecting=true;connectAddress(info.groupOwnerAddress.getHostAddress(),pendingDirectPort,false);}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}}

 void connect(Room room){
  if(room==null||!GUEST.equals(role)||(peer!=null))return;byte[] sid;try{sid=MultiplayerProtocol.parseId(room.sessionIdHex);}catch(IOException invalid){fail(PROTOCOL_ERROR,"선택한 방 정보를 확인할 수 없습니다.");return;}sessionId=sid;setState(CONNECTING,"상대 기기에 연결하는 중…");
  if(LAN.equals(room.transport)){stopLanDiscovery();connectAddress(room.address,room.port,true);return;}
  if(DIRECT.equals(room.transport)){
   if(room.deviceAddress==null||room.deviceAddress.isEmpty()||room.port<1||room.port>65535){fail(PROTOCOL_ERROR,"선택한 방 정보를 확인할 수 없습니다.");return;}pendingDirectPort=room.port;WifiP2pConfig config=new WifiP2pConfig();config.deviceAddress=room.deviceAddress;
   try{p2p.connect(p2pChannel,config,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int reason){socketConnecting=false;fail(DISCONNECTED,"직접 연결에 실패했습니다.");}});}catch(SecurityException e){externalError(PERMISSION_DENIED,"주변 기기 권한이 없어 직접 연결할 수 없습니다.");}
  }
 }
 private void connectAddress(String address,int port,boolean bindWifi){
  if(address==null||port<1||port>65535||!MultiplayerProtocol.isValidId(sessionId)){fail(PROTOCOL_ERROR,"연결할 방 정보를 확인할 수 없습니다.");return;}io.execute(()->{try{Socket s=bindWifi?newWifiBoundSocket():new Socket();s.connect(new InetSocketAddress(address,port),8000);s.setTcpNoDelay(true);establish(s);}catch(IOException e){socketConnecting=false;fail(DISCONNECTED,"상대 기기에 연결할 수 없습니다.");}});
 }
 private Socket newWifiBoundSocket() throws IOException {
  ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)throw new IOException("no connectivity manager");Network selected=null;Network active=cm.getActiveNetwork();if(active!=null){NetworkCapabilities caps=cm.getNetworkCapabilities(active);if(isLocalWifi(caps))selected=active;}if(selected==null)for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(isLocalWifi(caps)){selected=n;break;}}if(selected==null)throw new IOException("no wifi network");return selected.getSocketFactory().createSocket();
 }
 static boolean acceptsWifiTransport(boolean wifi,boolean vpn){return wifi&&!vpn;}
 private boolean isLocalWifi(NetworkCapabilities caps){return caps!=null&&acceptsWifiTransport(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));}
 private void startAccept(ServerSocket listening){io.execute(()->{try{Socket accepted=listening.accept();try{listening.close();}catch(IOException ignored){}if(server==listening)server=null;accepted.setTcpNoDelay(true);establish(accepted);}catch(IOException e){if(!closing)fail(DISCONNECTED,"상대 연결을 받을 수 없습니다.");}});}

 private void establish(Socket connected){
  if(closing||!MultiplayerProtocol.isValidId(sessionId)){try{connected.close();}catch(IOException ignored){}return;}socket=connected;stopDiscoveryOnly();
  io.execute(()->{try{SecurePeer p=new SecurePeer(connected,HOST.equals(role),sessionId);connected.setSoTimeout(10000);p.handshake();connected.setSoTimeout(0);peer=p;sas=p.keys.sas;lastPeerTrafficAt=SystemClock.elapsedRealtime();setState(PAIRING,"양쪽 화면의 8자리 확인 코드를 비교해 주세요.");main.postDelayed(this::handlePairingTimeout,60000);p.readLoop();if(!closing)fail(DISCONNECTED,"상대와의 연결이 끊겼습니다. 이번 대결은 승패 없이 종료되었습니다.");}
   catch(GeneralSecurityException e){if(!closing)fail(CRYPTO_ERROR,"연결을 안전하게 확인할 수 없어 종료했습니다.");}
   catch(IOException|RuntimeException e){if(!closing)fail(PROTOCOL_ERROR,"받은 데이터를 확인할 수 없어 연결을 종료했습니다.");}});
 }

 private boolean sendAsync(byte type,byte[] payload,String failureState,String failureMessage,Runnable onSuccess){
  final byte[] body=payload==null?new byte[0]:Arrays.copyOf(payload,payload.length);
  boolean accepted=outbound.execute(()->{try{SecurePeer target;synchronized(MultiplayerSession.this){if(closing)return;target=peer;}if(target==null)throw new IOException("peer unavailable");target.send(type,body);if(onSuccess!=null)synchronized(MultiplayerSession.this){if(!closing)onSuccess.run();}}catch(Exception e){fail(failureState,failureMessage);}});
  if(!accepted&&!closing)fail(failureState,failureMessage);return accepted;
 }

 synchronized void confirmPairing(boolean matches){
  if(!PAIRING.equals(state))return;
  if(!matches){fail(PAIRING_MISMATCH,"확인 코드가 다릅니다. 안전하게 연결을 종료했습니다.");return;}
  if(peer==null||localPaired||pairingSendPending)return;pairingSendPending=true;sendAsync(MultiplayerProtocol.PAIR_CONFIRM,new byte[0],CRYPTO_ERROR,"연결 확인을 전송할 수 없어 종료했습니다.",()->{pairingSendPending=false;localPaired=true;activateIfPaired();});
 }
 synchronized void handlePairingTimeout(){if(PAIRING.equals(state)&&!closing)fail(DISCONNECTED,"연결 확인 시간이 지나 새로 연결해야 합니다.");}
 private synchronized void activateIfPaired(){if(localPaired&&remotePaired){setState(HOST.equals(role)?HOST_SETUP:WAIT_CONFIG,HOST.equals(role)?"대결 설정을 선택해 주세요.":"방장이 대결을 설정하는 중…");startHeartbeat();}}
 static boolean heartbeatExpired(long now,long last){return last>0&&now-last>HEARTBEAT_TIMEOUT_MS;}
 synchronized boolean handleHeartbeatTimeout(long now){
  if(closing||!heartbeatExpired(now,lastPeerTrafficAt))return false;fail(DISCONNECTED,"상대 응답이 없어 연결을 종료했습니다. 이번 대결은 승패 없이 종료되었습니다.");return true;
 }
 private synchronized void startHeartbeat(){if(heartbeatFuture!=null||closing)return;lastPeerTrafficAt=SystemClock.elapsedRealtime();heartbeatFuture=scheduler.scheduleAtFixedRate(()->{if(closing||peer==null)return;long now=SystemClock.elapsedRealtime();if(handleHeartbeatTimeout(now))return;try{peer.send(MultiplayerProtocol.PING,new byte[0]);}catch(Exception e){fail(DISCONNECTED,"상대와의 연결을 확인할 수 없습니다. 이번 대결은 승패 없이 종료되었습니다.");}},HEARTBEAT_INTERVAL_MS,HEARTBEAT_INTERVAL_MS,TimeUnit.MILLISECONDS);}


 void configure(int size,String mode,byte[] photoBytes,Bitmap hostBitmap){
  if(!HOST.equals(role)||!HOST_SETUP.equals(state)||peer==null)return;if(size<3||size>6||!("number".equals(mode)||"photo".equals(mode)))return;
  if("photo".equals(mode)&&(photoBytes==null||photoBytes.length<1||photoBytes.length>MultiplayerProtocol.MAX_PHOTO||hostBitmap==null||hostBitmap.getWidth()!=hostBitmap.getHeight()||hostBitmap.getWidth()<16||hostBitmap.getWidth()>1440)){externalError(PHOTO_TRANSFER_ERROR,"전송할 게임 사진을 준비하지 못했습니다.");return;}
  setState(CONFIGURING,"대결 설정을 안전하게 전송하는 중…");
  io.execute(()->{try{
   Puzzle initial=new Puzzle(size);initial.shuffle(new SecureRandom());String board=initial.encode();String photoHash="photo".equals(mode)?MultiplayerProtocol.sha256Hex(photoBytes):"";String hash=MultiplayerProtocol.configHash(size,mode,board,photoHash);byte[] nextMatch=MultiplayerProtocol.newId();synchronized(this){matchId=nextMatch;}
   peer.send(MultiplayerProtocol.CONFIG,MultiplayerProtocol.packConfig(size,mode,board,photoHash,hash));
   if("photo".equals(mode)){
    peer.send(MultiplayerProtocol.PHOTO_BEGIN,MultiplayerProtocol.packPhotoBegin(photoHash,photoBytes.length,hostBitmap.getWidth(),hostBitmap.getHeight()));
    for(int offset=0;offset<photoBytes.length;offset+=MultiplayerProtocol.PHOTO_CHUNK){int n=Math.min(MultiplayerProtocol.PHOTO_CHUNK,photoBytes.length-offset);peer.send(MultiplayerProtocol.PHOTO_CHUNK_MSG,Arrays.copyOfRange(photoBytes,offset,offset+n));}
    peer.send(MultiplayerProtocol.PHOTO_END,MultiplayerProtocol.packString(photoHash,64));
   }
   synchronized(this){this.size=size;this.mode=mode;this.configHash=hash;this.localPuzzle=Puzzle.restore(size,board,0);this.remotePuzzle=Puzzle.restore(size,board,0);this.photoBitmap=hostBitmap;resetReadyAndResult();}
   setState(CONFIGURED,"양쪽 설정이 준비되었습니다.");
  }catch(Exception e){fail("photo".equals(mode)?PHOTO_TRANSFER_ERROR:PROTOCOL_ERROR,"대결 설정을 전송하지 못했습니다.");}});
 }

 synchronized void ready(){if(!(CONFIGURED.equals(state)||READY.equals(state))||peer==null||localReady||readySendPending||configHash.isEmpty()||!MultiplayerProtocol.isValidId(matchId))return;readySendPending=true;byte[] payload;try{payload=MultiplayerProtocol.packString(configHash,64);}catch(Exception e){readySendPending=false;fail(PROTOCOL_ERROR,"준비 상태를 전송하지 못했습니다.");return;}sendAsync(MultiplayerProtocol.READY,payload,PROTOCOL_ERROR,"준비 상태를 전송하지 못했습니다.",()->{readySendPending=false;localReady=true;setState(READY,remoteReady?"상대도 준비되었습니다.":"상대 준비를 기다리는 중…");maybeStart();});}
 private synchronized void maybeStart(){if(!HOST.equals(role)||!localReady||!remoteReady||peer==null)return;byte[] payload;try{payload=MultiplayerProtocol.packString(configHash,64);}catch(Exception e){fail(PROTOCOL_ERROR,"대결 시작 신호를 전송하지 못했습니다.");return;}sendAsync(MultiplayerProtocol.START,payload,PROTOCOL_ERROR,"대결 시작 신호를 전송하지 못했습니다.",this::startCountdown);}
 private synchronized void startCountdown(){localStarted=false;remoteStarted=false;countdown=3;setState(COUNTDOWN,"3");for(int step=1;step<=3;step++){final int s=step;main.postDelayed(()->{synchronized(MultiplayerSession.this){if(!COUNTDOWN.equals(state)||closing)return;if(s<3){countdown=3-s;notifyChanged();}else{countdown=0;sendAsync(MultiplayerProtocol.STARTED,new byte[0],PROTOCOL_ERROR,"대결 시작 확인을 전송하지 못했습니다.",()->{localStarted=true;maybeEnterMatch();});}}},step*1000L);}}
 private synchronized void maybeEnterMatch(){if(COUNTDOWN.equals(state)&&localStarted&&remoteStarted){matchStartedAt=SystemClock.elapsedRealtime();setState(MATCH,"대결 중");}}

 synchronized boolean move(int index){
  if(!MATCH.equals(state)||localPuzzle==null||peer==null||!localPuzzle.adjacent(index)||localPuzzle.solved())return false;
  if(!localPuzzle.move(index))return false;byte[] payload;try{payload=MultiplayerProtocol.packMove(configHash,index);}catch(Exception e){fail(PROTOCOL_ERROR,"이동 정보를 전송하지 못했습니다.");return false;}if(!sendAsync(MultiplayerProtocol.MOVE,payload,PROTOCOL_ERROR,"이동 정보를 전송하지 못했습니다.",null))return false;
  if(localPuzzle.solved()){localFinishAt=SystemClock.elapsedRealtime();if(HOST.equals(role))scheduleResult();}return true;
 }

 synchronized void rematch(){
  if(!RESULT.equals(state)||peer==null||rematchSendPending||!MultiplayerProtocol.isValidId(matchId))return;boolean host=HOST.equals(role);rematchSendPending=true;sendAsync(MultiplayerProtocol.REMATCH,new byte[]{host?(byte)1:(byte)0},PROTOCOL_ERROR,"재대결 요청을 전송하지 못했습니다.",()->{rematchSendPending=false;if(host){resetRound();setState(HOST_SETUP,"다음 대결 설정을 선택해 주세요.");}else{rematchRequested=true;notifyChanged();}});
 }

 private void onMessage(MultiplayerProtocol.Decoded decoded) throws Exception {
  synchronized(this){
   byte type=decoded.type;byte[] payload=decoded.payload;
   if(MultiplayerProtocol.sessionScopedType(type)){if(!MultiplayerProtocol.isSessionScope(decoded.matchId))throw new GeneralSecurityException("session message context");}
   else if(type!=MultiplayerProtocol.CONFIG){if(!MultiplayerProtocol.isValidId(matchId)||!Arrays.equals(matchId,decoded.matchId))throw new GeneralSecurityException("match context");}
   if(type==MultiplayerProtocol.LEAVE){MultiplayerProtocol.requireEmpty(payload);fail(DISCONNECTED,"상대가 나갔습니다. 이번 대결은 승패 없이 종료되었습니다.");return;}
   if(!(localPaired&&remotePaired)){
    if(type!=MultiplayerProtocol.PAIR_CONFIRM)throw new IOException("message before pair confirm");MultiplayerProtocol.requireEmpty(payload);if(remotePaired)throw new IOException("duplicate pair confirm");remotePaired=true;activateIfPaired();return;
   }
   switch(type){
    case MultiplayerProtocol.PAIR_CONFIRM: throw new IOException("late pair confirm");
    case MultiplayerProtocol.PING: MultiplayerProtocol.requireEmpty(payload);peer.send(MultiplayerProtocol.PONG,new byte[0]);break;
    case MultiplayerProtocol.PONG: MultiplayerProtocol.requireEmpty(payload);break;
    case MultiplayerProtocol.CONFIG: handleConfig(payload,decoded.matchId);break;
    case MultiplayerProtocol.PHOTO_BEGIN: try{handlePhotoBegin(payload);}catch(IOException e){fail(PHOTO_TRANSFER_ERROR,"게임 사진을 안전하게 받을 수 없어 연결을 종료했습니다.");}break;
    case MultiplayerProtocol.PHOTO_CHUNK_MSG: try{handlePhotoChunk(payload);}catch(IOException e){fail(PHOTO_TRANSFER_ERROR,"게임 사진을 안전하게 받을 수 없어 연결을 종료했습니다.");}break;
    case MultiplayerProtocol.PHOTO_END: try{handlePhotoEnd(payload);}catch(IOException e){fail(PHOTO_TRANSFER_ERROR,"게임 사진을 확인할 수 없어 연결을 종료했습니다.");}break;
    case MultiplayerProtocol.READY: handleReady(payload);break;
    case MultiplayerProtocol.START: handleStart(payload);break;
    case MultiplayerProtocol.STARTED: handleStarted(payload);break;
    case MultiplayerProtocol.MOVE: handleMove(payload);break;
    case MultiplayerProtocol.RESULT: handleResult(payload);break;
    case MultiplayerProtocol.REMATCH: handleRematch(payload);break;
    default: throw new IOException("unknown message");
   }
  }
 }
 private void handleConfig(byte[] payload,byte[] frameMatchId) throws Exception {
  if(!GUEST.equals(role)||!WAIT_CONFIG.equals(state)||!MultiplayerProtocol.isValidId(frameMatchId))throw new IOException("unexpected config");
  MultiplayerProtocol.Config c=MultiplayerProtocol.unpackConfig(payload);if(c.size<3||c.size>6||!("number".equals(c.mode)||"photo".equals(c.mode)))throw new IOException("config values");Puzzle lp=Puzzle.restore(c.size,c.board,0);Puzzle rp=Puzzle.restore(c.size,c.board,0);String expected=MultiplayerProtocol.configHash(c.size,c.mode,c.board,c.photoHash);if(!expected.equals(c.hash))throw new IOException("config hash");
  this.size=c.size;this.mode=c.mode;this.configHash=c.hash;this.localPuzzle=lp;this.remotePuzzle=rp;this.photoBitmap=null;this.matchId=Arrays.copyOf(frameMatchId,frameMatchId.length);resetReadyAndResult();expectedPhotoHash=c.photoHash;
  if("photo".equals(c.mode)){if(c.photoHash.length()!=64)throw new IOException("photo hash");setState(PHOTO_RECEIVING,"게임 사진을 받는 중…");}else{if(!c.photoHash.isEmpty())throw new IOException("unexpected photo hash");setState(CONFIGURED,"대결 설정을 받았습니다.");}
 }
 private void handlePhotoBegin(byte[] payload) throws IOException {if(!GUEST.equals(role)||!PHOTO_RECEIVING.equals(state)||!"photo".equals(mode)||photoReceiveFile!=null)throw new IOException("photo begin state");MultiplayerProtocol.PhotoMeta meta=MultiplayerProtocol.unpackPhotoBegin(payload,expectedPhotoHash);expectedPhotoBytes=meta.length;expectedPhotoWidth=meta.width;expectedPhotoHeight=meta.height;receivedPhotoBytes=0;File dir=multiplayerCacheDir();if(!dir.exists()&&!dir.mkdirs())throw new IOException("cache dir");photoReceiveFile=File.createTempFile("rx-",".jpg",dir);photoReceiveOut=new BufferedOutputStream(new FileOutputStream(photoReceiveFile));try{photoReceiveDigest=MessageDigest.getInstance("SHA-256");}catch(GeneralSecurityException e){throw new IOException("hash",e);}}
 private void handlePhotoChunk(byte[] payload) throws IOException {if(photoReceiveFile==null||photoReceiveOut==null||photoReceiveDigest==null||payload.length<1||payload.length>MultiplayerProtocol.PHOTO_CHUNK||receivedPhotoBytes+payload.length>expectedPhotoBytes)throw new IOException("photo chunk");photoReceiveOut.write(payload);photoReceiveDigest.update(payload);receivedPhotoBytes+=payload.length;}
 private void handlePhotoEnd(byte[] payload) throws IOException {
  if(photoReceiveFile==null||photoReceiveOut==null||photoReceiveDigest==null)throw new IOException("photo end state");String hash=MultiplayerProtocol.unpackString(payload,64);File file=photoReceiveFile;try{photoReceiveOut.close();photoReceiveOut=null;if(receivedPhotoBytes!=expectedPhotoBytes||!expectedPhotoHash.equals(hash)||!hash.equals(hex(photoReceiveDigest.digest())))throw new IOException("photo integrity");Bitmap bitmap=decodeBoundedJpeg(file,expectedPhotoBytes,hash,expectedPhotoWidth,expectedPhotoHeight);photoBitmap=bitmap;setState(CONFIGURED,"대결 설정과 게임 사진을 받았습니다.");}finally{deletePhotoReceive();}
 }
 static Bitmap decodeBoundedJpeg(File file,int expectedBytes,String expectedHash,int expectedWidth,int expectedHeight) throws IOException {
  if(file==null||!file.isFile()||expectedBytes<1||expectedBytes>MultiplayerProtocol.MAX_PHOTO||file.length()!=expectedBytes||expectedHash==null||expectedHash.length()!=64||expectedWidth<16||expectedWidth>1440||expectedHeight!=expectedWidth)throw new IOException("photo bounds");try{if(!expectedHash.equals(MultiplayerProtocol.sha256Hex(file)))throw new IOException("photo hash");}catch(GeneralSecurityException e){throw new IOException("photo hash",e);}BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth!=expectedWidth||bounds.outHeight!=expectedHeight||bounds.outWidth<16||bounds.outWidth>1440||bounds.outWidth!=bounds.outHeight||bounds.outMimeType==null||!"image/jpeg".equalsIgnoreCase(bounds.outMimeType))throw new IOException("photo decode bounds");BitmapFactory.Options decode=new BitmapFactory.Options();decode.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),decode);if(bitmap==null||bitmap.getWidth()!=expectedWidth||bitmap.getHeight()!=expectedHeight){if(bitmap!=null)bitmap.recycle();throw new IOException("photo decode");}return bitmap;
 }
 private static String hex(byte[] data){StringBuilder out=new StringBuilder(data.length*2);for(byte b:data)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
 private void handleReady(byte[] payload) throws Exception {if(!(CONFIGURED.equals(state)||READY.equals(state)))throw new IOException("ready state");String hash=MultiplayerProtocol.unpackString(payload,64);if(!configHash.equals(hash)||localPuzzle==null)throw new IOException("ready config");if(remoteReady)throw new IOException("duplicate ready");remoteReady=true;setState(READY,localReady?"양쪽 모두 준비되었습니다.":"상대가 준비되었습니다.");maybeStart();}
 private void handleStart(byte[] payload) throws Exception {if(!GUEST.equals(role)||!localReady||!remoteReady||!READY.equals(state)||!configHash.equals(MultiplayerProtocol.unpackString(payload,64)))throw new IOException("start state");startCountdown();}
 private void handleStarted(byte[] payload) throws Exception {if(!COUNTDOWN.equals(state)||remoteStarted)throw new IOException("started state");MultiplayerProtocol.requireEmpty(payload);remoteStarted=true;maybeEnterMatch();}
 static boolean acceptsRemoteMove(String currentState,Puzzle remote){return MATCH.equals(currentState)&&remote!=null&&!remote.solved();}
 static boolean guestResultEvidenceValid(String code,Puzzle local,Puzzle remote){if(!MultiplayerProtocol.validResultCode(code)||local==null||remote==null)return false;if("HOST".equals(code))return remote.solved();if("GUEST".equals(code))return local.solved();return local.solved()&&remote.solved();}
 private void handleMove(byte[] payload) throws Exception {if(!acceptsRemoteMove(state,remotePuzzle))throw new IOException("move state");int index=MultiplayerProtocol.unpackMove(payload,configHash);if(!remotePuzzle.move(index))throw new IOException("illegal move");if(remotePuzzle.solved()){remoteFinishAt=SystemClock.elapsedRealtime();if(HOST.equals(role))scheduleResult();}notifyChanged();}
 private void scheduleResult(){if(resultScheduled)return;resultScheduled=true;main.postDelayed(()->{synchronized(MultiplayerSession.this){if(!HOST.equals(role)||RESULT.equals(state)||closing||!MATCH.equals(state))return;boolean local=localPuzzle!=null&&localPuzzle.solved(),remote=remotePuzzle!=null&&remotePuzzle.solved();if(!local&&!remote){resultScheduled=false;return;}String code;if(local&&remote&&Math.abs(localFinishAt-remoteFinishAt)<=TIE_WINDOW_MS)code="TIE";else if(local&&(!remote||localFinishAt<remoteFinishAt))code="HOST";else code="GUEST";byte[] payload;try{payload=MultiplayerProtocol.packResult(configHash,code);}catch(Exception e){fail(PROTOCOL_ERROR,"대결 결과를 전송하지 못했습니다.");return;}sendAsync(MultiplayerProtocol.RESULT,payload,PROTOCOL_ERROR,"대결 결과를 전송하지 못했습니다.",()->{try{applyResult(code);}catch(IOException e){fail(PROTOCOL_ERROR,"대결 결과를 전송하지 못했습니다.");}});}},TIE_WINDOW_MS);}
 private void handleResult(byte[] payload) throws Exception {if(!GUEST.equals(role)||!MATCH.equals(state)||localPuzzle==null||remotePuzzle==null)throw new IOException("result state");String[] pair=MultiplayerProtocol.unpackResult(payload,configHash);String code=pair[1];if(!guestResultEvidenceValid(code,localPuzzle,remotePuzzle))throw new IOException("false result");applyResult(code);}
 private void applyResult(String code) throws IOException {if(!MultiplayerProtocol.validResultCode(code))throw new IOException("result code");if(matchEndedAt==0)matchEndedAt=SystemClock.elapsedRealtime();if("TIE".equals(code))result="TIE";else if((HOST.equals(role)&&"HOST".equals(code))||(GUEST.equals(role)&&"GUEST".equals(code)))result="WIN";else result="LOSS";setState(RESULT,"WIN".equals(result)?"이겼어요!":"LOSS".equals(result)?"상대가 먼저 완성했어요":"동시에 완성했어요!");}
 private void handleRematch(byte[] payload) throws Exception {if(payload.length!=1||!RESULT.equals(state))throw new IOException("rematch state");if(HOST.equals(role)){if(payload[0]!=0)throw new IOException("rematch direction");rematchRequested=true;notifyChanged();}else{if(payload[0]!=1)throw new IOException("rematch direction");resetRound();setState(WAIT_CONFIG,"방장이 다음 대결을 설정하는 중…");}}

 private void resetReadyAndResult(){localReady=false;remoteReady=false;localStarted=false;remoteStarted=false;readySendPending=false;rematchSendPending=false;result="";rematchRequested=false;countdown=0;matchStartedAt=0;matchEndedAt=0;localFinishAt=0;remoteFinishAt=0;resultScheduled=false;}
 private void resetRound(){resetReadyAndResult();localPuzzle=null;remotePuzzle=null;configHash="";matchId=null;mode="number";size=3;if(photoBitmap!=null&&GUEST.equals(role)){photoBitmap.recycle();}photoBitmap=null;deletePhotoReceive();expectedPhotoBytes=0;receivedPhotoBytes=0;expectedPhotoWidth=0;expectedPhotoHeight=0;expectedPhotoHash="";}

 void externalError(String state,String message){fail(state,message);}
 void close(){if(closing)return;closing=true;main.removeCallbacksAndMessages(null);outbound.clear();shutdownTransport();stopHeartbeat();deletePhotoReceive();io.shutdownNow();scheduler.shutdownNow();}
 private void fail(String state,String message){if(closing)return;this.state=state;this.errorMessage=message;closing=true;main.removeCallbacksAndMessages(null);outbound.clear();shutdownTransport();stopHeartbeat();deletePhotoReceive();io.shutdownNow();scheduler.shutdownNow();notifyChanged();}
 private void setState(String state,String message){if(closing&&!(DISCONNECTED.equals(state)||PAIRING_MISMATCH.equals(state)||PROTOCOL_ERROR.equals(state)||CRYPTO_ERROR.equals(state)||PHOTO_TRANSFER_ERROR.equals(state)||PERMISSION_DENIED.equals(state)||P2P_UNSUPPORTED.equals(state)))return;this.state=state;this.errorMessage=message;notifyChanged();}
 private void notifyChanged(){Callback cb=callback;if(cb!=null)main.post(()->{Callback current=callback;if(current!=null)current.onChanged();});}
 private synchronized byte[] matchContextForSend(byte type) throws IOException {if(MultiplayerProtocol.sessionScopedType(type))return MultiplayerProtocol.SESSION_SCOPE_ID;if(!MultiplayerProtocol.isValidId(matchId))throw new IOException("missing match context");return Arrays.copyOf(matchId,matchId.length);}

 private void stopDiscoveryOnly(){stopLanDiscovery();stopLanRegistration();stopDirectDiscovery();stopDirectAdvertise();}
 private void stopLanDiscovery(){if(nsd!=null&&discoveryListener!=null)try{nsd.stopServiceDiscovery(discoveryListener);}catch(RuntimeException ignored){}discoveryListener=null;}
 private void stopLanRegistration(){if(nsd!=null&&registrationListener!=null)try{nsd.unregisterService(registrationListener);}catch(RuntimeException ignored){}registrationListener=null;}
 private void stopDirectDiscovery(){if(p2p!=null&&p2pChannel!=null){if(p2pRequest!=null)try{p2p.removeServiceRequest(p2pChannel,p2pRequest,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){}try{p2p.stopPeerDiscovery(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){}}p2pRequest=null;}
 private void stopDirectAdvertise(){if(p2p!=null&&p2pChannel!=null&&p2pService!=null)try{p2p.removeLocalService(p2pChannel,p2pService,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){}p2pService=null;}
 private void shutdownTransport(){
  stopDiscoveryOnly();if(p2pReceiver!=null)try{context.unregisterReceiver(p2pReceiver);}catch(RuntimeException ignored){}p2pReceiver=null;
  if(p2p!=null&&p2pChannel!=null)try{p2p.removeGroup(p2pChannel,new WifiP2pManager.ActionListener(){public void onSuccess(){}public void onFailure(int r){}});}catch(RuntimeException ignored){}
  try{if(socket!=null)socket.close();}catch(IOException ignored){}try{if(server!=null)server.close();}catch(IOException ignored){}socket=null;server=null;
 }
 private synchronized void stopHeartbeat(){if(heartbeatFuture!=null)heartbeatFuture.cancel(true);heartbeatFuture=null;}
 private File multiplayerCacheDir(){return new File(context.getCacheDir(),"multiplayer");}
 private void cleanupStaleMultiplayerCache(){File dir=multiplayerCacheDir();File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();}
 private synchronized void deletePhotoReceive(){if(photoReceiveOut!=null)try{photoReceiveOut.close();}catch(IOException ignored){}photoReceiveOut=null;photoReceiveDigest=null;if(photoReceiveFile!=null)photoReceiveFile.delete();photoReceiveFile=null;}

 private final class SecurePeer {
  final Socket socket;final boolean host;final DataInputStream in;final DataOutputStream out;final byte[] expectedSessionId;MultiplayerProtocol.Keys keys;long sendSeq=1,recvSeq=1;
  SecurePeer(Socket socket,boolean host,byte[] expectedSessionId)throws IOException{this.socket=socket;this.host=host;this.expectedSessionId=Arrays.copyOf(expectedSessionId,expectedSessionId.length);in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));}
  void handshake() throws IOException,GeneralSecurityException {
   KeyPair pair=MultiplayerProtocol.newKeyPair();byte[] nonce=MultiplayerProtocol.newNonce(),localPublic=pair.getPublic().getEncoded();MultiplayerProtocol.Hello remote;
   if(host){writeHandshake(MultiplayerProtocol.encodeHello(localPublic,nonce,expectedSessionId));remote=readHandshake();if(!Arrays.equals(expectedSessionId,remote.sessionId))throw new GeneralSecurityException("session mismatch");keys=MultiplayerProtocol.derive(true,pair.getPrivate(),localPublic,remote.publicKey,nonce,remote.nonce,expectedSessionId);}
   else{remote=readHandshake();if(!Arrays.equals(expectedSessionId,remote.sessionId))throw new GeneralSecurityException("session mismatch");writeHandshake(MultiplayerProtocol.encodeHello(localPublic,nonce,expectedSessionId));keys=MultiplayerProtocol.derive(false,pair.getPrivate(),remote.publicKey,localPublic,remote.nonce,nonce,expectedSessionId);}
  }
  private void writeHandshake(byte[] data)throws IOException{synchronized(out){out.writeInt(data.length);out.write(data);out.flush();}}
  private MultiplayerProtocol.Hello readHandshake()throws IOException{int length=in.readInt();if(length<1||length>MultiplayerProtocol.MAX_HANDSHAKE)throw new IOException("handshake size");byte[] data=new byte[length];in.readFully(data);return MultiplayerProtocol.decodeHello(data);}
  void send(byte type,byte[] payload)throws IOException,GeneralSecurityException {byte[] context=matchContextForSend(type);synchronized(this){if(keys==null)throw new IOException("not secure");if(sendSeq<=0||sendSeq==Long.MAX_VALUE)throw new GeneralSecurityException("sequence exhausted");byte[] frame=MultiplayerProtocol.seal(keys.sendKey,keys.sendMarker,sendSeq,type,expectedSessionId,context,payload);sendSeq++;if(frame.length>MultiplayerProtocol.MAX_CONTROL+80)throw new IOException("frame too large");out.writeInt(frame.length);out.write(frame);out.flush();}}
  void readLoop()throws IOException,GeneralSecurityException {while(!closing){int length;try{length=in.readInt();}catch(EOFException eof){return;}if(length<45||length>MultiplayerProtocol.MAX_CONTROL+80)throw new IOException("frame size");byte[] frame=new byte[length];in.readFully(frame);if(recvSeq<=0||recvSeq==Long.MAX_VALUE)throw new GeneralSecurityException("sequence exhausted");MultiplayerProtocol.Decoded decoded=MultiplayerProtocol.open(keys.recvKey,keys.recvMarker,recvSeq,expectedSessionId,null,frame);recvSeq++;lastPeerTrafficAt=SystemClock.elapsedRealtime();try{onMessage(decoded);}catch(GeneralSecurityException e){throw e;}catch(IOException e){throw e;}catch(Exception e){throw new IOException("message",e);}}}
 }
}
