package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * JVMTI calls this transformer during RetransformClasses for Minecraft's movement
 * input class. Keeping the bytecode edit in Java lets us use ASM while the native
 * agent owns only the JVMTI lifecycle.
 */
public final class MovementInputTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/MovementInputHook";
    private static final String PATCH_METHOD = "afterUpdatePlayerMoveState";
    private static final String PATCH_DESC = "(Ljava/lang/Object;)V";

    private MovementInputTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null)
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
                    if (mv == null || !"()V".equals(desc) || !isUpdatePlayerMoveState(name))
                        return mv;

                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private boolean alreadyPatched;

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc,
                                                    boolean itf) {
                            if (opcode == Opcodes.INVOKESTATIC && HOOK_OWNER.equals(owner)
                                    && PATCH_METHOD.equals(methodName) && PATCH_DESC.equals(methodDesc)) {
                                alreadyPatched = true;
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN && !alreadyPatched) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, PATCH_METHOD, PATCH_DESC,
                                        false);
                                patched[0] = true;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            };

            reader.accept(visitor, 0);
            if (!patched[0]) {
                GnuLog.log("JAVA_ MovementInputTransformer: no patch for " + className
                        + " (method not found or already patched)");
                return null;
            }
            GnuLog.log("JAVA_ MovementInputTransformer: patched " + className
                    + " using MovementInputHook.afterUpdatePlayerMoveState");
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ MovementInputTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static boolean isUpdatePlayerMoveState(String name) {
        return "updatePlayerMoveState".equals(name) || "func_78898_a".equals(name) || "a".equals(name);
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // The transformer runs inside LaunchClassLoader during retransformation;
            // loading arbitrary MC classes here can recurse or fail. This patch only
            // inserts straight-line code before RETURN, so Object is a safe conservative
            // frame merge answer.
            return "java/lang/Object";
        }
    }
}
