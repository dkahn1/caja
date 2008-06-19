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

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.reporting.RenderContext;

import java.util.List;

/**
 *
 * @author ihab.awad@gmail.com
 */
public class DoWhileLoop extends Loop {
  private Statement body;
  private Expression condition;

  public DoWhileLoop(String label, List<? extends ParseTreeNode> children) {
    this(label, (Statement) children.get(0), (Expression) children.get(1));
  }

  public DoWhileLoop(String label, Statement body, Expression condition) {
    super(label);
    createMutation().appendChild(body).appendChild(condition).execute();
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    this.body = (Statement) children().get(0);
    this.condition = (Expression) children().get(1);
  }

  @Override
  public Expression getCondition() { return condition; }
  @Override
  public Statement getBody() { return body; }

  public void render(RenderContext rc) {
    TokenConsumer out = rc.getOut();
    out.mark(getFilePosition());
    String label = getRenderedLabel(rc);
    if (null != label) {
      out.consume(label);
      out.consume(":");
    }
    out.consume("do");
    body.renderBlock(rc, true);
    FilePosition cPos = condition.getFilePosition();
    FilePosition condStart = FilePosition.startOfOrNull(cPos);
    out.consume("while");
    out.consume("(");
    condition.render(rc);
    out.consume(")");
  }
}
