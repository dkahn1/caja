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

package com.google.caja.parser.quasiliteral;

import com.google.caja.parser.ParseTreeNode;

import java.util.List;
import java.util.Map;

/**
 * Quasiliteral "hole" matching a single value.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class SingleQuasiHole extends AbstractQuasiHole {
  public SingleQuasiHole(Class<? extends ParseTreeNode> matchedClass, String identifier) {
    super(matchedClass, identifier);
  }

  @Override
  protected boolean consumeSpecimens(
      List<ParseTreeNode> specimens,
      Map<String, ParseTreeNode> bindings) {
    return
        specimens.size() >= 1 &&
        isCompatibleClass(specimens.get(0)) &&
        putIfDeepEquals(bindings, getIdentifier(), specimens.remove(0));
  }

  @Override
  protected boolean createSubstitutes(
      List<ParseTreeNode> substitutes,
      Map<String, ParseTreeNode> bindings) {
    ParseTreeNode substitute = bindings.get(getIdentifier());
    if (substitute != null) {
      substitutes.add(substitute.clone());
      return true;
    }
    return false;
  }

  @Override
  protected String getQuantifierSuffix() { return ""; }
}
