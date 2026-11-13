package dev.sunlit.bettersunlit.inline;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Fingerprints executable instructions only; debug lines and stack-map frames vary at load time. */
public final class InlineBytecode {
    private InlineBytecode() {}

    public static String hash(MethodNode method) {
        Map<LabelNode,Integer> labels=new IdentityHashMap<>();
        int index=0;
        for (AbstractInsnNode n:method.instructions) {
            if (n instanceof LabelNode label) labels.put(label,index);
            if (n.getOpcode()>=0) index++;
        }
        StringBuilder text=new StringBuilder(method.name).append(method.desc).append('\n');
        for (AbstractInsnNode n:method.instructions) {
            if (n.getOpcode()<0) continue;
            text.append(n.getOpcode()).append(':');
            if(n instanceof VarInsnNode v) text.append(v.var);
            else if(n instanceof IntInsnNode v) text.append(v.operand);
            else if(n instanceof LdcInsnNode v) text.append(v.cst.getClass().getName()).append('=').append(v.cst);
            else if(n instanceof TypeInsnNode v) text.append(v.desc);
            else if(n instanceof FieldInsnNode v) text.append(v.owner).append('.').append(v.name).append(v.desc);
            else if(n instanceof MethodInsnNode v) text.append(v.owner).append('.').append(v.name).append(v.desc).append(':').append(v.itf);
            else if(n instanceof InvokeDynamicInsnNode v) text.append(v.name).append(v.desc).append(v.bsm).append(Arrays.deepToString(v.bsmArgs));
            else if(n instanceof JumpInsnNode v) text.append(labels.get(v.label));
            else if(n instanceof IincInsnNode v) text.append(v.var).append(',').append(v.incr);
            else if(n instanceof TableSwitchInsnNode v) {
                text.append(v.min).append(',').append(v.max).append(',').append(labels.get(v.dflt));
                for(LabelNode label:v.labels)text.append(',').append(labels.get(label));
            } else if(n instanceof LookupSwitchInsnNode v) {
                text.append(v.keys).append(',').append(labels.get(v.dflt));
                for(LabelNode label:v.labels)text.append(',').append(labels.get(label));
            } else if(n instanceof MultiANewArrayInsnNode v) text.append(v.desc).append(',').append(v.dims);
            text.append('\n');
        }
        return sha256(text.toString());
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String textHash(MethodNode method) {
        StringBuilder text=new StringBuilder(method.desc);
        for(AbstractInsnNode n:method.instructions) {
            if(n instanceof LdcInsnNode v && v.cst instanceof String)text.append("\nS:").append(v.cst);
            if(n instanceof InvokeDynamicInsnNode v)for(Object arg:v.bsmArgs)if(arg instanceof String)text.append("\nD:").append(arg);
            if(n instanceof MethodInsnNode v && v.owner.startsWith("net/minecraft/") && (v.owner.endsWith("/Component")||v.owner.endsWith("/MutableComponent")||v.owner.endsWith("/GuiGraphics")||v.owner.endsWith("/Font")))
                text.append("\nM:").append(v.owner).append('.').append(v.name).append(v.desc);
        }
        return sha256(text.toString());
    }
}
