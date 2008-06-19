// Copyright (C) 2008 Google Inc.
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

import java.util.List;

/**
 * An operation that assigns to its first child as an lValue.
 * <p>
 * All <tt>AssignOperation</tt>s except ASSIGN(=) also read from that
 * first child first, so those are read-modify-write operations.
 *
 * @author erights@gmail.com
 */
public final class AssignOperation extends Operation {
  public AssignOperation(Operator value, List<? extends Expression> children) {
    this(value, children.toArray(new Expression[children.size()]));
  }

  public AssignOperation(Operator op, Expression... params) {
    super(op, params);
  }

  @Override
  protected void childrenChanged() {
    super.childrenChanged();

    if (!children().get(0).isLeftHandSide()) {
      throw new IllegalArgumentException(children().get(0) + " not an lvalue");
    }
  }
}
