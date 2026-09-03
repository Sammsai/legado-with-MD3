package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookProgress
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.BackupRestoreLock
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.planBookRestore
import io.legado.app.lib.webdav.Authorization
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
    private var appliedConfig: AppliedWebDavConfig? = null

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
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
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
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
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
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return

        kotlin.runCatching {
            val rootWebDav = WebDav(rootWebDavUrl, authorization)
            val rootFiles = kotlin.runCatching { rootWebDav.listFiles() }.getOrDefault(emptyList())
            val rootFileMap = rootFiles.associateBy { it.displayName }

            val remoteBookshelfFile = rootFileMap["bookshelf.json"]
            val remoteBookGroupFile = rootFileMap["bookGroup.json"]

            val bookProgressFiles = kotlin.runCatching {
                WebDav(bookProgressUrl, authorization).listFiles()
            }.getOrDefault(emptyList())
            val progressMap = bookProgressFiles.associateBy { it.displayName }

            val localBooks = appDb.bookDao.all
            val localGroups = appDb.bookGroupDao.all

            val localLastOpTime = calculateLocalLastOpTime(localBooks)

            var remoteLastOpTime = 0L
            var remoteBooks: List<Book>? = null

            if (remoteBookshelfFile != null) {
                remoteLastOpTime = maxOf(remoteLastOpTime, remoteBookshelfFile.lastModify)
                val json = remoteBookshelfFile.download().toString(Charsets.UTF_8)
                if (json.isJson()) {
                    remoteBooks = GSON.fromJsonArray<Book>(json).getOrNull()
                }
            }

            if (remoteBookGroupFile != null) {
                remoteLastOpTime = maxOf(remoteLastOpTime, remoteBookGroupFile.lastModify)
            }

            remoteBooks?.forEach { rBook ->
                remoteLastOpTime = maxOf(
                    remoteLastOpTime,
                    rBook.durChapterTime,
                    rBook.lastCheckTime,
                    rBook.latestChapterTime,
                    rBook.syncTime
                )
            }

            bookProgressFiles.forEach { pFile ->
                remoteLastOpTime = maxOf(remoteLastOpTime, pFile.lastModify)
            }

            val bookshelfDiffers = remoteBooks != null && isBookshelfDifferent(localBooks, remoteBooks)

            if (bookshelfDiffers) {
                if (remoteLastOpTime > localLastOpTime) {
                    // 云端较新，以云端书架覆盖本地
                    applyRemoteBookshelf(remoteBooks!!, remoteBookGroupFile)
                    applyRemoteProgressFiles(progressMap)
                    LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, remoteLastOpTime)
                } else {
                    // 本地较新，应用云端进度中更新的部分后将本地书架及进度同步至云端
                    applyRemoteProgressFiles(progressMap)
                    uploadLocalBookshelfAndProgress(authorization, localBooks, localGroups)
                }
            } else {
                // 书架结构相同或无云端书架文件，正常双向/增量同步阅读进度
                applyRemoteProgressFiles(progressMap)
                if (remoteBookshelfFile == null && localBooks.isNotEmpty()) {
                    uploadLocalBookshelfAndProgress(authorization, localBooks, localGroups)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("同步书架及阅读进度失败\n${it.localizedMessage}", it)
        }
    }

    private fun calculateLocalLastOpTime(localBooks: List<Book>): Long {
        var lastOp = LocalConfig.lastBackup
        localBooks.forEach { book ->
            lastOp = maxOf(
                lastOp,
                book.durChapterTime,
                book.lastCheckTime,
                book.latestChapterTime,
                book.syncTime
            )
        }
        return lastOp
    }

    private fun isBookshelfDifferent(localBooks: List<Book>, remoteBooks: List<Book>): Boolean {
        if (localBooks.size != remoteBooks.size) return true
        val localMap = localBooks.associateBy { it.bookUrl }
        for (rBook in remoteBooks) {
            val lBook = localMap[rBook.bookUrl] ?: return true
            if (lBook.name != rBook.name ||
                lBook.author != rBook.author ||
                lBook.group != rBook.group ||
                lBook.order != rBook.order ||
                lBook.customTag != rBook.customTag ||
                lBook.origin != rBook.origin ||
                lBook.type != rBook.type
            ) {
                return true
            }
        }
        return false
    }

    private suspend fun applyRemoteBookshelf(
        remoteBooks: List<Book>,
        remoteBookGroupFile: WebDavFile?
    ) {
        val currentLocalBooks = appDb.bookDao.all
        remoteBooks.forEach { book ->
            book.upType()
        }
        val restorePlan = planBookRestore(
            restoredBooks = remoteBooks,
            existingBooks = currentLocalBooks,
            ignoreLocalBook = BackupConfig.ignoreLocalBook,
            locationStatus = { Restore.localBookLocationStatus(it) }
        )
        restorePlan.booksToUpsert
            .filter { book -> book.isLocal }
            .forEach { book -> book.coverUrl = LocalBook.getCoverPath(book) }

        appDb.runInTransaction {
            if (restorePlan.booksToDelete.isNotEmpty()) {
                appDb.bookDao.delete(*restorePlan.booksToDelete.toTypedArray())
            }
            if (restorePlan.booksToUpdate.isNotEmpty()) {
                appDb.bookDao.update(*restorePlan.booksToUpdate.toTypedArray())
            }
            if (restorePlan.booksToInsert.isNotEmpty()) {
                appDb.bookDao.insert(*restorePlan.booksToInsert.toTypedArray())
            }
        }

        if (remoteBookGroupFile != null) {
            val groupJson = remoteBookGroupFile.download().toString(Charsets.UTF_8)
            if (groupJson.isJson()) {
                GSON.fromJsonArray<BookGroup>(groupJson).getOrNull()?.let { groups ->
                    appDb.bookGroupDao.replaceAll(groups)
                }
            }
        }
    }

    private suspend fun applyRemoteProgressFiles(progressMap: Map<String, WebDavFile>) {
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = progressMap[progressFileName] ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    private suspend fun uploadLocalBookshelfAndProgress(
        authorization: Authorization,
        localBooks: List<Book>,
        localGroups: List<BookGroup>
    ) {
        val rootWebDav = WebDav(rootWebDavUrl, authorization)
        rootWebDav.makeAsDir()

        val bookshelfJson = GSON.toJson(localBooks)
        WebDav(rootWebDavUrl + "bookshelf.json", authorization).upload(bookshelfJson.toByteArray())

        val bookGroupJson = GSON.toJson(localGroups)
        WebDav(rootWebDavUrl + "bookGroup.json", authorization).upload(bookGroupJson.toByteArray())

        val localLocalBooks = localBooks.filter { it.isLocal }
        if (localLocalBooks.isNotEmpty()) {
            upLocalBooks(localLocalBooks)
        }
        localBooks.forEach { book ->
            uploadBookProgress(book)
        }
        LocalConfig.lastBackup = System.currentTimeMillis()
    }

}
