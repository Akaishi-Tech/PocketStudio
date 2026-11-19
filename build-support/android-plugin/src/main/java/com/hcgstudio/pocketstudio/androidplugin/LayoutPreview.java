package com.hcgstudio.pocketstudio.androidplugin;

import com.hcgstudio.pocketstudio.plugin.AndroidEnvironment.LayoutFiles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;

/** Builds a static preview from a fixed set of Android widgets. */
final class LayoutPreview {
  private static final String ANDROID = "http://schemas.android.com/apk/res/android";
  private static final String TOOLS = "http://schemas.android.com/tools";
  private static final int FILL = ViewGroup.LayoutParams.MATCH_PARENT;
  private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
  private final Context context;
  private final Context plugin;
  private final Map<String, String> values = new HashMap<>();
  private final Map<String, Bitmap> images = new HashMap<>();
  private final Set<String> notes = new LinkedHashSet<>();

  private LayoutPreview(Context context, Context plugin) {
    this.context = context;
    this.plugin = plugin;
  }

  private Element root;

  static LayoutPreview load(Context context, Context plugin, LayoutFiles project, String xml, String layoutPath) throws Exception {
    LayoutPreview preview = new LayoutPreview(context, plugin);
    preview.root = preview.parse(xml);
    int index = layoutPath.lastIndexOf("/res/");
    String res = index < 0 ? "res" : layoutPath.substring(0, index + 4);
    preview.loadValues(project, res);
    preview.loadImages(project, res, preview.root);
    return preview;
  }

  // Only the inspected application's XML content uses Android Views inside the Compose dialog.
  void drawInto(FrameLayout viewport) {
    viewport.setBackgroundColor(Color.WHITE);
    viewport.addView(render(root), params(viewport, root));
  }

  List<String> differences() {
    return new ArrayList<>(notes);
  }

  private static final class Element {
    final String tag;
    final Map<String, String> attrs = new LinkedHashMap<>();
    final List<Element> children = new ArrayList<>();

    Element(String tag) {
      this.tag = tag;
    }
  }

  private XmlPullParser parser(String xml) throws Exception {
    if (xml.length() > 768 * 1024) throw new IOException(plugin.getString(R.string.xml_exceeds_768_kib));
    if (xml.contains("<!DOCTYPE")) throw new IOException(plugin.getString(R.string.layout_resources_cannot_contain_a_dtd));
    XmlPullParser p = Xml.newPullParser();
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
    p.setInput(new StringReader(xml));
    return p;
  }

  private Element parse(String xml) throws Exception {
    XmlPullParser p = parser(xml);
    ArrayDeque<Element> stack = new ArrayDeque<>();
    Element root = null;
    int count = 0;
    for (int event = p.next(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
      if (event == XmlPullParser.END_TAG) stack.pop();
      if (event != XmlPullParser.START_TAG) continue;
      if (++count > 400 || stack.size() >= 32 || p.getAttributeCount() > 100)
        throw new IOException(plugin.getString(R.string.layout_exceeds_the_preview_limit_of_400_views));
      Element node = new Element(p.getName());
      for (int i = 0; i < p.getAttributeCount(); i++) {
        String ns = p.getAttributeNamespace(i), key = p.getAttributeName(i);
        if (ANDROID.equals(ns)) node.attrs.put(key, p.getAttributeValue(i));
        else if (TOOLS.equals(ns)) {
          if (key.equals("text") || key.equals("src"))
            node.attrs.put("tools:" + key, p.getAttributeValue(i));
        } else if (!key.equals("class")) note(plugin.getString(R.string.attribute_format, key));
      }
      if (stack.isEmpty()) root = node;
      else stack.peek().children.add(node);
      stack.push(node);
    }
    if (root == null) throw new IOException(plugin.getString(R.string.layout_has_no_root_element));
    return root;
  }

  private void loadValues(LayoutFiles project, String res) throws Exception {
    String dir = res + "/values";
    List<String> files;
    try {
      files = project.list(dir);
    } catch (FileNotFoundException missing) {
      return;
    }
    for (String file : files) {
      if (!(file.equals("strings.xml")
          || file.equals("colors.xml")
          || file.equals("dimens.xml"))) continue;
      try {
        XmlPullParser p = parser(project.read(dir + "/" + file));
        String key = null;
        StringBuilder value = new StringBuilder();
        for (int event = p.next(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
          if (event == XmlPullParser.START_TAG && p.getDepth() == 2) {
            String type = p.getName(), name = p.getAttributeValue(null, "name");
            key =
                name != null
                        && (type.equals("string") || type.equals("color") || type.equals("dimen"))
                    ? "@" + type + "/" + name
                    : null;
            value.setLength(0);
          } else if (event == XmlPullParser.TEXT && key != null) value.append(p.getText());
          else if (event == XmlPullParser.END_TAG && p.getDepth() == 2 && key != null) {
            values.put(key, value.toString().trim());
            key = null;
          }
        }
      } catch (Exception e) {
        note(plugin.getString(R.string.failed_to_parse_format, file, e.getLocalizedMessage()));
      }
    }
  }

  private void loadImages(LayoutFiles project, String res, Element root) throws IOException {
    Set<String> refs = new LinkedHashSet<>();
    ArrayDeque<Element> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Element e = pending.remove();
      String src = e.attrs.getOrDefault("src", e.attrs.get("tools:src"));
      if (src != null && src.matches("@(drawable|mipmap)/[a-z0-9_]+")) refs.add(src);
      pending.addAll(e.children);
    }
    Map<String, List<String>> folders = new HashMap<>();
    for (String ref : refs) {
      if (Thread.currentThread().isInterrupted()) return;
      if (images.size() >= 8) {
        note(plugin.getString(R.string.image_previews_are_limited_to_8_images));
        break;
      }
      String[] parts = ref.substring(1).split("/");
      boolean found = false;
      for (String qualifier :
          new String[] {"", "-nodpi", "-mdpi", "-hdpi", "-xhdpi", "-xxhdpi", "-xxxhdpi"}) {
        String path = res + "/" + parts[0] + qualifier;
        if (!folders.containsKey(path)) {
          try {
            folders.put(path, project.list(path));
          } catch (FileNotFoundException e) {
            folders.put(path, Collections.emptyList());
          }
        }
        for (String image : folders.get(path)) {
          if (!image.matches(parts[1] + "\\.(png|webp|jpg|jpeg)")) continue;
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inJustDecodeBounds = true;
          try (InputStream in = project.open(path + "/" + image)) {
            BitmapFactory.decodeStream(in, null, options);
          }
          if (options.outWidth < 1 || options.outHeight < 1) continue;
          options.inSampleSize = 1;
          while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512)
            options.inSampleSize *= 2;
          options.inJustDecodeBounds = false;
          try (InputStream in = project.open(path + "/" + image)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap != null) {
              images.put(ref, bitmap);
              found = true;
            }
          }
          break;
        }
        if (found) break;
      }
      if (!found) note(plugin.getString(R.string.unresolved_image_format, ref));
    }
  }

  private void note(String text) {
    if (notes.size() < 32) notes.add(text.length() > 160 ? text.substring(0, 158) + "…" : text);
    else notes.add(plugin.getString(R.string.some_content_could_not_be_previewed));
  }

  private String resolve(String raw) {
    String value = raw;
    Set<String> visited = new HashSet<>();
    while (value != null && value.startsWith("@") && values.containsKey(value)) {
      if (!visited.add(value)) {
        note(plugin.getString(R.string.circular_reference_format, raw));
        return raw;
      }
      value = values.get(value);
    }
    return value;
  }

  private int framework(String ref, String type) {
    if (ref == null || !ref.startsWith("@android:" + type + "/")) return 0;
    return context
        .getResources()
        .getIdentifier(ref.substring(ref.indexOf('/') + 1), type, "android");
  }

  private String text(String raw) {
    String value = resolve(raw);
    if (value == null) return "";
    int id = framework(value, "string");
    if (id != 0) return context.getString(id);
    if (value.startsWith("@") || value.startsWith("?")) note(plugin.getString(R.string.unresolved_text_format, value));
    return value
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\'", "'")
        .replace("\\\"", "\"");
  }

  private Integer color(String raw) {
    String value = resolve(raw);
    if (value == null) return null;
    int id = framework(value, "color");
    if (id != 0) return context.getColor(id);
    try {
      if (value.matches("#[0-9a-fA-F]{3,4}")) {
        StringBuilder expanded = new StringBuilder("#");
        for (int i = 1; i < value.length(); i++)
          expanded.append(value.charAt(i)).append(value.charAt(i));
        value = expanded.toString();
      }
      return Color.parseColor(value);
    } catch (IllegalArgumentException e) {
      note(plugin.getString(R.string.unresolved_color_format, value));
      return null;
    }
  }

  private int dimension(String raw, int fallback) {
    if (raw == null) return fallback;
    String value = resolve(raw).trim();
    if (value.equals("match_parent") || value.equals("fill_parent")) return FILL;
    if (value.equals("wrap_content")) return WRAP;
    try {
      int unit = TypedValue.COMPLEX_UNIT_PX;
      if (!value.equals("0")) {
        if (value.endsWith("dip")) value = value.substring(0, value.length() - 3) + "dp";
        String suffix = value.substring(value.length() - 2);
        switch (suffix) {
          case "dp":
            unit = TypedValue.COMPLEX_UNIT_DIP;
            break;
          case "sp":
            unit = TypedValue.COMPLEX_UNIT_SP;
            break;
          case "px":
            break;
          default:
            throw new NumberFormatException();
        }
        value = value.substring(0, value.length() - 2);
      }
      float n = Float.parseFloat(value);
      if (!Float.isFinite(n) || Math.abs(n) > 16384) throw new NumberFormatException();
      return Math.round(
          TypedValue.applyDimension(unit, n, context.getResources().getDisplayMetrics()));
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      note(plugin.getString(R.string.unresolved_dimension_format, raw));
      return fallback;
    }
  }

  private int gravity(String raw, int fallback) {
    if (raw == null) return fallback;
    int result = 0;
    for (String part : raw.split("\\|")) {
      switch (part.trim()) {
        case "start":
          result |= Gravity.START;
          break;
        case "end":
          result |= Gravity.END;
          break;
        case "left":
          result |= Gravity.LEFT;
          break;
        case "right":
          result |= Gravity.RIGHT;
          break;
        case "top":
          result |= Gravity.TOP;
          break;
        case "bottom":
          result |= Gravity.BOTTOM;
          break;
        case "center":
          result |= Gravity.CENTER;
          break;
        case "center_vertical":
          result |= Gravity.CENTER_VERTICAL;
          break;
        case "center_horizontal":
          result |= Gravity.CENTER_HORIZONTAL;
          break;
        case "fill":
          result |= Gravity.FILL;
          break;
        case "fill_vertical":
          result |= Gravity.FILL_VERTICAL;
          break;
        case "fill_horizontal":
          result |= Gravity.FILL_HORIZONTAL;
          break;
        default:
          note(plugin.getString(R.string.unresolved_alignment_format, part));
      }
    }
    return result == 0 ? fallback : result;
  }

  private ViewGroup.MarginLayoutParams params(ViewGroup parent, Element node) {
    Map<String, String> a = node.attrs;
    int w = dimension(a.get("layout_width"), WRAP), h = dimension(a.get("layout_height"), WRAP);
    int g = gravity(a.get("layout_gravity"), -1);
    ViewGroup.MarginLayoutParams lp;
    if (parent instanceof LinearLayout) {
      LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(w, h);
      row.gravity = g;
      if (a.containsKey("layout_weight")) {
        try {
          float weight = Float.parseFloat(a.get("layout_weight"));
          if (weight < 0 || !Float.isFinite(weight)) throw new NumberFormatException();
          row.weight = weight;
        } catch (NumberFormatException e) {
          note(plugin.getString(R.string.invalid_layout_weight));
        }
      }
      lp = row;
    } else lp = new FrameLayout.LayoutParams(w, h, g);
    int all = dimension(a.get("layout_margin"), 0);
    int mx = dimension(a.get("layout_marginHorizontal"), all),
        my = dimension(a.get("layout_marginVertical"), all);
    lp.setMargins(
        dimension(a.get("layout_marginLeft"), mx),
        dimension(a.get("layout_marginTop"), my),
        dimension(a.get("layout_marginRight"), mx),
        dimension(a.get("layout_marginBottom"), my));
    if (a.containsKey("layout_marginStart"))
      lp.setMarginStart(dimension(a.get("layout_marginStart"), mx));
    if (a.containsKey("layout_marginEnd"))
      lp.setMarginEnd(dimension(a.get("layout_marginEnd"), mx));
    return lp;
  }

  private View render(Element node) {
    String tag = node.tag;
    if (tag.startsWith("android.widget.")) tag = tag.substring("android.widget.".length());
    if (tag.equals("android.view.View")) tag = "View";
    View v;
    switch (tag) {
      case "LinearLayout":
        v = new LinearLayout(context);
        break;
      case "FrameLayout":
        v = new FrameLayout(context);
        break;
      case "ScrollView":
        v = new ScrollView(context);
        break;
      case "HorizontalScrollView":
        v = new HorizontalScrollView(context);
        break;
      case "TextView":
        v = new TextView(context);
        break;
      case "EditText":
        v = new EditText(context);
        break;
      case "Button":
        v = new Button(context);
        break;
      case "CheckBox":
        v = new CheckBox(context);
        break;
      case "Switch":
        v = new Switch(context);
        break;
      case "RadioButton":
        v = new RadioButton(context);
        break;
      case "ImageView":
        v = new ImageView(context);
        break;
      case "ProgressBar":
        v = new ProgressBar(context);
        break;
      case "Space":
        v = new Space(context);
        break;
      case "View":
        v = new View(context);
        break;
      default:
        note(tag + (node.children.isEmpty() ? plugin.getString(R.string.shown_as_a_placeholder) : plugin.getString(R.string.children_are_arranged_vertically)));
        if (node.children.isEmpty()) {
          TextView placeholder = new TextView(context);
          placeholder.setText(tag.substring(tag.lastIndexOf('.') + 1));
          placeholder.setBackgroundColor(0xffedecea);
          v = placeholder;
        } else {
          LinearLayout fallback = new LinearLayout(context);
          fallback.setOrientation(LinearLayout.VERTICAL);
          v = fallback;
        }
    }
    apply(v, node.attrs);
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (Element child : node.children) {
        if ((group instanceof ScrollView || group instanceof HorizontalScrollView)
            && group.getChildCount() == 1) {
          note(plugin.getString(R.string.accepts_only_one_direct_child_format, tag));
          break;
        }
        group.addView(render(child), params(group, child));
      }
    } else if (!node.children.isEmpty()) note(plugin.getString(R.string.children_could_not_be_displayed_format, tag));
    v.setFocusable(false);
    v.setClickable(false);
    return v;
  }

  private static final Set<String> ATTRIBUTES =
      new HashSet<>(
          Arrays.asList(
              "id",
              "orientation",
              "gravity",
              "background",
              "text",
              "tools:text",
              "hint",
              "textColor",
              "textSize",
              "textStyle",
              "src",
              "tools:src",
              "scaleType",
              "contentDescription",
              "visibility",
              "checked",
              "enabled",
              "singleLine",
              "layout_width",
              "layout_height",
              "layout_weight",
              "layout_gravity",
              "fillViewport",
              "padding",
              "paddingStart",
              "paddingEnd",
              "paddingLeft",
              "paddingTop",
              "paddingRight",
              "paddingBottom",
              "paddingHorizontal",
              "paddingVertical",
              "layout_margin",
              "layout_marginStart",
              "layout_marginEnd",
              "layout_marginLeft",
              "layout_marginTop",
              "layout_marginRight",
              "layout_marginBottom",
              "layout_marginHorizontal",
              "layout_marginVertical"));

  private void apply(View v, Map<String, String> a) {
    for (String key : a.keySet()) if (!ATTRIBUTES.contains(key)) note(plugin.getString(R.string.attribute_android_format, key));
    if (v instanceof LinearLayout) {
      LinearLayout l = (LinearLayout) v;
      if (a.containsKey("orientation"))
        l.setOrientation(
            "vertical".equals(a.get("orientation"))
                ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);
      if (a.containsKey("gravity")) l.setGravity(gravity(a.get("gravity"), Gravity.START));
    }
    if (v instanceof ScrollView)
      ((ScrollView) v).setFillViewport("true".equals(a.get("fillViewport")));
    if (v instanceof HorizontalScrollView)
      ((HorizontalScrollView) v).setFillViewport("true".equals(a.get("fillViewport")));
    if (v instanceof TextView) {
      TextView t = (TextView) v;
      String value = a.getOrDefault("text", a.get("tools:text"));
      if (value != null) t.setText(text(value));
      if (a.containsKey("hint")) t.setHint(text(a.get("hint")));
      Integer ink = color(a.get("textColor"));
      if (ink != null) t.setTextColor(ink);
      if (a.containsKey("textSize"))
        t.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            Math.max(1, dimension(a.get("textSize"), Math.round(t.getTextSize()))));
      if (a.containsKey("textStyle")) {
        String style = a.get("textStyle");
        boolean bold = style.contains("bold");
        boolean italic = style.contains("italic");
        t.setTypeface(
            t.getTypeface(),
            bold && italic
                ? Typeface.BOLD_ITALIC
                : bold ? Typeface.BOLD : italic ? Typeface.ITALIC : Typeface.NORMAL);
      }
      if (a.containsKey("singleLine")) t.setSingleLine("true".equals(a.get("singleLine")));
      if (a.containsKey("gravity")) t.setGravity(gravity(a.get("gravity"), t.getGravity()));
    }
    if (v instanceof CompoundButton)
      ((CompoundButton) v).setChecked("true".equals(a.get("checked")));
    if (v instanceof ImageView) {
      ImageView image = (ImageView) v;
      String source = a.getOrDefault("src", a.get("tools:src"));
      if (source != null) {
        int id = framework(source, "drawable");
        if (id != 0) image.setImageResource(id);
        else if (images.get(source) != null) image.setImageBitmap(images.get(source));
        else note(plugin.getString(R.string.unresolved_image_format, source));
      }
      String scale = a.get("scaleType");
      if (scale != null) {
        try {
          image.setScaleType(
              ImageView.ScaleType.valueOf(
                  scale.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
          note(plugin.getString(R.string.unresolved_scaletype_format, scale));
        }
      }
    }
    Integer bg = color(a.get("background"));
    if (bg != null) v.setBackgroundColor(bg);
    int p = dimension(a.get("padding"), -1);
    int px = dimension(a.get("paddingHorizontal"), p), py = dimension(a.get("paddingVertical"), p);
    int left = dimension(a.get("paddingLeft"), px < 0 ? v.getPaddingLeft() : px);
    int right = dimension(a.get("paddingRight"), px < 0 ? v.getPaddingRight() : px);
    int top = dimension(a.get("paddingTop"), py < 0 ? v.getPaddingTop() : py);
    int bottom = dimension(a.get("paddingBottom"), py < 0 ? v.getPaddingBottom() : py);
    v.setPadding(left, top, right, bottom);
    if (a.containsKey("paddingStart") || a.containsKey("paddingEnd"))
      v.setPaddingRelative(
          dimension(a.get("paddingStart"), v.getPaddingStart()),
          top,
          dimension(a.get("paddingEnd"), v.getPaddingEnd()),
          bottom);
    if (a.containsKey("contentDescription"))
      v.setContentDescription(text(a.get("contentDescription")));
    if (a.containsKey("enabled")) v.setEnabled(!"false".equals(a.get("enabled")));
    String visibility = a.get("visibility");
    if ("gone".equals(visibility)) v.setVisibility(View.GONE);
    else if ("invisible".equals(visibility)) v.setVisibility(View.INVISIBLE);
  }
}
