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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A quasiliteral node that can match trees and substitute into trees of
 * {@link com.google.caja.parser.ParseTreeNode} objects, as parsed by the
 * Caja JavaScript {@link com.google.caja.parser.js.Parser}.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public abstract class QuasiNode {
  private final List<QuasiNode> children;

  /**
   * A container for the result of a recursive match on a parse tree.
   */
  public interface QuasiMatch {
    /**
     * The root node at which the pattern matched.
     */
    ParseTreeNode getRoot();

    /**
     * The map of bindings resulting from the pattern match.
     */
    Map<String, ParseTreeNode> getBindings();
  }

  protected QuasiNode(QuasiNode... children) {
    this.children = Collections.unmodifiableList(Arrays.asList(children));
  }

  public List<QuasiNode> getChildren() { return children; }

  public List<QuasiMatch> match(ParseTreeNode specimen) {
    List<QuasiMatch> results = new ArrayList<QuasiMatch>();
    match(specimen, results);
    return results;
  }

  private void match(final ParseTreeNode specimen, List<QuasiMatch> results) {
    final Map<String, ParseTreeNode> bindings = matchHere(specimen);
    if (bindings != null) {
      results.add(new QuasiMatch() {
        public ParseTreeNode getRoot() { return specimen; }
        public Map<String, ParseTreeNode> getBindings() { return bindings; }
        public String toString() { return specimen.toString() + ": " + bindings.toString(); }
      });
    }
    for (ParseTreeNode child : specimen.children()) match(child, results);
  }

  public Map<String, ParseTreeNode> matchHere(ParseTreeNode specimen) {
    List<ParseTreeNode> specimens = new ArrayList<ParseTreeNode>();
    specimens.add(specimen);
    Map<String, ParseTreeNode> bindings = new HashMap<String, ParseTreeNode>();
    return consumeSpecimens(specimens, bindings) ? bindings : null;
  }

  public ParseTreeNode substituteHere(Map<String, ParseTreeNode> bindings) {
    List<ParseTreeNode> results = new ArrayList<ParseTreeNode>();
    return (createSubstitutes(results, bindings) && results.size() == 1) ? results.get(0) : null;
  }

  protected abstract boolean consumeSpecimens(
      List<ParseTreeNode> specimens,
      Map<String, ParseTreeNode> bindings);

  protected abstract boolean createSubstitutes(
      List<ParseTreeNode> substitutes,
      Map<String, ParseTreeNode> bindings);
  
  public String render() {
    return render(0);
  }

  private String render(int level) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < level; i++) result.append("  ");
    result.append(this.toString());
    result.append("\n");
    for (QuasiNode child : getChildren()) result.append(child.render(level + 1));
    return result.toString();
  }

  protected static boolean safeEquals(Object x, Object y) {
    return x != null ? x.equals(y) : y == null;
  }
}
