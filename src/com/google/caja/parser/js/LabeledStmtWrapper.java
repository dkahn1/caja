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

import com.google.caja.lexer.TokenConsumer;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.reporting.RenderContext;

import java.util.List;

/**
 * A labeled statement implementation that can apply to any statement.
 * Javascript allows labels on statements other than loops.
 *
 * @author mikesamuel@gmail.com
 */
public final class LabeledStmtWrapper extends LabeledStatement {
  // TODO(mikesamuel): Investigate whether use of continue to a non loop
  // functions as goto, and whether that can introduces vulnerabilities.
  // TODO(mikesamuel): Do we want to remove labelling of non-loop statements
  // from Caja?
  // TODO(mikesamuel): Erase the distinction between LabeledStmtWrapper and
  // LabeledStatement.
  private Statement body;

  public LabeledStmtWrapper(
      String value, List<? extends ParseTreeNode> children) {
    this(value, (Statement)children.get(0));
  }

  public LabeledStmtWrapper(String label, Statement body) {
    super(label, Statement.class);
    appendChild(body);
  }

  public Statement getBody() { return body; }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    this.body = (Statement) children().get(0);
  }

  public void render(RenderContext rc) {
    TokenConsumer out = rc.getOut();
    out.mark(getFilePosition());
    String label = getRenderedLabel(rc);
    if (null != label) {
      out.consume(label);
      out.consume(":");
    }
    body.render(rc);
  }
}
