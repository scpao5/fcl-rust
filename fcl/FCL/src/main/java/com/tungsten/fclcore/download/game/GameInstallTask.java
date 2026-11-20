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
package com.tungsten.fclcore.download.game;

import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.MINECRAFT;

import com.tungsten.fclcore.download.DefaultDependencyManager;
import com.tungsten.fclcore.game.DefaultGameRepository;
import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class GameInstallTask extends Task<Version> {

    private final DefaultGameRepository gameRepository;
    private final DefaultDependencyManager dependencyManager;
    private final Version version;
    private final GameRemoteVersion remote;
    private final VersionJsonDownloadTask downloadTask;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    public GameInstallTask(DefaultDependencyManager dependencyManager, Version version, GameRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.gameRepository = dependencyManager.getGameRepository();
        this.version = version;
        this.remote = remoteVersion;
        this.downloadTask = new VersionJsonDownloadTask(remoteVersion.getGameVersion(), dependencyManager);
    }


    /**
     * Asks Starling's engine to prepare its resources from the files just downloaded, and waits
     * for it: the game reads them, and a resource folder made before the files changed is stale.
     * Only 26.2 and later have the engine that reads them.
     */
    private void starlingPrepare(DefaultGameRepository repository, Version version, String game) {
        try {
            com.tungsten.fcl.game.StarlingResources.ensure(
                    repository.getVersionRoot(version.getId()).toPath(), game,
                    line -> { }, dependencyManager);
        } catch (Throwable e) {
            // The launcher's own game still starts; the engine will say what it needs.
        }
    }


    @Override
    public Collection<Task<?>> getDependents() {
        return Collections.singleton(downloadTask);
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isRelyingOnDependencies() {
        return false;
    }

    @Override
    public void execute() throws Exception {
        Version patch = JsonUtils.fromNonNullJson(downloadTask.getResult(), Version.class)
                .setId(MINECRAFT.getPatchId()).setVersion(remote.getGameVersion()).setJar(null).setPriority(0);
        setResult(patch);

        Version version = new Version(this.version.getId()).addPatch(patch);
        dependencies.add(Task.allOf(
                new GameDownloadTask(dependencyManager, remote.getGameVersion(), version),
                Task.allOf(
                        new GameAssetDownloadTask(dependencyManager, version, GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY, true),
                        new GameLibrariesTask(dependencyManager, version, true)
                ).withRunAsync(() -> {
                    // ignore failure
                })
        ).thenComposeAsync(gameRepository.saveAsync(version)).thenApplyAsync(saved -> {
            // The engine reads its own resources, not the jars: they are made here, where the
            // game's files have just been downloaded and checked, and only 26.2+ has them.
            starlingPrepare(gameRepository, saved, remote.getGameVersion());
            return saved;
        }));
    }

}
