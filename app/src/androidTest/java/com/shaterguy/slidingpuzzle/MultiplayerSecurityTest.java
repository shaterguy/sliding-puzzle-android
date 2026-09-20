package com.shaterguy.slidingpuzzle;

import android.content.Context;
import android.graphics.Bitmap;
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
 private File jpeg(Context context,int size) throws Exception {
  File file=File.createTempFile("multiplayer-test-",".jpg",context.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff336699);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,out));}finally{bitmap.recycle();}return file;
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
}
