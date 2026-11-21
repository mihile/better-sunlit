package dev.sunlit.bettersunlit.inline;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EntityDataOrder {
    static final String WOLF = "net.minecraft.world.entity.animal.Wolf";
    static final String CAT = "net.minecraft.world.entity.animal.Cat";
    private static final String SYNCHER = "net/minecraft/network/syncher/";
    private static final String ACCESSOR = "L" + SYNCHER + "EntityDataAccessor;";
    private static final String SERIALIZER = "L" + SYNCHER + "EntityDataSerializer;";
    private static final String DEFINE_ID = "(Ljava/lang/Class;" + SERIALIZER + ")" + ACCESSOR;
    private static final String BOOLEAN = "f_135035_";
    private static final String INT = "f_135028_";
    private static final String STRING = "f_135030_";
    private static final String CAT_VARIANT = "f_238114_";
    private static final Slot COMMAND = new Slot("COMMAND", INT);
    private static final Slot VARIANT = new Slot("DATA_VARIANT_ID", STRING);
    private static final Slot NATURAL_VARIANT = new Slot("DATA_NATURAL_VARIANT_ID", CAT_VARIANT);
    private static final Slot BEWEREAGER = new Slot("DATA_IS_BEWEREAGER", BOOLEAN);
    private static final Slot CURED_BEWEREAGER = new Slot("DATA_IS_CURED_BEWEREAGER", BOOLEAN);

    private static final Layout WOLF_LAYOUT = new Layout(
            List.of(new Slot("f_30358_", BOOLEAN), new Slot("f_30359_", INT), new Slot("f_30360_", INT)),
            List.of(new Slot("DATA_SOUND_VARIANT_ID", STRING), VARIANT, COMMAND, BEWEREAGER, CURED_BEWEREAGER));
    private static final Layout CAT_LAYOUT = new Layout(
            List.of(new Slot("f_218131_", CAT_VARIANT), new Slot("f_28105_", BOOLEAN),
                    new Slot("f_28106_", BOOLEAN), new Slot("f_28107_", INT)),
            List.of(VARIANT, COMMAND, NATURAL_VARIANT));

    private EntityDataOrder() {}

    private record Slot(String field, String serializer) {}
    private record Layout(List<Slot> prefix, List<Slot> server) {}
    private record Registration(int instructionIndex, FieldInsnNode field, FieldInsnNode serializer) {
        Slot slot() {
            return new Slot(field.name, serializer.name);
        }
    }

    static boolean matchServerOrder(ClassNode target) {
        Layout layout = switch (target.name.replace('/', '.')) {
            case WOLF -> WOLF_LAYOUT;
            case CAT -> CAT_LAYOUT;
            default -> null;
        };
        if (layout == null) return false;
        for (var method : target.methods) {
            if (!"<clinit>".equals(method.name) || !method.tryCatchBlocks.isEmpty()) continue;
            List<AbstractInsnNode> code = Arrays.stream(method.instructions.toArray())
                    .filter(node -> node.getOpcode() >= 0).toList();
            if (code.stream().anyMatch(node -> node instanceof JumpInsnNode
                    || node.getOpcode() == Opcodes.TABLESWITCH || node.getOpcode() == Opcodes.LOOKUPSWITCH)) {
                return false;
            }
            List<Registration> registrations = new ArrayList<>();
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof MethodInsnNode call)
                        || !(SYNCHER + "SynchedEntityData").equals(call.owner)
                        || !"m_135353_".equals(call.name)) continue;
                if (i < 2 || i + 1 >= code.size() || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !DEFINE_ID.equals(call.desc)
                        || !(code.get(i - 2) instanceof LdcInsnNode literal)
                        || !Type.getObjectType(target.name).equals(literal.cst)
                        || !(code.get(i - 1) instanceof FieldInsnNode serializer)
                        || serializer.getOpcode() != Opcodes.GETSTATIC
                        || !(SYNCHER + "EntityDataSerializers").equals(serializer.owner)
                        || !SERIALIZER.equals(serializer.desc)
                        || !(code.get(i + 1) instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTSTATIC
                        || !target.name.equals(field.owner) || !ACCESSOR.equals(field.desc)) return false;
                registrations.add(new Registration(i, field, serializer));
            }

            List<Slot> slots = registrations.stream().map(Registration::slot).toList();
            int prefixCount = layout.prefix.size();
            if (slots.size() != prefixCount + layout.server.size()
                    || !slots.subList(0, prefixCount).equals(layout.prefix)) return false;
            List<Slot> modSlots = slots.subList(prefixCount, slots.size());
            if (modSlots.equals(layout.server)
                    || !new HashSet<>(modSlots).equals(Set.copyOf(layout.server))) return false;
            for (int i = prefixCount + 1; i < registrations.size(); i++) {
                if (registrations.get(i).instructionIndex != registrations.get(i - 1).instructionIndex + 4) {
                    return false;
                }
            }

            // Each four-instruction registration has the same stack shape. Move the
            // serializer and its destination together so all getters retain their field.
            for (int i = 0; i < layout.server.size(); i++) {
                Slot slot = layout.server.get(i);
                Registration registration = registrations.get(prefixCount + i);
                registration.field.name = slot.field;
                registration.serializer.name = slot.serializer;
            }
            return true;
        }
        return false;
    }
}
