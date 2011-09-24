/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sdkuilib.internal.repository.sdkman2;

import com.android.sdklib.internal.repository.Archive;
import com.android.sdklib.internal.repository.ITask;
import com.android.sdklib.internal.repository.ITaskMonitor;
import com.android.sdklib.internal.repository.NullTaskMonitor;
import com.android.sdklib.internal.repository.Package;
import com.android.sdklib.internal.repository.SdkSource;
import com.android.sdklib.internal.repository.Package.UpdateInfo;
import com.android.sdkuilib.internal.repository.UpdaterData;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads packages fetched from the remote SDK Repository and keeps track
 * of their state compared with the current local SDK installation.
 */
class PackageLoader {

    private final UpdaterData mUpdaterData;

    /**
     * Interface for the callback called by
     * {@link PackageLoader#loadPackages(ISourceLoadedCallback)}.
     * <p/>
     * After processing each source, the package loader calls {@link #onUpdateSource}
     * with the list of packages found in that source.
     * By returning true from {@link #onUpdateSource}, the client tells the loader to
     * continue and process the next source. By returning false, it tells to stop loading.
     * <p/>
     * The {@link #onLoadCompleted()} method is guaranteed to be called at the end, no
     * matter how the loader stopped, so that the client can clean up or perform any
     * final action.
     */
    public interface ISourceLoadedCallback {
        /**
         * After processing each source, the package loader calls this method with the
         * list of packages found in that source.
         * By returning true from {@link #onUpdateSource}, the client tells
         * the loader to continue and process the next source.
         * By returning false, it tells to stop loading.
         * <p/>
         * <em>Important</em>: This method is called from a sub-thread, so clients which
         * try to access any UI widgets must wrap their calls into
         * {@link Display#syncExec(Runnable)} or {@link Display#asyncExec(Runnable)}.
         *
         * @param packages All the packages loaded from the source. Never null.
         * @return True if the load operation should continue, false if it should stop.
         */
        public boolean onUpdateSource(SdkSource source, Package[] packages);

        /**
         * This method is guaranteed to be called at the end, no matter how the
         * loader stopped, so that the client can clean up or perform any final action.
         */
        public void onLoadCompleted();
    }

    /**
     * Interface describing the task of installing a specific package.
     * For details on the operation,
     * see {@link PackageLoader#loadPackagesWithInstallTask(IAutoInstallTask)}.
     *
     * @see PackageLoader#loadPackagesWithInstallTask(IAutoInstallTask)
     */
    public interface IAutoInstallTask {
        /**
         * Invoked by the loader once a source has been loaded and its packages
         * definitions are known. The method should return the {@code packages}
         * array and can modify it if necessary.
         * The loader will call {@link #acceptPackage(Package)} on all the packages returned.
         *
         * @param source The source of the packages. Null for the locally installed packages.
         * @param packages The packages found in the source.
         */
        public Package[] filterLoadedSource(SdkSource source, Package[] packages);

        /**
         * Called by the install task for every package available (new ones, updates as well
         * as existing ones that don't have a potential update.)
         * The method should return true if this is the package that should be installed,
         * at which point the packager loader will stop processing the next packages and sources.
         * <p/>
         * <em>Important</em>: This method is called from a sub-thread, so clients who try
         * to access any UI widgets must wrap their calls into {@link Display#syncExec(Runnable)}
         * or {@link Display#asyncExec(Runnable)}.
         */
        public boolean acceptPackage(Package pkg);

        /**
         * Called when the accepted package has been installed, successfully or not.
         * If an already installed (aka existing) package has been accepted, this will
         * be called with a 'true' success and the actual install path.
         * <p/>
         * <em>Important</em>: This method is called from a sub-thread, so clients who try
         * to access any UI widgets must wrap their calls into {@link Display#syncExec(Runnable)}
         * or {@link Display#asyncExec(Runnable)}.
         */
        public void setResult(Package pkg, boolean success, File installPath);

        /**
         * Called when the task is done iterating and completed.
         */
        public void taskCompleted();
    }

    /**
     * Creates a new PackageManager associated with the given {@link UpdaterData}.
     *
     * @param updaterData The {@link UpdaterData}. Must not be null.
     */
    public PackageLoader(UpdaterData updaterData) {
        mUpdaterData = updaterData;
    }

    /**
     * Loads all packages from the remote repository.
     * This runs in an {@link ITask}. The call is blocking.
     * <p/>
     * The callback is called with each set of {@link PkgItem} found in each source.
     * The caller is responsible to accumulate the packages given to the callback
     * after each source is finished loaded. In return the callback tells the loader
     * whether to continue loading sources.
     */
    public void loadPackages(final ISourceLoadedCallback sourceLoadedCallback) {
        try {
            if (mUpdaterData == null) {
                return;
            }

            mUpdaterData.getTaskFactory().start("Loading Sources", new ITask() {
                public void run(ITaskMonitor monitor) {
                    monitor.setProgressMax(10);

                    // get local packages and offer them to the callback
                    Package[] localPkgs =
                        mUpdaterData.getInstalledPackages(monitor.createSubMonitor(1));
                    if (localPkgs == null) {
                        localPkgs = new Package[0];
                    }
                    if (!sourceLoadedCallback.onUpdateSource(null, localPkgs)) {
                        return;
                    }

                    // get remote packages
                    boolean forceHttp = mUpdaterData.getSettingsController().getForceHttp();
                    mUpdaterData.loadRemoteAddonsList(monitor.createSubMonitor(1));

                    SdkSource[] sources = mUpdaterData.getSources().getAllSources();
                    try {
                        if (sources != null && sources.length > 0) {
                            ITaskMonitor subMonitor = monitor.createSubMonitor(8);
                            subMonitor.setProgressMax(sources.length);
                            for (SdkSource source : sources) {
                                Package[] pkgs = source.getPackages();
                                if (pkgs == null) {
                                    source.load(subMonitor.createSubMonitor(1), forceHttp);
                                    pkgs = source.getPackages();
                                }
                                if (pkgs == null) {
                                    continue;
                                }

                                // Notify the callback a new source has finished loading.
                                // If the callback requests so, stop right away.
                                if (!sourceLoadedCallback.onUpdateSource(source, pkgs)) {
                                    return;
                                }
                            }
                        }
                    } catch(Exception e) {
                        monitor.logError("Loading source failed: %1$s", e.toString());
                    } finally {
                        monitor.setDescription("Done loading packages.");
                    }
                }
            });
        } finally {
            sourceLoadedCallback.onLoadCompleted();
        }
    }

    /**
     * Load packages, source by source using {@link #loadPackages(ISourceLoadedCallback)},
     * and executes the given {@link IAutoInstallTask} on the current package list.
     * That is for each package known, the install task is queried to find if
     * the package is the one to be installed or updated.
     * <p/>
     * - If an already installed package is accepted by the task, it is returned. <br/>
     * - If a new package (remotely available but not installed locally) is accepted,
     * the user will be <em>prompted</em> for permission to install it. <br/>
     * - If an existing package has updates, the install task will be accept if it
     * accepts one of the updating packages, and if yes the the user will be
     * <em>prompted</em> for permission to install it. <br/>
     * <p/>
     * Only one package can be accepted, after which the task is completed.
     * There is no direct return value, {@link IAutoInstallTask#setResult} is called on the
     * result of the accepted package.
     * When the task is completed, {@link IAutoInstallTask#taskCompleted()} is called.
     * <p/>
     * <em>Important</em>: Since some UI will be displayed to install the selected package,
     * the {@link UpdaterData} must have a window {@link Shell} associated using
     * {@link UpdaterData#setWindowShell(Shell)}.
     * <p/>
     * The call is blocking. Although the name says "Task", this is not an {@link ITask}
     * running in its own thread but merely a synchronous call.
     *
     * @param installTask The task to perform.
     */
    public void loadPackagesWithInstallTask(final IAutoInstallTask installTask) {

        loadPackages(new ISourceLoadedCallback() {
            public boolean onUpdateSource(SdkSource source, Package[] packages) {
                packages = installTask.filterLoadedSource(source, packages);
                if (packages == null || packages.length == 0) {
                    // Tell loadPackages() to process the next source.
                    return true;
                }

                for (Package pkg : packages) {
                    if (pkg.isLocal()) {
                        // This is a local (aka installed) package
                        if (installTask.acceptPackage(pkg)) {
                            // If the caller is accepting an installed package,
                            // return a success and give the package's install path
                            Archive[] a = pkg.getArchives();
                            // an installed package should have one local compatible archive
                            if (a.length == 1 && a[0].isCompatible()) {
                                installTask.setResult(
                                        pkg,
                                        true /*success*/,
                                        new File(a[0].getLocalOsPath()));
                            }
                            // return false to tell loadPackages() that we don't
                            // need to continue processing any more sources.
                            return false;
                        }

                    } else {
                        // This is a remote package
                        if (installTask.acceptPackage(pkg)) {
                            // The caller is accepting this remote package. Let's try to install it.

                            for (Archive archive : pkg.getArchives()) {
                                if (archive.isCompatible()) {
                                    installArchive(archive);
                                    break;
                                }
                            }
                            // return false to tell loadPackages() that we don't
                            // need to continue processing any more sources.
                            return false;
                        }
                    }
                }

                // Tell loadPackages() to process the next source.
                return true;
            }

            /**
             * Shows the UI of the install selector.
             * If the package is then actually installed, refresh the local list and
             * notify the install task of the installation path.
             *
             * @param archiveToInstall The archive to install.
             */
            private void installArchive(Archive archiveToInstall) {
                // What we want to install
                final ArrayList<Archive> archivesToInstall = new ArrayList<Archive>();
                archivesToInstall.add(archiveToInstall);

                Package packageToInstall = archiveToInstall.getParentPackage();

                // What we'll end up installing
                final Archive[] installedArchive = new Archive[] { null };

                // Actually install the new archive that we just found.
                // This will display some UI so we need a shell's sync exec.

                mUpdaterData.getWindowShell().getDisplay().syncExec(new Runnable() {
                    public void run() {
                        List<Archive> archives =
                            mUpdaterData.updateOrInstallAll_WithGUI(
                                archivesToInstall,
                                true /* includeObsoletes */);

                        if (archives != null && !archives.isEmpty()) {
                            // We expect that at most one archive has been installed.
                            assert archives.size() == 1;
                            installedArchive[0] = archives.get(0);
                        }
                    }
                });

                // If the desired package has been installed...
                if (installedArchive[0] == archiveToInstall) {

                    // The local package list has changed, make sure to refresh it
                    mUpdaterData.getLocalSdkParser().clearPackages();
                    final Package[] localPkgs = mUpdaterData.getInstalledPackages(
                            new NullTaskMonitor(mUpdaterData.getSdkLog()));

                    // Try to locate the installed package in the new package list
                    for (Package localPkg : localPkgs) {
                        if (localPkg.canBeUpdatedBy(packageToInstall) == UpdateInfo.NOT_UPDATE) {
                            Archive[] localArchive = localPkg.getArchives();
                            if (localArchive.length == 1 && localArchive[0].isCompatible()) {
                                installTask.setResult(
                                        localPkg,
                                        true /*success*/,
                                        new File(localArchive[0].getLocalOsPath()));
                                return;
                            }
                        }
                    }
                }

                // We failed to install the package.
                installTask.setResult(packageToInstall, false /*success*/, null);
            }

            public void onLoadCompleted() {
                installTask.taskCompleted();
            }
        });
    }
}
