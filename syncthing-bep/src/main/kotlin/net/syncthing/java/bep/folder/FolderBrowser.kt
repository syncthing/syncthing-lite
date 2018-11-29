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
package net.syncthing.java.bep.folder

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.syncthing.java.bep.index.IndexHandler
import net.syncthing.java.core.beans.FolderStats
import net.syncthing.java.core.configuration.Configuration
import net.syncthing.java.core.interfaces.IndexRepository
import java.io.Closeable

class FolderBrowser internal constructor(private val indexHandler: IndexHandler, private val configuration: Configuration) : Closeable {
    private val job = Job()
    private val foldersStatus = ConflatedBroadcastChannel<Map<String, FolderStatus>>()
    private val folderStatsUpdatedEvent = Channel<IndexRepository.FolderStatsUpdatedEvent>(capacity = Channel.UNLIMITED)

    // FIXME: This isn't nice
    private val indexRepositoryEventListener = { event: IndexRepository.FolderStatsUpdatedEvent ->
        folderStatsUpdatedEvent.offer(event)

        fun nothing() {
            // used to return Unit
        }

        nothing()
    }

    init {
        indexHandler.indexRepository.setOnFolderStatsUpdatedListener(indexRepositoryEventListener)  // TODO: remove this global state

        GlobalScope.launch (job) {
            // get initial status
            val currentFolderStats = mutableMapOf<String, FolderStats>()

            var currentIndexInfo = indexHandler.indexRepository.runInTransaction { indexTransaction ->
                configuration.folders.map { it.folderId }.forEach { folderId ->
                    currentFolderStats[folderId] = indexTransaction.findFolderStats(folderId) ?: FolderStats.createDummy(folderId)
                }

                indexTransaction.findAllIndexInfos().groupBy { it.folderId }.toMutableMap()
            }

            // send status
            suspend fun dispatch() {
                foldersStatus.send(
                        configuration.folders.map { info ->
                            FolderStatus(
                                    info = info,
                                    stats = currentFolderStats[info.folderId] ?: FolderStats.createDummy(info.folderId),
                                    indexInfo = currentIndexInfo[info.folderId] ?: emptyList()
                            )
                        }.associateBy { it.info.folderId }
                )
            }

            dispatch()

            // handle changes
            val updateLock = Mutex()

            async {
                folderStatsUpdatedEvent.consumeEach {
                    updateLock.withLock {
                        it.getFolderStats().forEach { folderStats ->
                            currentFolderStats[folderStats.folderId] = folderStats
                        }

                        dispatch()
                    }
                }
            }

            async {
                indexHandler.subscribeToOnIndexRecordAcquiredEvents().consumeEach { event ->
                    updateLock.withLock {
                        val oldList = currentIndexInfo[event.folderInfo.folderId] ?: emptyList()
                        val newList = oldList.filter { it.deviceId != event.indexInfo.deviceId } + event.indexInfo
                        currentIndexInfo[event.folderInfo.folderId] = newList

                        dispatch()
                    }
                }
            }
        }
    }

    fun folderInfoAndStatusStream() = GlobalScope.produce {
        foldersStatus.openSubscription().consumeEach { folderStats ->
            send(
                    folderStats
                            .values
                            .sortedBy { it.info.label }
            )
        }
    }

    suspend fun folderInfoAndStatusList(): List<FolderStatus> = folderInfoAndStatusStream().first()

    suspend fun getFolderStatus(folder: String): FolderStatus {
        return getFolderStatus(folder, foldersStatus.openSubscription().first())
    }

    fun getFolderStatusSync(folder: String) = runBlocking { getFolderStatus(folder) }

    private fun getFolderStatus(folder: String, folderStatus: Map<String, FolderStatus>) = folderStatus[folder] ?: FolderStatus.createDummy(folder)

    override fun close() {
        job.cancel()
        indexHandler.indexRepository.setOnFolderStatsUpdatedListener(null)
        folderStatsUpdatedEvent.close()
    }
}