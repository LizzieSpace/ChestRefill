package org.samo_lego.chestrefill.mixin;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

import static org.samo_lego.chestrefill.ChestRefill.config;
import static org.samo_lego.chestrefill.PlatformHelper.hasPermission;


/**
 * RandomizableContainerBEMixin_LootRefiller is a mixin class that implements the RandomizableContainer interface for
 * BlockEntities (BEs) that can have loot tables and can be refilled with loot.
 * It provides methods to refill the container's loot table, save and load the loot table, and modify the refill behavior of the container.
 * The class relies on a configuration file to customize the refill behavior of each specific loot table and each container.
 * This class should not be instantiated directly, instead, it should be mixed into specific BE classes that implement the RandomizableContainer interface.
 * <p>
 * This class uses mixin annotations to redirect and inject methods from the original class.
 *
 * @remarks This documentation is generated automatically and may contain errors or inconsistencies.
 */
@SuppressWarnings("AddedMixinMembersNamePattern")
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class RandomizableContainerBEMixin_LootRefiller implements RandomizableContainer {

    @Shadow
    @Nullable
    protected ResourceKey<LootTable> lootTable;

    @Unique
    private ResourceKey<LootTable> savedLootTable;

    @Shadow
    protected long lootTableSeed;

    @Shadow
    public abstract void setLootTable(ResourceKey<LootTable> resourceKey);

    @Unique
    private final Set<String> lootedUUIDs = new HashSet<>();

    @Shadow
    public abstract void setLootTableSeed(long l);

    @Unique
    private long savedLootTableSeed, lastRefillTime, minWaitTime;

    @Unique
    private boolean allowRelootByDefault, randomizeLootSeed, refillFull, hadCustomData;

    @Unique
    private int refillCounter, maxRefills;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.maxRefills = config.defaultProperties.maxRefills;
        this.refillFull = config.defaultProperties.refillFull;
        this.randomizeLootSeed = config.defaultProperties.randomizeLootSeed;
        this.allowRelootByDefault = config.defaultProperties.allowRelootByDefault;
        this.minWaitTime = config.defaultProperties.minWaitTime;

        this.refillCounter = 0;
        this.lastRefillTime = 0;
        this.savedLootTableSeed = 0L;
        this.hadCustomData = false;
    }

    @Redirect(method = "isEmpty", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/RandomizableContainerBlockEntity;unpackLootTable(Lnet/minecraft/world/entity/player/Player;)V"))
    private void onIsEmpty(RandomizableContainerBlockEntity randomizableContainerBlockEntity, Player player) {
        call_unpackLootTable(player);
    }

    @Shadow
    public abstract boolean isEmpty();

    @Unique
    public void unpackLootTable(@Nullable Player player) {
        refillLootTable(player);
        call_unpackLootTable(player);
    }

    /**
     * Unpacks the loot table of the container and fills it with items.
     *
     * @param player The player opening the container. Can be null.
     */
    @Unique
    private void call_unpackLootTable(@Nullable Player player) {
        Level level = this.getLevel();
        BlockPos blockPos = this.getBlockPos();
        ResourceKey<LootTable> resourceKey = this.getLootTable();
        if (resourceKey != null && level != null && level.getServer() != null) {
            LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(resourceKey);
            if (player instanceof ServerPlayer) {
                CriteriaTriggers.GENERATE_LOOT.trigger((ServerPlayer) player, resourceKey);
            }

            this.setLootTable(null);
            LootParams.Builder builder = (new LootParams.Builder((ServerLevel) level)).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(blockPos));
            if (player != null) {
                builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
            }

            lootTable.fill(this, builder.create(LootContextParamSets.CHEST), this.getLootTableSeed());
        }

    }

    /**
     * Tries to save the loot table information to a CompoundTag.
     *
     * @param compoundTag The CompoundTag to save the loot table information to.
     * @return true if the loot table was successfully saved, false otherwise.
     */
    @Unique
    private boolean call_trySaveLootTable(CompoundTag compoundTag) {
        ResourceKey<LootTable> resourceKey = this.getLootTable();
        if (resourceKey == null) {
            return false;
        } else {
            compoundTag.putString("LootTable", resourceKey.location().toString());
            long l = this.getLootTableSeed();
            if (l != 0L) {
                compoundTag.putLong("LootTableSeed", l);
            }

            return true;
        }
    }

    /**
     * Refills the loot table of the container.
     *
     * @param player The player opening the container. Can be null.
     */
    @Unique
    private void refillLootTable(@Nullable Player player) {
        if (player != null) {
            if (this.lootTable == null && this.savedLootTable != null) {
                boolean empty = isEmpty() || this.refillFull;
                if (empty && this.canRefillFor(player)) {
                    this.lootedUUIDs.add(player.getStringUUID());
                    // Refilling for player
                    this.setLootTable(this.savedLootTable);
                    this.setLootTableSeed(this.randomizeLootSeed ? player.getRandom().nextLong() : this.savedLootTableSeed);
                    this.lastRefillTime = System.currentTimeMillis();
                    ++refillCounter;
                }
            } else {
                // Original loot
                this.lastRefillTime = System.currentTimeMillis();
                this.lootedUUIDs.add(player.getStringUUID());

                if (this.lootTable != null) {
                    this.savedLootTable = this.lootTable;
                    this.savedLootTableSeed = this.lootTableSeed;

                }
            }
        }

    }

    /**
     * Tries to load the loot table from the given compound tag and sets it to the container.
     *
     * @param compoundTag The compound tag containing the loot table information.
     * @return true if the loot table was successfully loaded and set, false otherwise.
     */
    @Unique
    public boolean tryLoadLootTable(CompoundTag compoundTag) {
        if (compoundTag.contains("LootTable", 8)) {
            this.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(compoundTag.getString("LootTable"))));
            if (compoundTag.contains("LootTableSeed", 4)) {
                this.setLootTableSeed(compoundTag.getLong("LootTableSeed"));
            } else {
                this.setLootTableSeed(0L);
            }
            onLootTableLoad(compoundTag);
            return true;
        } else {
            onLootTableLoad(compoundTag);
            return false;
        }
    }

    /**
     * Loads the loot table from the given compound tag and performs various operations based on the tag contents and configs.
     *
     * @param compoundTag The compound tag containing the loot table information.
     */
    @Unique
    private void onLootTableLoad(CompoundTag compoundTag) {
        CompoundTag refillTag = compoundTag.getCompound("ChestRefill");
        if (!refillTag.isEmpty()) {
            // Has been looted already but has saved loot table
            this.savedLootTable = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(refillTag.getString("SavedLootTable")));
            this.savedLootTableSeed = refillTag.getLong("SavedLootTableSeed");

            this.refillCounter = refillTag.getInt("RefillCounter");
            this.lastRefillTime = refillTag.getLong("LastRefillTime");

            ListTag lootedUUIDsTag = (ListTag) refillTag.get("LootedUUIDs");
            if(lootedUUIDsTag != null) {
                lootedUUIDsTag.forEach(tag -> this.lootedUUIDs.add(tag.getAsString()));
            }

            // Per loot table customization
            var modifiers = config.lootModifierMap.get(this.savedLootTable.toString());
            if (modifiers == null) {
                modifiers = config.lootModifierMap.get(this.savedLootTable.registry().getPath());
            }

            if(modifiers != null) {
                // This loot table has special values set
                this.randomizeLootSeed = modifiers.randomizeLootSeed;
                this.refillFull = modifiers.refillFull;
                this.allowRelootByDefault = modifiers.allowRelootByDefault;
                this.maxRefills = modifiers.maxRefills;
                this.minWaitTime = modifiers.minWaitTime;
            }

            // Per-chest customization
            CompoundTag customValues = refillTag.getCompound("CustomValues");
            if(!customValues.isEmpty()) {
                this.hadCustomData = true;
                this.randomizeLootSeed = customValues.getBoolean("RandomizeLootSeed");
                this.refillFull = customValues.getBoolean("RefillNonEmpty");
                this.allowRelootByDefault = customValues.getBoolean("AllowReloot");
                this.maxRefills = customValues.getInt("MaxRefills");
                this.minWaitTime = customValues.getLong("MinWaitTime");
            }
        } else if (this.lootTable != null) {
            this.savedLootTable = this.lootTable;
            this.savedLootTableSeed = this.lootTableSeed;
        }
    }

    /**
     * Tries to save the loot table information to a CompoundTag.
     *
     * @param compoundTag The CompoundTag to save the loot table information to.
     * @return true if the loot table was successfully saved, false otherwise.
     */
    @Unique
    public boolean trySaveLootTable(CompoundTag compoundTag) {
        onLootTableSave(compoundTag);
        return call_trySaveLootTable(compoundTag);
    }

    /**
     * Saves the loot table information to a CompoundTag.
     *
     * @param compoundTag The CompoundTag to save the loot table information to.
     */
    @Unique
    private void onLootTableSave(CompoundTag compoundTag) {
        if (this.lootTable == null && this.savedLootTable != null) {
            // Save only if chest was looted (if there's no more original loot table)
            CompoundTag refillTag = new CompoundTag();

            refillTag.putString("SavedLootTable", this.savedLootTable.toString());
            refillTag.putLong("SavedLootTableSeed", this.savedLootTableSeed);
            refillTag.putInt("RefillCounter", this.refillCounter);
            refillTag.putLong("LastRefillTime", this.lastRefillTime);

            ListTag lootedUUIDsTag = new ListTag();
            this.lootedUUIDs.forEach(uuid -> lootedUUIDsTag.add(StringTag.valueOf(uuid)));
            refillTag.put("LootedUUIDs", lootedUUIDsTag);

            // Allows per-chest customization
            if (this.hadCustomData) {
                CompoundTag customValues = new CompoundTag();

                customValues.putBoolean("RandomizeLootSeed", this.randomizeLootSeed);
                customValues.putBoolean("RefillNonEmpty", this.refillFull);
                customValues.putBoolean("AllowReloot", this.allowRelootByDefault);
                customValues.putInt("MaxRefills", this.maxRefills);
                customValues.putLong("MinWaitTime", this.minWaitTime);
                refillTag.put("CustomValues", customValues);
            }

            compoundTag.put("ChestRefill", refillTag);
        }
    }

    /**
     * Whether container can be refilled for given player.
     * @param player player to check refilling for.
     * @return true if refilling can happen, otherwise false.
     */
    @Unique
    private boolean canRefillFor(Player player) {
        boolean relootPermission = hasPermission(player.createCommandSourceStack(), "chestrefill.allowReloot", this.allowRelootByDefault) || !this.lootedUUIDs.contains(player.getStringUUID());
        return this.canStillRefill() && this.hasEnoughTimePassed() && relootPermission;
    }


    /**
     * Whether this container hasn't reached max refills yet.
     * @return true if container can still be refilled, false if refills is more than max refills.
     */
    @Unique
    private boolean canStillRefill() {
        return this.refillCounter < this.maxRefills || this.maxRefills == -1;
    }

    /**
     * Tells whether enough time has passed since previous refill.
     * @return true if container can already be refilled, otherwise false.
     */
    @Unique
    private boolean hasEnoughTimePassed() {
        // * 1000 as seconds are used in config.
        return System.currentTimeMillis() - this.lastRefillTime > this.minWaitTime * 1000;
    }
}
