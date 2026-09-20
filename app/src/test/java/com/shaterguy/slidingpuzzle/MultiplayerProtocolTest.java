package com.shaterguy.slidingpuzzle;

import java.security.KeyPair;
import java.security.GeneralSecurityException;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiplayerProtocolTest {
 @Test public void ecdhSasAndDirectionalFramesMatch()throws Exception{
  KeyPair host=MultiplayerProtocol.newKeyPair(),guest=MultiplayerProtocol.newKeyPair();byte[] hn=MultiplayerProtocol.newNonce(),gn=MultiplayerProtocol.newNonce();
  MultiplayerProtocol.Keys hk=MultiplayerProtocol.derive(true,host.getPrivate(),host.getPublic().getEncoded(),guest.getPublic().getEncoded(),hn,gn);
  MultiplayerProtocol.Keys gk=MultiplayerProtocol.derive(false,guest.getPrivate(),host.getPublic().getEncoded(),guest.getPublic().getEncoded(),hn,gn);
  assertEquals(8,hk.sas.length());assertEquals(hk.sas,gk.sas);
  byte[] clear="ready".getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] frame=MultiplayerProtocol.seal(hk.sendKey,hk.sendMarker,1,MultiplayerProtocol.READY,clear);MultiplayerProtocol.Decoded decoded=MultiplayerProtocol.open(gk.recvKey,gk.recvMarker,1,frame);assertEquals(MultiplayerProtocol.READY,decoded.type);assertArrayEquals(clear,decoded.payload);
 }
 @Test public void replayAndTamperAreRejected()throws Exception{
  KeyPair host=MultiplayerProtocol.newKeyPair(),guest=MultiplayerProtocol.newKeyPair();byte[] hn=MultiplayerProtocol.newNonce(),gn=MultiplayerProtocol.newNonce();
  MultiplayerProtocol.Keys hk=MultiplayerProtocol.derive(true,host.getPrivate(),host.getPublic().getEncoded(),guest.getPublic().getEncoded(),hn,gn);MultiplayerProtocol.Keys gk=MultiplayerProtocol.derive(false,guest.getPrivate(),host.getPublic().getEncoded(),guest.getPublic().getEncoded(),hn,gn);
  byte[] frame=MultiplayerProtocol.seal(hk.sendKey,hk.sendMarker,1,MultiplayerProtocol.MOVE,new byte[]{1,2,3});MultiplayerProtocol.open(gk.recvKey,gk.recvMarker,1,frame);
  try{MultiplayerProtocol.open(gk.recvKey,gk.recvMarker,2,frame);fail("replay must fail");}catch(GeneralSecurityException expected){}
  byte[] tampered=frame.clone();tampered[tampered.length-1]^=1;try{MultiplayerProtocol.open(gk.recvKey,gk.recvMarker,1,tampered);fail("tamper must fail");}catch(GeneralSecurityException expected){}
 }
 @Test public void configBindsExactSolvableBoard()throws Exception{
  Puzzle puzzle=Puzzle.restore(3,"1,2,3,4,5,6,7,0,8",0);String board=puzzle.encode();String hash=MultiplayerProtocol.configHash(3,"number",board,"");byte[] packed=MultiplayerProtocol.packConfig(3,"number",board,"",hash);MultiplayerProtocol.Config c=MultiplayerProtocol.unpackConfig(packed);assertEquals(3,c.size);assertEquals(board,c.board);assertEquals(hash,c.hash);assertTrue(Puzzle.restore(c.size,c.board,0).solvable());
 }
 @Test public void helloAndPhotoBoundsAreStrict()throws Exception{
  KeyPair pair=MultiplayerProtocol.newKeyPair();byte[] encoded=MultiplayerProtocol.encodeHello(pair.getPublic().getEncoded(),MultiplayerProtocol.newNonce());assertArrayEquals(pair.getPublic().getEncoded(),MultiplayerProtocol.decodeHello(encoded).publicKey);
  try{MultiplayerProtocol.packPhotoBegin("x",MultiplayerProtocol.MAX_PHOTO+1);fail("oversize photo must fail");}catch(java.io.IOException expected){}
  byte[] huge=new byte[MultiplayerProtocol.PHOTO_CHUNK+1];try{MultiplayerProtocol.seal(new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"),0x11111111,1,MultiplayerProtocol.PHOTO_CHUNK_MSG,huge);fail("oversize chunk must fail");}catch(java.io.IOException expected){}
 }
}
