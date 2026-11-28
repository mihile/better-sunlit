package dev.sunlit.bettersunlit.tests.perf;

import dev.sunlit.bettersunlit.inline.EntryMixinPlugin;
import dev.sunlit.bettersunlit.client.MinecartDataCompatibility;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData.DataItem;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Uses the installed Wolf and mod initializers in the order recorded by Mixin. */
public final class EntityDataOrderTest {
    private static final String WOLF = "net.minecraft.world.entity.animal.Wolf";
    private static final String CAT = "net.minecraft.world.entity.animal.Cat";
    private static final String MIXIN = "dev.sunlit.fishvisuals.mixin.EntityDataOrderMixin";
    private static final String BACKPORT = "com.blackgear.vanillabackport.core.mixin.common.entities.";
    private static final String SPECIES = "com.ninni.species.mixin.WolfMixin";
    private static final String DOMESTICATION = "com.github.alexthe668.domesticationinnovation.mixin.";
    private static final String CRITTERS = "com.github.eterdelta.crittersandcompanions.mixin.NeutralMobsMixin";
    private static final String COPYCATS = "com.copycatsplus.copycats.mixin.entity.CatMixin";

    public static void main(String[] args) throws IOException {
        var plugin = new EntryMixinPlugin();
        verifyWolf(plugin);
        verifyCat(plugin);
        verifyMinecarts(plugin);
    }

    private static void verifyWolf(EntryMixinPlugin plugin) throws IOException {
        if (!plugin.canApplyGate(WOLF, MIXIN)) {
            System.out.println("SKIP: installed Wolf compatibility binaries differ from the reviewed versions");
            return;
        }
        String backport = BACKPORT + "WolfMixin";
        String command = DOMESTICATION + "WolfMixin";
        ClassNode server = merge(WOLF, backport, CRITTERS, command, SPECIES);
        ClassNode client = merge(WOLF, backport, SPECIES, CRITTERS, command);
        List<Slot> expected = slots(server);
        List<Slot> original = slots(client);
        check(expected.size() == 8 && original.size() == 8, "eight Wolf registrations");
        check(expected.get(4).field.equals("DATA_VARIANT_ID"), "coat variant is slot 24");
        check(expected.get(5).field.equals("COMMAND"), "server command is slot 25");
        check(original.get(5).field.equals("DATA_IS_BEWEREAGER"), "client boolean is slot 25");

        // Vanilla builds the initial metadata packet by iterating this map. In
        // the reported case COMMAND aborts application before the coat variant.
        var tracker = new Int2ObjectOpenHashMap<String>();
        for (int id = 0; id <= 27; id++) tracker.put(id, "");
        var wireOrder = new ArrayList<>(tracker.keySet());
        check(wireOrder.indexOf(25) < wireOrder.indexOf(24), "command precedes the coat in initial metadata");

        assertAligned(plugin, WOLF, client, expected);
        assertAligned(plugin, WOLF, merge(WOLF, SPECIES, command, backport), expected);
        assertAligned(plugin, WOLF, merge(WOLF, SPECIES, command), slots(merge(WOLF, command, SPECIES)));
        plugin.postApply(WOLF, server, MIXIN, null);
        check(slots(server).equals(expected), "server order is left intact");
        plugin.postApply(WOLF, client, MIXIN, null);
        check(slots(client).equals(expected), "alignment is idempotent");

        ClassNode unsupported = merge(WOLF, backport, SPECIES, CRITTERS, command);
        for (var instruction : initializer(unsupported).instructions) {
            if (instruction instanceof FieldInsnNode field && "DATA_IS_CURED_BEWEREAGER".equals(field.name)) {
                initializer(unsupported).instructions.insert(field, new InsnNode(Opcodes.NOP));
                break;
            }
        }
        plugin.postApply(WOLF, unsupported, MIXIN, null);
        check(slots(unsupported).equals(original), "unknown initializer layouts are left intact");
        System.out.println("PASS: real Wolf registrations align with server, preserving coat, command and Species flags");
    }

    private static void verifyCat(EntryMixinPlugin plugin) throws IOException {
        if (!plugin.canApplyGate(CAT, MIXIN)) {
            System.out.println("SKIP: installed Cat compatibility binaries differ from the reviewed versions");
            return;
        }
        String backport = BACKPORT + "CatMixin";
        String command = DOMESTICATION + "CatMixin";
        List<Slot> expected = slots(merge(CAT, backport, command, COPYCATS));
        check(expected.size() == 7, "seven Cat registrations");
        check(expected.subList(4, 7).equals(List.of(
                new Slot("DATA_VARIANT_ID", "f_135030_"),
                new Slot("COMMAND", "f_135028_"),
                new Slot("DATA_NATURAL_VARIANT_ID", "f_238114_"))), "server Cat wire order");

        ClassNode client = merge(CAT, COPYCATS, backport, command);
        List<Slot> original = slots(client);
        check(!original.get(4).serializer.equals(expected.get(4).serializer), "Cat rejects the server coat slot before alignment");
        assertAligned(plugin, CAT, client, expected);
        assertAligned(plugin, CAT, merge(CAT, backport, COPYCATS, command), expected);
        assertAligned(plugin, CAT, merge(CAT, backport, command, COPYCATS), expected);

        assertAligned(plugin, CAT, merge(CAT, COPYCATS, command), slots(merge(CAT, command, COPYCATS)));
        System.out.println("PASS: real Cat registrations align with server, preserving both variants and command");
    }

    private static void assertAligned(EntryMixinPlugin plugin, String name, ClassNode target, List<Slot> expected) {
        plugin.postApply(name, target, MIXIN, null);
        check(slots(target).equals(expected), name + " fields and serializers match the server");
    }

    private static void verifyMinecarts(EntryMixinPlugin plugin) throws IOException {
        String target = "net.minecraft.network.syncher.SynchedEntityData";
        if (!plugin.canApplyGate(target, "dev.sunlit.fishvisuals.mixin.MinecartDataCompatibilityMixin")) {
            System.out.println("SKIP: installed More Minecarts compatibility binaries differ from the reviewed versions");
            return;
        }
        String tank = "com.alc.moreminecarts.entities.TankCartEntity";
        String flag = "com.alc.moreminecarts.entities.FlagCartEntity";
        check(hasClassLiteral(initializer(read(tank)), flag), "Tank registers against Flag");
        check(slots(read(tank)).equals(List.of(new Slot("FLUID_TAG", "f_135042_"))), "Tank uses the NBT serializer");
        check(slots(read(flag)).equals(List.of(new Slot("DISPLAY_TYPE", "f_135028_"))), "Flag uses the integer serializer");
        check(slots(read("net.minecraft.world.entity.Entity")).size() == 8, "eight base entity slots");
        check(slots(read("net.minecraft.world.entity.vehicle.AbstractMinecart")).size() == 6, "six base cart slots");

        CompoundTag fluid = new CompoundTag();
        fluid.m_128359_("FluidName", "minecraft:water");
        checkCart(tank, fixtureSerializer(fluid), fluid);
        checkCart(flag, fixtureSerializer(3), 3);
        System.out.println("PASS: both More Minecarts registration orders preserve fluid and flag metadata");
    }

    private static boolean hasClassLiteral(MethodNode method, String className) {
        var type = org.objectweb.asm.Type.getObjectType(className.replace('.', '/'));
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode literal && type.equals(literal.cst)) return true;
        }
        return false;
    }

    private static <T> void checkCart(String entity, EntityDataSerializer<T> serializer, T value) {
        for (int local : List.of(14, 15)) {
            int remote = local == 14 ? 15 : 14;
            var items = new Int2ObjectOpenHashMap<DataItem<?>>();
            items.put(local, new DataItem<>(serializer.m_135021_(local), value));
            var oldPacket = List.<DataValue<?>>of(new DataValue<>(remote, serializer, value));
            var correctPacket = List.<DataValue<?>>of(new DataValue<>(local, serializer, value));
            check(MinecartDataCompatibility.align(serializer, items, oldPacket).equals(correctPacket),
                    "remaps the other registration order for " + entity);
            check(MinecartDataCompatibility.align(serializer, items, correctPacket) == correctPacket,
                    "already correct packet travels untouched");
            var wrongType = List.<DataValue<?>>of(new DataValue<>(remote, fixtureSerializer(true), true));
            check(MinecartDataCompatibility.align(serializer, items, wrongType) == wrongType, "wrong serializer is not remapped");
            items.put(remote, new DataItem<>(serializer.m_135021_(remote), value));
            check(MinecartDataCompatibility.align(serializer, items, oldPacket) == oldPacket, "occupied slot is not remapped");
        }
    }

    private static <T> EntityDataSerializer<T> fixtureSerializer(T value) {
        // Only serializer identity matters here. The real class requires registries.
        return EntityDataSerializer.m_238095_((buffer, input) -> {}, buffer -> value);
    }

    private static ClassNode merge(String targetName, String... mixins) throws IOException {
        ClassNode entity = read(targetName);
        MethodNode target = initializer(entity);
        var end = target.instructions.getLast();
        check(end.getOpcode() == Opcodes.RETURN, targetName + " initializer ends with RETURN");
        for (String mixin : mixins) {
            ClassNode node = read(mixin);
            for (MethodNode method : node.methods) {
                if (!method.name.equals("<clinit>")) continue;
                // Mixin 0.8.5 appends each initializer before the target return and
                // remaps self references. These initializers have no control flow.
                for (var instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() < 0 || instruction.getOpcode() == Opcodes.RETURN) continue;
                    var copy = instruction.clone(null);
                    if (copy instanceof FieldInsnNode field && field.owner.equals(node.name)) {
                        field.owner = entity.name;
                    }
                    target.instructions.insertBefore(end, copy);
                }
            }
        }
        return entity;
    }

    private static List<Slot> slots(ClassNode node) {
        var result = new ArrayList<Slot>();
        FieldInsnNode serializer = null;
        for (var instruction : initializer(node).instructions) {
            if (!(instruction instanceof FieldInsnNode field)) continue;
            if (field.owner.endsWith("/EntityDataSerializers") && field.getOpcode() == Opcodes.GETSTATIC) {
                serializer = field;
            } else if (field.desc.endsWith("/EntityDataAccessor;") && field.getOpcode() == Opcodes.PUTSTATIC) {
                check(serializer != null, "each registered field has a serializer");
                result.add(new Slot(field.name, serializer.name));
                serializer = null;
            }
        }
        return result;
    }

    private static MethodNode initializer(ClassNode node) {
        return node.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
    }

    private static ClassNode read(String name) throws IOException {
        try (var in = EntityDataOrderTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new IOException("Missing installed class " + name);
            var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Slot(String field, String serializer) {}
}
