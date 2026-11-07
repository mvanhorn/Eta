package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancementSettingsHistoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyPreservesDisplayedValuesWithoutWritingBackToTheRemoteSource() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.GEMINI.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasConnected)
        history.captureConnected(source)
        history.recordCommittedBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false)
        val restored = EnhancementSettingsHistory(context)
        assertTrue(restored.hasConnected)
        assertFalse(restored.checked(Prefs.Keys.HOTWORD_SELF_HEAL, true))
        assertEquals(PowerAssistantTarget.GEMINI, restored.powerTarget())
        assertTrue(source.getBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false))
    }

    @Test
    fun connectionRestoresCommittedAssistantSettingsBeforeCapture() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear().putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, "{}").commit()
        val history = EnhancementSettingsHistory(context)
        history.recordCommittedTarget(PowerAssistantTarget.ETA)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)

        assertTrue(history.reconcileConnected(source))
        assertEquals(PowerAssistantTarget.ETA, Prefs.powerAssistantTarget(source))
        assertTrue(source.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
        history.captureConnected(source)
        val restored = EnhancementSettingsHistory(context)
        assertEquals(PowerAssistantTarget.ETA, restored.powerTarget())
        assertTrue(restored.checked(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
        val repaired = source.all
        assertTrue(restored.reconcileConnected(source))
        assertEquals(repaired, source.all)
        assertEquals(
            mapOf(
                Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON to "{}",
                Prefs.Keys.POWER_KEY_ASSISTANT_TARGET to PowerAssistantTarget.ETA.persistedValue,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to true,
            ),
            source.all,
        )
    }

    @Test
    fun existingRemoteValuesTakePrecedenceIncludingLegacyFalse() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        val history = EnhancementSettingsHistory(context)
        history.recordCommittedTarget(PowerAssistantTarget.ETA)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
        for (target in listOf(PowerAssistantTarget.OEM, PowerAssistantTarget.GEMINI)) {
            source.edit().clear()
                .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.persistedValue)
                .putBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false).commit()
            val original = source.all
            assertTrue(history.reconcileConnected(source))
            assertEquals(original, source.all)
            assertEquals(target, Prefs.powerAssistantTarget(source))
        }
        for (takeover in listOf(true, false)) {
            source.edit().clear().putBoolean(Prefs.Keys.POWER_KEY_TAKEOVER, takeover).commit()
            assertTrue(history.reconcileConnected(source))
            assertFalse(source.contains(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET))
            assertEquals(
                if (takeover) PowerAssistantTarget.GEMINI else PowerAssistantTarget.OEM,
                Prefs.powerAssistantTarget(source),
            )
        }
    }

    @Test
    fun captureDoesNotInventAssistantHistoryOrEraseMissingCommittedValues() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear().commit()
        val history = EnhancementSettingsHistory(context)
        assertTrue(history.reconcileConnected(source))
        assertTrue(source.all.isEmpty())
        history.captureConnected(source)
        assertTrue(EnhancementSettingsHistory(context).reconcileConnected(source))
        assertTrue(source.all.isEmpty())

        history.recordCommittedTarget(PowerAssistantTarget.ETA)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
        history.captureConnected(source)
        assertTrue(source.all.isEmpty())
        val restored = EnhancementSettingsHistory(context)
        assertEquals(PowerAssistantTarget.ETA, restored.powerTarget())
        assertTrue(restored.checked(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
    }

    @Test
    fun legacyHistoryRestoresOnlyTheBooleanAndInvalidTargetsAreNotRestored() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear().commit()
        val history = EnhancementSettingsHistory(context)
        history.recordCommittedBoolean(Prefs.Keys.POWER_KEY_TAKEOVER, true)
        assertTrue(history.reconcileConnected(source))
        assertEquals(mapOf(Prefs.Keys.POWER_KEY_TAKEOVER to true), source.all)
        assertEquals(PowerAssistantTarget.GEMINI, Prefs.powerAssistantTarget(source))
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)
            .edit().putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, "invalid").commit()
        assertTrue(history.reconcileConnected(source))
        assertFalse(source.contains(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET))
        source.edit().clear().commit()
        assertTrue(history.reconcileConnected(source))
        assertEquals(mapOf(Prefs.Keys.POWER_KEY_TAKEOVER to true), source.all)
    }

    @Test
    fun failedRecoveryPreservesHistoryAndCanRetry() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear().commit()
        val history = EnhancementSettingsHistory(context)
        history.recordCommittedTarget(PowerAssistantTarget.ETA)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
        val snapshot = context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)
        val original = snapshot.all
        for (throws in listOf(false, true)) {
            val failing = object : SharedPreferences by source {
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by source.edit() {
                    override fun commit(): Boolean {
                        if (throws) error("commit failed")
                        return false
                    }
                }
            }
            assertFalse(history.reconcileConnected(failing))
            assertEquals(original, snapshot.all)
            assertTrue(source.all.isEmpty())
            assertFalse(history.hasConnected)
        }
        assertTrue(EnhancementSettingsHistory(context).reconcileConnected(source))
        history.captureConnected(source)
        assertTrue(history.hasConnected)
        assertEquals(PowerAssistantTarget.ETA, Prefs.powerAssistantTarget(source))
        assertTrue(source.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
    }

    @Test
    fun failedCommitCacheIsNotTreatedAsASuccessfulRecovery() {
        val cache = context.getSharedPreferences("test_remote_cache", Context.MODE_PRIVATE)
        val persisted = context.getSharedPreferences("test_remote_persisted", Context.MODE_PRIVATE)
        cache.edit().clear().commit()
        persisted.edit().clear().commit()
        var persistWrites = false
        val remote = object : SharedPreferences by cache {
            override fun edit(): SharedPreferences.Editor {
                val cacheEditor = cache.edit()
                val persistedEditor = persisted.edit()
                return object : SharedPreferences.Editor by cacheEditor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        cacheEditor.putString(key, value)
                        persistedEditor.putString(key, value)
                        return this
                    }

                    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                        cacheEditor.putBoolean(key, value)
                        persistedEditor.putBoolean(key, value)
                        return this
                    }

                    override fun remove(key: String?): SharedPreferences.Editor {
                        cacheEditor.remove(key)
                        persistedEditor.remove(key)
                        return this
                    }

                    override fun commit(): Boolean {
                        check(cacheEditor.commit())
                        if (!persistWrites) return false
                        return persistedEditor.commit()
                    }
                }
            }
        }
        val history = EnhancementSettingsHistory(context)
        history.recordCommittedTarget(PowerAssistantTarget.ETA)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)

        assertFalse(history.reconcileConnected(remote))
        assertFalse(remote.contains(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET))
        assertFalse(remote.contains(Prefs.Keys.ASSISTANT_AUTO_CONFIG))
        assertTrue(persisted.all.isEmpty())

        assertFalse(EnhancementSettingsHistory(context).reconcileConnected(remote))
        assertTrue(persisted.all.isEmpty())
        assertFalse(remote.contains(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET))

        persistWrites = true
        assertTrue(EnhancementSettingsHistory(context).reconcileConnected(remote))
        assertEquals(PowerAssistantTarget.ETA, Prefs.powerAssistantTarget(remote))
        assertEquals(PowerAssistantTarget.ETA, Prefs.powerAssistantTarget(persisted))
        assertTrue(persisted.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_ASSISTANT_TARGET to PowerAssistantTarget.ETA.persistedValue,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to true,
            ),
            persisted.all,
        )
    }

    @Test
    fun oversizedHistoryFileIsNotParsedOrRestored() {
        val historyPrefs = context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)
        historyPrefs.edit()
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.ETA.persistedValue)
            .putBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
            .commit()
        val historyFile = historyFile()
        assertTrue(historyFile.isFile)
        historyFile.writeBytes(ByteArray(EnhancementSettingsHistory.MAX_HISTORY_BYTES + 1))

        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear().commit()
        assertFalse(EnhancementSettingsHistory(context).reconcileConnected(source))
        assertTrue(source.all.isEmpty())
        assertEquals(PowerAssistantTarget.ETA.persistedValue, historyPrefs.getString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, null))
    }

    @Test
    fun oversizedHistoryDoesNotBlockAuthoritativeRemoteValues() {
        val historyPrefs = context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)
        historyPrefs.edit()
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.ETA.persistedValue)
            .commit()
        historyFile().writeBytes(ByteArray(EnhancementSettingsHistory.MAX_HISTORY_BYTES + 1))
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.OEM.persistedValue)
            .putBoolean(Prefs.Keys.POWER_KEY_TAKEOVER, false)
            .putBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false)
            .commit()
        val original = source.all
        assertTrue(EnhancementSettingsHistory(context).reconcileConnected(source))
        assertEquals(original, source.all)
    }

    @Test
    fun existingSystemizerEntrySurvivesLossOfCurrentRootAccess() {
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasUsedSystemizer)
        history.recordSystemizerUse()
        assertTrue(EnhancementSettingsHistory(context).hasUsedSystemizer)
    }

    private fun historyFile(): File =
        File(context.applicationContext.dataDir, "shared_prefs/eta_enhancement_ui_history.xml")
}
