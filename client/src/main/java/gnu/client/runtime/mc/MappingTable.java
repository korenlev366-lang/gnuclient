package gnu.client.runtime.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * SRG ↔ Notch member and class names for Minecraft 1.8.9 (Timewarp {@code getObfuscated}
 * equivalent). Forge keeps SRG field/method names at runtime; Lunar/Badlion/Vanilla use
 * short Notch identifiers.
 *
 * Source of truth: {@code /home/lev/forge 1.8.9 stable 22/fields.csv},
 * {@code /home/lev/forge 1.8.9 stable 22/methods.csv}.
 * SRGs not found in stable_22 are preserved as legacy fallbacks only.
 */
public final class MappingTable {

    private static final Map<String, String> SRG_TO_NOTCH_FIELD = new HashMap<>();
    private static final Map<String, String> SRG_TO_NOTCH_METHOD = new HashMap<>();
    private static final Map<String, String> SRG_TO_MCP_MEMBER = new HashMap<>();
    private static final Map<String, String> MCP_TO_NOTCH_CLASS = new HashMap<>();

    static {
        // ---- classes (MCP binary name → Notch internal name) ----
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.Minecraft", "ave");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.network.NetworkManager", "ek");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.network.Packet", "ff");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.entity.Entity", "pk");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.settings.KeyBinding", "avb");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.MovementInput", "bew");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.settings.MovementInputFromOptions", "bhc");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.settings.KeyboardInput", "bhd");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.Timer", "bhe");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.gui.ScaledResolution", "avp");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.network.play.client.C0BPacketEntityAction", "iw");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.renderer.entity.RenderManager", "biu");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.Vec3", "aui");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.MathHelper", "auz");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.AxisAlignedBB", "aug");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.item.ItemSword", "aay");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.item.ItemAxe", "yl");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.entity.player.EntityPlayer", "wn");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.BlockPos", "dt");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.util.MovingObjectPosition", "auh");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.multiplayer.PlayerControllerMP", "bda");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.settings.KeyboardInput", "bhd");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.settings.MovementInputFromOptions", "bhc");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.client.entity.EntityPlayerSP", "bli");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.network.play.client.C0CPacketInput", "in");
        MCP_TO_NOTCH_CLASS.put("net.minecraft.entity.item.EntityBoat", "uz");

        // ---- Minecraft fields ----
        putField("field_71439_g", "h");   // thePlayer
        putField("field_71441_e", "f");   // theWorld
        putField("field_71462_r", "m");   // currentScreen
        putField("field_71466_p", "k");   // fontRendererObj
        putField("field_71428_T", "Y");   // timer  (NOT entityRenderer! CSV stable_22: field_71428_T=timer)
        putField("field_71460_t", "aa");  // entityRenderer  (CSV stable_22: field_71460_t=entityRenderer)
        putField("field_71442_b", "c");   // playerController
        putField("field_71476_x", "s");   // objectMouseOver
        putField("field_147125_j", "u");  // pointedEntity
        putField("field_71474_y", "t");   // gameSettings
        putField("field_71429_W", "ag");  // leftClickCounter

        // ---- Entity fields ----
        putField("field_71074_e", "g");   // itemInUse (EntityPlayer)
        putField("field_71072_f", "h");   // itemInUseCount (EntityPlayer)
        putField("field_70165_t", "s");   // posX
        putField("field_70163_u", "t");   // posY
        putField("field_70161_v", "u");   // posZ
        putField("field_70142_S", "P");   // lastTickPosX
        putField("field_70137_T", "Q");   // lastTickPosY
        putField("field_70136_U", "R");   // lastTickPosZ
        putField("field_70169_q", "M");   // prevPosX
        putField("field_70167_r", "N");   // prevPosY
        putField("field_70166_s", "O");   // prevPosZ
        putField("field_70126_B", "A");   // prevRotationYaw
        putField("field_70127_C", "B");   // prevRotationPitch
        putField("field_70177_z", "y");   // rotationYaw
        putField("field_70125_A", "z");   // rotationPitch
        putField("field_70122_E", "C");   // onGround
        putField("field_70143_R", "O");   // fallDistance
        putField("field_70737_aN", "ax"); // hurtTime
        putField("field_70159_w", "v");   // motionX
        putField("field_70181_x", "w");   // motionY
        putField("field_70179_y", "x");   // motionZ
        putMcpMember("field_70153_n", "riddenByEntity");
        putMcpMember("field_70154_o", "ridingEntity");
        putField("field_71415_G", "A");   // inGameHasFocus
        // Legacy entityId / isSprinting / isSwingInProgress fields (not in stable_22 CSV;
        // preserved as fallbacks for older MCP-based Forge builds)
        putField("field_70157_k", "c");   // entityId (legacy SRG)
        putField("field_70151_cx", "bI"); // isSprinting (legacy SRG)
        putField("field_82175_bq", "aZ"); // isSwingInProgress (stable_22 SRG)

        // ---- EntityPlayerSP / MovementInput ----
        putField("field_71158_b", "bE");  // movementInput
        // field_71159_q not confirmed in stable_22 CSV (CSV says field_71159_c=mc on EntityPlayerSP)
        putField("field_78899_d", "a");   // sneak (MovementInput)
        putField("field_78902_a", "c");   // moveStrafe (MovementInput)
        putField("field_78900_b", "b");   // moveForward (MovementInput)
        putField("field_78901_c", "d");   // jump (MovementInput)

        // ---- C0CPacketInput (boat steer) ----
        putField("field_149618_a", "a");  // strafeSpeed
        putField("field_149617_b", "b");  // forwardSpeed
        putField("field_149619_c", "c");  // jumping
        putField("field_149616_d", "d");  // sneaking
        putMcpMember("field_149618_a", "strafeSpeed");
        putMcpMember("field_149617_b", "forwardSpeed");
        putMcpMember("field_149619_c", "jumping");
        putMcpMember("field_149616_d", "sneaking");
        putField("field_175171_bO", "bL"); // serverSprintState

        // ---- GameSettings keybinds ----
        putField("field_74311_E", "ad");  // keyBindSneak
        putField("field_74314_A", "ag");  // keyBindJump
        putField("field_74368_y", "aa");  // keyBindBack  (NOT field_74310_D! CSV: field_74368_y=keyBindBack)
        putField("field_74351_w", "ak");  // keyBindForward
        putField("field_74370_x", "al");  // keyBindLeft
        putField("field_74366_z", "am");  // keyBindRight
        putField("field_74312_F", "ac");  // keyBindAttack
        putField("field_74313_G", "ab");  // keyBindUseItem (NOT field_74313_B! CSV: field_74313_G=keyBindUseItem)
        putField("field_151444_V", "aj"); // keyBindSprint
        putField("field_74341_c", "a");   // mouseSensitivity

        // ---- RenderManager ----
        putField("field_78735_i", "h");   // playerViewY
        putField("field_78732_j", "i");   // playerViewX  (NOT field_78736_j! CSV: field_78732_j=playerViewX)

        // ---- Timer ----
        putField("field_74278_d", "d");   // timerSpeed  (CSV stable_22: field_74278_d=timerSpeed; field_74278_a NOT IN CSV)
        putField("field_74281_c", "c");   // renderPartialTicks

        // ---- AxisAlignedBB ----
        putField("field_72340_a", "a");   // minX
        putField("field_72338_b", "b");   // minY
        putField("field_72339_c", "c");   // minZ
        putField("field_72336_d", "d");   // maxX
        putField("field_72337_e", "e");   // maxY
        putField("field_72334_f", "f");   // maxZ

        // ---- MovingObjectPosition (Reach) ----
        putField("field_72308_g", "d");   // entityHit
        putField("field_72307_f", "c");   // hitVec
        putField("field_72313_a", "a");   // typeOfHit

        // ---- World / WorldClient ----
        putField("field_73010_i", "b");  // playerEntities (WorldClient)
        putField("field_72996_f", "f");  // loadedEntityList (World)  (NOT field_72996_E! CSV: field_72996_f=loadedEntityList)

        // ---- Container / Slot ----
        putField("field_147006_u", "d");  // theSlot (GuiContainer hovered slot)  (NOT inventorySlots!)
        putField("field_147009_r", "a");  // guiTop (CSV: field_147009_r=guiTop)
        putField("field_75222_d", "c");  // slotNumber (Slot)
        putField("field_75152_c", "b");  // windowId (Container)  (NOT field_75150_c! CSV: field_75152_c=windowId)
        putField("field_75151_b", "c");  // inventorySlots (Container)

        // ---- MCP deobf member names (Lunar Genesis may expose these) ----
        putMcpMember("field_71439_g", "thePlayer");
        putMcpMember("field_71441_e", "theWorld");
        putMcpMember("field_71462_r", "currentScreen");
        putMcpMember("field_71474_y", "gameSettings");
        putMcpMember("field_71476_x", "objectMouseOver");
        putMcpMember("field_147125_j", "pointedEntity");
        putMcpMember("field_71428_T", "timer");       // CSV: field_71428_T=timer
        putMcpMember("field_71460_t", "entityRenderer"); // CSV: field_71460_t=entityRenderer
        putMcpMember("field_71466_p", "fontRendererObj"); // CSV: fontRendererObj (not fontRenderer)
        putMcpMember("field_71442_b", "playerController");
        putMcpMember("field_71415_G", "inGameHasFocus");
        putMcpMember("field_73010_i", "playerEntities");
        putMcpMember("field_72996_f", "loadedEntityList");
        putMcpMember("field_71158_b", "movementInput");
        putMcpMember("field_70165_t", "posX");
        putMcpMember("field_70163_u", "posY");
        putMcpMember("field_70161_v", "posZ");
        putMcpMember("field_70142_S", "lastTickPosX");
        putMcpMember("field_70137_T", "lastTickPosY");
        putMcpMember("field_70136_U", "lastTickPosZ");
        putMcpMember("field_70177_z", "rotationYaw");
        putMcpMember("field_70125_A", "rotationPitch");
        putMcpMember("field_70122_E", "onGround");
        putMcpMember("field_70737_aN", "hurtTime");
        putMcpMember("field_70159_w", "motionX");
        putMcpMember("field_70181_x", "motionY");
        putMcpMember("field_70179_y", "motionZ");
        putMcpMember("field_70151_cx", "isSprinting"); // legacy
        putMcpMember("field_175171_bO", "serverSprintState");
        putMcpMember("field_74311_E", "keyBindSneak");
        putMcpMember("field_74314_A", "keyBindJump");
        putMcpMember("field_74351_w", "keyBindForward");
        putMcpMember("field_74368_y", "keyBindBack");  // CSV: field_74368_y=keyBindBack
        putMcpMember("field_74312_F", "keyBindAttack");
        putMcpMember("field_74313_G", "keyBindUseItem"); // CSV: field_74313_G=keyBindUseItem
        putMcpMember("field_151444_V", "keyBindSprint");
        putMcpMember("field_74278_d", "timerSpeed");
        putMcpMember("field_74281_c", "renderPartialTicks");
        putMcpMember("field_72340_a", "minX");
        putMcpMember("field_72338_b", "minY");
        putMcpMember("field_72339_c", "minZ");
        putMcpMember("field_72336_d", "maxX");
        putMcpMember("field_72337_e", "maxY");
        putMcpMember("field_72334_f", "maxZ");
        putMcpMember("field_70725_aQ", "deathTime");
        putMcpMember("field_78899_d", "sneak");
        putMcpMember("field_78902_a", "moveStrafe");
        putMcpMember("field_78900_b", "moveForward");
        putMcpMember("field_74341_c", "mouseSensitivity");
        putMcpMember("field_72450_a", "xCoord");
        putMcpMember("field_72448_b", "yCoord");
        putMcpMember("field_72449_c", "zCoord");
        putMcpMember("func_71410_x", "getMinecraft");
        putMcpMember("func_147114_u", "getNetHandler");
        putMcpMember("func_147298_b", "getNetworkManager");
        putMcpMember("func_147297_a", "addToSendQueue");
        putMcpMember("func_147116_af", "clickMouse");
        putMcpMember("func_175606_aa", "getRenderViewEntity");
        putMcpMember("func_70031_b", "setSprinting");
        putMcpMember("func_70051_ag", "isSprinting");
        putMcpMember("func_145782_y", "getEntityId");
        putMcpMember("func_70095_a", "setSneaking");
        putMcpMember("func_151463_i", "getKeyCode");
        putMcpMember("func_74510_a", "setKeyBindState");
        putMcpMember("func_74507_a", "onTick");
        putMcpMember("func_151470_d", "isKeyDown");
        putMcpMember("func_110143_aJ", "getHealth");  // CSV: func_110143_aJ=getHealth (NOT func_70066_a!)
        putMcpMember("func_110139_bj", "getAbsorptionAmount");
        putMcpMember("func_174824_e", "getPositionEyes");
        putMcpMember("func_174813_aQ", "getEntityBoundingBox");
        putMcpMember("func_82150_aj", "isInvisible");
        putMcpMember("func_70090_H", "isInWater");
        putMcpMember("func_76142_g", "wrapAngleTo180_float");
        putMcpMember("func_76131_a", "clamp_float");
        putMcpMember("func_78326_a", "getScaledWidth");
        putMcpMember("func_78328_b", "getScaledHeight");
        putMcpMember("func_70694_bm", "getHeldItem");
        putMcpMember("func_77973_b", "getItem");
        putMcpMember("func_72316_a", "calculateXOffset"); // CSV: func_72316_a=calculateXOffset
        putMcpMember("func_78898_a", "updatePlayerMoveState");
        putMcpMember("func_70676_i", "getLook");
        putMcpMember("func_72441_c", "addVector");
        putMcpMember("func_175623_d", "isAirBlock");
        putMcpMember("func_72839_b", "getEntitiesWithinAABBExcludingEntity");
        putMcpMember("func_72321_a", "addCoord");
        putMcpMember("func_72314_b", "expand");
        putMcpMember("func_72318_a", "isVecInside");
        putMcpMember("func_72327_a", "calculateIntercept");
        putMcpMember("func_72438_d", "distanceTo");
        putMcpMember("func_70067_L", "canBeCollidedWith");
        putMcpMember("func_70111_Y", "getCollisionBorderSize");
        putMcpMember("func_71038_i", "swingItem");
        putMcpMember("func_78764_a", "attackEntity");
        putMcpMember("func_78768_b", "interactWithEntitySendPacket"); // CSV: func_78768_b=interactWithEntitySendPacket
        putMcpMember("func_78769_a", "sendUseItem"); // CSV: func_78769_a=sendUseItem
        putMcpMember("func_70093_af", "isSneaking");

        putMcpMember("func_78753_a", "windowClick");
        putMcpMember("func_75144_a", "slotClick");
        putMcpMember("func_75216_d", "getHasStack");
        putMcpMember("func_75211_c", "getStack");
        putMcpMember("func_75139_a", "getSlot");
        putMcpMember("field_147006_u", "theSlot");     // CSV: field_147006_u=theSlot
        putMcpMember("field_147009_r", "guiTop");      // CSV: field_147009_r=guiTop
        putMcpMember("field_75222_d", "slotNumber");
        putMcpMember("field_75152_c", "windowId");     // CSV: field_75152_c=windowId
        putMcpMember("field_75151_b", "inventorySlots"); // CSV: inventorySlots (with 's')
        putMcpMember("field_71074_e", "itemInUse");
        putMcpMember("field_71072_f", "itemInUseCount");

        // ---- notch method names ----
        putMethod("func_71410_x", "S");   // getMinecraft
        putMethod("func_147114_u", "Q");  // getNetHandler
        putMethod("func_147298_b", "a");  // getNetworkManager
        putMethod("func_147297_a", "a");  // addToSendQueue
        putMethod("func_147116_af", "aw"); // clickMouse
        putMethod("func_175606_aa", "ac"); // getRenderViewEntity
        putMethod("func_70031_b", "d");   // setSprinting
        putMethod("func_70051_ag", "aw"); // isSprinting
        putMethod("func_145782_y", "F");  // getEntityId
        putMethod("func_70095_a", "b");   // setSneaking
        putMethod("func_151463_i", "h");  // getKeyCode
        putMethod("func_74510_a", "a");   // setKeyBindState
        putMethod("func_74507_a", "b");   // onTick (KeyBinding)
        putMethod("func_151470_d", "e");  // isKeyDown
        putMethod("func_78898_a", "a");   // updatePlayerMoveState
        putMethod("func_70107_b", "b");   // setPosition
        putMethod("func_70016_h", "g");   // setVelocity
    }

    private MappingTable() {}

    private static void putField(String srg, String notch) {
        SRG_TO_NOTCH_FIELD.put(srg, notch);
    }

    private static void putMethod(String srg, String notch) {
        SRG_TO_NOTCH_METHOD.put(srg, notch);
    }

    private static void putMcpMember(String srg, String mcp) {
        SRG_TO_MCP_MEMBER.put(srg, mcp);
    }

    /** Member lookup candidates: SRG, Notch, and MCP deobf names (Lunar Genesis hybrid). */
    public static String[] memberCandidates(String primary) {
        if (primary == null || primary.isEmpty())
            return new String[0];
        if (ClientProfile.current() == ClientProfile.FORGE_18)
            return new String[] { primary };

        LinkedHashSet<String> names = new LinkedHashSet<>();
        String mcp = SRG_TO_MCP_MEMBER.get(primary);
        String notch = SRG_TO_NOTCH_METHOD.get(primary);
        if (notch == null)
            notch = SRG_TO_NOTCH_FIELD.get(primary);

        // Lunar Genesis exposes MCP member names at runtime (mcpRuntime=true).
        if (ClientProfile.mcpRuntime() && mcp != null)
            names.add(mcp);

        names.add(primary);

        if (notch != null && !notch.equals(primary))
            names.add(notch);

        if (!ClientProfile.mcpRuntime() && mcp != null && !mcp.equals(primary))
            names.add(mcp);

        return names.toArray(new String[0]);
    }

    /** World entity lists — playerEntities first, then loadedEntityList fallback. */
    public static String[] worldEntityListFields() {
        List<String> fields = new ArrayList<>();
        for (String srg : new String[] { "field_73010_i", "field_72996_f" }) {
            for (String candidate : memberCandidates(srg))
                fields.add(candidate);
        }
        return fields.toArray(new String[0]);
    }

    /** Class binary names to try when loading game classes. */
    public static String[] classCandidates(String mcpBinaryName) {
        if (mcpBinaryName == null || mcpBinaryName.isEmpty())
            return new String[0];
        if (ClientProfile.current() == ClientProfile.FORGE_18)
            return new String[] { mcpBinaryName };

        String notch = MCP_TO_NOTCH_CLASS.get(mcpBinaryName);
        if (notch != null && !notch.equals(mcpBinaryName))
            return new String[] { mcpBinaryName, notch };
        return new String[] { mcpBinaryName };
    }

    /** Minecraft singleton accessor on the resolved MC class. */
    public static String[] getMinecraftSingletonMethods() {
        if (ClientProfile.current() == ClientProfile.FORGE_18)
            return new String[] { "func_71410_x", "getMinecraft" };
        return new String[] { "getMinecraft", "func_71410_x", "S" };
    }

    /** Obfuscated Minecraft class names (Timewarp {@code ave} fallback). */
    public static String[] minecraftClassNames() {
        return new String[] {
                "net.minecraft.client.Minecraft",
                "ave",
        };
    }
}
