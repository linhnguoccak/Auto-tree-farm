package com.albscript.autotreefarmer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
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

public class TreeFarmerController {

    private static final int   SCAN_RADIUS      = 8;
    private static final int   MAX_TREE_HEIGHT  = 20;
    private static final int   BONE_MEAL_DELAY  = 2;
    private static final int   BREAK_DELAY      = 3;
    private static final int   ACTION_DELAY     = 4;
    private static final float HEAL_THRESHOLD   = 6.0f;
    private static final double REACH           = 4.2; // tầm với tương tác

    private boolean        active           = false;
    private FarmerState    state            = FarmerState.IDLE;
    private List<BlockPos> farmPlots        = new ArrayList<>();
    private BlockPos       centerPos        = null; // vị trí trung tâm khi bật
    private int            currentAreaIdx   = 0;
    private BlockPos       currentTarget    = null;
    private int            actionCooldown   = 0;
    private int            boneMealAttempts = 0;
    private int            treeCount        = 0;
    private boolean        isWalkingToTree  = false;

    public boolean isActive()     { return active; }
    public FarmerState getState() { return state; }

    public void toggle(MinecraftClient client) {
        active = !active;
        if (active) {
            state            = FarmerState.SCANNING_AREA;
            currentAreaIdx   = 0;
            boneMealAttempts = 0;
            isWalkingToTree  = false;
            farmPlots.clear();
            if (client.player != null) centerPos = client.player.getBlockPos();
            AutoTreeFarmerMod.sendMessage(client, "§aBật Auto Tree Farmer");
        } else {
            stopWalking(client);
            state         = FarmerState.IDLE;
            currentTarget = null;
            AutoTreeFarmerMod.sendMessage(client, "§cTắt Auto Tree Farmer");
        }
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (actionCooldown > 0) { actionCooldown--; return; }

        ClientPlayerEntity player = client.player;
        ClientWorld        world  = client.world;

        if (state != FarmerState.HEALING && needsHealing(player)) {
            stopWalking(client);
            AutoTreeFarmerMod.sendMessage(client,
                "§eTạm dừng để hồi phục (Máu: " + (int)(player.getHealth() / 2) + "♥)");
            state = FarmerState.HEALING;
            return;
        }

        switch (state) {
            case SCANNING_AREA    -> scanArea(client, world, player);
            case SELECTING_TARGET -> selectTarget(client, world, player);
            case PLANTING         -> performPlanting(client, world, player);
            case BONE_MEALING     -> performBoneMeal(client, world, player);
            case CHOPPING         -> performChopping(client, world, player);
            case COLLECTING       -> finishCollecting(client, player);
            case HEALING          -> waitForHealing(client, player);
            default               -> {}
        }
    }

    // ─── SCAN ───────────────────────────────────────────────────────────────

    private void scanArea(MinecraftClient client, ClientWorld world,
                           ClientPlayerEntity player) {
        farmPlots.clear();
        BlockPos center = player.getBlockPos();
        centerPos = center;

        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos floor = center.add(x, dy, z);
                    if (isPlantable(world.getBlockState(floor).getBlock())) {
                        Block above = world.getBlockState(floor.up()).getBlock();
                        if (above == Blocks.AIR || isSapling(above) || isLog(above)) {
                            if (!farmPlots.contains(floor)) farmPlots.add(floor);
                        }
                    }
                }
            }
        }

        if (farmPlots.isEmpty()) {
            AutoTreeFarmerMod.sendMessage(client, "§cKhông tìm thấy ô farm! Dừng.");
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

    // ─── SELECT ──────────────────────────────────────────────────────────────

    private void selectTarget(MinecraftClient client, ClientWorld world,
                               ClientPlayerEntity player) {
        if (farmPlots.isEmpty()) { state = FarmerState.SCANNING_AREA; return; }

        int n = farmPlots.size();

        // PASS 1: Trồng hết ô trống (chỉ ô trong tầm với)
        for (int i = 0; i < n; i++) {
            int idx       = (currentAreaIdx + i) % n;
            BlockPos plot = farmPlots.get(idx);
            Block above   = world.getBlockState(plot.up()).getBlock();

            if ((above == Blocks.AIR || above == Blocks.CAVE_AIR)
                    && hasItem(player, Items.OAK_SAPLING)
                    && inReach(player, plot)) {
                currentTarget  = plot;
                currentAreaIdx = idx;
                state = FarmerState.PLANTING;
                return;
            }
        }

        // PASS 2: Bón bone meal hết sapling (chỉ ô trong tầm với)
        for (int i = 0; i < n; i++) {
            int idx       = (currentAreaIdx + i) % n;
            BlockPos plot = farmPlots.get(idx);
            Block above   = world.getBlockState(plot.up()).getBlock();

            if (isSapling(above)
                    && hasItem(player, Items.BONE_MEAL)
                    && inReach(player, plot)) {
                currentTarget    = plot;
                currentAreaIdx   = idx;
                boneMealAttempts = 0;
                state = FarmerState.BONE_MEALING;
                return;
            }
        }

        // PASS 3: Chặt cây — đi đến cây
        for (int i = 0; i < n; i++) {
            int idx       = (currentAreaIdx + i) % n;
            BlockPos plot = farmPlots.get(idx);

            if (hasLogAbove(world, plot)) {
                currentTarget  = plot;
                currentAreaIdx = idx;
                AutoTreeFarmerMod.sendMessage(client,
                    "Đi chặt cây §f" + (idx + 1) + "/" + n);
                state = FarmerState.CHOPPING;
                return;
            }
        }

        // Xong hết → quét lại
        stopWalking(client);
        actionCooldown = 40;
        state = FarmerState.SCANNING_AREA;
    }

    // ─── PLANT ───────────────────────────────────────────────────────────────

    private void performPlanting(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        // Kiểm tra tầm với
        if (!inReach(player, currentTarget)) {
            currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
            state = FarmerState.SELECTING_TARGET;
            return;
        }

        int slot = findItemSlot(player, Items.OAK_SAPLING);
        if (slot == -1) {
            AutoTreeFarmerMod.sendMessage(client, "§cHết sapling! Dừng.");
            active = false; return;
        }

        player.getInventory().setSelectedSlot(slot);
        lookAt(player, Vec3d.ofCenter(currentTarget).add(0, 0.5, 0));

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(currentTarget).add(0, 0.5, 0),
            Direction.UP, currentTarget, false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

        actionCooldown = ACTION_DELAY;
        currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
        state = FarmerState.SELECTING_TARGET;
    }

    // ─── BONE MEAL ───────────────────────────────────────────────────────────

    private void performBoneMeal(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        BlockPos sapPos   = currentTarget.up();
        Block    sapBlock = world.getBlockState(sapPos).getBlock();

        if (!isSapling(sapBlock)) {
            state = FarmerState.SELECTING_TARGET;
            return;
        }

        if (!inReach(player, currentTarget)) {
            currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
            state = FarmerState.SELECTING_TARGET;
            return;
        }

        int slot = findItemSlot(player, Items.BONE_MEAL);
        if (slot == -1) {
            AutoTreeFarmerMod.sendMessage(client, "§cHết bột xương!");
            state = FarmerState.SELECTING_TARGET; return;
        }

        player.getInventory().setSelectedSlot(slot);
        lookAt(player, Vec3d.ofCenter(sapPos));

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(sapPos).add(0, 0.5, 0),
            Direction.UP, sapPos, false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);

        boneMealAttempts++;
        actionCooldown = BONE_MEAL_DELAY;

        if (boneMealAttempts > 80) {
            boneMealAttempts = 0;
            currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
            state = FarmerState.SELECTING_TARGET;
        }
    }

    // ─── CHOP ────────────────────────────────────────────────────────────────

    private void performChopping(MinecraftClient client, ClientWorld world,
                                  ClientPlayerEntity player) {
        if (currentTarget == null) { state = FarmerState.SELECTING_TARGET; return; }

        // Tìm khúc gỗ thấp nhất
        BlockPos logPos = null;
        for (int y = 1; y <= MAX_TREE_HEIGHT; y++) {
            BlockPos check = currentTarget.up(y);
            if (isLog(world.getBlockState(check).getBlock())) {
                logPos = check; break;
            }
        }

        if (logPos == null) {
            // Cây đã chặt xong
            treeCount++;
            stopWalking(client);
            AutoTreeFarmerMod.sendMessage(client, "✔ Chặt xong cây §f" + treeCount);
            state = FarmerState.COLLECTING;
            return;
        }

        double distToPlot = player.getPos().distanceTo(Vec3d.ofCenter(currentTarget));

        if (distToPlot > 2.5) {
            // Đi đến gốc cây
            walkToward(client, player, currentTarget);
            actionCooldown = 1;
            return;
        }

        // Đã đến nơi — dừng đi, chặt
        stopWalking(client);

        int axeSlot = findBestAxeSlot(player);
        if (axeSlot != -1) player.getInventory().setSelectedSlot(axeSlot);

        lookAt(player, Vec3d.ofCenter(logPos));
        client.interactionManager.attackBlock(logPos, Direction.DOWN);
        client.interactionManager.updateBlockBreakingProgress(logPos, Direction.DOWN);

        actionCooldown = BREAK_DELAY;
    }

    // ─── COLLECT ─────────────────────────────────────────────────────────────

    private void finishCollecting(MinecraftClient client, ClientPlayerEntity player) {
        // Chờ item rơi rồi quét lại
        actionCooldown = 25;
        currentAreaIdx = (currentAreaIdx + 1) % Math.max(1, farmPlots.size());
        state = FarmerState.SELECTING_TARGET;
    }

    // ─── HEAL ────────────────────────────────────────────────────────────────

    private void waitForHealing(MinecraftClient client, ClientPlayerEntity player) {
        tryEatFood(client, player);
        if (!needsHealing(player)) {
            AutoTreeFarmerMod.sendMessage(client, "§aHồi phục xong! Tiếp tục...");
            state = FarmerState.SELECTING_TARGET;
        } else {
            actionCooldown = 20;
        }
    }

    // ─── MOVEMENT ────────────────────────────────────────────────────────────

    private void walkToward(MinecraftClient client, ClientPlayerEntity player,
                             BlockPos target) {
        lookAt(player, Vec3d.ofCenter(target));
        client.options.forwardKey.setPressed(true);
        isWalkingToTree = true;
    }

    private void stopWalking(MinecraftClient client) {
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
        }
        isWalkingToTree = false;
    }

    // ─── UTILS ───────────────────────────────────────────────────────────────

    private boolean inReach(ClientPlayerEntity player, BlockPos plot) {
        return player.getPos().distanceTo(Vec3d.ofCenter(plot)) <= REACH;
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d  eye  = player.getEyePos();
        double dx   = target.x - eye.x;
        double dy   = target.y - eye.y;
        double dz   = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        float yaw   = (float)  Math.toDegrees(Math.atan2(-dx, dz));

        player.setYaw(yaw);
        player.setPitch(Math.max(-90f, Math.min(90f, pitch)));
    }

    private void tryEatFood(MinecraftClient client, ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                player.getInventory().setSelectedSlot(i);
                client.options.useKey.setPressed(true);
                actionCooldown = 32;
                return;
            }
        }
    }

    private boolean hasItem(ClientPlayerEntity player, Item item) {
        return findItemSlot(player, item) != -1;
    }

    private int findItemSlot(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    private int findBestAxeSlot(ClientPlayerEntity player) {
        int   bestSlot     = -1;
        float bestPriority = -1f;
        Item[]  axes       = { Items.NETHERITE_AXE, Items.DIAMOND_AXE,
                               Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE };
        float[] priorities = { 5f, 4f, 3f, 2f, 1f };

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

    private boolean isPlantable(Block b) {
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK ||
               b == Blocks.PODZOL || b == Blocks.COARSE_DIRT ||
               b == Blocks.ROOTED_DIRT || b == Blocks.MUD;
    }

    private boolean isSapling(Block b) {
        return b == Blocks.OAK_SAPLING    || b == Blocks.BIRCH_SAPLING   ||
               b == Blocks.SPRUCE_SAPLING || b == Blocks.JUNGLE_SAPLING  ||
               b == Blocks.ACACIA_SAPLING || b == Blocks.DARK_OAK_SAPLING ||
               b == Blocks.MANGROVE_PROPAGULE || b == Blocks.CHERRY_SAPLING;
    }

    private boolean isLog(Block b) {
        return b == Blocks.OAK_LOG      || b == Blocks.BIRCH_LOG    ||
               b == Blocks.SPRUCE_LOG   || b == Blocks.JUNGLE_LOG   ||
               b == Blocks.ACACIA_LOG   || b == Blocks.DARK_OAK_LOG ||
               b == Blocks.MANGROVE_LOG || b == Blocks.CHERRY_LOG   ||
               b == Blocks.OAK_WOOD     || b == Blocks.BIRCH_WOOD   ||
               b == Blocks.SPRUCE_WOOD  || b == Blocks.JUNGLE_WOOD  ||
               b == Blocks.ACACIA_WOOD  || b == Blocks.DARK_OAK_WOOD ||
               b == Blocks.STRIPPED_OAK_LOG;
    }

    private boolean hasLogAbove(ClientWorld world, BlockPos plot) {
        for (int y = 1; y <= MAX_TREE_HEIGHT; y++) {
            if (isLog(world.getBlockState(plot.up(y)).getBlock())) return true;
        }
        return false;
    }

    private boolean needsHealing(ClientPlayerEntity player) {
        return player.getHealth() <= HEAL_THRESHOLD;
    }
}
