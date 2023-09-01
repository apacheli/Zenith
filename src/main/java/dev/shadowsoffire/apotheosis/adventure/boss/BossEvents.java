package dev.shadowsoffire.apotheosis.adventure.boss;

import com.mojang.serialization.Codec;
import dev.shadowsoffire.apotheosis.Apotheosis;
import dev.shadowsoffire.apotheosis.adventure.AdventureConfig;
import dev.shadowsoffire.apotheosis.adventure.AdventureModule;
import dev.shadowsoffire.apotheosis.adventure.boss.MinibossRegistry.IEntityMatch;
import dev.shadowsoffire.apotheosis.adventure.client.BossSpawnMessage;
import dev.shadowsoffire.apotheosis.adventure.compat.GameStagesCompat.IStaged;
import dev.shadowsoffire.placebo.codec.PlaceboCodecs;
import dev.shadowsoffire.placebo.network.PacketDistro;
import dev.shadowsoffire.placebo.reload.WeightedDynamicRegistry.IDimensional;
import io.github.fabricators_of_create.porting_lib.entity.events.living.LivingEntityEvents;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.function.BiPredicate;

public class BossEvents {

    public Object2IntMap<ResourceLocation> bossCooldowns = new Object2IntOpenHashMap<>();

    public static void init() {

    }

    public void naturalBosses() {
        LivingEntityEvents.NATURAL_SPAWN.register((mob, x, y, z, level, spawner, type) -> {
            if (type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION) {
                RandomSource rand = level.getRandom();
                if (this.bossCooldowns.getInt(mob.level().dimension().location()) <= 0 && !level.isClientSide() && mob instanceof Monster/* && e.getResult() != Result.DENY*/) {
                    ServerLevelAccessor sLevel = (ServerLevelAccessor) level;
                    Pair<Float, BossSpawnRules> rules = AdventureConfig.BOSS_SPAWN_RULES.get(sLevel.getLevel().dimension().location());
                    if (rules == null) return TriState.DEFAULT;
                    if (rand.nextFloat() <= rules.getLeft() && rules.getRight().test(sLevel, BlockPos.containing(x, y, z))) {
                        Player player = sLevel.getNearestPlayer(x, y, z, -1, false);
                        if (player == null) return TriState.DEFAULT; // Spawns require player context
                        ApothBoss item = BossRegistry.INSTANCE.getRandomItem(rand, player.getLuck(), IDimensional.matches(sLevel.getLevel()), IStaged.matches(player));
                        Mob boss = item.createBoss(sLevel, BlockPos.containing(x - 0.5, y, z - 0.5), rand, player.getLuck());
                        if (AdventureConfig.bossAutoAggro && !player.isCreative()) {
                            boss.setTarget(player);
                        }
                        if (canSpawn(sLevel, boss, player.distanceToSqr(boss))) {
                            sLevel.addFreshEntityWithPassengers(boss);
                            //e.setResult(Result.DENY);
                            AdventureModule.debugLog(boss.blockPosition(), "Surface Boss - " + boss.getName().getString());
                            Component name = this.getName(boss);
                            if (name == null || name.getStyle().getColor() == null) AdventureModule.LOGGER.warn("A Boss {} ({}) has spawned without a custom name!", boss.getName().getString(), EntityType.getKey(boss.getType()));
                            else {
                                sLevel.players().forEach(p -> {
                                    Vec3 tPos = new Vec3(boss.getX(), AdventureConfig.bossAnnounceIgnoreY ? p.getY() : boss.getY(), boss.getZ());
                                    if (p.distanceToSqr(tPos) <= AdventureConfig.bossAnnounceRange * AdventureConfig.bossAnnounceRange) {
                                        ((ServerPlayer) p).connection.send(new ClientboundSetActionBarTextPacket(Component.translatable("info.apotheosis.boss_spawn", name, (int) boss.getX(), (int) boss.getY())));
                                        TextColor color = name.getStyle().getColor();
                                        //PacketDistro.sendTo(Apotheosis.CHANNEL, new BossSpawnMessage(boss.blockPosition(), color == null ? 0xFFFFFF : color.getValue()), player);
                                        //TODO bossspawnmessage packet handler
                                    }
                                });
                            }
                            this.bossCooldowns.put(mob.level().dimension().location(), AdventureConfig.bossSpawnCooldown);
                            return TriState.FALSE;
                        }
                    }
                }
            }
            return TriState.DEFAULT;
        });

    }

    @Nullable
    private Component getName(Mob boss) {
        return boss.getSelfAndPassengers().filter(e -> e.getCustomData().contains("apoth.boss")).findFirst().map(Entity::getCustomName).orElse(null);
    }

    public void minibosses() {
        LivingEntityEvents.NATURAL_SPAWN.register((mob, x, y, z, level, spawner, type) -> {
            LivingEntity entity = mob;
            RandomSource rand = level.getRandom();
            if (!level.isClientSide() && entity != null) {
                ServerLevelAccessor sLevel = (ServerLevelAccessor) level;
                Player player = sLevel.getNearestPlayer(x, y, z, -1, false);
                if (player == null) return TriState.DEFAULT; // Spawns require player context
                ApothMiniboss item = MinibossRegistry.INSTANCE.getRandomItem(rand, player.getLuck(), IDimensional.matches(sLevel.getLevel()), IStaged.matches(player), IEntityMatch.matches(entity));
                if (item != null && !item.isExcluded(mob, sLevel, type) && sLevel.getRandom().nextFloat() <= item.getChance()) {
                    mob.getCustomData().putString("apoth.miniboss", item.getId().toString());
                    mob.getCustomData().putFloat("apoth.miniboss.luck", player.getLuck());
                    if (!item.shouldFinalize()) return TriState.FALSE;
                }
            }
            return TriState.DEFAULT;
        });

    }

    public void delayedMinibosses() {
        LivingEntityEvents.NATURAL_SPAWN.register((mob, x, y, z, level, spawner, type) -> {
            if (!level.isClientSide()) {
                String key = mob.getCustomData().getString("apoth.miniboss");
                if (key != null) {
                    ApothMiniboss item = MinibossRegistry.INSTANCE.getValue(new ResourceLocation(key));
                    if (item != null) {
                        item.transformMiniboss((ServerLevel) level, mob, level.getRandom(), mob.getCustomData().getFloat("apoth.miniboss.luck"));
                    }
                }
            }
            return null;
        });

    }

    public void tick() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            this.bossCooldowns.computeIntIfPresent(world.dimension().location(), (key, value) -> Math.max(0, value - 1));
        });

    }

    public void load() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            server.getLevel(Level.OVERWORLD).getDataStorage().computeIfAbsent(this::loadTimes, TimerPersistData::new, "apotheosis_boss_times");
        });

    }

    private class TimerPersistData extends SavedData {

        @Override
        public CompoundTag save(CompoundTag tag) {
            for (Object2IntMap.Entry<ResourceLocation> e : BossEvents.this.bossCooldowns.object2IntEntrySet()) {
                tag.putInt(e.getKey().toString(), e.getIntValue());
            }
            return tag;
        }

    }

    private TimerPersistData loadTimes(CompoundTag tag) {
        this.bossCooldowns.clear();
        for (String s : tag.getAllKeys()) {
            ResourceLocation id = new ResourceLocation(s);
            int val = tag.getInt(s);
            this.bossCooldowns.put(id, val);
        }
        return new TimerPersistData();
    }

    private static boolean canSpawn(LevelAccessor world, Mob entity, double playerDist) {
        if (playerDist > entity.getType().getCategory().getDespawnDistance() * entity.getType().getCategory().getDespawnDistance() && entity.removeWhenFarAway(playerDist)) {
            return false;
        }
        else {
            return entity.checkSpawnRules(world, MobSpawnType.NATURAL) && entity.checkSpawnObstruction(world);
        }
    }

    public static enum BossSpawnRules implements BiPredicate<ServerLevelAccessor, BlockPos> {
        NEEDS_SKY(ServerLevelAccessor::canSeeSky),
        NEEDS_SURFACE(
            (level, pos) -> pos.getY() >= level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())),
        BELOW_SURFACE(
            (level, pos) -> pos.getY() < level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())),
        CANNOT_SEE_SKY((level, pos) -> !level.canSeeSky(pos)),
        SURFACE_OUTER_END(
            (level, pos) -> NEEDS_SURFACE.test(level, pos) && (Mth.abs(pos.getX()) > 1024 || Mth.abs(pos.getZ()) > 1024)),
        ANY((level, pos) -> true);

        public static final Codec<BossSpawnRules> CODEC = PlaceboCodecs.enumCodec(BossSpawnRules.class);

        BiPredicate<ServerLevelAccessor, BlockPos> pred;

        private BossSpawnRules(BiPredicate<ServerLevelAccessor, BlockPos> pred) {
            this.pred = pred;
        }

        @Override
        public boolean test(ServerLevelAccessor t, BlockPos u) {
            return this.pred.test(t, u);
        }
    }

}