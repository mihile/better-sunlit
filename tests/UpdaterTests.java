import dev.sunlit.bettersunlit.update.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

public final class UpdaterTests {
    static final String SIDE=Build.CHANNEL,OTHER=SIDE.equals("client")?"server":"client";
    static int checks;
    static KeyPair pair;
    static Path work;
    static byte[] jar;
    static SignedRelease valid;
    interface Throwing {void run()throws Exception;}
    static void ok(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static void rejects(Throwing action,String message)throws Exception {
        try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(message);
    }
    static byte[] properties(Properties p)throws Exception {StringWriter out=new StringWriter();p.store(out,null);return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    static byte[] signed(Properties p)throws Exception {
        byte[] bytes=properties(p);var s=Signature.getInstance("Ed25519");s.initSign(pair.getPrivate());s.update(bytes);Properties outer=new Properties();outer.setProperty("payload",Base64.getEncoder().encodeToString(bytes));outer.setProperty("signature",Base64.getEncoder().encodeToString(s.sign()));return properties(outer);
    }
    static Properties metadata()throws Exception {
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("channel",SIDE);p.setProperty("pack","sunlit-valley");p.setProperty("mod","better_sunlit");p.setProperty("version","1.0.0");p.setProperty("filename",SignedRelease.FILE);p.setProperty("build","2026102502");p.setProperty("label","2026.10.25-2");p.setProperty("title","테스트 업데이트");p.setProperty("tag",SIDE+"-build-20261025.2");p.setProperty("url","https://github.com/"+SignedRelease.REPO+"/releases/download/"+SIDE+"-build-20261025.2/"+SignedRelease.FILE);p.setProperty("size",Integer.toString(jar.length));Path file=work.resolve("hash.jar");Files.write(file,jar);p.setProperty("sha256",UpdateFiles.sha(file));p.setProperty("notes.0","변경 내역은 바로 표시됩니다.");return p;
    }
    static byte[] jar(long build,String channel)throws Exception {
        var out=new ByteArrayOutputStream();try(var z=new JarOutputStream(out)){
            z.putNextEntry(new JarEntry("META-INF/mods.toml"));z.write("modId=\"sunlit_fish_visuals\"\nversion=\"1.0.0\"".getBytes());z.closeEntry();
            z.putNextEntry(new JarEntry("assets/better_sunlit/update-build.properties"));z.write(("build="+build+"\nchannel="+channel+"\npack=sunlit-valley\n").getBytes());z.closeEntry();
            z.putNextEntry(new JarEntry("test-data.bin"));byte[] noise=new byte[4096];new Random(7).nextBytes(noise);z.write(noise);z.closeEntry();
        }return out.toByteArray();
    }
    static Path fixture(String name)throws Exception {
        Path game=work.resolve(name);Files.createDirectories(game.resolve("mods"));Files.write(game.resolve("mods").resolve(SignedRelease.FILE),jar(2026102501L,SIDE));return game;
    }
    static Path incoming(Path game)throws Exception {Path p=game.resolve(".better-sunlit-update/incoming").resolve(valid.sha256()+".jar");Files.createDirectories(p.getParent());Files.write(p,jar);return p;}
    static void mutation(String key,String value)throws Exception {Properties p=metadata();p.setProperty(key,value);rejects(()->SignedRelease.verify(signed(p),pair.getPublic()),"Accepted invalid "+key);}
    public static void main(String[] args)throws Exception {
        Properties notes = new Properties(); notes.setProperty("notes.0", "수정 :: 시작 중 멈춤 해결");
        List<String> installed = ReleaseNotes.read(notes);
        ok(installed.equals(List.of("수정 :: 시작 중 멈춤 해결")), "embedded changelog");
        ok(ReleaseNotes.latest(6, installed, -1, null) == installed, "offline changelog");
        ok(ReleaseNotes.latest(6, installed, 5, List.of("old")) == installed, "older feed cannot replace local changelog");
        ok(ReleaseNotes.latest(6, installed, 6, List.of("same")) == installed, "installed notes for same build");
        ok(ReleaseNotes.latest(6, installed, 7, List.of("new")).equals(List.of("new")), "new release changelog");
        ok(!ReleaseNotes.read(new Properties()).get(0).contains("낚시"), "no evergreen feature fallback");

        ok(SignedRelease.FEED.toString().equals("https://raw.githubusercontent.com/"+SignedRelease.REPO+"/"+SIDE+"/feed/update.properties"),"Feed was initialized before channel identity");
        if(SIDE.equals("client"))try(var in=UpdaterTests.class.getResourceAsStream("/dev/sunlit/bettersunlit/client/UpdateClient.class")){ok(in!=null,"Missing UpdateClient class");String cls=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);ok(cls.contains("m_91265_")&&!cls.contains("m_91088_"),"UpdateClient must check getOverlay (m_91265_) and never invoke reloadResourcePacks (m_91088_)");}
        work=Files.createTempDirectory("prominence-updater-tests-테스트-");pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();jar=jar(2026102502L,SIDE);
        byte[] envelope=signed(metadata());valid=SignedRelease.verify(envelope,pair.getPublic());ok(valid.notes().size()==1,"Missing readable notes");
        byte[] tampered=envelope.clone();tampered[tampered.length/3]^=1;rejects(()->SignedRelease.verify(tampered,pair.getPublic()),"Accepted altered manifest");
        var wrong=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();rejects(()->SignedRelease.verify(envelope,wrong.getPublic()),"Accepted wrong signing key");
        rejects(()->SignedRelease.verify(new byte[131073],pair.getPublic()),"Accepted oversized manifest");
        for(String[] m:new String[][]{{"channel",OTHER},{"pack","4.1.2"},{"mod","other"},{"schema","2"},{"version","2.0.0"},{"filename","../other.jar"},{"build","0"},{"size","0"},{"size","67108865"},{"sha256","bad"},{"tag","../latest"},{"url","https://evil.example/file.jar"},{"title","bad\nline"},{"label","x".repeat(81)}})mutation(m[0],m[1]);
        var noNotes=metadata();noNotes.remove("notes.0");rejects(()->SignedRelease.verify(signed(noNotes),pair.getPublic()),"Accepted missing notes");
        for(String uri:List.of("http://github.com/file","https://github.com.evil.example/file","https://user@github.com/file","https://github.com:444/file","https://localhost/file","file:///tmp/test"))rejects(()->SafeHttp.trusted(URI.create(uri)),"Accepted untrusted URL");
        SafeHttp.trusted(URI.create("https://release-assets.githubusercontent.com/file?temporary-token=test"));checks++;
        Path good=work.resolve("good.jar");UpdateFiles.copyVerified(new ByteArrayInputStream(jar),good,valid,n->{});ok(UpdateFiles.sha(good).equals(valid.sha256()),"Wrong download");
        rejects(()->UpdateFiles.copyVerified(new ByteArrayInputStream(Arrays.copyOf(jar,jar.length-1)),work.resolve("short.part"),valid,n->{}),"Accepted truncated download");
        byte[] changed=jar.clone();changed[500]^=1;rejects(()->UpdateFiles.copyVerified(new ByteArrayInputStream(changed),work.resolve("changed.part"),valid,n->{}),"Accepted changed download");
        rejects(()->UpdateFiles.copyVerified(new ByteArrayInputStream(Arrays.copyOf(jar,jar.length+1)),work.resolve("large.part"),valid,n->{}),"Accepted oversized download");
        byte[] proper=jar;jar=jar(2026102502L,OTHER);SignedRelease wrongChannelJar=SignedRelease.verify(signed(metadata()),pair.getPublic());Path serverFile=work.resolve("wrong-channel.jar");Files.write(serverFile,jar);rejects(()->UpdateFiles.verify(serverFile,wrongChannelJar),"Accepted server JAR behind client manifest");jar=proper;
        Path game=fixture("正常 적용");Path old=game.resolve("mods").resolve(SignedRelease.FILE);String before=UpdateFiles.sha(old);ApplyUpdate.apply(game,incoming(game),valid,before);ok(UpdateFiles.sha(old).equals(valid.sha256()),"Apply failed");ok(UpdateFiles.sha(game.resolve(".better-sunlit-update/backups").resolve(before+"-"+SignedRelease.FILE)).equals(before),"Backup failed");
        game=fixture("manual-change");Path target=game.resolve("mods").resolve(SignedRelease.FILE);String prior=UpdateFiles.sha(target);Path next=incoming(game);Files.writeString(target,"manual update");Path g=game;rejects(()->ApplyUpdate.apply(g,next,valid,prior),"Overwrote manual update");ok(Files.readString(target).equals("manual update"),"Damaged manual update");
        Path other=work.resolve("unrelated.jar");Files.write(other,jar);Path badGame=fixture("outside-staging");String badHash=UpdateFiles.sha(badGame.resolve("mods").resolve(SignedRelease.FILE));rejects(()->ApplyUpdate.apply(badGame,other,valid,badHash),"Accepted arbitrary staged path");
        Path rollback=fixture("rollback");Files.write(rollback.resolve("mods").resolve(SignedRelease.FILE),jar(2026102503L,SIDE));String newer=UpdateFiles.sha(rollback.resolve("mods").resolve(SignedRelease.FILE));Path older=incoming(rollback);rejects(()->ApplyUpdate.apply(rollback,older,valid,newer),"Accepted rollback to older build");
        Path corrupt=fixture("corrupt-candidate");String original=UpdateFiles.sha(corrupt.resolve("mods").resolve(SignedRelease.FILE));Path broken=incoming(corrupt);Files.writeString(broken,"broken");rejects(()->ApplyUpdate.apply(corrupt,broken,valid,original),"Applied broken candidate");ok(UpdateFiles.sha(corrupt.resolve("mods").resolve(SignedRelease.FILE)).equals(original),"Damaged original after verification failure");
        Path symbolic=fixture("symlink");Path outside=work.resolve("outside-mods");Files.createDirectories(outside);
        try{Files.delete(symbolic.resolve("mods").resolve(SignedRelease.FILE));Files.delete(symbolic.resolve("mods"));Files.createSymbolicLink(symbolic.resolve("mods"),outside);rejects(()->UpdateFiles.target(symbolic),"Accepted linked mods");}catch(FileSystemException unsupported){System.out.println("SKIP: symbolic links require OS privilege");}
        testAfterExit(args[0]);
        testChangedPlan(args[0]);
        System.out.println("PASS: "+checks+" updater checks; isolated filesystem and helper processes only; no Minecraft/server/world launched.");
    }
    static void testAfterExit(String productionHelper)throws Exception {
        Path testHelper=work.resolve("test-worker.jar");
        try(var old=new JarFile(productionHelper);var out=new JarOutputStream(Files.newOutputStream(testHelper))){
            for(var en:Collections.list(old.entries())){
                out.putNextEntry(new JarEntry(en.getName()));out.write(en.getName().endsWith("update-public-key.der")?pair.getPublic().getEncoded():old.getInputStream(en).readAllBytes());out.closeEntry();
            }
        }
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path game=fixture("wait for parent 종료");Path target=game.resolve("mods").resolve(SignedRelease.FILE);String before=UpdateFiles.sha(target);incoming(game);Path root=game.resolve(".better-sunlit-update");Path gate=work.resolve("parent-exit.gate");
        String parentCp=Path.of(UpdaterTests.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();Process parent=new ProcessBuilder(java,"-cp",parentCp,"UpdaterTests$Parent",gate.toString()).start();
        Properties p=new Properties();p.setProperty("game",game.toString());p.setProperty("parentPid",Long.toString(parent.pid()));p.setProperty("parentStarted",parent.toHandle().info().startInstant().orElseThrow().toString());p.setProperty("build",Long.toString(valid.build()));p.setProperty("oldSha256",before);UpdateFiles.writeProperties(root.resolve("pending.properties"),p);Files.write(root.resolve("manifest.properties"),signed(metadata()));
        Process worker=new ProcessBuilder(java,"-cp",testHelper.toString(),ApplyUpdate.class.getName(),root.resolve("pending.properties").toString()).redirectErrorStream(true).redirectOutput(work.resolve("worker.log").toFile()).start();
        Thread.sleep(700);ok(worker.isAlive(),"Helper died while parent alive");ok(UpdateFiles.sha(target).equals(before),"Replaced running parent's JAR");
        Files.writeString(gate,"exit");ok(parent.waitFor(10,TimeUnit.SECONDS),"Test parent did not exit");ok(worker.waitFor(15,TimeUnit.SECONDS),"Helper did not finish after parent exit");ok(worker.exitValue()==0,"Helper failed: "+Files.readString(work.resolve("worker.log")));ok(UpdateFiles.sha(target).equals(valid.sha256()),"Helper did not apply after exit");ok(!Files.exists(root.resolve("pending.properties")),"Pending plan not cleared");
        ok(SignedRelease.properties(Files.readAllBytes(root.resolve("last-update.properties"))).getProperty("status").equals("applied"),"Missing success receipt");
    }
    public static final class Parent {public static void main(String[] args)throws Exception{for(int i=0;i<200 && !Files.exists(Path.of(args[0]));i++)Thread.sleep(100);}}
    static void testChangedPlan(String productionHelper)throws Exception {
        // Reuse the test-key helper created by the successful after-exit case.
        Path testHelper=work.resolve("test-worker.jar"),game=fixture("changed approved plan"),root=game.resolve(".better-sunlit-update"),gate=work.resolve("parent-exit2.gate");incoming(game);
        Path target=game.resolve("mods").resolve(SignedRelease.FILE);String before=UpdateFiles.sha(target);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String parentCp=Path.of(UpdaterTests.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();Process parent=new ProcessBuilder(java,"-cp",parentCp,"UpdaterTests$Parent",gate.toString()).start();
        Properties p=new Properties();p.setProperty("game",game.toString());p.setProperty("parentPid",Long.toString(parent.pid()));p.setProperty("parentStarted",parent.toHandle().info().startInstant().orElseThrow().toString());p.setProperty("build",Long.toString(valid.build()));p.setProperty("oldSha256",before);UpdateFiles.writeProperties(root.resolve("pending.properties"),p);Files.write(root.resolve("manifest.properties"),signed(metadata()));
        Process worker=new ProcessBuilder(java,"-cp",testHelper.toString(),ApplyUpdate.class.getName(),root.resolve("pending.properties").toString()).redirectErrorStream(true).redirectOutput(work.resolve("changed-worker.log").toFile()).start();
        Thread.sleep(700);p.setProperty("oldSha256","0".repeat(64));UpdateFiles.writeProperties(root.resolve("pending.properties"),p);Files.writeString(gate,"exit");parent.waitFor(10,TimeUnit.SECONDS);
        ok(worker.waitFor(15,TimeUnit.SECONDS),"Changed-plan helper did not stop");ok(worker.exitValue()!=0,"Accepted changed approved plan");ok(UpdateFiles.sha(target).equals(before),"Modified target after plan changed");
    }
}
