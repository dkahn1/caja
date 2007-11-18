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

/**
 * Types of html tokens.
 *
 * @author mikesamuel@gmail.com
 */
public enum HtmlTokenType implements TokenType {
  /**
   * An html or xml attribute name consisting of characters other than
   * whitespace, =, or specials.
   */
  ATTRNAME,
  /** An html value, possibly a quoted string. */
  ATTRVALUE,
  /** An html or xml style comment, <tt>&lt;!-- for example --></tt>. */
  COMMENT,
  /** A cdata block, <tt>&lt;![CDATA[ for example ]]></tt>. */
  CDATA,
  /**
   * A directive such as the DTD declaration or
   * <tt>&lt;?xml version=1.0?&gt;</tt>.
   */
  DIRECTIVE,
  /** Unescaped tag, for instance, inside a script, or xmp tag. */
  UNESCAPED,
  /**
   * A quoted string.  Should not show up in well formed html, but may where
   * there is an attribute value without a corresponding name.
   */
  QSTRING,
  /**
   * The beginning of a tag -- not to be condused with a start tag.
   * Valid tag beginnings include <tt>&lt'a</tt> and <tt>&lt;/a</tt>.  The
   * rest of the tag is a series of attribute names, values, and the tag end.
   */
  TAGBEGIN,
  /** The end of a tag.  Either <tt>&gt;</tt> or <tt>/&gt;</tt>. */
  TAGEND,
  /** A block of text, either inside a tag, or as element content. */
  TEXT,
  /** Ignorable whitespace nodes. */
  IGNORABLE,
  /** A server side script block a la php or jsp. */
  SERVERCODE,
  ;
}
