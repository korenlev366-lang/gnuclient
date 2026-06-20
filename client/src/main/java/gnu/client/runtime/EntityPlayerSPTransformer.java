package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code EntityPlayerSP.onUpdate} HEAD — raven {@code MixinEntityPlayerSP.onUpdatePre}.
 */
public final class EntityPlayerSPTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/PlayerUpdateHook";
    private static final String HOOK_METHOD = "onUpdateHead";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)Z";

    private static final String[] CLASS_NAMES = {
            "net/minecraft/client/entity/EntityPlayerSP",
            "bli", // Notch 1.8.9 (MCP stable_22)
    };

    private EntityPlayerSPTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null || !isEntityPlayerSp(className))
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
                    if (mv == null || !isOnUpdate(name, desc))
                        return mv;
                    return injectHeadCancel(mv, patched);
                }
            };

            reader.accept(visitor, 0);
            if (!patched[0]) {
                GnuLog.log("JAVA_ EntityPlayerSPTransformer: no onUpdate patch for " + className);
                return null;
            }
            GnuLog.log("JAVA_ EntityPlayerSPTransformer: patched " + className + ".onUpdate");
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ EntityPlayerSPTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static MethodVisitor injectHeadCancel(MethodVisitor mv, boolean[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            private boolean done;

            @Override
            public void visitCode() {
                super.visitCode();
                if (done)
                    return;
                Label cont = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, HOOK_DESC, false);
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(cont);
                done = true;
                patched[0] = true;
            }
        };
    }

    private static boolean isEntityPlayerSp(String name) {
        if (name == null)
            return false;
        for (String candidate : CLASS_NAMES) {
            if (candidate.equals(name))
                return true;
        }
        return false;
    }

    private static boolean isOnUpdate(String name, String desc) {
        if (!"()V".equals(desc))
            return false;
        return "onUpdate".equals(name) || "func_70071_h_".equals(name);
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
