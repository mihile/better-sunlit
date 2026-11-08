package dev.sunlit.bettersunlit.update;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Signed metadata only; no executable commands or destination paths are accepted. */
public record SignedRelease(long build,String label,String title,String sha256,long size,URI asset,String tag,List<String> notes,byte[] envelope) {
    public static final String REPO="mihile/better-sunlit";
    public static final String FILE="better-sunlit.jar";
    public static final URI FEED=URI.create("https://raw.githubusercontent.com/"+REPO+"/"+Build.CHANNEL+"/feed/update.properties");
    public static final long MAX_JAR=64L*1024*1024;
    public static Properties properties(byte[] bytes)throws IOException {
        Properties p=new Properties();p.load(new StringReader(new String(bytes,StandardCharsets.UTF_8)));return p;
    }
    public static PublicKey publicKey()throws GeneralSecurityException,IOException {
        try(var in=SignedRelease.class.getResourceAsStream("/assets/better_sunlit/update-public-key.der")){
            if(in==null)throw new IOException("Missing update public key");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(in.readAllBytes()));
        }
    }
    public static SignedRelease verify(byte[] envelope,PublicKey key)throws IOException,GeneralSecurityException {
        if(envelope.length>128*1024)throw new IOException("Manifest too large");
        try {
            Properties outer=properties(envelope);
            byte[] payload=Base64.getDecoder().decode(required(outer,"payload"));
            byte[] signature=Base64.getDecoder().decode(required(outer,"signature"));
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);
            if(!verifier.verify(signature))throw new SignatureException("Invalid update signature");
            Properties p=properties(payload);
            if(!required(p,"schema").equals("1") || !required(p,"channel").equals(Build.CHANNEL) || !required(p,"mod").equals("better_sunlit") || !required(p,"pack").equals("sunlit-valley") || !required(p,"version").equals("1.0.0") || !required(p,"filename").equals(FILE))throw new IOException("Incompatible update metadata");
            long build=Long.parseLong(required(p,"build")),size=Long.parseLong(required(p,"size"));
            String hash=required(p,"sha256"),tag=required(p,"tag");
            if(build<1 || size<1024 || size>MAX_JAR || !hash.matches("[a-f0-9]{64}") || (!tag.matches("[A-Za-z0-9._-]{1,80}") || !tag.startsWith(Build.CHANNEL+"-")))throw new IOException("Invalid update bounds");
            URI asset=URI.create(required(p,"url"));
            URI expected=URI.create("https://github.com/"+REPO+"/releases/download/"+tag+"/"+FILE);
            if(!asset.equals(expected))throw new IOException("Unexpected release asset");
            List<String> notes=new ArrayList<>();
            for(int i=0;i<40 && p.containsKey("notes."+i);i++)notes.add(bounded(p,"notes."+i,1200));
            if(notes.isEmpty())throw new IOException("Missing update notes");
            return new SignedRelease(build,bounded(p,"label",80),bounded(p,"title",100),hash,size,asset,tag,List.copyOf(notes),envelope.clone());
        }catch(IllegalArgumentException e){throw new IOException("Malformed update metadata",e);}
    }
    private static String bounded(Properties p,String key,int max)throws IOException {
        String v=required(p,key);if(v.length()>max || v.chars().anyMatch(c->c<32))throw new IOException("Invalid display metadata");return v;
    }
    public static String required(Properties p,String key)throws IOException {
        String s=p.getProperty(key);if(s==null || s.isBlank())throw new IOException("Missing "+key);return s;
    }
    public String track(){return "main";}
    public URI page(){return URI.create("https://github.com/"+REPO+"/releases/tag/"+tag);}
}
