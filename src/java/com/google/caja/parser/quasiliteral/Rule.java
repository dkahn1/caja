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

import com.google.caja.parser.AbstractParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodes;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.plugin.ReservedNames;
import com.google.caja.plugin.SyntheticNodes;
import static com.google.caja.plugin.SyntheticNodes.s;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Pair;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;

/**
 * A rewriting rule supplied by a subclass.
 */
public abstract class Rule implements MessagePart {
  
  /**
   * The special return value from a rule that indicates the rule
   * does not apply to the supplied input.
   */
  public static final ParseTreeNode NONE =
      new AbstractParseTreeNode<ParseTreeNode>() {
        @Override public Object getValue() { return null; }
        public void render(RenderContext r) {
          throw new UnsupportedOperationException();
        }
      };

  private final String name;
  private final Rewriter rewriter;

  /**
   * Create a new {@code Rule}.
   * 
   * @param name the unique name of this rule.
   */
  public Rule(String name, Rewriter rewriter) {
    this.name = name;
    this.rewriter = rewriter;
  }

  /**
   * @return the name of this {@code Rule}.
   */
  public String getName() { return name; }

  /**
   * Process the given input, returning a rewritten node.
   *
   * @param node an input node.
   * @param scope the current scope.
   * @param mq a {@code MessageQueue} for error reporting.
   * @return the rewritten node, or {@link #NONE} to indicate
   * that this rule does not apply to the given input.
   */
  public abstract ParseTreeNode fire(
      ParseTreeNode node,
      Scope scope,
      MessageQueue mq);

  /**
   * @see MessagePart#format(MessageContext,Appendable)
   */
  public void format(MessageContext mc, Appendable out) throws IOException {
    out.append("Rule \"" + name + "\"");
  }
  
  protected final boolean match(
      QuasiNode pattern,
      ParseTreeNode node) {
    return match(pattern, node, new HashMap<String, ParseTreeNode>());
  }

  protected final boolean match(
      QuasiNode pattern,
      ParseTreeNode node,
      Map<String, ParseTreeNode> bindings) {
    Map<String, ParseTreeNode> tempBindings = pattern.matchHere(node);

    if (tempBindings != null) {
      bindings.putAll(tempBindings);
      return true;
    }
    return false;
  }

  protected final boolean match(
      String patternText,
      ParseTreeNode node) {
    return match(getPatternNode(patternText), node);
  }

  protected final boolean match(
      String patternText,
      ParseTreeNode node,
      Map<String, ParseTreeNode> bindings) {
    return match(getPatternNode(patternText), node, bindings);
  }

  protected final void expandEntry(
      Map<String, ParseTreeNode> bindings,
      String key,
      Scope scope,
      MessageQueue mq) {
    bindings.put(key, rewriter.expand(bindings.get(key), scope, mq));
  }

  protected final void expandEntries(
      Map<String, ParseTreeNode> bindings,
      Scope scope,
      MessageQueue mq) {
    for (String key : bindings.keySet()) {
      expandEntry(bindings, key, scope, mq);
    }
  }

  protected final ParseTreeNode expandAll(ParseTreeNode node, Scope scope, MessageQueue mq) {
    return expandAllTo(node, node.getClass(), scope, mq);
  }

  protected final ParseTreeNode expandAllTo(
      ParseTreeNode node,
      Class<? extends ParseTreeNode> parentNodeClass,
      Scope scope,
      MessageQueue mq) {
    List<ParseTreeNode> rewrittenChildren = new ArrayList<ParseTreeNode>();
    for (ParseTreeNode child : node.children()) {
      rewrittenChildren.add(rewriter.expand(child, scope, mq));
    }

    return ParseTreeNodes.newNodeInstance(
        parentNodeClass,
        node.getValue(),
        rewrittenChildren);
  }


  protected final ParseTreeNode subst(
      String patternText,
      Map<String, ParseTreeNode> bindings) {
    return subst(getPatternNode(patternText), bindings);
  }

  protected final ParseTreeNode subst(
      QuasiNode pattern,
      Map<String, ParseTreeNode> bindings) {
    ParseTreeNode result = pattern.substituteHere(bindings);

    if (result == null) {
      // Pattern programming error
      // TODO(ihab.awad): Provide a detailed dump of the bindings in the exception
      throw new RuntimeException("Failed to substitute into: \"" + pattern + "\"");
    }

    return result;
  }

  protected final ParseTreeNode substV(Object... args) {
    if (args.length %2 == 0) throw new RuntimeException("Wrong # of args for subst()");
    Map<String, ParseTreeNode> bindings = new HashMap<String, ParseTreeNode>();
    for (int i = 1; i < args.length; ) {
      bindings.put(
          (String)args[i++],
          (ParseTreeNode)args[i++]);
    }
    return subst((String)args[0], bindings);
  }

  protected ParseTreeNode getFunctionHeadDeclarations(
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    List<ParseTreeNode> stmts = new ArrayList<ParseTreeNode>();

    if (scope.hasFreeArguments()) {
      stmts.add(substV(
          "var @la = ___.args(@ga);",
          "la", new Identifier(ReservedNames.LOCAL_ARGUMENTS),
          "ga", new Reference(new Identifier(ReservedNames.ARGUMENTS))));
    }
    if (scope.hasFreeThis()) {
      stmts.add(substV(
          "var @lt = @gt;",
          "lt", new Identifier(ReservedNames.LOCAL_THIS),
          "gt", new Reference(new Identifier(ReservedNames.THIS))));
    }

    return new ParseTreeNodeContainer(stmts);
  }

  protected Reference newReference(String name) {
    return
        SyntheticNodes.s(new Reference(SyntheticNodes.s(new Identifier(name))));
  }

  protected Pair<ParseTreeNode, ParseTreeNode> reuseEmpty(
      String variableName,
      boolean inOuters,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    ParseTreeNode variableDefinition;

    if (inOuters) {
      variableDefinition = expandReferenceToOuters(
          new Reference(new Identifier(variableName)),
          scope,
          mq);
      variableDefinition = s(new ExpressionStmt((Expression)variableDefinition));
    } else {
      variableDefinition = substV(
          "var @ref;",
          "ref", SyntheticNodes.s(new Identifier(variableName)));
    }

    return new Pair<ParseTreeNode, ParseTreeNode>(
        newReference(variableName),
        variableDefinition);
  }

  protected Pair<ParseTreeNode, ParseTreeNode> reuse(
      String variableName,
      ParseTreeNode value,
      boolean inOuters,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    ParseTreeNode variableDefinition, reference;

    if (inOuters) {
      variableDefinition = substV(
          "___OUTERS___.@ref = @rhs;",
          "ref", SyntheticNodes.s(new Reference(new Identifier(variableName))),
          "rhs", rewriter.expand(value, scope, mq));
      variableDefinition = s(new ExpressionStmt((Expression)variableDefinition));
      reference = substV(
          "___OUTERS___.@ref",
          "ref", newReference(variableName));
    } else {
      variableDefinition = substV(
          "var @ref = @rhs;",
          "ref", SyntheticNodes.s(new Identifier(variableName)),
          "rhs", rewriter.expand(value, scope, mq));
      reference = newReference(variableName);
    }

    return new Pair<ParseTreeNode, ParseTreeNode>(
        reference,
        variableDefinition);
  }

  protected Pair<ParseTreeNode, ParseTreeNode> reuseAll(
      ParseTreeNode arguments,
      boolean inOuters,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    List<ParseTreeNode> refs = new ArrayList<ParseTreeNode>();
    List<ParseTreeNode> rhss = new ArrayList<ParseTreeNode>();

    for (int i = 0; i < arguments.children().size(); i++) {
      Pair<ParseTreeNode, ParseTreeNode> p = reuse(
          "x" + i + "___",
          arguments.children().get(i),
          inOuters,
          rule,
          scope,
          mq);
      refs.add(p.a);
      rhss.add(p.b);
    }

    return new Pair<ParseTreeNode, ParseTreeNode>(
        new ParseTreeNodeContainer(refs),
        new ParseTreeNodeContainer(rhss));
  }

  // TODO(ihab.awad): Refactor so the global case of this is not redundant with the
  // rewriting we do for assignment in the global scope. Part of the problem is that
  // the helper functions here "pretend" not to know about the rewriting rules, when
  // in fact they are pretty closely coupled with them.
  protected ParseTreeNode expandDef(
      ParseTreeNode symbol,
      ParseTreeNode value,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    if (!(symbol instanceof Reference)) {
      throw new RuntimeException("expandDef on non-Reference: " + symbol);
    }
    String sName = getReferenceName(symbol);
    if (scope.isGlobal(sName)) {
      ParseTreeNode pva = new Reference(new Identifier("x___"));
      ParseTreeNode pvb = substV(
          "var @ref = @rhs;",
          "ref", new Identifier("x___"),
          "rhs", value);
        return new ExpressionStmt((Expression)substV(
            "(function() {" +
            "  @pvb;" +
            "  return ___OUTERS___.@sCanSet ? (___OUTERS___.@s = @pva) : " +
            "                                 ___.setPub(___OUTERS___, @sName, @pva);" +
            "})();",
            "s", symbol,
            "sCanSet", new Reference(new Identifier(sName + "_canSet___")),
            "sName", new StringLiteral(StringLiteral.toQuotedValue(sName)),
            "pva", pva,
            "pvb", pvb));
    } else {
        return substV(
            "var @s = @v",
            "s", symbol.children().get(0),
            "v", value);
    }
  }

  protected ParseTreeNode expandMember(
      ParseTreeNode fname,
      ParseTreeNode member,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    if (!scope.isDeclaredFunction(getReferenceName(fname))) {
      throw new RuntimeException("Internal: not statically a function name: " + fname);
    }

    Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();

    if (match("function(@ps*) { @bs*; }", member, bindings)) {
      Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)member);
      if (s2.hasFreeThis()) {
        return substV(
            "___.method(@fname, function(@ps*) {" +
            "  @fh*;" +
            "  @bs*;" +
            "});",
            "fname", expandReferenceToOuters(fname, scope, mq),
            "ps",    bindings.get("ps"),
            "bs",    rewriter.expand(bindings.get("bs"), s2, mq),
            "fh",    getFunctionHeadDeclarations(rule, s2, mq));
      }
    }

    return rewriter.expand(member, scope, mq);
  }

  protected ParseTreeNode expandAllMembers(
      ParseTreeNode fname,
      ParseTreeNode members,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    List<ParseTreeNode> results = new ArrayList<ParseTreeNode>();
    for (ParseTreeNode member : members.children()) {
      results.add(expandMember(fname, member, rule, scope, mq));
    }
    return new ParseTreeNodeContainer(results);
  }

  protected ParseTreeNode expandMemberMap(
      ParseTreeNode fname,
      ParseTreeNode memberMap,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    if (!scope.isDeclaredFunction(getReferenceName(fname))) {
      throw new RuntimeException("Internal: not statically a function name: " + fname);
    }

    Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();

    if (match("({@keys*: @vals*})", memberMap, bindings)) {
      if (literalsEndWith(bindings.get("keys"), "__")) {
        mq.addMessage(
            RewriterMessageType.MEMBER_KEY_MAY_NOT_END_IN_DOUBLE_UNDERSCORE,
            memberMap.getFilePosition(), rule, memberMap);
        return memberMap;
      }

      return substV(
          "({@keys*: @vals*})",
          "keys", bindings.get("keys"),
          "vals", expandAllMembers(fname, bindings.get("vals"), rule, scope, mq));
    }

    mq.addMessage(RewriterMessageType.MAP_EXPRESSION_EXPECTED,
        memberMap.getFilePosition(), rule, memberMap);
    return memberMap;
  }

  // TODO(erights): Remove this when first class constructors are checked in.
  protected ParseTreeNode expandReferenceToOuters(
      ParseTreeNode ref,
      Scope scope,
      MessageQueue mq) {
    String xName = getReferenceName(ref);
     if (scope.isGlobal(xName)) {
        return substV(
            "___OUTERS___.@xCanRead ? ___OUTERS___.@x : ___.readPub(___OUTERS___, @xName, true);",
            "x", ref,
            "xCanRead", new Reference(new Identifier(xName + "_canRead___")),
            "xName", new StringLiteral(StringLiteral.toQuotedValue(xName)));
    } else {
        return ref;
    }
  }

  protected boolean checkMapExpression(
      ParseTreeNode node,
      Rule rule,
      Scope scope,
      MessageQueue mq) {
    Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
    if (!match("({@keys*: @vals*})", node, bindings)) {
      mq.addMessage(
          RewriterMessageType.MAP_EXPRESSION_EXPECTED,
          node.getFilePosition(), rule, node);
      return false;
    } else if (literalsEndWith(bindings.get("keys"), "_")) {
      mq.addMessage(
          RewriterMessageType.KEY_MAY_NOT_END_IN_UNDERSCORE,
          node.getFilePosition(), rule, node);
      return false;
    }
    return true;
  }

  protected boolean isSynthetic(ParseTreeNode node) {
    return node.getAttributes().is(SyntheticNodes.SYNTHETIC);
  }

  protected String getReferenceName(ParseTreeNode ref) {
    return ((Reference)ref).getIdentifierName();
  }

  protected String getIdentifierName(ParseTreeNode id) {
    return ((Identifier)id).getValue();
  }

  protected boolean literalsEndWith(ParseTreeNode container, String suffix) {
    for (ParseTreeNode n : container.children()) {
      assert(n instanceof StringLiteral);
      if (((StringLiteral)n).getUnquotedValue().endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  private QuasiNode getPatternNode(String patternText) {
    return rewriter.getPatternNode(patternText);
  }
}
