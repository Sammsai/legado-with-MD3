package io.legado.app.ui.file

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.help.IntentData
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.takePersistablePermissionSafely
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

class HandleFileActivity : BaseComposeActivity(transparent = true) {

    private val viewModel by viewModels<HandleFileViewModel>()
    private var mode = 0
    private var allowExtensions: Array<String>? = null
    private var sheetTitle: String = ""

    private var showSheet by mutableStateOf(true)
    private var showInputDialog by mutableStateOf(false)
    private var inputDirectoryPath by mutableStateOf("")

    private val selectDocTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                if (it.isContentScheme()) {
                    it.takePersistablePermissionSafely(this)
                }
                onResult(Intent().setData(it))
            } ?: finish()
        }

    private val selectDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                if (it.isContentScheme()) {
                    it.takePersistablePermissionSafely(this)
                }
                onResult(Intent().setData(it))
            } ?: finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra("mode", 0)
        allowExtensions = intent.getStringArrayExtra("allowExtensions")
        sheetTitle = intent.getStringExtra("title") ?: when (mode) {
            HandleFileContract.EXPORT -> getString(R.string.export)
            HandleFileContract.DIR -> getString(R.string.select_folder)
            else -> getString(R.string.select_file)
        }
        viewModel.errorLiveData.observe(this) {
            toastOnUi(it)
            finish()
        }
    }

    @Composable
    override fun Content() {
        FilePickerSheet(
            show = showSheet,
            onDismissRequest = {
                showSheet = false
                finish()
            },
            title = sheetTitle,
            onSelectSysDir = if (mode == HandleFileContract.DIR_SYS || mode == HandleFileContract.DIR || mode == HandleFileContract.EXPORT) {
                {
                    showSheet = false
                    runCatching {
                        selectDocTree.launch(null)
                    }.onFailure {
                        AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
                        finish()
                    }
                }
            } else null,
            onSelectSysFile = if (mode == HandleFileContract.FILE) {
                { types ->
                    showSheet = false
                    runCatching {
                        selectDoc.launch(types)
                    }.onFailure {
                        AppLog.put(getString(R.string.open_sys_dir_picker_error), it, true)
                        finish()
                    }
                }
            } else null,
            onManualInput = if (mode == HandleFileContract.DIR_SYS || mode == HandleFileContract.DIR || mode == HandleFileContract.EXPORT) {
                {
                    showSheet = false
                    showInputDialog = true
                }
            } else null,
            onUpload = if (mode == HandleFileContract.EXPORT && getFileData() != null) {
                {
                    showSheet = false
                    getFileData()?.let { (fileName, file, contentType) ->
                        viewModel.upload(fileName, file, contentType) { url ->
                            val uri = Uri.parse(url)
                            setResult(RESULT_OK, Intent().setData(uri))
                            finish()
                        }
                    } ?: finish()
                }
            } else null,
            allowExtensions = allowExtensions
        )

        if (showInputDialog) {
            AppAlertDialog(
                show = true,
                onDismissRequest = {
                    showInputDialog = false
                    finish()
                },
                title = stringResource(R.string.manual_input),
                content = {
                    AppTextField(
                        value = inputDirectoryPath,
                        onValueChange = { inputDirectoryPath = it },
                        placeholder = { Text(stringResource(R.string.enter_directory_path)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmText = stringResource(R.string.ok),
                onConfirm = {
                    val inputPath = inputDirectoryPath
                    if (inputPath.isBlank()) {
                        toastOnUi(getString(R.string.empty_directory_input))
                        return@AppAlertDialog
                    }
                    val file = File(inputPath)
                    if (file.exists() &&
                        file.isDirectory &&
                        isExternalStorage(file) &&
                        file.checkWrite()
                    ) {
                        showInputDialog = false
                        onResult(Intent().setData(Uri.fromFile(file)))
                    } else {
                        toastOnUi(getString(R.string.invalid_directory))
                    }
                },
                dismissText = stringResource(R.string.cancel),
                onDismiss = {
                    showInputDialog = false
                    finish()
                }
            )
        }
    }

    private fun isExternalStorage(path: File): Boolean {
        if (path.canonicalPath.startsWith(appCtx.externalFiles.parent!!)) {
            return false
        }
        try {
            if (Environment.isExternalStorageEmulated(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
        }
        try {
            if (Environment.isExternalStorageRemovable(path)) {
                return true
            }
        } catch (_: IllegalArgumentException) {
        }
        return false
    }

    private fun getFileData(): Triple<String, Any, String>? {
        val fileName = intent.getStringExtra("fileName")
        val file = intent.getStringExtra("fileKey")?.let {
            IntentData.get<Any>(it)
        }
        val contentType = intent.getStringExtra("contentType")
        if (fileName != null && file != null && contentType != null) {
            return Triple(fileName, file, contentType)
        }
        return null
    }

    private fun onResult(data: Intent) {
        val uri = data.data
        if (uri == null) {
            finish()
            return
        }
        if (mode == HandleFileContract.EXPORT) {
            getFileData()?.let { fileData ->
                viewModel.saveToLocal(uri, fileData.first, fileData.second) { savedUri ->
                    setResult(RESULT_OK, Intent().setData(savedUri))
                    finish()
                }
            }
        } else {
            data.putExtra("value", intent.getStringExtra("value"))
            setResult(RESULT_OK, data)
            finish()
        }
    }
}
