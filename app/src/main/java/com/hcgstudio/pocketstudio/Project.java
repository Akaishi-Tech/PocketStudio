package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.content.Context;
import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment;
import com.hcgstudio.pocketstudio.plugin.ProjectStarter;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Project {
  static final class UnsupportedText extends IOException {
    UnsupportedText(String message) {
      super(message);
    }
  }

  static final int MAX_TEXT = 768 * 1024;
  final Context context;
  final Uri tree;
  final LinuxProject linux;
  final Node root;

  static final class Node {
    final Uri uri;
    final String name, path;
    final boolean folder;

    Node(Uri uri, String name, String path, boolean folder) {
      this.uri = uri;
      this.name = name;
      this.path = path;
      this.folder = folder;
    }
  }

  Project(Context context, Uri tree) throws IOException {
    this.context = context;
    if (tree == null) throw new IOException(uiString(R.string.select_a_project_folder));
    linux = LinuxProjects.SCHEME.equals(tree.getScheme()) ? LinuxProjects.open(context, tree) : null;
    if (linux != null) {
      this.tree = linux.getLocation();
      root = linux.node("", true);
    } else if ("file".equals(tree.getScheme())) {
      this.tree = null;
      File home = new File(Objects.requireNonNull(tree.getPath())).getCanonicalFile();
      Path imports = new File(context.getFilesDir(), "imports").getCanonicalFile().toPath();
      if (!home.toPath().startsWith(imports) || home.toPath().equals(imports) || !home.isDirectory())
        throw new IOException(uiString(R.string.the_imported_project_is_no_longer_available));
      root = new Node(Uri.fromFile(home), home.getName(), "", true);
    } else {
      this.tree = tree;
      Uri doc = DocumentsContract.isDocumentUri(context, tree) ? tree
          : DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
      root = new Node(doc, documentName(doc), "", true);
    }
  }

  private String documentName(Uri uri) throws IOException {
    try (Cursor c = context.getContentResolver()
        .query(uri, new String[] {Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
      if (c == null || !c.moveToFirst()) throw new IOException(uiString(R.string.unable_to_read_the_document_name_select_the));
      return c.getString(0);
    }
  }

  static String projectNameError(String name) {
    if (name.trim().isEmpty()) return uiString(R.string.enter_a_project_name);
    if (name.startsWith(".") || name.endsWith(".") || name.matches("(?s).*[\\\\/:*?\"<>|].*")
        || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xfffe || c == 0xffff
            || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE)))
      return uiString(R.string.the_project_name_cannot_start_or_end_with);
    if (name.getBytes(StandardCharsets.UTF_8).length > 255) return uiString(R.string.project_name_is_too_long);
    return null;
  }

  static Project createProject(Context context, Uri parentTree, String name, ProjectStarter starter) throws IOException {
    String problem = projectNameError(name);
    if (problem != null) throw new IOException(problem);
    Project parent = new Project(context, parentTree);
    Node child = parent.create(parent.root, name, true);
    try {
      Project project = new Project(context, child.uri);
      if (!name.equals(project.root.name)) throw new IOException(uiString(R.string.a_project_with_that_name_already_exists_or));
      Map<String, Node> directories = new HashMap<>();
      starter.writeFiles((path, content) -> {
        String[] parts = path.split("/", -1);
        Node dir = project.root;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
          prefix.append(parts[i]).append('/');
          String key = prefix.toString();
          Node next = directories.get(key);
          if (next == null) {
            next = project.createExact(dir, parts[i], true);
            directories.put(key, next);
          }
          dir = next;
        }
        project.write(project.createExact(dir, parts[parts.length - 1], false), content);
      });
      return project;
    } catch (IOException | RuntimeException e) {
      try { parent.delete(child); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }

  List<Node> list(Node dir) throws IOException {
    List<Node> nodes = new ArrayList<>();
    String prefix = dir.path.isEmpty() ? "" : dir.path + "/";
    if (linux != null) nodes.addAll(linux.list(dir.path));
    else if (tree == null) {
      File[] files = new File(dir.uri.getPath()).listFiles();
      if (files == null) throw new IOException(uiString(R.string.unable_to_read_directory_format, dir.name));
      for (File f : files)
        nodes.add(new Node(Uri.fromFile(f), f.getName(), prefix + f.getName(), f.isDirectory()));
    } else {
      Uri children =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              tree, DocumentsContract.getDocumentId(dir.uri));
      String[] projection = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
      };
      try (Cursor c = context.getContentResolver().query(children, projection, null, null, null)) {
        if (c == null) throw new IOException(uiString(R.string.unable_to_read_directory_format, dir.name));
        while (c.moveToNext()) {
          String name = c.getString(1);
          nodes.add(
              new Node(
                  DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                  name,
                  prefix + name,
                  Document.MIME_TYPE_DIR.equals(c.getString(2))));
        }
      }
    }
    nodes.sort(
        Comparator.comparing((Node n) -> !n.folder)
            .thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
    return nodes;
  }

  Node create(Node parent, String name, boolean folder) throws IOException {
    return createDocument(parent, name, folder, validateDestination(parent, name, folder));
  }

  // A build may generate arbitrary file names, including Android intermediate resources.
  // Preserve the exact name or fail before the new document becomes part of the worktree.
  Node createExact(Node parent, String name, boolean folder) throws IOException {
    if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.indexOf(0) >= 0)
      throw new IOException(uiString(R.string.invalid_file_name));
    String path = parent.path.isEmpty() ? name : parent.path + "/" + name;
    Node created = createDocument(parent, name, folder, path);
    if (linux == null && tree != null && !name.equals(documentName(created.uri))) {
      delete(created);
      throw new IOException(uiString(R.string.the_storage_provider_could_not_preserve_the_name_format, path));
    }
    return created;
  }

  Node renameExact(Node node, String name) throws IOException {
    if (linux != null) {
      int slash = node.path.lastIndexOf('/');
      if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.indexOf(0) >= 0)
        throw new IOException(uiString(R.string.invalid_file_name));
      return relocate(node, node.path.substring(0, slash + 1) + name, false);
    }
    Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), node.uri, name);
    if (renamed == null) throw new IOException(uiString(R.string.the_storage_provider_did_not_finish_renaming_format, node.path));
    String actual = documentName(renamed);
    if (!name.equals(actual)) throw new IOException(uiString(R.string.the_storage_provider_renamed_to, node.path, actual));
    int slash = node.path.lastIndexOf('/');
    return new Node(renamed, name, node.path.substring(0, slash + 1) + name, node.folder);
  }

  private Node createDocument(Node parent, String name, boolean folder, String path) throws IOException {
    Uri uri;
    if (linux != null) {
      linux.file(folder ? "mkdir" : "create", path);
      return linux.node(path, folder);
    }
    if (tree == null) {
      File f = new File(parent.uri.getPath(), name);
      if (!(folder ? f.mkdir() : f.createNewFile())) throw new IOException(uiString(R.string.unable_to_create_format, name));
      uri = Uri.fromFile(f);
    } else {
      int dot = name.lastIndexOf('.');
      String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
      String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
      uri =
          DocumentsContract.createDocument(
              context.getContentResolver(),
              parent.uri,
              folder ? Document.MIME_TYPE_DIR : (mime == null ? "application/octet-stream" : mime),
              name);
      if (uri == null) throw new IOException(uiString(R.string.unable_to_create_format, name));
    }
    return new Node(uri, name, path, folder);
  }

  private String validateDestination(Node parent, String name, boolean folder) throws IOException {
    if (!parent.folder || name.trim().isEmpty() || name.equals(".") || name.equals("..")
        || name.contains("/") || name.indexOf(0) >= 0) throw new IOException(uiString(R.string.invalid_name_or_destination_directory));
    if (!folder && linux == null) {
      AndroidEnvironment android = AndroidEnvironments.find(context);
      String problem = android == null ? null : android.resourceFileNameError(parent.path, name);
      if (problem != null) throw new IOException(problem);
    }
    for (Node node : list(parent)) if (node.name.equals(name)) throw new IOException(uiString(R.string.name_already_exists, name));
    return parent.path.isEmpty() ? name : parent.path + "/" + name;
  }

  Node parent(Node child) throws IOException {
    if (child.path.isEmpty()) throw new IOException(uiString(R.string.select_a_file_or_folder_in_the_project));
    int slash = child.path.lastIndexOf('/');
    return slash < 0 ? root : resolve(child.path.substring(0, slash));
  }

  Node relocate(Node source, String path, boolean copy) throws IOException {
    Node from = parent(source);
    if (path.startsWith("/") || path.endsWith("/") || path.contains("//"))
      throw new IOException(uiString(R.string.enter_a_project_relative_path));
    int slash = path.lastIndexOf('/');
    Node dest = slash < 0 ? root : resolve(path.substring(0, slash));
    String name = path.substring(slash + 1);
    String target = validateDestination(dest, name, source.folder);
    if (source.folder && target.startsWith(source.path + "/")) throw new IOException(uiString(R.string.cannot_place_a_folder_inside_its_own_subfolder));
    if (linux != null) {
      linux.file(copy ? "copy" : "move", source.path, target);
      return linux.node(target, source.folder);
    }
    if (copy) return copyNode(source, dest, name);
    Uri moved;
    if (tree == null) {
      Path result = Files.move(new File(source.uri.getPath()).toPath(), new File(dest.uri.getPath(), name).toPath());
      moved = Uri.fromFile(result.toFile());
    } else if (from.path.equals(dest.path)) {
      moved = DocumentsContract.renameDocument(context.getContentResolver(), source.uri, name);
    } else {
      if (!source.name.equals(name)) throw new IOException(uiString(R.string.move_the_file_before_renaming_it));
      moved = DocumentsContract.moveDocument(context.getContentResolver(), source.uri, from.uri, dest.uri);
    }
    if (moved == null) throw new IOException(uiString(R.string.the_storage_provider_did_not_complete_the_operation));
    return new Node(moved, name, target, source.folder);
  }

  private Node copyNode(Node source, Node dest, String name) throws IOException {
    Node copy = create(dest, name, source.folder);
    try {
      if (source.folder) {
        for (Node child : list(source)) copyNode(child, copy, child.name);
      } else {
        try (InputStream input = input(source);
             OutputStream output = context.getContentResolver().openOutputStream(copy.uri, "wt")) {
          if (output == null) throw new IOException(uiString(R.string.unable_to_write_copy));
          byte[] buffer = new byte[32768];
          for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
        }
      }
      return copy;
    } catch (IOException | RuntimeException e) {
      try { delete(copy); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }

  void delete(Node node) throws IOException {
    if (node.path.isEmpty()) throw new IOException(uiString(R.string.select_a_file_or_folder_in_the_project));
    if (linux != null) linux.file("delete", node.path);
    else if (tree == null) removeTree(new File(node.uri.getPath()).toPath());
    else if (!DocumentsContract.deleteDocument(context.getContentResolver(), node.uri)) throw new IOException(uiString(R.string.delete_failed));
  }

  Node resolve(String path) throws IOException {
    if (linux != null && !path.isEmpty()) return linux.stat(path);
    Node dir = root;
    if (!path.isEmpty())
      for (String part : path.split("/")) {
        Node next = null;
        for (Node child : list(dir))
          if (child.name.equals(part)) {
            next = child;
            break;
          }
        if (next == null) throw new FileNotFoundException(path);
        dir = next;
      }
    return dir;
  }

  InputStream input(Node n) throws IOException {
    if (linux != null) return linux.input(n.path);
    InputStream in = context.getContentResolver().openInputStream(n.uri);
    if (in == null) throw new IOException(uiString(R.string.unable_to_read_format, n.name));
    return in;
  }

  String read(Node n) throws IOException {
    try (InputStream in = input(n);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192];
      int count;
      while ((count = in.read(bytes)) != -1) {
        if (count + out.size() > MAX_TEXT) throw new UnsupportedText(uiString(R.string.text_file_exceeds_768_kib));
        for (int i = 0; i < count; i++)
          if (bytes[i] == 0) throw new UnsupportedText(uiString(R.string.binary_files_cannot_be_opened_in_the_text));
        out.write(bytes, 0, count);
      }
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(out.toByteArray()))
          .toString();
    }
  }

  void write(Node n, byte[] data) throws IOException {
    if (linux != null) { linux.file("write", n.path, null, data); return; }
    if (tree == null) {
      File target = new File(n.uri.getPath());
      android.util.AtomicFile file = new android.util.AtomicFile(target);
      FileOutputStream out = file.startWrite();
      try {
        out.write(data);
        file.finishWrite(out);
      } catch (IOException e) {
        file.failWrite(out);
        throw e;
      }
    } else {
      try (OutputStream out = context.getContentResolver().openOutputStream(n.uri, "rwt")) {
        if (out == null) throw new IOException(uiString(R.string.unable_to_write_format, n.name));
        out.write(data);
      }
    }
  }

  static Project importZip(Context context, Uri archive, String name) throws IOException {
    File imports = new File(context.getFilesDir(), "imports");
    Files.createDirectories(imports.toPath());
    Path container = Files.createTempDirectory(imports.toPath(), "project-");
    boolean complete = false;
    try {
      String base = name.replaceFirst("(?i)\\.zip$", "").replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
      if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "Project";
      Path destination = Files.createDirectory(container.resolve(base));
      int entries = 0;
      long bytes = 0;
      try (InputStream raw = context.getContentResolver().openInputStream(archive)) {
        if (raw == null) throw new IOException(uiString(R.string.unable_to_read_zip));
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
          byte[] buffer = new byte[32 * 1024];
          for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (++entries > 100000) throw new IOException(uiString(R.string.zip_contains_too_many_files));
            String path = entry.getName();
            if (path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains(".."))
              throw new IOException(uiString(R.string.invalid_zip_path));
            Path target = destination.resolve(path).normalize();
            if (!target.startsWith(destination)) throw new IOException(uiString(R.string.invalid_zip_path));
            if (entry.isDirectory()) Files.createDirectories(target);
            else {
              Files.createDirectories(target.getParent());
              try (OutputStream out = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                for (int count; (count = zip.read(buffer)) != -1; ) {
                  if ((bytes += count) > 1024L * 1024 * 1024) throw new IOException(uiString(R.string.project_exceeds_1_gib_when_extracted));
                  out.write(buffer, 0, count);
                }
              }
            }
          }
        }
      }
      if (entries == 0 || bytes == 0) throw new IOException(uiString(R.string.the_zip_contains_no_project_files));
      File root = destination.toFile();
      File[] children = root.listFiles(f -> !f.getName().equals("__MACOSX") && !f.getName().equals(".DS_Store"));
      if (children != null && children.length == 1 && children[0].isDirectory()) root = children[0];
      Project result = new Project(context, Uri.fromFile(root));
      complete = true;
      return result;
    } finally {
      if (!complete) removeTree(container);
    }
  }

  static void removeTree(Path root) throws IOException {
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      for (Path item : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
        Files.deleteIfExists(item);
    }
  }

  List<Node> search(String query) throws IOException {
    List<Node> result = new ArrayList<>();
    ArrayDeque<Node> pending = new ArrayDeque<>();
    pending.add(root);
    String needle = query.toLowerCase(Locale.ROOT);
    while (!pending.isEmpty() && result.size() < 100) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      for (Node n : list(pending.remove())) {
        if (n.folder
            && !Arrays.asList(".git", ".gradle", ".idea", "build", "node_modules").contains(n.name))
          pending.add(n);
        if (!n.folder && n.path.toLowerCase(Locale.ROOT).contains(needle)) result.add(n);
        if (result.size() == 100) break;
      }
    }
    return result;
  }
}
