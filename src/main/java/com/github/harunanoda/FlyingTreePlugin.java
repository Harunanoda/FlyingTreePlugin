package com.github.harunanoda;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class FlyingTreePlugin extends JavaPlugin implements Listener {

    private static final int MAX_TREE_SIZE = 500;

    @Override
    public void onEnable() {
        // プラグイン起動時の処理
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FlyingTreePlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        // プラグイン終了時の処理
    }

    @EventHandler // これがないとイベントが動きません
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {

        // 1. 左クリック（殴る動作）以外は無視
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null)
            return;

        Material type = clickedBlock.getType();

        // 2. 原木(LOGS) または 葉っぱ(LEAVES) かチェック
        boolean isLog = Tag.LOGS.isTagged(type);
        boolean isLeaves = Tag.LEAVES.isTagged(type);

        if (isLog || isLeaves) {
            // 3. 通常の破壊をキャンセル
            event.setCancelled(true);

            // 4. 木のブロックを探索 (BFS)
            Set<Block> treeBlocks = findTreeBlocks(clickedBlock);

            // 5. まとめて飛ばす
            Vector velocity = new Vector(0, 2.5, 0);
            for (Block block : treeBlocks) {
                // その場所に落下ブロックを召喚
                FallingBlock fallingBlock = block.getWorld().spawnFallingBlock(
                        block.getLocation().add(0.5, 0, 0.5),
                        block.getBlockData());

                // 元のブロックを消す
                block.setType(Material.AIR);

                // 真上に吹っ飛ばす
                fallingBlock.setVelocity(velocity);

                // アイテム化しない設定（EntityChangeBlockEventで手動でドロップするため）
                fallingBlock.setDropItem(false);
            }
        }
    }

    /**
     * 指定されたブロックから接続されている原木と、その周囲の葉っぱを探索します。
     * 1. 葉っぱクリック時は最寄りの幹を探す
     * 2. 幹（原木）をすべて特定する
     * 3. 幹の周囲の葉っぱを集める際、他の幹に近いものは除外する
     */
    private Set<Block> findTreeBlocks(Block startBlock) {
        Set<Block> logs = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();

        // 0. 起点調整：葉っぱなら近くの原木を探す
        Block origin = startBlock;
        if (Tag.LEAVES.isTagged(startBlock.getType())) {
            Block nearestLog = findNearestLog(startBlock);
            if (nearestLog != null) {
                origin = nearestLog;
            } else {
                // 近くに原木がなければその葉っぱだけ（または空）を返す
                // 今回はその葉っぱだけ対象にする
                Set<Block> singleLeaf = new HashSet<>();
                singleLeaf.add(startBlock);
                return singleLeaf;
            }
        }

        // 1. 「幹（原木）」をすべて探し出す (BFS)
        if (Tag.LOGS.isTagged(origin.getType())) {
            queue.add(origin);
            logs.add(origin);
        }

        while (!queue.isEmpty() && logs.size() < MAX_TREE_SIZE) {
            Block current = queue.poll();

            // 26方向（3x3x3）チェックして繋がっている原木を探す
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0)
                            continue;

                        Block relative = current.getRelative(x, y, z);
                        if (logs.contains(relative))
                            continue;

                        if (Tag.LOGS.isTagged(relative.getType())) {
                            logs.add(relative);
                            queue.add(relative);
                            if (logs.size() >= MAX_TREE_SIZE)
                                break;
                        }
                    }
                    if (logs.size() >= MAX_TREE_SIZE)
                        break;
                }
                if (logs.size() >= MAX_TREE_SIZE)
                    break;
            }
        }

        // 2. 見つかった「幹」の周囲（半径3ブロック以内）にある葉っぱを収集する
        // ただし、その葉っぱの近くに「今回のlogsに含まれない原木」がある場合は除外する
        Set<Block> results = new HashSet<>(logs);

        for (Block log : logs) {
            for (int x = -3; x <= 3; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -3; z <= 3; z++) {
                        Block leafCandidate = log.getRelative(x, y, z);
                        if (results.contains(leafCandidate))
                            continue;

                        if (Tag.LEAVES.isTagged(leafCandidate.getType())) {
                            // この葉っぱが「自分の木」のものか確認
                            // 半径2ブロック以内に「自分の幹以外の原木」があるか？
                            if (!isLeafBelongingToOtherTree(leafCandidate, logs)) {
                                results.add(leafCandidate);
                                if (results.size() >= MAX_TREE_SIZE)
                                    return results;
                            }
                        }
                    }
                }
            }
        }

        return results;
    }

    /**
     * 葉っぱの周囲を調べ、指定された logs 以外の原木が近くにあるか判定する
     */
    private boolean isLeafBelongingToOtherTree(Block leaf, Set<Block> myLogs) {
        // 半径2以内をチェック
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block nearby = leaf.getRelative(x, y, z);
                    if (Tag.LOGS.isTagged(nearby.getType())) {
                        // 原木を見つけたが、自分の幹リストに含まれていない -> 他人の幹
                        if (!myLogs.contains(nearby)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 葉っぱの近くにある原木をBFSで探す（最大半径6）
     */
    private Block findNearestLog(Block startLeaf) {
        Queue<Block> queue = new LinkedList<>();
        Set<Block> visited = new HashSet<>();

        queue.add(startLeaf);
        visited.add(startLeaf);

        // 簡易的な階層管理（ループ回数制限で代用）
        while (!queue.isEmpty() && visited.size() < 200) {
            Block current = queue.poll();

            // 半径1ずつ広げる
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block relative = current.getRelative(x, y, z);

                        if (Tag.LOGS.isTagged(relative.getType())) {
                            return relative; // 原木発見
                        }

                        if (visited.contains(relative))
                            continue;

                        // 葉っぱか空気だけ伝っていく
                        if (Tag.LEAVES.isTagged(relative.getType())) {
                            visited.add(relative);
                            queue.add(relative);
                        }
                    }
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        // 落下中のブロックがブロックに変わろうとする（着地する）のを検知
        if (event.getEntity() instanceof FallingBlock fallingBlock) {
            // イベントをキャンセルしてブロック化を防ぐ（たいまつの上に落ちた砂のような挙動）
            event.setCancelled(true);

            // その場所にアイテムとしてドロップさせる
            fallingBlock.getWorld().dropItemNaturally(
                    fallingBlock.getLocation(),
                    new ItemStack(fallingBlock.getBlockData().getMaterial()));

            // エンティティを消去
            fallingBlock.remove();
        }
    }
}
