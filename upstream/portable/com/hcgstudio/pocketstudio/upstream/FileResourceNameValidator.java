/*
 * Copyright (C) 2018 The Android Open Source Project
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

// Android port of sdk-common's FileResourceNameValidator. See upstream/README.md.
package com.hcgstudio.pocketstudio.upstream;

import static com.hcgstudio.pocketstudio.StudioStrings.uiString;
import com.hcgstudio.pocketstudio.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FileResourceNameValidator {
    private static final Set<String> FOLDERS = words("anim animator color drawable font interpolator layout menu mipmap navigation raw transition values xml");
    private static final Set<String> IMAGES = words("png gif jpeg jpg bmp webp avif xml");
    private static final Set<String> KEYWORDS = words("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null _");

    private FileResourceNameValidator() {}

    /** Returns a validation message for files immediately inside res/type[-qualifiers], or null. */
    public static String errorForFile(String parentPath, String fileName) {
        String[] path = parentPath.split("/");
        if (path.length < 2 || !path[path.length - 2].equals("res")) return null;
        String type = path[path.length - 1].split("-", 2)[0];
        if (!FOLDERS.contains(type)) return null;
        if (fileName.trim().isEmpty()) return uiString(R.string.enter_a_resource_file_name);
        int dot = fileName.lastIndexOf('.');
        String suffix = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String name = dot >= 0 ? fileName.substring(0, dot) : fileName;
        switch (type) {
            case "raw": break;
            case "drawable":
            case "mipmap":
                if (!IMAGES.contains(suffix)) return uiString(R.string.this_directory_requires_xml_or_image_files);
                if (fileName.toLowerCase(Locale.ROOT).endsWith(".9.png")) name = fileName.substring(0, fileName.length() - 6);
                break;
            case "font":
                if (!Arrays.asList("xml", "ttf", "ttc", "otf").contains(suffix)) return uiString(R.string.font_resources_require_an_xml_ttf_ttc_or);
                break;
            case "xml":
                if (!(suffix.equals("xml") || suffix.equals("xsd"))) return uiString(R.string.xml_resources_require_an_xml_or_xsd_extension);
                break;
            default:
                if (!suffix.equals("xml")) return uiString(R.string.this_directory_requires_xml_files);
        }
        if (type.equals("values")) return null;
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.codePointAt(0))) return uiString(R.string.start_resource_names_with_a_lowercase_letter_or);
        for (int c : name.codePoints().toArray()) {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return uiString(R.string.use_only_lowercase_letters_digits_and_underscores_in);
        }
        if (KEYWORDS.contains(name)) return uiString(R.string.the_resource_name_matches_a_java_keyword);
        return null;
    }

    private static Set<String> words(String text) {
        return new HashSet<>(Arrays.asList(text.split(" ")));
    }
}
