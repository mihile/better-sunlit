package dev.sunlit.bettersunlit.tests.perf;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Builds standalone fixtures from the installed classes, without launching Minecraft. */
public final class NativeMipmapFixture {
    private static ClassNode read(String name) throws Exception {
        var node = new ClassNode();
        try (var in = NativeMipmapFixture.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) throw new IllegalStateException("Missing " + name);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }
    private static void write(Path root, ClassNode node) throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path path = root.resolve(node.name + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, writer.toByteArray());
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        ClassNode image = read("com/mojang/blaze3d/platform/NativeImage");
        image.interfaces.add("dev/sunlit/fishvisuals/mixin/PerfNativeImageAccessor");
        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, "sunlit$getPixels", "()J", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, image.name, "f_84964_", "J"));
        getter.instructions.add(new InsnNode(Opcodes.LRETURN));
        image.methods.add(getter);
        image.interfaces.add("me/jellysquid/mods/sodium/mixin/features/textures/NativeImageAccessor");
        MethodNode sodiumGetter = new MethodNode(Opcodes.ACC_PUBLIC, "getPointer", "()J", null, null);
        sodiumGetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        sodiumGetter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, image.name, "f_84964_", "J"));
        sodiumGetter.instructions.add(new InsnNode(Opcodes.LRETURN));
        image.methods.add(sodiumGetter);
        write(output, image);

        // Preserve Forge's exact pure sizing method without bootstrapping the entire client.
        ClassNode forge = read("net/minecraftforge/client/ForgeHooksClient");
        ClassNode sizing = new ClassNode();
        sizing.version = forge.version;
        sizing.access = Opcodes.ACC_PUBLIC;
        sizing.name = forge.name;
        sizing.superName = "java/lang/Object";
        MethodNode sizeMethod = forge.methods.stream()
                .filter(m -> m.name.equals("getMaxMipmapLevel") && m.desc.equals("(II)I"))
                .findFirst().orElseThrow();
        sizing.methods.add(sizeMethod);
        write(output, sizing);

        ClassNode generator = read("net/minecraft/client/renderer/texture/MipmapGenerator");
        ClassNode iris = read("net/irisshaders/iris/mixin/bettermipmaps/MixinMipmapGenerator");
        generator.methods.removeIf(m -> m.name.equals("m_118048_") && m.desc.equals("(IIIIZ)I"));
        for (MethodNode method : iris.methods) {
            if (!method.name.equals("m_118048_") && !method.name.equals("weightedAverageColor")
                    && !method.name.equals("averageRgb")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(iris.name)) call.owner = generator.name;
            }
            generator.methods.add(method);
        }
        write(output, generator);
    }
}
