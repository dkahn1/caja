// Copyright (C) 2007 Google Inc.
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

package com.google.caja.parser.js;

import com.google.caja.lexer.escaping.Escaping;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.parser.AbstractParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParserBase;
import com.google.caja.render.JsPrettyPrinter;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Callback;

import java.io.IOException;

import java.util.List;

/**
 * An identifier used in JavaScript source.
 *
 * @author ihab.awad@gmail.com
 */
public final class Identifier extends AbstractParseTreeNode {
  private final String name;

  public Identifier(
      FilePosition pos, String name, List<? extends ParseTreeNode> children) {
    this(pos, name);
    assert(children.isEmpty());
  }

  public Identifier(FilePosition pos, String name) {
    super(pos);
    if (!(name == null || ParserBase.isQuasiIdentifier(name))) {
      // Disallowed in Parser, so no code should ever produce something that
      // reaches here unless it concatenates two strings together without
      // normalizing the result.
      // This is meant to prevent rendered output from containing identifiers
      // that are not normalized as required by EcmaScript3.1 Chapter 6.
      throw new IllegalArgumentException("Invalid identifier " + name);
    }
    this.name = name;
  }

  @Override
  public String getValue() {
    return name;
  }

  public String getName() { return name; }

  public void render(RenderContext r) {
    if (name != null) {
      StringBuilder escapedName = new StringBuilder();
      Escaping.escapeJsIdentifier(name, r.isAsciiOnly(), escapedName);
      r.getOut().mark(getFilePosition());
      r.getOut().consume(escapedName.toString());
    }
  }

  public final TokenConsumer makeRenderer(
      Appendable out, Callback<IOException> exHandler) {
    return new JsPrettyPrinter(out, exHandler);
  }
}
