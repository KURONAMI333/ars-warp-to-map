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

import net.minecraftforge.fml.ModList;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JourneyMap への差分プラン適用（Forge 1.20.1）。JM 不在時に NoClassDefFoundError を
 * 起こさないよう、JM API への参照はすべてこのクラス（と Inner）に閉じ込める。
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
            int created = 0;
            for (PointSnapshot snapshot : ordered) {
                Waypoint wp = createWaypoint(snapshot);
                if (wp == null) {
                    continue;
                }
                LIVE.put(snapshot.id(), wp);
                api.addWaypoint(ArsWarpToMap.MODID, wp);
                created++;
            }
            ArsWarpToMap.LOGGER.info("Rebuilt {} Ars warp waypoint(s) on JourneyMap", created);
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
                Waypoint wp = createWaypoint(snapshot);
                if (wp == null) {
                    continue; // 次元が壊れた巻物。既存の実体は消さずそのまま残す
                }
                Waypoint previous = LIVE.remove(id);
                if (previous != null) {
                    api.removeWaypoint(ArsWarpToMap.MODID, previous);
                }
                LIVE.put(id, wp);
                api.addWaypoint(ArsWarpToMap.MODID, wp);
            }
            return true;
        }

        /** 行き先の次元が ResourceLocation として解釈できない巻物は null を返す（この 1 件だけ捨てる）。 */
        static Waypoint createWaypoint(PointSnapshot s) {
            // 1.20.1 の srg jar には ResourceLocation.parse が無い版がある（tryParse は全版にある）
            ResourceLocation location = ResourceLocation.tryParse(s.dimensionId());
            if (location == null) {
                ArsWarpToMap.LOGGER.debug("Skipping waypoint with unreadable dimension: {}", s.dimensionId());
                return null;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, location);
            // 永続 waypoint。1.20.1 の v2 API には createClientWaypoint が無く createWaypoint のみ
            // （引数順は 1.21.1 の createClientWaypoint と同一・javap 突合済み）
            Waypoint wp = WaypointFactory.createWaypoint(
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
