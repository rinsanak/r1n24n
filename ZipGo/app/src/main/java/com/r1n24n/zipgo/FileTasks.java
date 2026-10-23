package com.r1n24n.zipgo;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** File operations are explicitly user triggered; no background scheduler or watcher. */
final class FileTasks {
    private FileTasks(){}
    static void validateName(String name)throws IOException {
        if(name==null||name.length()==0||name.equals(".")||name.equals("..")
            ||name.contains("/")||name.contains("\\")||name.indexOf(0)>=0)
            throw new IOException("Invalid name");
    }
    static String readable(long size) {
        if(size<1024)return size+" B";
        if(size<1024*1024)return String.format(Locale.US,"%.1f KB",size/1024f);
        if(size<1024L*1024*1024)return String.format(Locale.US,"%.1f MB",size/(1024f*1024));
        return String.format(Locale.US,"%.2f GB",size/(1024f*1024*1024));
    }
    static File checked(File child)throws IOException {
        File base=android.os.Environment.getExternalStorageDirectory().getCanonicalFile();
        File real=child.getCanonicalFile();
        if(!real.getPath().equals(base.getPath())&&!real.getPath().startsWith(base.getPath()+File.separator))
            throw new IOException("Outside shared storage");
        return real;
    }
    static void noProtected(File file)throws IOException {
        String path=file.getCanonicalPath();
        if(DataBridge.protectedPath(path))
            throw new IOException("Use Shizuku for Android/data");
    }
    static void copy(File source,File dest)throws IOException {
        source=checked(source);dest=checked(dest);noProtected(source);noProtected(dest);
        if(!source.exists())throw new FileNotFoundException(source.toString());
        if(dest.exists())throw new IOException("Destination already exists");
        if(source.isDirectory()&&dest.getPath().startsWith(source.getPath()+"/"))
            throw new IOException("Cannot copy folder into itself");
        copyInner(source,dest,0,new int[]{0});
    }
    private static void copyInner(File src,File dst,int level,int[] count)throws IOException {
        if(level>60||++count[0]>100000)throw new IOException("Too many nested files");
        if(src.isDirectory()){
            if(!dst.mkdir())throw new IOException("Cannot create folder "+dst.getName());
            File[] children=src.listFiles();
            if(children==null)throw new IOException("Cannot list "+src.getName());
            for(File part:children)copyInner(checked(part),checked(new File(dst,part.getName())),level+1,count);
        } else{
            if(!src.isFile())throw new IOException("Unsupported file");
            try(InputStream in=new BufferedInputStream(new FileInputStream(src));
                OutputStream out=new BufferedOutputStream(new FileOutputStream(dst))) {
                transfer(in,out,null);
            }catch(IOException ex){dst.delete();throw ex;}
        }
    }
    static void move(File src,File dst)throws IOException {
        src=checked(src);dst=checked(dst);noProtected(src);noProtected(dst);
        if(dst.exists())throw new IOException("Destination already exists");
        if(src.renameTo(dst))return;
        copy(src,dst);
        delete(src);
    }
    static void delete(File path)throws IOException{
        path=checked(path);noProtected(path);
        File base=android.os.Environment.getExternalStorageDirectory().getCanonicalFile();
        if(path.equals(base)||path.getPath().endsWith("/Android"))throw new IOException("Protected parent folder");
        remove(path,0,new int[]{0});
    }
    private static void remove(File file,int level,int[] count)throws IOException{
        if(level>64||++count[0]>100000)throw new IOException("Too many nested files");
        if(file.isDirectory()){
            File[] children=file.listFiles();
            if(children==null)throw new IOException("Cannot open "+file.getName());
            for(File item:children)remove(checked(item),level+1,count);
        }
        if(!file.delete())throw new IOException("Cannot delete "+file.getName());
    }
    interface Progress {void message(String detail);}
    static void transfer(InputStream in,OutputStream out,Progress status)throws IOException {
        byte[] buffer=new byte[65536];int n;long written=0;
        while((n=in.read(buffer))!=-1){
            out.write(buffer,0,n);written+=n;
            if(status!=null&&written%(4L*1024*1024)<65536)status.message(readable(written));
        }
    }
    static void zip(List<File> files,File output,Progress progress)throws IOException{
        output=checked(output);noProtected(output);
        if(output.exists())throw new IOException("Archive name already exists");
        Set<String> names=new HashSet<>();
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))){
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for(File file:files){
                file=checked(file);noProtected(file);
                zipInner(file,file.getName(),zip,names,0,new int[]{0},progress);
            }
        }catch(IOException ex){output.delete();throw ex;}
    }
    private static void zipInner(File file,String entry,ZipOutputStream zip,Set<String> names,int level,int[] count,Progress progress)throws IOException{
        if(level>64||++count[0]>100000)throw new IOException("Too many files in archive");
        if(file.isDirectory()){
            if(names.add(entry+"/")){
                ZipEntry e=new ZipEntry(entry+"/");zip.putNextEntry(e);zip.closeEntry();
            }
            File[] children=file.listFiles();
            if(children==null)throw new IOException("Cannot list "+entry);
            for(File part:children)zipInner(checked(part),entry+"/"+part.getName(),zip,names,level+1,count,progress);
        }else{
            if(!names.add(entry))throw new IOException("Duplicate entry "+entry);
            if(progress!=null)progress.message("Compressing "+entry);
            ZipEntry e=new ZipEntry(entry);e.setTime(file.lastModified());
            zip.putNextEntry(e);
            try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
                transfer(in,zip,null);
            }
            zip.closeEntry();
        }
    }
}
