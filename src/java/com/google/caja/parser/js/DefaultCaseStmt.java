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

/**
 *
 * @author mikesamuel@gmail.com
 */
public final class DefaultCaseStmt extends SwitchCase {
  private Statement body;

  public DefaultCaseStmt(Statement body) {
    this.children.add(body);
    childrenChanged();
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    this.body = (Statement) children.get(0);
  }

  @Override
  public Object getValue() { return null; }

  public void render(RenderContext rc) throws IOException {
    rc.out.append("default:");
    body.renderBlock(rc, true, false, false);
  }
}
