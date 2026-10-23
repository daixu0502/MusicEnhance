package vip.chener.musicenhance.adapter.qq

import java.lang.reflect.Method

/** Derive the IPC contract from its semantic factory, including after host obfuscation. */
internal class QQPlayProcessApi(loader: ClassLoader) {
    val getEnvironment: Method = QQInterfaceRules.environment(loader)
    val type: Class<*> = getEnvironment.returnType
}
