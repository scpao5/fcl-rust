package com.tungsten.fclcore.mod.horizon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tungsten.fclcore.download.DownloadProvider;
import com.tungsten.fclcore.mod.LocalModFile;
import com.tungsten.fclcore.mod.ModLoaderType;
import com.tungsten.fclcore.mod.RemoteMod;
import com.tungsten.fclcore.mod.RemoteModRepository;
import com.tungsten.fclcore.util.io.NetworkUtils;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Horizon's own mod index (horizon-rust.github.io/horizon-mods/index.json).
 *
 * <p>The engine runs no Java mod: its mods are native bundles (.hnmod), content packs (.hpack)
 * and shaders, compiled for one exact game version. The index is a single JSON document listing
 * every project with its versions, so a search is a filter over it rather than a request per
 * page, and {@link #refresh()} is the only network call.
 */
public final class HorizonRemoteModRepository implements RemoteModRepository {

    /** The index the engine's own registry reads (crates/horizon-mods/src/index.rs). */
    public static final String INDEX_URL =
            "https://horizon-rust.github.io/horizon-mods/index.json";

    private final Type type;
    /** The index as fetched, kept until a refresh: searching twice costs one request. */
    private JsonObject index;

    public HorizonRemoteModRepository(Type type) {
        this.type = type;
    }

    @Override
    public Type getType() {
        return type;
    }

    /** Fetches the index again; null leaves the cached one in place. */
    public void refresh() {
        index = null;
    }

    private JsonObject index() throws IOException {
        if (index == null) {
            String text = NetworkUtils.doGet(NetworkUtils.toURL(INDEX_URL));
            index = JsonParser.parseString(text).getAsJsonObject();
        }
        return index;
    }

    /** The kinds of this repository's index entries a page of this type lists. */
    private List<String> kinds() {
        return switch (type) {
            case MOD -> List.of("native-mod", "mod");
            case RESOURCE_PACK -> List.of("resourcepack");
            case SHADER_PACK -> List.of("shader");
            case MODPACK -> List.of("modpack");
            default -> List.of();
        };
    }

    private Stream<JsonObject> projects() throws IOException {
        List<String> kinds = kinds();
        JsonArray all = index().getAsJsonArray("projects");
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : all) {
            JsonObject project = element.getAsJsonObject();
            String kind = optionalString(project, "kind");
            if (kind != null && kinds.contains(kind)) {
                out.add(project);
            }
        }
        return out.stream();
    }

    @Override
    public SearchResult search(DownloadProvider downloadProvider, String gameVersion,
                               @Nullable Category category, int pageOffset, int pageSize,
                               String searchFilter, SortType sortType, SortOrder sortOrder)
            throws IOException {
        String needle = searchFilter == null ? "" : searchFilter.toLowerCase();
        List<JsonObject> matched = projects()
                .filter(project -> gameVersion == null || gameVersion.isEmpty()
                        || versionsFor(project, gameVersion).findAny().isPresent())
                .filter(project -> needle.isEmpty() || matches(project, needle))
                .sorted((a, b) -> compare(a, b, sortType, sortOrder))
                .collect(Collectors.toList());

        int total = matched.size();
        int pages = pageSize <= 0 ? 1 : Math.max(1, (total + pageSize - 1) / pageSize);
        List<RemoteMod> page = matched.stream()
                .skip((long) pageOffset * Math.max(pageSize, 1))
                .limit(Math.max(pageSize, 1))
                .map(this::toRemoteMod)
                .collect(Collectors.toList());
        return new SearchResult(page.stream(), pages);
    }

    private boolean matches(JsonObject project, String needle) {
        for (String field : new String[]{"name", "summary", "description", "id"}) {
            String value = optionalString(project, field);
            if (value != null && value.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private int compare(JsonObject a, JsonObject b, SortType sortType, SortOrder order) {
        int result = switch (sortType) {
            case TOTAL_DOWNLOADS, POPULARITY -> Long.compare(downloads(a), downloads(b));
            case NAME -> str(a, "name").compareToIgnoreCase(str(b, "name"));
            case DATE_CREATED, LAST_UPDATED -> {
                Instant ta = published(a);
                Instant tb = published(b);
                yield ta.compareTo(tb);
            }
            case AUTHOR -> str(a, "name").compareToIgnoreCase(str(b, "name"));
        };
        return order == SortOrder.ASC ? result : -result;
    }

    private static long downloads(JsonObject project) {
        JsonElement value = project.get("downloads");
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static Instant published(JsonObject project) {
        return versionsFor(project)
                .map(v -> optionalString(v, "published"))
                .filter(s -> s != null)
                .map(HorizonRemoteModRepository::instant)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
    }

    /** The index writes dates as "2026-10-29" (a plain day) and, in some entries, a full ISO
     *  timestamp. Both map to an instant at the start of the day in UTC. */
    private static Instant instant(String text) {
        if (text == null || text.isEmpty()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            // Not a timestamp: fall through to a date.
        }
        try {
            return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private static Stream<JsonObject> versionsFor(JsonObject project) {
        JsonElement versions = project.get("versions");
        if (versions == null || !versions.isJsonArray()) {
            return Stream.empty();
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : versions.getAsJsonArray()) {
            if (element.isJsonObject()) {
                out.add(element.getAsJsonObject());
            }
        }
        return out.stream();
    }

    private static Stream<JsonObject> versionsFor(JsonObject project, String gameVersion) {
        return versionsFor(project).filter(v -> gameVersion.equals(optionalString(v, "game")));
    }

    private RemoteMod toRemoteMod(JsonObject project) {
        String id = str(project, "id");
        String name = str(project, "name");
        String summary = optionalString(project, "summary");
        String icon = optionalString(project, "icon");
        List<String> categories = stringList(project, "categories");
        if (categories.isEmpty()) {
            categories = stringList(project, "tags");
        }
        String page = "https://horizon-rust.github.io/horizon-mods/#" + id;
        return new RemoteMod(id, authors(project), name,
                summary == null ? "" : summary, categories, page, icon,
                new HorizonMod(project), (int) Math.min(downloads(project), Integer.MAX_VALUE), id);
    }

    private static String authors(JsonObject project) {
        List<String> owners = stringList(project, "owners");
        if (owners.isEmpty()) {
            owners = stringList(project, "authors");
        }
        return String.join(", ", owners);
    }

    private static List<String> stringList(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }

    private static String optionalString(JsonObject parent, String field) {
        JsonElement element = parent.get(field);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? null : element.getAsString();
    }

    private static String str(JsonObject parent, String field) {
        String value = optionalString(parent, field);
        return value == null ? "" : value;
    }

    @Override
    public Optional<RemoteMod.Version> getRemoteVersionByLocalFile(LocalModFile localModFile, Path file) {
        return Optional.empty();
    }

    @Override
    public RemoteMod getModById(String id) throws IOException {
        return projects()
                .filter(project -> id.equals(optionalString(project, "id")))
                .findFirst()
                .map(this::toRemoteMod)
                .orElseThrow(() -> new IOException("no project " + id + " in Horizon's index"));
    }

    @Override
    public RemoteMod.File getModFile(String modId, String fileId) throws IOException {
        return versionsFor(projectById(modId))
                .map(HorizonRemoteModRepository::fileOf)
                .filter(file -> file != null && fileId.equals(file.filename()))
                .findFirst()
                .orElseThrow(() -> new IOException("no file " + fileId + " of " + modId));
    }

    private JsonObject projectById(String id) throws IOException {
        return projects()
                .filter(project -> id.equals(optionalString(project, "id")))
                .findFirst()
                .orElseThrow(() -> new IOException("no project " + id + " in Horizon's index"));
    }

    @Nullable
    private static RemoteMod.File fileOf(JsonObject version) {
        JsonElement element = version.get("file");
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject file = element.getAsJsonObject();
        String url = optionalString(file, "url");
        String name = optionalString(file, "name");
        if (url == null || name == null) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        String sha256 = optionalString(file, "sha256");
        if (sha256 != null) {
            hashes.put("sha256", sha256);
        }
        return new RemoteMod.File(hashes, url, name);
    }

    @Override
    public Stream<RemoteMod.Version> getRemoteVersionsById(String id) throws IOException {
        JsonObject project = projectById(id);
        String modid = optionalString(project, "id");
        return versionsFor(project).map(version -> toVersion(modid, version));
    }

    private RemoteMod.Version toVersion(String modid, JsonObject version) {
        RemoteMod.File file = fileOf(version);
        String game = str(version, "game");
        return new RemoteMod.Version(
                new HorizonVersion(),
                modid,
                str(version, "version"),
                str(version, "version"),
                optionalString(version, "changelog"),
                instant(str(version, "published")),
                RemoteMod.VersionType.Release,
                file,
                Collections.emptyList(),
                game.isEmpty() ? Collections.emptyList() : List.of(game),
                Collections.emptyList());
    }

    @Override
    public Stream<Category> getCategories() throws IOException {
        Map<String, Category> categories = new LinkedHashMap<>();
        projects().forEach(project -> {
            for (String name : stringList(project, "categories")) {
                categories.computeIfAbsent(name, key -> new Category(key, key, Collections.emptyList()));
            }
        });
        return categories.values().stream();
    }

    /** The index's own entry, kept so a mod's page can read fields the repository does not model. */
    public static final class HorizonMod implements RemoteMod.IMod {
        private final JsonObject project;

        HorizonMod(JsonObject project) {
            this.project = project;
        }

        public JsonObject json() {
            return project;
        }

        @Override
        public List<RemoteMod> loadDependencies(RemoteModRepository modRepository) {
            // The index lists no dependencies: a native mod declares its own api_level.
            return Collections.emptyList();
        }

        @Override
        public Stream<RemoteMod.Version> loadVersions(RemoteModRepository modRepository) throws IOException {
            if (!(modRepository instanceof HorizonRemoteModRepository repository)) {
                return Stream.empty();
            }
            String id = optionalString(project, "id");
            return repository.getRemoteVersionsById(id);
        }

        @Override
        public List<RemoteMod.Screenshot> loadScreenshots(RemoteModRepository modRepository) {
            List<RemoteMod.Screenshot> out = new ArrayList<>();
            JsonElement shots = project.get("screenshots");
            if (shots != null && shots.isJsonArray()) {
                for (JsonElement shot : shots.getAsJsonArray()) {
                    if (shot.isJsonPrimitive()) {
                        out.add(new RemoteMod.Screenshot(shot.getAsString(), null, null));
                    }
                }
            }
            return out;
        }
    }

    private static final class HorizonVersion implements RemoteMod.IVersion {
        @Override
        public RemoteMod.Type getType() {
            // RemoteMod.Type names where a *Java* mod comes from, and this engine loads none.
            // Nothing here reads it: a Horizon version is handed straight to its own installer.
            return RemoteMod.Type.MODRINTH;
        }
    }
}
