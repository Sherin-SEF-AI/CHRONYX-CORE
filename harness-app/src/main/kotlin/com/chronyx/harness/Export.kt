package com.chronyx.harness

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Exports a recording (the `.mcap` plus its JSON manifest when present) through the system share sheet
 * via a [FileProvider] content URI — so the operator can send it to Drive, email, USB, etc. without
 * needing `adb pull`.
 */
fun shareRecording(context: Context, rec: RecordingFile) {
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList<Uri>()
    runCatching { uris.add(FileProvider.getUriForFile(context, authority, rec.file)) }
    rec.manifestFile?.let { mf -> runCatching { uris.add(FileProvider.getUriForFile(context, authority, mf)) } }
    if (uris.isEmpty()) return

    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, rec.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Export ${rec.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
