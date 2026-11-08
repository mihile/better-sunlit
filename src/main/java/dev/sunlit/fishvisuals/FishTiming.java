package dev.sunlit.fishvisuals;

/** Immutable per-catch settings, measured in server ticks. */
public record FishTiming(int freshTicks, int fadingTicks, int stepTicks) {
    public static final FishTiming DEFAULT = new FishTiming(60,60,20);
    public FishTiming {
        if (freshTicks<0 || freshTicks>12000 || fadingTicks<0 || fadingTicks>12000 || stepTicks<20 || stepTicks>12000)
            throw new IllegalArgumentException("Invalid realfishing timing");
    }
    public int totalTicks() {return freshTicks+fadingTicks;}
    public float strength(long elapsed) {
        if (elapsed<freshTicks) return 1;
        if (elapsed>=totalTicks() || fadingTicks==0) return 0;
        int stages=(fadingTicks+stepTicks-1)/stepTicks;
        long stage=(elapsed-freshTicks)/stepTicks;
        return (float)(1.0-(stage+1.0)/(stages+1.0));
    }
    public String description() {
        return "싱싱함 "+freshTicks/20+"초 / 약해짐 "+fadingTicks/20+"초 / 단계 간격 "+stepTicks/20+"초";
    }
}
