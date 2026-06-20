package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * JVMTI ClassFileLoadHook delegates here to inject packet intercept calls into
 * {@code NetworkManager.sendPacket} and {@code NetworkManager.channelRead0}.
 */
public final class PacketTransformer {

    private static final String EVENTS_OWNER = "gnu/client/runtime/packet/PacketEvents";
    private static final String ON_SEND = "onSend";
    private static final String ON_SEND_DESC = "(Ljava/lang/Object;)Z";
    private static final String HOOK_READ = "hookChannelRead";
    private static final String HOOK_READ_DESC = "(Ljava/lang/Object;)Z";

    private PacketTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null)
            return null;
        if (!isNetworkManager(className))
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
                    if (mv == null)
                        return null;

                    if (isSendPacket(name, desc))
                        return injectCancelOnTrue(mv, patched, 1, ON_SEND, ON_SEND_DESC);
                    if (isChannelRead0(name, desc))
                        return injectCancelOnTrue(mv, patched, 2, HOOK_READ, HOOK_READ_DESC);
                    return mv;
                }
            };

            reader.accept(visitor, 0);
            if (!patched[0]) {
                GnuLog.log("JAVA_ PacketTransformer: no patch for " + className);
                return null;
            }
            GnuLog.log("JAVA_ PacketTransformer: patched " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ PacketTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    private static MethodVisitor injectCancelOnTrue(MethodVisitor mv, boolean[] patched, int argIndex,
                                                    String method, String desc) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            private boolean done;

            @Override
            public void visitCode() {
                super.visitCode();
                if (done)
                    return;
                Label cont = new Label();
                super.visitVarInsn(Opcodes.ALOAD, argIndex);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, EVENTS_OWNER, method, desc, false);
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(cont);
                done = true;
                patched[0] = true;
            }
        };
    }

    private static boolean isNetworkManager(String name) {
        return "net/minecraft/network/NetworkManager".equals(name)
                || "ek".equals(name);
    }

    private static boolean isSendPacket(String name, String desc) {
        return desc != null && desc.contains("network/Packet") && desc.endsWith(")V")
                && ("sendPacket".equals(name) || "func_179290_a".equals(name) || "a".equals(name));
    }

    private static boolean isChannelRead0(String name, String desc) {
        return "channelRead0".equals(name)
                && desc != null && desc.contains("ChannelHandlerContext") && desc.contains("Object");
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
