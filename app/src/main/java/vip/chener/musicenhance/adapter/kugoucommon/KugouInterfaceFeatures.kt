package vip.chener.musicenhance.adapter.kugoucommon

import vip.chener.musicenhance.adapter.discovery.DexSearch
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shared DEX vocabulary; app-specific semantic rules remain in their own adapters. */
internal object KugouInterfaceFeatures {
    const val UTILITY = "com.kugou.framework.service.util.PlaybackServiceUtil"
    const val SERVICE = "com.kugou.framework.service.IKugouPlaybackService"
    const val WRAPPER = "com.kugou.framework.service.entity.KGMusicWrapper"
    const val MUSIC = "com.kugou.android.common.entity.KGMusic"
    const val MANAGER = "com.kugou.framework.lyric.LyricManager"
    const val DATA = "com.kugou.framework.lyric.LyricData"

    fun MethodData.shape(parameters: List<String>, result: String, static: Boolean? = null): Boolean =
        isMethod && paramTypeNames == parameters && returnTypeName == result &&
            (static == null || Modifier.isStatic(modifiers) == static)

    fun Method.shape(parameters: List<String>, result: String, static: Boolean? = null): Boolean =
        parameterTypes.map { it.name } == parameters && returnType.name == result &&
            (static == null || Modifier.isStatic(modifiers) == static)

    fun DexSearch.inClass(owner: String, signature: MethodMatcher.() -> Unit = {}): List<MethodData> =
        methods { matcher { declaredClass(owner); signature() } }

    /** Inspect a bounded call graph, never execute a candidate. */
    fun MethodData.reaches(depth: Int, predicate: (MethodData) -> Boolean): Boolean =
        predicate(this) || depth > 0 && invokes.filter { it.className.startsWith("com.kugou.") }
            .any { it.reaches(depth - 1, predicate) }

    /** Obfuscated request fields without a proved value mapping must not be guessed after updates. */
    fun verified(loader: ClassLoader, app: String, key: String, owner: Class<*>, name: String,
                 result: Class<*>, vararg parameters: Class<*>): Method = HostInterfaces.method(
        loader, "$app.$key.v1", { owner.getMethod(name, *parameters) },
        { it.shape(parameters.map(Class<*>::getName), result.name) },
        discover = { emptyList() },
    )
}
