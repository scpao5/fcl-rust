package com.tungsten.fcl.ui.manage

import android.content.Context
import android.content.res.ColorStateList
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import com.mio.util.AnimUtil
import com.mio.util.AnimUtil.Companion.interpolator
import com.mio.util.showErrorDialog
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.PageManageVersionBinding
import com.tungsten.fcl.setting.Profile
import com.tungsten.fcl.ui.UIManager.Companion.instance
import com.tungsten.fcl.ui.manage.ManageUI.VersionLoadable
import com.tungsten.fcl.ui.manage.adapter.ManageItemAdapter
import com.tungsten.fcl.ui.manage.item.ManageItem
import com.tungsten.fcl.ui.version.Versions
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.fakefx.beans.property.BooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleBooleanProperty
import com.tungsten.fcl.ui.TaskDialog
import com.tungsten.fclcore.task.Schedulers
import com.tungsten.fcl.util.TaskCancellationAction
import com.tungsten.fclcore.task.TaskExecutor
import com.tungsten.fclcore.task.TaskListener
import com.tungsten.fclcore.task.Task
import com.tungsten.fclcore.util.io.FileUtils
import com.tungsten.fcllibrary.browser.FileBrowser
import com.tungsten.fcllibrary.browser.options.LibMode
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.ui.FCLPage
import android.widget.Toast
import com.tungsten.fcl.game.StarlingResources
import com.tungsten.fcllibrary.ui.ProgressDialog
import com.tungsten.fcllibrary.util.uploadLog
import java.io.File

class ManagePage(context: Context?, id: Int) : FCLPage(context, id, R.layout.page_manage_version), VersionLoadable {
    private val currentVersionUpgradable: BooleanProperty = SimpleBooleanProperty()
    val profile: Profile
        get() = instance.manageUI.profile
    val version: String
        get() = instance.manageUI.version

    private lateinit var binding: PageManageVersionBinding

    init {
        create()
    }


    override fun loadVersion(profile: Profile, version: String?) {
        // null 是合法分发（切到未选版本 Profile 的瞬态）：无版本可判定整合包升级，保持现状
        if (version == null) return
        currentVersionUpgradable.set(profile.repository.isModpack(version))
    }

    private fun create() {
        binding = PageManageVersionBinding.bind(contentView).apply {
            ThemeEngine.getInstance().registerEvent(left) {
                left.backgroundTintList = ColorStateList(
                    arrayOf(intArrayOf()), intArrayOf(ThemeEngine.getInstance().getTheme().ltColor)
                )
            }
            ThemeEngine.getInstance().registerEvent(right) {
                right.backgroundTintList = ColorStateList(
                    arrayOf(intArrayOf()), intArrayOf(ThemeEngine.getInstance().getTheme().ltColor)
                )
            }

            left.layoutManager = LinearLayoutManager(context)
            left.adapter = ManageItemAdapter(
                context,
                listOf(
                    ManageItem(R.drawable.ic_baseline_cloud_upload_24, R.string.upload_log) {
                        uploadLatestLog()
                    },
                    ManageItem(R.drawable.ic_baseline_script_24, R.string.folder_fcl_log) {
                        onBrowse(
                            FCLPath.LOG_DIR
                        )
                    },
                    ManageItem(R.drawable.ic_baseline_videogame_asset_24, R.string.folder_game) {
                        onBrowse("")
                    },
                    ManageItem(R.drawable.ic_outline_extension_24, R.string.folder_mod) {
                        onBrowse("mods")
                    },
                    ManageItem(R.drawable.ic_baseline_settings_24, R.string.folder_config) {
                        onBrowse("config")
                    },
                    ManageItem(R.drawable.ic_baseline_texture_24, R.string.folder_resourcepacks) {
                        onBrowse("resourcepacks")
                    },
                    ManageItem(R.drawable.ic_baseline_application_24, R.string.folder_shaderpacks) {
                        onBrowse("shaderpacks")
                    },
                    ManageItem(R.drawable.ic_baseline_screenshot_24, R.string.folder_screenshots) {
                        onBrowse("screenshots")
                    },
                    ManageItem(R.drawable.ic_baseline_earth_24, R.string.folder_saves) {
                        onBrowse("saves")
                    }

                ))
            right.layoutManager = LinearLayoutManager(context)
            right.adapter = ManageItemAdapter(
                context,
                // First on the page: the game cannot start until the engine's resources are
                // built, and a button further down is a button people never find.
                listOf(
                    ManageItem(
                        R.drawable.ic_baseline_build_24,
                        R.string.version_manage_starling_resources
                    ) {
                        buildEngineResources()
                    },
                    ManageItem(R.drawable.ic_baseline_update_24, R.string.version_update) {
                        if (!currentVersionUpgradable.get()) {
                            AnimUtil.playTranslationX(it, 500, 0f, 50f, -50f, 0f)
                                .interpolator(OvershootInterpolator()).start()
                        } else {
                            updateGame()
                        }
                    },
                    ManageItem(R.drawable.ic_baseline_edit_24, R.string.version_manage_rename) {
                        rename()
                    },
                    ManageItem(
                        R.drawable.ic_baseline_content_copy_24,
                        R.string.version_manage_duplicate
                    ) {
                        duplicate()
                    },
                    ManageItem(R.drawable.ic_baseline_output_24, R.string.modpack_export) {
                        export()
                    },
                    ManageItem(
                        R.drawable.ic_baseline_list_24,
                        R.string.version_manage_redownload_assets_index
                    ) {
                        redownloadAssetIndex()
                    },
                    ManageItem(
                        R.drawable.ic_baseline_delete_24,
                        R.string.version_manage_remove_libraries
                    ) {
                        clearLibraries()
                    },
                    ManageItem(
                        R.drawable.ic_baseline_delete_24,
                        R.string.version_manage_clean
                    ) {
                        clearJunkFiles()
                    }
                ))
        }
    }


    /**
     * Builds the engine's own resources now, from this version's files, instead of waiting for a
     * launch to find them missing: the same work the launcher does before starting a game, on
     * demand, with its progress on the screen.
     */
    private fun buildEngineResources() {
        val game = profile.repository.getGameVersion(version).orElse(null)
        if (!StarlingResources.supported(game)) {
            Toast.makeText(
                context,
                context.getString(R.string.version_manage_starling_resources_unsupported),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        StarlingResources.note("manage: build started for " + version)
        val root = profile.repository.getVersionRoot(version).toPath()
        StarlingResources.note("manage: root " + root)
        // The launcher's own task dialog, as the download page uses: its list shows each step
        // the engine reports, in place of a spinner that says nothing.
        val dialog = TaskDialog(context, TaskCancellationAction(Runnable { }))
        dialog.setTitle(context.getString(R.string.version_manage_starling_resources))
        val task = object : Task<Boolean>() {
            override fun execute() {
                val folder = StarlingResources.folder(root)
                folder.deleteRecursively()
                StarlingResources.note("manage: ensure called")
                StarlingResources.ensure(root, game, progress@{ line ->
                    // The preparation says one JSON object a line: its words, and how far along
                    // the work is. The row's title follows the words, its bar the fraction.
                    when {
                        line.startsWith("fraction:") -> {
                            val f = line.substringAfter(':').toDoubleOrNull() ?: return@progress
                            updateProgress(f.coerceIn(0.0, 1.0))
                        }
                        line.isEmpty() -> { }
                        else -> updateMessage(line.take(80))
                    }
                }, profile.getDependency())
                setResult(StarlingResources.complete(folder))
            }
        }.setName(context.getString(R.string.version_manage_starling_resources))
        val executor = task.executor(object : TaskListener() {
            override fun onStop(success: Boolean, executor: TaskExecutor) {
                Schedulers.androidUIThread().execute {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(
                        context,
                        context.getString(
                            if (success) R.string.version_manage_starling_resources_done
                            else R.string.version_manage_starling_resources_failed
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        // The dialog builds its list from the executor: without this it shows nothing at all.
        dialog.setExecutor(executor)
        dialog.show()
        executor.start()
    }

    private fun onBrowse(path: String) {
        val root =
            if (path.startsWith("/")) File(path) else if (path.isEmpty()) profile.repository.getRunDirectory(
                version
            ) else File(
                profile.repository.getRunDirectory(version), path
            )
        if (!root.exists()) {
            root.mkdirs()
        }
        FileBrowser.Builder(context)
            .setInitDir(root.absolutePath)
            .setLibMode(LibMode.FILE_BROWSER)
            .create()
            .browse(activity)
    }

    private fun redownloadAssetIndex() {
        Versions.updateGameAssets(context, profile, version)
    }

    private fun clearLibraries() {
        val builder = FCLAlertDialog.Builder(context)
        builder.setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
        builder.setMessage(
            String.format(
                context.getString(R.string.version_manage_remove_confirm),
                "libraries"
            )
        )
        builder.setPositiveButton {
            val progress = ProgressDialog(context)
            Task.runAsync {
                FileUtils.deleteDirectoryQuietly(
                    File(
                        profile.repository.baseDirectory, "libraries"
                    )
                )
            }.whenComplete(Schedulers.androidUIThread()) { _: Exception? ->
                progress.dismiss()
            }.start()
        }
        builder.setNegativeButton(null)
        builder.create().show()
    }

    private fun clearJunkFiles() {
        val builder = FCLAlertDialog.Builder(context)
        builder.setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
        builder.setMessage(
            String.format(
                context.getString(R.string.version_manage_remove_confirm),
                "logs"
            )
        )
        builder.setPositiveButton {
            val progress = ProgressDialog(context)
            Task.runAsync {
                Versions.cleanVersion(
                    profile, version
                )
            }.whenComplete(Schedulers.androidUIThread()) { _: Exception? ->
                progress.dismiss()
            }.start()
        }
        builder.setNegativeButton(null)
        builder.create().show()
    }

    private fun updateGame() {
        Versions.updateVersion(context, profile, version)
    }

    private fun export() {
        Versions.exportVersion(context, profile, version)
    }

    private fun rename() {
        Versions.renameVersion(context, profile, version)
            .thenApply {
                instance.manageUI.preferredVersionName = it
            }
    }

    private fun duplicate() {
        Versions.duplicateVersion(context, profile, version)
    }

    private fun uploadLatestLog() {
        val logFile = FCLPath.getLatestGameLog()
        if (!logFile.exists()) {
            showErrorDialog(context,R.string.log_not_found)
            return
        }
        try {
            if (logFile.length() > 5 * 1024 * 1024) {
                showErrorDialog(context,R.string.log_too_large)
                return
            }
            val logs = FileUtils.readText(logFile)
            uploadLog(activity, logs)
        } catch (e: Exception) {
            showErrorDialog(context,"Failed to read log: ${e.message}")
        }
    }

}
