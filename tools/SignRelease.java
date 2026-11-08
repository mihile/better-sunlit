import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Maintainer-only utility. Never bundled with a private signing key. */
public final class SignRelease {
    public static void main(String[] args)throws Exception {
        if(args[0].equals("keygen")){
            Path secret=Path.of(args[1]),pub=Path.of(args[2]);
            if(Files.exists(secret))throw new IllegalStateException("Signing key already exists; never rotate it accidentally");
            var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Files.createDirectories(secret.getParent());Files.write(secret,pair.getPrivate().getEncoded(),StandardOpenOption.CREATE_NEW);
            try{Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Files.createDirectories(pub.getParent());Files.write(pub,pair.getPublic().getEncoded(),StandardOpenOption.CREATE_NEW);
            System.out.println("Created Ed25519 signing key outside repository and public verification key.");
        }else if(args[0].equals("sign")){
            byte[] payload=Files.readAllBytes(Path.of(args[2]));
            var key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
            var signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(payload);
            String envelope="payload="+Base64.getEncoder().encodeToString(payload)+"\nsignature="+Base64.getEncoder().encodeToString(signature.sign())+"\n";
            Files.writeString(Path.of(args[3]),envelope,StandardCharsets.UTF_8);
            System.out.println("Signed release metadata.");
        }else throw new IllegalArgumentException("Use keygen or sign");
    }
}
