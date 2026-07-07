package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code EntityLivingBase.moveEntityWithHeading} to route {@code moveFlying}
 * through {@link EntityLivingBaseHook} (OpenMyau {@code MixinEntityLivingBase} parity).
 */
public final class EntityLivingBaseTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/EntityLivingBaseHook";
    private static final String HOOK_METHOD = "moveFlying";
    private static final String HOOK_DESC = "(Ljava/lang/Object;FFF)V";

    private static final String[] CLASS_NAMES = {
            "net/minecraft/entity/EntityLivingBase",
            "pr", // Notch 1.8.9 (MCP stable_22)
    };

    private EntityLivingBaseTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null || !isEntityLivingBase(className))
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
                    // 1.8.9 uses moveEntityWithHeading(FF)V — not the 1.9+ (FFF)V signature.
                    if (mv == null || !"(FF)V".equals(desc) || !isMoveEntityWithHeading(name))
                        return mv;
                    return new MoveFlyingRedirectVisitor(mv, patched);
                }
            };

            reader.accept(visitor, 0);
            if (!patched[0]) {
                GnuLog.log("JAVA_ EntityLivingBaseTransformer: no moveFlying redirect for " + className);
                return null;
            }
            GnuLog.log("JAVA_ EntityLivingBaseTransformer: patched " + className
                    + " moveEntityWithHeading -> EntityLivingBaseHook.moveFlying");
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ EntityLivingBaseTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static final class MoveFlyingRedirectVisitor extends MethodVisitor {
        private final boolean[] patched;

        MoveFlyingRedirectVisitor(MethodVisitor mv, boolean[] patched) {
            super(Opcodes.ASM9, mv);
            this.patched = patched;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && "(FFF)V".equals(desc) && isMoveFlying(name)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, HOOK_DESC, false);
                patched[0] = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    private static boolean isEntityLivingBase(String name) {
        if (name == null)
            return false;
        for (String candidate : CLASS_NAMES) {
            if (candidate.equals(name))
                return true;
        }
        return false;
    }

    private static boolean isMoveEntityWithHeading(String name) {
        return "moveEntityWithHeading".equals(name) || "func_70612_e".equals(name);
    }

    private static boolean isMoveFlying(String name) {
        return "moveFlying".equals(name) || "func_70060_a".equals(name);
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
