package net.syncthing.lite.library

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * This class manages the access to an LibraryInstance
 *
 * Users can get an instance with startLibraryUsage()
 * If they are done with it, the should call stopLibraryUsage()
 * After this, it's NOT safe to continue using the received LibraryInstance
 *
 * Every call to startLibraryUsage should be followed by an call to stopLibraryUsage,
 * even if the callback was not called yet. It can still be called, so users should watch out.
 *
 * All listeners are executed at the UI Thread (except the synchronousInstanceCreator)
 *
 * The userCounterListener is always called before the isRunningListener
 *
 * The listeners are called for all changes, nothing is skipped or batched
 */
class LibraryManager (
        val synchronousInstanceCreator: () -> LibraryInstance,
        val userCounterListener: (Int) -> Unit = {},
        val isRunningListener: (isRunning: Boolean) -> Unit = {}
) {
    companion object {
        private val handler = Handler(Looper.getMainLooper())
    }

    // this must be a SingleThreadExecutor to avoid race conditions
    // only this Thread should access instance and userCounter
    private val startStopExecutor = Executors.newSingleThreadExecutor()

    private val instanceStream = ConflatedBroadcastChannel<LibraryInstance?>(null)
    private var userCounter = 0

    fun startLibraryUsage(callback: (LibraryInstance) -> Unit) {
        startStopExecutor.submit {
            val newUserCounter = ++userCounter
            handler.post { userCounterListener(newUserCounter) }

            if (instanceStream.value == null) {
                instanceStream.offer(synchronousInstanceCreator())
                handler.post { isRunningListener(true) }
            }

            handler.post { callback(instanceStream.value!!) }
        }
    }

    suspend fun startLibraryUsageCoroutine(): LibraryInstance {
        return suspendCoroutine { continuation ->
            startLibraryUsage { instance ->
                continuation.resume(instance)
            }
        }
    }

    suspend fun <T> withLibrary(action: suspend (LibraryInstance) -> T): T {
        val instance = startLibraryUsageCoroutine()

        return try {
            action(instance)
        } finally {
            stopLibraryUsage()
        }
    }

    fun stopLibraryUsage() {
        startStopExecutor.submit {
            val newUserCounter = --userCounter

            if (newUserCounter < 0) {
                userCounter = 0

                throw IllegalStateException("can not stop library usage if there are 0 users")
            }

            handler.post { userCounterListener(newUserCounter) }
        }
    }

    fun shutdownIfThereAreZeroUsers(listener: (wasShutdownPerformed: Boolean) -> Unit = {}) {
        startStopExecutor.submit {
            if (userCounter == 0) {
                runBlocking { instanceStream.value?.shutdown() }
                instanceStream.offer(null)

                handler.post { isRunningListener(false) }
                handler.post { listener(true) }
            } else {
                handler.post { listener(false) }
            }
        }
    }

    fun streamDirectoryListing(folder: String, path: String) = GlobalScope.produce {
        var job = Job()

        instanceStream.openSubscription().consumeEach { instance ->
            job.cancel()
            job = Job()

            if (instance != null) {
                async (job) {
                    instance.indexBrowser.streamDirectoryListing(folder, path).consumeEach {
                        send(it)
                    }
                }
            }
        }
    }
}
