package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.ContextWrapper
import com.jcraft.jsch.JSch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.ssh.SshCredentialStore
import me.rerere.rikkahub.data.ssh.SshToolSecretSanitizer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.spec.SecretKeySpec

class SshSecurityTest {
    @get:Rule val folder = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(null) {
        override fun getFilesDir() = folder.root
        override fun getNoBackupFilesDir() = folder.root.resolve("no_backup").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    } }
    private val vault by lazy { SshCredentialStore(folder.root.resolve("ssh_credentials.enc"), Json) {
        SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    } }
    private val dao = object : SshHostDao {
        override suspend fun getAll(): List<SshHostEntity> = error("Must not access hosts")
        override suspend fun getByName(name: String): SshHostEntity? = error("Must not access hosts")
        override suspend fun upsert(host: SshHostEntity) = error("Must not save hosts")
        override suspend fun delete(host: SshHostEntity) = error("Must not delete hosts")
        override suspend fun deleteByName(name: String) = error("Must not delete hosts")
    }
    private val repo by lazy { SshHostRepository(dao, vault) }

    @Test fun `forbidden SSH commands are blocked before network or host lookup`() = runBlocking {
        val adHoc = sshExecTool(context, SshToolSecretSanitizer(vault))
        val saved = sshExecSavedTool(context, repo)
        for (command in listOf("rm -rf /", "reboot", "dd if=/dev/zero of=/dev/block/x")) {
            val escaped = kotlinx.serialization.json.JsonPrimitive(command).toString()
            val adHocResult = adHoc.execute(Json.parseToJsonElement("""{"host":"example","user":"me","command":$escaped,"password":"secret","background":true}"""))
            val savedResult = saved.execute(Json.parseToJsonElement("""{"name":"server","command":$escaped,"background":true}"""))
            listOf(adHocResult, savedResult).forEach { result ->
                assertTrue((result.single() as UIMessagePart.Text).text.contains("blocked_command"))
            }
            assertTrue(runOnSession(JSch().getSession("me", "example"), command, 100).toString().contains("blocked_command"))
        }
    }

    @Test fun `SSH and SFTP tools request approval including personal data reads`() {
        val tools = listOf(sshExecTool(context, SshToolSecretSanitizer(vault)), saveSshHostTool(repo),
            listSshHostsTool(repo), deleteSshHostTool(repo), sshExecSavedTool(context, repo),
            sshUploadTool(context, repo), sshDownloadTool(context, repo), forgetSshHostKeyTool(context))
        assertEquals(8, tools.size)
        tools.forEach { assertTrue(it.name, it.needsApproval(JsonObject(emptyMap()))) }
    }

    @Test fun `shell code from stdin also uses command guard while ordinary file data remains data`() {
        assertNotNull(blockedSshCommand("sh", "reboot"))
        assertNotNull(blockedSshCommand("/bin/bash -s", "rm -rf /"))
        assertNull(blockedSshCommand("cat > /tmp/note.txt", "Documenting reboot command"))
    }

    @Test fun `conventional shell wrappers retain stdin in guard and HARDLINE inputs`() {
        val wrappers = listOf("env sh", "env -i PATH=/bin /bin/bash -s", "sudo -u root sh", "sudo -u root -s", "su root",
            "su -c sh", "busybox sh", "/system/bin/toybox sh", "env sudo busybox ash", "env -S 'sh -s'")
        wrappers.forEach { wrapper ->
            assertEquals(wrapper, listOf(wrapper, "reboot"), sshCommandInputs(wrapper, "reboot"))
            assertNotNull(wrapper, blockedSshCommand(wrapper, "reboot"))
            assertTrue(wrapper, sshCommandInputs(wrapper, "setenforce 0").any {
                me.rerere.rikkahub.root.RootApprovalPolicy.reason(it) != null
            })
        }
        listOf("cat > /tmp/note", "env cat > /tmp/note", "sudo cat > /tmp/note",
            "busybox cat > /tmp/note", "su -c 'cat > /tmp/note'").forEach { command ->
            assertEquals(command, listOf(command), sshCommandInputs(command, "reboot"))
            assertNull(command, blockedSshCommand(command, "reboot"))
        }
    }

    @Test fun `SFTP cannot read or overwrite private credential files`() = runBlocking {
        val privateFile = folder.root.resolve("no_backup/private.enc").apply { parentFile?.mkdirs(); writeText("sentinel") }
        for (tool in listOf(sshUploadTool(context, repo), sshDownloadTool(context, repo))) {
            val path = kotlinx.serialization.json.JsonPrimitive(privateFile.absolutePath).toString()
            try {
                tool.execute(Json.parseToJsonElement("""{"name":"server","local_path":$path,"remote_path":"/tmp/file"}"""))
                fail("Private path was reachable")
            } catch (failure: IllegalArgumentException) {
                assertTrue(failure.message.orEmpty().contains("Путь вне"))
            }
        }
        assertEquals("sentinel", privateFile.readText())
    }
}
