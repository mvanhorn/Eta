package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import java.io.File

/** 用于断连后的界面展示，并在连接时恢复缺失的已提交助手配置；不接收离线修改，Hook 不直接读取此历史。 */
internal class EnhancementSettingsHistory(context: Context) {
    private val appContext = context.applicationContext
    private val snapshot = appContext.getSharedPreferences(HISTORY_NAME, Context.MODE_PRIVATE)

    val hasConnected: Boolean get() = snapshot.getBoolean("has_connected", false)
    val hasUsedSystemizer: Boolean get() = snapshot.getBoolean("has_used_systemizer", false)

    fun reconcileConnected(preferences: SharedPreferences): Boolean = runCatching {
        val targetKey = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET
        val takeoverKey = Prefs.Keys.POWER_KEY_TAKEOVER
        val autoKey = Prefs.Keys.ASSISTANT_AUTO_CONFIG
        val restoreTakeover = !preferences.contains(takeoverKey)
        val restoreTarget = !preferences.contains(targetKey) && restoreTakeover
        val restoreAuto = !preferences.contains(autoKey)
        if (!restoreTarget && !restoreTakeover && !restoreAuto) return@runCatching true
        // 历史文件可被写成任意大小。超限时不解析，也不复制整份快照。
        if (historyFile().length() > MAX_HISTORY_BYTES) return@runCatching false
        val missing = mutableMapOf<String, Any>()
        if (restoreTarget) savedAssistantTarget()?.let { missing[targetKey] = it }
        if (restoreTakeover) savedBoolean(takeoverKey)?.let { missing[takeoverKey] = it }
        if (restoreAuto) savedBoolean(autoKey)?.let { missing[autoKey] = it }
        if (missing.isEmpty()) return@runCatching true
        val committed = preferences.edit().apply {
            missing.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                }
            }
        }.commit()
        if (committed) return@runCatching true
        // RemotePreferences.commit 先改缓存再走 binder。false 时这些键仍可见，下次会当成已恢复并跳过写入。
        val leaked = missing.keys.filter { preferences.contains(it) }
        if (leaked.isNotEmpty()) {
            preferences.edit().apply {
                leaked.forEach { remove(it) }
            }.commit()
        }
        false
    }.getOrDefault(false)

    private fun historyFile(): File = File(appContext.dataDir, "shared_prefs/$HISTORY_NAME.xml")

    private fun savedAssistantTarget(): String? {
        val target = try {
            snapshot.getString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, null)
        } catch (_: ClassCastException) {
            null
        } ?: return null
        val maxChars = PowerAssistantTarget.entries.maxOf { it.persistedValue.length }
        if (target.length > maxChars) return null
        return target.takeIf { candidate ->
            PowerAssistantTarget.entries.any { it.persistedValue == candidate }
        }
    }

    private fun savedBoolean(key: String): Boolean? {
        if (!snapshot.contains(key)) return null
        return try {
            snapshot.getBoolean(key, false)
        } catch (_: ClassCastException) {
            null
        }
    }

    fun captureConnected(preferences: SharedPreferences) {
        snapshot.edit().apply {
            putBoolean("has_connected", true)
            Prefs.Keys.BOOLEAN_DEFAULTS.filterKeys { it !in Prefs.Keys.LOCAL_AGENT_KEYS }.forEach { (key, default) ->
                if ((key == Prefs.Keys.POWER_KEY_TAKEOVER || key == Prefs.Keys.ASSISTANT_AUTO_CONFIG) &&
                    !preferences.contains(key)
                ) return@forEach
                putBoolean(key, preferences.getBoolean(key, default))
            }
            if (preferences.contains(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET)) {
                putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, preferences.getString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, null))
            }
        }.apply()
    }

    fun checked(key: String, default: Boolean): Boolean = snapshot.getBoolean(key, default)

    fun powerTarget(): PowerAssistantTarget = Prefs.powerAssistantTarget(snapshot)

    fun recordCommittedBoolean(key: String, value: Boolean) {
        if (key !in Prefs.Keys.LOCAL_AGENT_KEYS) snapshot.edit().putBoolean(key, value).apply()
    }

    fun recordCommittedTarget(target: PowerAssistantTarget) {
        snapshot.edit().putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.persistedValue).apply()
    }

    fun recordSystemizerUse() {
        snapshot.edit().putBoolean("has_used_systemizer", true).apply()
    }

    internal companion object {
        const val MAX_HISTORY_BYTES = 64 * 1024
        private const val HISTORY_NAME = "eta_enhancement_ui_history"
    }
}
