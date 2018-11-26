/*
 * Copyright (C) 2016 Davide Imbriaco
 * Copyright (C) 2018 Jonas Lochmann
 *
 * This Java file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.syncthing.java.repository.repo

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import net.syncthing.java.bep.BlockExchangeExtraProtos
import net.syncthing.java.bep.BlockExchangeProtos
import net.syncthing.java.core.beans.*
import net.syncthing.java.core.interfaces.IndexRepository
import net.syncthing.java.core.interfaces.IndexTransaction
import net.syncthing.java.core.interfaces.Sequencer
import org.bouncycastle.util.encoders.Hex
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.*

class SqlTransaction(
        private val connection: Connection,
        private val onFolderStatsUpdatedListener: ((IndexRepository.FolderStatsUpdatedEvent) -> Unit)?,
        private val initDb: (Connection) -> Unit
): IndexTransaction, Sequencer {
    private var closed = false

    fun close() {
        closed = true
    }

    private fun <T> runIfAllowed(block: () -> T): T {
        if (closed) {
            throw IllegalStateException("transaction already done")
        }

        return block()
    }

    override fun getSequencer() = this

    override fun indexId(): Long = runIfAllowed {
        connection.prepareStatement("SELECT index_id FROM index_sequence").use { statement ->
            val resultSet = statement.executeQuery()
            assert(resultSet.first())
            resultSet.getLong("index_id")
        }
    }

    override fun currentSequence(): Long = runIfAllowed {
        connection.prepareStatement("SELECT current_sequence FROM index_sequence").use { statement ->
            val resultSet = statement.executeQuery()
            assert(resultSet.first())
            resultSet.getLong("current_sequence")
        }
    }

    override fun nextSequence(): Long = runIfAllowed {
        connection.prepareStatement("UPDATE index_sequence SET current_sequence = current_sequence + 1").use { statement ->
            assert(statement.executeUpdate() == 1)
        }

        currentSequence()
    }

    override fun updateIndexInfo(indexInfo: IndexInfo): Unit = runIfAllowed {
        connection.prepareStatement("MERGE INTO folder_index_info"
                + " (folder,device_id,index_id,local_sequence,max_sequence)"
                + " VALUES (?,?,?,?,?)").use { prepareStatement ->
            prepareStatement.setString(1, indexInfo.folderId)
            prepareStatement.setString(2, indexInfo.deviceId)
            prepareStatement.setLong(3, indexInfo.indexId)
            prepareStatement.setLong(4, indexInfo.localSequence)
            prepareStatement.setLong(5, indexInfo.maxSequence)
            prepareStatement.executeUpdate()
        }
    }

    override fun findIndexInfoByDeviceAndFolder(deviceId: DeviceId, folder: String): IndexInfo? = runIfAllowed {
        doFindIndexInfoByDeviceAndFolder(deviceId, folder)
    }

    @Throws(SQLException::class)
    private fun doFindIndexInfoByDeviceAndFolder(deviceId: DeviceId, folder: String): IndexInfo? = runIfAllowed {
        connection.prepareStatement("SELECT * FROM folder_index_info WHERE device_id=? AND folder=?").use { prepareStatement ->
            prepareStatement.setString(1, deviceId.deviceId)
            prepareStatement.setString(2, folder)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                readFolderIndexInfo(resultSet)
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class)
    override fun findFileInfo(folder: String, path: String): FileInfo? = runIfAllowed {
        connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND path=?").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            prepareStatement.setString(2, path)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                readFileInfo(resultSet)
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class)
    override fun findFileInfoLastModified(folder: String, path: String): Date? = runIfAllowed {
        connection.prepareStatement("SELECT last_modified FROM file_info WHERE folder=? AND path=?").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            prepareStatement.setString(2, path)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                Date(resultSet.getLong("last_modified"))
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class)
    override fun findNotDeletedFileInfo(folder: String, path: String): FileInfo? = runIfAllowed {
        connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND path=? AND is_deleted=FALSE").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            prepareStatement.setString(2, path)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                readFileInfo(resultSet)
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class)
    private fun readFileInfo(resultSet: ResultSet): FileInfo {
        val folder = resultSet.getString("folder")
        val path = resultSet.getString("path")
        val fileType = FileInfo.FileType.valueOf(resultSet.getString("file_type"))
        val lastModified = Date(resultSet.getLong("last_modified"))
        val versionList = listOf(FileInfo.Version(resultSet.getLong("version_id"), resultSet.getLong("version_value")))
        val isDeleted = resultSet.getBoolean("is_deleted")
        val builder = FileInfo.Builder()
                .setFolder(folder)
                .setPath(path)
                .setLastModified(lastModified)
                .setVersionList(versionList)
                .setDeleted(isDeleted)
        return if (fileType == FileInfo.FileType.DIRECTORY) {
            builder.setTypeDir().build()
        } else {
            builder.setTypeFile().setSize(resultSet.getLong("size")).setHash(resultSet.getString("hash")).build()
        }
    }

    @Throws(SQLException::class, InvalidProtocolBufferException::class)
    override fun findFileBlocks(folder: String, path: String): FileBlocks? = runIfAllowed {
        connection.prepareStatement("SELECT * FROM file_blocks WHERE folder=? AND path=?").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            prepareStatement.setString(2, path)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                readFileBlocks(resultSet)
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class, InvalidProtocolBufferException::class)
    private fun readFileBlocks(resultSet: ResultSet): FileBlocks {
        val blocks = BlockExchangeExtraProtos.Blocks.parseFrom(resultSet.getBytes("blocks"))
        val blockList = blocks.blocksList.map { record ->
            BlockInfo(record!!.offset, record.size, Hex.toHexString(record.hash.toByteArray()))
        }
        return FileBlocks(resultSet.getString("folder"), resultSet.getString("path"), blockList)
    }

    @Throws(SQLException::class)
    override fun updateFileInfo(fileInfo: FileInfo, fileBlocks: FileBlocks?): Unit = runIfAllowed {
        val version = fileInfo.versionList.last()

        if (fileBlocks != null) {
            FileInfo.checkBlocks(fileInfo, fileBlocks)
            connection.prepareStatement("MERGE INTO file_blocks"
                    + " (folder,path,hash,size,blocks)"
                    + " VALUES (?,?,?,?,?)").use { prepareStatement ->
                prepareStatement.setString(1, fileBlocks.folder)
                prepareStatement.setString(2, fileBlocks.path)
                prepareStatement.setString(3, fileBlocks.hash)
                prepareStatement.setLong(4, fileBlocks.size)
                prepareStatement.setBytes(5, BlockExchangeExtraProtos.Blocks.newBuilder()
                        .addAllBlocks(fileBlocks.blocks.map { input ->
                            BlockExchangeProtos.BlockInfo.newBuilder()
                                    .setOffset(input.offset)
                                    .setSize(input.size)
                                    .setHash(ByteString.copyFrom(Hex.decode(input.hash)))
                                    .build()
                        }).build().toByteArray())
                prepareStatement.executeUpdate()
            }
        }
        val oldFileInfo = findFileInfo(fileInfo.folder, fileInfo.path)
        connection.prepareStatement("MERGE INTO file_info"
                + " (folder,path,file_name,parent,size,hash,last_modified,file_type,version_id,version_value,is_deleted)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)").use { prepareStatement ->
            prepareStatement.setString(1, fileInfo.folder)
            prepareStatement.setString(2, fileInfo.path)
            prepareStatement.setString(3, fileInfo.fileName)
            prepareStatement.setString(4, fileInfo.parent)
            prepareStatement.setLong(7, fileInfo.lastModified.time)
            prepareStatement.setString(8, fileInfo.type.name)
            prepareStatement.setLong(9, version.id)
            prepareStatement.setLong(10, version.value)
            prepareStatement.setBoolean(11, fileInfo.isDeleted)
            if (fileInfo.isDirectory()) {
                prepareStatement.setNull(5, Types.BIGINT)
                prepareStatement.setNull(6, Types.VARCHAR)
            } else {
                prepareStatement.setLong(5, fileInfo.size!!)
                prepareStatement.setString(6, fileInfo.hash)
            }
            prepareStatement.executeUpdate()
        }
        //update stats
        var deltaFileCount: Long = 0
        var deltaDirCount: Long = 0
        var deltaSize: Long = 0
        val oldMissing = oldFileInfo == null || oldFileInfo.isDeleted
        val newMissing = fileInfo.isDeleted
        val oldSizeMissing = oldMissing || !oldFileInfo!!.isFile()
        val newSizeMissing = newMissing || !fileInfo.isFile()
        if (!oldSizeMissing) {
            deltaSize -= oldFileInfo!!.size!!
        }
        if (!newSizeMissing) {
            deltaSize += fileInfo.size!!
        }
        if (!oldMissing) {
            if (oldFileInfo!!.isFile()) {
                deltaFileCount--
            } else if (oldFileInfo.isDirectory()) {
                deltaDirCount--
            }
        }
        if (!newMissing) {
            if (fileInfo.isFile()) {
                deltaFileCount++
            } else if (fileInfo.isDirectory()) {
                deltaDirCount++
            }
        }
        val folderStats = updateFolderStats(connection, fileInfo.folder, deltaFileCount, deltaDirCount, deltaSize, fileInfo.lastModified)

        onFolderStatsUpdatedListener?.invoke(object : IndexRepository.FolderStatsUpdatedEvent() {
            override fun getFolderStats(): List<FolderStats> {
                return listOf(folderStats)
            }
        })
    }

    @Throws(SQLException::class)
    override fun findNotDeletedFilesByFolderAndParent(folder: String, parentPath: String): MutableList<FileInfo> = runIfAllowed {
        connection.prepareStatement("SELECT * FROM file_info WHERE folder=? AND parent=? AND is_deleted=FALSE").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            prepareStatement.setString(2, parentPath)
            val resultSet = prepareStatement.executeQuery()
            val list = mutableListOf<FileInfo>()
            while (resultSet.next()) {
                list.add(readFileInfo(resultSet))
            }

            list
        }
    }

    @Throws(SQLException::class)
    override fun findFileInfoBySearchTerm(query: String): List<FileInfo> = runIfAllowed {
        assert(query.isNotBlank())
        //        checkArgument(maxResult > 0);
        //        try (Connection connection = getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM file_info WHERE LOWER(file_name) LIKE ? AND is_deleted=FALSE LIMIT ?")) {
        connection.prepareStatement("SELECT * FROM file_info WHERE LOWER(file_name) REGEXP ? AND is_deleted=FALSE").use { preparedStatement ->
            //        try (Connection connection = getConnection(); PreparedStatement prepareStatement = connection.prepareStatement("SELECT * FROM file_info LIMIT 10")) {
            //            preparedStatement.setString(1, "%" + query.trim().toLowerCase() + "%");
            preparedStatement.setString(1, query.trim { it <= ' ' }.toLowerCase())
            //            preparedStatement.setInt(2, maxResult);
            val resultSet = preparedStatement.executeQuery()
            val list = mutableListOf<FileInfo>()
            while (resultSet.next()) {
                list.add(readFileInfo(resultSet))
            }

            list
        }
    }

    @Throws(SQLException::class)
    override fun countFileInfoBySearchTerm(query: String): Long = runIfAllowed {
        assert(query.isNotBlank())
        connection.prepareStatement("SELECT COUNT(*) FROM file_info WHERE LOWER(file_name) REGEXP ? AND is_deleted=FALSE").use { preparedStatement ->
            //        try (Connection connection = getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("SELECT COUNT(*) FROM file_info")) {
            preparedStatement.setString(1, query.trim { it <= ' ' }.toLowerCase())
            val resultSet = preparedStatement.executeQuery()
            assert(resultSet.first())
            resultSet.getLong(1)
        }
    }

    // FILE INFO - END
    override fun clearIndex() = runIfAllowed {
        initDb(connection)
    }

    // FOLDER STATS - BEGIN
    @Throws(SQLException::class)
    private fun readFolderStats(resultSet: ResultSet) = FolderStats(
            folderId = resultSet.getString("folder"),
            dirCount = resultSet.getLong("dir_count"),
            fileCount = resultSet.getLong("file_count"),
            size = resultSet.getLong("size"),
            lastUpdate = Date(resultSet.getLong("last_update"))
    )

    override fun findFolderStats(folder: String): FolderStats? {
        return doFindFolderStats(folder)
    }

    @Throws(SQLException::class)
    private fun doFindFolderStats(folder: String): FolderStats? = runIfAllowed {
        connection.prepareStatement("SELECT * FROM folder_stats WHERE folder=?").use { prepareStatement ->
            prepareStatement.setString(1, folder)
            val resultSet = prepareStatement.executeQuery()

            if (resultSet.first()) {
                readFolderStats(resultSet)
            } else {
                null
            }
        }
    }

    @Throws(SQLException::class)
    override fun findAllFolderStats(): List<FolderStats> = runIfAllowed {
        connection.prepareStatement("SELECT * FROM folder_stats").use { prepareStatement ->
            val resultSet = prepareStatement.executeQuery()
            val list = mutableListOf<FolderStats>()
            while (resultSet.next()) {
                val folderStats = readFolderStats(resultSet)
                list.add(folderStats)
            }

            list
        }
    }

    @Throws(SQLException::class)
    private fun updateFolderStats(connection: Connection, folder: String, deltaFileCount: Long, deltaDirCount: Long, deltaSize: Long, lastUpdate: Date): FolderStats {
        val oldFolderStats = findFolderStats(folder)
        val newFolderStats: FolderStats
        if (oldFolderStats == null) {
            newFolderStats = FolderStats(
                    dirCount = deltaDirCount,
                    fileCount = deltaFileCount,
                    folderId = folder,
                    lastUpdate = lastUpdate,
                    size = deltaSize
            )
        } else {
            newFolderStats = oldFolderStats.copy(
                    dirCount = oldFolderStats.dirCount + deltaDirCount,
                    fileCount = oldFolderStats.fileCount + deltaFileCount,
                    size = oldFolderStats.size + deltaSize,
                    lastUpdate = if (lastUpdate.after(oldFolderStats.lastUpdate)) lastUpdate else oldFolderStats.lastUpdate
            )
        }
        updateFolderStats(connection, newFolderStats)
        return newFolderStats
    }

    //    private void updateFolderStats() {
    //        logger.info("updateFolderStats BEGIN");
    //        final Map<String, FolderStats.Builder> map = Maps.newHashMap();
    //        final Function<String, FolderStats.Builder> func = new Function<String, FolderStats.Builder>() {
    //            @Override
    //            public FolderStats.Builder apply(String folder) {
    //                FolderStats.Builder res = map.get(folder);
    //                if (res == null) {
    //                    res = FolderStats.newBuilder().setFolder(folder);
    //                    map.put(folder, res);
    //                }
    //                return res;
    //            }
    //        };
    //        final List<FolderStats> list;
    //        try (Connection connection = getConnection()) {
    //            try (PreparedStatement prepareStatement = connection.prepareStatement("SELECT folder, COUNT(*) AS file_count, SUM(size) AS size, MAX(last_modified) AS last_update FROM file_info WHERE file_type=? AND is_deleted=FALSE GROUP BY folder")) {
    //                prepareStatement.setString(1, FileType.FILE.name());
    //                ResultSet resultSet = prepareStatement.executeQuery();
    //                while (resultSet.next()) {
    //                    FolderStats.Builder builder = func.apply(resultSet.getString("folder"));
    //                    builder.setSize(resultSet.getLong("size"));
    //                    builder.setFileCount(resultSet.getLong("file_count"));
    //                    builder.setLastUpdate(new Date(resultSet.getLong("last_update")));
    //                }
    //            }
    //            try (PreparedStatement prepareStatement = connection.prepareStatement("SELECT folder, COUNT(*) AS dir_count FROM file_info WHERE file_type=? AND is_deleted=FALSE GROUP BY folder")) {
    //                prepareStatement.setString(1, FileType.DIRECTORY.name());
    //                ResultSet resultSet = prepareStatement.executeQuery();
    //                while (resultSet.next()) {
    //                    FolderStats.Builder builder = func.apply(resultSet.getString("folder"));
    //                    builder.setDirCount(resultSet.getLong("dir_count"));
    //                }
    //            }
    //            list = Lists.newArrayList(Iterables.transform(map.values(), new Function<FolderStats.Builder, FolderStats>() {
    //                @Override
    //                public FolderStats apply(FolderStats.Builder builder) {
    //                    return builder.build();
    //                }
    //            }));
    //            for (FolderStats folderStats : list) {
    //                updateFolderStats(connection, folderStats);
    //            }
    //        } catch (SQLException ex) {
    //            throw new RuntimeException(ex);
    //        }
    //        logger.info("updateFolderStats END");
    //        eventBus.post(new FolderStatsUpdatedEvent() {
    //            @Override
    //            public List<FolderStats> getFolderStats() {
    //                return Collections.unmodifiableList(list);
    //            }
    //        });
    //    }
    @Throws(SQLException::class)
    private fun updateFolderStats(connection: Connection, folderStats: FolderStats) {
        assert(folderStats.fileCount >= 0)
        assert(folderStats.dirCount >= 0)
        assert(folderStats.size >= 0)
        connection.prepareStatement("MERGE INTO folder_stats"
                + " (folder,file_count,dir_count,size,last_update)"
                + " VALUES (?,?,?,?,?)").use { prepareStatement ->
            prepareStatement.setString(1, folderStats.folderId)
            prepareStatement.setLong(2, folderStats.fileCount)
            prepareStatement.setLong(3, folderStats.dirCount)
            prepareStatement.setLong(4, folderStats.size)
            prepareStatement.setLong(5, folderStats.lastUpdate.time)
            prepareStatement.executeUpdate()
        }
    }

    @Throws(SQLException::class)
    private fun readFolderIndexInfo(resultSet: ResultSet) = IndexInfo(
            folderId = resultSet.getString("folder"),
            deviceId = resultSet.getString("device_id"),
            indexId = resultSet.getLong("index_id"),
            localSequence = resultSet.getLong("local_sequence"),
            maxSequence = resultSet.getLong("max_sequence")
    )

    @Throws(SQLException::class)
    private fun readDeviceAddress(resultSet: ResultSet): DeviceAddress {
        val instanceId = resultSet.getLong("instance_id")
        return DeviceAddress.Builder()
                .setAddress(resultSet.getString("address_url"))
                .setDeviceId(DeviceId(resultSet.getString("device_id")))
                .setInstanceId(if (instanceId == 0L) null else instanceId)
                .setProducer(DeviceAddress.AddressProducer.valueOf(resultSet.getString("address_producer")))
                .setScore(resultSet.getInt("address_score"))
                .setLastModified(Date(resultSet.getLong("last_modified")))
                .build()
    }
}
