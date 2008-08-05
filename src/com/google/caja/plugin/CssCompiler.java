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

package com.google.caja.plugin;

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.TokenConsumer;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.css.CssTree;
import com.google.caja.parser.js.ArrayConstructor;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.quasiliteral.QuasiBuilder;
import com.google.caja.parser.quasiliteral.ReservedNames;
import com.google.caja.render.CssPrettyPrinter;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.RenderContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compiles CSS style-sheets to JavaScript which outputs the same CSS, but with
 * rules only affecting nodes that are children of a class whose name contains
 * the gadget id.
 *
 * @author mikesamuel@gmail.com
 */
public final class CssCompiler {
  /**
   * A string that will not pass {@link CssRewriter#removeForbiddenIdents}, but
   * which can be used as a suffix for identifiers and class literals that need
   * to be dynamically generated at {@code ___.loadModule} time.
   */
  private static final String GADGET_ID_PLACEHOLDER = "___GADGET___";

  /**
   * @param ss modified destructively.
   */
  public Statement compileCss(CssTree.StyleSheet ss) {
    //     '#foo {}'                                        ; The original rule
    // =>  '#foo-' + IMPORTS___.getIdClass___() + ' {}'     ; Cajoled rule
    // =>  '#foo-gadget123___ {}'                           ; In the browser
    rewriteIds(ss);
    // Make sure that each selector only applies to nodes under a node
    // controlled by the gadget.
    //     'p { }'                                          ; The original rule
    // =>  '.' + IMPORTS___.getIdClass___() + '___ p { }'   ; Cajoled rule
    // =>  '.gadget123___ p { }'                            ; In the browser
    restrictRulesToSubtreeWithGadgetClass(ss);
    // Convert the CSS to JavaScript which emits the same styles.
    //     'p { }'
    // =>  'IMPORTS___.emitCss___(
    //         ['.',' p { }'].join(IMPORTS___.getIdClass___()))
    return cssToJs(ss);
  }

  private void rewriteIds(CssTree.StyleSheet ss) {
    // Rewrite IDs with the gadget suffix.
    ss.acceptPreOrder(new Visitor() {
          public boolean visit(AncestorChain<?> ancestors) {
            ParseTreeNode node = ancestors.node;
            if (!(node instanceof CssTree.SimpleSelector)) { return true; }
            CssTree.SimpleSelector ss = (CssTree.SimpleSelector) node;
            List<? extends CssTree> children = ss.children();
            for (int i = 0, n = children.size(); i < n; ++i) {
              CssTree child = children.get(i);
              if (child instanceof CssTree.IdLiteral) {
                CssTree.IdLiteral idLit = (CssTree.IdLiteral) child;
                idLit.setValue(
                    "#" + idLit.getValue().substring(1)
                    + "-" + GADGET_ID_PLACEHOLDER);
              }
            }
            return true;
          }
        }, null);
  }
  private void restrictRulesToSubtreeWithGadgetClass(CssTree.StyleSheet ss) {
    ss.acceptPreOrder(new Visitor() {
          public boolean visit(AncestorChain<?> ancestors) {
            ParseTreeNode node = ancestors.node;
            if (!(node instanceof CssTree.Selector)) { return true; }
            CssTree.Selector sel = (CssTree.Selector) node;

            // A selector that describes an ancestor of all nodes matched
            // by this rule.
            CssTree.SimpleSelector baseSelector = (CssTree.SimpleSelector)
                sel.children().get(0);
            // If this selector is like body.ie or body.firefox, move over
            // it so that it remains topmost.
            if (sel.children().size() > 2
                && selectorMatchesElement(baseSelector, "body")
                && isDescendant(sel.children().get(1))) {
              baseSelector = (CssTree.SimpleSelector) sel.children().get(2);
            }

            // Use the start position of the base selector as the position of
            // the synthetic parts.
            FilePosition pos = FilePosition.startOf(
                baseSelector.getFilePosition());

            CssTree.Combination op = new CssTree.Combination(
                pos, CssTree.Combinator.DESCENDANT);

            CssTree.ClassLiteral restrictClass = new CssTree.ClassLiteral(
                pos, "." + GADGET_ID_PLACEHOLDER);
            CssTree.SimpleSelector restrictSel = new CssTree.SimpleSelector(
                pos, Collections.singletonList(restrictClass));

            sel.insertBefore(op, baseSelector);
            sel.insertBefore(restrictSel, op);
            return false;
          }
        }, null);
  }

  private Statement cssToJs(CssTree.StyleSheet ss) {
    // Render the CSS to a string, split it (effectively) on the
    // GADGET_ID_PLACEHOLDER to get an array of strings, and produce JavaScript
    // which joins it on the actual gadget id which is chosen at runtime.

    // The below will, if GADGET_ID_PLACEHOLDER where "X", given the sequence
    // of calls
    //    call            sb        cssParts
    //    consume("a")    "a"       []
    //    consume("bX")   ""        ["ab"]
    //    consume("cX")   ""        ["ab", "c"]
    //    consume("d")    "d"       ["ab", "c"]
    //    noMoreTokens()  ""        ["ab", "c", "d"]
    // Which has he property that the output list joined with the placeholder
    // produces the concatenation of the original string.
    final List<Expression> cssParts = new ArrayList<Expression>();
    TokenConsumer cssCompiler = new TokenConsumer() {
          final StringBuilder sb = new StringBuilder();
          final CssPrettyPrinter pp = new CssPrettyPrinter(sb, null);
          public void mark(FilePosition p) { pp.mark(p); }
          public void consume(String s) {
            pp.consume(s);
            // Introduce a break in the array, which will be filled by the join
            // on the gadget id.
            if (s.endsWith(GADGET_ID_PLACEHOLDER)) { flush(); }
          }
          public void noMoreTokens() {
            pp.noMoreTokens();
            flush();
          }
          private void flush() {
            String content = sb.toString();
            if (content.endsWith(GADGET_ID_PLACEHOLDER)) {
              // Remove the place-holder from the end so that we can later join
              // on the gadget's class.
              content = content.substring(
                  0, content.length() - GADGET_ID_PLACEHOLDER.length());
            }
            cssParts.add(
                new StringLiteral(StringLiteral.toQuotedValue(content)));
            sb.setLength(0);
          }
        };
    ss.render(new RenderContext(new MessageContext(), cssCompiler));
    cssCompiler.noMoreTokens();

    ArrayConstructor cssPartsArray = new ArrayConstructor(cssParts);
    cssPartsArray.setFilePosition(ss.getFilePosition());
    // The CSS rule
    //     p { color: purple }
    // is converted to the JavaScript
    //     IMPORTS___.emitCss___(
    //         ['.', ' p { color: purple }']
    //         .join(IMPORTS___.getIdClass___()));
    //
    // If IMPORTS___.getIdClass() returns "g123___", then the resulting
    //     .g123___ p { color: purple }
    // will only make purple paragraphs that are under a node with class g123__.
    ExpressionStmt emitStmt = new ExpressionStmt(
        (Expression) QuasiBuilder.substV(
            ReservedNames.IMPORTS + ".emitCss___(@cssParts./*@synthetic*/join("
            + ReservedNames.IMPORTS + ".getIdClass___()))",
            "cssParts", cssPartsArray));
    emitStmt.setFilePosition(ss.getFilePosition());
    return emitStmt;
  }


  private static boolean selectorMatchesElement(
      CssTree.SimpleSelector t, String elementName) {
    return elementName.equalsIgnoreCase(t.getElementName());
  }

  private static boolean isDescendant(CssTree t) {
    return (t instanceof CssTree.Combination
            && (CssTree.Combinator.DESCENDANT
                == ((CssTree.Combination) t).getCombinator()));
  }
}
