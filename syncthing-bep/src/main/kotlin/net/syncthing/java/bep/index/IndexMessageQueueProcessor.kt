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
package net.syncthing.java.bep.index

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.syncthing.java.bep.BlockExchangeProtos
import net.syncthing.java.bep.connectionactor.ClusterConfigInfo
import net.syncthing.java.core.beans.DeviceId
import net.syncthing.java.core.beans.FolderInfo
import net.syncthing.java.core.configuration.Configuration
import net.syncthing.java.core.interfaces.IndexRepository
import net.syncthing.java.core.interfaces.IndexTransaction
import net.syncthing.java.core.interfaces.TempRepository
import org.slf4j.LoggerFactory

class IndexMessageQueueProcessor (
        private val markActive: () -> Unit,
        private val indexRepository: IndexRepository,
        private val tempRepository: TempRepository,
        private val configuration: Configuration,
        private val onIndexRecordAcquiredEvents: BroadcastChannel<IndexRecordAcquiredEvent>,
        private val onFullIndexAcquiredEvents: BroadcastChannel<FolderInfo>,
        private val indexWaitLock: Object,
        private val isRemoteIndexAcquired: (ClusterConfigInfo, DeviceId, IndexTransaction) -> Boolean
) {
    private data class IndexUpdateAction(val update: BlockExchangeProtos.IndexUpdate, val clusterConfigInfo: ClusterConfigInfo, val peerDeviceId: DeviceId)
    private data class StoredIndexUpdateAction(val updateId: String, val clusterConfigInfo: ClusterConfigInfo, val peerDeviceId: DeviceId)

    companion object {
        private val logger = LoggerFactory.getLogger(IndexMessageQueueProcessor::class.java)
    }

    private val job = Job()
    private val indexUpdateIncomingLock = Mutex()
    private val indexUpdateProcessStoredQueue = Channel<StoredIndexUpdateAction>(capacity = Channel.UNLIMITED)
    private val indexUpdateProcessingQueue = Channel<IndexUpdateAction>(capacity = Channel.RENDEZVOUS)

    suspend fun handleIndexMessageReceivedEvent(folderId: String, filesList: List<BlockExchangeProtos.FileInfo>, clusterConfigInfo: ClusterConfigInfo, peerDeviceId: DeviceId) {
        indexUpdateIncomingLock.withLock {
            logger.info("received index message event, preparing")
            markActive()

            val data = BlockExchangeProtos.IndexUpdate.newBuilder()
                    .addAllFiles(filesList)
                    .setFolder(folderId)
                    .build()

            if (indexUpdateProcessingQueue.offer(IndexUpdateAction(data, clusterConfigInfo, peerDeviceId))) {
                // message is beeing processed now
            } else {
                val key = tempRepository.pushTempData(data.toByteArray())

                logger.debug("received index message event, stored to temp record {}, queuing for processing", key)
                indexUpdateProcessStoredQueue.send(StoredIndexUpdateAction(key, clusterConfigInfo, peerDeviceId))
            }
        }
    }

    init {
        GlobalScope.launch(Dispatchers.IO + job) {
            indexUpdateProcessingQueue.consumeEach {
                doHandleIndexMessageReceivedEvent(it)
            }
        }

        GlobalScope.launch(Dispatchers.IO + job) {
            indexUpdateProcessStoredQueue.consumeEach { action ->
                markActive()
                logger.debug("processing index message event from temp record {}", action.updateId)

                val data = tempRepository.popTempData(action.updateId)
                val message = BlockExchangeProtos.IndexUpdate.parseFrom(data)

                indexUpdateProcessingQueue.send(IndexUpdateAction(
                        message,
                        action.clusterConfigInfo,
                        action.peerDeviceId
                ))
            }
        }
    }

    private fun doHandleIndexMessageReceivedEvent(action: IndexUpdateAction) {
        val (message, clusterConfigInfo, peerDeviceId) = action

        logger.info("processing index message with {} records", message.filesCount)

        indexRepository.runInTransaction { indexTransaction ->
            val wasIndexAcquiredBefore = isRemoteIndexAcquired(clusterConfigInfo, peerDeviceId, indexTransaction)

            val (newIndexInfo, newRecords) = NewIndexMessageProcessor.doHandleIndexMessageReceivedEvent(
                    message = message,
                    peerDeviceId = peerDeviceId,
                    transaction = indexTransaction,
                    markActive = markActive
            )

            logger.info("processed {} index records, acquired {}", message.filesCount, newRecords.size)

            val folderInfo = configuration.folders.find { it.folderId == message.folder } ?: FolderInfo(
                    folderId = message.folder,
                    label = message.folder
            )

            if (!newRecords.isEmpty()) {
                runBlocking { onIndexRecordAcquiredEvents.send(IndexRecordAcquiredEvent(folderInfo, newRecords, newIndexInfo)) }
            }

            logger.debug("index info = {}", newIndexInfo)

            if (!wasIndexAcquiredBefore) {
                if (isRemoteIndexAcquired(clusterConfigInfo, peerDeviceId, indexTransaction)) {
                    logger.debug("index acquired")
                    runBlocking { onFullIndexAcquiredEvents.send(folderInfo) }
                }
            }
        }

        markActive()

        synchronized(indexWaitLock) {
            indexWaitLock.notifyAll()
        }
    }

    fun stop() {
        logger.info("stopping index record processor")
        job.cancel()
    }
}
