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

package com.google.caja.parser.js;

import com.google.caja.reporting.RenderContext;

import java.io.IOException;

import java.util.List;

/**
 * A group of statements executed serially
 * (except that FunctionDeclarations are hoisted).
 *
 * @author mikesamuel@gmail.com
 */
public final class Block
    extends AbstractStatement<Statement> implements NestedScope {

  public Block(List<? extends Statement> elements) {
    createMutation().appendChildren(elements).execute();
  }

  public void prepend(Statement statement) {
    insertBefore(statement, children().isEmpty() ? null : children().get(0));
  }

  @Override
  public Object getValue() { return null; }

  @Override
  public void renderBlock(RenderContext rc, boolean pre, boolean post,
                          boolean terminate)
      throws IOException {
    if (pre) { rc.out.append(" "); }
    rc.out.append("{");
    rc.indent += 2;
    for (Statement stmt : children()) {
      rc.newLine();
      stmt.render(rc);
      if (!stmt.isTerminal()) {
        rc.out.append(";");
      }
    }
    rc.indent -= 2;
    rc.newLine();
    rc.out.append("}");
    if (post) { rc.out.append(" "); }
  }

  public void render(RenderContext rc) throws IOException {
    renderBlock(rc, false, false, false);
  }

  @Override
  public boolean isTerminal() {
    return true;
  }
}
