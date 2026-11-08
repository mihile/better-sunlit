package dev.sunlit.bettersunlit.update;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.jar.JarFile;

public final class UpdateFiles {
    private UpdateFiles(){}
    public static String sha(Path p)throws IOException {
        try(var in=Files.newInputStream(p)){return digest(in);}
    }
    private static String digest(InputStream in)throws IOException {
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);return HexFormat.of().formatHex(d.digest());}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static void verify(Path p,SignedRelease release)throws IOException {
        if(Files.isSymbolicLink(p) || !Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) || Files.size(p)!=release.size() || !sha(p).equals(release.sha256()))throw new IOException("Downloaded JAR failed verification");
        try(JarFile jar=new JarFile(p.toFile())){
            var entry=jar.getJarEntry("assets/better_sunlit/update-build.properties");
            if(entry==null || jar.getJarEntry("META-INF/mods.toml")==null)throw new IOException("Not an update-enabled compatibility JAR");
            String toml=new String(jar.getInputStream(jar.getJarEntry("META-INF/mods.toml")).readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);
            if(!toml.contains("modId=\"sunlit_fish_visuals\""))throw new IOException("Wrong Forge mod identity");
            try(var in=jar.getInputStream(entry)){
                Properties m=SignedRelease.properties(in.readNBytes(8192));
                if(!SignedRelease.required(m,"build").equals(Long.toString(release.build())) || !SignedRelease.required(m,"channel").equals(Build.CHANNEL) || !SignedRelease.required(m,"pack").equals("sunlit-valley"))throw new IOException("Wrong build or channel in JAR");
                if(!m.getProperty("track","main").equals(release.track()))throw new IOException("Wrong release track in JAR");
            }
        }
    }
    public static String installedTrack(Path p)throws IOException {
        try(var jar=new JarFile(p.toFile())){
            var entry=jar.getJarEntry("assets/better_sunlit/update-build.properties");
            if(entry==null)throw new IOException("Missing build metadata");
            try(var in=jar.getInputStream(entry)){
                String track=SignedRelease.properties(in.readNBytes(8192)).getProperty("track","main");
                if(!track.equals("main") && !track.equals("nightly"))throw new IOException("Invalid installed track");
                return track;
            }
        }
    }
    public static long installedBuild(Path p)throws IOException {
        try(var jar=new JarFile(p.toFile())){
            var entry=jar.getJarEntry("assets/better_sunlit/update-build.properties");
            if(entry==null)throw new IOException("Installed updater build metadata missing");
            try(var in=jar.getInputStream(entry)){
                Properties m=SignedRelease.properties(in.readNBytes(8192));
                if(!Build.CHANNEL.equals(m.getProperty("channel")))throw new IOException("Installed JAR is not a client build");
                try{return Long.parseLong(SignedRelease.required(m,"build"));}catch(NumberFormatException e){throw new IOException("Invalid installed build",e);}
            }
        }
    }
    public static void copyVerified(InputStream in,Path part,SignedRelease release,LongConsumer progress)throws IOException {
        long total=0;
        try(var out=Files.newOutputStream(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;){total+=n;if(total>release.size())throw new IOException("Oversized download");out.write(bytes,0,n);progress.accept(total);}
        }
        if(total!=release.size())throw new IOException("Incomplete download");
        verify(part,release);
    }
    public static Path directory(Path p)throws IOException {
        p=p.toAbsolutePath().normalize();
        for(Path a=p;a!=null;a=a.getParent())if(Files.isSymbolicLink(a))throw new IOException("Linked update directory is not supported");
        Files.createDirectories(p);return p.toRealPath();
    }
    public static Path target(Path game)throws IOException {
        Path root=game.toRealPath();Path mods=root.resolve("mods");
        if(Files.isSymbolicLink(game) || Files.isSymbolicLink(mods) || !mods.toRealPath().startsWith(root))throw new IOException("Linked mods directory is not supported");
        Path target=mods.resolve(SignedRelease.FILE);
        if(Files.isSymbolicLink(target) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Expected compatibility filename is missing");
        return target;
    }
    public static void replace(Path from,Path to)throws IOException {
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
    public static void writeProperties(Path file,Properties properties)throws IOException {
        Path tmp=file.resolveSibling(file.getFileName()+".tmp-"+UUID.randomUUID());
        try(var out=Files.newBufferedWriter(tmp,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {properties.store(out,null);}
        try{replace(tmp,file);}finally{Files.deleteIfExists(tmp);}
    }
    public static void writeBytes(Path file,byte[] bytes)throws IOException {
        Path tmp=file.resolveSibling(file.getFileName()+".tmp-"+UUID.randomUUID());
        Files.write(tmp,bytes,StandardOpenOption.CREATE_NEW);
        try{replace(tmp,file);}finally{Files.deleteIfExists(tmp);}
    }
}
