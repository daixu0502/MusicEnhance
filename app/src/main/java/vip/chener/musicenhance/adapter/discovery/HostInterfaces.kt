package vip.chener.musicenhance.adapter.discovery

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** Fast validated contracts first; unknown versions require a unique semantic DEX match. */
internal object HostInterfaces {
    private val engines = WeakHashMap<ClassLoader, Engine>()
    private var hostInfo: ApplicationInfo? = null
    private var hostProcess = ""
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-interfaces").apply { isDaemon = true }
    }
    private val versionWorker by lazy { Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicEnhance-host-version").apply { isDaemon = true }
    } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Synchronized fun configure(loader: ClassLoader, info: ApplicationInfo, processName: String) {
        hostInfo = ApplicationInfo(info)
        hostProcess = processName
        engine(loader).configure(info, processName)
    }

    fun attach(application: Application) {
        engine(application.classLoader).attach(application)
    }

    /** Retry native hook installation after a batch, never once for every individual method. */
    fun onResolved(loader: ClassLoader, key: String, callback: () -> Unit) {
        engine(loader).onResolved(key, callback)
    }

    /** Register dependent capabilities together, using one worker/index rather than UI retries. */
    fun preload(loader: ClassLoader, key: String, prepare: () -> Unit) = engine(loader).preload(key, prepare)

    fun method(loader: ClassLoader, key: String, known: () -> Method = { throw Unavailable("Discovery required: $key") },
               validate: (Method) -> Boolean = { true }, discover: DexSearch.() -> List<Method>): Method =
        resolve(loader, key, known, validate, discover)

    fun field(loader: ClassLoader, key: String, known: () -> Field = { throw Unavailable("Discovery required: $key") },
              validate: (Field) -> Boolean = { true }, discover: DexSearch.() -> List<Field>): Field =
        resolve(loader, key, known, validate, discover)

    fun type(loader: ClassLoader, key: String, known: () -> Class<*> = { throw Unavailable("Discovery required: $key") },
             validate: (Class<*>) -> Boolean = { true }, discover: DexSearch.() -> List<Class<*>>): Class<*> =
        resolve(loader, key, known, validate, discover)

    fun constructor(loader: ClassLoader, key: String, known: () -> Constructor<*>,
                    validate: (Constructor<*>) -> Boolean = { true },
                    discover: DexSearch.() -> List<Constructor<*>>): Constructor<*> =
        resolve(loader, key, known, validate, discover)

    @Suppress("UNCHECKED_CAST") // A key is owned by one adapter's typed rule and cache descriptor.
    private fun <T : Any> resolve(loader: ClassLoader, key: String, known: () -> T,
                                  validate: (T) -> Boolean, discover: DexSearch.() -> List<T>): T =
        engine(loader).resolve(key, { known() }, { value ->
            validateCandidate { validate(value as T) }
        }, { discover().map { it } }) as T

    internal fun validateCandidate(validate: () -> Boolean): Boolean = try {
        validate()
    } catch (error: Unavailable) {
        // A dependency still being resolved is different from a structurally invalid member.
        throw error
    } catch (_: Throwable) {
        false
    }

    @Synchronized private fun engine(loader: ClassLoader) = engines.getOrPut(loader) {
        Engine(loader).also { value -> hostInfo?.let { value.configure(it, hostProcess) } }
    }

    internal class Unavailable(message: String) : ReflectiveOperationException(message)

    private class Rule(
        val key: String, val known: () -> Any, val validate: (Any) -> Boolean,
        val discover: DexSearch.() -> List<Any>,
    ) {
        @Volatile var value: Any? = null
        @Volatile var status = "pending"
    }

    internal class Engine(
        private val loader: ClassLoader,
        private val executor: Executor = worker,
        private val dispatch: (() -> Unit) -> Unit = { main.post(it) },
        private val searchFactory: (() -> DexSearch)? = null,
        private val metadataExecutor: Executor = versionWorker,
    ) {
        private val rules = linkedMapOf<String, Rule>()
        private val callbacks = linkedMapOf<String, () -> Unit>()
        private val preloadKeys = mutableSetOf<String>()
        private val preloads = mutableListOf<() -> Unit>()
        private val activeSearch = ThreadLocal<DexSearch>()
        private val visiting = ThreadLocal.withInitial { mutableSetOf<String>() }
        private val cached = linkedMapOf<String, InterfaceDescriptor>()
        private var cache: InterfaceCache? = null
        private var info: ApplicationInfo? = null
        private var apkPaths = emptyList<String>()
        @Volatile private var knownAllowed = true // Unconfigured mapped loaders are also used by JVM fixtures.
        private var prepared = false
        private var scheduled = false
        private var readyNotificationPending = false
        private var prepareFailed = false

        @Synchronized fun configure(applicationInfo: ApplicationInfo, processName: String) {
            if (info != null) return
            info = ApplicationInfo(applicationInfo)
            val code = versionCode(applicationInfo)
            knownAllowed = code in VERIFIED_CODES[applicationInfo.packageName].orEmpty()
            apkPaths = (listOfNotNull(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty()).distinct().sorted()
            executor.execute {
                try {
                    val fingerprint = InterfaceCache.fingerprint(applicationInfo.packageName, code, apkPaths)
                    val processKey = processName.hashCode().toUInt().toString(16)
                    val store = InterfaceCache(File(applicationInfo.dataDir,
                        "code_cache/musicenhance-interfaces/$processKey.json"), fingerprint)
                    val restored = store.read()
                    synchronized(this) { cache = store; cached.putAll(restored); prepared = true; schedule() }
                    moduleInfo("Interface cache ready: app=${applicationInfo.packageName}, knownVersion=$knownAllowed, entries=${cached.size}")
                } catch (error: Throwable) {
                    synchronized(this) { prepared = true; prepareFailed = true }
                    moduleInfo("Interface fingerprint failed for ${applicationInfo.packageName}: $error")
                }
            }
        }

        fun attach(application: Application) {
            if (knownAllowed) return
            // A PackageManager read and disk fingerprinting never run in Activity rendering or polling.
            // Version recognition must not wait behind hashing a large base/split APK set.
            metadataExecutor.execute {
                val version = runCatching {
                    application.packageManager.getPackageInfo(application.packageName,
                        PackageManager.PackageInfoFlags.of(0)).versionName
                }.getOrNull()
                synchronized(this) {
                    if (version in VERIFIED_NAMES[application.packageName].orEmpty() && !knownAllowed) {
                        knownAllowed = true
                        rules.values.filter { it.value == null }.forEach { it.status = "pending" }
                        // Existing contracts can be retried immediately, while APK hashing continues.
                        notifyReady()
                        schedule()
                    }
                }
            }
        }

        @Synchronized fun onResolved(key: String, callback: () -> Unit) { callbacks[key] = callback }

        @Synchronized fun preload(key: String, prepare: () -> Unit) {
            if (!preloadKeys.add(key)) return
            preloads += prepare
            schedule()
        }

        fun resolve(key: String, known: () -> Any, validate: (Any) -> Boolean,
                                  discover: DexSearch.() -> List<Any>): Any {
            val rule = synchronized(this) { rules.getOrPut(key) {
                require(rules.size < InterfaceCache.MAX_ENTRIES) { "Too many interface discovery rules" }
                Rule(key, known, validate, discover)
            } }
            rule.value?.let { value -> if (validate(value)) return value else { rule.value = null; rule.status = "pending" } }
            if (knownAllowed) {
                runCatching { known() }.getOrNull()?.takeIf(validate)?.let {
                    accept(rule, it, "known"); return it
                }
            }
            // Cached descriptors are only made available after the complete APK identity was checked.
            synchronized(this) { cached[key] }?.let { descriptor ->
                runCatching { descriptor.resolve(loader) }.getOrNull()?.takeIf(validate)?.let {
                    accept(rule, it, "cache"); return it
                }
                synchronized(this) { cached.remove(key) }
            }
            activeSearch.get()?.let { search -> return discover(rule, search) }
            if (rule.status == "pending") schedule()
            throw Unavailable("Interface $key ${rule.status}; native capability is unavailable")
        }

        @Synchronized private fun accept(rule: Rule, value: Any, source: String) {
            val descriptor = InterfaceDescriptor.of(value)
            rule.value = value
            rule.status = source
            cached[rule.key] = descriptor
            if (source != "known") moduleInfo("Interface ${rule.key}: $source -> ${descriptor.owner}.${descriptor.name}")
        }

        private fun discover(rule: Rule, search: DexSearch): Any {
            val activeRules = requireNotNull(visiting.get())
            check(activeRules.add(rule.key)) { "Cyclic interface rule: ${rule.key}" }
            try {
                val candidates = rule.discover(search).filter(rule.validate).distinctBy(InterfaceDescriptor::of)
                if (candidates.size != 1) {
                    rule.status = if (candidates.isEmpty()) "missing" else "ambiguous(${candidates.size})"
                    throw Unavailable("Interface ${rule.key} ${rule.status}")
                }
                return candidates.single().also { accept(rule, it, "feature") }
            } finally { activeRules.remove(rule.key) }
        }

        @Synchronized private fun schedule() {
            if (!prepared || prepareFailed || scheduled ||
                (preloads.isEmpty() && rules.values.none { it.status == "pending" })) return
            scheduled = true
            executor.execute(::scan)
        }

        private fun scan() {
            var changed = false
            try {
                val preparations = synchronized(this) { preloads.toList().also { preloads.clear() } }
                fun prepare() = preparations.forEach { callback ->
                    runCatching(callback).onFailure { moduleInfo("Interface preload incomplete: ${it.message}") }
                }
                val resolvedBefore = synchronized(this) { rules.values.count { it.value != null } }
                prepare() // A valid descriptor cache can satisfy the entire batch without DEX.
                // Retry the verified/cached paths before paying for a DEX index.
                synchronized(this) { rules.values.toList().filter { it.status == "pending" } }.forEach { rule ->
                    try {
                        if (knownAllowed) runCatching { rule.known() }.getOrNull()?.takeIf(rule.validate)?.let {
                            accept(rule, it, "known"); changed = true
                        }
                        if (rule.value == null) synchronized(this) { cached[rule.key] }?.let { descriptor ->
                            runCatching { descriptor.resolve(loader) }.getOrNull()?.takeIf(rule.validate)?.let {
                                accept(rule, it, "cache"); changed = true
                            }
                        }
                    } catch (error: Unavailable) {
                        rule.status = "waiting"
                    }
                }
                if (changed) synchronized(this) {
                    rules.values.filter { it.status == "waiting" }.forEach { it.status = "pending" }
                }
                val needsSearch = synchronized(this) { rules.values.any { it.status == "pending" } }
                if (needsSearch) openSearch().use { search ->
                    activeSearch.set(search)
                    try {
                        prepare() // Nested lookups now share this background index.
                        // Nested dependency rules are resolved on this worker using the same index.
                        while (true) {
                            val batch = synchronized(this) { rules.values.filter { it.status == "pending" }.toList() }
                            if (batch.isEmpty()) break
                            var batchChanged = false
                            for (rule in batch) {
                                if (rule.value == null) {
                                    try { discover(rule, search); changed = true; batchChanged = true }
                                    catch (error: Throwable) {
                                        if (rule.status == "pending") rule.status = if (error is Unavailable) "waiting" else "failed"
                                        moduleInfo("Interface ${rule.key}: ${rule.status}; ${error.message}")
                                    }
                                }
                            }
                            if (batchChanged) synchronized(this) {
                                rules.values.filter { it.status == "waiting" }.forEach { it.status = "pending" }
                            }
                        }
                    } finally { activeSearch.remove() }
                }
                changed = changed || synchronized(this) { rules.values.count { it.value != null } > resolvedBefore }
                val saved = synchronized(this) { cache to cached.toMap() }
                saved.first?.write(saved.second)
            } catch (error: Throwable) {
                synchronized(this) { rules.values.filter { it.status == "pending" }.forEach { it.status = "failed" } }
                moduleInfo("Host interface discovery failed: $error")
            } finally {
                synchronized(this) { scheduled = false; if (changed) notifyReady(); schedule() }
            }
        }

        private fun openSearch(): DexSearch {
            searchFactory?.let { return it() }
            NativeScanner.load()
            val bridges = mutableListOf<DexKitBridge>()
            try {
                apkPaths.filter { path -> ZipFile(path).use { zip -> zip.getEntry("classes.dex") != null } }.forEach {
                    bridges += DexKitBridge.create(it).also { bridge -> bridge.setThreadNum(2) }
                }
                require(bridges.isNotEmpty()) { "No host DEX files" }
                return DexSearch(loader, bridges, apkPaths)
            } catch (error: Throwable) { bridges.forEach { it.close() }; throw error }
        }

        private fun notifyReady() {
            if (readyNotificationPending) return
            readyNotificationPending = true
            dispatch {
                val readyCallbacks = synchronized(this) { readyNotificationPending = false; callbacks.values.toList() }
                readyCallbacks.forEach { callback -> runCatching(callback).onFailure { moduleInfo("Interface ready callback failed: $it") } }
            }
        }
    }

    private object NativeScanner {
        private var loaded = false
        // Injected code cannot search the host's native directory for our library. The fallback
        // uses only libxposed's installed module APK/directory and a constant bundled filename.
        @SuppressLint("UnsafeDynamicallyLoadedCode")
        @Synchronized fun load() {
            if (loaded) return
            try { System.loadLibrary("dexkit") }
            catch (error: UnsatisfiedLinkError) {
                val moduleInfo = module.moduleApplicationInfo
                val extracted = File(moduleInfo.nativeLibraryDir, "libdexkit.so")
                System.load(if (extracted.isFile) extracted.absolutePath
                    else "${moduleInfo.sourceDir}!/lib/arm64-v8a/libdexkit.so")
            }
            loaded = true
        }
    }

    private fun versionCode(info: ApplicationInfo): Long = runCatching {
        ApplicationInfo::class.java.getField("longVersionCode").getLong(info)
    }.getOrElse { runCatching { ApplicationInfo::class.java.getField("versionCode").getInt(info).toLong() }.getOrDefault(-1) }

    private val VERIFIED_CODES = mapOf(
        "cn.kuwo.player" to setOf(12224L),
        "com.kugou.android" to setOf(20849L),
        "com.kugou.android.lite" to setOf(11590L),
        "com.spotify.music" to setOf(147078674L),
    )
    private val VERIFIED_NAMES = mapOf(
        "com.apple.android.music" to setOf("6.5.2"),
        "cn.kuwo.player" to setOf("12.2.2.4"),
        "com.kugou.android" to setOf("20.8.2", "20.8.4"),
        "com.kugou.android.lite" to setOf("5.2.9"),
        "com.lxwalnut.music.mobile" to setOf("26.09.20"),
        "com.spotify.music" to setOf("9.1.90.2270"),
    )
}
