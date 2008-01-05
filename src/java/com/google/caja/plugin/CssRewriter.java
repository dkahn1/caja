// Copyright (C) 2006 Google Inc.
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
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.MutableParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.css.CssTree;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import static com.google.caja.plugin.SyntheticNodes.s;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rewrites CSS to be safer and shorter.
 * Namespaces css ids and classes, excises disallowed constructs, removes
 * extraneous nodes, and collapses duplicate ruleset selectors.
 *
 * @author mikesamuel@gmail.com
 */
public final class CssRewriter {
  private final PluginMeta meta;
  private final MessageQueue mq;

  public CssRewriter(PluginMeta meta, MessageQueue mq) {
    assert null != mq;
    assert null != meta;
    this.meta = meta;
    this.mq = mq;
  }

  /**
   * Rewrite the given CSS tree to be safer and shorter.
   *
   * @param t non null.  modified in place.
   * @return true if the resulting tree is safe.
   */
  public boolean rewrite(AncestorChain<CssTree> t) {
    boolean valid = true;
    // Once at the beginning, and again at the end.
    valid &= removeUnsafeConstructs(t);
    removeEmptyDeclarations(t);
    // After we remove declarations, we may have some rulesets without any
    // declarations which is technically illegal, so we remove rulesets without
    // declarations
    removeEmptyRuleSets(t);
    simplifyExprs(t);
    if (null != meta.namespacePrefix) { namespaceIdents(t); }
    // Do this again to make sure no earlier changes introduce unsafe constructs
    valid &= removeUnsafeConstructs(t);

    translateUrls(t);

    return valid;
  }

  private void removeEmptyDeclarations(AncestorChain<CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.Declaration)) { return true; }
          CssTree.Declaration decl = (CssTree.Declaration) node;
          if (null == decl.getProperty()) {
            ParseTreeNode parent = ancestors.getParentNode();
            if (parent instanceof MutableParseTreeNode) {
              ((MutableParseTreeNode) parent).removeChild(decl);
            }
          }
          return false;
        }
      }, t.parent);
  }
  private void removeEmptyRuleSets(AncestorChain<CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.RuleSet)) { return true; }
          CssTree.RuleSet rset = (CssTree.RuleSet) node;
          List<? extends CssTree> children = rset.children();
          if (children.isEmpty()
              || (children.get(children.size() - 1)
                  instanceof CssTree.Selector)) {
            // No declarations, so get rid of it.
            ParseTreeNode parent = ancestors.getParentNode();
            if (parent instanceof MutableParseTreeNode) {
              ((MutableParseTreeNode) parent).removeChild(rset);
            }
          }
          return false;
        }
      }, t.parent);
  }
  private void simplifyExprs(AncestorChain<CssTree> t) {
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.Term)) { return true; }
          // #ffffff -> #fff
          // lengths such as 0 0 0 0 -> 0
          // rgb(0, 0, 0) -> #000
          // TODO
          return true;
        }
      }, t.parent);
  }
  private void namespaceIdents(AncestorChain<CssTree> t) {
    // Namespace classes and ids
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.SimpleSelector)) { return true; }
          CssTree.SimpleSelector ss = (CssTree.SimpleSelector) node;
          List<? extends CssTree> children = ss.children();
          for (int i = 0, n = children.size(); i < n; ++i) {
            CssTree child = children.get(i);
            if (child instanceof CssTree.ClassLiteral) {
              CssTree.ClassLiteral classLit = (CssTree.ClassLiteral) child;
              CssTree prevSibling = i > 0 ? children.get(i - 1) : null;
              if (prevSibling instanceof CssTree.IdentLiteral
                  && "BODY".equalsIgnoreCase(
                      ((CssTree.IdentLiteral) prevSibling).getValue())) {
                // Don't rename a class if it applies to BODY.  See the code
                // below that allows body.ie6 for browser handling.
                return true;
              }

              classLit.setValue("." + meta.namespacePrefix + "-"
                                + classLit.getValue().substring(1));
            } else if (child instanceof CssTree.IdLiteral) {
              CssTree.IdLiteral idLit = (CssTree.IdLiteral) child;
              idLit.setValue("#" + meta.namespacePrefix + "-"
                             + idLit.getValue().substring(1));
            }
          }
          return true;
        }
      }, t.parent);
    // Make sure that each selector prefixed by a root rule
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (!(node instanceof CssTree.Selector)) { return true; }
          CssTree.Selector sel = (CssTree.Selector) node;
          if (sel.children().isEmpty()
              || !(sel.children().get(0) instanceof CssTree.SimpleSelector)) {
            // Remove from parent
            ParseTreeNode parent = ancestors.getParentNode();
            if (parent instanceof MutableParseTreeNode) {
              ((MutableParseTreeNode) parent).removeChild(sel);
            }
          } else {
            CssTree.SimpleSelector first =
                (CssTree.SimpleSelector) sel.children().get(0);
            // If this selector is like body.ie or body.firefox, move over
            // it so that it remains topmost
            if ("BODY".equalsIgnoreCase(first.getElementName())) {
              // the next part had better be a DESCENDANT combinator
              ParseTreeNode it = sel.children().get(1);
              if (it instanceof CssTree.Combination
                  && (CssTree.Combinator.DESCENDANT
                      == ((CssTree.Combination) it).getCombinator())) {
                first = (CssTree.SimpleSelector) sel.children().get(2);
              }
            }

            // Use the start position of the first item as the position of the
            // synthetic parts.
            FilePosition pos = FilePosition.startOf(first.getFilePosition());

            CssTree.Combination op = s(new CssTree.Combination(
                pos, CssTree.Combinator.DESCENDANT));

            CssTree.ClassLiteral prefixId = s(new CssTree.ClassLiteral(
                pos, "." + meta.namespacePrefix));
            CssTree.SimpleSelector prefixSel = s(new CssTree.SimpleSelector(
                pos, Collections.singletonList(prefixId)));

            sel.insertBefore(op, first);
            sel.insertBefore(prefixSel, op);
          }
          return false;
        }
      }, t.parent);
  }

  private static final Set<String> ALLOWED_PSEUDO_SELECTORS =
      new HashSet<String>(Arrays.asList(
          "link", "visited", "hover", "active", "first-child", "first-letter"
          ));
  boolean removeUnsafeConstructs(AncestorChain<CssTree> t) {
    final Switch rewrote = new Switch();

    // 1) Check that all classes, ids, property names, etc. are valid
    //    css identifiers.
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.SimpleSelector) {
            for (CssTree child : ((CssTree.SimpleSelector) node).children()) {
              if (child instanceof CssTree.Pseudo) {
                child = child.children().get(0);
                // TODO: check argument if child now instanceof FunctionLiteral
              }
              String value = (String) child.getValue();
              if (!isSafeSelectorPart(value)) {
                mq.addMessage(PluginMessageType.UNSAFE_CSS_IDENTIFIER,
                              child.getFilePosition(),
                              MessagePart.Factory.valueOf(value));
                // Will be deleted by a later pass after all messages have been
                // generated
                node.getAttributes().set(CssValidator.INVALID, Boolean.TRUE);
                rewrote.set();
                return false;
              }
            }
          } else if (node instanceof CssTree.Property) {
            CssTree.Property p = (CssTree.Property) node;
            if (!isSafeCssIdentifier(p.getPropertyName())) {
              mq.addMessage(PluginMessageType.UNSAFE_CSS_IDENTIFIER,
                            p.getFilePosition(),
                            MessagePart.Factory.valueOf(p.getPropertyName()));
              declarationFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
              rewrote.set();
              return false;
            }
          }
          return true;
        }
      }, t.parent);

    // 2) Ban content properties, and attr pseudo selectors, and any other
    //    pseudo selectors that don't match the whitelist
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.Property) {
            if ("content".equalsIgnoreCase(
                ((CssTree.Property) node).getPropertyName())) {
              mq.addMessage(PluginMessageType.UNSAFE_CSS_PROPERTY,
                            node.getFilePosition(),
                            MessagePart.Factory.valueOf("content"));
              declarationFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
              rewrote.set();
            }
          } else if (node instanceof CssTree.Pseudo) {
            boolean remove = false;
            CssTree child = ((CssTree.Pseudo) node).children().get(0);
            if (child instanceof CssTree.IdentLiteral) {
              if (!ALLOWED_PSEUDO_SELECTORS.contains(
                  ((CssTree.IdentLiteral) child).getValue().toLowerCase())) {
                mq.addMessage(PluginMessageType.UNSAFE_CSS_PSEUDO_SELECTOR,
                              node.getFilePosition(),
                              node);
                rewrote.set();
                remove = true;
              }
            } else {
              StringBuilder rendered = new StringBuilder();
              try {
                node.render(new RenderContext(new MessageContext(), rendered));
              } catch (IOException ex) {
                throw (AssertionError) new AssertionError(
                    "IOException writing to StringBuilder").initCause(ex);
              }
              mq.addMessage(PluginMessageType.UNSAFE_CSS_PSEUDO_SELECTOR,
                            node.getFilePosition(),
                            MessagePart.Factory.valueOf(rendered.toString()));
              rewrote.set();
              remove = true;
            }
            if (remove) {
              // Delete the containing selector, since otherwise we'd broaden
              // the rule.
              selectorFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
            }
          }
          return true;
        }
      }, t.parent);
    // 3) Remove any properties and attributes that didn't validate
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node instanceof CssTree.Property) {
            if (Boolean.TRUE.equals(node.getAttributes().get(
                                        CssValidator.INVALID))) {
              declarationFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
              rewrote.set();
            }
          } else if (node instanceof CssTree.Attrib) {
            if (Boolean.TRUE.equals(node.getAttributes().get(
                                        CssValidator.INVALID))) {
              simpleSelectorFor(ancestors).getAttributes().set(
                  CssValidator.INVALID, Boolean.TRUE);
              rewrote.set();
            }
          } else if (node instanceof CssTree.Term
                     && (CssPropertyPartType.URI ==
                         node.getAttributes().get(
                             CssValidator.CSS_PROPERTY_PART_TYPE))) {

            boolean remove = false;
            Message removeMsg = null;

            CssTree term = (CssTree.Term) node;

            CssTree.CssLiteral content =
                (CssTree.CssLiteral) term.children().get(0);
            String uriStr = content.getValue();
            try {
              URI uri = new URI(uriStr);
              // the same url check as GxpCompiler
              if (!UrlUtil.isDomainlessUrl(uri)) {
                removeMsg = new Message(
                    PluginMessageType.DISALLOWED_URI,
                    node.getFilePosition(),
                    MessagePart.Factory.valueOf(uriStr));
                rewrote.set();
                remove = true;
              }
            } catch (URISyntaxException ex) {
              removeMsg = new Message(
                  PluginMessageType.DISALLOWED_URI,
                  node.getFilePosition(), MessagePart.Factory.valueOf(uriStr));
              rewrote.set();
              remove = true;
            }

            if (remove) {
              // condemn the containing declaration
              CssTree.Declaration decl = declarationFor(ancestors);
              if (null != decl) {
                if (!decl.getAttributes().is(CssValidator.INVALID)) {
                  if (null != removeMsg) { mq.getMessages().add(removeMsg); }
                  decl.getAttributes().set(CssValidator.INVALID, Boolean.TRUE);
                }
              }
            }
          }
          return true;
        }
      }, t.parent);

    // 4) Remove invalid nodes
    t.node.acceptPreOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if (node.getAttributes().is(CssValidator.INVALID)) {
            ((MutableParseTreeNode) ancestors.parent.node).removeChild(node);
            return false;
          }
          return true;
        }
      }, t.parent);

    // 5) Cleanup.  Remove any rulesets with empty selectors
    // Since this is a post order traversal, we will first remove empty
    // selectors, and then consider any rulesets that have become empty due to
    // a lack of selectors.
    t.node.acceptPostOrder(new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          ParseTreeNode node = ancestors.node;
          if ((node instanceof CssTree.Selector && node.children().isEmpty())
              || (node instanceof CssTree.RuleSet
                  && (node.children().isEmpty()
                      || node.children().get(0) instanceof CssTree.Declaration))
              ) {
            ((MutableParseTreeNode) ancestors.parent.node).removeChild(node);
            return false;
          }
          return true;
        }
      }, t.parent);

    return !rewrote.get();
  }

  private void translateUrls(AncestorChain<CssTree> t) {
      t.node.acceptPreOrder(new Visitor() {
          public boolean visit(AncestorChain<?> ancestors) {
            ParseTreeNode node = ancestors.node;
            if (node instanceof CssTree.Term
                && CssPropertyPartType.URI ==
                node.getAttributes().get(
                    CssValidator.CSS_PROPERTY_PART_TYPE)) {
              CssTree term = (CssTree.Term) node;

              CssTree.CssLiteral content =
                  (CssTree.CssLiteral) term.children().get(0);
              String uriStr = content.getValue();
              try {
                URI uri = new URI(uriStr);
                // prefix the uri properly
                content.setValue(UrlUtil.translateUrl(uri, meta.pathPrefix));
              } catch (URISyntaxException ex) {
                // should've been checked in removeUnsafeConstructs
                throw new AssertionError();
              }
            }
            return true;
          }
        }, t.parent);
  }

  private static CssTree.Declaration declarationFor(AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.Declaration) {
        return (CssTree.Declaration) c.node;
      }
    }
    return null;
  }

  private static CssTree.SimpleSelector simpleSelectorFor(
      AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.SimpleSelector) {
        return (CssTree.SimpleSelector) c.node;
      }
    }
    return null;
  }

  private static CssTree.Selector selectorFor(AncestorChain<?> chain) {
    for (AncestorChain<?> c = chain; null != c; c = c.parent) {
      if (c.node instanceof CssTree.Selector) {
        return (CssTree.Selector) c.node;
      }
    }
    return null;
  }

  private static final Pattern SAFE_SELECTOR_PART =
    Pattern.compile("^[#!\\.]?[a-zA-Z][a-zA-Z0-9\\-]*$");
  /**
   * Restrict selectors to ascii characters until we can test browser handling
   * of escape sequences.
   */
  private static boolean isSafeSelectorPart(String s) {
    return SAFE_SELECTOR_PART.matcher(s).matches();
  }
  private static final Pattern SAFE_CSS_IDENTIFIER =
    Pattern.compile("^[a-zA-Z][a-zA-Z0-9\\-]*$");
  /**
   * Restrict identifiers to ascii characters until we can test browser handling
   * of escape sequences.
   */
  private static boolean isSafeCssIdentifier(String s) {
    return SAFE_CSS_IDENTIFIER.matcher(s).matches();
  }

  private static final class Switch {
    private boolean on;

    public boolean get() { return on; }
    public void set() { this.on = true; }
  }
}
