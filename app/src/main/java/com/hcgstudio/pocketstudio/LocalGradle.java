package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment;
import com.hcgstudio.pocketstudio.plugin.GradleTask;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.function.Consumer;

/** Gives the Android plugin a synchronized project directory and the user's Gradle preferences. */
final class LocalGradle implements GradleTask {
    private final Project project;

    LocalGradle(Project project) { this.project = project; }

    private AndroidEnvironment.GradleRun runner;
    private boolean cancelled;

    @Override public synchronized void cancel() {
        cancelled = true;
        if (runner != null) runner.cancel();
    }

    @Override public List<File> run(String tasks, Consumer<String> log) throws Exception {
        synchronized (this) {
            if (cancelled) throw new InterruptedIOException(uiString(R.string.gradle_task_stopped));
            runner = AndroidEnvironments.require(project.context).newGradleRun();
        }
        StudioSettings settings = StudioSettings.load(project.context);
        try (ProjectWorkspace workspace = ProjectWorkspace.open(project)) {
            log.accept(uiString(workspace.direct ? R.string.running_in_selected_directory :
                    R.string.writing_back_to_selected_directory_through_document_provider) + project.root.name);
            return runner.run(workspace.directory, workspace.gradleCache, BuildSdk.selectedBuildSdk(project), tasks,
                    settings.getGradleWorkers(), settings.getGradleOffline(), settings.getGradleStacktrace(), log);
        } catch (Exception e) {
            for (Throwable cleanup : e.getSuppressed()) {
                log.accept(uiString(R.string.failed_to_write_back_to_project_format, cleanup.getLocalizedMessage()));
            }
            throw e;
        } finally {
            synchronized (this) { runner = null; }
        }
    }
}
