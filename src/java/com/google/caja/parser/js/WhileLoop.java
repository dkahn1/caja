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
 *
 * @author mikesamuel@gmail.com
 */
public class WhileLoop extends Loop {
  private Expression condition;
  private Statement body;

  public WhileLoop(String label, List<? extends ParseTreeNode> children) {
    this(label, (Expression) children.get(0), (Statement) children.get(1));
  }

  public WhileLoop(String label, Expression condition, Statement body) {
    super(label);
    createMutation().appendChild(condition).appendChild(body).execute();
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    this.condition = (Expression) children().get(0);
    this.body = (Statement) children().get(1);
  }

  @Override
  public Expression getCondition() { return condition; }
  @Override
  public Statement getBody() { return body; }

  public void render(RenderContext rc) {
    TokenConsumer out = rc.getOut();
    out.mark(getFilePosition());
    String label = getLabel();
    if (null != label && !"".equals(label)) {
      out.consume(label);
      out.consume(":");
    }
    out.consume("while");
    out.consume("(");
    condition.render(rc);
    out.consume(")");
    body.renderBlock(rc, false);
  }
}
