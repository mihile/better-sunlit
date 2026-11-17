package net.irisshaders.iris.gl.uniform;

/** Package access to the actual reviewed Uniform constructor; no GL operations. */
public final class RendererTestUniform extends Uniform {
    private final Runnable update;
    public RendererTestUniform(Runnable update) { super(-1); this.update = update; }
    @Override public void update() { update.run(); }
}
