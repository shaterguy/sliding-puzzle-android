package com.shaterguy.slidingpuzzle;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Strict, bounded, serverless multiplayer crypto/wire primitives. */
final class MultiplayerProtocol {
 static final int MAGIC=0x53505631; // SPV1
 static final int VERSION=1;
 static final int ID_BYTES=16;
 static final int MAX_HANDSHAKE=8*1024;
 static final int MAX_CONTROL=64*1024;
 static final int MAX_PHOTO=8*1024*1024;
 static final int PHOTO_CHUNK=48*1024;
 static final byte PAIR_CONFIRM=1,CONFIG=2,PHOTO_BEGIN=3,PHOTO_CHUNK_MSG=4,PHOTO_END=5,READY=6,START=7,MOVE=8,RESULT=9,REMATCH=10,LEAVE=11,PING=12,PONG=13,STARTED=14;
 static final byte[] SESSION_SCOPE_ID=new byte[ID_BYTES];
 private static final int HOST_MARKER=0x484F5354, GUEST_MARKER=0x47554553;
 private static final byte[] INFO="sliding-puzzle/session-v1".getBytes(StandardCharsets.US_ASCII);
 private static final byte[] SAS_INFO="sliding-puzzle/sas-v1".getBytes(StandardCharsets.US_ASCII);
 private static final int FRAME_HEADER=1+8+4+ID_BYTES+ID_BYTES;

 static final class Hello {
  final byte[] publicKey,nonce,sessionId;
  Hello(byte[] publicKey,byte[] nonce,byte[] sessionId){this.publicKey=publicKey;this.nonce=nonce;this.sessionId=sessionId;}
 }
 static final class Keys {
  final SecretKey sendKey,recvKey;final int sendMarker,recvMarker;final String sas;
  Keys(SecretKey sendKey,SecretKey recvKey,int sendMarker,int recvMarker,String sas){this.sendKey=sendKey;this.recvKey=recvKey;this.sendMarker=sendMarker;this.recvMarker=recvMarker;this.sas=sas;}
 }
 static final class Decoded {
  final byte type;final byte[] payload,sessionId,matchId;
  Decoded(byte type,byte[] payload,byte[] sessionId,byte[] matchId){this.type=type;this.payload=payload;this.sessionId=sessionId;this.matchId=matchId;}
 }
 static final class PhotoMeta {
  final int length,width,height;final String hash;
  PhotoMeta(String hash,int length,int width,int height){this.hash=hash;this.length=length;this.width=width;this.height=height;}
 }

 static KeyPair newKeyPair() throws GeneralSecurityException {
  KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
  generator.initialize(new ECGenParameterSpec("secp256r1"),new SecureRandom());
  return generator.generateKeyPair();
 }
 static byte[] newNonce(){byte[] n=new byte[16];new SecureRandom().nextBytes(n);return n;}
 static byte[] newId(){byte[] n=new byte[ID_BYTES];new SecureRandom().nextBytes(n);return n;}
 static boolean isSessionScope(byte[] id){return id!=null&&id.length==ID_BYTES&&Arrays.equals(id,SESSION_SCOPE_ID);}
 static boolean isValidId(byte[] id){return id!=null&&id.length==ID_BYTES&&!isSessionScope(id);}
 static String idHex(byte[] id){if(id==null||id.length!=ID_BYTES)throw new IllegalArgumentException("id");StringBuilder out=new StringBuilder(ID_BYTES*2);for(byte b:id)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
 static byte[] parseId(String value) throws IOException {if(value==null||value.length()!=ID_BYTES*2)throw new IOException("id text");byte[] out=new byte[ID_BYTES];for(int i=0;i<out.length;i++){int hi=Character.digit(value.charAt(i*2),16),lo=Character.digit(value.charAt(i*2+1),16);if(hi<0||lo<0)throw new IOException("id text");out[i]=(byte)((hi<<4)|lo);}if(isSessionScope(out))throw new IOException("zero id");return out;}

 static byte[] encodeHello(byte[] publicKey,byte[] nonce,byte[] sessionId) throws IOException {
  if(publicKey==null||publicKey.length<32||publicKey.length>2048||nonce==null||nonce.length!=16||!isValidId(sessionId))throw new IOException("hello bounds");
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
  out.writeInt(MAGIC);out.writeInt(VERSION);out.writeShort(publicKey.length);out.write(publicKey);out.write(nonce);out.write(sessionId);out.flush();
  byte[] result=bytes.toByteArray();if(result.length>MAX_HANDSHAKE)throw new IOException("hello too large");return result;
 }
 static Hello decodeHello(byte[] message) throws IOException {
  if(message==null||message.length<42||message.length>MAX_HANDSHAKE)throw new IOException("hello size");
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(message));
  if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("protocol version");
  int length=in.readUnsignedShort();if(length<32||length>2048||length>in.available()-16-ID_BYTES)throw new IOException("public key size");
  byte[] key=new byte[length];in.readFully(key);byte[] nonce=new byte[16];in.readFully(nonce);byte[] sessionId=new byte[ID_BYTES];in.readFully(sessionId);if(!isValidId(sessionId)||in.available()!=0)throw new IOException("hello trailing data");
  return new Hello(key,nonce,sessionId);
 }

 static Keys derive(boolean host,PrivateKey privateKey,byte[] hostPublic,byte[] guestPublic,byte[] hostNonce,byte[] guestNonce,byte[] sessionId) throws GeneralSecurityException {
  if(privateKey==null||hostNonce==null||guestNonce==null||hostNonce.length!=16||guestNonce.length!=16||!isValidId(sessionId))throw new GeneralSecurityException("handshake state");
  byte[] peerEncoded=host?guestPublic:hostPublic;
  PublicKey peer=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerEncoded));
  KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(privateKey);agreement.doPhase(peer,true);byte[] shared=agreement.generateSecret();
  byte[] transcript=transcript(hostPublic,guestPublic,hostNonce,guestNonce,sessionId);
  byte[] salt=digest(transcript);byte[] prk=hmac(salt,shared);byte[] okm=hkdfExpand(prk,INFO,64);
  SecretKey hostKey=new SecretKeySpec(Arrays.copyOfRange(okm,0,32),"AES");SecretKey guestKey=new SecretKeySpec(Arrays.copyOfRange(okm,32,64),"AES");
  byte[] sasMac=hmac(prk,concat(SAS_INFO,transcript));long value=((sasMac[0]&255L)<<24)|((sasMac[1]&255L)<<16)|((sasMac[2]&255L)<<8)|(sasMac[3]&255L);String sas=String.format(Locale.US,"%08d",value%100000000L);
  Arrays.fill(shared,(byte)0);Arrays.fill(prk,(byte)0);Arrays.fill(okm,(byte)0);
  return host?new Keys(hostKey,guestKey,HOST_MARKER,GUEST_MARKER,sas):new Keys(guestKey,hostKey,GUEST_MARKER,HOST_MARKER,sas);
 }

 static byte[] seal(SecretKey key,int marker,long seq,byte type,byte[] sessionId,byte[] matchId,byte[] payload) throws GeneralSecurityException,IOException {
  if(key==null||seq<=0||seq==Long.MAX_VALUE)throw new GeneralSecurityException("sequence");if(!isValidId(sessionId)||matchId==null||matchId.length!=ID_BYTES)throw new GeneralSecurityException("frame context");if(!knownType(type))throw new IOException("message type");if(payload==null)payload=new byte[0];
  int limit=type==PHOTO_CHUNK_MSG?PHOTO_CHUNK:MAX_CONTROL;if(payload.length>limit)throw new IOException("payload too large");
  int clearLength=payload.length;byte[] aad=aad(marker,seq,type,clearLength,sessionId,matchId);byte[] nonce=nonce(marker,seq);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(aad);byte[] encrypted=cipher.doFinal(payload);
  ByteBuffer frame=ByteBuffer.allocate(FRAME_HEADER+encrypted.length);frame.put(type).putLong(seq).putInt(clearLength).put(sessionId).put(matchId).put(encrypted);return frame.array();
 }
 static Decoded open(SecretKey key,int marker,long expectedSeq,byte[] expectedSessionId,byte[] expectedMatchId,byte[] frame) throws GeneralSecurityException,IOException {
  if(key==null||expectedSeq<=0||!isValidId(expectedSessionId)||frame==null||frame.length<FRAME_HEADER+16||frame.length>FRAME_HEADER+MAX_CONTROL+16)throw new IOException("frame size");
  ByteBuffer input=ByteBuffer.wrap(frame);byte type=input.get();long seq=input.getLong();int clearLength=input.getInt();byte[] sessionId=new byte[ID_BYTES],matchId=new byte[ID_BYTES];input.get(sessionId);input.get(matchId);
  if(!knownType(type)||seq!=expectedSeq)throw new GeneralSecurityException("sequence or type mismatch");if(!Arrays.equals(expectedSessionId,sessionId))throw new GeneralSecurityException("session mismatch");if(expectedMatchId!=null&&!Arrays.equals(expectedMatchId,matchId))throw new GeneralSecurityException("match mismatch");
  int limit=type==PHOTO_CHUNK_MSG?PHOTO_CHUNK:MAX_CONTROL;if(clearLength<0||clearLength>limit||input.remaining()!=clearLength+16)throw new IOException("frame length");
  byte[] encrypted=new byte[input.remaining()];input.get(encrypted);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce(marker,seq)));cipher.updateAAD(aad(marker,seq,type,clearLength,sessionId,matchId));byte[] plain=cipher.doFinal(encrypted);
  if(plain.length!=clearLength)throw new IOException("plaintext length");return new Decoded(type,plain,sessionId,matchId);
 }

 static String sha256Hex(byte[] data) throws GeneralSecurityException {byte[] hash=digest(data);return hex(hash);}
 static String sha256Hex(File file) throws GeneralSecurityException,IOException {MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32*1024];try(InputStream in=new BufferedInputStream(new FileInputStream(file))){for(int n;(n=in.read(buffer))!=-1;)d.update(buffer,0,n);}return hex(d.digest());}
 private static String hex(byte[] data){StringBuilder out=new StringBuilder(data.length*2);for(byte b:data)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
 static String configHash(int size,String mode,String board,String photoHash) throws GeneralSecurityException {
  if(size<3||size>6||!("number".equals(mode)||"photo".equals(mode))||board==null||board.length()>2048||photoHash==null||photoHash.length()>64)throw new GeneralSecurityException("config");
  return sha256Hex((VERSION+"|"+size+"|"+mode+"|"+board+"|"+photoHash).getBytes(StandardCharsets.UTF_8));
 }

 static byte[] packConfig(int size,String mode,String board,String photoHash,String hash) throws IOException {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(size);writeString(out,mode,16);writeString(out,board,2048);writeString(out,photoHash,64);writeString(out,hash,64);out.flush();return bounded(bytes.toByteArray(),MAX_CONTROL);
 }
 static Config unpackConfig(byte[] data) throws IOException {
  DataInputStream in=new DataInputStream(new ByteArrayInputStream(bounded(data,MAX_CONTROL)));int size=in.readInt();String mode=readString(in,16),board=readString(in,2048),photoHash=readString(in,64),hash=readString(in,64);if(in.available()!=0)throw new IOException("config trailing");return new Config(size,mode,board,photoHash,hash);
 }
 static final class Config {final int size;final String mode,board,photoHash,hash;Config(int size,String mode,String board,String photoHash,String hash){this.size=size;this.mode=mode;this.board=board;this.photoHash=photoHash;this.hash=hash;}}

 static byte[] packString(String value,int max) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);writeString(o,value,max);o.flush();return b.toByteArray();}
 static String unpackString(byte[] data,int max) throws IOException {DataInputStream in=new DataInputStream(new ByteArrayInputStream(bounded(data,MAX_CONTROL)));String s=readString(in,max);if(in.available()!=0)throw new IOException("trailing");return s;}
 static byte[] packMove(String hash,int index) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);writeString(o,hash,64);o.writeInt(index);o.flush();return b.toByteArray();}
 static int unpackMove(byte[] data,String expectedHash) throws IOException {DataInputStream in=new DataInputStream(new ByteArrayInputStream(bounded(data,MAX_CONTROL)));String h=readString(in,64);int index=in.readInt();if(in.available()!=0||!expectedHash.equals(h))throw new IOException("stale move");return index;}
 static byte[] packPhotoBegin(String hash,int length,int width,int height) throws IOException {if(hash==null||hash.length()!=64||length<1||length>MAX_PHOTO||width<16||width>1440||height!=width)throw new IOException("photo meta");ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);writeString(o,hash,64);o.writeInt(length);o.writeInt(width);o.writeInt(height);o.flush();return b.toByteArray();}
 static PhotoMeta unpackPhotoBegin(byte[] data,String expectedHash) throws IOException {DataInputStream in=new DataInputStream(new ByteArrayInputStream(bounded(data,MAX_CONTROL)));String h=readString(in,64);int n=in.readInt(),w=in.readInt(),ht=in.readInt();if(in.available()!=0||!expectedHash.equals(h)||h.length()!=64||n<1||n>MAX_PHOTO||w<16||w>1440||ht!=w)throw new IOException("photo begin");return new PhotoMeta(h,n,w,ht);}
 static byte[] packResult(String hash,String code) throws IOException {if(!validResultCode(code))throw new IOException("result code");ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream o=new DataOutputStream(b);writeString(o,hash,64);writeString(o,code,8);o.flush();return b.toByteArray();}
 static String[] unpackResult(byte[] data,String expectedHash) throws IOException {DataInputStream in=new DataInputStream(new ByteArrayInputStream(bounded(data,MAX_CONTROL)));String hash=readString(in,64),code=readString(in,8);if(in.available()!=0||!expectedHash.equals(hash)||!validResultCode(code))throw new IOException("result");return new String[]{hash,code};}
 static boolean validResultCode(String code){return "HOST".equals(code)||"GUEST".equals(code)||"TIE".equals(code);}
 static void requireEmpty(byte[] payload) throws IOException {if(payload==null||payload.length!=0)throw new IOException("payload must be empty");}
 static boolean knownType(byte type){return type>=PAIR_CONFIRM&&type<=STARTED;}
 static boolean sessionScopedType(byte type){return type==PAIR_CONFIRM||type==LEAVE||type==PING||type==PONG;}

 private static void writeString(DataOutputStream out,String value,int max) throws IOException {if(value==null)throw new IOException("null string");byte[] data=value.getBytes(StandardCharsets.UTF_8);if(data.length>max)throw new IOException("string too long");out.writeShort(data.length);out.write(data);}
 private static String readString(DataInputStream in,int max) throws IOException {int n=in.readUnsignedShort();if(n>max||n>in.available())throw new IOException("string size");byte[] data=new byte[n];in.readFully(data);return new String(data,StandardCharsets.UTF_8);}
 private static byte[] bounded(byte[] data,int max) throws IOException {if(data==null||data.length>max)throw new IOException("bounds");return data;}
 private static byte[] transcript(byte[] hostPublic,byte[] guestPublic,byte[] hostNonce,byte[] guestNonce,byte[] sessionId) throws GeneralSecurityException {MessageDigest d=MessageDigest.getInstance("SHA-256");put(d,hostPublic);put(d,guestPublic);put(d,hostNonce);put(d,guestNonce);put(d,sessionId);return d.digest();}
 private static void put(MessageDigest d,byte[] data) throws GeneralSecurityException {if(data==null||data.length>4096)throw new GeneralSecurityException("transcript field");d.update(ByteBuffer.allocate(4).putInt(data.length).array());d.update(data);}
 private static byte[] digest(byte[] data) throws GeneralSecurityException {return MessageDigest.getInstance("SHA-256").digest(data);}
 private static byte[] hmac(byte[] key,byte[] data) throws GeneralSecurityException {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);}
 private static byte[] hkdfExpand(byte[] prk,byte[] info,int length) throws GeneralSecurityException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] previous=new byte[0];int counter=1;while(out.size()<length){Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(prk,"HmacSHA256"));mac.update(previous);mac.update(info);mac.update((byte)counter++);previous=mac.doFinal();out.write(previous,0,previous.length);}return Arrays.copyOf(out.toByteArray(),length);}
 private static byte[] concat(byte[] a,byte[] b){byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
 private static byte[] nonce(int marker,long seq){return ByteBuffer.allocate(12).putInt(marker).putLong(seq).array();}
 private static byte[] aad(int marker,long seq,byte type,int length,byte[] sessionId,byte[] matchId){return ByteBuffer.allocate(4+4+8+1+4+ID_BYTES+ID_BYTES).putInt(VERSION).putInt(marker).putLong(seq).put(type).putInt(length).put(sessionId).put(matchId).array();}
 private MultiplayerProtocol(){}
}
