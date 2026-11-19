package com.hcgstudio.pocketstudio.avfplugin;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Stage the signed APK's disk and guest-side installer in the portion of Downloads shared by Terminal. */
final class AvfInstaller {
    static final String FOLDER = "staged-dir";
    static final String COMMAND = "verified-install-command";
    static final String RESTORE = "verified-restore-command";
    private static final String ROWS = "staged-downloads";
    private static final String REVISION = "staged-revision";

    static synchronized String stage(Context context) throws Exception {
        String digest;
        try (InputStream in = context.getAssets().open("debian.img.sha256")) {
            digest = new String(in.readNBytes(256), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
        }
        if (!digest.matches("[a-fA-F0-9]{64}")) throw new IOException("Invalid embedded image digest");
        byte[] key = AvfSsh.authorizedKey(context);
        SharedPreferences prefs = context.getSharedPreferences(AvfSsh.PREFS, 0);
        String revision = digest + ":" + BuildConfig.VERSION_CODE;
        String old = prefs.getString(FOLDER, "");
        Set<String> previous = prefs.getStringSet(ROWS, Set.of());
        ContentResolver resolver = context.getContentResolver();
        if (revision.equals(prefs.getString(REVISION, "")) && !old.isEmpty() && previous.size() == 4) {
            boolean allPresent = prefs.contains(COMMAND) && prefs.contains(RESTORE);
            for (String row : previous) {
                try (var fd = resolver.openFileDescriptor(Uri.parse(row), "r")) {
                    if (fd == null) allPresent = false;
                } catch (IOException e) { allPresent = false; }
            }
            if (allPresent) return old;
        }

        String folder = "PocketStudio-AVF-" + UUID.randomUUID().toString().substring(0, 12);
        String path = Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/";
        List<Uri> rows = new ArrayList<>();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = context.getAssets().open("debian-image.bin");
                 InputStream image = new DigestInputStream(raw, sha)) {
                write(resolver, path, "debian.img.gz", image, rows);
            }
            if (!java.util.HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(digest))
                throw new IOException("Embedded Debian image checksum mismatch");
            String installerHash;
            try (InputStream script = new DigestInputStream(context.getAssets().open("replace.sh"), sha)) {
                write(resolver, path, "replace.sh", script, rows);
                installerHash = java.util.HexFormat.of().formatHex(sha.digest());
            }
            try (InputStream manifest = context.getAssets().open("debian.img.sha256")) {
                write(resolver, path, "debian.img.sha256", manifest, rows);
            }
            write(resolver, path, "authorized_keys", new ByteArrayInputStream(key), rows);
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            Set<String> names = new LinkedHashSet<>();
            for (Uri uri : rows) {
                if (resolver.update(uri, ready, null, null) != 1) throw new IOException("Could not publish " + uri);
                names.add(uri.toString());
            }
            String keyHash = java.util.HexFormat.of().formatHex(sha.digest(key));
            String command = "(set -eu; d=/mnt/shared/Download/" + folder
                + "; [ -d \"$d\" ] || d=/mnt/shared/" + folder
                + "; t=$(mktemp -d); trap 'rm -rf -- \"$t\"' EXIT; cp -- \"$d/replace.sh\" \"$t/replace.sh\";"
                + " echo '" + installerHash + "  replace.sh' | (cd \"$t\" && sha256sum -c -);"
                + " sudo env STUDIO_SOURCE_DIR=\"$d\" STUDIO_IMAGE_SHA256=" + digest + " STUDIO_KEY_SHA256=" + keyHash
                + " bash \"$t/replace.sh\"";
            if (!prefs.edit().putString(COMMAND, command + ")").putString(RESTORE, command + " --restore)").putString(FOLDER, folder).putString(REVISION, revision).putStringSet(ROWS, names).commit())
                throw new IOException(context.getString(R.string.avf_save_failed));
        } catch (Exception e) {
            for (Uri uri : rows) try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
            throw e;
        }
        return folder;
    }

    static synchronized void clear(Context context) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences(AvfSsh.PREFS, 0);
        for (String row : prefs.getStringSet(ROWS, Set.of()))
            context.getContentResolver().delete(Uri.parse(row), null, null);
        if (!prefs.edit().remove(FOLDER).remove(COMMAND).remove(RESTORE).remove(REVISION).remove(ROWS).commit())
            throw new IOException(context.getString(R.string.avf_save_failed));
    }

    private static void write(ContentResolver resolver, String path, String name, InputStream in,
            List<Uri> rows) throws IOException {
        ContentValues entry = new ContentValues();
        entry.put(MediaStore.Downloads.DISPLAY_NAME, name);
        entry.put(MediaStore.Downloads.RELATIVE_PATH, path);
        entry.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        entry.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, entry);
        if (uri == null) throw new IOException("Cannot create " + path + name);
        rows.add(uri);
        // MediaStore may rewrite a display name for extension/MIME mismatches or collisions.
        // Both the guest installer and sha256 manifest require the original basename.
        try (var cursor = resolver.query(uri, new String[]{MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !name.equals(cursor.getString(0)))
                throw new IOException("Downloads provider could not preserve the filename " + name);
        }
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Cannot write " + path + name);
            byte[] block = new byte[256 * 1024];
            for (int n; (n = in.read(block)) != -1;) out.write(block, 0, n);
        }
    }
}
