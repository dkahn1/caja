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
 * A top level statement that declares a variable and assigns a function to it.
 *
 * @author mikesamuel@gmail.com
 */
public final class FunctionDeclaration extends Declaration {
  public FunctionDeclaration(
      String identifier, FunctionConstructor initializer) {
    super(identifier, initializer);
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();
    Expression initializer = getInitializer();
    if (null == initializer) {
      throw new IllegalArgumentException(
          "Function declaration missing function");
    }
    if (!(initializer instanceof FunctionConstructor)) {
      throw new ClassCastException(initializer.getClass().getName());
    }
  }

  @Override
  public FunctionConstructor getInitializer() {
    return (FunctionConstructor) super.getInitializer();
  }

  @Override
  public boolean isTerminal() {
    return getInitializer().getBody().isTerminal();
  }

  @Override
  public void render(RenderContext rc) throws IOException {
    FunctionConstructor fc = getInitializer();
    rc.out.append("function ").append(getIdentifier()).append('(');
    rc.indent += 2;
    boolean seen = false;
    for (FormalParam p : fc.getParams()) {
      if (seen) {
        rc.out.append(", ");
      } else {
        seen = true;
      }
      p.render(rc);
    }
    rc.indent -= 2;
    rc.out.append(")");
    fc.getBody().renderBlock(rc, true, false, false);
  }
}
