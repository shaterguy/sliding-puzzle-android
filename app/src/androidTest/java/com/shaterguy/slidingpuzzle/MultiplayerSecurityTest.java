package com.shaterguy.slidingpuzzle;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MultiplayerSecurityTest {
 private interface Condition { boolean done() throws Exception; }
 private File jpeg(Context context,int size) throws Exception {
  File file=File.createTempFile("multiplayer-test-",".jpg",context.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff336699);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,out));}finally{bitmap.recycle();}return file;
 }


 private static java.lang.reflect.Field field(String name) throws Exception {java.lang.reflect.Field f=MultiplayerSession.class.getDeclaredField(name);f.setAccessible(true);return f;}
 private static void setField(MultiplayerSession session,String name,Object value) throws Exception {field(name).set(session,value);}
 private static Object getField(MultiplayerSession session,String name) throws Exception {return field(name).get(session);}
 private static void establish(MultiplayerSession session,java.net.Socket socket) throws Exception {java.lang.reflect.Method method=MultiplayerSession.class.getDeclaredMethod("establish",java.net.Socket.class);method.setAccessible(true);method.invoke(session,socket);}
 private static void await(String label,long timeoutMs,Condition condition) throws Exception {
  long deadline=SystemClock.elapsedRealtime()+timeoutMs;
  while(SystemClock.elapsedRealtime()<deadline){if(condition.done())return;Thread.sleep(20);}
  fail("timeout waiting for "+label);
 }
 private static void awaitState(MultiplayerSession session,String expected,long timeoutMs) throws Exception {await(expected,timeoutMs,()->expected.equals(session.state));}
 private static final class LivePair implements AutoCloseable {
  final MultiplayerSession host,guest;
  LivePair(Context context) throws Exception {
   host=new MultiplayerSession(context);guest=new MultiplayerSession(context);host.role=MultiplayerSession.HOST;guest.role=MultiplayerSession.GUEST;host.transport=MultiplayerSession.LAN;guest.transport=MultiplayerSession.LAN;
   byte[] sessionId=MultiplayerProtocol.newId();setField(host,"sessionId",sessionId);setField(guest,"sessionId",sessionId);
   java.net.InetAddress loopback=java.net.InetAddress.getLoopbackAddress();java.net.ServerSocket listener=new java.net.ServerSocket(0,1,loopback);java.net.Socket guestSocket=new java.net.Socket();
   try{guestSocket.connect(new java.net.InetSocketAddress(loopback,listener.getLocalPort()),2000);java.net.Socket hostSocket=listener.accept();hostSocket.setTcpNoDelay(true);guestSocket.setTcpNoDelay(true);establish(host,hostSocket);establish(guest,guestSocket);}finally{listener.close();}
   awaitState(host,MultiplayerSession.PAIRING,5000);awaitState(guest,MultiplayerSession.PAIRING,5000);assertFalse(host.sas.isEmpty());assertEquals(host.sas,guest.sas);
  }
  void enterMatch() throws Exception {
   host.confirmPairing(true);guest.confirmPairing(true);awaitState(host,MultiplayerSession.HOST_SETUP,3000);awaitState(guest,MultiplayerSession.WAIT_CONFIG,3000);
   host.configure(3,"number",null,null);awaitState(host,MultiplayerSession.CONFIGURED,3000);awaitState(guest,MultiplayerSession.CONFIGURED,3000);assertEquals(host.configHash,guest.configHash);assertArrayEquals(host.localPuzzle.snapshot(),guest.localPuzzle.snapshot());
   guest.ready();await("guest ready reaches host",3000,()->guest.localReady&&host.remoteReady);host.ready();awaitState(host,MultiplayerSession.MATCH,7000);awaitState(guest,MultiplayerSession.MATCH,7000);
  }
  @Override public void close(){host.close();guest.close();}
 }
 private static final class LifecycleResources {
  final java.net.ServerSocket server;final java.net.Socket socket;final java.util.concurrent.ScheduledFuture<?> heartbeat;
  LifecycleResources(java.net.ServerSocket server,java.net.Socket socket,java.util.concurrent.ScheduledFuture<?> heartbeat){this.server=server;this.socket=socket;this.heartbeat=heartbeat;}
 }
 private LifecycleResources attachLifecycleResources(MultiplayerSession session) throws Exception {
  java.net.ServerSocket server=new java.net.ServerSocket(0);java.net.Socket socket=new java.net.Socket();setField(session,"server",server);setField(session,"socket",socket);
  java.util.concurrent.ScheduledExecutorService scheduler=(java.util.concurrent.ScheduledExecutorService)getField(session,"scheduler");java.util.concurrent.ScheduledFuture<?> heartbeat=scheduler.schedule(()->{},5,java.util.concurrent.TimeUnit.MINUTES);setField(session,"heartbeatFuture",heartbeat);
  setField(session,"discoveryListener",new android.net.nsd.NsdManager.DiscoveryListener(){public void onDiscoveryStarted(String t){}public void onStartDiscoveryFailed(String t,int c){}public void onStopDiscoveryFailed(String t,int c){}public void onDiscoveryStopped(String t){}public void onServiceFound(android.net.nsd.NsdServiceInfo i){}public void onServiceLost(android.net.nsd.NsdServiceInfo i){}});
  setField(session,"registrationListener",new android.net.nsd.NsdManager.RegistrationListener(){public void onRegistrationFailed(android.net.nsd.NsdServiceInfo i,int c){}public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo i,int c){}public void onServiceRegistered(android.net.nsd.NsdServiceInfo i){}public void onServiceUnregistered(android.net.nsd.NsdServiceInfo i){}});
  setField(session,"p2pReceiver",new android.content.BroadcastReceiver(){public void onReceive(android.content.Context c,android.content.Intent i){}});
  return new LifecycleResources(server,socket,heartbeat);
 }
 private void assertLifecycleReleased(MultiplayerSession session,LifecycleResources r) throws Exception {
  assertTrue(r.server.isClosed());assertTrue(r.socket.isClosed());assertTrue(r.heartbeat.isCancelled());assertNull(getField(session,"server"));assertNull(getField(session,"socket"));assertNull(getField(session,"heartbeatFuture"));assertNull(getField(session,"discoveryListener"));assertNull(getField(session,"registrationListener"));assertNull(getField(session,"p2pReceiver"));assertTrue(((java.util.concurrent.ExecutorService)getField(session,"io")).isShutdown());assertTrue(((java.util.concurrent.ScheduledExecutorService)getField(session,"scheduler")).isShutdown());
 }

 @Test public void liveLoopbackSessionCompletesHandshakePairingReadyStartMoveAndResult() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();try(LivePair pair=new LivePair(context)){pair.enterMatch();Puzzle almostSolved=Puzzle.restore(3,"1,2,3,4,5,6,7,0,8",0);pair.host.localPuzzle=almostSolved;pair.guest.remotePuzzle=Puzzle.restore(3,almostSolved.encode(),0);assertTrue(pair.host.move(8));awaitState(pair.host,MultiplayerSession.RESULT,3000);awaitState(pair.guest,MultiplayerSession.RESULT,3000);assertTrue(pair.host.localPuzzle.solved());assertTrue(pair.guest.remotePuzzle.solved());assertEquals("WIN",pair.host.result);assertEquals("LOSS",pair.guest.result);assertEquals(1,pair.guest.remoteMoves());}
 }
 @Test public void liveLoopbackDisconnectDuringMatchEndsWithoutResult() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();try(LivePair pair=new LivePair(context)){pair.enterMatch();pair.guest.close();awaitState(pair.host,MultiplayerSession.DISCONNECTED,3000);assertEquals("",pair.host.result);assertTrue(pair.host.errorMessage.contains("승패 없이"));}
 }
 @Test public void boundedJpegValidatesHashAndDimensionsBeforeFullDecode() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();File file=jpeg(context,32);try{String hash=MultiplayerProtocol.sha256Hex(file);Bitmap decoded=MultiplayerSession.decodeBoundedJpeg(file,(int)file.length(),hash,32,32);try{assertEquals(32,decoded.getWidth());assertEquals(32,decoded.getHeight());}finally{decoded.recycle();}}finally{file.delete();}
 }

 @Test public void malformedBadHashAndHugeDimensionAreRejected() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();File malformed=File.createTempFile("multiplayer-bad-",".jpg",context.getCacheDir());try(FileOutputStream out=new FileOutputStream(malformed)){out.write(new byte[]{1,2,3,4,5});}try{String hash=MultiplayerProtocol.sha256Hex(malformed);try{MultiplayerSession.decodeBoundedJpeg(malformed,(int)malformed.length(),hash,32,32);fail("malformed jpeg must fail");}catch(IOException expected){}}finally{malformed.delete();}
  File normal=jpeg(context,32);try{try{MultiplayerSession.decodeBoundedJpeg(normal,(int)normal.length(),new String(new char[64]).replace('\0','0'),32,32);fail("bad hash must fail");}catch(IOException expected){}}finally{normal.delete();}
  File huge=jpeg(context,1441);try{String hash=MultiplayerProtocol.sha256Hex(huge);try{MultiplayerSession.decodeBoundedJpeg(huge,(int)huge.length(),hash,1440,1440);fail("actual huge dimensions must fail before full decode");}catch(IOException expected){}}finally{huge.delete();}
 }

 @Test public void transportErrorsNeverCreateWinOrLoss() {
  Context context=ApplicationProvider.getApplicationContext();MultiplayerSession denied=new MultiplayerSession(context);try{denied.externalError(MultiplayerSession.PERMISSION_DENIED,"denied");assertEquals(MultiplayerSession.PERMISSION_DENIED,denied.state);assertEquals("",denied.result);}finally{denied.close();}
  MultiplayerSession unsupported=new MultiplayerSession(context);try{unsupported.externalError(MultiplayerSession.P2P_UNSUPPORTED,"unsupported");assertEquals(MultiplayerSession.P2P_UNSUPPORTED,unsupported.state);assertEquals("",unsupported.result);}finally{unsupported.close();}
 }

 @Test public void staleMultiplayerCacheIsRemovedAtSessionStart() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();File dir=new File(context.getCacheDir(),"multiplayer");assertTrue(dir.exists()||dir.mkdirs());File stale=new File(dir,"stale.jpg");try(FileOutputStream out=new FileOutputStream(stale)){out.write(7);}assertTrue(stale.exists());MultiplayerSession session=new MultiplayerSession(context);try{assertFalse(stale.exists());}finally{session.close();}
 }
 @Test public void sasMismatchFailsClosedWithoutResultAndCleansLifecycle() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();MultiplayerSession session=new MultiplayerSession(context);LifecycleResources resources=attachLifecycleResources(session);session.state=MultiplayerSession.PAIRING;session.result="";session.confirmPairing(false);assertEquals(MultiplayerSession.PAIRING_MISMATCH,session.state);assertEquals("",session.result);assertLifecycleReleased(session,resources);
 }

 @Test public void pairingTimeoutFailsClosedWithoutWaitingAndCleansLifecycle() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();MultiplayerSession session=new MultiplayerSession(context);LifecycleResources resources=attachLifecycleResources(session);session.state=MultiplayerSession.PAIRING;session.result="";session.handlePairingTimeout();assertEquals(MultiplayerSession.DISCONNECTED,session.state);assertEquals("",session.result);assertLifecycleReleased(session,resources);
 }

 @Test public void heartbeatTimeoutUsesProductionFailurePathAndCleansLifecycle() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();MultiplayerSession session=new MultiplayerSession(context);LifecycleResources resources=attachLifecycleResources(session);session.state=MultiplayerSession.MATCH;session.result="";field("lastPeerTrafficAt").setLong(session,1L);assertTrue(session.handleHeartbeatTimeout(15002L));assertEquals(MultiplayerSession.DISCONNECTED,session.state);assertEquals("",session.result);assertLifecycleReleased(session,resources);
 }

 @Test public void userCloseIsNonResultAndIdempotentlyReleasesLifecycle() throws Exception {
  Context context=ApplicationProvider.getApplicationContext();MultiplayerSession session=new MultiplayerSession(context);LifecycleResources resources=attachLifecycleResources(session);session.state=MultiplayerSession.MATCH;session.result="";session.close();session.close();assertEquals("",session.result);assertLifecycleReleased(session,resources);
 }


}
