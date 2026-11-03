package com.albscript.autotreefarmer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ điều khiển chính của Auto Tree Farmer.
 *
 * Luồng hoạt động (state machine):
 *   SCANNING_AREA → SELECTING_TARGET → PLANTING / BONE_MEALING / CHOPPING
 *                                          ↓             ↓              ↓
 *                                   SELECTING_TARGET  CHOPPING     COLLECTING
 *                                                                       ↓
 *                                                               SELECTING_TARGET  (vòng lặp)
 *
 * Tính năng:
 *  - Quét tự động ô farm (dirt/grass + không khí bên trên)
 *  - Trồng Oak Sapling nếu ô trống
 *  - Bón Bone Meal cho đến khi cây mọc
 *  - Chặt cây từ gốc lên ngọn
 *  - Tự chọn slot đúng trên hotbar
 *  - Tạm dừng khi máu hoặc đói thấp
 *  - Hỗ trợ đa khu (multi-area cycling)
 */
public class TreeFarmerController {

    // ─── Cấu hình ────────────────────────────────────────────────────────────

    /** Bán kính quét ô farm (blocks) */
    private static final int SCAN_RADIUS       = 8;
    /** Chiều cao tối đa của cây cần chặt */
    private static final int MAX_TREE_HEIGHT   = 20;
    /** Cooldown giữa hai lần bón bột xương (ticks) */
    private static final int BONE_MEAL_DELAY   = 2;
    /** Cooldown giữa hai lần phá block (ticks) */
    private static final int BREAK_DELAY       = 4;
    /** Cooldown chung giữa các action (ticks) */
    private static final int ACTION_DELAY      = 5;
    /** Ngưỡng máu (tim) để tạm dừng hồi phục */
    private static final float HEAL_THRESHOLD  = 6.0f;  // 3 tim

    // ─── Trạng thái nội bộ ───────────────────────────────────────────────────

    private boolean      active           = false;
    private FarmerState  state            = FarmerState.IDLE;
    private List<BlockPos> farmPlots      = new ArrayList<>();
    private int          currentAreaIdx   = 0;
    private BlockPos     currentTarget    = null;
    private int          actionCooldown   = 0;
    private int          boneMealAttempts = 0;
    private int          areaCount        = 0; // đếm số khu đã xử lý

    // ─── Public API ──────────────────────────────────────────────────────────

    public boolean isActive() { return active; }

    public FarmerState getState() { return state; }

    /** Bật / Tắt farmer */
    public void toggle(MinecraftClient client) {
        active = !active;
        if (active) {
            state            = FarmerState.SCANNING_AREA;
            currentAreaIdx   = 0;
            boneMealAttempts = 0;
            farmPlots.clear();
            AutoTreeFarmerMod.sendMessage(client,
                "§aBật Auto Tree Farmer §7(chế độ §fĐa khu§7)");
        } else {
            state         = FarmerState.IDLE;
            currentTarget = null;
            AutoTreeFarmerMod.sendMessage(client, "§cTắt Auto Tree Farmer");
        }
    }

    /** Được gọi mỗi tick khi active == true */
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Giảm cooldown
        if (actionCooldown > 0) { actionCooldown--; return; }

        ClientPlayerEntity player = client.player;
        ClientWorld        world  = client.world;

        // Kiểm tra máu/đói trước mọi action
        if (state != FarmerState.HEALING && needsHealing(player)) {
            AutoTreeFarmerMod.sendMessage(client,
                "§eTạm dừng để hồi phục (Máu: " +
                (int)(player.getHealth() / 2) + "♥)");
            state = FarmerState.HEALING;
            return;
        }

        switch (state) {
            case SCANNING_AREA    -> scanArea(client, world, player);
            case SELECTING_TARGET -> selectTarget(client, world, player);
            case PLANTING         -> performPlanting(client, world, player);
            case BONE_MEALING     -> performBoneMeal(client, world, player);
            case CHOPPING         -> performChopping(client, world, player);
            case COLLECTING       -> finishCollecting(client);
            case HEALING          -> waitForHealing(client, player);
            default               -> {}
        }
    }

    // ─── State handlers ──────────────────────────────────────────────────────

    /** Quét ô farm trong bán kính SCAN_RADIUS */
    private void scanArea(MinecraftClient client, ClientWorld world,
                           ClientPlayerEntity player) {
        farmPlots.clear();
        BlockPos center = player.getBlockPos();
        AutoTreeFarmerMod.sendMessage(client, "Đang phân tích khu vực Farm hiện tại...");

        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                // Kiểm tra nhiều mức Y xung quanh vị trí player
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos floor = center.add(x, dy, z);
                    if (isPlantable(world.getBlockState(floor).getBlock())) {
                        Block above = world.getBlockState(floor.up()).getBlock();
                        if (above == Blocks.AIR || isSapling(above) || isLog(above)) {
                            if (!farmPlots.contains(floor)) {
                                farmPlots.add(floor);
                            }
                        }
                    }
                }
            }
        }

        if (farmPlots.isEmpty()) {
            AutoTreeFarmerMod.sendMessage(client, "§cKhông tìm thấy ô farm nào! Dừng lại.");
            active = false;
            state  = FarmerState.IDLE;
        } else {
            AutoTreeFarmerMod.sendMessage(client,
                "Phát hiện §f" + farmPlots.size() + "§r ô farm");
            currentAreaIdx = 0;
            state = FarmerState.SELECTING_TARGET;
            actionCooldown = 5;
        }
    }

    /**
     * Duyệt qua farmPlots, quyết định action tiếp theo cho mỗi ô:
     *  - Trống  → PLANTING
     *  - Sapling → BONE_MEALING
     *  - Có log  → CHOPPING
     */
    private void selectTarget(MinecraftClient client, ClientWorld world,
                               ClientPlayerEntity player) {
        if (farmPlots.isEmpty()) { state = FarmerState.SCANNING_AREA; return; }

        int n = farmPlots.size();
        for (int i = 0; i < n; i++) {
            int idx   = (currentAreaIdx + i) % n;
            BlockPos plot = farmPlots.get(idx);
            Block above   = world.getBlockState(plot.up()).getBlock();

            if (above == Blocks.AIR || above == Blocks.CAVE_AIR) {
                // Ô trống → trồng sapling
                if (hasItem(player, Items.OAK_SAPLING)) {
                    currentTarget    = plot;
                    currentAreaIdx   = idx;
                    boneMealAttempts = 0;
                    state = FarmerState.PLANTING;
                    return;
                }
            } else if (isSapling(above)) {
                // Có sapling → bón bột xương
                if (hasItem(player, Items.BONE_MEAL)) {
                    currentTarget    = plot;
                    currentAreaIdx   = idx;
                    boneMealAttempts = 0;
                    state = FarmerState.BONE_MEALING;
                    AutoTreeFarmerMod.sendMessage(client,
                        "Đang bón bột xương (" + (idx + 1) + "/" + n + " khu)");
                    return;
                }
            } else if (isLog(above) || hasLogAbove(world, plot)) {
                // Có cây → chặt
                currentTarget  = plot;
                currentAreaIdx = idx;
                AutoTreeFarmerMod.sendMessage(client,
                    "Bắt đầu chặt cây tại khu §f" + (idx + 1) + "/" + n);
                state = FarmerState.CHOPPING;
                return;
            }
        }

        // Chưa có việc → quét lại sau một lúc
        actionCooldown = 40;
        state = FarmerState.SCANNING_AREA;
    }

    /** Trồng oak sapling lên ô đất */
    private void performPlanting(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        int slot = findItemSlot(player, Items.OAK_SAPLING);
        if (slot == -1) {
            AutoTreeFarmerMod.sendMessage(client, "§cHết sapling! Dừng farmer.");
            active = false; return;
        }

        player.getInventory().selectedSlot = slot;
        lookAt(player, Vec3d.ofCenter(currentTarget).add(0, 0.5, 0));

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(currentTarget).add(0, 0.5, 0),
            Direction.UP, currentTarget, false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

        actionCooldown = ACTION_DELAY;
        // Sau khi trồng, chuyển sang ô tiếp theo để tạo cơ hội cho cây mọc
        currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
        state = FarmerState.SELECTING_TARGET;
    }

    /** Bón bột xương cho sapling, lặp đến khi cây mọc */
    private void performBoneMeal(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        BlockPos sapPos = currentTarget.up();

        // Kiểm tra cây đã mọc chưa
        Block sapBlock = world.getBlockState(sapPos).getBlock();
        if (!isSapling(sapBlock)) {
            if (hasLogAbove(world, currentTarget)) {
                AutoTreeFarmerMod.sendMessage(client, "✔ Cây đã mọc! Chuẩn bị chặt...");
                state = FarmerState.CHOPPING;
            } else {
                // Sapling biến mất nhưng không thấy log → rescan
                state = FarmerState.SELECTING_TARGET;
            }
            return;
        }

        int slot = findItemSlot(player, Items.BONE_MEAL);
        if (slot == -1) {
            AutoTreeFarmerMod.sendMessage(client, "§cHết bột xương!");
            state = FarmerState.SELECTING_TARGET; return;
        }

        player.getInventory().selectedSlot = slot;
        lookAt(player, Vec3d.ofCenter(sapPos));

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(sapPos).add(0, 0.5, 0),
            Direction.UP, sapPos, false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

        boneMealAttempts++;
        actionCooldown = BONE_MEAL_DELAY;

        // Bảo vệ: sau 80 lần bone meal vẫn không mọc → bỏ qua ô này
        if (boneMealAttempts > 80) {
            AutoTreeFarmerMod.sendMessage(client,
                "§eCảnh báo: Ô " + (currentAreaIdx + 1) + " không phản hồi, bỏ qua");
            boneMealAttempts = 0;
            currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
            state = FarmerState.SELECTING_TARGET;
        }
    }

    /** Chặt cây từ gốc lên ngọn (một block mỗi tick) */
    private void performChopping(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        // Tìm log block thấp nhất
        BlockPos logPos = null;
        for (int y = 1; y <= MAX_TREE_HEIGHT; y++) {
            BlockPos check = currentTarget.up(y);
            if (isLog(world.getBlockState(check).getBlock())) {
                logPos = check;
                break;
            }
        }

        if (logPos == null) {
            // Không còn log → cây đã bị chặt xong
            areaCount++;
            AutoTreeFarmerMod.sendMessage(client,
                "✔ Đã thu hoạch §f" + areaCount + "§r cây. Đặt lại sapling...");
            state = FarmerState.COLLECTING;
            return;
        }

        // Chọn rìu nếu có
        int axeSlot = findBestAxeSlot(player);
        if (axeSlot != -1) player.getInventory().selectedSlot = axeSlot;

        // Nhìn về phía log và phá
        lookAt(player, Vec3d.ofCenter(logPos));
        client.interactionManager.attackBlock(logPos, Direction.DOWN);
        // Tiếp tục mining progress mỗi tick
        client.interactionManager.updateBlockBreakingProgress(logPos, Direction.DOWN);

        actionCooldown = BREAK_DELAY;
    }

    /** Đợi drops rơi, sau đó chuyển sang ô tiếp */
    private void finishCollecting(MinecraftClient client) {
        actionCooldown = 20; // ~1 giây chờ items rơi
        currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
        state = FarmerState.SELECTING_TARGET;
    }

    /** Kiểm tra máu, chờ hồi phục nếu cần */
    private void waitForHealing(MinecraftClient client, ClientPlayerEntity player) {
        // Tự ăn nếu có đồ ăn trong hotbar
        tryEatFood(client, player);

        if (!needsHealing(player)) {
            AutoTreeFarmerMod.sendMessage(client, "§aĐã hồi phục! Tiếp tục farming...");
            state = FarmerState.SELECTING_TARGET;
        } else {
            actionCooldown = 20;
        }
    }

    // ─── Utility: Player Actions ─────────────────────────────────────────────

    /** Xoay player nhìn về phía target */
    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye  = player.getEyePos();
        double dx  = target.x - eye.x;
        double dy  = target.y - eye.y;
        double dz  = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        float yaw   = (float)  Math.toDegrees(Math.atan2(-dx, dz));

        player.setYaw(yaw);
        player.setPitch(Math.max(-90f, Math.min(90f, pitch)));
    }

    /** Ăn đồ ăn từ hotbar để hồi phục */
    private void tryEatFood(MinecraftClient client, ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().isFood()) {
                player.getInventory().selectedSlot = i;
                client.options.useKey.setPressed(true);
                actionCooldown = 32; // thời gian ăn
                return;
            }
        }
    }

    // ─── Utility: Inventory ──────────────────────────────────────────────────

    private boolean hasItem(ClientPlayerEntity player, Item item) {
        return findItemSlot(player, item) != -1;
    }

    /** Trả về slot hotbar (0–8) chứa item, -1 nếu không có */
    private int findItemSlot(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    /**
     * Tìm slot có rìu tốt nhất (ưu tiên Diamond > Iron > Stone > Wood).
     * Trả về -1 nếu không có rìu nào.
     */
    private int findBestAxeSlot(ClientPlayerEntity player) {
        int   bestSlot     = -1;
        float bestPriority = -1f;

        Item[] axes = {
            Items.DIAMOND_AXE, Items.NETHERITE_AXE,
            Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
        };
        float[] priorities = { 4f, 5f, 3f, 2f, 1f };

        for (int i = 0; i < 9; i++) {
            Item item = player.getInventory().getStack(i).getItem();
            for (int j = 0; j < axes.length; j++) {
                if (item == axes[j] && priorities[j] > bestPriority) {
                    bestSlot     = i;
                    bestPriority = priorities[j];
                }
            }
        }
        return bestSlot;
    }

    // ─── Utility: Block Checks ───────────────────────────────────────────────

    /** Block này có thể trồng cây lên không? */
    private boolean isPlantable(Block b) {
        return b == Blocks.DIRT          || b == Blocks.GRASS_BLOCK ||
               b == Blocks.PODZOL       || b == Blocks.COARSE_DIRT  ||
               b == Blocks.ROOTED_DIRT  || b == Blocks.MUD;
    }

    /** Block này có phải sapling không? */
    private boolean isSapling(Block b) {
        return b == Blocks.OAK_SAPLING    || b == Blocks.BIRCH_SAPLING   ||
               b == Blocks.SPRUCE_SAPLING || b == Blocks.JUNGLE_SAPLING  ||
               b == Blocks.ACACIA_SAPLING || b == Blocks.DARK_OAK_SAPLING ||
               b == Blocks.MANGROVE_PROPAGULE || b == Blocks.CHERRY_SAPLING;
    }

    /** Block này có phải log gỗ không? */
    private boolean isLog(Block b) {
        return b == Blocks.OAK_LOG         || b == Blocks.BIRCH_LOG       ||
               b == Blocks.SPRUCE_LOG      || b == Blocks.JUNGLE_LOG      ||
               b == Blocks.ACACIA_LOG      || b == Blocks.DARK_OAK_LOG    ||
               b == Blocks.MANGROVE_LOG    || b == Blocks.CHERRY_LOG      ||
               b == Blocks.OAK_WOOD        || b == Blocks.BIRCH_WOOD      ||
               b == Blocks.SPRUCE_WOOD     || b == Blocks.JUNGLE_WOOD     ||
               b == Blocks.ACACIA_WOOD     || b == Blocks.DARK_OAK_WOOD   ||
               b == Blocks.STRIPPED_OAK_LOG;
    }

    /** Kiểm tra xem có log block nào phía trên plot không */
    private boolean hasLogAbove(ClientWorld world, BlockPos plot) {
        for (int y = 1; y <= MAX_TREE_HEIGHT; y++) {
            if (isLog(world.getBlockState(plot.up(y)).getBlock())) return true;
        }
        return false;
    }

    /** Player cần hồi phục không? */
    private boolean needsHealing(ClientPlayerEntity player) {
        return player.getHealth() <= HEAL_THRESHOLD;
    }
}
