package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;

/** Selects a filesystem working directory for a build opened by file URI or SAF. */
final class GradleWorkspace implements AutoCloseable {
    final Path directory;
    final Path projectCache;
    private final ProjectWorkspace workspace;

    private GradleWorkspace(ProjectWorkspace workspace) {
        this.workspace = workspace;
        directory = workspace.directory;
        projectCache = workspace.gradleCache;
    }

    static GradleWorkspace open(Project project, File sdkRoot, Consumer<String> log) throws IOException {
        ProjectWorkspace workspace = ProjectWorkspace.open(project);
        try {
            log.accept((workspace.direct ? uiString(R.string.running_in_selected_directory) : uiString(R.string.writing_back_to_selected_directory_through_document_provider)) + project.root.name);
            checkSdkSetting(workspace.directory, sdkRoot, log);
            return new GradleWorkspace(workspace);
        } catch (RuntimeException e) {
            try { workspace.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static void checkSdkSetting(Path root, File sdkRoot, Consumer<String> log) {
        Path settingsFile = root.resolve("local.properties");
        if (!Files.exists(settingsFile)) return;
        Properties settings = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            settings.load(input);
            String sdk = settings.getProperty("sdk.dir");
            if (sdk == null) return;
            Path directory = root.resolve(sdk);
            if (sdk.isEmpty() || !Files.isDirectory(directory) || !Files.isReadable(directory)) {
                log.accept(uiString(R.string.invalid_sdk_dir_replacement, sdkRoot));
            }
        } catch (IOException | IllegalArgumentException e) {
            log.accept(uiString(R.string.unable_to_read_sdk_settings_from_local_properties_format, e.getMessage()));
        }
    }

    @Override public void close() throws IOException {
        workspace.close();
    }
}
