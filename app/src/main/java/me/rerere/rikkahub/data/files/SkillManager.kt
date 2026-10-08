package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        const val MAX_SKILL_FILE_BYTES: Long = 512L * 1024L
    }

    /** Thrown by [readCached] when a skill file exceeds [MAX_SKILL_FILE_BYTES]. */
    class SkillFileTooLargeException(val lengthBytes: Long) :
        java.io.IOException("Skill file is $lengthBytes bytes, over the ${MAX_SKILL_FILE_BYTES}-byte cap")

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> {
        return root.listFiles()
            // 跳过隐藏目录，如原子写入残留的 .<name>.staging.N.tmp
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir, builtin)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveReadableSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(readCached(skillFile))
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveReadableSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return readCached(skillFile)
    }

    /**
     * Phase 16 audit fix — read-only accessor backing the `skill_get_content` LLM tool.
     *
     * Returns the parsed frontmatter (name / description / format / source label) plus the
     * markdown body and an optional args schema, or null when [skillName] does not resolve
     * to a skill directory with a readable SKILL.md. Reads from the same on-disk location
     * the install tools write to — no new persistence layer.
     *
     * `format` and `sourceLabel` are pulled from the optional `format:` / `source-url:`
     * frontmatter keys the install path writes; both are absent on hand-authored skills.
     * `argsSchema` is the optional `args_schema:` frontmatter key, parsed as a flat JSON
     * object string (the frontmatter parser is line-oriented, so a skill author declares it
     * on a single line); malformed or non-object values are dropped rather than surfaced.
     */
    fun getContent(skillName: String): SkillContent? {
        val skillDir = resolveReadableSkillDir(skillName) ?: return null
        val skillFile = skillDir.resolve("SKILL.md")
        if (!skillFile.exists()) return null
        val raw = runCatching { readCached(skillFile) }.getOrNull() ?: return null
        val frontmatter = SkillFrontmatterParser.parse(raw)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
        val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
        val argsSchema = frontmatter["args_schema"]?.takeIf { it.isNotBlank() }?.let { rawSchema ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawSchema)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject
        }
        return SkillContent(
            name = name,
            description = description,
            format = frontmatter["format"]?.takeIf { it.isNotBlank() },
            sourceLabel = frontmatter["source-url"]?.takeIf { it.isNotBlank() },
            contentMd = SkillFrontmatterParser.extractBody(raw),
            argsSchema = argsSchema,
        )
    }

    /**
     * Read the body of an arbitrary file inside [skillName]'s directory (e.g. an
     * `auto_load_path` such as `SOUL.md`). Returns null if the skill or relative path
     * does not resolve, or the file does not exist. Backed by the same mtime-aware cache
     * used by [readSkillBody] / [readSkillContent], so the per-turn auto-load reads in
     * `SkillsTools.systemPrompt` are O(stat) on cache hit, not O(read).
     */
    fun readSkillFileCached(skillName: String, relativePath: String): String? {
        val skillDir = resolveReadableSkillDir(skillName) ?: return null
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return null
        if (!target.exists()) return null
        return readCached(target)
    }

    // ---- mtime-aware in-memory cache for SKILL.md and auto-loaded sidecars ----

    private data class CachedFile(val lastModifiedMs: Long, val length: Long, val text: String)

    private val bodyCache = java.util.concurrent.ConcurrentHashMap<String, CachedFile>()

    /**
     * Return [file]'s text, serving from [bodyCache] when both `lastModified` and
     * `length` match the cached values — invalidation falls out of the file system,
     * no explicit write hook needed (write paths necessarily change the mtime). Falls
     * back to a direct read on any cache miss / stat failure / anomalous length jump.
     */
    private fun readCached(file: File): String {
        val key = file.absolutePath
        val mtime = file.lastModified()
        val len = file.length()
        // Refuse oversized files before reading or caching them so a huge skill file
        // can never be loaded whole into memory or pinned in bodyCache. Checked on
        // every call (not just cache misses) so a file that grows past the cap after
        // a small initial read is caught on the next access.
        if (len > MAX_SKILL_FILE_BYTES) {
            bodyCache.remove(key)
            throw SkillFileTooLargeException(len)
        }
        val hit = bodyCache[key]
        if (hit != null && hit.lastModifiedMs == mtime && hit.length == len) {
            return hit.text
        }
        val text = file.readText()
        bodyCache[key] = CachedFile(mtime, len, text)
        return text
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理内置技能的启用状态
        if (!skillDir.exists()) return@withContext false
        val isBundled = name in bundledSkillNames()
        val deleted = skillDir.deleteRecursively()
        // 删除的是覆盖内置技能的同名用户技能时，内置技能会重新生效，保留启用状态
        if (deleted && listBuiltinSkills().none { it.name == name }) {
            settingsStore.update { settings ->
                settings.copy(
                    deletedBundledSkills = deletedBundledSkillsAfterDelete(settings.deletedBundledSkills, name, isBundled),
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * Clear [name]'s deletion record (if any) and reseed, so a deliberate reinstall from
     * the skill catalog restores a bundled skill the user previously deleted (#84). A no-op
     * data-wise for a skill that was never deleted; the reseed pass below is still safe to
     * run since [decideSeedAction] skips it when the bundled hash already matches.
     */
    suspend fun reinstallBundledSkill(name: String) {
        settingsStore.update { settings ->
            settings.copy(deletedBundledSkills = settings.deletedBundledSkills - name)
        }
        seedDefaultSkillsIfNeeded()
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveReadableSkillDir(skillName)

    /**
     * Doctor support, read-only: the skill names currently bundled in
     * `assets/default-skills/`, so the Doctor can tell a "bundled" skill directory
     * (seeded from assets) apart from a user-added one without duplicating the seeding
     * logic in [seedDefaultSkillsIfNeeded].
     */
    fun bundledSkillNames(): Set<String> = runCatching {
        context.assets.list("default-skills").orEmpty().toSet()
    }.getOrDefault(emptySet())

    /**
     * Doctor support, read-only: hash [skillName]'s currently-bundled assets the same
     * way [seedDefaultSkillsIfNeeded] does, so the Doctor can compare it against the
     * on-disk `.core-bundled-hash` sentinel without re-deriving the hashing algorithm.
     */
    fun bundledSkillAssetHash(skillName: String): String = computeBundledSkillHash("default-skills", skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        val parent = target.parentFile ?: return false
        // 先写同目录临时文件再 rename 覆盖，避免写到一半失败时损坏原文件；
        // IO 异常（如 mkdirs 失败导致 FileNotFoundException）转为返回 false，不向调用方抛出
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    /**
     * Copy any default skills bundled in `assets/default-skills/<name>/` into the user's
     * filesDir on first launch, but only if they have not already been installed before.
     * "Before" is tracked with a sentinel marker file inside each seeded skill so subsequent
     * launches skip the copy without checking individual file mtimes — and so the user can
     * delete a default skill and we will not silently re-install it.
     */
    suspend fun seedDefaultSkillsIfNeeded() {
        val assetRoot = "default-skills"
        val assetMgr = context.assets
        val skillNames = try {
            assetMgr.list(assetRoot).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "seedDefaultSkillsIfNeeded: cannot list assets", e)
            return
        }
        // #84: names the user explicitly deleted. Read once per pass; deleteSkill() /
        // reinstallBundledSkill() are the only writers, both persisted before this can run.
        // settingsFlow starts as Settings.dummy() (init = true, empty set) until DataStore
        // loads, and this runs at process start, so wait for the real value.
        val deletedBundledSkills = settingsStore.settingsFlow.first { !it.init }.deletedBundledSkills
        for (skillName in skillNames) {
            val targetDir = SkillPaths.resolveSkillDir(getSkillsDir(), skillName) ?: continue
            val deletedByUser = skillName in deletedBundledSkills

            // Read the bundled SKILL.md once to decide what to do.
            val bundledSkillMd = runCatching {
                assetMgr.open("$assetRoot/$skillName/SKILL.md").bufferedReader().use { it.readText() }
            }.getOrNull()
            val isCoreSkill = bundledSkillMd?.let { content ->
                SkillFrontmatterParser.parse(content)["auto_load"]?.equals("true", ignoreCase = true) == true
            } == true

            val sentinel = targetDir.resolve(".seeded")
            val coreVersionFile = targetDir.resolve(".core-bundled-hash")

            if (isCoreSkill) {
                // Core skills (auto_load=true) re-seed whenever the bundled content changes
                // — typically across an APK upgrade. This keeps SOUL/HEARTBEAT/TOOLS in
                // sync with the app version while still allowing the user to edit between
                // upgrades (their edits stick until we ship a new bundled version). Core
                // skills are always ours to manage, so the sentinel does not gate this.
                val bundledHash = computeBundledSkillHash(assetRoot, skillName)
                val currentHash = if (coreVersionFile.exists()) coreVersionFile.readText().trim() else ""
                val decision = decideSeedAction(
                    ownedByUs = true,
                    targetDirExists = targetDir.exists(),
                    targetDirNonEmpty = false, // unused when ownedByUs is true
                    bundledHash = bundledHash,
                    storedHash = currentHash,
                    deletedByUser = deletedByUser,
                )
                if (decision == SeedDecision.SKIP) continue
                try {
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    copyAssetSkill(assetRoot, skillName, targetDir)
                    sentinel.writeText(System.currentTimeMillis().toString())
                    coreVersionFile.writeText(bundledHash)
                    Log.i(TAG, "seedDefaultSkillsIfNeeded: re-seeded core skill $skillName (hash=$bundledHash)")
                } catch (e: Exception) {
                    Log.w(TAG, "seedDefaultSkillsIfNeeded: failed to re-seed core skill $skillName", e)
                }
                continue
            }

            // Non-core (lazy) skills: seeded once, then re-seeded only when the bundled
            // content changes AND the directory is one we seeded ourselves (tracked by the
            // .seeded sentinel, reusing the same .core-bundled-hash file the core path uses).
            // A directory that exists with no sentinel is user-owned — the user may have
            // manually installed and then deleted the skill, or created a same-named one —
            // and is never overwritten, preserving the original "seed once, then leave
            // alone" contract for anything we did not create ourselves.
            val bundledHash = computeBundledSkillHash(assetRoot, skillName)
            val storedHash = if (coreVersionFile.exists()) coreVersionFile.readText().trim() else ""
            val decision = decideSeedAction(
                ownedByUs = sentinel.exists(),
                targetDirExists = targetDir.exists(),
                targetDirNonEmpty = targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true,
                bundledHash = bundledHash,
                storedHash = storedHash,
                deletedByUser = deletedByUser,
            )
            if (decision == SeedDecision.SKIP) continue
            try {
                if (targetDir.exists()) targetDir.deleteRecursively()
                copyAssetSkill(assetRoot, skillName, targetDir)
                sentinel.writeText(System.currentTimeMillis().toString())
                coreVersionFile.writeText(bundledHash)
                Log.i(TAG, "seedDefaultSkillsIfNeeded: seeded $skillName (hash=$bundledHash)")
            } catch (e: Exception) {
                Log.w(TAG, "seedDefaultSkillsIfNeeded: failed to seed $skillName", e)
            }
        }
    }

    /**
     * Compute a stable hash over every file in the bundled skill (recursively, in sorted
     * order so the result is deterministic across runs). Used as the "version" of the
     * bundled core skill so we know when to re-seed the user's local copy.
     *
     * Asset-read failures are mixed into the digest as a stable marker rather than
     * silently skipped so a transient read failure can't change the hash on a later
     * successful read (which would trigger a spurious re-seed and clobber user edits).
     */
    private fun computeBundledSkillHash(assetRoot: String, skillName: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val readFailMarker = "<<read-failed>>".toByteArray()
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty().toList().sorted()
            for (child in children) {
                val childPath = "$path/$child"
                if (isAssetDirectory(childPath)) {
                    walk(childPath)
                } else {
                    md.update(child.toByteArray())  // include name so renames bump the hash
                    val ok = runCatching {
                        context.assets.open(childPath).use { input ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n <= 0) break
                                md.update(buf, 0, n)
                            }
                        }
                    }.isSuccess
                    if (!ok) {
                        Log.w(TAG, "computeBundledSkillHash: read failed for $childPath; marker mixed into digest")
                        md.update(readFailMarker)
                    }
                }
            }
        }
        walk("$assetRoot/$skillName")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAssetSkill(assetRoot: String, skillName: String, targetDir: File) {
        val assetMgr = context.assets
        targetDir.mkdirs()
        val children = assetMgr.list("$assetRoot/$skillName").orEmpty()
        for (child in children) {
            val source = "$assetRoot/$skillName/$child"
            if (isAssetDirectory(source)) {
                // Recurse into directories — including the genuinely-empty case where
                // listing returns []. The recursive call mkdirs the empty target
                // and exits cleanly without copying anything.
                copyAssetSkill("$assetRoot/$skillName", child, targetDir.resolve(child))
                continue
            }
            val outFile = targetDir.resolve(child)
            assetMgr.open(source).use { input ->
                outFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /**
     * Reliably distinguish an asset directory from an asset file. `AssetManager.list`
     * returns an empty array for both files and empty directories, which the previous
     * heuristic confused — any bundled skill shipping an empty placeholder subdir would
     * crash the seed when we later tried to `assetMgr.open` it as a file.
     *
     * The trick: try to open it as a file. Files succeed; directories throw. This is
     * the same approach AOSP's sample code recommends.
     */
    private fun isAssetDirectory(path: String): Boolean {
        return runCatching { context.assets.open(path).close() }.isFailure
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveReadableSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveReadableSkillDir(skillName: String): File? {
        val local = SkillPaths.resolveSkillDir(getSkillsDir(), skillName) ?: return null
        if (local.resolve("SKILL.md").isFile) return local
        return findSkill(skillName)?.skillDir
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                autoLoad = frontmatter["auto_load"]?.equals("true", ignoreCase = true) == true,
                autoLoadPath = frontmatter["auto_load_path"]?.takeIf { it.isNotBlank() },
                skillDir = skillDir,
                builtin = builtin,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val autoLoad: Boolean = false,
    val autoLoadPath: String? = null,
    val skillDir: File,
    /** 内置技能，来自 assets 解压，只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

internal enum class SeedDecision { SKIP, SEED }

/**
 * #84: the deletedBundledSkills set after [SkillManager.deleteSkill] deletes [deletedName].
 * Only a bundled skill's name is ever recorded — deleting a user-created or otherwise
 * non-bundled skill leaves [current] untouched, since [decideSeedAction] only consults this
 * set for names that actually appear in `assets/default-skills/`. Extracted out of
 * [SkillManager] so it is testable without a [android.content.Context]. Pure.
 */
internal fun deletedBundledSkillsAfterDelete(
    current: Set<String>,
    deletedName: String,
    isBundled: Boolean,
): Set<String> = if (isBundled) current + deletedName else current

/**
 * Pure decision for whether a bundled skill directory should be (re)written from assets.
 * Shared by both the core (`auto_load: true`) and non-core seeding branches of
 * [SkillManager.seedDefaultSkillsIfNeeded] so they cannot drift apart, and extracted out of
 * [SkillManager] itself so it is testable without a [android.content.Context] /
 * `AssetManager`.
 *
 * @param ownedByUs whether this directory is ours to overwrite: always `true` for core
 * skills (they are unconditionally ours to manage), or `sentinel.exists()` for non-core
 * skills (a directory that exists with no `.seeded` sentinel was never created by us and is
 * user-owned).
 * @param targetDirNonEmpty ignored when [ownedByUs] is `true`.
 * @param deletedByUser #84: `true` when this skill's name is in [me.rerere.rikkahub.data.datastore.Settings.deletedBundledSkills]
 * (the user explicitly deleted it via [SkillManager.deleteSkill]). Checked first and skips
 * unconditionally, in both the core and non-core branches, so a deliberate delete is never
 * mistaken for "never seeded" and silently re-created.
 */
internal fun decideSeedAction(
    ownedByUs: Boolean,
    targetDirExists: Boolean,
    targetDirNonEmpty: Boolean,
    bundledHash: String,
    storedHash: String,
    deletedByUser: Boolean = false,
): SeedDecision {
    if (deletedByUser) return SeedDecision.SKIP
    if (!ownedByUs) {
        // Never touch a directory we did not create ourselves.
        return if (targetDirExists && targetDirNonEmpty) SeedDecision.SKIP else SeedDecision.SEED
    }
    return if (bundledHash == storedHash) SeedDecision.SKIP else SeedDecision.SEED
}


/**
 * Phase 16 audit fix — read-only view of a skill's SKILL.md, returned by
 * [SkillManager.getContent] and surfaced to the LLM via the `skill_get_content` tool.
 *
 * @property contentMd the markdown body with the YAML frontmatter stripped.
 * @property argsSchema optional structured arg description from the `args_schema:`
 * frontmatter key; null when the skill doesn't declare one.
 */
data class SkillContent(
    val name: String,
    val description: String,
    val format: String? = null,
    val sourceLabel: String? = null,
    val contentMd: String,
    val argsSchema: kotlinx.serialization.json.JsonObject? = null,
)
