package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookProgress
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.BackupRestoreLock
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.planBookRestore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.ObjectNotFoundException
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private val backupGateway by lazy { GlobalContext.get().get<BackupSettingsGateway>() }

    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    private val configMutex = Mutex()
    private val syncMutex = Mutex()
    private var appliedConfig: AppliedWebDavConfig? = null

    fun triggerBookshelfSync() {
        if (!backupGateway.currentSettings.syncBookProgress) return
        Coroutine.async {
            downloadAllBookProgress()
        }
    }

    @Volatile
    var authorization: Authorization? = null
        private set

    @Volatile
    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    val rootWebDavUrl: String
        get() {
            val configUrl = backupGateway.currentSettings.webDavUrl.trim()
            var url = if (configUrl.isEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            backupGateway.currentSettings.webDavDir.trim().let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        configMutex.withLock {
            val config = AppliedWebDavConfig(
                url = backupGateway.currentSettings.webDavUrl,
                account = backupGateway.currentSettings.webDavAccount,
                password = backupGateway.currentSettings.webDavPassword,
                dir = backupGateway.currentSettings.webDavDir,
            )
            if (appliedConfig == config) return

            kotlin.runCatching {
                authorization = null
                defaultBookWebDav = null
                if (config.account.isNotEmpty() && config.password.isNotEmpty()) {
                    val mAuthorization = Authorization(config.account, config.password)
                    checkAuthorization(mAuthorization)
                    WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                    WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                    WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                    WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                    val rootBooksUrl = "${rootWebDavUrl}books/"
                    defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                    authorization = mAuthorization
                }
                appliedConfig = config
            }
        }
    }

    private data class AppliedWebDavConfig(
        val url: String,
        val account: String,
        val password: String,
        val dir: String,
    )

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            //appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        val auth = authorization ?: throw NoStackTraceException("webDav没有配置")
        val files = WebDav(rootWebDavUrl, auth).listFiles()
        val backupNameSet = Backup.backupFileNames
        val hasBackup = files.any {
            !it.isDir && (backupNameSet.contains(it.displayName)
                    || it.displayName.endsWith(".json")
                    || it.displayName.endsWith(".xml"))
        }
        if (hasBackup) {
            names.add("WebDAV 备份")
        }
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String = "") {
        val auth = authorization ?: throw WebDavException("webDav未配置")
        if (!NetworkUtils.isAvailable()) throw WebDavException("网络不可用")
        BackupRestoreLock.withLock {
            FileUtils.delete(Backup.backupPath)
            val backupDir = File(Backup.backupPath).apply { mkdirs() }
            val remoteFiles = WebDav(rootWebDavUrl, auth).listFiles()
            val backupNameSet = Backup.backupFileNames
            var hasDownloadedAny = false
            remoteFiles.forEach { webDavFile ->
                currentCoroutineContext().ensureActive()
                if (!webDavFile.isDir && (backupNameSet.contains(webDavFile.displayName)
                            || webDavFile.displayName.endsWith(".json")
                            || webDavFile.displayName.endsWith(".xml"))
                ) {
                    val localFile = File(backupDir, webDavFile.displayName)
                    WebDav(webDavFile.path, auth).downloadTo(localFile.absolutePath, true)
                    hasDownloadedAny = true
                }
            }
            if (!hasDownloadedAny) {
                throw WebDavException("WebDAV 上未找到备份文件")
            }
            Restore.restoreUnzipped(Backup.backupPath)
            LocalConfig.lastBackup = System.currentTimeMillis()
            FileUtils.delete(Backup.backupPath)
            downBgs()
        }
    }

    suspend fun hasBackUp(backUpName: String = ""): Boolean {
        val auth = authorization ?: return false
        if (backUpName.isNotEmpty()) {
            val url = "$rootWebDavUrl$backUpName"
            return WebDav(url, auth).exists()
        }
        return kotlin.runCatching {
            val files = WebDav(rootWebDavUrl, auth).listFiles()
            val backupNameSet = Backup.backupFileNames
            files.any {
                !it.isDir && (backupNameSet.contains(it.displayName)
                        || it.displayName.endsWith(".json")
                        || it.displayName.endsWith(".xml"))
            }
        }.getOrDefault(false)
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            val auth = authorization ?: return@runCatching null
            var lastBackupFile: WebDavFile? = null
            val backupNameSet = Backup.backupFileNames
            WebDav(rootWebDavUrl, auth).listFiles().forEach { webDavFile ->
                if (!webDavFile.isDir && (backupNameSet.contains(webDavFile.displayName)
                            || webDavFile.displayName.endsWith(".json")
                            || webDavFile.displayName.endsWith(".xml"))
                ) {
                    if (lastBackupFile == null || webDavFile.lastModify > lastBackupFile.lastModify) {
                        lastBackupFile = webDavFile
                    }
                }
            }
            lastBackupFile
        }
    }

    suspend fun testWebDav(): Boolean {
        return kotlin.runCatching {
            val account = backupGateway.currentSettings.webDavAccount
            val password = backupGateway.currentSettings.webDavPassword
            if (account.isNullOrEmpty() || password.isNullOrEmpty()) {
                appCtx.toastOnUi("账号或密码为空")
                return false
            }

            val auth = Authorization(account, password)
            checkAuthorization(auth)

            appCtx.toastOnUi("WebDAV 服务可用")
            true
        }.getOrElse {
            it.printStackTrace()
            if (it !is WebDavException) {
                appCtx.toastOnUi(it.message ?: "未知错误")
            }
            false
        }
    }

    /**
     * webDav备份
     * @param sourceDirPath 本地备份文件所在目录
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(sourceDirPath: String = Backup.backupPath) {
        if (!NetworkUtils.isAvailable()) return
        val auth = authorization ?: throw NoStackTraceException("webDav未配置")
        val sourceDir = File(sourceDirPath)
        if (!sourceDir.exists() || !sourceDir.isDirectory) return
        val files = sourceDir.listFiles() ?: return
        files.forEach { file ->
            currentCoroutineContext().ensureActive()
            if (file.isFile) {
                val putUrl = "$rootWebDavUrl${file.name}"
                WebDav(putUrl, auth).upload(file)
            }
        }
    }

    /**
     * 上传本地书籍到 WebDAV books/ 目录
     */
    suspend fun upLocalBooks(books: List<Book>) {
        val auth = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookWebDav = defaultBookWebDav ?: RemoteBookWebDav("${rootWebDavUrl}books/", auth).also {
            defaultBookWebDav = it
        }
        books.forEach { book ->
            currentCoroutineContext().ensureActive()
            kotlin.runCatching {
                if (book.isLocal) {
                    bookWebDav.upload(book)
                }
            }.onFailure { e ->
                currentCoroutineContext().ensureActive()
                AppLog.put("上传本地书籍失败: ${book.name}\n${e.localizedMessage}", e)
            }
        }
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrNull() ?: return
        val bgDir = appCtx.externalFiles.getFile("bg").createFolderIfNotExist()
        bgWebDavFiles.forEach { webDavFile ->
            currentCoroutineContext().ensureActive()
            if (!webDavFile.isDir) {
                val localFile = File(bgDir, webDavFile.displayName)
                if (!localFile.exists() || localFile.length() != webDavFile.size) {
                    kotlin.runCatching {
                        WebDav(webDavFile.path, authorization).downloadTo(localFile.absolutePath, true)
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                        AppLog.put("下载背景图失败: ${webDavFile.displayName}\n${it.localizedMessage}", it)
                    }
                }
            }
        }
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!backupGateway.currentSettings.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val remoteProgress = getBookProgress(book.name, book.author)
            if (remoteProgress != null) {
                val remoteIsNewer = remoteProgress.durChapterIndex > book.durChapterIndex ||
                        (remoteProgress.durChapterIndex == book.durChapterIndex && remoteProgress.durChapterPos > book.durChapterPos)
                if (remoteIsNewer) {
                    AppLog.put("云端阅读进度较新，跳过上传《${book.name}》 (云端: ${remoteProgress.durChapterTitle}, 本地: ${book.durChapterTitle})")
                    return
                }
            }
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            try {
                WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            } catch (e: Exception) {
                WebDav(bookProgressUrl, authorization).makeAsDir()
                WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            }
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(
        bookProgress: BookProgress,
        onSuccess: (() -> Unit)? = null
    ): Boolean {
        try {
            val authorization = authorization ?: return false
            if (!backupGateway.currentSettings.syncBookProgress) return false
            if (!NetworkUtils.isAvailable()) return false
            val remoteProgress = getBookProgress(bookProgress.name, bookProgress.author)
            if (remoteProgress != null) {
                val remoteIsNewer = remoteProgress.durChapterIndex > bookProgress.durChapterIndex ||
                        (remoteProgress.durChapterIndex == bookProgress.durChapterIndex && remoteProgress.durChapterPos > bookProgress.durChapterPos)
                if (remoteIsNewer) {
                    AppLog.put("云端阅读进度较新，跳过上传《${bookProgress.name}》")
                    return false
                }
            }
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            try {
                WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            } catch (e: Exception) {
                WebDav(bookProgressUrl, authorization).makeAsDir()
                WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            }
            onSuccess?.invoke()
            return true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
            return false
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        return getBookProgress(book.name, book.author)
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(name: String, author: String): BookProgress? {
        val url = getProgressUrl(name, author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            if (it !is ObjectNotFoundException) {
                AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
            }
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        if (authorization == null) {
            upConfig()
        }
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return

        syncMutex.withLock {
            kotlin.runCatching {
                val rootWebDav = WebDav(rootWebDavUrl, authorization)
                val rootFiles = kotlin.runCatching { rootWebDav.listFiles() }.getOrDefault(emptyList())
                val rootFileMap = rootFiles.associateBy { it.displayName }

                val remoteBookshelfFile = rootFileMap["bookshelf.json"]
                val remoteBookGroupFile = rootFileMap["bookGroup.json"]
                val remoteDeletedBooksFile = rootFileMap["deletedBooks.json"]

                val bookProgressFiles = kotlin.runCatching {
                    WebDav(bookProgressUrl, authorization).listFiles()
                }.getOrDefault(emptyList())
                val progressMap = bookProgressFiles.associateBy { it.displayName }

                // 1. 先同步分组（确保后续新添加的书籍其分组在本地存在）
                if (remoteBookGroupFile != null) {
                    val groupJson = remoteBookGroupFile.download().toString(Charsets.UTF_8)
                    if (groupJson.isJson()) {
                        GSON.fromJsonArray<BookGroup>(groupJson).getOrNull()?.let { remoteGroups ->
                            val currentGroups = appDb.bookGroupDao.all
                            val currentGroupIds = currentGroups.map { it.groupId }.toSet()
                            val groupsToInsert = remoteGroups.filter { it.groupId !in currentGroupIds }
                            if (groupsToInsert.isNotEmpty()) {
                                appDb.bookGroupDao.insert(*groupsToInsert.toTypedArray())
                            }
                        }
                    }
                }

                // 2. 同步并合并被删除书籍记录（Tombstones）
                val localDeletedMap = LocalConfig.getDeletedBooksLog().toMutableMap()
                if (remoteDeletedBooksFile != null) {
                    val remoteDeletedJson = remoteDeletedBooksFile.download().toString(Charsets.UTF_8)
                    if (remoteDeletedJson.isJson()) {
                        GSON.fromJsonObject<Map<String, Long>>(remoteDeletedJson).getOrNull()?.let { remoteDeletedMap ->
                            remoteDeletedMap.forEach { (url, time) ->
                                val localTime = localDeletedMap[url] ?: 0L
                                if (time > localTime) {
                                    localDeletedMap[url] = time
                                }
                            }
                        }
                    }
                }
                val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                val activeDeletedMap = localDeletedMap.filterValues { it > cutoff }.toMutableMap()
                LocalConfig.setDeletedBooksLog(activeDeletedMap)

                var remoteBooks: List<Book>? = null
                if (remoteBookshelfFile != null) {
                    val json = remoteBookshelfFile.download().toString(Charsets.UTF_8)
                    if (json.isJson()) {
                        remoteBooks = GSON.fromJsonArray<Book>(json).getOrNull()
                    }
                }

                // 3. 过滤掉远程中已被删除的书籍，并清理已重新添加到书架的书籍墓碑
                val activeRemoteBooks = remoteBooks?.filter { rBook ->
                    val deletedTime = activeDeletedMap[rBook.bookUrl]
                    if (deletedTime == null) {
                        true
                    } else if (remoteBookshelfFile != null && remoteBookshelfFile.lastModify >= deletedTime) {
                        // bookshelf.json 修改时间晚于或等于删除时间，说明该书已被重新添加，墓碑已失效
                        activeDeletedMap.remove(rBook.bookUrl)
                        LocalConfig.removeDeletedBook(rBook.bookUrl)
                        true
                    } else {
                        // 删除发生在该书架文件上传之后，确属被删除书籍
                        rBook.durChapterTime > deletedTime
                    }
                }?.toMutableList() ?: mutableListOf()

                // 4. 检查本地是否有已被其他端删除的书籍（仅检查属于书架的书籍）
                val localBooks = appDb.bookDao.all.filterNot { it.isNotShelf }.toMutableList()
                val toDeleteLocally = mutableListOf<Book>()
                localBooks.removeAll { lBook ->
                    val deletedTime = activeDeletedMap[lBook.bookUrl]
                    if (deletedTime != null) {
                        // 如果远端最新书架里有这本书，且远端书架最后修改时间晚于删除记录时间，说明该书已被重新添加，不应删除
                        val isReAddedOnRemote = remoteBookshelfFile != null &&
                                remoteBooks?.any { it.bookUrl == lBook.bookUrl } == true &&
                                remoteBookshelfFile.lastModify >= deletedTime
                        if (isReAddedOnRemote) {
                            activeDeletedMap.remove(lBook.bookUrl)
                            LocalConfig.removeDeletedBook(lBook.bookUrl)
                            false
                        } else if (lBook.durChapterTime <= deletedTime) {
                            toDeleteLocally.add(lBook)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                if (toDeleteLocally.isNotEmpty()) {
                    appDb.runInTransaction {
                        toDeleteLocally.forEach { book ->
                            appDb.bookChapterDao.delByBook(book.bookUrl)
                            appDb.bookDao.delete(book)
                        }
                    }
                }

                // 5. 先应用 WebDAV 独立的阅读进度文件（阅读进度以 bookProgress 为单一真实可信来源）
                applyRemoteProgressFiles(progressMap)

                // 6. 双向智能合并书架元数据
                val refreshedLocalBooks = appDb.bookDao.all.filterNot { it.isNotShelf }.toMutableList()
                val localBookMap = refreshedLocalBooks.associateBy { it.bookUrl }
                val mergedBooks = linkedMapOf<String, Book>()
                var localChanged = false
                var remoteNeedsUpload = (remoteBooks != null && remoteBooks.size != activeRemoteBooks.size)

                activeRemoteBooks.forEach { rBook ->
                    rBook.upType()
                    rBook.removeType(BookType.notShelf)
                    val lBook = localBookMap[rBook.bookUrl]
                    if (lBook != null) {
                        if (lBook.isNotShelf) {
                            lBook.removeType(BookType.notShelf)
                            localChanged = true
                        }
                        // bookshelf.json 不得覆盖本地/bookProgress 中的阅读进度！
                        // 仅当本地从无阅读记录且没有独立进度文件时，才使用 rBook 的进度初始化
                        val progressFileName = getProgressFileName(lBook.name, lBook.author)
                        val hasDedicatedProgress = progressMap.containsKey(progressFileName)
                        if (!hasDedicatedProgress && lBook.durChapterIndex == 0 && lBook.durChapterPos == 0 && rBook.durChapterIndex > 0) {
                            lBook.durChapterIndex = rBook.durChapterIndex
                            lBook.durChapterPos = rBook.durChapterPos
                            lBook.durChapterTitle = rBook.durChapterTitle
                            lBook.durChapterTime = rBook.durChapterTime
                            localChanged = true
                        }
                        if (rBook.group != lBook.group || rBook.customTag != lBook.customTag || rBook.order != lBook.order || rBook.remark != lBook.remark) {
                            lBook.group = rBook.group
                            lBook.customTag = rBook.customTag
                            lBook.order = rBook.order
                            lBook.remark = rBook.remark
                            localChanged = true
                        }
                        mergedBooks[lBook.bookUrl] = lBook
                    } else {
                        // 远程有而本地书架没有：A端新添加的书籍同步到B端
                        // 检查本地数据库中是否已存在同 url 的临时探索/非书架书籍
                        val existingNonShelf = appDb.bookDao.getBook(rBook.bookUrl)
                        if (existingNonShelf != null) {
                            existingNonShelf.removeType(BookType.notShelf)
                            if (rBook.group != existingNonShelf.group || rBook.customTag != existingNonShelf.customTag || rBook.order != existingNonShelf.order) {
                                existingNonShelf.group = rBook.group
                                existingNonShelf.customTag = rBook.customTag
                                existingNonShelf.order = rBook.order
                            }
                            mergedBooks[existingNonShelf.bookUrl] = existingNonShelf
                            localChanged = true
                        } else {
                            if (rBook.isLocal) {
                                rBook.coverUrl = LocalBook.getCoverPath(rBook)
                            }
                            val progressFileName = getProgressFileName(rBook.name, rBook.author)
                            if (progressMap.containsKey(progressFileName)) {
                                getBookProgress(rBook.name, rBook.author)?.let { bp ->
                                    rBook.durChapterIndex = bp.durChapterIndex
                                    rBook.durChapterPos = bp.durChapterPos
                                    rBook.durChapterTitle = bp.durChapterTitle
                                    rBook.durChapterTime = bp.durChapterTime
                                }
                            }
                            mergedBooks[rBook.bookUrl] = rBook
                            localChanged = true
                        }
                    }
                }

                refreshedLocalBooks.forEach { lBook ->
                    if (!mergedBooks.containsKey(lBook.bookUrl)) {
                        mergedBooks[lBook.bookUrl] = lBook
                        remoteNeedsUpload = true
                    }
                }

                // 7. 应用到本地数据库
                if (localChanged) {
                    val currentDbBooks = appDb.bookDao.all.associateBy { it.bookUrl }
                    val toInsert = mutableListOf<Book>()
                    val toUpdate = mutableListOf<Book>()
                    mergedBooks.values.forEach { book ->
                        if (currentDbBooks.containsKey(book.bookUrl)) {
                            toUpdate.add(book)
                        } else {
                            toInsert.add(book)
                        }
                    }
                    appDb.runInTransaction {
                        if (toUpdate.isNotEmpty()) appDb.bookDao.update(*toUpdate.toTypedArray())
                        if (toInsert.isNotEmpty()) appDb.bookDao.insert(*toInsert.toTypedArray())
                    }
                }

                // 8. 远端需要更新时上传
                if (remoteNeedsUpload || remoteBookshelfFile == null || remoteDeletedBooksFile == null || toDeleteLocally.isNotEmpty()) {
                    val finalBooks = appDb.bookDao.all.filterNot { it.isNotShelf }
                    val finalGroups = appDb.bookGroupDao.all
                    uploadLocalBookshelfAndProgress(authorization, finalBooks, finalGroups, activeDeletedMap)
                }
            }.onFailure {
                currentCoroutineContext().ensureActive()
                AppLog.put("同步书架及阅读进度失败\n${it.localizedMessage}", it)
            }
        }
    }

    private suspend fun applyRemoteProgressFiles(progressMap: Map<String, WebDavFile>) {
        val books = appDb.bookDao.all
        books.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = progressMap[progressFileName] ?: return@forEach
            if (book.durChapterTime > 0L && webDavFile.lastModify <= book.durChapterTime && webDavFile.lastModify <= book.syncTime) {
                return@forEach
            }
            getBookProgress(book.name, book.author)?.let { bookProgress ->
                val remoteIsNewer = bookProgress.durChapterIndex > book.durChapterIndex ||
                        (bookProgress.durChapterIndex == book.durChapterIndex && bookProgress.durChapterPos > book.durChapterPos)
                if (remoteIsNewer) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = maxOf(book.durChapterTime, bookProgress.durChapterTime)
                    book.syncTime = maxOf(System.currentTimeMillis(), webDavFile.lastModify)
                    appDb.bookDao.update(book)
                } else {
                    book.syncTime = maxOf(book.syncTime, webDavFile.lastModify)
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    private suspend fun uploadLocalBookshelfAndProgress(
        authorization: Authorization,
        localBooks: List<Book>,
        localGroups: List<BookGroup>,
        deletedMap: Map<String, Long> = emptyMap()
    ) {
        val rootWebDav = WebDav(rootWebDavUrl, authorization)
        rootWebDav.makeAsDir()

        val shelfBooks = localBooks.filterNot { it.isNotShelf }
        val bookshelfJson = GSON.toJson(shelfBooks)
        WebDav(rootWebDavUrl + "bookshelf.json", authorization).upload(bookshelfJson.toByteArray())

        val bookGroupJson = GSON.toJson(localGroups)
        WebDav(rootWebDavUrl + "bookGroup.json", authorization).upload(bookGroupJson.toByteArray())

        // 无论 deletedMap 是否为空，都上传 deletedBooks.json，以确保远端被清空的墓碑记录能同步清理，避免僵尸删除
        val deletedJson = GSON.toJson(deletedMap)
        WebDav(rootWebDavUrl + "deletedBooks.json", authorization).upload(deletedJson.toByteArray())

        val localLocalBooks = shelfBooks.filter { it.isLocal }
        if (localLocalBooks.isNotEmpty()) {
            upLocalBooks(localLocalBooks)
        }
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

}
