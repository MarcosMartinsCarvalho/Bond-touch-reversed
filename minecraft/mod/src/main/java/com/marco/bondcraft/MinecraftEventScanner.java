package com.marco.bondcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class MinecraftEventScanner {
    private final BridgeClient bridge = new BridgeClient();
    private final CobblemonBattleScanner cobblemon = new CobblemonBattleScanner(bridge);
    private final Map<String, Long> lastEventTicks = new HashMap<>();
    private final Map<Integer, Float> lastCobblemonHealth = new HashMap<>();

    private boolean initialized;
    private boolean lastAttackDown;
    private boolean lastUseDown;
    private boolean lastOnGround;
    private boolean lastInWater;
    private boolean lastInLava;
    private boolean lastOnFire;
    private boolean lastSleeping;
    private boolean lastFallFlying;
    private boolean lastPassenger;
    private boolean lastBlocking;
    private boolean lastRaining;
    private boolean lastThundering;
    private boolean lastNight;
    private boolean lastCrouching;
    private boolean lastSprinting;
    private boolean lastSwimming;
    private boolean usingItem;
    private int bowUseTicks;
    private int itemUseTicks;
    private int lastFood;
    private int lastAir;
    private int lastXpLevel;
    private int lastTotalXp;
    private int lastHotbarSlot;
    private int airTicks;
    private long tick;
    private double lastHealth;
    private double lastX;
    private double lastY;
    private double lastZ;
    private double airStartY;
    private double highestAirY;
    private double stepAccumulator;
    private boolean fallingWarningSent;
    private String lastDimension = "";
    private String currentUseKind = "";
    private Set<String> lastEffects = Set.of();

    void tick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            initialized = false;
            lastCobblemonHealth.clear();
            return;
        }

        if (!initialized) {
            initialize(player, level);
            emit("session_start", 80, Map.of("dimension", lastDimension));
            return;
        }

        scanVitals(player);
        scanMovement(player);
        scanEnvironment(player, level);
        scanCombatAndTools(mc, player, level);
        scanNearbyThreats(player, level);
        scanEffects(player);
        scanCobblemonEntityDamage(player, level);
        cobblemon.tick(mc, player, level, tick);
        updateLast(player, level);
    }

    private void initialize(LocalPlayer player, ClientLevel level) {
        initialized = true;
        lastHealth = player.getHealth();
        lastFood = player.getFoodData().getFoodLevel();
        lastAir = player.getAirSupply();
        lastXpLevel = player.experienceLevel;
        lastTotalXp = player.totalExperience;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        lastOnGround = player.onGround();
        lastInWater = player.isInWater();
        lastInLava = player.isInLava();
        lastOnFire = player.isOnFire();
        lastSleeping = player.isSleeping();
        lastFallFlying = player.isFallFlying();
        lastPassenger = player.isPassenger();
        lastBlocking = player.isBlocking();
        lastRaining = level.isRaining();
        lastThundering = level.isThundering();
        lastNight = level.isNight();
        lastCrouching = player.isCrouching();
        lastSprinting = player.isSprinting();
        lastSwimming = player.isSwimming();
        lastHotbarSlot = player.getInventory().selected;
        airTicks = 0;
        fallingWarningSent = false;
        lastDimension = level.dimension().location().toString();
        lastEffects = effectKeys(player);
        lastCobblemonHealth.clear();
    }

    private void scanVitals(LocalPlayer player) {
        double health = player.getHealth();
        double maxHealth = Math.max(1.0, player.getMaxHealth());
        if (health < lastHealth - 0.05) {
            String damageKind = damageKind(player.getLastDamageSource());
            emit("damage", 6, Map.of("amount", round(lastHealth - health), "health", round(health), "kind", damageKind));
        }
        if (health > lastHealth + 0.05) {
            emit("heal", 20, Map.of("amount", round(health - lastHealth), "health", round(health)));
        }
        if (health > 0 && health <= maxHealth * 0.3) {
            emit("low_health", 45, Map.of("health", round(health), "maxHealth", round(maxHealth)));
        }
        if (health <= 0 && lastHealth > 0) {
            emit("death", 5, Map.of("health", 0));
        }
        if (health > 0 && lastHealth <= 0) {
            emit("respawn", 20, Map.of("health", round(health)));
        }

        int food = player.getFoodData().getFoodLevel();
        if (food <= 6) {
            emit("hunger_low", 80, Map.of("food", food));
        }
        if (food > lastFood) {
            emit("food_restored", 25, Map.of("food", food));
        }

        int air = player.getAirSupply();
        if (air < 80 && air < lastAir) {
            emit("air_low", 30, Map.of("air", air));
        }

        if (player.experienceLevel > lastXpLevel) {
            emit("level_up", 20, Map.of("level", player.experienceLevel));
        } else if (player.totalExperience > lastTotalXp) {
            emit("xp_gain", 20, Map.of("xp", player.totalExperience - lastTotalXp));
        }
    }

    private void scanMovement(LocalPlayer player) {
        boolean onGround = player.onGround();
        double dx = player.getX() - lastX;
        double dz = player.getZ() - lastZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (onGround && horizontal > 0.01) {
            stepAccumulator += horizontal;
            double threshold = BondCraftConfig.STEP_DISTANCE_CM.get() / 100.0;
            if (stepAccumulator >= threshold) {
                stepAccumulator = 0;
                String stepEvent = player.isSprinting() ? "sprint_step" : stepEvent(player);
                emit(stepEvent, 10, Map.of("speed", round(horizontal * 20), "block", groundBlockName(player)));
            }
        }

        if (lastOnGround && !onGround && player.getDeltaMovement().y > 0.12) {
            airStartY = player.getY();
            highestAirY = player.getY();
            airTicks = 0;
            fallingWarningSent = false;
            emit("jump", 8, Map.of("y", round(player.getY())));
        }
        if (!onGround) {
            airTicks++;
            highestAirY = Math.max(highestAirY, player.getY());
            double dropSoFar = Math.max(0, highestAirY - player.getY());
            if (!fallingWarningSent && airTicks > 12 && dropSoFar > 3.25 && player.getDeltaMovement().y < -0.65) {
                fallingWarningSent = true;
                emit("falling", 45, Map.of("speedY", round(player.getDeltaMovement().y), "drop", round(dropSoFar)));
            }
        }
        if (!lastOnGround && onGround) {
            double drop = Math.max(airStartY, highestAirY) - player.getY();
            if (drop > 3.0) {
                emit("land", 12, Map.of("drop", round(drop)));
            }
            airTicks = 0;
            fallingWarningSent = false;
        }

        if (player.isFallFlying() && !lastFallFlying) {
            emit("elytra_start", 20, Map.of());
        }
        if (!player.isFallFlying() && lastFallFlying) {
            emit("elytra_stop", 20, Map.of());
        }
        if (player.isPassenger() && !lastPassenger) {
            emit("mount", 20, Map.of());
        }
        if (!player.isPassenger() && lastPassenger) {
            emit("dismount", 20, Map.of());
        }
        if (player.isCrouching() && !lastCrouching) {
            emit("crouch_start", 10, Map.of());
        }
        if (!player.isCrouching() && lastCrouching) {
            emit("crouch_stop", 10, Map.of());
        }
        if (player.isSprinting() && !lastSprinting) {
            emit("sprint_start", 10, Map.of());
        }
        if (!player.isSprinting() && lastSprinting) {
            emit("sprint_stop", 10, Map.of());
        }
        if (player.isSwimming() && !lastSwimming) {
            emit("swim_start", 14, Map.of());
        }
        if (!player.isSwimming() && lastSwimming) {
            emit("swim_stop", 14, Map.of());
        }
    }

    private void scanEnvironment(LocalPlayer player, ClientLevel level) {
        if (player.isInWater() && !lastInWater) {
            emit("water_enter", 25, Map.of());
        }
        if (player.isInLava() && !lastInLava) {
            emit("lava_enter", 20, Map.of());
        }
        if (player.isOnFire()) {
            emit("fire", 28, Map.of());
        }
        if (player.isSleeping() && !lastSleeping) {
            emit("sleep", 20, Map.of());
        }

        String dimension = level.dimension().location().toString();
        if (!dimension.equals(lastDimension)) {
            emit("dimension_change", 20, Map.of("dimension", dimension));
        }
        if (level.isRaining() && !lastRaining) {
            emit("weather_rain", 100, Map.of());
        }
        if (level.isThundering() && !lastThundering) {
            emit("weather_thunder", 80, Map.of());
        }
        if (level.isRaining() && player.onGround() && level.canSeeSky(player.blockPosition())) {
            emit("rain_ground", 28, Map.of("block", groundBlockName(player)));
        }
        if (level.isThundering()) {
            emit("thunder_rumble", 180, Map.of());
        }
        if (level.isNight() && !lastNight) {
            emit("night", 120, Map.of());
        }
    }

    private void scanCombatAndTools(Minecraft mc, LocalPlayer player, ClientLevel level) {
        boolean attackDown = mc.options.keyAttack.isDown();
        boolean useDown = mc.options.keyUse.isDown();

        if (attackDown && !lastAttackDown) {
            if (isLookingAtBlock(mc)) {
                emit("mine_start", 4, blockPayload(mc, level));
            } else {
                emit("attack_swing", 6, Map.of("item", itemName(player.getMainHandItem())));
            }
        }
        if (attackDown && isLookingAtBlock(mc)) {
            Map<String, Object> payload = blockPayload(mc, level);
            emit(miningEvent(payload.get("block")), 8, payload);
        }

        Item mainItem = player.getMainHandItem().getItem();
        Item offItem = player.getOffhandItem().getItem();
        boolean rangedItem = mainItem instanceof BowItem || mainItem instanceof CrossbowItem || mainItem instanceof TridentItem
                || offItem instanceof BowItem || offItem instanceof CrossbowItem || offItem instanceof TridentItem;
        if (useDown && rangedItem) {
            bowUseTicks++;
            if (bowUseTicks == 8) {
                emit("bow_charge", 18, Map.of("item", itemName(player.getMainHandItem())));
            }
        }
        if (!useDown && lastUseDown && bowUseTicks > 3) {
            emit("bow_shot", 5, Map.of("chargeTicks", bowUseTicks));
            bowUseTicks = 0;
        }
        if (!useDown) {
            bowUseTicks = 0;
        }

        if (useDown && !lastUseDown) {
            ItemStack stack = player.getMainHandItem();
            ItemStack offhand = player.getOffhandItem();
            String mainItemName = itemName(stack);
            String offhandItemName = itemName(offhand);
            if (isCobblemonBall(mainItemName) || isCobblemonBall(offhandItemName)) {
                emit("cobblemon_ball_throw", 10, Map.of("item", isCobblemonBall(mainItemName) ? mainItemName : offhandItemName));
            }
            if (stack.getItem() instanceof FishingRodItem) {
                emit("fishing_cast", 12, Map.of("item", mainItemName));
            }
        }

        boolean blocking = player.isBlocking() || player.getUseItem().getItem() instanceof ShieldItem;
        if (blocking && !lastBlocking) {
            emit("blocking", 12, Map.of());
        }

        scanItemUse(player);
        if (player.getInventory().selected != lastHotbarSlot) {
            emit("hotbar_change", 3, Map.of("slot", player.getInventory().selected));
        }
        lastAttackDown = attackDown;
        lastUseDown = useDown;
    }

    private void scanItemUse(LocalPlayer player) {
        boolean nowUsing = player.isUsingItem();
        if (nowUsing && !usingItem) {
            currentUseKind = classifyUse(player.getUseItem());
            itemUseTicks = 0;
            if (!currentUseKind.isEmpty()) {
                emit(currentUseKind + "_start", 20, Map.of("item", itemName(player.getUseItem())));
            }
        }
        if (nowUsing) {
            itemUseTicks++;
        }
        if (!nowUsing && usingItem && itemUseTicks > 8 && !currentUseKind.isEmpty()) {
            emit(currentUseKind, 20, Map.of("ticks", itemUseTicks));
        }
        usingItem = nowUsing;
    }

    private String classifyUse(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        if (stack.getFoodProperties(null) != null) {
            return "eating";
        }
        String item = itemName(stack);
        if (item.contains("potion") || item.contains("milk_bucket")) {
            return "drinking";
        }
        return "";
    }

    private void scanNearbyThreats(LocalPlayer player, ClientLevel level) {
        double creeperRadius = BondCraftConfig.CREEPER_RADIUS.get();
        double closestCreeper = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(creeperRadius), e -> e instanceof Creeper && e.isAlive())) {
            closestCreeper = Math.min(closestCreeper, entity.distanceTo(player));
        }
        if (closestCreeper <= 3.2) {
            emit("creeper_close", 10, Map.of("distance", round(closestCreeper)));
        } else if (closestCreeper < Double.MAX_VALUE) {
            emit("creeper_warning", 28, Map.of("distance", round(closestCreeper)));
        }

        double hostileRadius = BondCraftConfig.HOSTILE_RADIUS.get();
        int hostiles = level.getEntities(player, player.getBoundingBox().inflate(hostileRadius),
                e -> e instanceof Enemy && !(e instanceof Creeper) && e.isAlive()).size();
        if (hostiles > 0) {
            emit("hostile_nearby", 45, Map.of("count", hostiles));
        }
    }

    private void scanEffects(LocalPlayer player) {
        Set<String> effects = effectKeys(player);
        for (String effect : effects) {
            if (!lastEffects.contains(effect)) {
                emit("effect_added", 15, Map.of("effect", effect));
            }
        }
        for (String effect : lastEffects) {
            if (!effects.contains(effect)) {
                emit("effect_removed", 15, Map.of("effect", effect));
            }
        }
        lastEffects = effects;
    }

    private void scanCobblemonEntityDamage(LocalPlayer player, ClientLevel level) {
        if (tick % 2 != 0) {
            return;
        }
        Map<Integer, Float> seen = new HashMap<>();
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(48.0), this::isCobblemonEntity)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            int id = living.getId();
            float health = living.getHealth();
            seen.put(id, health);
            Float last = lastCobblemonHealth.get(id);
            if (last == null || health >= last - 0.05f) {
                continue;
            }
            double amount = last - health;
            double percent = Math.min(100.0, amount / Math.max(1.0, living.getMaxHealth()) * 100.0);
            emit("cobblemon_damage", 12, Map.of(
                    "pokemon", entityName(entity),
                    "amount", round(amount),
                    "percent", round(percent),
                    "distance", round(entity.distanceTo(player))
            ));
        }
        lastCobblemonHealth.keySet().removeIf(id -> !seen.containsKey(id));
        lastCobblemonHealth.putAll(seen);
    }

    private Set<String> effectKeys(LocalPlayer player) {
        return player.getActiveEffects().stream()
                .map(MobEffectInstance::getDescriptionId)
                .collect(Collectors.toSet());
    }

    private boolean isLookingAtBlock(Minecraft mc) {
        return mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
    }

    private Map<String, Object> blockPayload(Minecraft mc, ClientLevel level) {
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) {
            return Map.of();
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        return Map.of(
                "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                "x", pos.getX(),
                "y", pos.getY(),
                "z", pos.getZ()
        );
    }

    private String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private String groundBlockName(LocalPlayer player) {
        BlockState state = player.level().getBlockState(player.blockPosition().below());
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private String stepEvent(LocalPlayer player) {
        String block = groundBlockName(player);
        if (containsAny(block, "iron", "gold", "copper", "chain", "anvil", "rail", "metal")) {
            return "step_metal";
        }
        if (containsAny(block, "wood", "log", "planks", "stem", "hyphae", "bamboo")) {
            return "step_wood";
        }
        if (containsAny(block, "sand")) {
            return "step_sand";
        }
        if (containsAny(block, "gravel")) {
            return "step_gravel";
        }
        if (containsAny(block, "snow", "ice")) {
            return "step_snow";
        }
        if (containsAny(block, "grass", "leaves", "moss", "vine")) {
            return "step_grass";
        }
        if (containsAny(block, "stone", "deepslate", "netherrack", "basalt", "blackstone", "brick", "concrete")) {
            return "step_stone";
        }
        return "step";
    }

    private String miningEvent(Object blockValue) {
        String block = String.valueOf(blockValue);
        if (containsAny(block, "ore", "ancient_debris")) {
            return "mining_ore";
        }
        if (containsAny(block, "wood", "log", "planks", "stem", "hyphae", "bamboo")) {
            return "mining_wood";
        }
        if (containsAny(block, "dirt", "mud", "clay", "farmland", "podzol")) {
            return "mining_dirt";
        }
        if (containsAny(block, "sand")) {
            return "mining_sand";
        }
        if (containsAny(block, "gravel")) {
            return "mining_gravel";
        }
        if (containsAny(block, "stone", "deepslate", "netherrack", "basalt", "blackstone", "brick", "concrete")) {
            return "mining_stone";
        }
        return "mining";
    }

    private boolean isCobblemonBall(String item) {
        String clean = item.toLowerCase();
        return clean.startsWith("cobblemon:") && clean.contains("ball");
    }

    private boolean isCobblemonEntity(Entity entity) {
        String key = entityName(entity).toLowerCase();
        return entity.isAlive() && key.startsWith("cobblemon:");
    }

    private String entityName(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String damageKind(Object source) {
        if (source == null) {
            return "";
        }
        String value = invokeString(source, "getMsgId");
        if (!value.isEmpty()) {
            return value;
        }
        try {
            Object type = source.getClass().getMethod("type").invoke(source);
            value = invokeString(type, "msgId");
            if (!value.isEmpty()) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return String.valueOf(source);
    }

    private String invokeString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void emit(String event, int cooldownTicks, Map<String, Object> extra) {
        long last = lastEventTicks.getOrDefault(event, -10_000L);
        if (tick - last < cooldownTicks) {
            return;
        }
        lastEventTicks.put(event, tick);
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("tick", tick);
        bridge.send(event, payload);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void updateLast(LocalPlayer player, ClientLevel level) {
        lastHealth = player.getHealth();
        lastFood = player.getFoodData().getFoodLevel();
        lastAir = player.getAirSupply();
        lastXpLevel = player.experienceLevel;
        lastTotalXp = player.totalExperience;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        lastOnGround = player.onGround();
        lastInWater = player.isInWater();
        lastInLava = player.isInLava();
        lastOnFire = player.isOnFire();
        lastSleeping = player.isSleeping();
        lastFallFlying = player.isFallFlying();
        lastPassenger = player.isPassenger();
        lastBlocking = player.isBlocking();
        lastRaining = level.isRaining();
        lastThundering = level.isThundering();
        lastNight = level.isNight();
        lastCrouching = player.isCrouching();
        lastSprinting = player.isSprinting();
        lastSwimming = player.isSwimming();
        lastHotbarSlot = player.getInventory().selected;
        lastDimension = level.dimension().location().toString();
    }
}
