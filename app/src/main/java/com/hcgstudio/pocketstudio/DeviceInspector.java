package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.graphics.*;
import android.util.Xml;
import com.hcgstudio.pocketstudio.adb.Target;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.xmlpull.v1.XmlPullParser;

/** Inspects a user-requested snapshot of the connected display's accessibility hierarchy. */
final class DeviceInspector {
  static final String SCREEN_COMMAND = "screencap -p";
  static final int MAX_SCREEN_BYTES = 32 * 1024 * 1024;
  // Zero-length IEND chunk, including its CRC. BitmapFactory accepts some partial PNG streams.
  private static final byte[] PNG_END = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82};
  static final class Snapshot {
    final Bitmap bitmap;
    final int width, height;
    final List<Node> nodes = new ArrayList<>();
    String hierarchyError = "";

    Snapshot(Bitmap bitmap, int width, int height) {
      this.bitmap = bitmap;
      this.width = width;
      this.height = height;
    }
  }

  static final class Node {
    final Rect area;
    final String details;

    Node(Rect area, String details) {
      this.area = area;
      this.details = details;
    }
  }

  private static final Pattern BOUNDS =
      Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

  static String hierarchyCommand() {
    String path = "/data/local/tmp/studio-inspector-" + UUID.randomUUID() + ".xml";
    // The remote shell transport is not guaranteed to have a controlling terminal.
    return "umask 077; trap 'rm -f " + path
        + "' EXIT; uiautomator dump " + path + " >/dev/null && cat " + path;
  }

  static Snapshot capture(Target target) throws Exception {
    String hierarchy;
    try {
      hierarchy = target.shell(hierarchyCommand());
    } catch (IOException e) {
      hierarchy = "";
    }
    byte[] bytes;
    try (Target.Stream stream = target.open("exec:" + SCREEN_COMMAND);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[65536];
      int count;
      while ((count = stream.input().read(buf)) != -1) {
        if (out.size() + count > MAX_SCREEN_BYTES) throw new IOException(uiString(R.string.screen_image_exceeds_32_mib));
        out.write(buf, 0, count);
      }
      bytes = out.toByteArray();
    }
    Snapshot snapshot = decodeScreen(bytes);
    try {
      visitNodes(hierarchy, (node, rect, bounds) -> {
        List<String> details = new ArrayList<>();
        for (String name : new String[] {"class", "resource-id", "text", "content-desc"}) {
          String value = node.getAttributeValue(null, name);
          if (value != null && !value.isEmpty() && !details.contains(value)) details.add(value);
        }
        details.add(bounds);
        snapshot.nodes.add(new Node(rect, String.join("\n", details)));
        return snapshot.nodes.size() < 6000;
      });
    } catch (IOException e) {
      snapshot.hierarchyError = e.getMessage();
    }
    return snapshot;
  }

  static Snapshot decodeScreen(byte[] bytes) throws IOException {
    if (!hasPngEnd(bytes)) throw new IOException(uiString(R.string.unable_to_read_the_target_screen_image));
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (!"image/png".equals(options.outMimeType) || options.outWidth <= 0 || options.outHeight <= 0
        || (long) options.outWidth * options.outHeight > 32000000)
      throw new IOException(uiString(R.string.unable_to_read_the_target_screen_image));
    int width = options.outWidth, height = options.outHeight;
    options.inSampleSize = 1;
    while ((long) width * height / options.inSampleSize / options.inSampleSize > 4000000L)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException(uiString(R.string.unable_to_parse_the_screenshot));
    return new Snapshot(bitmap, width, height);
  }

  private static boolean hasPngEnd(byte[] bytes) {
    int start = bytes.length - PNG_END.length;
    if (start < 0) return false;
    for (int i = 0; i < PNG_END.length; i++) {
      if (bytes[start + i] != PNG_END[i]) return false;
    }
    return true;
  }

  interface Visitor {
    boolean visit(XmlPullParser node, Rect rect, String bounds) throws Exception;
  }

  private static boolean visitNodes(String hierarchy, Visitor visitor) throws IOException {
    return visitHierarchy(hierarchy, (node, rect, bounds) -> rect == null || visitor.visit(node, rect, bounds));
  }

  // Invisible or malformed containers still delimit the ancestry of their descendants.
  static boolean visitHierarchy(String hierarchy, Visitor visitor) throws IOException {
    int start = hierarchy.indexOf("<hierarchy");
    if (start < 0) throw new IOException(uiString(R.string.the_target_did_not_return_a_view_hierarchy));
    try {
      XmlPullParser parser = Xml.newPullParser();
      parser.setInput(new StringReader(hierarchy.substring(start)));
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.getEventType() == XmlPullParser.END_TAG && parser.getDepth() == 1
            && "hierarchy".equals(parser.getName())) return true;
        if (parser.getEventType() != XmlPullParser.START_TAG || !"node".equals(parser.getName())) continue;
        String bounds = parser.getAttributeValue(null, "bounds");
        Rect rect = null;
        if (bounds != null) {
          Matcher m = BOUNDS.matcher(bounds);
          if (m.matches()) {
            try {
              Rect parsed = new Rect(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
              if (!parsed.isEmpty()) rect = parsed;
            } catch (NumberFormatException ignored) { /* A malformed container can still have visible children. */ }
          }
        }
        if (!visitor.visit(parser, rect, bounds)) return false;
      }
    } catch (Exception e) {
      throw new IOException(uiString(R.string.unable_to_parse_the_view_hierarchy), e);
    }
    throw new IOException(uiString(R.string.unable_to_parse_the_view_hierarchy));
  }
}
