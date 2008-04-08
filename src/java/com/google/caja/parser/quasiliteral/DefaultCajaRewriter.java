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
import com.google.caja.parser.ParseTreeNodes;
import com.google.caja.parser.AbstractParseTreeNode;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.js.AssignOperation;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.BreakStmt;
import com.google.caja.parser.js.CaseStmt;
import com.google.caja.parser.js.Conditional;
import com.google.caja.parser.js.ContinueStmt;
import com.google.caja.parser.js.ControlOperation;
import com.google.caja.parser.js.Declaration;
import com.google.caja.parser.js.DefaultCaseStmt;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.FunctionDeclaration;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.LabeledStmtWrapper;
import com.google.caja.parser.js.Literal;
import com.google.caja.parser.js.Loop;
import com.google.caja.parser.js.MultiDeclaration;
import com.google.caja.parser.js.Noop;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.ReturnStmt;
import com.google.caja.parser.js.SimpleOperation;
import com.google.caja.parser.js.SpecialOperation;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.SwitchStmt;
import com.google.caja.parser.js.ThrowStmt;
import com.google.caja.parser.js.TryStmt;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.UndefinedLiteral;
import com.google.caja.parser.js.ArrayConstructor;
import com.google.caja.plugin.ReservedNames;
import com.google.caja.plugin.SyntheticNodes;
import static com.google.caja.plugin.SyntheticNodes.s;
import com.google.caja.util.Pair;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Rewrites a JavaScript parse tree to comply with default Caja rules.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class DefaultCajaRewriter extends Rewriter {
  public DefaultCajaRewriter() {
    this(true);
  }

  public DefaultCajaRewriter(boolean logging) {
    super(logging);

    ////////////////////////////////////////////////////////////////////////
    // Do nothing if the node is already the result of some translation
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("synthetic0", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (isSynthetic(node)) {
          if (node instanceof FunctionConstructor) {
            scope = Scope.fromFunctionConstructor(scope, (FunctionConstructor)node);
          }
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // with - disallow the 'with' construct
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("with", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        // `with` violates the assumptions that Scope makes and makes it very
        // hard to write a Scope that works.

        // http://yuiblog.com/blog/2006/04/11/with-statement-considered-harmful/
        // briefly touches on why `with` is bad for programmers and more-so
        // for reviewers -- matching of references with declarations can only
        // be done at runtime.

        // All other secure JS subsets that I know of (ADSafe Jacaranda & FBJS)
        // also disallow `with`.

        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("with (@scope) @body;", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.WITH_BLOCKS_NOT_ALLOWED,
              node.getFilePosition());
          return node;
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // foreach - "for ... in" loops
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("foreach", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        boolean isDecl;

        if (match("for (var @k in @o) @ss;", node, bindings)) {
          isDecl = true;
          bindings.put("k", new Reference((Identifier)bindings.get("k")));
        } else if (match("for (@k in @o) @ss;", node, bindings)) {
          isDecl = false;
          ExpressionStmt es = (ExpressionStmt)bindings.get("k");
          bindings.put("k", es.getExpression());
        } else {
          return NONE;
        }

        Pair<ParseTreeNode, ParseTreeNode> oTemp = reuse(
            scope.newTempVariable(),
            bindings.get("o"),
            scope.isGlobal(),
            this,
            scope,
            mq);

        Pair<ParseTreeNode, ParseTreeNode> kTemp = reuse(
            scope.newTempVariable(),
            s(new UndefinedLiteral()),
            scope.isGlobal(),
            this,
            scope,
            mq);

        List<Statement> declsList = new ArrayList<Statement>();
        declsList.add((Statement)oTemp.b);
        declsList.add((Statement)kTemp.b);

        if (isDecl) {
          Pair<ParseTreeNode, ParseTreeNode> kDecl = reuseEmpty(
              ((Reference)bindings.get("k")).getIdentifierName(),
              scope.isGlobal(),
              this,
              scope,
              mq);
          declsList.add((Statement)kDecl.b);
        }

        ParseTreeNode kAssignment = substV(
            "@k = @kTempRef;",
            "k", bindings.get("k"),
            "kTempRef", kTemp.a);
        kAssignment.getAttributes().remove(SyntheticNodes.SYNTHETIC);
        kAssignment = expand(kAssignment, scope, mq);
        kAssignment = s(new ExpressionStmt((Expression)kAssignment));

        // Note that we use 'canEnumProp' even if 'this' is actually the global object (in
        // which case 'this' will get rewritten to '___OUTERS___'. Statements in the global
        // scope *are* effectively executing with 'this === ___OUTERS___'.

        boolean isThis = ReservedNames.THIS.equals(bindings.get("o").children().get(0).getValue());
        String canEnumName = isThis && !scope.isGlobal() ? "canEnumProp" : "canEnumPub";
        Reference canEnum = new Reference(new Identifier(canEnumName));

        return substV(
            "@decls*;" +
            "for (@kTempStmt in @oTempRef) {" +
            "  if (___.@canEnum(@oTempRef, @kTempRef)) {" +
            "    @kAssignment;" +
            "    @ss;" +
            "  }" +
            "}",
            "canEnum", canEnum,
            "decls", new ParseTreeNodeContainer(declsList),
            "oTempRef", oTemp.a,
            "kTempRef", kTemp.a,
            "kTempStmt", s(new ExpressionStmt((Expression)kTemp.a)),
            "kAssignment", kAssignment,
            "ss", expand(bindings.get("ss"), scope, mq));
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // try - try/catch/finally constructs
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("tryCatch", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("try { @s0*; } catch (@x) { @s1*; }", node, bindings)) {
          TryStmt t = (TryStmt)node;
          return substV(
            "try {" +
            "  @s0*;" +
            "} catch (ex___) {" +
            "  try {" +
            "    throw ___.tameException(ex___); " +
            "  } catch (@x) {" +
            "    @s1*;" +
             "  }" +
            "}",
            "s0",  expandAll(bindings.get("s0"), scope, mq),
            "s1",  expandAll(bindings.get("s1"),
                             Scope.fromCatchStmt(scope, t.getCatchClause()), mq),
            "x", bindings.get("x"));
        }
        return NONE;
      }
    });

    addRule(new Rule("tryCatchFinally", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("try { @s0*; } catch (@x) { @s1*; } finally { @s2*; }", node, bindings)) {
          TryStmt t = (TryStmt)node;
          return substV(
            "try {" +
            "  @s0*;" +
            "} catch (ex___) {" +
            "  try {" +
            "    throw ___.tameException(ex___);" +
            "  } catch (@x) {" +
            "    @s1*;" +
            "  }" +
            "} finally {" +
            "  @s2*;" +
            "}",
            "s0",  expandAll(bindings.get("s0"), scope, mq),
            "s1",  expandAll(bindings.get("s1"),
                             Scope.fromCatchStmt(scope, t.getCatchClause()), mq),
            "s2",  expandAll(bindings.get("s2"), scope, mq),
            "x", bindings.get("x"));
        }
        return NONE;
      }
    });

    addRule(new Rule("tryFinally", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("try { @s0*; } finally { @s1*; }", node, bindings)) {
          return substV(
            "try { @s0*; } finally { @s1*; }",
            "s0",  expandAll(bindings.get("s0"), scope, mq),
            "s1",  expandAll(bindings.get("s1"), scope, mq));
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // variable - variable name handling
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("varArgs", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match(ReservedNames.ARGUMENTS, node, bindings)) {
          return subst(ReservedNames.LOCAL_ARGUMENTS, bindings);
        }
        return NONE;
      }
    });

    addRule(new Rule("varThis", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match(ReservedNames.THIS, node, bindings)) {
          return scope.isGlobal() ?
              subst("___OUTERS___", bindings) :
              subst(ReservedNames.LOCAL_THIS, bindings);
        }
        return NONE;
      }
    });

    addRule(new Rule("varBadSuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x__", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("varBadSuffixDeclaration", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Declaration &&
            ((Declaration)node).getIdentifier().getValue().endsWith("__")) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("varBadGlobalSuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x_", node, bindings)) {
          String symbol = ((Identifier)bindings.get("x")).getValue() + "_";
          if (scope.isGlobal(symbol)) {
            mq.addMessage(
                RewriterMessageType.GLOBALS_CANNOT_END_IN_UNDERSCORE,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("varFuncFreeze", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x", node, bindings) &&
            bindings.get("x") instanceof Reference) {
          String name = getReferenceName(bindings.get("x"));
          if (scope.isFunction(name)) {
            return substV(
                "___.primFreeze(@x)",
                "x", expandReferenceToOuters(bindings.get("x"), scope, mq));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("varGlobal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x", node, bindings) &&
            bindings.get("x") instanceof Reference) {
          return expandReferenceToOuters(bindings.get("x"), scope, mq);
        }
        return NONE;
      }
    });

    addRule(new Rule("varDefault", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x", node, bindings) &&
            bindings.get("x") instanceof Reference) {
          return bindings.get("x");
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // read - reading values
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("readBadSuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x.@y__", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("readGlobalViaThis", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@p", node, bindings) && scope.isGlobal()) {
          String xName = getReferenceName(bindings.get("p"));
          return substV(
              "___OUTERS___.@xCanRead ? ___OUTERS___.@x : ___.readPub(___OUTERS___, @xName);",
              "x", bindings.get("p"),
              "xCanRead", new Reference(new Identifier(xName + "_canRead___")),
              "xName", new StringLiteral(StringLiteral.toQuotedValue(xName)));
        }
        return NONE;
      }
    });

    addRule(new Rule("readInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@p", node, bindings)) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
            "t___.@fp ? t___.@p : ___.readProp(t___, @rp)",
            "p",  p,
            "fp", new Reference(new Identifier(propertyName + "_canRead___")),
            "rp", toStringLiteral(p));
        }
        return NONE;
      }
    });

    addRule(new Rule("readBadInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x.@y_", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_PROPERTIES_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("readPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o.@p", node, bindings)) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
              "(function() {" +
              "  var x___ = @o;" +
              "  return x___.@fp ? x___.@p : ___.readPub(x___, @rp);" +
              "})()",
              "o",  expand(bindings.get("o"), scope, mq),
              "p",  p,
              "fp", new Reference(new Identifier(propertyName + "_canRead___")),
              "rp", toStringLiteral(p));
        }
        return NONE;
      }
    });

    addRule(new Rule("readIndexGlobal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this[@s]", node, bindings) && scope.isGlobal()) {
          return substV(
              "___.readPub(___OUTERS___, @s)",
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("readIndexInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this[@s]", node, bindings)) {
          return substV(
              "___.readProp(t___, @s)",
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("readIndexPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o[@s]", node, bindings)) {
          return substV(
              "___.readPub(@o, @s)",
              "o", expand(bindings.get("o"), scope, mq),
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // set - assignments
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("setGlobal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@p = @r", node, bindings) &&
            bindings.get("p") instanceof Reference) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = getReferenceName(p);
          if (scope.isGlobal(propertyName) && !ReservedNames.THIS.equals(propertyName)) {
            return substV(
                "(function() {" +
                "  var x___ = @r;" +
                "  return ___OUTERS___.@fp ?" +
                "      (___OUTERS___.@p = x___) :" +
                "      ___.setPub(___OUTERS___, @rp, x___);" +
                "})()",
                "r",  expand(bindings.get("r"), scope, mq),
                "p",  p,
                "fp", new Reference(new Identifier(propertyName + "_canSet___")),
                "rp", toStringLiteral(p));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("setBadThis", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this = @z", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.CANNOT_ASSIGN_TO_THIS,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("setBadSuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x.@y__ = @z", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("setGlobalViaThis", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@p = @r", node, bindings) && scope.isGlobal()) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
              "(function() {" +
              "  var x___ = @r;" +
              "  return ___OUTERS___.@fp ?" +
              "      (___OUTERS___.@p = x___) :" +
              "      ___.setPub(___OUTERS___, @rp, x___);" +
              "})()",
              "r",  expand(bindings.get("r"), scope, mq),
              "p",  p,
              "fp", new Reference(new Identifier(propertyName + "_canSet___")),
              "rp", toStringLiteral(p));
        }
        return NONE;
      }
    });

    addRule(new Rule("setInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@p = @r", node, bindings)) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          Reference target = new Reference(new Identifier(
              scope.isGlobal() ? ReservedNames.OUTERS : ReservedNames.LOCAL_THIS));
          return substV(
              "(function() {" +
              "  var x___ = @r;" +
              "  return t___.@fp ? (t___.@p = x___) : ___.setProp(t___, @rp, x___);" +
              "})()",
              "r",  expand(bindings.get("r"), scope, mq),
              "p",  p,
              "fp", new Reference(new Identifier(propertyName + "_canSet___")),
              "rp", toStringLiteral(p),
              "target", target);
        }
        return NONE;
      }
    });

    addRule(new Rule("setMember", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();

        // BUG TODO(erights,ihab): We must only recognize (and thus allow) this
        // expression when it is evaluated for effects only, not for value.
        // Currently, since we have no such test, the translated expression will
        // safely evaluate to <tt>undefined</tt>, but this behavior is not within
        // a fail-stop subset of JavaScript.
        if (match("@clazz.prototype.@p = @m;", node, bindings)) {
          ParseTreeNode clazz = bindings.get("clazz");
          if (clazz instanceof Reference) {
            String className = getReferenceName(clazz);
            if (scope.isDeclaredFunction(className)) {
              Reference p = (Reference) bindings.get("p");
              if (!"constructor".equals(getReferenceName(p))) {
                // Make sure @p and @clazz are mentionable.
                expand(p, scope, mq);
                expand(clazz, scope, mq);
                return substV(
                    "___.setMember(@clazz, @rp, @m);",
                    "clazz", expandReferenceToOuters(clazz, scope, mq),  // Don't expand so we don't freeze.
                    "m", expandMember(clazz, bindings.get("m"), this, scope, mq),
                    "rp", toStringLiteral(p));
              }
            }
          } else {
            // TODO(mikesamuel): make constructors first class for the purpose
            // of defining members.
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("setBadInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@x.@y_ = @z", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_PROPERTIES_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("setStatic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@fname.@p = @r", node, bindings) &&
            bindings.get("fname") instanceof Reference &&
            scope.isFunction(getReferenceName(bindings.get("fname")))) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          if (!"Super".equals(propertyName)) {
            return substV(
                "___.setPub(@fname, @rp, @r)",
                "fname", bindings.get("fname"),
                "rp", toStringLiteral(p),
                "r", expand(bindings.get("r"), scope, mq));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("setPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o.@p = @r", node, bindings)) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          Pair<ParseTreeNode, ParseTreeNode> po =
              reuse("x___", bindings.get("o"), false, this, scope, mq);
          Pair<ParseTreeNode, ParseTreeNode> pr =
              reuse("x0___", bindings.get("r"), false, this, scope, mq);
          return substV(
              "(function() {" +
              "  @pob;" +
              "  @prb;" +
              "  return @poa.@pCanSet ? (@poa.@p = @pra) : " +
              "                         ___.setPub(@poa, @pName, @pra);" +
              "})();",
              "pName", toStringLiteral(p),
              "p", p,
              "pCanSet", new Reference(new Identifier(propertyName + "_canSet___")),
              "poa", po.a,
              "pob", po.b,
              "pra", pr.a,
              "prb", pr.b);
        }
        return NONE;
      }
    });

    addRule(new Rule("setIndexInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this[@s] = @r", node, bindings)) {
          return substV(
              "___.setProp(t___, @s, @r)",
              "s", expand(bindings.get("s"), scope, mq),
              "r", expand(bindings.get("r"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("setIndexPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o[@s] = @r", node, bindings)) {
          return substV(
              "___.setPub(@o, @s, @r)",
              "o", expand(bindings.get("o"), scope, mq),
              "s", expand(bindings.get("s"), scope, mq),
              "r", expand(bindings.get("r"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("setBadInitialize", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("var @v__ = @r", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("setInitialize", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("var @v = @r", node, bindings) &&
            !scope.isFunction(getIdentifierName(bindings.get("v")))) {
          return expandDef(
              new Reference((Identifier)bindings.get("v")),
              expand(bindings.get("r"), scope, mq),
              this,
              scope,
              mq);
        }
        return NONE;
      }
    });

    addRule(new Rule("setBadDeclare", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("var @v__", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("setDeclare", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("var @v", node, bindings) &&
            !scope.isFunction(getIdentifierName(bindings.get("v")))) {
          if (!scope.isGlobal()) {
            return node;
          } else {
            ParseTreeNode v = bindings.get("v");
            String vName = getIdentifierName(v);
            ParseTreeNode expr = substV(
                "___.setPub(___OUTERS___, @vName, ___.readPub(___OUTERS___, @vName));",
                "vName", toStringLiteral(v));
            // Must now wrap the Expression in something Statement-like since
            // that is what the enclosing context expects:
            return ParseTreeNodes.newNodeInstance(
                ExpressionStmt.class,
                null,
                Arrays.asList(new ParseTreeNode[] { expr }));
          }
        }
        return NONE;
      }
    });

    // TODO(erights): Need a general way to expand lValues
    addRule(new Rule("setVar", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@v = @r", node, bindings)) {
          ParseTreeNode v = bindings.get("v");
          ParseTreeNode r = bindings.get("r");
          if (v instanceof Reference) {
            String vName = getReferenceName(v);
            if (!scope.isFunction(vName)) {
              if (scope.isGlobal(vName)) {
                Pair<ParseTreeNode, ParseTreeNode> pr =
                    reuse("x___", r, true, this, scope, mq);
                return substV(
                    "(function() {" +
                    "  @prb;" +
                    "  return ___OUTERS___.@vCanSet ? (___OUTERS___.@v = @pra) :" +
                    "                                 ___.setPub(___OUTERS___, @vName, @pra);" +
                    "})();",
                    "v", v,
                    "vCanSet", new Reference(new Identifier(vName + "_canSet___")),
                    "vName", toStringLiteral(v),
                    "pra", pr.a,
                    "prb", pr.b);
              } else {
                return substV(
                    "@v = @r",
                    "v", v,
                    "r", expand(r, scope, mq));
              }
            }
          }
        }
        return NONE;
      }
    });

    // TODO(erights): Need a general way to expand readModifyWrite lValues.
    // For now, we're just picking off a few common special cases as they
    // come up.

    addRule(new Rule("setReadModifyWriteLocalVar", this) {
      // Handle x += 3 and similar ops by rewriting them using the assignment
      // delegate, "x += y" => "x = x + y", with deconstructReadAssignOperand
      // assuring that x is evaluated at most once where that matters.
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof AssignOperation) {
          AssignOperation aNode = (AssignOperation)node;
          Operator op = aNode.getOperator();
          if (op.getAssignmentDelegate() == null) { return NONE; }

          ReadAssignOperands ops = deconstructReadAssignOperand(
              aNode.children().get(0), scope, mq);
          if (ops == null) { return node; }  // Error deconstructing

          // For x += 3, rhs is (x + 3)
          Operation rhs = Operation.create(
              op.getAssignmentDelegate(), ops.getRValue(),
              (Expression) expand(aNode.children().get(1), scope, mq));
          rhs.setFilePosition(aNode.getFilePosition());
          Expression assignment = ops.makeAssignment(rhs);
          ((AbstractParseTreeNode<?>) assignment)
              .setFilePosition(aNode.getFilePosition());
          if (ops.getTemporaries().isEmpty()) {
            return assignment;
          } else {
            return substV("(function () { @tmp*; return @assign; })()",
                          "tmp", ops.getTemporariesAsContainer(),
                          "assign", assignment);
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("setIncrDecr", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (!(node instanceof AssignOperation)) { return NONE; }
        AssignOperation op = (AssignOperation) node;
        Expression v = op.children().get(0);
        ReadAssignOperands ops = deconstructReadAssignOperand(v, scope, mq);
        if (ops == null) { return node; }

        // TODO(mikesamuel): Figure out when post increments are being
        // used without use of the resulting value and switch them to
        // pre-increments.
        switch (op.getOperator()) {
          case POST_INCREMENT:
            if (ops.isSimpleLValue()) {
              return substV("@v ++", "v", ops.getRValue());
            } else {
              return substV(
                  "(function () {"
                  + "  @tmp*;"
                  + "  var x___ = @rvalue - 0;"  // Coerce to a number.
                  + "  @assign;"  // Assign value.
                  + "  return x___;"
                  + "})()",
                  "tmp", ops.getTemporariesAsContainer(),
                  "rvalue", ops.getRValue(),
                  "assign", new ExpressionStmt(
                      ops.makeAssignment((Expression) substV("x___ + 1"))));
            }
          case PRE_INCREMENT:
            // We subtract -1 instead of adding 1 since the - operator coerces
            // to a number in the same way the ++ operator does.
            if (ops.isSimpleLValue()) {
              return substV("++@v", "v", ops.getRValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return ops.makeAssignment((Expression) 
                  substV("@rvalue - -1", "rvalue", ops.getRValue()));
            } else {
              return substV(
                  "(function () {" +
                  "  @tmp*;" +
                  "  return @assign;" +
                  "})()",
                  "tmp", ops.getTemporariesAsContainer(),
                  "assign", ops.makeAssignment((Expression) 
                      substV("@rvalue - -1", "rvalue", ops.getRValue())));
            }
          case POST_DECREMENT:
            if (ops.isSimpleLValue()) {
              return substV("@v--", "v", ops.getRValue());
            } else {
              return substV(
                  "(function () {" +
                  "  @tmp*;" +
                  "  var x___ = @rvalue - 0;" +  // Coerce to a number.
                  "  @assign;" +  // Assign value.
                  "  return x___;" +
                  "})()",
                  "tmp", ops.getTemporariesAsContainer(),
                  "rvalue", ops.getRValue(),
                  "assign", new ExpressionStmt(
                      ops.makeAssignment((Expression) substV("x___ - 1"))));
            }
          case PRE_DECREMENT:
            if (ops.isSimpleLValue()) {
              return substV("--@v", "v", ops.getRValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return ops.makeAssignment((Expression) 
                  substV("@rvalue - 1", "rvalue", ops.getRValue()));
            } else {
              return substV(
                  "(function () {" +
                  "  @tmp*;" +
                  "  return @assign;" +
                  "})()",
                  "tmp", ops.getTemporariesAsContainer(),
                  "assign", ops.makeAssignment((Expression) 
                      substV("@rvalue - 1", "rvalue", ops.getRValue())));
            }
          default:
            return NONE;
        }
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // new - new object creation
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("newCalllessCtor", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("new @ctor", node, bindings)) {
          return expand(
              Operation.create(Operator.FUNCTION_CALL, (Expression) node),
              scope, mq);
        }
        return NONE;
      }
    });

    addRule(new Rule("newCtor", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("new @ctor(@as*)", node, bindings)) {
          ParseTreeNode ctor = bindings.get("ctor");
          return substV(
              "new (___.asCtor(@ctor))(@as*)",
              "ctor", expand(ctor, scope, mq),
              "as", expandAll(bindings.get("as"), scope, mq));
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // delete - property deletion
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("deleteProp", this) {
      @Override
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings
            = new LinkedHashMap<String, ParseTreeNode>();
        if (match("delete this[@k]", node, bindings)) {
          ParseTreeNode thisNode = node.children().get(0).children().get(0);
          return substV(
              "___.deleteProp(@this, @k)",
              "this", expand(thisNode, scope, mq),
              "k", expand(bindings.get("k"), scope, mq)
              );
        } else if (match("delete this.@k", node, bindings)) {
          ParseTreeNode thisNode = node.children().get(0).children().get(0);
          Reference k = (Reference) bindings.get("k");
          if (k.getIdentifierName().endsWith("__")) {
            mq.addMessage(
                RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
                node.getFilePosition(), this, node);
          }
          return substV(
              "___.deleteProp(@this, @kname)",
              "this", expand(thisNode, scope, mq),
              "kname", toStringLiteral(k)
              );
        }
        return NONE;
      }
    });

    addRule(new Rule("deletePub", this) {
      @Override
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings
            = new LinkedHashMap<String, ParseTreeNode>();
        if (match("delete @o[@k]", node, bindings)) {
          return substV(
              "___.deletePub(@o, @k)",
              "o", expand(bindings.get("o"), scope, mq),
              "k", expand(bindings.get("k"), scope, mq));
        } else if (match("delete @o.@k", node, bindings)) {
          Reference k = (Reference) bindings.get("k");
          expand(k, scope, mq);
          return substV(
              "___.deletePub(@o, @ks)",
              "o", expand(bindings.get("o"), scope, mq),
              "ks", toStringLiteral(k));
        }
        return NONE;
      }
    });

    addRule(new Rule("deleteGlobal", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings
            = new LinkedHashMap<String, ParseTreeNode>();
        if (match("delete @v", node, bindings)) {
          ParseTreeNode v = bindings.get("v");
          if (v instanceof Reference) {
            expand(v, scope, mq);  // Make sure v is mentionable
            return substV(
                "___.deletePub(___OUTERS___, @vname)",
                "vname", toStringLiteral(v));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("deleteNonLvalue", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings
            = new LinkedHashMap<String, ParseTreeNode>();
        if (match("delete @v", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.NOT_DELETABLE, node.getFilePosition());
          return node;
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // call - function calls
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("callBadSuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o.@s__(@as*)", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.SELECTORS_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("callGlobalViaThis", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@m(@as*)", node, bindings) && scope.isGlobal()) {
          Pair<ParseTreeNode, ParseTreeNode> aliases =
              reuseAll(bindings.get("as"), false, this, scope, mq);
          Reference m = (Reference) bindings.get("m");
          String methodName = m.getIdentifierName();
          return substV(
              "(function() {" +
              "  @as*;" +
              "  return ___OUTERS___.@fm ?" +
              "      ___OUTERS___.@m(@vs*) :" +
              "      ___.callPub(___OUTERS___, @rm, [@vs*]);" +
              "})()",
              "as", aliases.b,
              "vs", aliases.a,
              "m",  m,
              "fm", new Reference(new Identifier(methodName + "_canCall___")),
              "rm", toStringLiteral(m));
        }
        return NONE;
      }
    });

    addRule(new Rule("callInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this.@m(@as*)", node, bindings)) {
          Pair<ParseTreeNode, ParseTreeNode> aliases =
              reuseAll(bindings.get("as"), false, this, scope, mq);
          Reference m = (Reference) bindings.get("m");
          String methodName = m.getIdentifierName();
          return substV(
              "(function() {" +
              "  @as*;" +
              "  return t___.@fm ? t___.@m(@vs*) : ___.callProp(t___, @rm, [@vs*]);" +
              "})()",
              "as", aliases.b,
              "vs", aliases.a,
              "m",  bindings.get("m"),
              "fm", new Reference(new Identifier(methodName + "_canCall___")),
              "rm", toStringLiteral(m));
        }
        return NONE;
      }
    });

    addRule(new Rule("callBadInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o.@s_(@as*)", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_SELECTORS_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("callCajaDef2", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("caja.def(@fname, @base)", node, bindings) &&
            scope.isFunction(getReferenceName(bindings.get("fname"))) &&
            scope.isFunction(getReferenceName(bindings.get("base")))) {
          return substV(
              "caja.def(@fname, @base)",
              "fname", expandReferenceToOuters(bindings.get("fname"), scope, mq),
              "base", expandReferenceToOuters(bindings.get("base"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("callCajaDef2Bad", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("caja.def(@fname, @base)", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.CAJA_DEF_ON_NON_CTOR,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("callCajaDef3Plus", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("caja.def(@fname, @base, @mm, @ss?)", node, bindings) &&
            scope.isFunction(getReferenceName(bindings.get("fname"))) &&
            (bindings.get("base") instanceof UndefinedLiteral ||
             scope.isFunction(getReferenceName(bindings.get("base"))))) {
          if (!checkMapExpression(bindings.get("mm"), this, scope, mq)) {
            return node;
          }
          if (bindings.get("ss") != null &&
              !checkMapExpression(bindings.get("ss"), this, scope, mq)) {
            return node;
          }
          ParseTreeNode ss = bindings.get("ss") == null ? null :
              expandAll(bindings.get("ss"), scope, mq);
          return substV(
              "caja.def(@fname, @base, @mm, @ss?)",
              "fname", expandReferenceToOuters(bindings.get("fname"), scope, mq),
              "base", expandReferenceToOuters(bindings.get("base"), scope, mq),
              "mm", expandMemberMap(bindings.get("fname"), bindings.get("mm"), this, scope, mq),
              "ss", ss);
        }
        return NONE;
      }
    });

    addRule(new Rule("callCajaDef3PlusBad", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("caja.def(@fname, @base, @mm, @ss?)", node, bindings)) {
          mq.addMessage(
              RewriterMessageType.CAJA_DEF_ON_NON_CTOR,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("callPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o.@m(@as*)", node, bindings)) {
          Pair<ParseTreeNode, ParseTreeNode> aliases =
              reuseAll(bindings.get("as"), false, this, scope, mq);
          Reference m = (Reference) bindings.get("m");
          String methodName = m.getIdentifierName();
          return substV(
              "(function() {" +
              "  var x___ = @o;" +
              "  @as*;" +
              "  return x___.@fm ? x___.@m(@vs*) : ___.callPub(x___, @rm, [@vs*]);" +
              "})()",
              "o",  expand(bindings.get("o"), scope, mq),
              "as", aliases.b,
              "vs", aliases.a,
              "m",  m,
              "fm", new Reference(new Identifier(methodName + "_canCall___")),
              "rm", toStringLiteral(m));
        }
        return NONE;
      }
    });

    addRule(new Rule("callIndexInternal", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("this[@s](@as*)", node, bindings)) {
          expandEntries(bindings, scope, mq);
          return subst(
              "___.callProp(t___, @s, [@as*])", bindings
          );
        }
        return NONE;
      }
    });

    addRule(new Rule("callIndexPublic", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o[@s](@as*)", node, bindings)) {
          expandEntries(bindings, scope, mq);
          return subst(
              "___.callPub(@o, @s, [@as*])", bindings
          );
        }
        return NONE;
      }
    });

    addRule(new Rule("callFunc", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@f(@as*)", node, bindings)) {
          return substV(
              "___.asSimpleFunc(@f)(@as*)",
              "f", expand(bindings.get("f"), scope, mq),
              "as", expandAll(bindings.get("as"), scope, mq));
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // function - function definitions
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("funcAnonSimple", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        // Anonymous simple function constructor
        if (match("function(@ps*) { @bs*; }", node, bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)node);
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            return substV(
                "___.primFreeze(" +
                "  ___.simpleFunc(" +
                "    function(@ps*) {" +
                "      @fh*;" +
                "      @bs*;" +
                "}))",
                "ps", bindings.get("ps"),
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(this, s2, mq));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("funcNamedSimpleDecl", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        // Named simple function declaration
        if (node instanceof FunctionDeclaration &&
            match("function @f(@ps*) { @bs*; }", node.children().get(1), bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor)node.children().get(1));
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            return expandDef(
                new Reference((Identifier)bindings.get("f")),
                substV(
                    "___.simpleFunc(" +
                    "  function @f(@ps*) {" +
                    "    @fh*;" +
                    "    @bs*;" +
                    "});",
                    "f", bindings.get("f"),
                    "ps", bindings.get("ps"),
                    "bs", expand(bindings.get("bs"), s2, mq),
                    "fh", getFunctionHeadDeclarations(this, s2, mq)),
                this,
                scope,
                mq);
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("funcNamedSimpleValue", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        // Named simple function expression
        if (match("function @f(@ps*) { @bs*; }", node, bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor)node);
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            return substV(
                "___.primFreeze(" +
                "  ___.simpleFunc(" +
                "    function @f(@ps*) {" +
                "      @fh*;" +
                "      @bs*;" +
                "  }));",
                "f", bindings.get("f"),
                "ps", bindings.get("ps"),
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(this, s2, mq));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("funcUnattachedMethod", this) {
      @Override
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, final MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("(function (@formals*) { @body*; })", node, bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope, (FunctionConstructor) node);
          if (!s2.hasFreeThis()) { return NONE; }

          checkFormals(bindings.get("formals"), mq);
          // An unattached method is one where this is only used to access the
          // public API.
          // We cajole an unattached method by converting all `this` references
          // in the body to `t___` and then cajole the body.
          // Attempts to use private APIs, as in (this.foo_) fail statically,
          // and elsewhere, we will use (___.readPub) instead of (___.readProp).
          ParseTreeNode rewrittenBody = bindings.get("body").clone();
          rewrittenBody.acceptPreOrder(new Visitor() {
                public boolean visit(AncestorChain<?> ac) {
                  if (ac.node instanceof FunctionConstructor) { return false; }
                  if (!(ac.node instanceof Reference)) { return true; }
                  Reference ref = ac.cast(Reference.class).node;
                  if (!ReservedNames.THIS.equals(ref.getIdentifierName())) {
                    return true;
                  }
                  // If used in a context where this would be ambiguous, warn.
                  if (ac.parent != null
                      && ac.parent.node instanceof Operation) {
                    switch (((Operation) ac.parent.node).getOperator()) {
                      case SQUARE_BRACKET:
                      case DELETE:
                        mq.addMessage(
                            RewriterMessageType.UNATTACHED_METHOD_AMBIGUITY,
                            ac.parent.node.getFilePosition());
                        break;
                    }
                  }
                  // Make a synethetic reference, so the reference will survive
                  // cajoling but will not trigger the readProp/readPub
                  // difference.
                  Identifier syntheticLocalThis = s(
                      new Identifier(ReservedNames.LOCAL_THIS));
                  syntheticLocalThis.setFilePosition(ref.getFilePosition());
                  s(ref).replaceChild(syntheticLocalThis, ref.getIdentifier());
                  return true;
                }
              }, null);
          return substV(
              "___.unattachedMethod(" +
              "    function (@formals*) { var @localThis = this; @body*; })",
              "formals", bindings.get("formals"),
              "localThis", s(new Identifier(ReservedNames.LOCAL_THIS)),
              "body", expand(rewrittenBody, scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("funcBadMethod", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("function(@ps*) { @bs*; }", node, bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)node);
          if (s2.hasFreeThis()) {
            mq.addMessage(
                RewriterMessageType.ANONYMOUS_FUNCTION_REFERENCES_THIS,
                node.getFilePosition(), 
                this, 
                node);
            return node;
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("funcCtor", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        boolean declaration = node instanceof FunctionDeclaration;
        ParseTreeNode constructorNode = declaration ? node.children().get(1) : node;
        if (match("function @f(@ps*) { @b; @bs*; }", constructorNode, bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)constructorNode);
          if (s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            ParseTreeNode bNode = bindings.get("b");
            if (bNode instanceof ExpressionStmt) {
              // Rebind bNode to the Expression part of the ExpressionStmt.
              bNode = bNode.children().get(0);
            }
            Map<String, ParseTreeNode> superBindings = new LinkedHashMap<String, ParseTreeNode>();
            // To subclass, the very first line must be a call to the super constructor,
            // which must be a reference to a declared function.
            if (match("@super.call(this, @params*);", bNode, superBindings) &&
                s2.isDeclaredFunctionReference(superBindings.get("super"))){
              Scope paramScope = Scope.fromParseTreeNodeContainer(
                  s2, 
                  (ParseTreeNodeContainer)superBindings.get("params"));
              // The rest of the parameters must not contain "this".
              if (paramScope.hasFreeThis()) {
                mq.addMessage(
                    RewriterMessageType.PARAMETERS_TO_SUPER_CONSTRUCTOR_MAY_NOT_CONTAIN_THIS,
                    node.getFilePosition(), 
                    this, 
                    bNode);
                return node;
              }
              // Expand the parameters, but not the call itself. 
              bNode = new ExpressionStmt((Expression)substV(
                  "@super.call(this, @params*);",
                  "super", expandReferenceToOuters(superBindings.get("super"), s2, mq),
                  "params", expand(superBindings.get("params"), s2, mq)));
            } else {
              // If it's not a call to a constructor, expand the entire node.
              bNode = expand(bindings.get("b"), s2, mq);
            }
            Identifier f = (Identifier)bindings.get("f");
            Reference fRef = new Reference(f);
            Identifier f_init___ = new Identifier(f.getName() + "_init___");
            Reference f_init___Ref = new Reference(f_init___);
            ParseTreeNode result = substV(
                "(function () {" +
                "  ___.splitCtor(@fRef, @f_init___Ref);" +
                "  function @f(var_args) { return new @fRef.make___(arguments); }" +
                "  function @f_init(@ps*) {" +
                "    @fh*;" +
                "    @b;" +
                "    @bs*;" +
                "  }" +
                "  return @fRef;" +
                "})()",
                "f", f,
                "fRef", fRef,
                "f_init", f_init___,
                "f_init___Ref", f_init___Ref,
                "ps", bindings.get("ps"),
                "fh", getFunctionHeadDeclarations(this, s2, mq),
                "b", bNode,
                "bs", expand(bindings.get("bs"), s2, mq));
            return declaration ?
                // If it's a declaration, assign the result to a variable with the same name.
                expandDef(
                    new Reference((Identifier)bindings.get("f")),
                    result,
                    this,
                    scope,
                    mq) :
                // If used in an expression, it's the first use, so we freeze it.
                substV("___.primFreeze(@result);",
                    "result", result);
          }
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // map - object literals
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("mapEmpty", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("({})", node, bindings)) {
          return node.clone();
        }
        return NONE;
      }
    });

    addRule(new Rule("mapBadKeySuffix", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("({@keys*: @vals*})", node, bindings) &&
            literalsEndWith(bindings.get("keys"), "_")) {
          mq.addMessage(
              RewriterMessageType.KEY_MAY_NOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    });

    addRule(new Rule("mapNonEmpty", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("({@keys*: @vals*})", node, bindings)) {
          return substV(
              "({ @keys*: @vals* })",
              "keys", bindings.get("keys"),
              "vals", expand(bindings.get("vals"), scope, mq));
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // multiDeclaration - multiple declarations
    ////////////////////////////////////////////////////////////////////////

    // TODO(ihab.awad): The 'multiDeclaration' implementation is hard
    // to follow or maintain. Refactor asap.
    addRule(new Rule("multiDeclaration", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof MultiDeclaration) {
          boolean allDeclarations = true;
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();

          // Expand each declaration individually, and keep track of whether
          // the result is a declaration or whether we can just run the
          // initializers separately.
          for (ParseTreeNode child : node.children()) {
            ParseTreeNode result = expand(child, scope, mq);
            if (result instanceof ExpressionStmt) {
              result = result.children().get(0);
            } else if (!(result instanceof Expression
                         || result instanceof Declaration)) {
              throw new RuntimeException(
                  "Unexpected result class: " + result.getClass());
            }
            expanded.add(result);
            allDeclarations &= result instanceof Declaration;
          }

          // If they're not all declarations, then split the initializers out
          // so that we can run them in order.
          if (!allDeclarations) {
            List<Declaration> declarations = new ArrayList<Declaration>();
            List<Expression> initializers = new ArrayList<Expression>();
            for (ParseTreeNode n : expanded) {
              if (n instanceof Declaration) {
                Declaration decl = (Declaration) n;
                Expression init = decl.getInitializer();
                if (init != null) {
                  initializers.add(init);
                  decl.removeChild(init);
                }
                declarations.add(decl);
              } else {
                initializers.add((Expression) n);
              }
            }
            Expression[] initOperands = initializers.toArray(new Expression[0]);
            Expression init = (initOperands.length > 1
                               ? Operation.create(Operator.COMMA, initOperands)
                               : initOperands[0]);
            if (declarations.isEmpty()) {
              return new ExpressionStmt(init);
            } else {
              return substV(
                  "{ @decl; @init; }",
                  "decl", new MultiDeclaration(declarations),
                  "init", new ExpressionStmt(init));
            }
          } else {
            return ParseTreeNodes.newNodeInstance(
                MultiDeclaration.class, null, expanded);
          }
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // other - things not otherwise covered
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("otherInstanceof", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("@o instanceof @f", node, bindings)) {
          return substV(
              "@o instanceof @f",
              "o", expand(bindings.get("o"), scope, mq),
              "f", expand(bindings.get("f"), scope, mq));
        }
        return NONE;
      }
    });

    addRule(new Rule("otherTypeof", this) {
      @Override
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (match("typeof @f", node, bindings)) {
          ParseTreeNode f = bindings.get("f");
          if (f instanceof Reference && scope.isGlobal(getReferenceName(f))) {
            // Lookup of an undefined&undeclared global for typing purposes
            // should not fail with an exception.
            expand(f, scope, mq);
            return substV(
                "typeof ___.readPub(___OUTERS___, @fname)",
                "fname", toStringLiteral(f));
          } else {
            return substV(
                "typeof @f",
                "f", expand(f, scope, mq));
          }
        }
        return NONE;
      }
    });

    addRule(new Rule("otherSpecialOp", this) {
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (!(node instanceof SpecialOperation)) { return NONE; }
        switch (((SpecialOperation) node).getOperator()) {
          case COMMA: case VOID:
            return expandAll(node, scope, mq);
          default:
            return NONE;
        }
      }
    });

    addRule(new Rule("labeledStatement", this) {
      @Override
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof LabeledStmtWrapper) {
          LabeledStmtWrapper lsw = (LabeledStmtWrapper) node;
          if (lsw.getLabel().endsWith("__")) {
            mq.addMessage(
                RewriterMessageType.LABELS_CANNOT_END_IN_DOUBLE_UNDERSCORE,
                node.getFilePosition(),
                MessagePart.Factory.valueOf(lsw.getLabel()));
          }
          LabeledStmtWrapper expanded = new LabeledStmtWrapper(
              lsw.getLabel(), (Statement) expand(lsw.getBody(), scope, mq));
          expanded.setFilePosition(lsw.getFilePosition());
          return expanded;
        }
        return NONE;
      }
    });

    ////////////////////////////////////////////////////////////////////////
    // recurse - automatically recurse into some structures
    ////////////////////////////////////////////////////////////////////////

    addRule(new Rule("recurse", this) {
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof ParseTreeNodeContainer ||
            node instanceof ArrayConstructor ||
            node instanceof BreakStmt ||
            node instanceof Block ||
            node instanceof CaseStmt ||
            node instanceof Conditional ||
            node instanceof ContinueStmt ||
            node instanceof DefaultCaseStmt ||
            node instanceof ExpressionStmt ||
            node instanceof Identifier ||
            node instanceof Literal ||
            node instanceof Loop ||
            node instanceof MultiDeclaration ||
            node instanceof Noop ||
            node instanceof SimpleOperation ||
            node instanceof ControlOperation ||
            node instanceof ReturnStmt ||
            node instanceof SwitchStmt ||
            node instanceof ThrowStmt) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    });
  }
}
