import java.nio.file.*;import dev.sunlit.bettersunlit.update.*;
class VerifyRelease {public static void main(String[]a)throws Exception{SignedRelease r=SignedRelease.verify(Files.readAllBytes(Path.of(a[0])),SignedRelease.publicKey());UpdateFiles.verify(Path.of(a[1]),r);System.out.println("Verified "+Build.CHANNEL+" build="+r.build()+" sha256="+r.sha256());}}
