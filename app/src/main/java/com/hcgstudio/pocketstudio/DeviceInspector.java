package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

import android.graphics.*;
import android.util.Xml;
import com.hcgstudio.pocketstudio.adb.Target;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Inspects a user-requested snapshot of the connected display's accessibility hierarchy. */
final class DeviceInspector {
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
    try (Target.Stream stream = target.open("exec:screencap -p");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[65536];
      int count;
      while ((count = stream.input().read(buf)) != -1) {
        if (out.size() + count > 32 * 1024 * 1024) throw new IOException(uiString(R.string.screen_image_exceeds_32_mib));
        out.write(buf, 0, count);
      }
      bytes = out.toByteArray();
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || (long) options.outWidth * options.outHeight > 32000000)
      throw new IOException(uiString(R.string.unable_to_read_the_target_screen_image));
    int width = options.outWidth, height = options.outHeight;
    options.inSampleSize = 1;
    while ((long) width * height / options.inSampleSize / options.inSampleSize > 4000000L)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException(uiString(R.string.unable_to_parse_the_screenshot));
    Snapshot snapshot = new Snapshot(bitmap, width, height);
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

  static JSONObject hierarchyForAgent(String xml) throws Exception {
    JSONArray nodes = new JSONArray();
    int[] length = {0};
    boolean[] clippedText = {false};
    boolean complete = visitNodes(xml, (node, rect, bounds) -> {
      if (nodes.length() == 200) return false;
      JSONObject entry = new JSONObject();
      entry.put("depth", node.getDepth() - 2);
      entry.put("bounds", new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
      boolean password = Boolean.parseBoolean(node.getAttributeValue(null, "password"));
      for (String name : new String[] {"package", "class", "resource-id", "text", "content-desc"}) {
        if (password && (name.equals("text") || name.equals("content-desc"))) continue;
        String value = node.getAttributeValue(null, name);
        if (value == null || value.isEmpty()) continue;
        if (value.length() > 512) {
          int end = Character.isHighSurrogate(value.charAt(511)) ? 511 : 512;
          value = value.substring(0, end);
          clippedText[0] = true;
        }
        entry.put(name, value);
      }
      if (password) entry.put("password", true);
      entry.put("clickable", Boolean.parseBoolean(node.getAttributeValue(null, "clickable")));
      entry.put("enabled", Boolean.parseBoolean(node.getAttributeValue(null, "enabled")));
      int size = entry.toString().length() + 1;
      if (length[0] + size > 24000) return false;
      length[0] += size;
      nodes.put(entry);
      return true;
    });
    return new JSONObject().put("nodes", nodes).put("truncated", !complete || clippedText[0]);
  }

  private interface Visitor {
    boolean visit(XmlPullParser node, Rect rect, String bounds) throws Exception;
  }

  private static boolean visitNodes(String hierarchy, Visitor visitor) throws IOException {
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
        if (bounds == null) continue;
        Matcher m = BOUNDS.matcher(bounds);
        if (!m.matches()) continue;
        Rect rect = new Rect(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        if (!rect.isEmpty() && !visitor.visit(parser, rect, bounds)) return false;
      }
    } catch (Exception e) {
      throw new IOException(uiString(R.string.unable_to_parse_the_view_hierarchy), e);
    }
    throw new IOException(uiString(R.string.unable_to_parse_the_view_hierarchy));
  }
}
