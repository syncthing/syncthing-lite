package net.syncthing.java.bep.folder

import net.syncthing.java.bep.utils.longSumBy
import net.syncthing.java.core.beans.FolderInfo
import net.syncthing.java.core.beans.FolderStats
import net.syncthing.java.core.beans.IndexInfo

data class FolderStatus(
        val info: FolderInfo,
        val stats: FolderStats,
        val indexInfo: List<IndexInfo>
) {
    companion object {
        fun createDummy(folder: String) = FolderStatus(
                info = FolderInfo(folder, folder),
                stats = FolderStats.createDummy(folder),
                indexInfo = emptyList()
        )
    }

    val missingIndexUpdates: Long by lazy { indexInfo.longSumBy { Math.min(it.maxSequence - it.localSequence, 0) } }
}