package com.hcgstudio.pocketstudio.build;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the literal subset of the Android Gradle DSL supported by the embedded toolchain. */
final class QuickProject {
    final Map<String, String> values = new HashMap<>();
    private static final Pattern TOKEN = Pattern.compile(
            "/\\*.*?\\*/|//[^\\r\\n]*|\\s+|;|'[^'\\r\\n]*'|\"[^\"\\r\\n]*\"|[a-zA-Z_][a-zA-Z_0-9.]*|[0-9]+|.", Pattern.DOTALL);
    private final List<String> tokens = new ArrayList<>();
    private int at;

    QuickProject(File root) throws IOException {
        File module = new File(root, "app");
        read(script(root, "settings.gradle"));
        settings();
        File top = script(root, "build.gradle");
        if (top.isFile()) {
            read(top);
            if (take("plugins")) {
                want("{");
                while (!take("}")) { pluginId(); want("version"); string(); want("apply"); want("false"); }
            }
            end();
        }
        read(script(module, "build.gradle"));
        want("plugins"); want("{");
        boolean application = false;
        while (!take("}")) {
            if (pluginId().equals("com.android.application")) application = true;
            if (take("version")) string();
        }
        if (!application) throw unsupported();
        want("android"); want("{");
        while (!take("}")) {
            if (take("namespace")) put("namespace", stringValue());
            else if (take("compileSdk") || take("compileSdkVersion")) {
                if (take("{")) {
                    want("version"); want("="); want("release"); want("("); put("compileSdk", number()); want(")");
                    if (take("{")) {
                        want("minorApiLevel"); take("="); put("minorApiLevel", number()); want("}");
                    }
                    want("}");
                } else { take("="); put("compileSdk", number()); }
            } else if (take("defaultConfig")) defaults();
            else throw unsupported();
        }
        if (take("dependencies")) { want("{"); want("}"); }
        end();
        for (String key : Arrays.asList("namespace", "applicationId", "compileSdk", "minSdk", "targetSdk", "versionCode", "versionName")) {
            if (!values.containsKey(key)) throw new IOException(uiString(R.string.missing_build_with_gradle_or_provide_a_constant, key));
        }
        int compile = integer("compileSdk");
        int min = integer("minSdk");
        int target = integer("targetSdk");
        if (min < 24 || min > target || target > compile) throw new IOException(uiString(R.string.quick_builds_require_24_minsdk_targetsdk_compilesdk));
        if (integer("versionCode") < 1) throw new IOException(uiString(R.string.versioncode_must_be_a_positive_integer));
        for (String key : Arrays.asList("namespace", "applicationId")) {
            if (!values.get(key).matches("[A-Za-z]\\w*(\\.[A-Za-z]\\w*)+")) throw new IOException(uiString(R.string.invalid_value, key));
        }
        if (new File(root, "buildSrc").exists()) throw new IOException(uiString(R.string.this_project_uses_custom_gradle_build_logic_build));
        File src = new File(module, "src");
        for (File dir : contents(src)) {
            if (!Arrays.asList("main", "test", "androidTest").contains(dir.getName()))
                throw new IOException(uiString(R.string.additional_source_set_found_build_with_gradle, dir.getName()));
        }
        for (File dir : contents(new File(src, "main"))) {
            if (!Arrays.asList("AndroidManifest.xml", "res", "java", "kotlin", "assets").contains(dir.getName()))
                throw new IOException(uiString(R.string.quick_builds_do_not_support_src_main_build, dir.getName()));
        }
    }

    String platform() {
        String major = values.get("compileSdk");
        return values.containsKey("minorApiLevel") ? major + "." + values.get("minorApiLevel") : major;
    }

    private static File script(File directory, String name) {
        File groovy = new File(directory, name);
        File kotlin = new File(directory, name + ".kts");
        return !groovy.isFile() && kotlin.isFile() ? kotlin : groovy;
    }

    private static File[] contents(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) throw new IOException(uiString(R.string.unable_to_read_format, dir.getPath()));
        return children;
    }

    private void settings() throws IOException {
        boolean app = false;
        while (at < tokens.size()) {
            if (take("pluginManagement")) { want("{"); repositories(); want("}"); }
            else if (take("dependencyResolutionManagement")) {
                want("{");
                if (take("repositoriesMode.set")) {
                    want("(");
                    String mode = next();
                    if (!Arrays.asList("RepositoriesMode.FAIL_ON_PROJECT_REPOS", "RepositoriesMode.PREFER_SETTINGS",
                            "RepositoriesMode.PREFER_PROJECT").contains(mode)) throw unsupported();
                    want(")");
                }
                repositories(); want("}");
            } else if (take("rootProject.name")) { want("="); string(); }
            else if (take("include") && !app) { boolean paren = take("("); if (!":app".equals(string())) throw unsupported(); if (paren) want(")"); app = true; }
            else throw unsupported();
        }
        if (!app) throw unsupported();
    }

    private void repositories() throws IOException {
        want("repositories"); want("{");
        while (!take("}")) {
            if (!(take("google") || take("mavenCentral") || take("gradlePluginPortal"))) throw unsupported();
            want("("); want(")");
        }
    }

    private String pluginId() throws IOException {
        want("id");
        boolean paren = take("(");
        String id = string();
        if (!id.equals("com.android.application") && !id.equals("org.jetbrains.kotlin.android")) throw unsupported();
        if (paren) want(")");
        return id;
    }

    private void defaults() throws IOException {
        want("{");
        while (!take("}")) {
            String key = next();
            if (key.equals("applicationId") || key.equals("versionName")) put(key, stringValue());
            else if (key.equals("versionCode") || key.equals("minSdk") || key.equals("targetSdk")) {
                take("="); put(key, number());
            } else throw unsupported();
        }
    }

    private void read(File file) throws IOException {
        if (!file.isFile()) throw new IOException(uiString(R.string.missing_build_with_gradle, file.getName()));
        if (file.length() > 1048576) throw unsupported();
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher m = TOKEN.matcher(source);
        tokens.clear();
        at = 0;
        while (m.find()) {
            String t = m.group();
            if (!(t.trim().isEmpty() || t.equals(";") || t.startsWith("//") || t.startsWith("/*"))) tokens.add(t);
        }
    }

    private String next() throws IOException { if (at >= tokens.size()) throw unsupported(); return tokens.get(at++); }
    private boolean take(String expected) {
        if (at < tokens.size() && tokens.get(at).equals(expected)) { at++; return true; }
        return false;
    }
    private void want(String expected) throws IOException { if (!take(expected)) throw unsupported(); }
    private void end() throws IOException { if (at != tokens.size()) throw unsupported(); }
    private String stringValue() throws IOException { take("="); return string(); }
    private String string() throws IOException {
        String t = next();
        if (t.length() < 2 || (t.charAt(0) != '\'' && t.charAt(0) != '"') || t.indexOf('$') >= 0 || t.indexOf('\\') >= 0) throw unsupported();
        return t.substring(1, t.length() - 1);
    }
    private String number() throws IOException {
        String t = next();
        if (!t.matches("[0-9]+")) throw unsupported();
        return t;
    }
    private int integer(String key) throws IOException {
        try { return Integer.parseInt(values.get(key)); } catch (NumberFormatException e) { throw unsupported(); }
    }
    private void put(String key, String value) throws IOException { if (values.put(key, value) != null) throw unsupported(); }
    private IOException unsupported() {
        return new IOException(uiString(R.string.quick_builds_support_single_module_java_and_kotlin));
    }
}
