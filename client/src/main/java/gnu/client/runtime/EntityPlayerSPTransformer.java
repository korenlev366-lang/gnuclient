package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Patches {@code EntityPlayerSP.onUpdate} with the OpenMyau-style silent
 * rotation lifecycle.
 */
public final class EntityPlayerSPTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/PlayerUpdateHook";
    private static final String MOVE_FIX_OWNER = "gnu/client/runtime/MoveFixHook";
    private static final String HOOK_HEAD = "onUpdateHead";
    private static final String HOOK_BEFORE_WALKING = "beforeWalkingPlayer";
    private static final String HOOK_AFTER_WALKING = "onAfterWalkingPlayer";
    private static final String HOOK_RETURN = "onUpdateReturn";
    private static final String MOVE_FIX_BEFORE = "beforeLivingUpdate";
    private static final String MOVE_FIX_AFTER = "afterLivingUpdate";
    private static final String HOOK_DESC = "(Ljava/lang/Object;)Z";
    private static final String HOOK_VOID_DESC = "(Ljava/lang/Object;)V";

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
            boolean[] patchedHead = new boolean[] { false };
            boolean[] patchedBeforeWalking = new boolean[] { false };
            boolean[] patchedAfterWalking = new boolean[] { false };
            boolean[] patchedReturn = new boolean[] { false };
            boolean[] patchedLivingUpdate = new boolean[] { false };

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (mv == null)
                        return null;
                    if (isOnUpdate(name, desc))
                        return patchOnUpdate(mv, patchedHead, patchedBeforeWalking, patchedAfterWalking,
                                patchedReturn);
                    if (isOnLivingUpdate(name, desc))
                        return patchOnLivingUpdate(mv, patchedLivingUpdate);
                    return mv;
                }
            };

            reader.accept(visitor, 0);
            if (!patchedHead[0] || !patchedReturn[0]) {
                GnuLog.log("JAVA_ EntityPlayerSPTransformer: incomplete onUpdate patch for " + className
                        + " head=" + patchedHead[0]
                        + " beforeWalking=" + patchedBeforeWalking[0]
                        + " afterWalking=" + patchedAfterWalking[0]
                        + " return=" + patchedReturn[0]
                        + " livingUpdate=" + patchedLivingUpdate[0]);
                return null;
            }
            GnuLog.log("JAVA_ EntityPlayerSPTransformer: patched " + className
                    + " onUpdate beforeWalking=" + patchedBeforeWalking[0]
                    + " afterWalking=" + patchedAfterWalking[0]
                    + " onLivingUpdate movefix=" + patchedLivingUpdate[0]);
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ EntityPlayerSPTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static MethodVisitor patchOnUpdate(MethodVisitor mv, boolean[] patchedHead,
                                               boolean[] patchedBeforeWalking,
                                               boolean[] patchedAfterWalking,
                                               boolean[] patchedReturn) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            private boolean headDone;

            @Override
            public void visitCode() {
                super.visitCode();
                if (headDone)
                    return;
                Label cont = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_HEAD, HOOK_DESC, false);
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(cont);
                headDone = true;
                patchedHead[0] = true;
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL && "()V".equals(desc) && isOnUpdateWalkingPlayer(name)) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_BEFORE_WALKING,
                            HOOK_VOID_DESC, false);
                    patchedBeforeWalking[0] = true;
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_AFTER_WALKING,
                            HOOK_VOID_DESC, false);
                    patchedAfterWalking[0] = true;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_RETURN,
                            HOOK_VOID_DESC, false);
                    patchedReturn[0] = true;
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor patchOnLivingUpdate(MethodVisitor mv, boolean[] patchedLivingUpdate) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (opcode == Opcodes.INVOKESPECIAL && "()V".equals(desc) && isOnLivingUpdate(name)) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, MOVE_FIX_OWNER, MOVE_FIX_BEFORE,
                            HOOK_VOID_DESC, false);
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, MOVE_FIX_OWNER, MOVE_FIX_AFTER,
                            HOOK_VOID_DESC, false);
                    patchedLivingUpdate[0] = true;
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        };
    }

    private static boolean isOnLivingUpdate(String name) {
        return "onLivingUpdate".equals(name) || "func_70636_d".equals(name);
    }

    private static boolean isOnLivingUpdate(String name, String desc) {
        return "()V".equals(desc) && isOnLivingUpdate(name);
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

    private static boolean isOnUpdateWalkingPlayer(String name) {
        return "onUpdateWalkingPlayer".equals(name) || "func_175161_p".equals(name);
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
