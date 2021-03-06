// Copyright (C) 2005 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.caja.lexer;

import java.util.HashMap;
import java.util.Map;

import com.google.caja.util.Name;

/**
 * From section 8.1.2.6 of http://www.whatwg.org/specs/web-apps/current-work/
 * <p>
 * The text in CDATA and RCDATA elements must not contain any
 * occurrences of the string "</" (U+003C LESS-THAN SIGN, U+002F
 * SOLIDUS) followed by characters that case-insensitively match the
 * tag name of the element followed by one of U+0009 CHARACTER
 * TABULATION, U+000A LINE FEED (LF), U+000B LINE TABULATION, U+000C
 * FORM FEED (FF), U+0020 SPACE, U+003E GREATER-THAN SIGN (>), or
 * U+002F SOLIDUS (/), unless that string is part of an escaping
 * text span.
 * </p>
 *
 * <p>
 * See also
 * http://www.whatwg.org/specs/web-apps/current-work/#cdata-rcdata-restrictions
 * for the elements which fall in each category.
 * </p>
 *
 * @author mikesamuel@gmail.com
 */
public enum HtmlTextEscapingMode {
  /**
   * Normally escaped character data that breaks around comments and tags.
   */
  PCDATA,
  /**
   * A span of text where HTML special characters are interpreted literally,
   * as in a SCRIPT tag.
   */
  CDATA,
  /**
   * A span of text and character entity references where HTML special
   * characters are interpreted literally, as in a TITLE tag.
   */
  RCDATA,
  /**
   * A span of text where HTML special characters are interpreted literally,
   * where there is no end tag.  PLAIN_TEXT runs until the end of the file.
   */
  PLAIN_TEXT,

  /**
   * Cannot contain data.
   */
  VOID,
  ;

  private static final Map<Name, HtmlTextEscapingMode> ESCAPING_MODES
      = new HashMap<Name, HtmlTextEscapingMode>();
  static {
    ESCAPING_MODES.put(Name.html("iframe"), CDATA);
    // HTML5 does not treat listing as CDATA, but HTML2 does
    // at http://www.w3.org/MarkUp/1995-archive/NonStandard.html
    // Listing is not supported by browsers.
    //ESCAPING_MODES.put("listing", CDATA);

    // Technically, only if embeds, frames, and scripts, respectively, are
    // enabled.
    ESCAPING_MODES.put(Name.html("noembed"), CDATA);
    ESCAPING_MODES.put(Name.html("noframes"), CDATA);
    ESCAPING_MODES.put(Name.html("noscript"), CDATA);

    // Runs till end of file.
    ESCAPING_MODES.put(Name.html("plaintext"), PLAIN_TEXT);

    ESCAPING_MODES.put(Name.html("script"), CDATA);
    ESCAPING_MODES.put(Name.html("style"), CDATA);

    // Textarea and Title are RCDATA, not CDATA, so decode entity references.
    ESCAPING_MODES.put(Name.html("textarea"), RCDATA);
    ESCAPING_MODES.put(Name.html("title"), RCDATA);

    ESCAPING_MODES.put(Name.html("xmp"), CDATA);

    // Nodes that can't contain content.
    ESCAPING_MODES.put(Name.html("base"), VOID);
    ESCAPING_MODES.put(Name.html("link"), VOID);
    ESCAPING_MODES.put(Name.html("meta"), VOID);
    ESCAPING_MODES.put(Name.html("hr"), VOID);
    ESCAPING_MODES.put(Name.html("br"), VOID);
    ESCAPING_MODES.put(Name.html("img"), VOID);
    ESCAPING_MODES.put(Name.html("embed"), VOID);
    ESCAPING_MODES.put(Name.html("param"), VOID);
    ESCAPING_MODES.put(Name.html("area"), VOID);
    ESCAPING_MODES.put(Name.html("col"), VOID);
    ESCAPING_MODES.put(Name.html("input"), VOID);
  }

  /**
   * The mode used for content following a start tag with the given name.
   */
  public static HtmlTextEscapingMode getModeForTag(Name tagName) {
    HtmlTextEscapingMode mode = ESCAPING_MODES.get(tagName);
    return mode != null ? mode : PCDATA;
  }

  /**
   * True iff the content following the given tag allows escaping text
   * spans: {@code <!--&hellip;-->} that escape even things that might
   * be an end tag for the corresponding open tag.
   */
  public static boolean allowsEscapingTextSpan(Name tagName) {
    // <xmp> and <plaintext> do not admit escaping text spans.
    String canonTagName = tagName.getCanonicalForm();
    return "style".equals(canonTagName) || "script".equals(canonTagName)
        || "title".equals(canonTagName) || "textarea".equals(canonTagName)
        || "noembed".equals(canonTagName) || "noscript".equals(canonTagName)
        || "noframes".equals(canonTagName);
  }

  /**
   * True if content immediately following the start tag must be treated as
   * special CDATA so that less-thans are not treated as starting tags, comments
   * or directives.
   */
  public static boolean isTagFollowedByLiteralContent(Name tagName) {
    HtmlTextEscapingMode mode = getModeForTag(tagName);
    return mode != PCDATA && mode != VOID;
  }

  /**
   * True iff the tag cannot contain any content -- will an HTML parser consider
   * the element to have ended immediately after the start tag.
   */
  public static boolean isVoidElement(Name tagName) {
    return getModeForTag(tagName) == VOID;
  }
}
