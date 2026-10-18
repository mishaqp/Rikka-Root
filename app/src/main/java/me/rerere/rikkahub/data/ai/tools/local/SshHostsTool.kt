// Ported from ExTV/rikkahub-agent, adapted for Rikka-Root permissions/security (AGPL v3).
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository

/**
 * Save an SSH host so it can be referenced by name in subsequent calls instead of passing
 * credentials every time. Replaces any existing host with the same name.
 */
fun saveSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "save_ssh_host",
    description = "Сохранить SSH-хост под коротким именем для ssh_exec_saved, ssh_upload и ssh_download. Используйте для повторных подключений; для разовой команды — ssh_exec. Хост с тем же именем заменяется. Нужны host, user и пароль либо приватный ключ; безопаснее ввести секреты в настройках SSH приложения. Порт по умолчанию 22, допустимо 1–65535. Секреты не возвращаются.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Короткое имя записи. Существующая запись с этим именем будет заменена.") })
                put("host", buildJsonObject { put("type", "string"); put("description", "Имя DNS или IP-адрес SSH-сервера.") })
                put("port", buildJsonObject { put("type", "integer"); put("description", "Порт SSH: 1–65535, по умолчанию 22."); put("minimum", 1); put("maximum", 65535); put("default", 22) })
                put("user", buildJsonObject { put("type", "string"); put("description", "Имя пользователя на SSH-сервере.") })
                put("password", buildJsonObject { put("type", "string"); put("description", "Пароль для входа, если не используется private_key. Безопаснее ввести в настройках SSH.") })
                put("private_key", buildJsonObject { put("type", "string"); put("description", "Полный приватный ключ PEM/OpenSSH вместо пароля. Безопаснее сохранить в настройках SSH.") })
                put("passphrase", buildJsonObject { put("type", "string"); put("description", "Необязательная парольная фраза для зашифрованного private_key.") })
            },
            required = listOf("name", "host", "user")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val p = repo.toolSecrets.resolveArgs("save_ssh_host", input).jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: name")
        val host = p["host"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: host")
        val user = p["user"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: user")
        val port = p["port"]?.jsonPrimitive?.intOrNull ?: 22
        val password = p["password"]?.jsonPrimitive?.contentOrNull
        val privateKey = p["private_key"]?.jsonPrimitive?.contentOrNull
        val passphrase = p["passphrase"]?.jsonPrimitive?.contentOrNull
        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "Укажите password или private_key. Безопаснее сохранить хост в настройках SSH и использовать ssh_exec_saved.") }.toString()
            ))
        }
        try {
            repo.upsert(SshHostEntity(
                name = name, host = host, port = port, user = user,
                createdAtMs = System.currentTimeMillis(),
            ).apply {
                this.password = password
                this.privateKey = privateKey
                this.passphrase = passphrase
            })
        } catch (error: Exception) {
            return@Tool sshFailureParts(error,
                "Проверьте доступность хранилища SSH в настройках приложения и повторите сохранение хоста.",
                buildJsonObject { put("name", name); put("host", host); put("port", port); put("user", user) })
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/** List saved hosts. Secrets omitted — only metadata + which auth method is configured. */
fun listSshHostsTool(repo: SshHostRepository): Tool = Tool(
    name = "list_ssh_hosts",
    description = "Получить сохранённые SSH-хосты: name, host, port, user, has_password и has_private_key. Используйте, чтобы выбрать точное name для ssh_exec_saved или SFTP. Пароли и ключи не возвращаются; для изменения записи используйте save_ssh_host или настройки SSH.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    needsApproval = { true },
    execute = {
        val all = try { repo.getAll() } catch (error: Exception) {
            return@Tool sshFailureParts(error,
                "Проверьте доступность сохранённых хостов в настройках SSH и повторите получение списка.")
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("hosts", buildJsonArray {
                all.forEach { h ->
                    addJsonObject {
                        put("name", h.name)
                        put("host", h.host)
                        put("port", h.port)
                        put("user", h.user)
                        put("has_password", h.hasPassword)
                        put("has_private_key", h.hasPrivateKey)
                    }
                }
            })
        }.toString()))
    }
)

/** Delete a saved host by name. */
fun deleteSshHostTool(repo: SshHostRepository): Tool = Tool(
    name = "delete_ssh_host",
    description = "Удалить сохранённый SSH-хост по точному name из list_ssh_hosts. Используйте для удаления ненужной записи; для изменения адреса или учётных данных — save_ssh_host либо настройки SSH. Удаление записи не удаляет файлы на сервере.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Точное имя сохранённого хоста из list_ssh_hosts.") })
            },
            required = listOf("name")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: error("Укажите обязательный параметр: name")
        try { repo.deleteByName(name) } catch (error: Exception) {
            return@Tool sshFailureParts(error,
                "Проверьте список хостов в настройках SSH перед повторным удалением.",
                buildJsonObject { put("name", name) })
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("name", name)
        }.toString()))
    }
)

/**
 * Forget the stored SSH host key(s) for a host. Use after the user explicitly confirms they
 * reinstalled the remote — the next connect will trust the new key per accept-new policy.
 *
 * The host argument is the actual hostname/IP (not a saved-host name). If the saved host's
 * underlying address changed too, look it up via list_ssh_hosts first.
 */
fun forgetSshHostKeyTool(context: Context): Tool = Tool(
    name = "ssh_forget_host_key",
    description = "Удалить сохранённый ключ сервера SSH из known_hosts по host. Используйте только после явного подтверждения пользователем переустановки сервера: следующее подключение примет новый ключ. Не применяйте для обычного сбоя входа или обхода предупреждения — смена ключа может означать подмену сервера. Для просмотра адреса используйте list_ssh_hosts, для изменения пароля — настройки SSH.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("host", buildJsonObject { put("type", "string"); put("description", "Фактический hostname или IP, а не имя записи. Удаляет сохранённый ключ только после подтверждения пользователя.") })
            },
            required = listOf("host")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val host = input.jsonObject["host"]?.jsonPrimitive?.contentOrNull
            ?: error("Укажите обязательный параметр: host")
        val removed = try { forgetHostKey(context, host) } catch (error: Exception) {
            return@Tool sshFailureParts(error,
                "Проверьте доступность хранилища known_hosts. Повторяйте удаление ключа SSH только после подтверждения пользователя.",
                buildJsonObject { put("host", host) })
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("host", host)
            put("keys_removed", removed)
        }.toString()))
    }
)

/** Run a command on a saved host without re-passing credentials. */
fun sshExecSavedTool(context: Context, repo: SshHostRepository): Tool = Tool(
    name = "ssh_exec_saved",
    description = "Выполнить команду на сохранённом SSH-хосте по name из list_ssh_hosts без повторной передачи секретов. Для нового хоста сначала используйте настройки SSH или save_ssh_host; для интерактивного терминала — termux_session_start. Возвращает stdout, stderr и exit_code; stdout ограничен 8000 байт, stderr 2000 байт. Таймаут 1–300 секунд, по умолчанию 30. background=true возвращает PID отсоединённой команды и несовместим со stdin. Разрушительные и системные команды требуют явного согласования с пользователем.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Точное имя сохранённого хоста из list_ssh_hosts.") })
                put("command", buildJsonObject { put("type", "string"); put("description", "Команда оболочки на удалённом сервере.") })
                put("stdin", buildJsonObject { put("type", "string"); put("description", "Данные для стандартного ввода команды, затем EOF; при отсутствии сразу EOF. Несовместимо с background=true.") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "Отсоединить команду через nohup с перенаправлением потоков и вернуть PID. По умолчанию false; stdin недоступен."); put("default", false) })
                put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Общий таймаут операции в секундах: 1–300, по умолчанию 30."); put("minimum", 1); put("maximum", 300); put("default", 30) })
            },
            required = listOf("name", "command")
        )
    },
    needsApproval = { true },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: name")
        val command = p["command"]?.jsonPrimitive?.contentOrNull ?: error("Укажите обязательный параметр: command")
        val stdin = p["stdin"]?.jsonPrimitive?.contentOrNull
        blockedSshCommand(command, stdin)?.let { blocked -> return@Tool listOf(UIMessagePart.Text(blocked.toString())) }
        val background = p["background"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val timeoutSec = (p["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 300)
        val h = try { repo.getByName(name) } catch (error: Exception) {
            return@Tool sshFailureParts(error,
                "Проверьте сохранённый хост и его учётные данные в настройках SSH, затем повторите вызов.",
                buildJsonObject { put("name", name) })
        }
            ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "Сохранённый хост не найден: $name. Проверьте name через list_ssh_hosts или сохраните хост в настройках SSH.") }.toString()
            ))
        val auth = SshAuth(password = h.password, privateKey = h.privateKey, passphrase = h.passphrase)
        if (!auth.isUsable()) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "У сохранённого хоста нет доступного пароля или приватного ключа. Сохраните хост заново в настройках SSH.") }.toString()
            ))
        }
        if (background && stdin != null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "Нельзя одновременно передать stdin и background: отсоединённая команда читает /dev/null.") }.toString()
            ))
        }
        val effectiveCommand = if (background) wrapDetachedCommand(command) else command
        val output = SshCommandOutput()
        val payload = runCancellableSshOp(timeoutSec * 1000L, output) { sessionRef ->
            execOneShot(context, h.host, h.port, h.user, auth, effectiveCommand, timeoutSec * 1000, sessionRef, stdin, output)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
