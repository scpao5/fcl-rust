package com.tungsten.fcl.game;

import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.task.Task;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The engine's own resources, made from the game's files when they are not there yet.
 *
 * <p>Starling's engine reads a prepared resource folder, not the jars: this makes it once, from
 * the files FCL has just checked, and only for the versions whose engine reads it (26.2 and
 * later). A folder already there is left alone, so a normal launch checks one file and starts.
 */
public final class StarlingResources {

    private StarlingResources() {
    }


    /**
     * The backend the launcher chose, as the game's own argument file: `horizon-args.txt` in the
     * game folder, one argument per line. The engine reads it itself, so nothing here has to
     * know where the game keeps `options.txt`; a line left from a previous launch is replaced,
     * and every other line is kept as it was.
     */
    public static void writeGraphicsBackend(Path versionRoot, String backend) {
        logDir = versionRoot;
        if (backend == null || backend.isEmpty()) {
            return;
        }
        String value = switch (backend) {
            case "opengl" -> "opengl";
            case "vulkan" -> "vulkan";
            default -> "default";
        };
        File args = new File(versionRoot.toFile(), "horizon-args.txt");
        try {
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (args.isFile()) {
                for (String line : java.nio.file.Files.readAllLines(
                        args.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    // A previous choice is two lines: the flag and its value.
                    if (line.trim().equals("--graphics-backend") || isBackendValue(line)) {
                        continue;
                    }
                    lines.add(line);
                }
            }
            lines.add("--graphics-backend");
            lines.add(value);
            java.nio.file.Files.write(args.toPath(), lines,
                    java.nio.charset.StandardCharsets.UTF_8);
            trace("launch: graphics backend " + value + " in " + args);
        } catch (Throwable e) {
            trace("launch: the graphics backend was not written: " + e);
        }
    }

    private static boolean isBackendValue(String line) {
        String t = line.trim();
        return t.equals("opengl") || t.equals("vulkan") || t.equals("default");
    }

    /** A line for the launch log, from the launcher's own code. */
    public static void note(String line) {
        trace(line);
    }

    /** Where the last launch's version lived: the log goes beside it, wherever that is. */
    private static volatile Path logDir;

    /**
     * One line into the launch log, which every launcher-side check writes to. The folder comes
     * from the launch that is running, not from a path written here: a game folder moves, and the
     * launcher is told where it is rather than assuming.
     */
    private static void trace(String line) {
        android.util.Log.i("StarlingResources", line);
        Path dir = logDir;
        if (dir == null) {
            return;
        }
        try (java.io.FileWriter writer = new java.io.FileWriter(
                new java.io.File(dir.toFile(), "starling-resources.log"), true)) {
            writer.write(line + "\n");
        } catch (Throwable ignored) {
        }
    }

    /** The folder the engine reads, beside the version's jar. */
    public static File folder(Path versionRoot) {
        return new File(versionRoot.toFile(), "resources");
    }

    /** Whether the engine can read a folder of resources: 26.2 and later. */
    public static boolean supported(String gameVersion) {
        if (gameVersion == null) {
            return false;
        }
        String[] parts = gameVersion.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > 26 || (major == 26 && minor >= 2);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The app's own files, where the engine leaves its stage line. */
    private static java.io.File appFiles() {
        return com.tungsten.fcl.FCLApp.getAppContext().getFilesDir();
    }


    /**
     * Runs the preparation as a program of its own, from the folder the APK's libraries were
     * unpacked to (a phone may exec nothing out of its own files, but that folder it can). Its
     * own process keeps the launcher's own free to draw the progress, and gives the preparation
     * the whole of a core instead of whatever the renderer leaves.
     */
    private static void ask(String[] paths) {
        String program = new java.io.File(
                com.tungsten.fcl.FCLApp.getAppContext()
                        .getApplicationInfo().nativeLibraryDir,
                "libhorizon_prepare.so").getAbsolutePath();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(program);
        command.add("--prepare");
        command.add("--complete");
        command.add("--output");
        command.add(paths[0]);
        command.add("--minecraft-dir");
        command.add(paths[1]);
        command.add("--libraries-dir");
        command.add(paths[2]);
        command.add("--assets-dir");
        command.add(paths[3]);
        command.add("--version-json");
        command.add(paths[4]);
        command.add("--client-jar");
        command.add(paths[5]);
        command.add("--server-jar");
        command.add(paths[6]);
        command.add("--java-home");
        command.add(paths[7]);
        command.add("--root");
        command.add(paths[8]);
        // One JSON object a line: the words for the row, and how far along the work is.
        command.add("--progress");
        command.add("json");
        trace("ensure: running " + program);
        File javaHome = new File(paths[7]);
        // The runtime's own launchers are named without the bit that runs them: a launcher's
        // files are not executable by default, and this one has to be.
        for (String name : new String[] {"java", "javac"}) {
            File launcher = new File(javaHome, "bin/" + name);
            if (launcher.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                launcher.setExecutable(true, false);
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            String nativeLib = com.tungsten.fcl.FCLApp.getAppContext()
                    .getApplicationInfo().nativeLibraryDir;
            builder.environment().put("LD_LIBRARY_PATH",
                    javaHome.getAbsolutePath() + "/lib:"
                            + javaHome.getAbsolutePath() + "/lib/server:" + nativeLib);
            builder.environment().put("JAVA_HOME", javaHome.getAbsolutePath());
            process = builder.start();
        } catch (Throwable e) {
            trace("ensure: the preparation did not start: " + e);
        }
    }

    /** The preparation's process, while it runs: `ensure` waits on it and reads its lines. */
    private static volatile Process process;

    /**
     * Every path the preparation reads and writes, from what the launcher and the app already
     * know. None is written down here: the install, its libraries, its assets, the jars and the
     * Java all move with the launcher's own settings.
     *
     * <p>Null when something the preparation cannot do without is missing.
     */
    private static String[] pathsFor(Path versionRoot, File output, com.tungsten.fclcore.download.DownloadProvider provider) {
        // versionRoot is `.../versions/<id>`; the install is two folders above it.
        File versions = versionRoot.toFile();
        File install = versions.getParentFile() == null
                ? versions
                : versions.getParentFile().getParentFile();
        if (install == null) {
            return null;
        }
        String id = versions.getName();
        // The libraries FCL installs, wherever this launcher keeps them: `libraries/` beside the
        // versions, or a `libraries/` inside `versions/`. Both are searched, and the one holding
        // the game's own jars wins.
        File libraries = findLibraries(install);
        File assets = new File(install, "assets");
        File versionJson = new File(versions, id + ".json");
        File clientJar = new File(versions, id + ".jar");
        File serverJar = findServerJar(install);
        if (serverJar == null) {
            // The preparation exports from the server jar, and a launcher that only plays the
            // game never downloaded one: Mojang's own manifest names it, with its hash.
            serverJar = fetchServerJar(new File(versions, id + ".json"), install, provider);
        }
        File javaHome = findJava();
        File kit = new File(com.tungsten.fcl.FCLApp.getAppContext().getFilesDir(), "preparer/kit");
        unpackKit(kit);
        fetchManifest(kit, provider);
        String libraryDir = com.tungsten.fcl.FCLApp.getAppContext()
                .getApplicationInfo().nativeLibraryDir;
        trace("ensure: install=" + install + " id=" + id
                + " libs=" + libraries.isDirectory() + " assets=" + assets.isDirectory()
                + " json=" + versionJson.isFile() + " jar=" + clientJar.isFile()
                + " server=" + (serverJar == null ? "none" : serverJar.getAbsolutePath())
                + " java=" + (javaHome == null ? "none" : javaHome.getAbsolutePath())
                + " native=" + libraryDir);
        if (!versionJson.isFile() || !clientJar.isFile() || serverJar == null || javaHome == null) {
            return null;
        }
        return new String[] {
                output.getAbsolutePath(),
                install.getAbsolutePath(),
                libraries.getAbsolutePath(),
                assets.getAbsolutePath(),
                versionJson.getAbsolutePath(),
                clientJar.getAbsolutePath(),
                serverJar.getAbsolutePath(),
                javaHome.getAbsolutePath(),
                kit.getAbsolutePath(),
                libraryDir,
        };
    }


    /**
     * The preparation kit out of the APK's assets, into `kit`: the engine's own unpacking reads
     * it through the platform hooks, which are not registered before a game has started.
     */
    private static void unpackKit(File kit) {
        java.io.File marker = new java.io.File(kit, "research-lock.json");
        if (marker.isFile()) {
            return;
        }
        if (!kit.isDirectory() && !kit.mkdirs()) {
            return;
        }
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                com.tungsten.fcl.FCLApp.getAppContext().getAssets().open("prepare-kit.zip"))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                java.io.File target = new java.io.File(kit, entry.getName());
                java.io.File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    continue;
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            trace("ensure: kit unpacked into " + kit);
        } catch (Throwable e) {
            trace("ensure: the kit did not unpack: " + e);
        }
    }


    /**
     * Mojang's own version manifest, when the kit does not have one yet. The launcher's version
     * JSON is rewritten by FCL (its hash is not the pinned one), and the preparation refuses it:
     * this fetches the pinned file once, through the same download task and the same mirrors the
     * download page uses, and checks it against the hash in the kit's lock.
     */
    private static void fetchManifest(File kit, com.tungsten.fclcore.download.DownloadProvider provider) {
        File lock = new File(kit, "research-lock.json");
        if (!lock.isFile()) {
            return;
        }
        try {
            org.json.JSONObject minecraft = new org.json.JSONObject(readText(lock))
                    .getJSONObject("minecraft");
            String version = minecraft.getString("version");
            String sha1 = minecraft.getString("manifest_sha1");
            File target = new File(kit, ".local/minecraft/" + version + "/manifest.json");
            if (target.isFile()) {
                trace("ensure: manifest already there");
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            String pinned = "https://piston-meta.mojang.com/v1/packages/" + sha1 + "/"
                    + version + ".json";
            String url = provider == null ? pinned : provider.injectURL(pinned);
            trace("ensure: fetching manifest " + url);
            com.tungsten.fclcore.task.FileDownloadTask task =
                    new com.tungsten.fclcore.task.FileDownloadTask(
                            com.tungsten.fclcore.util.io.NetworkUtils.toURL(url),
                            target,
                            com.tungsten.fclcore.task.FileDownloadTask.IntegrityCheck
                                    .of("SHA-1", sha1));
            com.tungsten.fclcore.task.TaskExecutor executor = task.executor();
            executor.start();
            // The download runs on the launcher's executor: wait for it, since the preparation
            // cannot start without the file.
            for (int i = 0; i < 1200 && !executor.test(); i++) {
                Thread.sleep(100);
            }
            trace("ensure: manifest " + (target.isFile() ? "fetched" : "missing")
                    + (executor.getException() == null ? "" : " (" + executor.getException() + ")"));
        } catch (Throwable e) {
            trace("ensure: the manifest did not fetch: " + e);
        }
    }

    private static String readText(File file) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }



    /**
     * The version's libraries, LWJGL among them. The launcher leaves them out of a download (it
     * runs the game on its own copy), but the preparation reads them: it runs the vanilla data
     * generator, which loads every class the version names.
     */
    private static void completeLibraries(Path versionRoot, com.tungsten.fclcore.download.AbstractDependencyManager manager, java.util.function.Consumer<String> progress) {
        try {
            String id = versionRoot.toFile().getName();
            com.tungsten.fclcore.game.Version version = manager.getGameRepository()
                    .getVersion(id).resolve(manager.getGameRepository());
            // false: LWJGL and the rest stay in, which is what the preparation needs.
            com.tungsten.fclcore.game.Version full =
                    com.tungsten.fclcore.util.LibFilter.filter(version, false);
            com.tungsten.fclcore.download.game.GameLibrariesTask task =
                    new com.tungsten.fclcore.download.game.GameLibrariesTask(
                            manager, full, false, full.getLibraries(), false);
            // Its bar and its line, as the download page shows them: the preparation waits on
            // this, and a launcher that showed nothing for it would look stuck.
            task.progressProperty().addListener((observable, was, now) -> {
                if (now != null) {
                    progress.accept("fraction:" + now.doubleValue() * 0.05);
                }
            });
            task.messageProperty().addListener((observable, was, now) -> {
                if (now != null && !now.isEmpty()) {
                    progress.accept(now);
                }
            });
            progress.accept("正在检查游戏库");
            com.tungsten.fclcore.task.TaskExecutor executor = task.executor();
            executor.start();
            for (int i = 0; i < 1200 && !executor.test(); i++) {
                Thread.sleep(100);
            }
            trace("ensure: the libraries are " + (executor.getException() == null ? "complete" : "not complete: " + executor.getException()));
        } catch (Throwable e) {
            trace("ensure: the libraries did not complete: " + e);
        }
    }

    /**
     * The folder with the game's libraries: `libraries/` under the install, or under `versions/`.
     * A launcher may keep them either way, and the one with `org/lwjgl` is the complete one.
     */
    private static File findLibraries(File install) {
        File direct = new File(install, "libraries");
        File underVersions = new File(new File(install, "versions"), "libraries");
        boolean directHas = new File(direct, "org/lwjgl").isDirectory();
        boolean versionsHas = new File(underVersions, "org/lwjgl").isDirectory();
        if (versionsHas && !directHas) {
            return underVersions;
        }
        if (directHas) {
            return direct;
        }
        if (versionsHas) {
            return underVersions;
        }
        return direct;
    }


    /**
     * The server jar the version's manifest names, downloaded next to the game's own files:
     * nothing here is written down, the address and the hash both come from Mojang's manifest.
     */
    private static File fetchServerJar(File manifest, File install, com.tungsten.fclcore.download.DownloadProvider provider) {
        try {
            org.json.JSONObject downloads = new org.json.JSONObject(readText(manifest))
                    .getJSONObject("downloads");
            if (!downloads.has("server")) {
                return null;
            }
            org.json.JSONObject server = downloads.getJSONObject("server");
            String sha1 = server.getString("sha1");
            String target = install.getAbsolutePath() + "/server.jar";
            File jar = new File(target);
            if (jar.isFile() && jar.length() == server.optLong("size", -1)) {
                return jar;
            }
            String pinned = server.getString("url");
            String url = provider == null ? pinned : provider.injectURL(pinned);
            trace("ensure: fetching the server jar " + url);
            com.tungsten.fclcore.task.FileDownloadTask task =
                    new com.tungsten.fclcore.task.FileDownloadTask(
                            com.tungsten.fclcore.util.io.NetworkUtils.toURL(url),
                            jar,
                            com.tungsten.fclcore.task.FileDownloadTask.IntegrityCheck.of("SHA-1", sha1));
            com.tungsten.fclcore.task.TaskExecutor executor = task.executor();
            executor.start();
            for (int i = 0; i < 1200 && !executor.test(); i++) {
                Thread.sleep(100);
            }
            trace("ensure: the server jar " + (jar.isFile() ? "is there" : "is missing"));
            return jar.isFile() ? jar : null;
        } catch (Throwable e) {
            trace("ensure: the server jar did not fetch: " + e);
            return null;
        }
    }

    /** The server jar the preparation exports from: wherever the install keeps it. */
    private static File findServerJar(File install) {
        for (String name : new String[] {"server.jar", "minecraft_server.jar"}) {
            File jar = new File(install, name);
            if (jar.isFile()) {
                return jar;
            }
        }
        File versions = new File(install, "versions");
        File[] entries = versions.listFiles();
        if (entries == null) {
            return null;
        }
        for (File entry : entries) {
            File jar = new File(entry, "server.jar");
            if (jar.isFile()) {
                return jar;
            }
        }
        return null;
    }

    /**
     * The Java the exporters run on: the launcher's own runtimes, newest first, wherever it put
     * them. None is named: the launcher picks its Java and this follows it.
     */
    private static File findJava() {
        File data = com.tungsten.fcl.FCLApp.getAppContext().getApplicationInfo().dataDir == null
                ? null
                : new File(com.tungsten.fcl.FCLApp.getAppContext().getApplicationInfo().dataDir);
        if (data == null) {
            return null;
        }
        File runtime = new File(data, "app_runtime/java");
        File[] entries = runtime.listFiles();
        if (entries == null) {
            return null;
        }
        java.util.Arrays.sort(entries, (a, b) -> b.getName().compareTo(a.getName()));
        for (File entry : entries) {
            if (new File(entry, "bin/java").isFile()) {
                return entry;
            }
        }
        return null;
    }

    /**
     * What the preparer is doing, as its last line: the engine writes it where Java can read it
     * (preparer-stage.txt in the app's files), since a String back through JNI needs an array.
     */
    public static String stageText() {
        try {
            java.io.File file = new java.io.File(appFiles(), "preparer-stage.txt");
            if (!file.isFile()) {
                return "";
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * Whether the folder holds a complete preparation. The engine writes
     * {@code horizon-resources.json} last, so its presence is the marker: a run cut short leaves
     * no marker and is made again.
     */
    public static boolean complete(File folder) {
        return new File(folder, "horizon-resources.json").isFile();
    }

    /**
     * Makes the resources when they are missing, and waits for the engine to finish: the game
     * would start against an empty folder otherwise.
     */
    public static void ensure(Path versionRoot, String gameVersion) {
        ensure(versionRoot, gameVersion, line -> { });
    }

    /** `ensure`, reporting each line the engine says as `progress`. */
    public static void ensure(Path versionRoot, String gameVersion, java.util.function.Consumer<String> progress) {
        ensure(versionRoot, gameVersion, progress, null);
    }

    /** `ensure`, with the launcher's dependency manager: its libraries and its download. */
    public static void ensure(Path versionRoot, String gameVersion, java.util.function.Consumer<String> progress, com.tungsten.fclcore.download.AbstractDependencyManager manager) {
        trace("=== ensure: version=" + gameVersion + " root=" + versionRoot);
        if (!supported(gameVersion)) {
            trace("ensure: version not supported");
            return;
        }
        File folder = folder(versionRoot);
        if (complete(folder)) {
            trace("ensure: already complete");
            return;
        }
        trace("ensure: asking the engine to prepare " + folder);
        com.tungsten.fclcore.download.DownloadProvider provider =
                manager == null ? null : manager.getDownloadProvider();
        if (manager != null) {
            completeLibraries(versionRoot, manager, progress);
        }
        String[] paths = pathsFor(versionRoot, folder, provider);
        if (paths == null) {
            trace("ensure: the engine's files are not all here yet");
            return;
        }
        try {
            ask(paths);
        } catch (Throwable e) {
            // The engine's library loads with its activity, which has not started yet: load it
            // here and ask again.
            trace("ensure: first ask failed (" + e + "), loading the engine's library");
            try {
                System.loadLibrary("horizon_android");
                ask(paths);
            } catch (Throwable again) {
                trace("ensure: the engine's library did not load: " + again);
                return;
            }
        }

        // The preparation is its own process now: its lines are read as it writes them, and the
        // launcher waits for it, since the launch is what it is holding up.
        Process running = process;
        if (running == null) {
            progress.accept("stage:5");
            return;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(running.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                trace("ensure: " + line);
                // {"message": ...} is the words for the row, {"fraction": F} how far along it
                // is, {"error": ...} why it stopped: the preparation says one a line.
                if (line.startsWith("{")) {
                    try {
                        org.json.JSONObject event = new org.json.JSONObject(line);
                        if (event.has("message")) {
                            progress.accept(event.getString("message"));
                            continue;
                        }
                        if (event.has("fraction")) {
                            progress.accept("fraction:" + event.getDouble("fraction"));
                            continue;
                        }
                        if (event.has("error")) {
                            progress.accept(event.getString("error"));
                            progress.accept("stage:5");
                            return;
                        }
                    } catch (org.json.JSONException ignored) {
                        // Not one of the preparation's objects: shown as it came.
                    }
                }
                progress.accept(line);
            }
            int code = running.waitFor();
            trace("ensure: the preparation exited with " + code);
            progress.accept(code == 0 && complete(folder) ? "stage:4" : "stage:5");
        } catch (Throwable e) {
            trace("ensure: the preparation did not finish: " + e);
            progress.accept("stage:5");
        }
    }
}
