package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code GuiIngame.updateTick} so scaffold item-spoof can redirect
 * {@code InventoryPlayer.getCurrentItem()} to the pre-scaffold hotbar slot.
 */
public final class GuiIngameTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/ScaffoldItemSpoofHook";
    private static final String HOOK_METHOD = "redirectCurrentItem";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private static final String INVENTORY_OWNER = "net/minecraft/entity/player/InventoryPlayer";
    private static final String METHOD_GET_CURRENT_ITEM = "func_70448_g";
    private static final String METHOD_UPDATE_TICK = "func_73829_a";

    private static final String[] CLASS_NAMES = {
            "net/minecraft/client/gui/GuiIngame",
            "avo", // Notch 1.8.9 fallback
    };

    private GuiIngameTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null || !isGuiIngame(className))
            return null;
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            boolean[] patched = new boolean[] { false };

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (mv == null || !isUpdateTick(name, desc))
                        return mv;
                    return redirectGetCurrentItem(mv, patched);
                }
            };

            reader.accept(visitor, 0);
            if (!patched[0]) {
                GnuLog.log("JAVA_ GuiIngameTransformer: no patch for " + className);
                return null;
            }
            GnuLog.log("JAVA_ GuiIngameTransformer: patched " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ GuiIngameTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static MethodVisitor redirectGetCurrentItem(MethodVisitor mv, boolean[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && INVENTORY_OWNER.equals(owner)
                        && METHOD_GET_CURRENT_ITEM.equals(name)
                        && "()Lnet/minecraft/item/ItemStack;".equals(desc)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, HOOK_DESC, false);
                    patched[0] = true;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        };
    }

    private static boolean isGuiIngame(String name) {
        if (name == null)
            return false;
        for (String candidate : CLASS_NAMES) {
            if (candidate.equals(name))
                return true;
        }
        return false;
    }

    private static boolean isUpdateTick(String name, String desc) {
        return "()V".equals(desc)
                && (METHOD_UPDATE_TICK.equals(name) || "updateTick".equals(name));
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
