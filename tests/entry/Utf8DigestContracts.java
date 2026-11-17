import dev.sunlit.bettersunlit.client.Utf8Digest;
import dev.sunlit.bettersunlit.client.RenderSectionIndexCapacity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Utf8DigestContracts {
    static int checks;
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    static byte[] reference(String value)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);
        int size=bytes==null?-1:bytes.length;
        digest.update((byte)(size>>>24));digest.update((byte)(size>>>16));digest.update((byte)(size>>>8));digest.update((byte)size);
        if(bytes!=null)digest.update(bytes);return digest.digest();
    }
    static byte[] actual(String value)throws Exception{var digest=MessageDigest.getInstance("SHA-256");Utf8Digest.write(digest,value);return digest.digest();}
    static void compare(String value)throws Exception{check(Arrays.equals(reference(value),actual(value)),"UTF-8 digest differs, length "+(value==null?-1:value.length()));}
    public static void run()throws Exception{
        compare(null);compare("");Random random=new Random(2026111803L);
        for(int size:new int[]{1,1024,4095,4096,4097,16380,16381,16382,16383,16384,16385,32768,524288}){
            compare("a".repeat(size));
            char[] chars=new char[size];for(int i=0;i<size;i++)chars[i]=(char)random.nextInt(65536);compare(new String(chars));
            for(char c:new char[]{0,127,128,2047,2048,'\ud800','\udbff','\udc00','\udfff','\uffff'}){
                Arrays.fill(chars,'a');chars[size-1]=c;if(size>4096)chars[16380%size]=c;compare(new String(chars));
            }
            compare(("abc한글\ud83d\ude00\ud800x\udc00".repeat(size/10+1)).substring(0,size));
        }
        for(int boundary:new int[]{4095,8191,8192,16383}) {
            char[] chars=new char[20000];Arrays.fill(chars,'x');chars[boundary]='\ud83d';chars[boundary+1]='\ude00';compare(new String(chars));
            chars[boundary+1]='x';compare(new String(chars));chars[boundary]='\udc00';compare(new String(chars));
        }
        for(int trial=0;trial<1800;trial++){
            char[] chars=new char[random.nextInt(12000)];
            for(int i=0;i<chars.length;i++) chars[i]=(char)(trial%2==0?random.nextInt(128):random.nextInt(65536));
            compare(new String(chars));
        }
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();List<Thread> workers=new ArrayList<>();
        for(int i=0;i<4;i++){int seed=i;var worker=new Thread(()->{try{
            Random r=new Random(seed);for(int k=0;k<200;k++){char[] chars=new char[10000];for(int j=0;j<chars.length;j++)chars[j]=(char)r.nextInt(65536);
                if(!Arrays.equals(reference(new String(chars)),actual(new String(chars))))throw new AssertionError("UTF-8 worker scratch contamination");}
        }catch(Throwable t){failure.set(t);}});workers.add(worker);worker.start();}
        for(var worker:workers)worker.join();check(failure.get()==null,"UTF-8 thread isolation "+failure.get());
        for(int distance:new int[]{Integer.MIN_VALUE,0,2,8,12,32,Integer.MAX_VALUE})for(int height:new int[]{0,1,24,256,Integer.MAX_VALUE}){
            int capacity=RenderSectionIndexCapacity.expected(distance,height);check(capacity>=256&&capacity<=65536,"section capacity not bounded");
        }
        int count=25*25*24;
        var baseline=new it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<Object>();
        var sized=new it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap<Object>(RenderSectionIndexCapacity.expected(12,24));
        var n=it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap.class.getDeclaredField("n");n.setAccessible(true);
        int oldN=n.getInt(baseline),newN=n.getInt(sized),oldGrowth=0,newGrowth=0;Object item=new Object();
        for(int i=0;i<count;i++){
            long key=((long)i<<32)^i;baseline.put(key,item);sized.put(key,item);
            int oldNext=n.getInt(baseline),newNext=n.getInt(sized);if(oldNext!=oldN){oldGrowth++;oldN=oldNext;}if(newNext!=newN){newGrowth++;newN=newNext;}
        }
        check(oldGrowth>0&&newGrowth==0,"initial section-map expansions were not removed");
        for(int i=0;i<count;i++)check(baseline.get(((long)i<<32)^i)==sized.get(((long)i<<32)^i),"section map lookup changed");
        for(int i=0;i<count;i+=2)check(baseline.remove(((long)i<<32)^i)==sized.remove(((long)i<<32)^i),"section removal changed");
        check(baseline.size()==sized.size(),"section size changed");sized.clear();check(sized.isEmpty(),"section clear changed");
        String source="// common shader source\n".repeat(16000);
        for(int i=0;i<40;i++){reference(source);actual(source);}
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
        long bytes=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();for(int i=0;i<100;i++)reference(source);long oldTime=System.nanoTime()-start,oldBytes=bean.getThreadAllocatedBytes(thread)-bytes;
        bytes=bean.getThreadAllocatedBytes(thread);start=System.nanoTime();for(int i=0;i<100;i++)actual(source);
        System.out.printf("PASS: %d UTF-8/section-index contracts; identical cache bytes; 15000 sections resized %d -> %d times; 100 large strings %.2f -> %.2f ms, allocated %d -> %d bytes%n",checks,oldGrowth,newGrowth,oldTime/1e6,(System.nanoTime()-start)/1e6,oldBytes,bean.getThreadAllocatedBytes(thread)-bytes);
    }
    public static void main(String[] args)throws Exception{run();}
}
