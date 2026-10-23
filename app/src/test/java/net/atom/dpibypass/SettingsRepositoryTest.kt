package net.atom.dpibypass

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.data.Settings
import net.atom.dpibypass.data.SettingsRepository
import net.atom.dpibypass.social.TestMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Gerçek DataStore (JVM, geçici dosya) üzerinde kalıcılık ve atomiklik. */
class SettingsRepositoryTest {
    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() { dir = Files.createTempDirectory("settings-test").toFile() }
    @After fun tearDown() { scopes.forEach { it.cancel() }; dir.deleteRecursively() }

    /** Her çağrı yeni bir DataStore örneği: süreç yeniden başlamış gibi. */
    private fun repo(): SettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "dpi_settings.preferences_pb") }) { false }
    }

    @Test fun rapidConcurrentAppTogglesLoseNothing() = runBlocking {
        val r = repo()
        r.setAppFilterMode(AppFilterMode.Include)
        (1..40).map { i -> async(Dispatchers.IO) { r.updateSelectedApps { it + "app.$i" } } }.awaitAll()
        (1..40 step 2).map { i -> async(Dispatchers.IO) { r.updateSelectedApps { it - "app.$i" } } }.awaitAll()
        assertEquals((2..40 step 2).map { "app.$it" }.toSet(), r.settings.first().selectedApps)
    }

    @Test fun snapshotReadModifyWriteWasTheLostUpdateMechanism() = runBlocking {
        // Eski AppViewModel.toggleApp: eski StateFlow anlık görüntüsünden yeni küme kurup tümünü yazar.
        val r = repo()
        val snapshot = r.settings.first().selectedApps
        r.setSelectedApps(snapshot + "a")
        r.setSelectedApps(snapshot + "b") // ikinci dokunuş henüz güncellenmemiş anlık görüntüyü kullanır
        assertEquals("Eski yöntemde 'a' kaybolur", setOf("b"), r.settings.first().selectedApps)
        r.updateSelectedApps { it + "a" }
        assertEquals(setOf("a", "b"), r.settings.first().selectedApps)
    }

    @Test fun socialPreferencesSurviveProcessRestart() = runBlocking {
        val first = repo()
        first.setSocialAccessEnabled(true)
        first.setSocialAccessMode(TestMode.AUTO_BETA)
        first.updateSocialPlatforms { it + "youtube" + "discord" + "not-a-platform" }
        first.setSocialTargetUrl("https://youtu.be/abcdefghijk")
        scopes.forEach { it.cancel() }; scopes.clear()
        val restarted = repo().settings.first()
        assertTrue(restarted.loaded)
        assertTrue(restarted.socialAccessEnabled)
        assertEquals(TestMode.AUTO_BETA, restarted.socialAccessMode)
        assertEquals(setOf("youtube", "discord"), restarted.socialPlatforms)
        assertEquals("https://youtu.be/abcdefghijk", restarted.socialTargetUrl)
    }

    @Test fun defaultStateFlowValueIsMarkedNotLoaded() {
        assertFalse("UI başlangıç değeri gerçek tercih sayılmamalı", Settings().loaded)
    }

    @Test fun discordOnlyIsAtomicAndRestorable() = runBlocking {
        val r = repo()
        r.setAppFilterMode(AppFilterMode.Exclude)
        r.setSelectedApps(setOf("com.bank"))
        r.enableOnlyApps(setOf("com.discord"))
        r.enableOnlyApps(setOf("com.discord")) // ikinci dokunuş yedeği ezmez
        r.settings.first().let {
            assertEquals(AppFilterMode.Include, it.appFilterMode)
            assertEquals(setOf("com.discord"), it.selectedApps)
            assertTrue(it.scopeBackupAvailable)
        }
        r.restoreScopeBackup()
        r.settings.first().let {
            assertEquals(AppFilterMode.Exclude, it.appFilterMode)
            assertEquals(setOf("com.bank"), it.selectedApps)
            assertFalse(it.scopeBackupAvailable)
        }
    }

    @Test fun bringIntoScopeFixesOnlyWhatTheModeNeeds() = runBlocking {
        val r = repo()
        r.setAppFilterMode(AppFilterMode.Include); r.setSelectedApps(setOf("com.discord"))
        r.bringIntoScope(setOf("com.instagram.android"))
        assertEquals(setOf("com.discord", "com.instagram.android"), r.settings.first().selectedApps)
        r.setAppFilterMode(AppFilterMode.Exclude)
        r.bringIntoScope(setOf("com.discord"))
        assertEquals(setOf("com.instagram.android"), r.settings.first().selectedApps)
        assertEquals(AppFilterMode.Exclude, r.settings.first().appFilterMode)
    }

    @Test fun corruptSettingsFileIsReportedNotSilentlyDefaulted() = runBlocking {
        File(dir, "dpi_settings.preferences_pb").writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04))
        val s = repo().settings.first()
        assertTrue(s.readError)
        assertTrue(s.loaded)
    }
}
