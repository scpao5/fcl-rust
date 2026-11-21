/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fcl.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.gson.GsonBuilder
import com.mio.JavaManager
import com.mio.data.Renderer
import com.mio.manager.RendererManager
import com.mio.minecraft.ModCheckException
import com.mio.minecraft.ModChecker
import com.mio.plugin.NativeLibPlugin
import com.mio.plugin.RendererPlugin
import com.mio.util.ParseUtil
import com.mio.util.getLocalizedText
import com.mio.util.hasStringId
import com.mio.util.loginStageText
import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.JVMActivity
import com.tungsten.fcl.activity.MainActivity
import com.tungsten.fcl.control.MenuType
import com.tungsten.fcl.setting.GameOption
import com.tungsten.fcl.setting.MenuSetting
import com.tungsten.fcl.setting.Profile
import com.tungsten.fcl.setting.VersionSetting
import com.tungsten.fcl.ui.TaskDialog
import com.tungsten.fcl.ui.UIManager
import com.tungsten.fcl.util.JnaNatives
import com.tungsten.fcl.util.TaskCancellationAction
import com.tungsten.fclauncher.bridge.FCLBridge
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.auth.Account
import com.tungsten.fclcore.auth.AuthInfo
import com.tungsten.fclcore.auth.AuthenticationException
import com.tungsten.fclcore.auth.CharacterDeletedException
import com.tungsten.fclcore.auth.CredentialExpiredException
import com.tungsten.fclcore.auth.authlibinjector.AuthlibInjectorDownloadException
import com.tungsten.fclcore.auth.microsoft.MicrosoftAccount
import com.tungsten.fclcore.download.DefaultDependencyManager
import com.tungsten.fclcore.download.LibraryAnalyzer
import com.tungsten.fclcore.download.MaintainTask
import com.tungsten.fclcore.download.game.GameAssetIndexDownloadTask
import com.tungsten.fclcore.download.game.GameVerificationFixTask
import com.tungsten.fclcore.download.game.LibraryDownloadException
import com.tungsten.fclcore.game.JavaVersion
import com.tungsten.fclcore.game.Version
import com.tungsten.fclcore.mod.LocalModFile
import com.tungsten.fclcore.mod.ModpackCompletionException
import com.tungsten.fclcore.mod.ModpackConfiguration
import com.tungsten.fclcore.task.DownloadException
import com.tungsten.fclcore.task.Task
import com.tungsten.fclcore.task.TaskExecutor
import com.tungsten.fclcore.task.TaskListener
import com.tungsten.fclcore.util.DigestUtils
import com.tungsten.fclcore.util.LibFilter
import com.tungsten.fclcore.util.Logging.LOG
import com.tungsten.fclcore.util.StringUtils
import com.tungsten.fclcore.util.io.FileUtils
import com.tungsten.fclcore.util.io.ResponseCodeException
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import com.tungsten.fclcore.util.versioning.VersionNumber
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.component.view.FCLButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 启动流程编排：以协程串联版本解析、mod 扫描、依赖补全、登录与启动前检查，
 * 替代原先的 Task 任务链；弹窗以任务列表形式展示各启动阶段并随协程推进打勾，
 * fclcore 子任务（依赖补全等）仍经 [runTracked] 接入 TaskExecutor 以复用进度展示。
 */
class LauncherHelper(
    private val context: Context,
    private val profile: Profile,
    private val account: Account,
    private val selectedVersion: String,
) {

    private val setting: VersionSetting = profile.getVersionSetting(selectedVersion)
    private val launchingStepsPane = TaskDialog(context, TaskCancellationAction.NORMAL).apply {
        title = context.getString(R.string.version_launch)
    }
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scaleFactor = 1.0
    private var cursorOffset = 0.0
    private var launchJob: Job? = null

    /** 启动阶段列表（与原任务链 withStagesHint 一致），驱动弹窗任务列表的阶段行展示 */
    private val launchStages = listOf(
        "launch.state.version",
        "launch.state.mods",
        "launch.state.java",
        "launch.state.dependencies",
        "launch.state.logging_in",
        "launch.state.waiting_launching",
    )
    private var currentStage: String? = null

    /** 确认框的按钮位置：默认 POSITIVE 取消启动、NEGATIVE 继续，调用方可通过文案与分支重定义（如 Java 检查中 POSITIVE 为自动选择） */
    private enum class Choice { POSITIVE, NEUTRAL, NEGATIVE }

    /** 登录异常弹窗的按钮选择 */
    private enum class LoginChoice { RETRY, SKIP_OFFLINE, CANCEL }

    fun launch() {
        // 同一实例重复触发时忽略，避免正在执行的启动协程被覆盖
        if (launchJob?.isActive == true) return
        LOG.info("Launching game version: $selectedVersion")
        launchingStepsPane.show()
        // 任务列表形式展示各启动阶段，随协程推进打勾
        launchingStepsPane.setStages(launchStages)
        // 取消按钮点击时同时取消启动协程
        launchingStepsPane.setCancel(TaskCancellationAction(Runnable { launchJob?.cancel() }))
        launchJob = mainScope.launch(Dispatchers.IO) {
            try {
                try {
                    launch0()
                    withContext(Dispatchers.Main) {
                        currentStage?.let { launchingStepsPane.succeedStage(it) }
                        launchingStepsPane.dismiss()
                    }
                } catch (_: CancellationException) {
                    // 用户主动取消（TaskDialog 取消按钮或检查弹窗中选择取消）：幂等关闭启动弹窗。
                    // NonCancellable：经取消按钮取消时 Job 已取消，普通 withContext 在入口即抛出
                    withContext(NonCancellable + Dispatchers.Main) {
                        launchingStepsPane.dismiss()
                    }
                } catch (e: Throwable) {
                    // NonCancellable：错误冒出瞬间被取消时（Job 已取消）仍须关闭弹窗并提示错误
                    withContext(NonCancellable + Dispatchers.Main) {
                        currentStage?.let { launchingStepsPane.failStage(it) }
                        launchingStepsPane.dismiss()
                        showLaunchErrorMessage(e)
                    }
                }
            } finally {
                // 登录进度回调在 logIn 的 finally 中已解除注册，此处收掉常驻 scope 防泄漏
                mainScope.cancel()
            }
        }
    }

    /** 进入下一启动阶段：上一真正进入的阶段打勾，当前阶段标记开始 */
    private fun enterStage(stage: String) {
        // 按 currentStage 而非列表固定前一项打勾，isNotCheckGame 跳过 dependencies 时不会错位
        currentStage?.let { launchingStepsPane.succeedStage(it) }
        launchingStepsPane.beginStage(stage)
        currentStage = stage
    }

    private suspend fun launch0() {
        val repository = profile.repository
        val dependencyManager = profile.getDependency()

        enterStage("launch.state.version")
        // 版本维护/解析较重，协程已在后台线程执行
        val versionRef = AtomicReference(
            MaintainTask.maintain(repository, repository.getResolvedVersion(selectedVersion))
        )

        enterStage("launch.state.mods")
        // 全量 mod 扫描一次，供 lwjgl3ify 补丁、modloader 与渲染器检查共用
        val scannedMods = scanMods(repository, selectedVersion)
        // 装有自实现 FPE 模拟的模组（Angelica/Actinium 等）时本次启动跳过 SFPEW 兼容层
        RendererPlugin.setFpeEmulatorModsPresent(scannedMods)
        // GTNH/lwjgl3ify 兼容：mods 目录存在 lwjgl3ify 时在内存中合并 RFB 启动清单（不落盘），以 RFB + Java17+ 启动
        Lwjgl3ifyPatcher.patchIfNeeded(repository, selectedVersion, scannedMods, versionRef)
        val gameVersion = repository.getGameVersion(versionRef.get())
        val integrityCheck = repository.unmarkVersionLaunchedAbnormally(selectedVersion)

        enterStage("launch.state.java")
        val javaVersion = checkGameState(versionRef.get())
        val version = LibFilter.filter(versionRef.get(), false)

        if (!setting.isNotCheckGame) {
            completeDependencies(dependencyManager, version, integrityCheck)
        }
        // The files are complete now: the engine's own resources are made from them, once, only
        // when they are not there yet (26.2 and later read them).
        StarlingResources.note("launch: gameVersion=$gameVersion present=${gameVersion.isPresent}")
        if (gameVersion.isPresent) {
            withContext(Dispatchers.IO) {
                StarlingResources.ensure(
                    repository.getVersionRoot(selectedVersion).toPath(),
                    gameVersion.get(),
                    { },
                    dependencyManager
                )
            }
        }

        // The launcher's own graphics setting, where the engine reads it: the game's options.txt
        // names the backend by these three words, and the launcher's choice is the one to start
        // with. Every launch says it again, so a change in the launcher reaches the game.
        StarlingResources.writeGraphicsBackend(
            repository.getVersionRoot(selectedVersion).toPath(),
            setting.graphicsBackend
        )

        unpackBuiltinJars()

        if (gameVersion.isPresent) {
            runTracked(GameVerificationFixTask(dependencyManager, gameVersion.get(), version))
        }

        enterStage("launch.state.logging_in")
        val authInfo = logIn()

        enterStage("launch.state.waiting_launching")
        val fclBridge = prepareLaunch(repository, version, javaVersion, authInfo)

        runPreLaunchChecks(repository, fclBridge, scannedMods)
        startGame(repository, version, fclBridge, javaVersion)
    }

    /** 补全游戏文件与整合包依赖，任务接入弹窗任务列表复用进度展示 */
    private suspend fun completeDependencies(
        dependencyManager: DefaultDependencyManager,
        version: Version,
        integrityCheck: Boolean,
    ) {
        enterStage("launch.state.dependencies")
        // 游戏文件补全与整合包依赖补全（若存在）在同一执行器下并行，进度展示在弹窗任务列表
        val tasks = mutableListOf<Task<*>>(
            dependencyManager.checkGameCompletionAsync(version, integrityCheck)
        )
        try {
            val configuration: ModpackConfiguration<*> =
                ModpackHelper.readModpackConfiguration(
                    profile.repository.getModpackConfiguration(selectedVersion)
                )
            ModpackHelper.getProviderByType(configuration.type)?.let { provider ->
                // 用户已关闭整合包自动补全时不创建补全任务；否则探测将恢复的缺失文件，非空先弹窗确认
                if (setting.isNotRestoreModpack)
                    return@let
                val missing =
                    ModpackHelper.getMissingModpackFiles(profile.repository, selectedVersion, configuration)
                if (missing.isNotEmpty()) {
                    // 文件列表超 20 个折叠，避免弹窗过长
                    val names = if (missing.size > 20)
                        missing.take(20).joinToString("\n") + "\n..."
                    else
                        missing.joinToString("\n")
                    val proceed = when (awaitChoice(
                        context.getString(R.string.modpack_completion_confirm, names),
                        positiveText = context.getString(R.string.modpack_completion_skip),
                        negativeText = context.getString(R.string.modpack_completion_continue),
                        neutralText = context.getString(R.string.modpack_completion_disable),
                    )) {
                        Choice.NEGATIVE -> true
                        Choice.NEUTRAL -> {
                            setting.isNotRestoreModpack = true
                            false
                        }
                        Choice.POSITIVE -> false
                    }
                    if (!proceed)
                        return@let
                }
                // HMCL / MultiMC 整合包无补全任务，provider 返回 null 时不加入
                provider.createCompletionTask(dependencyManager, selectedVersion)?.let { tasks.add(it) }
            }
        } catch (_: IOException) {
        }
        runTracked(Task.allOf(*tasks.toTypedArray()))
    }

    /** 解包内置补丁 jar（MioLibPatcher / MioLaunchWrapper），失败仅记录警告不阻断启动 */
    private fun unpackBuiltinJars() {
        unpackJar("/assets/game/MioLibPatcher.jar", FCLPath.LIB_PATCHER_PATH, "MioLibFixer.jar")
        unpackJar(
            "/assets/game/MioLaunchWrapper.jar",
            FCLPath.MIO_LAUNCH_WRAPPER,
            "MioLaunchWrapper.jar"
        )
    }

    /** 读取菜单设置、装配 FCLGameLauncher 并拉起游戏进程，返回后续检查与界面配置所用的桥接实例 */
    private fun prepareLaunch(
        repository: FCLGameRepository,
        version: Version,
        javaVersion: JavaVersion,
        authInfo: AuthInfo,
    ): FCLBridge {
        // Nothing here starts a JVM: Starling's engine reads the folder and the mods itself, so
        // FCLGameLauncher, JNA, LWJGL and the launch arguments are all unused. The bridge is the
        // empty object startGame fills in before handing the intent to StarlingActivity.
        println("starling: skipping the Java launch for " + version.id)
        return FCLBridge()
    }

    /** 启动前检查：路径合法性、渲染器与原生插件版本、modloader 及模组兼容性，并记录启动状态 */
    private suspend fun runPreLaunchChecks(
        repository: FCLGameRepository,
        fclBridge: FCLBridge,
        scannedMods: List<LocalModFile>?,
    ) {
        // Only the folder is checked. The rest asks about FCL's Java renderers, its native lib
        // plugins and the mod loaders' mixins: Starling draws with wgpu on the system Vulkan
        // driver and runs no Java mod, so none of those questions has an answer here.
        checkPathValid(repository)
    }

    /** 切回主线程拉起 JVMActivity，并把桥接配置与控制器设置交给游戏进程 */
    private suspend fun startGame(
        repository: FCLGameRepository,
        version: Version,
        fclBridge: FCLBridge,
        javaVersion: JavaVersion,
    ) {
        withContext(Dispatchers.Main) {
            // Nothing of the JVM launch path here: CallbackBridge (libpojavexec.so) and the
            // FCLBridge fields fed JVMActivity's input queue and launch arguments, and Starling's
            // engine takes neither. Its input comes from the NativeActivity itself.
            // Starling's engine, not the Java runtime: it draws and ticks the world itself.
            // Everything above this point (the version checks, the mods, the account) is FCL's.
            val intent = Intent(context, io.github.juanluci.starling.StarlingActivity::class.java)
            // The folder this version runs in, and the name the player signed in with: the
            // engine keeps its worlds, packs and options there and joins with that name.
            intent.putExtra(
                io.github.juanluci.starling.StarlingActivity.EXTRA_GAME_DIR,
                repository.getRunDirectory(selectedVersion).absolutePath
            )
            intent.putExtra(
                io.github.juanluci.starling.StarlingActivity.EXTRA_PLAYER,
                account.username
            )
            // The control layout this version is set to use: the menu starts with none otherwise,
            // and the default one shows whatever the setting says.
            intent.putExtra(
                io.github.juanluci.starling.StarlingActivity.EXTRA_CONTROLLER,
                setting.controller
            )
            // Where the game's own files are: the run folder holds the world, and the install
            // above it (or the run folder itself, when a version keeps its own) holds versions/,
            // assets/ and libraries/. The engine reads them instead of downloading 672 MB.
            val runDir = repository.getRunDirectory(selectedVersion)
            intent.putExtra(
                io.github.juanluci.starling.StarlingActivity.EXTRA_MINECRAFT_ROOT,
                if (File(runDir, "versions").isDirectory) runDir.absolutePath
                else runDir.parentFile?.absolutePath ?: runDir.absolutePath
            )
            LOG.log(Level.INFO, "Start StarlingActivity!")
            context.startActivity(intent)
            if (MainActivity.getInstance().shouldPlayVideo()) {
                MainActivity.getInstance().mediaPlayer = null
                MainActivity.getInstance().binding.videoView.stopPlayback()
            }
            if (context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                    .getBoolean("autoExitLauncher", false)
            ) {
                FCLApp.getActivity()?.finish()
            }
        }
    }

    /**
     * 在独立 TaskExecutor 中执行 fclcore 任务（依赖补全、资源修复等），协程挂起至完成；
     * 执行器接入弹窗既有的阶段列表以复用下载进度展示，协程取消会级联取消任务
     */
    private suspend fun runTracked(task: Task<*>) {
        val executor = task.executor()
        withContext(Dispatchers.Main) { launchingStepsPane.setExecutor(executor, false) }
        suspendCancellableCoroutine { cont ->
            executor.addTaskListener(object : TaskListener() {
                override fun onStop(success: Boolean, executor: TaskExecutor) {
                    // 取消与任务结束可能同时发生，已完成时不再重复 resume
                    if (!cont.isActive) return
                    if (success) {
                        cont.resume(Unit)
                    } else {
                        // 用户取消时 exception 为 null，按协程取消处理
                        executor.exception?.let { cont.resumeWithException(it) } ?: cont.cancel()
                    }
                }
            })
            // 先 start：setExecutor 已把执行器交给取消按钮，此窗口内取消会因 future 尚未建立而抛异常
            executor.start()
            cont.invokeOnCancellation { runCatching { executor.cancel() } }
        }
    }

    /** 弹出不可关闭的确认框并挂起等待选择，默认 positive 为取消启动 */
    private suspend fun awaitChoice(
        message: String,
        positiveText: String = context.getString(R.string.button_cancel),
        negativeText: String = context.getString(R.string.mod_check_continue),
        neutralText: String? = null,
    ): Choice = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            // 弹窗按钮与协程取消可能竞争，已完成时不再重复 resume
            fun choose(choice: Choice) {
                if (cont.isActive) cont.resume(choice)
            }

            val builder = FCLAlertDialog.Builder(context)
            builder.setCancelable(false)
            builder.setMessage(message)
            builder.setPositiveButton(positiveText) { choose(Choice.POSITIVE) }
            builder.setNegativeButton(negativeText) { choose(Choice.NEGATIVE) }
            if (neutralText != null) builder.setNeutralButton(neutralText) { choose(Choice.NEUTRAL) }
            val dialog = builder.create()
            // 启动协程被取消（如任务弹窗点取消）时一并收掉停留屏幕上的检查弹窗
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    private suspend fun checkPathValid(repository: FCLGameRepository) {
        try {
            val path = repository.getVersionJar(selectedVersion).absolutePath
            if (!ParseUtil.isValidCharacters(path)) {
                if (awaitChoice(
                        context.getString(
                            R.string.message_check_path_valid,
                            path
                        )
                    ) == Choice.POSITIVE
                )
                    throw CancellationException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 检查失败时按路径有效处理，不阻断启动
        }
    }

    /** 全量扫描 mod 列表，失败返回 null（调用方按需回退） */
    private fun scanMods(repository: FCLGameRepository, versionId: String): List<LocalModFile>? =
        try {
            repository.getModManager(versionId).mods
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to scan mods before launch", e)
            null
        }

    private suspend fun checkRenderer(
        renderer: Renderer,
        version: String,
        mods: List<LocalModFile>?
    ) {
        if (Lwjgl3ifyPatcher.shouldSkipRendererCheck(mods)) {
            LOG.log(Level.INFO, "Angelica + lwjgl3ify detected, skip renderer version warning")
            return
        }
        try {
            if (version.isNotEmpty()) {
                // SFPEW 兼容层启用时补齐固定管线能力，旧版 MC 可正常运行，跳过 min 下界判定；
                // max 上界保留——层不扩展渲染器自身的现代 GL 上限
                val sfpewLayer = RendererPlugin.isSfpewLayerEnabled(renderer)
                if (renderer.minMCver.isNotEmpty() && !sfpewLayer && GameVersionNumber.compare(
                        version,
                        renderer.minMCver
                    ) < 0
                ) {
                    if (awaitChoice(
                            context.getString(
                                R.string.message_check_renderer,
                                renderer.name
                            )
                        ) == Choice.POSITIVE
                    )
                        throw CancellationException()
                }
                if (renderer.maxMCver.isNotEmpty() && GameVersionNumber.compare(
                        version,
                        renderer.maxMCver
                    ) > 0
                ) {
                    if (awaitChoice(
                            context.getString(
                                R.string.message_check_renderer,
                                renderer.name
                            )
                        ) == Choice.POSITIVE
                    )
                        throw CancellationException()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.log(Level.WARNING, "checkRenderer() failed", e)
        }
    }

    private suspend fun checkNativeLibPlugin(version: String) {
        try {
            val unsupportedPlugins = NativeLibPlugin.pluginList
                .filter { plugin ->
                    (plugin.minMCVer.isNotEmpty() && GameVersionNumber.compare(
                        version,
                        plugin.minMCVer
                    ) < 0)
                            || (plugin.maxMCVer.isNotEmpty() && GameVersionNumber.compare(
                        version,
                        plugin.maxMCVer
                    ) > 0)
                }.map { it.appName }
            if (unsupportedPlugins.isNotEmpty()) {
                val fullString = unsupportedPlugins.joinToString(", ")
                if (awaitChoice(
                        context.getString(
                            R.string.message_check_plugin,
                            fullString
                        )
                    ) == Choice.POSITIVE
                )
                    throw CancellationException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.log(Level.WARNING, "checkNativeLibPlugin() failed", e)
        }
    }

    private suspend fun checkModLoader(
        repository: FCLGameRepository,
        mods: List<LocalModFile>?
    ) {
        try {
            val modded =
                LibraryAnalyzer.isModded(repository, repository.getVersion(selectedVersion))
            if (!mods.isNullOrEmpty() && !modded) {
                when (awaitChoice(
                    context.getString(R.string.message_check_has_modloader),
                    neutralText = context.getString(R.string.button_install)
                )) {
                    Choice.NEGATIVE -> {}
                    Choice.NEUTRAL -> {
                        // 跳转版本管理页的“游戏”标签页（索引 2）引导安装 modloader，随后取消本次启动。
                        // awaitChoice 自身在主线程执行，但返回后恢复在调用方上下文（IO），界面操作须显式切回；
                        // 跳转失败同样取消启动，避免退化成用户未选择的“继续启动”
                        withContext(Dispatchers.Main) {
                            runCatching {
                                MainActivity.getInstance().binding.manage.isSelected = true
                                val tabLayout = UIManager.instance.manageUI.tabLayout
                                tabLayout.selectTab(tabLayout.getTabAt(2))
                            }.onFailure {
                                LOG.log(Level.WARNING, "Unable to open mod loader install page", it)
                            }
                        }
                        throw CancellationException()
                    }

                    Choice.POSITIVE -> throw CancellationException()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.log(Level.WARNING, "checkModLoader() failed", e)
        }
    }

    private suspend fun checkMod(bridge: FCLBridge, version: String, mods: List<LocalModFile>?) {
        try {
            // 启动链扫描失败时回退为按当前选中版本自行获取（getModManager 每次返回新实例，此处为全量重扫）
            val modList = mods ?: profile.repository.getModManager(selectedVersion).mods
            val modCheckerInfo = StringBuilder()
            val modSummary = StringBuilder()
            val modChecker = ModChecker(context, version)
            var count = 0
            for (mod in modList) {
                if (!mod.isActive) {
                    continue
                }
                modSummary.append(mod.fileName).append(" | ")
                    .append(mod.id).append(" | ")
                    .append(mod.version).append(" | ")
                    .append(mod.modLoaderType).append('\n')
                try {
                    modChecker.check(bridge, mod)
                } catch (e: ModCheckException) {
                    count++
                    modCheckerInfo.append(count).append('.').append(e.reason).append("\n\n")
                }
            }
            bridge.modSummary = modSummary.toString()
            if (!setting.isNotCheckMod && modCheckerInfo.toString().trim().isNotEmpty()) {
                if (awaitChoice(modCheckerInfo.toString()) == Choice.POSITIVE)
                    throw CancellationException()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.log(Level.WARNING, "CheckMod() failed", e)
        }
    }

    private suspend fun checkGameState(version: Version): JavaVersion {
        // Starling's engine is Rust: it starts no JVM, so there is no Java to pick and nothing to
        // check. A placeholder keeps the callers' signature (JVMActivity would want the real one).
        println("starling: no Java is needed for " + version.id)
        return JavaVersion.JAVA_AUTO
    }

    /**
     * 登录账户；微软账户登录期间将认证阶段文字实时写入启动弹窗日志区
     */
    private suspend fun logIn(): AuthInfo {
        val withProgress = account is MicrosoftAccount
        if (withProgress) {
            // 进度回调在后台登录线程触发，需切回主线程写弹窗日志
            account.setProgressCallback { stage ->
                mainScope.launch { launchingStepsPane.appendLog(loginStageText(context, stage)) }
            }
        }
        try {
            while (true) {
                try {
                    return account.logIn()
                } catch (e: CredentialExpiredException) {
                    LOG.log(Level.INFO, "Credential has expired", e)
                    // 凭证过期：跳过则离线进入，确认则取消启动
                    if (awaitLoginChoice { callback ->
                            TipReLoginLoginDialog(
                                context,
                                callback
                            )
                        } == LoginChoice.CANCEL)
                        throw CancellationException()
                    return account.playOffline()
                } catch (e: AuthenticationException) {
                    LOG.log(Level.WARNING, "Authentication failed, try skipping refresh", e)
                    when (awaitLoginChoice { callback -> SkipLoginDialog(context, callback) }) {
                        LoginChoice.RETRY -> {} // 继续循环重试登录
                        LoginChoice.SKIP_OFFLINE -> return account.playOffline()
                        LoginChoice.CANCEL -> throw CancellationException()
                    }
                }
            }
        } finally {
            if (withProgress) {
                account.setProgressCallback(null)
            }
        }
    }

    /** 弹出登录异常处理弹窗并挂起等待选择 */
    private suspend fun awaitLoginChoice(create: ((LoginChoice) -> Unit) -> FCLDialog): LoginChoice =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = create { choice -> if (cont.isActive) cont.resume(choice) }
                // 启动协程被取消（如任务弹窗点取消）时一并收掉停留屏幕上的登录弹窗
                cont.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

    /** 解包内置补丁 jar 到目标路径，本地文件与包内 .sha1 记录一致时跳过，失败仅记录警告不阻断启动 */
    private fun unpackJar(resourcePath: String, targetPath: String, logName: String) {
        try {
            if (isBuiltinJarUpToDate(resourcePath, targetPath)) return
            LauncherHelper::class.java.getResourceAsStream(resourcePath).use { input ->
                Files.copy(input, File(targetPath).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            LOG.log(Level.WARNING, "Unable to unpack $logName", e)
        }
    }

    /** 本地 jar 已存在且 SHA-1 与安装包内同名 .sha1 记录一致时视为无需更新（.sha1 缺失则回退为总是解包） */
    private fun isBuiltinJarUpToDate(resourcePath: String, targetPath: String): Boolean {
        val expectedSha1 = LauncherHelper::class.java.getResourceAsStream("$resourcePath.sha1")
            ?.use { it.readBytes().decodeToString() }
            ?.trim()
            ?: return false
        val target = File(targetPath)
        return target.isFile && DigestUtils.digestToString("SHA-1", target.toPath()) == expectedSha1
    }

    /** 主线程展示启动失败原因（按异常类型整理文案，兜底分支直接打印堆栈） */
    private fun showLaunchErrorMessage(ex: Throwable) {
        val message: String = when (ex) {
            is ModpackCompletionException ->
                if (ex.cause is FileNotFoundException)
                    context.getString(R.string.modpack_type_curse_not_found)
                else
                    context.getString(R.string.modpack_type_curse_error)

            is LibraryDownloadException -> {
                val info = StringBuilder(
                    context.getString(R.string.launch_failed_download_library, ex.library.name)
                ).append('\n')
                when (val cause = ex.cause) {
                    is ResponseCodeException ->
                        if (cause.responseCode == 404)
                            info.append(context.getString(R.string.download_code_404, cause.url))
                        else
                            info.append(
                                context.getString(
                                    R.string.download_failed,
                                    cause.url,
                                    cause.responseCode
                                )
                            )

                    else -> info.append(StringUtils.getStackTrace(ex.cause))
                }
                info.toString()
            }

            is DownloadException -> {
                val url = ex.url
                when (val cause = ex.cause) {
                    is SocketTimeoutException -> context.getString(
                        R.string.install_failed_downloading_timeout,
                        url
                    )

                    is ResponseCodeException -> {
                        val key = "download_code_${cause.responseCode}"
                        if (hasStringId(context, key))
                            getLocalizedText(context, key, url)
                        else
                            context.getString(
                                R.string.install_failed_downloading_detail,
                                url
                            ) + "\n" +
                                    StringUtils.getStackTrace(ex.cause)
                    }

                    else -> context.getString(
                        R.string.install_failed_downloading_detail,
                        url
                    ) + "\n" +
                            StringUtils.getStackTrace(ex.cause)
                }
            }

            is GameAssetIndexDownloadTask.GameAssetIndexMalformedException -> context.getString(R.string.assets_index_malformed)
            is AuthlibInjectorDownloadException -> context.getString(R.string.account_failed_injector_download_failure)
            is CharacterDeletedException -> context.getString(R.string.account_failed_character_deleted)
            is ResponseCodeException ->
                if (ex.responseCode == 404)
                    context.getString(R.string.download_code_404, ex.url)
                else
                    context.getString(R.string.download_failed, ex.url, ex.responseCode)

            is AccessDeniedException -> context.getString(R.string.exception_access_denied, ex.file)
            is ModCheckException -> ex.reason
            is IllegalArgumentException -> context.getString(R.string.exception_no_suitable_java)
            else -> StringUtils.getStackTrace(ex)
        }

        val builder = FCLAlertDialog.Builder(context)
        builder.setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
        builder.setCancelable(false)
        builder.setTitle(context.getString(R.string.launch_failed))
        builder.setMessage(message)
        builder.setNegativeButton(context.getString(R.string.dialog_positive), null)
        builder.create().show()
    }

    /** 认证失败弹窗：重试登录 / 跳过离线进入 / 取消启动 */
    private class SkipLoginDialog(
        context: Context,
        private val onChoice: (LoginChoice) -> Unit,
    ) : FCLDialog(context), View.OnClickListener {

        private val retry: FCLButton
        private val skip: FCLButton
        private val cancel: FCLButton

        init {
            setContentView(R.layout.dialog_skip_login)
            setCancelable(false)
            retry = findViewById(R.id.retry)!!
            skip = findViewById(R.id.skip)!!
            cancel = findViewById(R.id.cancel)!!
            retry.setOnClickListener(this)
            skip.setOnClickListener(this)
            cancel.setOnClickListener(this)
        }

        override fun onClick(view: View) {
            when (view) {
                retry -> onChoice(LoginChoice.RETRY)
                skip -> onChoice(LoginChoice.SKIP_OFFLINE)
                cancel -> onChoice(LoginChoice.CANCEL)
            }
            dismiss()
        }
    }

    /** 凭证过期弹窗：跳过离线进入 / 取消启动 */
    private class TipReLoginLoginDialog(
        context: Context,
        private val onChoice: (LoginChoice) -> Unit,
    ) : FCLDialog(context), View.OnClickListener {

        private val skip: FCLButton
        private val ok: FCLButton

        init {
            setContentView(R.layout.dialog_tip_relogin)
            setCancelable(false)
            skip = findViewById(R.id.skip)!!
            ok = findViewById(R.id.ok)!!
            skip.setOnClickListener(this)
            ok.setOnClickListener(this)
        }

        override fun onClick(view: View) {
            when (view) {
                skip -> onChoice(LoginChoice.SKIP_OFFLINE)
                ok -> onChoice(LoginChoice.CANCEL)
            }
            dismiss()
        }
    }
}
