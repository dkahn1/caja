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
import com.google.caja.reporting.RenderContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 * @author mikesamuel@gmail.com
 */
public final class ContinueStmt extends AbstractStatement<Statement> {
  private String label;

  /** @param children unused.  This ctor is provided for reflection. */
  public ContinueStmt(String value, List<? extends Statement> children) {
    this(value);
  }

  public ContinueStmt(String label) {
    this.label = label;
  }

  @Override
  public void continues(Map<String, List<ContinueStmt>> continuesReaching) {
    List<ContinueStmt> continues = continuesReaching.get(this.label);
    if (null == continues) {
      continuesReaching.put(label, continues = new ArrayList<ContinueStmt>());
    }
    continues.add(this);
  }

  @Override
  public Object getValue() { return label; }

  public void render(RenderContext rc) {
    TokenConsumer out = rc.getOut();
    out.mark(getFilePosition());
    out.consume("continue");
    if (null != label && !"".equals(label)) {
      out.consume(label);
    }
  }
}
