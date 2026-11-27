package dev.sunlit.bettersunlit.client;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Generates a lightweight {@code IValue} proxy via ASM without a compile-time dependency on
 * jarjar-nested {@code mclib}, deferring {@code BytecodeGen.compile(CompiledAst)} from asset
 * reload to first runtime evaluation.
 */
public final class GbfMolangPerf {
    private static final String IVALUE_INTERNAL = "com/eliotlash/mclib/math/IValue";
    private static final String IVALUE_DESC = "L" + IVALUE_INTERNAL + ";";
    private static final String WRAPPER_INTERNAL = "dev/sunlit/bettersunlit/client/LazyMolangValue";
    private static final String SELF_INTERNAL = "dev/sunlit/bettersunlit/client/GbfMolangPerf";
    private static volatile MethodHandle factoryHandle;
    private static volatile MethodHandle compileHandle;
    private static volatile boolean compileHandleInit;

    private GbfMolangPerf() {
    }

    public static Object compileAst(Object compiledAst) throws Throwable {
        MethodHandle h = compileHandle;
        if (h == null && !compileHandleInit) {
            synchronized (GbfMolangPerf.class) {
                if (!compileHandleInit) {
                    try {
                        ClassLoader cl = compiledAst.getClass().getClassLoader();
                        Class<?> bytecodeGen = Class.forName("com.moepus.gbf.molang.compiler.BytecodeGen", false, cl);
                        Class<?> astClass = Class.forName("com.moepus.gbf.molang.compiler.CompiledAst", false, cl);
                        java.lang.reflect.Method m = bytecodeGen.getDeclaredMethod("compile", astClass);
                        m.setAccessible(true);
                        compileHandle = MethodHandles.lookup()
                                .unreflect(m)
                                .asType(MethodType.methodType(Object.class, Object.class));
                    } catch (Throwable ignored) {
                        compileHandle = null;
                    } finally {
                        compileHandleInit = true;
                    }
                }
                h = compileHandle;
            }
        }
        if (h == null) {
            return null;
        }
        return h.invokeExact(compiledAst);
    }

    public static Object wrapLazy(Object compiledAst, Object fallback) {
        if (compiledAst == null) {
            return fallback;
        }
        try {
            MethodHandle factory = getOrCreateFactory();
            if (factory != null) {
                return factory.invokeExact(compiledAst, fallback);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static MethodHandle getOrCreateFactory() {
        MethodHandle h = factoryHandle;
        if (h != null) {
            return h;
        }
        synchronized (GbfMolangPerf.class) {
            if (factoryHandle != null) {
                return factoryHandle;
            }
            try {
                byte[] bytecode = generateWrapperBytes();
                Class<?> wrapperClass = MethodHandles.lookup().defineClass(bytecode);
                MethodHandle ctor = MethodHandles.lookup().findConstructor(
                        wrapperClass, MethodType.methodType(void.class, Object.class, Object.class));
                factoryHandle = ctor.asType(MethodType.methodType(Object.class, Object.class, Object.class));
                return factoryHandle;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static byte[] generateWrapperBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return GbfMolangPerf.class.getClassLoader();
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                WRAPPER_INTERNAL, null, "java/lang/Object", new String[]{IVALUE_INTERNAL});

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "delegate", IVALUE_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "ast", "Ljava/lang/Object;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "fallback", "Ljava/lang/Object;", null, null).visitEnd();

        // <init>(Object ast, Object fallback)
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER_INTERNAL, "ast", "Ljava/lang/Object;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER_INTERNAL, "fallback", "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // public double get()
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()D", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, WRAPPER_INTERNAL, "delegate", IVALUE_DESC);
        get.visitVarInsn(Opcodes.ASTORE, 1);
        get.visitVarInsn(Opcodes.ALOAD, 1);
        Label callDelegate = new Label();
        get.visitJumpInsn(Opcodes.IFNONNULL, callDelegate);

        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, WRAPPER_INTERNAL, "resolve", "()" + IVALUE_DESC, false);
        get.visitVarInsn(Opcodes.ASTORE, 1);

        get.visitLabel(callDelegate);
        get.visitVarInsn(Opcodes.ALOAD, 1);
        get.visitMethodInsn(Opcodes.INVOKEINTERFACE, IVALUE_INTERNAL, "get", "()D", true);
        get.visitInsn(Opcodes.DRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        // private synchronized IValue resolve()
        MethodVisitor res = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNCHRONIZED, "resolve",
                "()" + IVALUE_DESC, null, null);
        res.visitCode();
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitFieldInsn(Opcodes.GETFIELD, WRAPPER_INTERNAL, "delegate", IVALUE_DESC);
        res.visitVarInsn(Opcodes.ASTORE, 1);
        res.visitVarInsn(Opcodes.ALOAD, 1);
        Label alreadyDone = new Label();
        res.visitJumpInsn(Opcodes.IFNONNULL, alreadyDone);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        res.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

        res.visitLabel(tryStart);
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitFieldInsn(Opcodes.GETFIELD, WRAPPER_INTERNAL, "ast", "Ljava/lang/Object;");
        res.visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "compileAst",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        res.visitTypeInsn(Opcodes.CHECKCAST, IVALUE_INTERNAL);
        res.visitVarInsn(Opcodes.ASTORE, 1);
        res.visitVarInsn(Opcodes.ALOAD, 1);
        Label clearAndStore = new Label();
        res.visitJumpInsn(Opcodes.IFNONNULL, clearAndStore);
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitFieldInsn(Opcodes.GETFIELD, WRAPPER_INTERNAL, "fallback", "Ljava/lang/Object;");
        res.visitTypeInsn(Opcodes.CHECKCAST, IVALUE_INTERNAL);
        res.visitVarInsn(Opcodes.ASTORE, 1);
        res.visitLabel(tryEnd);
        res.visitJumpInsn(Opcodes.GOTO, clearAndStore);

        res.visitLabel(catchHandler);
        res.visitInsn(Opcodes.POP);
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitFieldInsn(Opcodes.GETFIELD, WRAPPER_INTERNAL, "fallback", "Ljava/lang/Object;");
        res.visitTypeInsn(Opcodes.CHECKCAST, IVALUE_INTERNAL);
        res.visitVarInsn(Opcodes.ASTORE, 1);

        res.visitLabel(clearAndStore);
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitInsn(Opcodes.ACONST_NULL);
        res.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER_INTERNAL, "ast", "Ljava/lang/Object;");
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitInsn(Opcodes.ACONST_NULL);
        res.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER_INTERNAL, "fallback", "Ljava/lang/Object;");
        res.visitVarInsn(Opcodes.ALOAD, 0);
        res.visitVarInsn(Opcodes.ALOAD, 1);
        res.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER_INTERNAL, "delegate", IVALUE_DESC);

        res.visitLabel(alreadyDone);
        res.visitVarInsn(Opcodes.ALOAD, 1);
        res.visitInsn(Opcodes.ARETURN);
        res.visitMaxs(0, 0);
        res.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
