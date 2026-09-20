package com.shaterguy.slidingpuzzle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

final class PhotoStore {
 private final Context context; private final File folder;
 PhotoStore(Context context){this.context=context.getApplicationContext();folder=new File(context.getFilesDir(),"photos");folder.mkdirs();}
 File file(String name) throws IOException {if(name==null||!name.matches("[a-f0-9]{64}\\.jpg"))throw new IOException("Invalid photo");return new File(folder,name);}
 File[] list(){File[] files=folder.listFiles((d,n)->n.matches("[a-f0-9]{64}\\.jpg"));if(files==null)return new File[0];Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return files;}
 void delete(String name)throws IOException{File f=file(name);if(f.exists()&&!f.delete())throw new IOException("Delete failed");}
 Bitmap load(String name)throws IOException {Bitmap b=BitmapFactory.decodeFile(file(name).getPath());if(b==null)throw new IOException("Invalid image");return b;}
 byte[] readForPeer(String name)throws IOException{
  File f=file(name);long length=f.length();if(length<1||length>MultiplayerProtocol.MAX_PHOTO)throw new IOException("Invalid peer photo size");
  ByteArrayOutputStream out=new ByteArrayOutputStream((int)length);try(InputStream in=new FileInputStream(f)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>MultiplayerProtocol.MAX_PHOTO)throw new IOException("Peer photo too large");out.write(buffer,0,n);}}byte[] data=out.toByteArray();if(data.length!=length)throw new IOException("Peer photo truncated");return data;
 }
 String importPhoto(Uri uri)throws Exception{
  if(uri==null || !"content".equals(uri.getScheme()))throw new IOException("Unsupported image URI");
  File incoming=File.createTempFile("photo-",".tmp",context.getCacheDir());
  try{
   try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(incoming)){
    if(in==null)throw new IOException("Unavailable photo");byte[] buffer=new byte[32768];long total=0;int n;
    while((n=in.read(buffer))!=-1){total+=n;if(total>80L*1024*1024)throw new IOException("Photo exceeds 80 MB");out.write(buffer,0,n);}
   }
   BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(incoming.getPath(),options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Invalid image");
   options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
   Bitmap original=BitmapFactory.decodeFile(incoming.getPath(),options);if(original==null)throw new IOException("Invalid image");int orientation=new ExifInterface(incoming).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix matrix=new Matrix();
   switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
   Bitmap oriented=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);if(oriented!=original)original.recycle();int edge=Math.min(oriented.getWidth(),oriented.getHeight());Bitmap square=Bitmap.createBitmap(oriented,(oriented.getWidth()-edge)/2,(oriented.getHeight()-edge)/2,edge,edge);if(square!=oriented)oriented.recycle();if(edge>1440){Bitmap resized=Bitmap.createScaledBitmap(square,1440,1440,true);if(resized!=square)square.recycle();square=resized;}
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean ok=square.compress(Bitmap.CompressFormat.JPEG,92,bytes);square.recycle();if(!ok)throw new IOException("Encode failed");byte[] data=bytes.toByteArray();byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format("%02x",b&255));name.append(".jpg");File target=file(name.toString());if(!target.exists()){File temp=File.createTempFile("normalized-",".tmp",folder);try{try(FileOutputStream output=new FileOutputStream(temp)){output.write(data);output.getFD().sync();}if(!temp.renameTo(target)&&!target.exists())throw new IOException("Save failed");}finally{temp.delete();}}target.setLastModified(System.currentTimeMillis());return name.toString();
  }finally{incoming.delete();}
 }
}
