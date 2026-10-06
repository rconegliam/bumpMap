package com.rconegliam.bumpmap.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.rconegliam.bumpmap.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingStore {
    private const val DIR = "recordings"

    fun fileName(now: Date): String =
        "bumpmap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now) + ".csv"

    fun newFile(context: Context, now: Date = Date()): File = File(dir(context), fileName(now))

    fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, context.getString(R.string.recording_share_chooser))
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
}
