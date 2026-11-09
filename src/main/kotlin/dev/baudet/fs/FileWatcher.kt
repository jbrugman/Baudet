package dev.baudet.fs

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes

/**
 * Watches a project folder so the file explorer can keep itself current when the folder changes
 * on disk, without waiting for the user to press Refresh. It wraps a java.nio [WatchService]: one
 * background thread blocks for an event, waits a short window, drains whatever piled up during that
 * window, and only then calls back once, so a burst of writes becomes a single refresh.
 *
 * Watching is recursive so a change deep in the tree is noticed, but it is bounded: [collectWatchableDirs]
 * prunes excluded directories and stops at a cap, which keeps the watch list off the operating
 * system limit and away from a runaway tree. .git is excluded on purpose -- git status can itself
 * touch the index, and watching it would make every refresh look like a new change.
 *
 * The watch set is self-healing. Folders are not adopted event by event; instead, whenever a batch
 * signals a structural change -- a directory appeared, a watched folder vanished, or events
 * overflowed -- the whole subtree is re-walked and reconciled: keys for gone directories are
 * cancelled and keys for new ones are registered. That keeps a long-lived watch correct through
 * mkdir, rm -rf and an overflow, any of which would otherwise leave a directory silently unwatched.
 *
 * Only the three standard event kinds are registered (create, delete, modify); java.nio exposes no
 * portable move kind, and a rename already reads as a delete at one path and a create at another.
 *
 * The class holds no Swing reference and fires its callback on its own worker thread; it is the
 * caller's job to marshal that onto the event dispatch thread if the callback touches the UI. The
 * worker runs as a virtual thread: the loop spends its life blocked in [WatchService.take], which a
 * virtual thread parks cheaply, so a watcher costs almost nothing while it waits.
 */
class FileWatcher {

    private var service: WatchService? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /**
     * Watch [root] and call [onChanged], at most once per quiet interval, while anything changes.
     * Starting a watcher that is already watching the same or another folder first stops the old
     * one, so there is never more than one live. A folder that cannot be watched -- missing, or a
     * new [WatchService] the platform refuses -- starts nothing rather than throwing.
     */
    fun start(
        root: Path,
        exclude: Set<String> = DEFAULT_EXCLUDE,
        maxDirs: Int = DEFAULT_MAX_DIRS,
        debounceMillis: Long = DEBOUNCE_MILLIS,
        onChanged: () -> Unit
    ) {
        stop()
        val svc = try {
            FileSystems.getDefault().newWatchService()
        } catch (_: IOException) {
            return
        } catch (_: RuntimeException) {
            return // for example a SecurityException from a restricted file system
        }

        val keyToDir = HashMap<WatchKey, Path>()
        collectWatchableDirs(root, exclude, maxDirs).forEach { register(svc, it, keyToDir, maxDirs) }
        if (keyToDir.isEmpty()) { // nothing was watchable; do not keep an idle service alive
            runCatching { svc.close() }
            return
        }

        service = svc
        running = true
        if (DEBUG) System.err.println("[fs] watching ${keyToDir.size} dirs at $root")
        worker = Thread.ofVirtual()
            .name("baudet-file-watch")
            .start(Runnable { runLoop(svc, keyToDir, root, exclude, maxDirs, debounceMillis, onChanged) })
    }

    /** Stop watching and release the service. Safe to call repeatedly and from any thread. */
    fun stop() {
        running = false
        service?.let { runCatching { it.close() } } // closing unblocks a blocked take()
        service = null
        worker = null
    }

    // ---- event loop ---------------------------------------------------------

    private fun runLoop(
        svc: WatchService,
        keyToDir: HashMap<WatchKey, Path>,
        root: Path,
        exclude: Set<String>,
        maxDirs: Int,
        debounceMillis: Long,
        onChanged: () -> Unit
    ) {
        try {
            while (running) {
                val key = svc.take() // blocks until an event arrives or the service is closed
                var dirty = false
                var overflowed = false
                var createdDir = false
                for (event in key.pollEvents()) { // all events accumulated for this directory
                    if (event.kind() == OVERFLOW) { // some events were dropped; re-read and re-watch
                        dirty = true
                        overflowed = true
                        continue
                    }
                    dirty = true
                    if (event.kind() == ENTRY_CREATE && isDirectoryUnder(keyToDir[key], event.context())) createdDir = true
                }
                val alive = key.reset() // false once the watched folder is gone
                if (!alive) {
                    keyToDir.remove(key)
                    dirty = true // a watched folder disappearing is a structural change to catch up on
                }
                if (!dirty) continue
                // Coalesce a burst: wait out the window, then throw away anything that arrived during
                // it so the next take starts clean and the caller sees one refresh for the whole burst.
                Thread.sleep(debounceMillis)
                drain(svc, keyToDir)
                if (overflowed || createdDir || !alive) reconcile(svc, keyToDir, root, exclude, maxDirs)
                if (DEBUG) System.err.println("[fs] fire under ${keyToDir[key] ?: root}")
                if (running) onChanged()
            }
        } catch (_: ClosedWatchServiceException) {
            // stop() closed the service; the thread has nothing left to do.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** True when the created entry names a directory still present under the watched [parent]. */
    private fun isDirectoryUnder(parent: Path?, context: Any?): Boolean {
        val name = context as? Path ?: return false
        val child = parent?.resolve(name) ?: return false
        return Files.isDirectory(child)
    }

    /**
     * Re-walk [root] and bring the watched directories back in line with what is on disk: cancel
     * keys whose folder is gone and register keys for folders that appeared, so a long watch neither
     * leaks stale keys nor misses a subtree that arrived after the last refresh.
     */
    private fun reconcile(
        svc: WatchService,
        keyToDir: HashMap<WatchKey, Path>,
        root: Path,
        exclude: Set<String>,
        maxDirs: Int
    ) {
        val present = collectWatchableDirs(root, exclude, maxDirs).toSet()
        val watched = keyToDir.values.toSet()
        val (toAdd, toCancel) = planReconcile(watched, present)
        if (DEBUG && (toAdd.isNotEmpty() || toCancel.isNotEmpty()))
            System.err.println("[fs] reconcile +${toAdd.size} -${toCancel.size}")
        if (toCancel.isNotEmpty()) {
            keyToDir.filterValues { it in toCancel }.keys.toList().forEach { it.cancel() }
            keyToDir.entries.removeAll { it.value in toCancel }
        }
        toAdd.forEach { register(svc, it, keyToDir, maxDirs) }
    }

    /** Discard everything already queued, so a burst collapses into the one refresh already decided. */
    private fun drain(svc: WatchService, keyToDir: HashMap<WatchKey, Path>) {
        while (true) {
            val key = svc.poll() ?: break
            key.pollEvents() // discard the accumulated events
            if (!key.reset()) keyToDir.remove(key)
        }
    }

    private fun register(svc: WatchService, dir: Path, keyToDir: HashMap<WatchKey, Path>, maxDirs: Int) {
        if (keyToDir.size >= maxDirs) return
        try {
            val key = dir.register(svc, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keyToDir[key] = dir
        } catch (_: IOException) {
            // A folder that vanished between the walk and this call is simply not watched.
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {

        /** Directories never watched; .git would turn every git command into a change event. */
        private val DEFAULT_EXCLUDE = setOf(".git")

        /** Ceiling on watched directories, keeping the watch list under the operating-system limit. */
        private const val DEFAULT_MAX_DIRS = 4096

        /** How long to gather a burst of events before firing a single refresh. */
        private const val DEBOUNCE_MILLIS = 400L

        /** Set -Dbaudet.fs.debug=true to trace what the watcher watches, fires and reconciles. */
        private val DEBUG = System.getProperty("baudet.fs.debug") == "true"

        /**
         * The directories under [root] to register, root included, walking depth first. A directory
         * whose own name is in [exclude] is pruned together with everything below it, an unreadable
         * folder is skipped, and the walk stops once [max] directories are collected so a huge tree
         * cannot overrun. Pure and side-effect free but for reading the directory names, so it is
         * testable against a throwaway tree without any watcher.
         */
        fun collectWatchableDirs(root: Path, exclude: Set<String> = DEFAULT_EXCLUDE, max: Int = DEFAULT_MAX_DIRS): List<Path> {
            if (!Files.isDirectory(root)) return emptyList()
            val dirs = ArrayList<Path>()
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dirs.size >= max) return FileVisitResult.TERMINATE
                        val name = dir.fileName?.toString()
                        if (dir != root && name != null && name in exclude) return FileVisitResult.SKIP_SUBTREE
                        dirs.add(dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE // a single unreadable entry must not abort the walk
                })
            } catch (_: IOException) {
                // Mid-walk the folder became unreadable; keep whatever was collected before that.
            }
            return dirs
        }

        /**
         * The difference between the directories a watcher currently holds and the ones actually on
         * disk, as a pair of (to add, to cancel). Anything present but unwatched must be added;
         * anything watched but gone must be cancelled; the overlap is left alone. Pure set logic over
         * already-decided inputs, so it is testable without touching the file system or a service.
         */
        fun planReconcile(watched: Set<Path>, present: Set<Path>): Pair<Set<Path>, Set<Path>> =
            (present - watched) to (watched - present)
    }
}
