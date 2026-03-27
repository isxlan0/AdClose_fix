package com.close.hook.ads.rule

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

object RuleSnapshotStore {

    private const val SNAPSHOT_DIRECTORY = "rule_snapshot"
    private const val CURRENT_SNAPSHOT_FILE = "current_rules.json"
    private const val NEXT_SNAPSHOT_FILE = "next_rules.json"

    fun currentSnapshotFile(context: Context): File {
        return snapshotDirectory(context).resolve(CURRENT_SNAPSHOT_FILE)
    }

    fun nextSnapshotFile(context: Context): File {
        return snapshotDirectory(context).resolve(NEXT_SNAPSHOT_FILE)
    }

    fun openCurrentSnapshot(context: Context): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            currentSnapshotFile(context),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    private fun snapshotDirectory(context: Context): File {
        return context.applicationContext.filesDir
            .resolve(SNAPSHOT_DIRECTORY)
            .apply { mkdirs() }
    }
}
