import dev.sunlit.bettersunlit.update.*;import java.nio.file.*;import java.util.*;import java.security.*;
class ServerRecoveryTests {
 public static void main(String[]args)throws Exception{UpdaterTests.work=Files.createTempDirectory("better-sunlit-recovery-");UpdaterTests.pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();UpdaterTests.jar=UpdaterTests.jar(2026102502L,Build.CHANNEL);UpdaterTests.valid=SignedRelease.verify(UpdaterTests.signed(UpdaterTests.metadata()),UpdaterTests.pair.getPublic());
  Path failed=stage("failed");Path state=failed.resolve(".better-sunlit-update");Properties pending=SignedRelease.properties(Files.readAllBytes(state.resolve("server-pending.properties")));ServerUpdate.recover(failed,state);UpdaterTests.ok(UpdateFiles.sha(failed.resolve("mods/better-sunlit.jar")).equals(pending.getProperty("oldSha256")),"Previous build not restored");UpdaterTests.ok(Files.exists(state.resolve("blocked.properties")),"Failed build not blocked");UpdaterTests.ok(!Files.exists(state.resolve("server-pending.properties")),"Pending not cleared");
  Path success=stage("success");state=success.resolve(".better-sunlit-update");String hash=UpdateFiles.sha(success.resolve("mods/better-sunlit.jar"));Properties ready=new Properties();ready.setProperty("build",Long.toString(UpdaterTests.valid.build()));ready.setProperty("status","ready");UpdateFiles.writeProperties(state.resolve("server-health.properties"),ready);ServerUpdate.recover(success,state);UpdaterTests.ok(hash.equals(UpdateFiles.sha(success.resolve("mods/better-sunlit.jar"))),"Healthy update rolled back");
  Path manual=stage("manual");Path target=manual.resolve("mods/better-sunlit.jar");Files.writeString(target,"manual replacement");UpdaterTests.rejects(()->ServerUpdate.recover(manual,manual.resolve(".better-sunlit-update")),"Manual replacement overwritten");UpdaterTests.ok(Files.readString(target).equals("manual replacement"),"Manual file damaged");
  testNamedRollback();
  Properties running=new Properties();var self=ProcessHandle.current();running.setProperty("pid",Long.toString(self.pid()));running.setProperty("started",self.info().startInstant().orElseThrow().toString());Path runningState=UpdaterTests.work.resolve("running");Files.createDirectories(runningState);UpdateFiles.writeProperties(runningState.resolve("server-running.properties"),running);UpdaterTests.rejects(()->ServerUpdate.ensureStopped(runningState),"Running server accepted");System.out.println("PASS: "+UpdaterTests.checks+" server recovery checks");
 }
 static void testNamedRollback() throws Exception {
  Path game = stage("named server download", UpdaterTests.DOWNLOAD_NAME);
  Path state = game.resolve(".better-sunlit-update");
  Path target = game.resolve("mods").resolve(UpdaterTests.DOWNLOAD_NAME);
  Properties pending = SignedRelease.properties(Files.readAllBytes(state.resolve("server-pending.properties")));
  ServerUpdate.recover(game, state);
  UpdaterTests.ok(UpdateFiles.sha(target).equals(pending.getProperty("oldSha256")),
          "Rollback did not restore the downloaded filename");
  UpdaterTests.ok(!Files.exists(target.resolveSibling(SignedRelease.FILE)), "Rollback created a second Better Sunlit JAR");
 }

 static Path stage(String name) throws Exception {
  return stage(name, SignedRelease.FILE);
 }

 static Path stage(String name, String filename) throws Exception {
  Path game = UpdaterTests.fixture(name, filename);
  Path state = game.resolve(".better-sunlit-update");
  Path target = game.resolve("mods").resolve(filename);
  String old = UpdateFiles.sha(target);
  ApplyUpdate.apply(game, UpdaterTests.incoming(game), UpdaterTests.valid, old);
  Properties pending = new Properties();
  pending.setProperty("build", Long.toString(UpdaterTests.valid.build()));
  pending.setProperty("newSha256", UpdaterTests.valid.sha256());
  pending.setProperty("oldSha256", old);
  UpdateFiles.writeProperties(state.resolve("server-pending.properties"), pending);
  return game;
 }
}
