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

import com.google.caja.parser.ParseTreeNode;

import java.util.List;

/**
 * A literal boolean value.
 *
 * @author mikesamuel@gmail.com
 */
public final class BooleanLiteral extends Literal {
  public final boolean value;

  /** @param children unused.  This ctor is provided for reflection. */
  public BooleanLiteral(Boolean value, List<? extends ParseTreeNode> children) {
    this(value);
  }

  public BooleanLiteral(boolean value) {
    this.value = value;
  }

  @Override
  public Boolean getValue() {
    return Boolean.valueOf(value);
  }

  @Override
  public boolean getValueInBooleanContext() {
    return value;
  }
}
