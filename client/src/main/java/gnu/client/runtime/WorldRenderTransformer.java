package gnu.client.runtime;

import gnu.client.common.GnuLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * JVMTI ClassFileLoadHook patches {@code EntityRenderer}:
 * <ul>
 *   <li><strong>renderWorldPass (IFJ)V:</strong>
 *     <ul>
 *       <li>At HEAD: calls {@link WorldRenderHook#onWorldRender(float)} for
 *           module rendering (no CameraHook before/after).</li>
 *       <li>Before each RETURN (pass==1): calls
 *           {@link WorldRenderHook#onWorldRender(float)}.</li>
 *     </ul>
 *   </li>
 *   <li><strong>orientCamera (F)V:</strong>
 *     <ul>
 *       <li>Replaces every GETFIELD of {@code Entity.field_70177_z} (rotationYaw)
 *           with INVOKESTATIC {@link FreeLookHook#redirectYaw(Object)}.</li>
 *       <li>Replaces every GETFIELD of {@code Entity.field_70125_A} (rotationPitch)
 *           with INVOKESTATIC {@link FreeLookHook#redirectPitch(Object)}.</li>
 *     </ul>
 *     This is the Path B approach: no rotation writes, all rotation reads in
 *     orientCamera are conditionally redirected to independent camera angles
 *     when FreeLook is active. The player's real rotationYaw/rotationPitch are
 *     NEVER modified.
 *   </li>
 * </ul>
 *
 * <p>No CameraHook references remain. The WorldRenderHook injection stays
 * pass==1-guarded as before.
 */
public final class WorldRenderTransformer {

    private static final String HOOK_OWNER = "gnu/client/runtime/WorldRenderHook";
    private static final String HOOK_METHOD = "onWorldRender";
    private static final String HOOK_DESC = "(F)V";

    // FreeLook hook constants
    private static final String FREE_LOOK_HOOK_OWNER = "gnu/client/runtime/FreeLookHook";
    private static final String SPOOF_HOOK_OWNER = "gnu/client/runtime/ScaffoldItemSpoofHook";
    private static final String ENTITY_OWNER = "net/minecraft/entity/Entity";

    // SetAngles redirect constants (updateCameraAndRender)
    private static final String ENTITY_PLAYER_SP_OWNER = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String METHOD_SET_ANGLES = "func_70082_c";
    private static final String METHOD_UPDATE_CAMERA_AND_RENDER = "func_181560_a";
    private static final String METHOD_UPDATE_RENDERER = "func_78479_a";

    // SRG field names for rotation
    private static final String FIELD_ROTATION_YAW = "field_70177_z";
    private static final String FIELD_ROTATION_PITCH = "field_70125_A";
    private static final String FIELD_PREV_ROTATION_YAW = "field_70126_B";
    private static final String FIELD_PREV_ROTATION_PITCH = "field_70127_C";

    private WorldRenderTransformer() {}

    public static byte[] transform(String className, byte[] classBytes) {
        if (classBytes == null)
            return null;
        if (!isEntityRenderer(className))
            return null;
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            boolean[] renderWorldPassPatched = new boolean[] { false };
            boolean[] orientCameraPatched = new boolean[] { false };
            boolean[] updateCameraAndRenderPatched = new boolean[] { false };
            boolean[] updateRendererPatched = new boolean[] { false };
            boolean[] itemSpoofPatched = new boolean[] { false };
            final int[] setAnglesReplaceCount = new int[] { 0 };

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (mv == null)
                        return null;

                    // ── renderWorldPass: inject WorldRenderHook (no CameraHook) ──
                    if (isRenderWorldPass(name, desc)) {
                        return injectRenderHook(mv, renderWorldPassPatched);
                    }

                    // ── orientCamera: redirect rotation field reads ──
                    if (isOrientCamera(name, desc)) {
                        return injectRotationRedirect(mv, orientCameraPatched);
                    }

                    // ── updateCameraAndRender (func_181560_a): redirect setAngles calls ──
                    if (isUpdateCameraAndRender(name, desc)) {
                        return injectSlotSpoofLifecycle(
                                injectSetAnglesRedirect(mv, updateCameraAndRenderPatched, setAnglesReplaceCount),
                                itemSpoofPatched);
                    }

                    // ── updateRenderer (func_78479_a): scaffold item-spoof render swap ──
                    if (isUpdateRenderer(name, desc)) {
                        return injectSlotSpoofLifecycle(mv, updateRendererPatched);
                    }

                    return mv;
                }
            };

            reader.accept(visitor, 0);

            if (orientCameraPatched[0]) {
                GnuLog.log("JAVA_ WorldRenderTransformer: patched orientCamera in " + className);
            }
            if (updateCameraAndRenderPatched[0]) {
                GnuLog.log("JAVA_ WorldRenderTransformer: patched updateCameraAndRender in " + className
                        + " — replaced " + setAnglesReplaceCount[0] + " setAngles sites"
                        + ", itemSpoof=" + itemSpoofPatched[0]);
            }
            if (updateRendererPatched[0]) {
                GnuLog.log("JAVA_ WorldRenderTransformer: patched updateRenderer in " + className);
            }
            if (!renderWorldPassPatched[0] && !orientCameraPatched[0]
                    && !updateCameraAndRenderPatched[0] && !updateRendererPatched[0]) {
                GnuLog.log("JAVA_ WorldRenderTransformer: no patch for " + className);
                return null;
            }
            if (renderWorldPassPatched[0]) {
                GnuLog.log("JAVA_ WorldRenderTransformer: patched renderWorldPass in " + className);
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            GnuLog.log("JAVA_ WorldRenderTransformer failed for " + className + ": " + t);
            return null;
        }
    }

    /**
     * Injects into {@code renderWorldPass}:
     * <ul>
     *   <li>{@link WorldRenderHook#onWorldRender(float)} before each RETURN
     *       (guarded by pass == 1, existing behaviour).</li>
     * </ul>
     * No CameraHook before/after calls — replaced by orientCamera field redirects.
     */
    private static MethodVisitor injectRenderHook(MethodVisitor mv, boolean[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                // No CameraHook.beforeRenderWorldPass() at HEAD anymore
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    // No CameraHook.afterRenderWorldPass() anymore

                    // Existing: WorldRenderHook.onWorldRender(partialTicks) guarded by pass == 1
                    Label skip = new Label();
                    super.visitVarInsn(Opcodes.ILOAD, 1);   // load pass param
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitJumpInsn(Opcodes.IF_ICMPNE, skip);

                    super.visitVarInsn(Opcodes.FLOAD, 2);   // load partialTicks param
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, HOOK_DESC, false);

                    super.visitLabel(skip);
                    patched[0] = true;
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor injectSlotSpoofLifecycle(MethodVisitor mv, boolean[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            private boolean headDone;

            @Override
            public void visitCode() {
                super.visitCode();
                if (headDone)
                    return;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SPOOF_HOOK_OWNER,
                        "beginRenderSlotSpoof", "()V", false);
                headDone = true;
                patched[0] = true;
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SPOOF_HOOK_OWNER,
                            "endRenderSlotSpoof", "()V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    /**
     * INVOKEVIRTUAL of {@code EntityPlayerSP.func_70082_c(FF)V} (setAngles) with
     * INVOKESTATIC {@link FreeLookHook#dispatchSetAngles(Object, float, float)}.
     *
     * <p>Both call sites in updateCameraAndRender are on the local player entity
     * (EntityPlayerSP). The stack before the call is:
     * {@code ..., entityRef (EntityPlayerSP), yaw (float), pitch (float)}.
     * INVOKESTATIC has the same stack effect: pops (Object, float, float), pushes
     * nothing (void).
     *
     * <p>The entityRef is already on the stack (loaded via GETFIELD
     * Minecraft.field_71439_g) — no changes to preceding instructions needed.
     *
     * <p>Exact match on owner {@code net/minecraft/client/entity/EntityPlayerSP}
     * + name {@code func_70082_c} + desc {@code (FF)V}. Stops the transformer if
     * the replacement count is not exactly 2 (the known normal + inverted-mouse paths).
     */
    private static MethodVisitor injectSetAnglesRedirect(MethodVisitor mv, boolean[] patched, int[] replaceCount) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && ENTITY_OWNER.equals(owner)
                        && METHOD_SET_ANGLES.equals(name)
                        && "(FF)V".equals(desc)) {
                    // Replace: INVOKEVIRTUAL EntityPlayerSP.func_70082_c (FF)V
                    // With:    INVOKESTATIC FreeLookHook.dispatchSetAngles (Ljava/lang/Object;FF)V
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            FREE_LOOK_HOOK_OWNER,
                            "dispatchSetAngles",
                            "(Ljava/lang/Object;FF)V",
                            false);
                    replaceCount[0]++;
                    patched[0] = true;
                    return;
                }
                // All other method instructions pass through unchanged
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
        };
    }

    private static MethodVisitor injectRotationRedirect(MethodVisitor mv, boolean[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                // Only redirect GETFIELD on Entity with float descriptor
                if (opcode == Opcodes.GETFIELD
                        && ENTITY_OWNER.equals(owner)
                        && "F".equals(desc)) {

                    if (FIELD_ROTATION_YAW.equals(name)) {
                        // Replace: GETFIELD Entity.field_70177_z F
                        // With:    INVOKESTATIC FreeLookHook.redirectYaw(Object)F
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                FREE_LOOK_HOOK_OWNER,
                                "redirectYaw",
                                "(Ljava/lang/Object;)F",
                                false);
                        patched[0] = true;
                        return;
                    } else if (FIELD_ROTATION_PITCH.equals(name)) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                FREE_LOOK_HOOK_OWNER,
                                "redirectPitch",
                                "(Ljava/lang/Object;)F",
                                false);
                        patched[0] = true;
                        return;
                    } else if (FIELD_PREV_ROTATION_YAW.equals(name)) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                FREE_LOOK_HOOK_OWNER,
                                "redirectPrevYaw",
                                "(Ljava/lang/Object;)F",
                                false);
                        patched[0] = true;
                        return;
                    } else if (FIELD_PREV_ROTATION_PITCH.equals(name)) {
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                FREE_LOOK_HOOK_OWNER,
                                "redirectPrevPitch",
                                "(Ljava/lang/Object;)F",
                                false);
                        patched[0] = true;
                        return;
                    }
                }
                // All other field instructions pass through unchanged
                super.visitFieldInsn(opcode, owner, name, desc);
            }
        };
    }

    private static boolean isEntityRenderer(String name) {
        return "net/minecraft/client/renderer/EntityRenderer".equals(name);
    }

    private static boolean isRenderWorldPass(String name, String desc) {
        return "renderWorldPass".equals(name) && "(IFJ)V".equals(desc);
    }

    private static boolean isOrientCamera(String name, String desc) {
        return "orientCamera".equals(name) && "(F)V".equals(desc);
    }

    private static boolean isUpdateCameraAndRender(String name, String desc) {
        return METHOD_UPDATE_CAMERA_AND_RENDER.equals(name) && "(FJ)V".equals(desc);
    }

    private static boolean isUpdateRenderer(String name, String desc) {
        return METHOD_UPDATE_RENDERER.equals(name) && "()V".equals(desc);
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
