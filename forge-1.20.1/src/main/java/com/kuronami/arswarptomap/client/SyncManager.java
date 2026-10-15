package com.kuronami.arswarptomap.client;

import com.kuronami.arswarptomap.compat.jm.JourneyMapBridge;
import com.kuronami.arswarptomap.sync.PointSnapshot;
import com.kuronami.arswarptomap.sync.SyncPlan;
import com.kuronami.arswarptomap.sync.WaypointDiffer;
import com.kuronami.arswarptomap.sync.WaypointPlanner;

import net.minecraft.client.Minecraft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * クライアント側の同期状態機械。今インベントリにある巻物の行き先全量を「あるべき状態」
 * として保持し、JourneyMap へ差分適用する。
 *
 * <p>セマンティクス: 片方向ミラー（インベントリの巻物 → ウェイポイント集合）。</p>
 *
 * <p>セッション開始の扱い: ウェイポイントは永続（JourneyMap の保存データに残る）ため、
 * セッション最初の適用では {@code removeAllWaypoints} → 現在の行き先で全件再構築して
 * 重複蓄積を防ぐ。2 回目以降は差分適用（add / update=remove+add / remove）。</p>
 *
 * <p>JourneyMap API がまだ初期化されていない間に走査しても、dirty フラグで保持しておき
 * tick ループから再試行する（取りこぼし無し）。</p>
 */
public final class SyncManager {

    /** 最後に JourneyMap へ反映した地点集合（キー = 行き先キー）。 */
    private static final Map<UUID, PointSnapshot> TRACKED = new LinkedHashMap<>();

    /** 今のインベントリから算出した最新のあるべき状態。 */
    private static Map<UUID, PointSnapshot> desired = null;

    /** desired の未適用フラグ。 */
    private static boolean dirty = false;

    /** 今セッションで JourneyMap 側を一度全再構築済みか。 */
    private static boolean rebuiltThisSession = false;

    private SyncManager() {
    }

    /**
     * インベントリを走査してあるべき状態を更新する（main thread・定期呼び出し）。
     * 内容が変わらなければ何もしない。
     */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Map<UUID, PointSnapshot> next = WaypointPlanner.plan(InventoryScrollReader.scan(mc.player));
        if (next.equals(desired)) {
            return; // 内容同一（順序違いを含む）なら JourneyMap に触らず済む
        }
        desired = next;
        dirty = true;
    }

    /**
     * 未適用の状態があれば JourneyMap へ適用する。JourneyMap 不在・API 未初期化・失敗時は
     * 静かに何もせず次回再試行。
     *
     * @return 実際に何か適用したら true
     */
    public static boolean flushPending() {
        if (!dirty || desired == null) {
            return false;
        }
        if (!rebuiltThisSession) {
            if (!JourneyMapBridge.replaceAll(desired)) {
                return false; // API 未初期化 → 次サイクルで再試行
            }
            commit();
            return true;
        }
        SyncPlan plan = WaypointDiffer.plan(desired, TRACKED);
        if (plan.isEmpty()) {
            commit();
            return false;
        }
        if (!JourneyMapBridge.apply(plan)) {
            return false;
        }
        commit();
        com.kuronami.arswarptomap.ArsWarpToMap.LOGGER.debug(
                "Synced {} upsert(s) / {} removal(s) to JourneyMap",
                plan.upserts().size(), plan.removals().size());
        return true;
    }

    /** dirty 状態か（テスト・デバッグ用）。 */
    public static boolean hasPending() {
        return dirty;
    }

    private static void commit() {
        TRACKED.clear();
        TRACKED.putAll(desired);
        dirty = false;
        rebuiltThisSession = true;
    }

    /**
     * ワールド接続/切断の境界でのリセット。JourneyMap 上のウェイポイントは永続データなので
     * 削除しに行かない（次の走査で作り直す）。保持している実体参照だけ捨てる。
     */
    public static void resetSession() {
        TRACKED.clear();
        desired = null;
        dirty = false;
        rebuiltThisSession = false;
        JourneyMapBridge.forgetSession();
    }
}
