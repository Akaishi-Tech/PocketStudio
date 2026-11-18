package com.hcgstudio.pocketstudio;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;

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

  static Snapshot capture(Target target) throws Exception {
    String hierarchy;
    try {
      String path = "/data/local/tmp/studio-inspector-" + UUID.randomUUID() + ".xml";
      // The remote shell transport is not guaranteed to have a controlling terminal.
      hierarchy = target.shell("umask 077; trap 'rm -f " + path
          + "' EXIT; uiautomator dump " + path + " >/dev/null && cat " + path);
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
    int start = hierarchy.indexOf("<hierarchy"), end = hierarchy.indexOf("</hierarchy>");
    if (start < 0 || end < start) {
      snapshot.hierarchyError = uiString(R.string.the_target_did_not_return_a_view_hierarchy);
      return snapshot;
    }
    try {
      XmlPullParser parser = Xml.newPullParser();
      parser.setInput(new StringReader(hierarchy.substring(start, end + "</hierarchy>".length())));
      while (parser.next() != XmlPullParser.END_DOCUMENT && snapshot.nodes.size() < 6000) {
        if (parser.getEventType() != XmlPullParser.START_TAG || !"node".equals(parser.getName()))
          continue;
        String bounds = parser.getAttributeValue(null, "bounds");
        if (bounds == null) continue;
        Matcher m = BOUNDS.matcher(bounds);
        if (!m.matches()) continue;
        Rect rect =
            new Rect(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)));
        if (rect.isEmpty()) continue;
        List<String> details = new ArrayList<>();
        for (String a : new String[] {"class", "resource-id", "text", "content-desc"}) {
          String value = parser.getAttributeValue(null, a);
          if (value != null && !value.isEmpty() && !details.contains(value)) details.add(value);
        }
        details.add(bounds);
        snapshot.nodes.add(new Node(rect, String.join("\n", details)));
      }
    } catch (Exception e) {
      snapshot.hierarchyError = uiString(R.string.unable_to_parse_the_view_hierarchy);
    }
    return snapshot;
  }

}
