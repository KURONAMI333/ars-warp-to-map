package com.kuronami.arswarptomap.compat.jm;

import com.kuronami.arswarptomap.ArsWarpToMap;
import com.kuronami.arswarptomap.sync.PointSnapshot;
import com.kuronami.arswarptomap.sync.SyncPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;

import net.neoforged.fml.ModList;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JourneyMap への差分プラン適用。JM 不在時に NoClassDefFoundError を起こさないよう、
 * JM API への参照はすべてこのクラス（と Inner）に閉じ込める。
 *
 * <p>更新は「同一キーの remove + add」。行き先キーを追跡キーとして自前の live マップで
 * Waypoint 実体を保持する（removeWaypoint は実体参照を取るため）。</p>
 */
public final class JourneyMapBridge {

    /** キー = 行き先キー。JM へ渡した Waypoint 実体の保持用。 */
    private static final Map<UUID, Waypoint> LIVE = new HashMap<>();

    private JourneyMapBridge() {
    }

    public static boolean isJourneyMapLoaded() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("journeymap");
    }

    /**
     * 差分プランを JM へ適用する。
     *
     * @return 適用が完了したら true（JM 不在・API 未初期化・例外時は false）
     */
    public static boolean apply(SyncPlan plan) {
        if (!isJourneyMapLoaded()) {
            return false;
        }
        try {
            return Inner.apply(plan);
        } catch (Throwable t) {
            ArsWarpToMap.LOGGER.warn("JourneyMap waypoint apply failed: {}", t.toString());
            return false;
        }
    }

    /**
     * セッション開始時の全再構築。前セッションで永続化された自 MOD ウェイポイントを
     * 一度すべて消してから現在の行き先集合を積み直す（重複蓄積の防止）。
     *
     * @param desired 現在の行き先集合（空でもよい＝全部消えるのが正）
     * @return 完了したら true
     */
    public static boolean replaceAll(Map<UUID, PointSnapshot> desired) {
        if (!isJourneyMapLoaded()) {
            return false;
        }
        try {
            return Inner.replaceAll(desired);
        } catch (Throwable t) {
            ArsWarpToMap.LOGGER.warn("JourneyMap waypoint rebuild failed: {}", t.toString());
            return false;
        }
    }

    /**
     * ワールド切断時に呼ぶ。JM 上の永続ウェイポイントには触れず、
     * 自分の保持している実体参照だけを捨てる。
     */
    public static void forgetSession() {
        LIVE.clear();
    }

    private static final class Inner {

        static boolean replaceAll(Map<UUID, PointSnapshot> desired) {
            IClientAPI api = AwtmJourneyMapPlugin.api;
            if (api == null) {
                ArsWarpToMap.LOGGER.debug("JourneyMap API not yet initialized, skipping session rebuild");
                return false;
            }
            api.removeAllWaypoints(ArsWarpToMap.MODID);
            LIVE.clear();
            List<PointSnapshot> ordered = desired.values().stream()
                    .sorted(Comparator.comparing(PointSnapshot::id))
                    .toList();
            for (PointSnapshot snapshot : ordered) {
                Waypoint wp = createWaypoint(snapshot);
                LIVE.put(snapshot.id(), wp);
                api.addWaypoint(ArsWarpToMap.MODID, wp);
            }
            ArsWarpToMap.LOGGER.info("Rebuilt {} Ars warp waypoint(s) on JourneyMap", ordered.size());
            return true;
        }

        static boolean apply(SyncPlan plan) {
            IClientAPI api = AwtmJourneyMapPlugin.api;
            if (api == null) {
                // JM は居るがまだプラグイン初期化前 → 静かに skip（次サイクルで再試行）
                ArsWarpToMap.LOGGER.debug("JourneyMap API not yet initialized, skipping sync cycle");
                return false;
            }

            for (UUID id : plan.removals()) {
                Waypoint wp = LIVE.remove(id);
                if (wp != null) {
                    api.removeWaypoint(ArsWarpToMap.MODID, wp);
                }
            }

            for (PointSnapshot snapshot : plan.upserts()) {
                UUID id = snapshot.id();
                Waypoint previous = LIVE.remove(id);
                if (previous != null) {
                    api.removeWaypoint(ArsWarpToMap.MODID, previous);
                }
                Waypoint wp = createWaypoint(snapshot);
                LIVE.put(id, wp);
                api.addWaypoint(ArsWarpToMap.MODID, wp);
            }
            return true;
        }

        static Waypoint createWaypoint(PointSnapshot s) {
            ResourceKey<Level> dimension = ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse(s.dimensionId()));
            // 永続 waypoint。行き先キーを追跡し、名前・座標・次元・色をそのまま反映する。
            Waypoint wp = WaypointFactory.createClientWaypoint(
                    ArsWarpToMap.MODID,
                    new BlockPos(s.x(), s.y(), s.z()),
                    s.name(),
                    dimension,
                    true);
            wp.setColor(s.rgb());
            return wp;
        }
    }
}
