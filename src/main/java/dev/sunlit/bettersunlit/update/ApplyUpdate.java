package dev.sunlit.bettersunlit.update;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate Java process: waits for the approved game process to exit, then replaces one JAR. */
public final class ApplyUpdate {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected one pending plan");
        Path plan=Path.of(args[0]).toAbsolutePath().normalize();
        byte[] approvedPlan=Files.readAllBytes(plan);
        Properties p=SignedRelease.properties(approvedPlan);
        Path game=Path.of(SignedRelease.required(p,"game")).toRealPath();
        Path root=UpdateFiles.directory(game.resolve(".better-sunlit-update"));
        if(!plan.equals(root.resolve("pending.properties")) || Files.isSymbolicLink(plan))throw new IOException("Invalid pending plan location");
        long pid=Long.parseLong(SignedRelease.required(p,"parentPid"));
        Instant started=Instant.parse(SignedRelease.required(p,"parentStarted"));
        var parent=ProcessHandle.of(pid);
        if(parent.isPresent() && parent.get().isAlive()){
            if(!parent.get().info().startInstant().orElseThrow().equals(started))throw new IOException("Parent PID changed");
            parent.get().onExit().get(72,TimeUnit.HOURS);
        }
        Properties receipt=new Properties();
        try(var channel=FileChannel.open(root.resolve("apply.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another update is applying");
            // A queued plan cannot silently switch to a different payload while waiting.
            if(!Arrays.equals(Files.readAllBytes(plan),approvedPlan))throw new IOException("Pending plan changed");
            SignedRelease release=SignedRelease.verify(Files.readAllBytes(root.resolve("manifest.properties")),SignedRelease.publicKey());
            if(release.build()!=Long.parseLong(SignedRelease.required(p,"build")))throw new IOException("Unexpected pending build");
            Path incoming=root.resolve("incoming").resolve(release.sha256()+".jar");
            apply(game,incoming,release,SignedRelease.required(p,"oldSha256"),"true".equals(p.getProperty("approvedMainReturn")));
            receipt.setProperty("status","applied");receipt.setProperty("build",Long.toString(release.build()));
            Files.delete(plan);
        }catch(Exception e){
            receipt.setProperty("status","failed");receipt.setProperty("build",p.getProperty("build","unknown"));receipt.setProperty("reason",e.getClass().getSimpleName());
            throw e;
        }finally{UpdateFiles.writeProperties(root.resolve("last-update.properties"),receipt);}
    }
    public static void apply(Path game,Path incoming,SignedRelease release,String oldHash)throws Exception {
        apply(game,incoming,release,oldHash,false);
    }
    public static void apply(Path game,Path incoming,SignedRelease release,String oldHash,boolean approvedMainReturn)throws Exception {
        Path target=UpdateFiles.target(game);Path root=UpdateFiles.directory(game.resolve(".better-sunlit-update"));
        Path staging=UpdateFiles.directory(root.resolve("incoming"));
        if(!incoming.toAbsolutePath().normalize().equals(staging.resolve(release.sha256()+".jar")))throw new IOException("Invalid staged file location");
        if(!oldHash.matches("[a-f0-9]{64}") || !UpdateFiles.sha(target).equals(oldHash))throw new IOException("Installed JAR changed; preserve manual update");
        if(release.build()<=UpdateFiles.installedBuild(target))throw new IOException("Update is not newer than installed build");
        UpdateFiles.verify(incoming,release);
        Path backup=UpdateFiles.directory(root.resolve("backups")).resolve(oldHash+"-"+SignedRelease.FILE);
        if(Files.exists(backup)){
            if(Files.isSymbolicLink(backup) || !UpdateFiles.sha(backup).equals(oldHash))throw new IOException("Invalid existing backup");
        }else{Files.copy(target,backup);if(!UpdateFiles.sha(backup).equals(oldHash))throw new IOException("Backup verification failed");}
        IOException failure=null;
        for(int attempt=0;attempt<8;attempt++){
            if(!UpdateFiles.sha(target).equals(oldHash))throw new IOException("Installed JAR changed before replace");
            try{UpdateFiles.replace(incoming,target);failure=null;break;}
            catch(IOException e){failure=e;Thread.sleep(500);}
        }
        if(failure!=null)throw failure;
        try{UpdateFiles.verify(target,release);}
        catch(Exception e){Files.copy(backup,target,StandardCopyOption.REPLACE_EXISTING);throw e;}
    }
}
