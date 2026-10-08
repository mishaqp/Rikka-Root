package me.rerere.rikkahub.data.datastore

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.control.McpControlSecretStore
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the actual production DataStore factory and restore hook, without new migration APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class McpLegacyPersistenceIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val opened = mutableListOf<DataStore<Preferences>>()
    private var previousProvider: Provider? = null
    private val canaries = listOf("legacy-header-canary", "legacy-client-secret-canary", "legacy-access-canary", "legacy-refresh-canary")

    @Before
    fun setup() {
        val files = temporary.newFolder("files")
        val noBackup = temporary.newFolder("no-backup")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
        }
        previousProvider = Security.getProvider("AndroidKeyStore")
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(FixtureKeyStoreProvider())
        failKeyLookup = false
        singletonField().set(null, null)
    }

    @After
    fun cleanup(): Unit = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val singleton = singletonField().get(null) as? DataStore<Preferences>
        (opened + listOfNotNull(singleton)).distinct().forEach { closeDataStore(it) }
        singletonField().set(null, null)
        Security.removeProvider("AndroidKeyStore")
        previousProvider?.let(Security::addProvider)
        failKeyLookup = false
    }

    @Test
    fun `first production DataStore emission has no legacy plaintext and retains working secrets`() = runBlocking {
        val original = legacyConfig()
        seedLegacyPreferences(original)
        val store = productionFactory()
        val first = store.data.first()
        assertProtected(first, original)
        assertProtected(store.data.first(), original)
    }

    @Test
    fun `actual restoreBeforeInitialization never persists restored MCP plaintext`() = runBlocking {
        val original = legacyConfig()
        SettingsStore.restoreBeforeInitialization(context, Settings(mcpServers = listOf(original)))
        @Suppress("UNCHECKED_CAST")
        val store = singletonField().get(null) as DataStore<Preferences>
        assertProtected(store.data.first(), original)
    }

    @Test
    fun `actual ordinary update persists references and emits protected settings`() = runBlocking {
        val current = Settings()
        val settingsStore = settingsStoreForUpdates(current)
        val original = legacyConfig()
        val emitted = mutableListOf<Settings>()
        val observer = launch(Dispatchers.Unconfined) { settingsStore.settingsFlow.collect { emitted += it } }
        try {
            settingsStore.update(current.copy(mcpServers = listOf(original)))
            @Suppress("UNCHECKED_CAST")
            val persisted = singletonField().get(null) as DataStore<Preferences>
            assertProtected(persisted.data.first(), original)
            emitted.forEach { published ->
                val serialized = JsonInstant.encodeToString(published)
                canaries.forEach { assertFalse("Ordinary update emitted private MCP credentials", serialized.contains(it)) }
            }
            assertEquals(original.id, settingsStore.settingsFlow.value.mcpServers.single().id)
            assertTrue(McpControlSecretStore.isReference(settingsStore.settingsFlow.value.mcpServers.single().commonOptions.headers.single().second))
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `actual ordinary update with vault failure preserves previous settings and preferences`() = runBlocking {
        val current = Settings()
        val settingsStore = settingsStoreForUpdates(current)
        @Suppress("UNCHECKED_CAST")
        val persisted = singletonField().get(null) as DataStore<Preferences>
        val before = persisted.data.first()
        failKeyLookup = true
        val emitted = mutableListOf<Settings>()
        val observer = launch(Dispatchers.Unconfined) { settingsStore.settingsFlow.collect { emitted += it } }
        try {
            val failure = runCatching {
                settingsStore.update(current.copy(mcpServers = listOf(legacyConfig())))
            }.exceptionOrNull()
            assertTrue("Unavailable vault must reject the update", failure != null)
            assertEquals("Failed protection must not publish the supplied plaintext settings", listOf(current), emitted)
            assertEquals("Failed protection must not replace persisted settings", before, persisted.data.first())
            canaries.forEach { assertFalse(failure!!.stackTraceToString().contains(it)) }
            assertFalse(context.noBackupFilesDir.walkTopDown().any { it.isFile })
        } finally {
            observer.cancelAndJoin()
        }
    }

    private suspend fun settingsStoreForUpdates(current: Settings): SettingsStore {
        SettingsStore.restoreBeforeInitialization(context, current)
        // An already cancelled scope prevents the constructor's background collector
        // from starting. The real constructor, public update, persistence and vault run;
        // the read model's initial snapshot is controlled without Koin or background UI.
        val scope = AppScope().apply { cancel() }
        return SettingsStore(context, scope).also { it.settingsFlow.value = current }
    }

    private suspend fun seedLegacyPreferences(config: McpServerConfig) {
        val job = SupervisorJob()
        val seeded = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { context.preferencesDataStoreFile("settings") },
        )
        seeded.edit {
            it[SettingsStore.VERSION] = 4
            it[SettingsStore.MCP_SERVERS] = JsonInstant.encodeToString<List<McpServerConfig>>(listOf(config))
        }
        job.cancelAndJoin()
    }

    @Suppress("UNCHECKED_CAST")
    private fun productionFactory(): DataStore<Preferences> {
        val factory = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
            .getDeclaredMethod("createSettingsDataStore", Context::class.java)
        factory.isAccessible = true
        return (factory.invoke(null, context) as DataStore<Preferences>).also(opened::add)
    }

    private fun singletonField() = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").also { it.isAccessible = true }

    private suspend fun closeDataStore(store: DataStore<Preferences>) {
        // The factory owns its default scope. Close it to release DataStore's file lock
        // and prevent background actors from outliving the temporary test directory.
        var delegate: Any = store
        while (delegate.javaClass.declaredFields.none { it.name == "scope" }) {
            delegate = delegate.javaClass.getDeclaredField("delegate").also { it.isAccessible = true }.get(delegate)
        }
        val scope = delegate.javaClass.getDeclaredField("scope").also { it.isAccessible = true }.get(delegate) as CoroutineScope
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private fun assertProtected(preferences: Preferences, original: McpServerConfig) {
        val serialized = preferences[SettingsStore.MCP_SERVERS]!!
        canaries.forEach { assertFalse("MCP plaintext escaped the production persistence hook", serialized.contains(it)) }
        val protected = JsonInstant.decodeFromString<List<McpServerConfig>>(serialized).single()
        assertEquals(original.id, protected.id)
        assertEquals(original.commonOptions.name, protected.commonOptions.name)
        val vault = McpControlSecretStore(context)
        val header = protected.commonOptions.headers.single().second
        assertTrue(McpControlSecretStore.isReference(header))
        assertEquals("Bearer ${canaries[0]}", vault.resolve(header))
        val oauth = protected.commonOptions.oauth!!
        assertEquals(canaries[1], vault.resolve(oauth.clientSecret!!))
        assertEquals(canaries[2], vault.resolve(oauth.accessToken!!))
        assertEquals(canaries[3], vault.resolve(oauth.refreshToken!!))
        context.noBackupFilesDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val contents = file.readBytes().toString(Charsets.ISO_8859_1)
            canaries.forEach { assertFalse("Vault file contains plaintext", contents.contains(it)) }
        }
    }

    private fun legacyConfig() = McpServerConfig.StreamableHTTPServer(
        url = "https://example.org/mcp",
        commonOptions = McpCommonOptions(
            name = "legacy-server",
            headers = listOf("Authorization" to "Bearer ${canaries[0]}"),
            oauth = McpOAuthState(enabled = true, clientId = "public-client", clientSecret = canaries[1],
                accessToken = canaries[2], refreshToken = canaries[3]),
        ),
    )

    /** Only Android's key lookup is a fixture; production encryption uses real AES/GCM. */
    class FixtureKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "MCP migration key lookup fixture") {
        init { put("KeyStore.AndroidKeyStore", FixtureKeyStoreSpi::class.java.name) }
    }

    class FixtureKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? {
            check(!failKeyLookup) { "Test AndroidKeyStore is unavailable" }
            return fixtureKey
        }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = error("Fixture key is preinstalled")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = error("Fixture key is preinstalled")
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = error("No certificates in fixture")
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(listOf("rikka_root_mcp_control_headers"))
        override fun engineContainsAlias(alias: String?): Boolean = alias == "rikka_root_mcp_control_headers"
        override fun engineSize(): Int = 1
        override fun engineIsKeyEntry(alias: String?): Boolean = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    companion object {
        private val fixtureKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        @Volatile private var failKeyLookup = false
    }
}
