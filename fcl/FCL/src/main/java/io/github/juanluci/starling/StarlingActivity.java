package io.github.juanluci.starling;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.tungsten.fcl.R;
import com.tungsten.fcl.control.GameMenu;
import com.tungsten.fcl.FCLApp;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fcllibrary.component.FCLActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The game screen: Starling's engine draws into a TextureView, and FCL's own GameMenu (the touch
 * buttons, the menu, the controls editor) is drawn on top of it by the same code that draws it for
 * a Java game.
 *
 * <p>This is a plain FCLActivity, not a NativeActivity: the engine cannot be one either, because a
 * NativeActivity owns its window and nothing can be laid over it. The engine is handed the
 * TextureView's Surface instead (setSurface, android_lib.rs) and renders into that, while
 * its event loop keeps running on the same thread. Input reaches it from FCL's controls through
 * the send* calls below, which fill the same queues winit's own events go to.
 *
 * <p>The package is the engine's own and cannot move: its JNI entry points are named after it.
 */
public class StarlingActivity extends FCLActivity {
    private static final String TAG = "starling";

    /** The folder the launcher chose: the engine keeps its worlds, packs and options there. */
    public static final String EXTRA_GAME_DIR = "io.github.juanluci.starling.GAME_DIR";

    /** The launcher's Minecraft install: the folder holding versions/, assets/ and libraries/. */
    public static final String EXTRA_MINECRAFT_ROOT = "io.github.juanluci.starling.MINECRAFT_ROOT";

    /** The player name the launcher signed in with, so the engine joins with it. */
    public static final String EXTRA_PLAYER = "io.github.juanluci.starling.PLAYER";

    private static final String FILES_DONE = "starling-files.json";
    private static final String VERSION = "26.3";

    /** The engine's, from android_lib.rs. */
    private static native void onStorage(String folder);
    public static native void onMinecraftRoot(String folder);

    /** Asks the engine to prepare the game's resources once FCL has downloaded its files. */
    public static native void prepareResources(String[] paths);

    /** The preparation's stage: 0 idle, 1 java, 2 unpacking, 3 preparing, 4 done, 5 failed. */
    public static native int preparerStage();
    public static native void sendMouseMove(float x, float y);
    public static native void sendMouseButton(int button, boolean down);
    public static native void sendMouseWheel(float x, float y);
    public static native void sendMouseMotion(float dx, float dy);
    public static native void sendKey(String name, boolean down, int text);

    /** Hands the engine a Surface to render into; null when the TextureView's surface goes. */
    private static native void setSurface(Surface surface);

    /**
     * Starts the game's own thread. The launcher is an ordinary Activity, so the engine has no
     * NativeActivity to be started by: it takes this activity, its APK's assets and its files
     * directory and keeps them for the JNI calls that follow.
     */
    private native void startEngine(String filesDir, AssetManager assets);

    /** The engine's library, loaded once per process. */
    private static boolean libraryLoaded;

    private GameMenu menu;
    private FCLBridge bridge;
    private String gameDir;
    /** The Surface handed to the engine now; the one before it is released. */
    private Surface textureSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        traceFile = new File(getFilesDir(), "engine_trace.txt");
        trace("onCreate: entered");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        trace("onCreate: flag set");
        loadGame();
        trace("onCreate: library loaded");
        super.onCreate(savedInstanceState);
        trace("onCreate: super done");
        setContentView(R.layout.activity_starling);
        trace("onCreate: content set");

        gameDir = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_GAME_DIR);
        String player = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PLAYER);
        if (gameDir != null) {
            File folder = new File(gameDir);
            if (!folder.isDirectory() && !folder.mkdirs()) {
                Log.w(TAG, "cannot use the launcher's folder: " + gameDir);
            } else {
                Log.i(TAG, "the launcher's folder: " + gameDir);
                onStorage(gameDir);
                String root = getIntent().getStringExtra(EXTRA_MINECRAFT_ROOT);
                if (root != null) {
                    Log.i(TAG, "the launcher's install: " + root);
                    onMinecraftRoot(root);
                }
                writeFilesDoneIfComplete(folder);
            }
        }
        if (gameDir != null && player != null && !player.isEmpty()) {
            try {
                Files.write(new File(gameDir, "horizon-player.txt").toPath(),
                        ("name:" + player + "\n").getBytes(StandardCharsets.UTF_8));
                Log.i(TAG, "the launcher's player: " + player);
            } catch (Exception e) {
                Log.w(TAG, "cannot keep the player name: " + e);
            }
        }
        // FCL's accounts and skins, in the shape the engine reads, in the game folder it runs in.
        // After the name, which shares horizon-player.txt: the skin is added to that file.
        importAccounts(getFilesDir(), gameDir != null ? new File(gameDir) : null);

        // FCL's controls over the engine's picture, in JVMActivity's order: set up first (it finds
        // this activity's own views, texture_view among them), then lay the menu over them.
        bridge = new FCLBridge();
        menu = new GameMenu();
        menu.setup(this, bridge);
        addContentView(menu.getLayout(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // After the bridge and the menu exist: the engine's first lines go to the log window,
        // which has nowhere to put them before this.
        startEngine(getFilesDir().getAbsolutePath(), getAssets());
        trace("onCreate: engine asked for");

        TextureView view = findViewById(R.id.texture_view);
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                trace("surface available " + width + "x" + height);
                useSurface(texture);
                // The engine draws its first frame into the surface it was just given. FCL waits
                // for JVMActivity's callback to clear the launch spinner; nothing calls it here,
                // so the spinner would turn forever, and the menu is ready to be seen.
                runOnUiThread(() -> menu.onGraphicOutput());
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                // The same SurfaceTexture, a new size: the engine's surface is rebuilt from it.
                useSurface(texture);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                trace("surface destroyed");
                useSurface(null);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        // A TextureView that is already alive (the activity came back) reports no callback, so the
        // engine would hold no surface and draw nothing.
        if (view.isAvailable() && view.getSurfaceTexture() != null) {
            useSurface(view.getSurfaceTexture());
            runOnUiThread(() -> menu.onGraphicOutput());
        }

        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onDestroy() {
        // The engine outlives this activity in the same process: without this it would keep
        // drawing into a window whose surface is gone.
        useSurface(null);
        super.onDestroy();
    }

    /** Hands `texture`'s surface to the engine, releasing the one it had: a TextureView keeps the
     *  same SurfaceTexture across a resize, and a Surface holds a reference of its own. */

    /**
     * FCL's accounts, in the shape the engine reads. FCL keeps `accounts.json` at the files root;
     * the engine reads `accounts/accounts.json` with its own fields, so a plain copy would leave
     * the player unnamed in game (Player&#8203;63859).
     */
    @SuppressWarnings("unchecked")
    private static void importAccounts(java.io.File files, java.io.File game) {
        java.io.File source = new java.io.File(files, "accounts.json");
        if (!source.isFile()) {
            return;
        }
        try {
            org.json.JSONArray list = new org.json.JSONArray(readFile(source));
            org.json.JSONArray online = new org.json.JSONArray();
            org.json.JSONArray offline = new org.json.JSONArray();
            String selectedOnline = null;
            String selectedOffline = null;
            String offlineSkin = null;
            String offlineModel = null;
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject account = list.optJSONObject(i);
                if (account == null) {
                    continue;
                }
                String name = account.optString("username", "");
                String uuid = account.optString("uuid", "").replace("-", "");
                String type = account.optString("type", "offline");
                org.json.JSONObject skin = account.optJSONObject("skin");
                if ("offline".equals(type)) {
                    if (name.isEmpty()) {
                        continue;
                    }
                    offline.put(name);
                    if (selectedOffline == null) {
                        selectedOffline = name;
                        // The offline player's own skin: a local file FCL copied in, or its
                        // built-in Steve or Alex.
                        String path = skin == null ? null : skin.optString("localSkinPath", "");
                        if (path != null && !path.isEmpty() && new java.io.File(path).isFile()) {
                            offlineSkin = path;
                            offlineModel = skin.optString("textureModel", "default");
                        } else if (skin != null && !"default".equals(skin.optString("type", "default"))) {
                            offlineSkin = skin.optString("type", "");
                            offlineModel = skin.optString("textureModel", "default");
                        }
                    }
                } else if (!uuid.isEmpty() && !name.isEmpty()) {
                    // A signed-in account (Microsoft or a skin site): the engine reads the
                    // profile and, with a token, keeps refreshing it.
                    org.json.JSONObject entry = new org.json.JSONObject();
                    entry.put("provider", "ely".equals(type) ? "Ely" : "Microsoft");
                    entry.put("uuid", uuid.toLowerCase());
                    entry.put("name", name);
                    String accessToken = account.optString("accessToken", "");
                    String refreshToken = account.optString("refreshToken", "");
                    if (!accessToken.isEmpty()) {
                        entry.put("accessToken", accessToken);
                    }
                    if (!refreshToken.isEmpty()) {
                        entry.put("refreshToken", refreshToken);
                    }
                    long notAfter = account.optLong("notAfter", 0L);
                    if (notAfter > 0) {
                        entry.put("notAfter", notAfter);
                    }
                    online.put(entry);
                    if (selectedOnline == null) {
                        selectedOnline = uuid.toLowerCase();
                    }
                }
            }
            org.json.JSONObject out = new org.json.JSONObject();
            out.put("accounts", online);
            out.put("selected", selectedOnline == null ? org.json.JSONObject.NULL : selectedOnline);
            out.put("offline", offline);
            out.put("offline_selected", selectedOffline == null ? org.json.JSONObject.NULL : selectedOffline);
            java.io.File dir = new java.io.File(files, "accounts");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            java.io.File target = new java.io.File(dir, "accounts.json");
            try (java.io.FileWriter writer = new java.io.FileWriter(target, false)) {
                writer.write(out.toString());
            }
            if (offlineSkin != null) {
                importOfflineSkin(game, offlineSkin, offlineModel);
            }
            trace("accounts: imported " + online.length() + " online, " + offline.length()
                    + " offline, skin " + offlineSkin);
        } catch (Exception e) {
            trace("accounts: import failed: " + e);
        }
    }

    /**
     * The offline player's skin, where the engine reads it: `skins/&lt;name&gt;.png` in the game
     * folder, and its file name and model in the `horizon-player.txt` beside `options.txt`.
     */
    private static void importOfflineSkin(java.io.File game, String source, String model) {
        if (game == null) {
            return;
        }
        try {
            java.io.File skins = new java.io.File(game, "skins");
            if (!skins.isDirectory() && !skins.mkdirs()) {
                return;
            }
            String name = "offline.png";
            java.io.File from = new java.io.File(source);
            if (from.isFile()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(from);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(skins, name))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            } else if ("alex".equalsIgnoreCase(source) || "steve".equalsIgnoreCase(source)) {
                try (java.io.InputStream in = FCLApp.getAppContext().getAssets()
                        .open("img/" + source.toLowerCase() + ".png");
                     java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(skins, name))) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            } else {
                return;
            }
            // The file's other lines (the player's name) stay: only the skin's two are replaced.
            java.io.File player = new java.io.File(game, "horizon-player.txt");
            String slim = "slim".equalsIgnoreCase(model) || "alex".equalsIgnoreCase(source) ? "slim" : "classic";
            java.util.LinkedHashMap<String, String> lines = new java.util.LinkedHashMap<>();
            if (player.isFile()) {
                for (String line : readFile(player).split("\n")) {
                    String trimmed = line.trim();
                    int at = trimmed.indexOf(':');
                    if (at > 0) {
                        lines.put(trimmed.substring(0, at), trimmed.substring(at + 1));
                    }
                }
            }
            lines.put("skin", name);
            lines.put("model", slim);
            StringBuilder text = new StringBuilder();
            for (java.util.Map.Entry<String, String> line : lines.entrySet()) {
                text.append(line.getKey()).append(':').append(line.getValue()).append('\n');
            }
            try (java.io.FileWriter writer = new java.io.FileWriter(player, false)) {
                writer.write(text.toString());
            }
            trace("accounts: offline skin " + name + " (" + slim + ")");
        } catch (Exception e) {
            trace("accounts: skin import failed: " + e);
        }
    }

    private static String readFile(java.io.File file) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return out.toString("UTF-8");
    }

    /**
     * The engine's log lines, into the launcher's log window: FCL reads a game's console through
     * FCLBridge.receiveLog, and the engine's own messages go the same way.
     */
    public void onEngineLog(String line) {
        runOnUiThread(() -> {
            if (bridge != null) {
                bridge.receiveLog(line);
            }
        });
    }

    /**
     * The engine stopped with an error: its own screen shows it, with plain View code, since a
     * crash is when a layout or a theme is least trustworthy.
     */
    public void onEngineFailure(String error) {
        trace("engine failure: " + error);
        runOnUiThread(() -> showCrash(error == null ? "The engine stopped." : error));
    }

    /** Starts the crash screen, whether or not this activity still has a window. */
    private void showCrash(String text) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(this, EngineCrashActivity.class);
            intent.putExtra(EngineCrashActivity.EXTRA_TEXT, text);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Throwable e) {
            trace("cannot show the crash screen: " + e);
        }
    }

    /** The engine's pointer grab, as a Java game's onCursorModeChange: 1 frees the pointer (a
     * screen, the title), 0 holds it (gameplay). The controls follow it.
     */
    public void onEngineCursorMode(int mode) {
        runOnUiThread(() -> {
            if (menu != null) {
                menu.onCursorModeChange(mode == 1 ? FCLBridge.CursorEnabled : FCLBridge.CursorDisabled);
            }
        });
    }

    private void useSurface(SurfaceTexture texture) {
        Surface next = texture == null ? null : new Surface(texture);
        setSurface(next);
        if (textureSurface != null && textureSurface != next) {
            textureSurface.release();
        }
        textureSurface = next;
    }

    /**
     * One line in logcat and in {@code engine_trace.txt} next to the game's files. logcat's buffer
     * rolls over within minutes on this device, so the file is what a start is read from.
     */
    private static File traceFile;

    static void trace(String line) {
        Log.i(TAG, line);
        if (traceFile == null) {
            return;
        }
        try (java.io.FileWriter writer = new java.io.FileWriter(traceFile, true)) {
            writer.write(line + "\n");
        } catch (Throwable ignored) {
            // The trace must never be what breaks a start.
        }
    }

    /** Loads the engine's library once: its JNI entry points must exist before onCreate calls one. */
    private static synchronized void loadGame() {
        if (!libraryLoaded) {
            System.loadLibrary("horizon_android");
            libraryLoaded = true;
        }
    }

    private static void writeFilesDoneIfComplete(File root) {
        File marker = new File(root, FILES_DONE);
        if (marker.exists()) {
            return;
        }
        String name = root.getName();
        File jar = new File(root, name + ".jar");
        if (!jar.isFile()) {
            jar = new File(root, "versions/" + name + "/" + name + ".jar");
        }
        File upper = root.getParentFile();
        File libraries = new File(root, "libraries");
        if (!libraries.isDirectory()) {
            libraries = upper == null ? libraries : new File(upper, "libraries");
        }
        File objects = new File(root, "assets/objects");
        if (!objects.isDirectory()) {
            objects = upper == null ? objects : new File(upper, "assets/objects");
        }
        if (!jar.isFile() || jar.length() == 0) {
            Log.i(TAG, "no " + jar.getPath() + ": the engine downloads the files itself");
            return;
        }
        String[] libs = libraries.list();
        String[] objs = objects.list();
        if (libs == null || libs.length == 0 || objs == null || objs.length == 0) {
            Log.i(TAG, "libraries or assets are empty in " + root + ": letting the engine fetch");
            return;
        }
        // The preparer needs the pinned server jar to run its exporters: a launcher keeps it beside
        // the version folder, or in the version folder itself.
        File server = new File(root, "server.jar");
        if (!server.isFile()) {
            server = new File(root, name + "-server.jar");
        }
        if (!server.isFile() && upper != null) {
            server = new File(upper, "server.jar");
        }
        String text = "{\"version\":\"" + VERSION + "\",\"client\":\""
                + jar.getAbsolutePath().replace("\\", "/") + "\",\"server\":\""
                + (server.isFile() ? server.getAbsolutePath().replace("\\", "/") : "")
                + "\"}";
        try {
            Files.write(marker.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "the launcher's files are complete, wrote " + marker.getPath());
        } catch (Exception e) {
            Log.w(TAG, "cannot write " + marker.getPath() + ": " + e);
        }
    }
}
