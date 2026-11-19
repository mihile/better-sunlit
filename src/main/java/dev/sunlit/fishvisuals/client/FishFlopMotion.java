package dev.sunlit.fishvisuals.client;

/** Pose sampled from the catch's launch and server kick times. Angles are in radians. */
record FishFlopMotion(float front, float rear, float tail, float tip) {
    static final FishFlopMotion STILL = new FishFlopMotion(0, 0, 0, 0);

    static FishFlopMotion sample(boolean landed, double elapsed, double sinceKick, float strength,
                                 double remaining, float size, int side) {
        if (!landed) {
            // A short thrash during retrieval, then coast to the landing.
            return elapsed < 11 ? pulse(elapsed / 11.0, 0.35, size, side) : STILL;
        }
        if (remaining <= 0 || strength <= 0) return STILL;
        double phase = sinceKick / Math.max(7, 10.5 * strength);
        return pulse(phase, strength * unit(remaining / 6), size, side);
    }

    private static FishFlopMotion pulse(double phase, double strength, float size, int side) {
        if (phase <= 0 || phase >= 1.49 || strength == 0) {
            return STILL;
        }
        double small = unit((0.72 - size) / 0.42);
        double swing = side * strength * (1 + 0.16 * small);
        // After contact, leave a small delayed tail recoil rather than raising the body again.
        double release = Math.sin(Math.PI * unit((phase - 1.02) / 0.47));
        double recoil = -release * release;
        // The front bends promptly against the kick. Each following section gets the same
        // contraction later, then recoils less strongly as the body relaxes.
        return new FishFlopMotion(
                (float)(swing * 0.30 * flex(phase)),
                (float)(swing * 0.52 * flex(phase - 0.085)),
                (float)(swing * (0.80 * flex(phase - 0.17) + 0.07 * recoil)),
                (float)(swing * (1.08 * flex(phase - 0.255) + 0.23 * recoil)));
    }

    private static double flex(double phase) {
        double motion = phase / 0.745;
        if (motion <= 0 || motion >= 1) return 0;
        boolean retracting = motion < 0.55;
        double progress = retracting ? motion / 0.55 : (motion - 0.55) / 0.45;
        double wave = Math.sin(Math.PI * progress);
        return wave * wave * (retracting ? 1 : -0.43);
    }

    private static double unit(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
