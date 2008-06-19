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
 * Encapsulates a value and the code to execute when the switch block is
 * reached with that value.
 * <p>
 * When multiple cases are seen adjacent as in:{@code
 *   case FOO:
 *   case BAR:
 * }
 * each is a separate {@code CaseStmt} but the FOO case has a body which is
 * a {@link Noop}.
 *
 * @author mikesamuel@gmail.com
 */
public final class CaseStmt extends SwitchCase {
  private Expression caseValue;
  private Statement body;

  public CaseStmt(Void value, List<? extends ParseTreeNode> children) {
    this((Expression) children.get(0), (Statement) children.get(1));
  }

  public CaseStmt(Expression caseValue, Statement body) {
    createMutation()
        .appendChild(caseValue)
        .appendChild(body)
        .execute();
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    List<? extends ParseTreeNode> children = children();
    this.caseValue = (Expression) children.get(0);
    this.body = (Statement) children.get(1);
  }

  @Override
  public Object getValue() { return null; }

  public void render(RenderContext rc) {
    TokenConsumer out = rc.getOut();
    out.mark(getFilePosition());
    out.consume("case");
    caseValue.render(rc);
    out.consume(":");
    out.consume("\n");
    body.renderBlock(rc, false);
  }
}
