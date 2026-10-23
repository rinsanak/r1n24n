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

    /** Additional archive creation formats. ZIP AES encryption uses Zip4j. */
    static void createArchive(List<File> source,File output,String format,char[] password,Progress progress)throws Exception{
        format=format.toLowerCase(Locale.ROOT);
        if(format.equals("zip")&&(password==null||password.length==0)){zip(source,output,progress);return;}
        output=checked(output);noProtected(output);
        if(output.exists())throw new IOException("Archive already exists");
        for(File f:source){checked(f);noProtected(f);if(!f.exists())throw new IOException("Missing "+f.getName());}
        try{
            if(format.equals("zip")){
                try(net.lingala.zip4j.ZipFile archive=new net.lingala.zip4j.ZipFile(output,password)){
                    net.lingala.zip4j.model.ZipParameters params=new net.lingala.zip4j.model.ZipParameters();
                    params.setCompressionMethod(net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE);
                    params.setEncryptFiles(true);
                    params.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
                    params.setAesKeyStrength(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256);
                    for(File item:source){
                        if(progress!=null)progress.message("Compressing "+item.getName());
                        if(item.isDirectory())archive.addFolder(item,params);
                        else archive.addFile(item,params);
                    }
                }
            }else if(format.equals("7z")){
                try(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile seven=
                    new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(output)){
                    int[] count={0};
                    for(File src:source)sevenItem(checked(src),src.getName(),seven,0,count,progress);
                    seven.finish();
                }
            }else if(format.equals("tar.gz")){
                try(OutputStream raw=new BufferedOutputStream(new FileOutputStream(output));
                    java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(raw);
                    org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar=
                        new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzip)){
                    tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);
                    int[] count={0};
                    for(File src:source)tarItem(checked(src),src.getName(),tar,0,count,progress);
                    tar.finish();
                }
            }else throw new IOException("Unsupported creation format");
        }catch(Exception e){output.delete();throw e;}
    }
    private static void sevenItem(File src,String name,
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile archive,
        int depth,int[] count,Progress progress)throws Exception{
        if(depth>60||++count[0]>100000)throw new IOException("Too many entries");
        if(progress!=null)progress.message("Compressing "+name);
        org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry=
            archive.createArchiveEntry(src,src.isDirectory()?name+"/":name);
        archive.putArchiveEntry(entry);
        if(src.isFile()){
            try(InputStream in=new BufferedInputStream(new FileInputStream(src))){
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1)archive.write(buffer,0,n);
            }
        }
        archive.closeArchiveEntry();
        if(src.isDirectory()){
            File[] children=src.listFiles();if(children==null)throw new IOException("Cannot list folder");
            for(File child:children)sevenItem(checked(child),name+"/"+child.getName(),archive,depth+1,count,progress);
        }
    }
    private static void tarItem(File src,String name,
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream archive,
        int depth,int[] count,Progress progress)throws Exception{
        if(depth>60||++count[0]>100000)throw new IOException("Too many entries");
        if(progress!=null)progress.message("Compressing "+name);
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry=
            new org.apache.commons.compress.archivers.tar.TarArchiveEntry(src,src.isDirectory()?name+"/":name);
        archive.putArchiveEntry(entry);
        if(src.isFile()){
            try(InputStream in=new BufferedInputStream(new FileInputStream(src))){
                transfer(in,archive,null);
            }
        }
        archive.closeArchiveEntry();
        if(src.isDirectory()){
            File[] children=src.listFiles();if(children==null)throw new IOException("Cannot list folder");
            for(File child:children)tarItem(checked(child),name+"/"+child.getName(),archive,depth+1,count,progress);
        }
    }
}
