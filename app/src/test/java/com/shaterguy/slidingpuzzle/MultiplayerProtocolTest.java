package com.shaterguy.slidingpuzzle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiplayerProtocolTest {
 private static final class Pair {
  final MultiplayerProtocol.Keys host,guest;final byte[] session,match;
  Pair() throws Exception {
   KeyPair h=MultiplayerProtocol.newKeyPair(),g=MultiplayerProtocol.newKeyPair();byte[] hn=MultiplayerProtocol.newNonce(),gn=MultiplayerProtocol.newNonce();session=MultiplayerProtocol.newId();match=MultiplayerProtocol.newId();
   host=MultiplayerProtocol.derive(true,h.getPrivate(),h.getPublic().getEncoded(),g.getPublic().getEncoded(),hn,gn,session);
   guest=MultiplayerProtocol.derive(false,g.getPrivate(),h.getPublic().getEncoded(),g.getPublic().getEncoded(),hn,gn,session);
  }
 }

 @Test public void ecdhSasAndDirectionalFramesBindSessionMatchAndLength() throws Exception {
  Pair p=new Pair();assertEquals(8,p.host.sas.length());assertEquals(p.host.sas,p.guest.sas);
  byte[] clear="ready".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  byte[] frame=MultiplayerProtocol.seal(p.host.sendKey,p.host.sendMarker,1,MultiplayerProtocol.READY,p.session,p.match,clear);
  MultiplayerProtocol.Decoded decoded=MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,p.match,frame);
  assertEquals(MultiplayerProtocol.READY,decoded.type);assertArrayEquals(clear,decoded.payload);assertArrayEquals(p.session,decoded.sessionId);assertArrayEquals(p.match,decoded.matchId);
 }

 @Test public void helloAndKeyScheduleBindAdvertisedSession() throws Exception {
  KeyPair pair=MultiplayerProtocol.newKeyPair();byte[] session=MultiplayerProtocol.newId();byte[] encoded=MultiplayerProtocol.encodeHello(pair.getPublic().getEncoded(),MultiplayerProtocol.newNonce(),session);MultiplayerProtocol.Hello hello=MultiplayerProtocol.decodeHello(encoded);assertArrayEquals(pair.getPublic().getEncoded(),hello.publicKey);assertArrayEquals(session,hello.sessionId);
  try{MultiplayerProtocol.encodeHello(pair.getPublic().getEncoded(),MultiplayerProtocol.newNonce(),MultiplayerProtocol.SESSION_SCOPE_ID);fail("zero session must fail");}catch(IOException expected){}
 }

 @Test public void replayOutOfOrderTamperWrongKeyAndContextMismatchAreRejected() throws Exception {
  Pair p=new Pair();byte[] frame=MultiplayerProtocol.seal(p.host.sendKey,p.host.sendMarker,1,MultiplayerProtocol.MOVE,p.session,p.match,new byte[]{1,2,3});
  MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,p.match,frame);
  try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,2,p.session,p.match,frame);fail("replay/out-of-order must fail");}catch(GeneralSecurityException expected){}
  byte[] tampered=frame.clone();tampered[tampered.length-1]^=1;try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,p.match,tampered);fail("tamper must fail");}catch(GeneralSecurityException expected){}
  try{MultiplayerProtocol.open(new SecretKeySpec(new byte[32],"AES"),p.guest.recvMarker,1,p.session,p.match,frame);fail("wrong key must fail");}catch(GeneralSecurityException expected){}
  try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,MultiplayerProtocol.newId(),p.match,frame);fail("wrong session must fail");}catch(GeneralSecurityException expected){}
  try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,MultiplayerProtocol.newId(),frame);fail("wrong match must fail");}catch(GeneralSecurityException expected){}
 }

 @Test public void frameTypeLengthAndSequenceOverflowAreStrict() throws Exception {
  Pair p=new Pair();byte[] frame=MultiplayerProtocol.seal(p.host.sendKey,p.host.sendMarker,1,MultiplayerProtocol.READY,p.session,p.match,new byte[]{7});
  byte[] unknown=frame.clone();unknown[0]=99;try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,p.match,unknown);fail("unknown type must fail");}catch(GeneralSecurityException expected){}
  byte[] badLength=frame.clone();ByteBuffer.wrap(badLength,1+8,4).putInt(MultiplayerProtocol.MAX_CONTROL+1);try{MultiplayerProtocol.open(p.guest.recvKey,p.guest.recvMarker,1,p.session,p.match,badLength);fail("oversize declared length must fail");}catch(IOException expected){}
  try{MultiplayerProtocol.seal(p.host.sendKey,p.host.sendMarker,Long.MAX_VALUE,MultiplayerProtocol.READY,p.session,p.match,new byte[0]);fail("sequence exhaustion must fail");}catch(GeneralSecurityException expected){}
 }

 @Test public void configBindsExactSolvableBoard() throws Exception {
  Puzzle puzzle=Puzzle.restore(3,"1,2,3,4,5,6,7,0,8",0);String board=puzzle.encode();String hash=MultiplayerProtocol.configHash(3,"number",board,"");byte[] packed=MultiplayerProtocol.packConfig(3,"number",board,"",hash);MultiplayerProtocol.Config c=MultiplayerProtocol.unpackConfig(packed);assertEquals(3,c.size);assertEquals(board,c.board);assertEquals(hash,c.hash);assertTrue(Puzzle.restore(c.size,c.board,0).solvable());
 }

 @Test public void photoMetadataAndChunkBoundsAreStrict() throws Exception {
  String hash=MultiplayerProtocol.sha256Hex("jpeg".getBytes(java.nio.charset.StandardCharsets.US_ASCII));byte[] begin=MultiplayerProtocol.packPhotoBegin(hash,12345,1440,1440);MultiplayerProtocol.PhotoMeta meta=MultiplayerProtocol.unpackPhotoBegin(begin,hash);assertEquals(12345,meta.length);assertEquals(1440,meta.width);assertEquals(1440,meta.height);
  try{MultiplayerProtocol.packPhotoBegin(hash,MultiplayerProtocol.MAX_PHOTO+1,32,32);fail("oversize photo must fail");}catch(IOException expected){}
  try{MultiplayerProtocol.packPhotoBegin(hash,100,1441,1441);fail("huge dimensions must fail");}catch(IOException expected){}
  byte[] huge=new byte[MultiplayerProtocol.PHOTO_CHUNK+1];try{MultiplayerProtocol.seal(new SecretKeySpec(new byte[32],"AES"),0x11111111,1,MultiplayerProtocol.PHOTO_CHUNK_MSG,MultiplayerProtocol.newId(),MultiplayerProtocol.newId(),huge);fail("oversize chunk must fail");}catch(IOException expected){}
 }

 @Test public void resultEnumAndEvidenceAreStrict() throws Exception {
  String hash=MultiplayerProtocol.sha256Hex("config".getBytes(java.nio.charset.StandardCharsets.US_ASCII));assertEquals("HOST",MultiplayerProtocol.unpackResult(MultiplayerProtocol.packResult(hash,"HOST"),hash)[1]);
  try{MultiplayerProtocol.packResult(hash,"OTHER");fail("unknown result must fail");}catch(IOException expected){}
  Puzzle solved=Puzzle.restore(3,"1,2,3,4,5,6,7,8,0",0);Puzzle unsolved=Puzzle.restore(3,"1,2,3,4,5,6,7,0,8",0);
  assertTrue(MultiplayerSession.guestResultEvidenceValid("HOST",unsolved,solved));assertFalse(MultiplayerSession.guestResultEvidenceValid("HOST",unsolved,unsolved));
  assertTrue(MultiplayerSession.guestResultEvidenceValid("GUEST",solved,unsolved));assertFalse(MultiplayerSession.guestResultEvidenceValid("TIE",solved,unsolved));assertTrue(MultiplayerSession.guestResultEvidenceValid("TIE",solved,solved));assertFalse(MultiplayerSession.guestResultEvidenceValid("OTHER",solved,solved));
 }

 @Test public void remoteMoveOnlyAcceptedDuringLiveUnsolvedMatch() throws Exception {
  Puzzle unsolved=Puzzle.restore(3,"1,2,3,4,5,6,7,0,8",0);Puzzle solved=Puzzle.restore(3,"1,2,3,4,5,6,7,8,0",0);
  assertTrue(MultiplayerSession.acceptsRemoteMove(MultiplayerSession.MATCH,unsolved));assertFalse(MultiplayerSession.acceptsRemoteMove(MultiplayerSession.COUNTDOWN,unsolved));assertFalse(MultiplayerSession.acceptsRemoteMove(MultiplayerSession.MATCH,solved));
 }

 @Test public void localWifiHeartbeatAndDirectServiceGuardsAreStrict() {
  assertTrue(MultiplayerSession.acceptsWifiTransport(true,false));assertFalse(MultiplayerSession.acceptsWifiTransport(false,false));assertFalse(MultiplayerSession.acceptsWifiTransport(true,true));
  assertFalse(MultiplayerSession.heartbeatExpired(15000,1));assertTrue(MultiplayerSession.heartbeatExpired(15002,1));
  assertTrue(MultiplayerSession.isDirectServiceDomain("SlidePuzzle-abcd._slidepuzzle._tcp.local."));assertFalse(MultiplayerSession.isDirectServiceDomain("SlidePuzzle-abcd._evil_slidepuzzle._tcp.local."));assertFalse(MultiplayerSession.isDirectServiceDomain(null));
 }
}
