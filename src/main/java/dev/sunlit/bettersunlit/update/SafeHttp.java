package dev.sunlit.bettersunlit.update;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.concurrent.*;

public final class SafeHttp {
    private static final Set<String> HOSTS=Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","raw.githubusercontent.com");
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"Better Sunlit Download Deadline");t.setDaemon(true);return t;});
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public static void trusted(URI uri)throws IOException {
        if(!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))throw new IOException("Untrusted download redirect");
    }
    private InputStream open(URI uri)throws IOException,InterruptedException {
        for(int i=0;i<6;i++){
            trusted(uri);
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent","Better Sunlit-Korean-Compat-Updater/1").header("Accept","application/octet-stream").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()==200)return new DeadlineStream(response.body());
            response.body().close();
            if(Set.of(301,302,303,307,308).contains(response.statusCode())){
                String location=response.headers().firstValue("Location").orElseThrow(()->new IOException("Missing redirect"));
                uri=uri.resolve(location);continue;
            }
            throw new IOException("Update service returned HTTP "+response.statusCode());
        }
        throw new IOException("Too many update redirects");
    }
    private static final class DeadlineStream extends FilterInputStream {
        private final ScheduledFuture<?> deadline;
        DeadlineStream(InputStream in){super(in);deadline=DEADLINES.schedule(()->{try{in.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);}
        @Override public void close()throws IOException{deadline.cancel(false);super.close();}
    }
    public byte[] manifest()throws IOException,InterruptedException {
        return manifest("main");
    }
    public byte[] manifest(String track)throws IOException,InterruptedException {
        if(!"main".equals(track))throw new IOException("Invalid track");
        URI feed=SignedRelease.FEED;
        try(var in=open(feed)){
            byte[] bytes=in.readNBytes(128*1024+1);if(bytes.length>128*1024)throw new IOException("Manifest too large");return bytes;
        }
    }
    public Path download(SignedRelease release,Path incoming,LongConsumer progress)throws IOException,InterruptedException {
        incoming=UpdateFiles.directory(incoming);Path target=incoming.resolve(release.sha256()+".jar");
        if(Files.exists(target)){UpdateFiles.verify(target,release);return target;}
        Path part=incoming.resolve(release.sha256()+".part-"+UUID.randomUUID());
        try{
            try(var in=open(release.asset())){UpdateFiles.copyVerified(in,part,release,progress);}
            UpdateFiles.replace(part,target);return target;
        }finally{Files.deleteIfExists(part);}
    }
}
