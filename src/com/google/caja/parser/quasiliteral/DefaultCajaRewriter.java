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
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.ParseTreeNodes;
import com.google.caja.parser.js.AssignOperation;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.BreakStmt;
import com.google.caja.parser.js.CaseStmt;
import com.google.caja.parser.js.Conditional;
import com.google.caja.parser.js.ContinueStmt;
import com.google.caja.parser.js.ControlOperation;
import com.google.caja.parser.js.DebuggerStmt;
import com.google.caja.parser.js.Declaration;
import com.google.caja.parser.js.DefaultCaseStmt;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FormalParam;
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
import com.google.caja.parser.js.RegexpLiteral;
import com.google.caja.parser.js.ReturnStmt;
import com.google.caja.parser.js.SimpleOperation;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.SwitchStmt;
import com.google.caja.parser.js.SyntheticNodes;
import com.google.caja.parser.js.ThrowStmt;
import com.google.caja.parser.js.TryStmt;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.ArrayConstructor;
import com.google.caja.util.Pair;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;

import static com.google.caja.parser.js.SyntheticNodes.s;
import static com.google.caja.parser.quasiliteral.QuasiBuilder.substV;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites a JavaScript parse tree to comply with default Caja rules.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
@RulesetDescription(
    name="Caja Transformation Rules",
    synopsis="Default set of transformations used by Caja"
  )
public class DefaultCajaRewriter extends Rewriter {

  // A NOTE ABOUT MATCHING MEMBER ACCESS EXPRESSIONS
  // When we match the pattern like '@x.@y' or '@x.@y()' against a specimen,
  // the result is that 'y' is bound to the rightmost component, and 'x' is
  // the remaining sub-expression on the left. Thus the result of matching
  //     @x.@y, @x.@y(), @x.@y(arg), @x.@y(args*), ...
  // is that 'y' is always bound to a Reference.

  final public Rule[] cajaRules = {
    new Rule() {
      @Override
      @RuleDescription(
          name="module",
          synopsis="Disallow top-level \"this\". Import free variables.",
          reason="In Caja, \"this\" may only be bound to an object when within "
              + "the object's encapsulation boundary. At top-level level, "
              + "\"this\" would be bound to the provided imports object, but "
              + "the module is outside that object's encapsulation boundary.",
          matches="{@ss*;}",
          substitutes="@importedvars*; @startStmts*; @expanded*;")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block && scope == null) {
          Scope s2 = Scope.fromProgram((Block) node, mq);
          if (s2.hasFreeThis()) {
            mq.addMessage(
                RewriterMessageType.THIS_IN_GLOBAL_CONTEXT,
                node.getFilePosition());
          }
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          for (ParseTreeNode c : node.children()) {
            expanded.add(expand(c, s2, mq));
          }
          List<ParseTreeNode> importedVars = new ArrayList<ParseTreeNode>();
          for (String k : s2.getImportedVariables()) {
            importedVars.add(
                QuasiBuilder.substV(
                    "var @vIdent = ___.readImport(IMPORTS___, @vName);",
                    "vIdent", s(new Identifier(k)),
                    "vName", toStringLiteral(new Identifier(k)))
            );
          }

          return substV(
              "@importedvars*; @startStmts*; @expanded*;",
              "importedvars", new ParseTreeNodeContainer(importedVars),
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "expanded", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // Do nothing if the node is already the result of some translation
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticReferemce",
          synopsis="Pass through calls where the method name is synthetic.",
          reason="A synthetic method may not be marked callable.",
          matches="/* synthetic */ @ref",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Reference) {
          Reference ref = (Reference) node;
          if (isSynthetic(ref.getIdentifier())) {
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticCalls",
          synopsis="Pass through calls where the method name is synthetic.",
          reason="A synthetic method may not be marked callable.",
          matches="/* synthetic */ @o.@m(@as*)",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && isSynthetic((Reference) bindings.get("m"))) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticDeletes",
          synopsis="Pass through reads of synthetic members.",
          reason="A synthetic member may not be marked deletable.",
          matches="/* synthetic */ delete @o.@m",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && isSynthetic((Reference) bindings.get("m"))) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticReads",
          synopsis="Pass through reads of synthetic members.",
          reason="A synthetic member may not be marked readable.",
          matches="/* synthetic */ @o.@m",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && isSynthetic((Reference) bindings.get("m"))) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticSetMember",
          synopsis="Pass through sets of synthetic members.",
          reason="A synthetic member may not be marked writable.",
          matches="/* synthetic */ @o.@m = @v",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && isSynthetic((Reference) bindings.get("m"))) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticSetVar",
          synopsis="Pass through set of synthetic vars.",
          reason="A local variable might not be mentionable otherwise.",
          matches="/* synthetic */ @lhs___ = @rhs",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          if (isSynthetic((Identifier) bindings.get("lhs"))) {
            return expandAll(node, scope, mq);
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticDeclaration",
          synopsis="Pass through synthetic variables which are unmentionable.",
          reason="Synthetic code might need local variables for safe-keeping.",
          matches="/* synthetic */ var @v___ = @initial?;",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && isSynthetic((Identifier) bindings.get("v"))) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticFnDeclaration",
          synopsis="Allow declaration of synthetic functions.",
          reason="Synthetic functions allow generated code to avoid introducing"
              + " unnecessary scopes.",
          matches="/* synthetic */ function @i?(@actuals*) { @body* }",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        FunctionConstructor ctor = node instanceof FunctionDeclaration
            ? ((FunctionDeclaration) node).getInitializer()
            : (FunctionConstructor) node;
        if (isSynthetic(ctor)) {
          return expandAll(node, scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticCatches1",
          synopsis="Pass through synthetic variables which are unmentionable.",
          reason="Catching unmentionable exceptions helps maintain invariants.",
          matches=(
              "try { @body* } catch (/* synthetic */ @ex___) { @handler*; }"),
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Declaration ex = (Declaration) bindings.get("ex");
          if (isSynthetic(ex.getIdentifier())) {
            return expandAll(node, scope, mq);
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticCatches2",
          synopsis="Pass through synthetic variables which are unmentionable.",
          reason="Catching unmentionable exceptions helps maintain invariants.",
          matches=(
               "try { @body* } catch (/* synthetic */ @ex___) { @handler*; }"
               + " finally { @cleanup* }"),
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Declaration ex = (Declaration) bindings.get("ex");
          if (isSynthetic(ex.getIdentifier())) {
            return expandAll(node, scope, mq);
          }
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // Support hoisting of functions to the top of their containing block
    ////////////////////////////////////////////////////////////////////////
    new Rule() {
      @Override
      @RuleDescription(
          name="block",
          synopsis="Initialize named functions at the beginning of their "
              + "enclosing block.",
          reason="Nested named function declarations are illegal in ES3 but are "
              + "universally supported by all JavaScript implementations, "
              + "though in different ways. The compromise semantics currently "
              + "supported by Caja is to hoist the declaration of a variable "
              + "with the function's name to the beginning of the enclosing "
              + "function body or module top level, and to initialize this "
              + "variable to a new anonymous function every time control "
              + "re-enters the enclosing block.\n"
              + "Note that ES3.1 and ES4 specify a better and safer semantics "
              + "-- block level lexical scoping -- that we'd like to adopt into "
              + "Caja eventually. However, it so challenging to implement this "
              + "semantics by translation to currently-implemented JavaScript "
              + "that we provide something quicker and dirtier for now.",
          matches="{@ss*;}",
          substitutes="@startStmts*; @ss*;")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          Scope s2 = Scope.fromPlainBlock(scope);
          for (ParseTreeNode c : node.children()) {
            expanded.add(expand(c, s2, mq));
          }
          return substV(
              "@startStmts*; @ss*;",
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "ss", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // with - disallow the 'with' construct
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="with",
          synopsis="Statically reject if a `with` block is found.",
          reason="`with` violates the assumptions made by Scope, and makes it "
              + "very hard to write a Scope that works. "
              + "http://yuiblog.com/blog/2006/04/11/with-statement-considered-harmful/ "
              + "briefly touches on why `with` is bad for programmers. For "
              + "reviewers -- matching of references with declarations can only "
              + "be done at runtime. All other secure JS subsets that we know "
              + "of (ADSafe, Jacaranda, & FBJS) also disallow `with`.",
          matches="with (@scope) @body;",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.WITH_BLOCKS_NOT_ALLOWED,
              node.getFilePosition());
          return node;
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // foreach - "for ... in" loops
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="foreachBadFreeVariable",
          synopsis="Do not allow a for-in to assign to an imported variable.",
          reason="We do not allow assignments to imports anywhere.",
          matches="for (@k in @o) @ss;",
          substitutes="")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && bindings.get("k") instanceof ExpressionStmt) {
          ExpressionStmt es = (ExpressionStmt) bindings.get("k");
          if (es.getExpression() instanceof Reference
              && scope.isImported(getReferenceName(es.getExpression()))) {
            mq.addMessage(
                RewriterMessageType.CANNOT_ASSIGN_TO_FREE_VARIABLE,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="foreach",
          synopsis="Only enumerate Caja-visible and enumerable property names. "
              + "A for-in on \"this\" will see pubic and protected property "
              + "names. Otherwise, only public property names.",
          reason="To enumerate any other property names would be to violate the "
              + "object's encapsulation, leak internals of the Caja "
              + "implementation, or violate taming decisions of what should be "
              + "visible.",
          matches="for (var @k in @o) @ss;",
          substitutes="<approx> for (@k in @o) { if (___.@canEnum(@o,@k)) @ss")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);

        if (bindings != null) {
          scope.addStartOfScopeStatement((Statement) substV(
              "var @k;",
              "k", bindings.get("k")));
          bindings.put("k", new Reference((Identifier) bindings.get("k")));
        } else {
          bindings = makeBindings();
          if (QuasiBuilder.match("for (@k in @o) @ss;", node, bindings)) {
            ExpressionStmt es = (ExpressionStmt) bindings.get("k");
            bindings.put("k", es.getExpression());
          } else {
            return NONE;
          }
        }

        List<Statement> declsList = new ArrayList<Statement>();

        Identifier oTemp = scope.declareStartOfScopeTempVariable();
        declsList.add(new ExpressionStmt((Expression) substV(
            "@oTemp = @o;",
            "oTemp", new Reference(oTemp),
            "o", expand(bindings.get("o"), scope, mq))));

        Identifier kTemp = scope.declareStartOfScopeTempVariable();

        ParseTreeNode kAssignment = substV(
            "@k = @kTempRef;",
            "k", bindings.get("k"),
            "kTempRef", new Reference(kTemp));
        kAssignment.getAttributes().remove(SyntheticNodes.SYNTHETIC);
        kAssignment = expand(kAssignment, scope, mq);
        kAssignment = new ExpressionStmt((Expression) kAssignment);

        String canEnumName = QuasiBuilder.match("this", bindings.get("o")) ?
            "canEnumProp" : "canEnumPub";
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
            "oTempRef", new Reference(oTemp),
            "kTempRef", new Reference(kTemp),
            "kTempStmt", new ExpressionStmt(new Reference(kTemp)),
            "kAssignment", kAssignment,
            "ss", expand(bindings.get("ss"), scope, mq));
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // try - try/catch/finally constructs
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="tryCatch",
          synopsis="Ensure that only immutable data is thrown, and repair scope "
              + "confusion in existing JavaScript implementations of "
              + "try/catch.",
          reason="When manually reviewing code for vulnerability, experience "
              + "shows that reviewers cannot pay adequate attention to the "
              + "pervasive possibility of thrown exceptions. These lead to four "
              + "dangers: 1) leaking an authority-bearing object, endangering "
              + "integrity, 2) leaking a secret, endangering secrecy, and 3) "
              + "aborting a partially completed state update, leaving the state "
              + "malformed, endangering integrity, and 4) preventing an "
              + "operation that was needed, endangering availability. Caja only "
              + "seeks to make strong claims about integrity. By ensuring that "
              + "only immutable (transitively frozen) data is thrown, we "
              + "prevent problem #1. For the others, programmer vigilance is "
              + "still needed. \n"
              + "Current JavaScript implementations fail, in different ways, to "
              + "implement the scoping of the catch variable specified in ES3. "
              + "We translate Caja to JavaScript so as to implement the ES3 "
              + "specified scoping on current JavaScript implementations.",
          matches="try { @s0*; } catch (@x) { @s1*; }",
          substitutes="try {\n"
              + "  @s0*;\n"
              + "} catch (ex___) {\n"
              + "  try {\n"
              + "    throw ___.tameException(ex___); \n"
              + "  } catch (@x) {\n"
              + "    @s1*;\n"
              + "  }\n"
              + "}")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          TryStmt t = (TryStmt) node;
          Identifier exceptionName = t.getCatchClause().getException()
              .getIdentifier();
          if (exceptionName.getName().endsWith("__")) {
            mq.addMessage(
                RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
                node.getFilePosition(), this, node);
            return node;
          }
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
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="tryCatchFinally",
          synopsis="Finally adds no special issues beyond those explained in "
              + "try/catch.",
          reason="Caja is not attempting to impose determinism, so the reasons "
              + "for Joe-E to avoid finally do not apply.",
          matches="try { @s0*; } catch (@x) { @s1*; } finally { @s2*; }",
          substitutes="try {\n"
              + "  @s0*;\n"
              + "} catch (ex___) {\n"
              + "  try {\n"
              + "    throw ___.tameException(ex___);\n"
              + "  } catch (@x) {\n"
              + "    @s1*;\n"
              + "  }\n"
              + "} finally {\n"
              + "  @s2*;\n"
              + "}")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          TryStmt t = (TryStmt) node;
          Identifier exceptionName = t.getCatchClause().getException()
              .getIdentifier();
          if (exceptionName.getName().endsWith("__")) {
            mq.addMessage(
                RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
                node.getFilePosition(), this, node);
            return node;
          }
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
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="tryFinally",
          synopsis="See bug 383. Otherwise, it's just the trivial translation.",
          reason="try/finally actually seems to work as needed by current "
              + "JavaScript implementations.",
          matches="try { @s0*; } finally { @s1*; }",
          substitutes="try { @s0*; } finally { @s1*; }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
            "try { @s0*; } finally { @s1*; }",
            "s0",  expandAll(bindings.get("s0"), scope, mq),
            "s1",  expandAll(bindings.get("s1"), scope, mq));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // variable - variable name handling
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="varArgs",
          synopsis="Make all references to the magic \"arguments\" variable "
              + "into references to a frozen array containing a snapshot of the "
              + "actual arguments taken when the function was first entered.",
          reason="ES3 specifies that the magic \"arguments\" variable is a "
              + "dynamic (\"joined\") mutable array-like reflection of the "
              + "values of the parameter variables. However, te typical usage "
              + "is to pass it to provide access to one's original arguments -- "
              + "without the intention of providing the ability to mutate the "
              + "caller's parameter variables. By making a frozen array "
              + "snapshot with no \"callee\" property, we provide the least "
              + "authority assumed by this typical use.\n"
              + "The snapshot is made with a \"var a___ = "
              + "___.args(arguments);\" generated at the beginning of the "
              + "function body.",
          matches="arguments",
          substitutes="a___")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return subst(bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varThis",
          synopsis="Translates all occurrences of \"this\" to \"t___\".",
          reason="The translation is able to worry less about the complex "
              + "scoping rules of \"this\".\n"
              + "In a function mentioning \"this\", a \"var t___ = this;\" is "
              + "generated at the beginning of the function body.",
          matches="this",
          substitutes="t___")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return subst(bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varBadSuffix",
          synopsis="Statically reject if a variable with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="@v__",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varBadSuffixDeclaration",
          synopsis="Statically reject if a variable with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="<approx>(var|function) @v__ ...",  // TODO(mikesamuel): limit
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Declaration) {
          Identifier name = ((Declaration) node).getIdentifier();
          if (name.getValue().endsWith("__")) {
            mq.addMessage(
                RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varBadImportSuffix",
          synopsis="Statically reject if an imported variable with `_` suffix "
              + "is found",
          reason="A module is outside the encapsulation boundary of its imports "
              + "object, and so cannot address any of that object's protected "
              + "properties.",
          matches="@import_",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          String symbol = ((Identifier) bindings.get("import")).getValue() + "_";
          if (scope.isImported(symbol)) {
            mq.addMessage(
                RewriterMessageType.IMPORTED_SYMBOLS_CANNOT_END_IN_UNDERSCORE,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varFuncFreeze",
          synopsis="An escaping occurence of a function name freezes the "
              + "function.",
          reason="By adopting this static rule, we only need to generate "
              + "freezes for names that are statically known to be function "
              + "names, rather than freezing at every potential point of use.",
          matches="@fname",  // TODO(mikesamuel): limit further
          substitutes="___.primFreeze(@fname)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && bindings.get("fname") instanceof Reference) {
          String name = getReferenceName(bindings.get("fname"));
          // TODO(erights) Does this only need to check for isDeclaredFunction?
          if (scope.isFunction(name)) {
            return substV(
                "___.primFreeze(@fname)",
                "fname", bindings.get("fname"));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="varDefault",
          synopsis="Any remaining uses of a variable name are preserved.",
          reason="",
          matches="@v",  // TODO(mikesamuel): limit further
          substitutes="@v")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && bindings.get("v") instanceof Reference) {
          return bindings.get("v");
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // read - reading properties
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="readBadSuffix",
          synopsis="Statically reject if a property has `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="@x.@p__",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readInternal",
          synopsis="Read a public or protected property.",
          reason="Since it is addressed from \"this.\", Caja assumes we are "
              + "inside the encapsulation boundary of the object bound to "
              + "\"this\", and so its protected properties should be "
              + "accessible.",
          matches="this.@p",
          substitutes="<approx> ___.readProp(t___, @'p')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
            "t___.@fp ? t___.@p : ___.readProp(t___, @rp)",
            "p",  p,
            "fp", newReference(propertyName + "_canRead___"),
            "rp", toStringLiteral(p));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readBadInternal",
          synopsis="Statically reject public reading of a property ending with "
              + "'_'.",
          reason="Caja defines variable with a `_` suffix as protected.",
          matches="@x.@p_",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_PROPERTIES_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readPublic",
          synopsis="",
          reason="",
          matches="@o.@p",
          substitutes="<approx> ___.readPub(@o, @'p')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
              "@ref = @o, ("
              + "    @ref.@fp"
              + "    ? @ref.@p"
              + "    : ___.readPub(@ref, @rp))",
              "ref", new Reference(scope.declareStartOfScopeTempVariable()),
              "o", expand(bindings.get("o"), scope, mq),
              "p",  p,
              "fp", newReference(propertyName + "_canRead___"),
              "rp", toStringLiteral(p));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readIndexInternal",
          synopsis="",
          reason="",
          matches="this[@s]",
          substitutes="___.readProp(t___, @s)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.readProp(t___, @s)",
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readIndexPublic",
          synopsis="",
          reason="",
          matches="@o[@s]",
          substitutes="___.readPub(@o, @s)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.readPub(@o, @s)",
              "o", expand(bindings.get("o"), scope, mq),
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // set - assignments
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadAssignToFunctionName",
          synopsis="Statically reject if an assignment expression assigns to a "
              + "function name.",
          reason="",
          matches="<approx> @fname @op?= @x", // TODO(mikesamuel): Limit further
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof AssignOperation
            && node.children().get(0) instanceof Reference
            && scope.isFunction(getReferenceName(node.children().get(0)))) {
          mq.addMessage(
              RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadThis",
          synopsis="Statically reject if an expression assigns to `this`.",
          reason="Invalid JavaScript.",
          matches="this = @z",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.CANNOT_ASSIGN_TO_THIS,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadFreeVariable",
          synopsis="Statically reject if an expression assigns to a free "
              + "variable.",
          reason="This is still controversial (see bug 375). However, the "
              + "rationale is to prevent code that's nested lexically within a "
              + "module to from introducing mutable state outside its local "
              + "function-body scope. Without this rule, two nested blocks "
              + "within the same module could communicate via a pseudo-imported "
              + "variable that is not declared or used at the outer scope of "
              + "the module body.",
          matches="@import = @y",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && bindings.get("import") instanceof Reference) {
          String name = ((Reference) bindings.get("import")).getIdentifierName();
          if (scope.isImported(name)) {
            mq.addMessage(
                RewriterMessageType.CANNOT_ASSIGN_TO_FREE_VARIABLE,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadValueOf",
          synopsis="Statically reject if assigning to valueOf.",
          reason="We depend on valueOf returning consistent results.",
          matches="@x.valueOf = @z",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VALUEOF_PROPERTY_MUST_NOT_BE_SET,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadSuffix",
          synopsis="Statically reject if a property with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="@x.@p__ = @z",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setInternal",
          synopsis="Set or create a public or protected property.",
          reason="We allow methods and constructors within a constructed object "
              + "to create new properties on itself directly by assignment.",
          matches="this.@p = @r",
          substitutes="<approx> ___.setProp(t___, @'p', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          String propertyName = ((Reference) bindings.get("p")).getIdentifierName();
          return substV(
              "@ref = @r," +
              "@target.@fp ?" +
              "  (@target.@p = @ref) :" +
              "  ___.setProp(@target, @rp, @ref);",
              "ref", new Reference(scope.declareStartOfScopeTempVariable()),
              "r",  expand(bindings.get("r"), scope, mq),
              "p",  bindings.get("p"),
              "fp", newReference(propertyName + "_canSet___"),
              "rp", toStringLiteral(bindings.get("p")),
              "target", newReference(ReservedNames.LOCAL_THIS));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setMember",
          synopsis="Initialize a member of the prototypical object associated "
              + "with a constructor or named function, to be inherited by the "
              + "instances of that function.",
          reason="The right hand side of this rule is a \"method context\" -- a "
              + "position in which Caja methods can appear. This allows "
              + "unattached methods to be stored in the prototypical object, "
              + "which is necessary for allowing instances to share these. "
              + "However, any attempt to obtain access to a method as a value "
              + "will obtain at most an attached method.",
          matches="@df.prototype.@p = @m",
          substitutes="___.setMember(@df, @'p', @m)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        // BUG TODO(erights,ihab): We must only recognize (and thus allow) this
        // expression when it is evaluated for effects only, not for value.
        // Currently, since we have no such test, the translated expression will
        // safely evaluate to <tt>undefined</tt>, but this behavior is not within
        // a fail-stop subset of JavaScript.
        if (bindings != null) {
          ParseTreeNode clazz = bindings.get("df");
          if (clazz instanceof Reference) {
            String className = getReferenceName(clazz);
            if (scope.isDeclaredFunction(className)) {
              Reference p = (Reference) bindings.get("p");
              return substV(
                  "___.setMember(@df, @rp, @m);",
                  "df", clazz,
                  "m", expandMember(bindings.get("m"), scope, mq),
                  "rp", toStringLiteral(p));
            }
          } else {
            // TODO(mikesamuel): make constructors first class for the purpose
            // of defining members.
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadInternal",
          synopsis="Cannot publicly access a property ending with '_'.",
          reason="Caja defines variable with a `_` suffix as protected.",
          matches="@x.@y_ = @z",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_PROPERTIES_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setStatic",
          synopsis="Initialize the direct properties (static members) of a "
              + "potentially-mutable constructor or named function.",
          reason="",
          matches="@fname.@p = @r",
          substitutes="___.setStatic(@fname, @'p', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && bindings.get("fname") instanceof Reference) {
          Reference fname = (Reference) bindings.get("fname");
          Reference p = (Reference) bindings.get("p");
          // TODO(erights) Does this only need to check isDeclaredFunction?
          if (scope.isFunction(getReferenceName(fname))) {
            return substV(
                "___.setStatic(@fname, @rp, @r)",
                "fname", fname,
                "rp", toStringLiteral(p),
                "r", expand(bindings.get("r"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setPublic",
          synopsis="Set a public property.",
          reason="If the object is an unfrozen JSONContainer (a record or "
              + "array), then this will create the own property if needed. If "
              + "it is an unfrozen constructed object, then clients can assign "
              + "to existing public own properties, but cannot directly create "
              + "such properties.",
          matches="@o.@p = @r",
          substitutes="<approx> ___.setPub(@o, @'p', @r);")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          String propertyName = p.getIdentifierName();
          return substV(
              "@tmpO = @expandO," +
              "@tmpR = @expandR," +
              "@tmpO.@pCanSet ?" +
              "    (@tmpO.@p = @tmpR) :" +
              "    ___.setPub(@tmpO, @pName, @tmpR);",
              "tmpO", new Reference(scope.declareStartOfScopeTempVariable()),
              "tmpR", new Reference(scope.declareStartOfScopeTempVariable()),
              "expandO", expand(bindings.get("o"), scope, mq),
              "expandR", expand(bindings.get("r"), scope, mq),
              "pCanSet", newReference(propertyName + "_canSet___"),
              "p", p,
              "pName", toStringLiteral(p));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setIndexInternal",
          synopsis="",
          reason="",
          matches="this[@s] = @r",
          substitutes="___.setProp(t___, @s, @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.setProp(t___, @s, @r)",
              "s", expand(bindings.get("s"), scope, mq),
              "r", expand(bindings.get("r"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setIndexPublic",
          synopsis="",
          reason="",
          matches="@o[@s] = @r",
          substitutes="___.setPub(@o, @s, @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.setPub(@o, @s, @r)",
              "o", expand(bindings.get("o"), scope, mq),
              "s", expand(bindings.get("s"), scope, mq),
              "r", expand(bindings.get("r"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadInitialize",
          synopsis="Statically reject if a variable with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="var @v__ = @r",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setInitialize",
          synopsis="Ensure v is not a function name. Expand the right side.",
          reason="",
          matches="var @v = @r",
          substitutes="var @v = @r")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null
            && !scope.isFunction(getIdentifierName(bindings.get("v")))) {
          return substV(
              "var @v = @r",
              "v", bindings.get("v"),
              "r", expand(bindings.get("r"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setBadDeclare",
          synopsis="Statically reject if a variable with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="var @v__",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setDeclare",
          synopsis="Ensure that v isn't a function name.",
          reason="",
          matches="var @v",
          substitutes="var @v")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null
            && !scope.isFunction(getIdentifierName(bindings.get("v")))) {
          return node;
        }
        return NONE;
      }
    },

    // TODO(erights): Need a general way to expand lValues
    new Rule() {
      @Override
      @RuleDescription(
          name="setBadVar",
          synopsis="Statically reject if a variable with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="@v__ = @r",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    // TODO(erights): Need a general way to expand lValues
    new Rule() {
      @Override
      @RuleDescription(
          name="setVar",
          synopsis="Only if v isn't a function name.",
          reason="",
          matches="@v = @r",
          substitutes="@v = @r")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          ParseTreeNode v = bindings.get("v");
          if (v instanceof Reference) {
            if (!scope.isFunction(getReferenceName(v))) {
              return substV(
                  "@v = @r",
                  "v", v,
                  "r", expand(bindings.get("r"), scope, mq));
            }
          }
        }
        return NONE;
      }
    },

    // TODO(erights): Need a general way to expand readModifyWrite lValues.
    // For now, we're just picking off a few common special cases as they
    // come up.

    new Rule() {
      @Override
      @RuleDescription(
          name="setReadModifyWriteLocalVar",
          synopsis="",
          reason="",
          matches="@x @op= @y",  // TODO(mikesamuel): better lower limit
          substitutes="<approx> @x = @x @op @y")
      // Handle x += 3 and similar ops by rewriting them using the assignment
      // delegate, "x += y" => "x = x + y", with deconstructReadAssignOperand
      // assuring that x is evaluated at most once where that matters.
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof AssignOperation) {
          AssignOperation aNode = (AssignOperation) node;
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
            return substV(
                "@tmps, @assign",
                "tmps", newCommaOperation(ops.getTemporaries()),
                "assign", assignment);
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setIncrDecr",
          synopsis="Handle pre and post ++ and --.",
          // TODO(mikesamuel): better lower bound
          matches="<approx> ++@x but any {pre,post}{in,de}crement will do",
          reason="")
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
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = ops.makeAssignment((Expression) substV(
                  "@tmpVal + 1",
                  "tmpVal", tmpVal));
              return substV(
                  "  @tmps,"
                  + "@tmpVal = @rvalue - 0,"  // Coerce to a number.
                  + "@assign,"  // Assign value.
                  + "@tmpVal",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "tmpVal", tmpVal,
                  "rvalue", ops.getRValue(),
                  "assign", assign);
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
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", ops.makeAssignment((Expression)
                      substV("@rvalue - -1", "rvalue", ops.getRValue())));
            }
          case POST_DECREMENT:
            if (ops.isSimpleLValue()) {
              return substV("@v--", "v", ops.getRValue());
            } else {
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = ops.makeAssignment((Expression) substV(
                  "@tmpVal - 1",
                  "tmpVal", tmpVal));
              return substV(
                  "  @tmps,"
                  + "@tmpVal = @rvalue - 0,"  // Coerce to a number.
                  + "@assign,"  // Assign value.
                  + "@tmpVal;",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "tmpVal", tmpVal,
                  "rvalue", ops.getRValue(),
                  "assign", assign);
            }
          case PRE_DECREMENT:
            if (ops.isSimpleLValue()) {
              return substV("--@v", "v", ops.getRValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return ops.makeAssignment((Expression)
                  substV("@rvalue - 1", "rvalue", ops.getRValue()));
            } else {
              return substV(
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", ops.makeAssignment((Expression)
                      substV("@rvalue - 1", "rvalue", ops.getRValue())));
            }
          default:
            return NONE;
        }
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // new - new object creation
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="newCalllessCtor",
          synopsis="Add missing empty argument list.",
          reason="JavaScript syntax allows constructor calls without \"()\".",
          matches="new @ctor",
          substitutes="new @ctor()")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return expand(
              Operation.create(Operator.FUNCTION_CALL, (Expression) node),
              scope, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="newCtor",
          synopsis="",
          reason="",
          matches="new @ctor(@as*)",
          substitutes="new (___.asCtor(@ctor))(@as*)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          ParseTreeNode ctor = bindings.get("ctor");
          return substV(
              "new (___.asCtor(@ctor))(@as*)",
              "ctor", expand(ctor, scope, mq),
              "as", expandAll(bindings.get("as"), scope, mq));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // delete - property deletion
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteBadValueOf",
          synopsis="Prohibit deletion of valueOf.",
          reason="Although a non-existent valueOf should behave the same way "
              + "asthe default one as regards [[DefaultValue]], for simplicity "
              + "weonly want to have to consider one of those cases.",
          matches="delete @o.valueOf",
          substitutes="<reject>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.VALUEOF_PROPERTY_MUST_NOT_BE_DELETED,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteBadSuffix",
          synopsis="",
          reason="",
          matches="delete @o.@p__",
          substitutes="<reject>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PROPERTIES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteInternal",
          synopsis="",
          reason="",
          matches="delete this.@p",
          substitutes="___.deleteProp(t___, @'p')")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.deleteProp(t___, @pname)",
              "pname", toStringLiteral(bindings.get("p")));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteBadInternal",
          synopsis="",
          reason="",
          matches="delete @o.@p_",
          substitutes="<reject>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_PROPERTIES_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deletePublic",
          synopsis="",
          reason="",
          matches="delete @o.@p",
          substitutes="___.deletePub(@o, @'p')")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "___.deletePub(@o, @pname)",
              "o", expand(bindings.get("o"), scope, mq),
              "pname", toStringLiteral(p));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteIndexInternal",
          synopsis="",
          reason="",
          matches="delete this[@s]",
          substitutes="___.deleteProp(t___, @s)")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.deleteProp(t___, @s)",
              "s", expand(bindings.get("s"), scope, mq)
              );
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteIndexPublic",
          synopsis="",
          reason="",
          matches="delete @o[@s]",
          substitutes="___.deletePub(@o, @s)")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.deletePub(@o, @s)",
              "o", expand(bindings.get("o"), scope, mq),
              "s", expand(bindings.get("s"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteNonProperty",
          synopsis="",
          reason="",
          matches="delete @v",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.NOT_DELETABLE, node.getFilePosition());
          return node;
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // call - function calls
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="callBadSuffix",
          synopsis="Statically reject if a selector with `__` suffix is found.",
          reason="Caja reserves the `__` suffix for internal use.",
          matches="@o.@p__(@as*)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.SELECTORS_CANNOT_END_IN_DOUBLE_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callInternal",
          synopsis="",
          reason="",
          matches="this.@p(@as*)",
          substitutes="<approx> ___.callProp(t___, @'p', [@as*]")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Pair<ParseTreeNode, ParseTreeNode> aliases =
              reuseAll(bindings.get("as"), scope, mq);
          Reference p = (Reference) bindings.get("p");
          String methodName = p.getIdentifierName();
          return substV(
              "@as," +
              "t___.@fp ? t___.@p(@vs*) : ___.callProp(t___, @pname, [@vs*])",
              "as", newCommaOperation(aliases.b.children()),
              "vs", aliases.a,
              "p",  bindings.get("p"),
              "fp", newReference(methodName + "_canCall___"),
              "pname", toStringLiteral(p));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callBadInternal",
          synopsis="Statically reject if a public selector with `_` suffix is "
              + "found.",
          reason="Caja defines selectors with a `_` as private.",
          matches="@o.@s_(@as*)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.PUBLIC_SELECTORS_CANNOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef2",
          synopsis="Declares that the first argument acts as a derived "
              + "constructor inheriting from the second.",
          reason="Sets up a well formed prototype inheritance chain between "
              + "these two functions. The first argument must be a declared "
              + "function name. Calling caja.def() on it does not freeze it.",
          matches="caja.def(@fname, @base)",
          substitutes="caja.def(@fname, @base)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          ParseTreeNode fname = bindings.get("fname");
          if (fname instanceof Reference
              && scope.isDeclaredFunction(getReferenceName(fname))) {
            return substV(
                "caja.def(@fname, @base)",
                "fname", fname,
                "base", expand(bindings.get("base"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef2BadFunction",
          synopsis="Reject calls to caja.def() on names of functions statically "
              + "known to be frozen.",
          reason="Within a function foo(), foo must already be frozen, so it is "
              + "too late to initialize it.",
          matches="caja.def(@fname, @base)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          ParseTreeNode fname = bindings.get("fname");
          if (fname instanceof Reference
              && scope.isFunction(getReferenceName(fname))) {
            mq.addMessage(
                RewriterMessageType.CAJA_DEF_ON_FROZEN_FUNCTION,
                node.getFilePosition(), this, node);
            return node;
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef2Bad",
          synopsis="Reject other calls to caja.def().",
          reason="If the first argument is not a declared function name, then "
              + "it cannot be an unfrozen function.",
          matches="caja.def(@x, @base)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.CAJA_DEF_ON_NON_FUNCTION,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef3Plus",
          synopsis="Declare an inheritance relationship, and initialize methods "
              + "and statics.",
          reason="The enumerable own properties of the third and fourth "
              + "arguments, if present are used to initialize @fname.prototype "
              + "and @fname, respectively. The third argument must statically "
              + "be an object-literal expression. The value positions of this "
              + "expression is a method context -- a position in which methods "
              + "are allowed.",
          matches="caja.def(@fname, @base, @mm, @ss?)",
          substitutes="caja.def(@fname, @base, @mm, @ss?)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null
            && bindings.get("fname") instanceof Reference
            && scope.isDeclaredFunction(getReferenceName(bindings.get("fname")))) {
          if (!checkMapExpression(bindings.get("mm"), mq)) {
            return node;
          }
          if (bindings.get("ss") != null &&
              !checkMapExpression(bindings.get("ss"), mq)) {
            return node;
          }
          ParseTreeNode ss = bindings.get("ss") == null ? null :
              expandAll(bindings.get("ss"), scope, mq);
          return substV(
              "caja.def(@fname, @base, @mm, @ss?)",
              "fname", bindings.get("fname"),
              "base", expand(bindings.get("base"), scope, mq),
              "mm", expandMemberMap(bindings.get("mm"), scope, mq),
              "ss", ss);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef3PlusBadFunction",
          synopsis="Reject initialization of a name of a function statically "
              + "known to be frozen.",
          reason="",
          matches="caja.def(@fname, @base, @mm, @ss?)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null
            && bindings.get("fname") instanceof Reference
            && scope.isFunction(getReferenceName(bindings.get("fname")))) {
          mq.addMessage(
              RewriterMessageType.CAJA_DEF_ON_FROZEN_FUNCTION,
              node.getFilePosition(), this, node);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callCajaDef3PlusBad",
          synopsis="Reject other calls to caja.def().",
          reason="",
          matches="caja.def(@x, @base, @mm, @ss?)",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          mq.addMessage(
              RewriterMessageType.CAJA_DEF_ON_NON_FUNCTION,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callFuncInlineMethodCall",
          synopsis="",
          reason="",
          matches="(function (@formals*) { @body*; }).call(this, @args*);",
          substitutes="(function (@formals*) { @fh*; @stmts*; @body*; })\n"
              + "    .call(this, @args*);")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, final MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          FunctionConstructor fc = (FunctionConstructor) node.children().get(0).children().get(0);
          Scope s2 = Scope.fromFunctionConstructor(scope, fc);
          if (s2.hasFreeThis()) {
            return substV(
                "(function (@formals*) { @fh*; @stmts*; @body*; })"
                + "    .call(this, @args*);",
                "formals", bindings.get("formals"),
                // It's important that body is expanded before computing fh and stmts.
                "body", expand(bindings.get("body"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()),
                "args", expandAll(bindings.get("args"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callFuncInlineMethodApply",
          synopsis="",
          reason="",
          matches="(function (@formals*) { @body*; }).apply(this, @arg);",
          substitutes="(function (@formals*) { @fh*; @stmts*; @body*; })\n"
              + "    .apply(this, @arg);")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, final MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          FunctionConstructor fc = (FunctionConstructor) node.children().get(0).children().get(0);
          Scope s2 = Scope.fromFunctionConstructor(scope, fc);
          if (s2.hasFreeThis()) {
            return substV(
                "(function (@formals*) { @fh*; @stmts*; @body*; })"
                + ".apply(this, @arg);",
                "formals", bindings.get("formals"),
                // It's important that body is expanded before computing fh and stmts.
                "body", expand(bindings.get("body"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()),
                "arg", expand(bindings.get("arg"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callFuncInlineMethodBind",
          synopsis="",
          reason="",
          matches="(function (@formals*) { @body*; }).bind(this, @args*);",
          substitutes="___.simpleFrozenFunc(function (@formals*) {\n"
              + "      @fh*; @stmts*; @body*;\n"
              + "    }.bind(t___, @args*));")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, final MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          FunctionConstructor fc = (FunctionConstructor) node.children().get(0).children().get(0);
          Scope s2 = Scope.fromFunctionConstructor(scope, fc);
          if (s2.hasFreeThis()) {
            return substV(""
                + "___.simpleFrozenFunc(function (@formals*) {"
                + "  @fh*; @stmts*; @body*; }.bind(t___, @args*));",
                "formals", bindings.get("formals"),
                // It's important that body is expanded before computing fh and stmts.
                "body", expand(bindings.get("body"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()),
                "args", expand(bindings.get("args"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callPublic",
          synopsis="",
          reason="",
          matches="@o.@m(@as*)",
          substitutes="<approx> ___.callPub(@o, @'m', [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Pair<ParseTreeNode, ParseTreeNode> aliases =
              reuseAll(bindings.get("as"), scope, mq);
          Reference m = (Reference) bindings.get("m");
          String methodName = m.getIdentifierName();
          return substV(
              "@oTmp = @o," +
              "@as," +
              "@oTmp.@fm ? @oTmp.@m(@vs*) : ___.callPub(@oTmp, @rm, [@vs*]);",
              "oTmp", new Reference(scope.declareStartOfScopeTempVariable()),
              "o",  expand(bindings.get("o"), scope, mq),
              "as", newCommaOperation(aliases.b.children()),
              "vs", aliases.a,
              "m",  m,
              "fm", newReference(methodName + "_canCall___"),
              "rm", toStringLiteral(m));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callIndexInternal",
          synopsis="",
          reason="",
          matches="this[@s](@as*)",
          substitutes="___.callProp(t___, @s, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          expandEntries(bindings, scope, mq);
          return QuasiBuilder.subst(
              "___.callProp(t___, @s, [@as*])", bindings
          );
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callIndexPublic",
          synopsis="",
          reason="",
          matches="@o[@s](@as*)",
          substitutes="___.callPub(@o, @s, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          expandEntries(bindings, scope, mq);
          return QuasiBuilder.subst(
              "___.callPub(@o, @s, [@as*])", bindings
          );
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callFunc",
          synopsis="",
          reason="",
          matches="@f(@as*)",
          substitutes="___.asSimpleFunc(@f)(@as*)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.asSimpleFunc(@f)(@as*)",
              "f", expand(bindings.get("f"), scope, mq),
              "as", expandAll(bindings.get("as"), scope, mq));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // function - function definitions
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="funcAnonSimple",
          synopsis="",
          reason="",
          matches="function (@ps*) { @bs*; }",
          substitutes="___.simpleFrozenFunc(\n"
              + "  function (@ps*) {\n"
              + "    @fh*;\n"
              + "    @stmts*;\n"
              + "    @bs*;\n"
              + "})")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        // Anonymous simple function constructor
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor) node);
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            return substV(
                "___.simpleFrozenFunc(" +
                "  function (@ps*) {" +
                "    @fh*;" +
                "    @stmts*;" +
                "    @bs*;" +
                "})",
                "ps", bindings.get("ps"),
                // It's important to expand bs before computing fh and stmts.
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="funcNamedSimpleDecl",
          synopsis="",
          reason="",
          matches="function @fname(@ps*) { @bs*; }",
          substitutes="@fname = ___.simpleFunc(\n"
              + "  function(@ps*) {\n"
              + "    @fh*;\n"
              + "    @stmts*;\n"
              + "    @bs*;\n"
              + "}, @'fname');")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = (
            node instanceof FunctionDeclaration)
            ? match(((FunctionDeclaration) node).getInitializer())
            : null;
        // Named simple function declaration
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              ((FunctionDeclaration) node).getInitializer());
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            Identifier fname = (Identifier) bindings.get("fname");
            scope.declareStartOfScopeVariable(fname);
            Expression expr = (Expression) substV(
                "@fname = ___.simpleFunc(" +
                "  function(@ps*) {" +
                "    @fh*;" +
                "    @stmts*;" +
                "    @bs*;" +
                "}, @rf);",
                "fname", new Reference(fname),
                "rf", toStringLiteral(fname),
                "ps", bindings.get("ps"),
                // It's important to expand bs before computing fh and stmts.
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
            scope.addStartOfBlockStatement(new ExpressionStmt(expr));
            return substV(";");
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="funcNamedSimpleValue",
          synopsis="",
          reason="",
          matches="function @fname(@ps*) { @bs*; }",
          substitutes="(function() {\n"
              + "  function @fname(@ps*) {\n"
              + "    @fh*;\n"
              + "    @stmts*;\n"
              + "    @bs*;\n"
              + "  }\n"
              + "  return ___.simpleFrozenFunc(@fname, @'fname');\n"
              + "})();")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        // Named simple function expression
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor) node);
          if (!s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            Identifier fname = (Identifier) bindings.get("fname");
            Reference fRef = new Reference(fname);
            return substV(
                "(function() {" +
                "  function @fname(@ps*) {" +
                "    @fh*;" +
                "    @stmts*;" +
                "    @bs*;" +
                "  }" +
                "  return ___.simpleFrozenFunc(@fRef, @rf);" +
                "})();",
                "fname", fname,
                "fRef", fRef,
                "rf", toStringLiteral(fname),
                "ps", bindings.get("ps"),
                // It's important to expand bs before computing fh and stmts.
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
          }
        }
        return NONE;
      }
    },

    /**
     * Rewrites an
     * - anonymous function
     * - mentioning this
     * - whose earliest function scope ancestor is NOT a constructor or method
     * into an exophoric function.
     */
    new Rule() {
      @Override
      @RuleDescription(
          name="funcXo4a",
          synopsis="Rewrites an 1) anonymous function 2) mentioning this 3) "
              + "whose earliest function scope ancestor is NOT a constructor "
              + "or method into an exophoric function.",
          wart="Prefer pseudo-xo4a declaration",
          reason="A moderately risky stepping stone to ease the conversion of "
              + "old code.",
          matches="(function (@formals*) { @body*; })",
          substitutes="<approx> ___.xo4a(function (@formals*) { @fh*; @stmts*; "
              + "@body*; })")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, final MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope, (FunctionConstructor) node);
          if (!s2.hasFreeThis()) { return NONE; }

          if (!wartsMode) {
            // TODO(ihab.awad): Make "warts mode" checks automatic based on the
            // RuleDescription
            mq.addMessage(
                RewriterMessageType.IMPLICIT_XO4A_ONLY_ALLOWED_IN_WARTS_MODE,
                node.getFilePosition(),
                this,
                node);
            // Returning NONE, rather than 'node', here to be consistent with
            // what will happen when we are automatically deactivating rules
            // based on the 'warts' flag.
            return NONE;
          }

          checkFormals(bindings.get("formals"), mq);
          // An exophoric function is one where this is only used to access the
          // public API.
          // We cajole an exophoric function by converting all `this` references
          // in the body to `t___` and then cajole the body.
          // Attempts to use private APIs, as in (this.foo_) fail statically,
          // and elsewhere, we will use (___.readPub) instead of (___.readProp).
          ParseTreeNode rewrittenBody = bindings.get("body").clone();
          rewrittenBody.acceptPreOrder(new ExophoricFunctionRewriter(mq), null);
          return substV(
              "___.xo4a(" +
              "    function (@formals*) { @fh*; @stmts*; @body*; })",
              "formals", bindings.get("formals"),
              // It's important that body is expanded before computing
              // fh and stmts.
              "body", expand(rewrittenBody, s2, mq),
              // fh will contain a declaration for ReservedNames.LOCAL_THIS
              "fh", getFunctionHeadDeclarations(s2),
              "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="funcCtor",
          synopsis="",
          reason="",
          matches="function @fname(@ps*) { @b; @bs*; }",
          substitutes="<approx> @fname = (function () {\n"
              + "  ___.splitCtor(@fRef, @f_init___Ref);\n"
              + "  function @fname(var_args) {\n"
              + "    return new @fRef.make___(arguments);\n"
              + "  }\n"
              + "  function @f_init(@ps*) {\n"
              + "    @fh*;\n"
              + "    @stmts*;\n"
              + "    @b;\n"
              + "    @bs*;\n"
              + "  }\n"
              + "  return @fRef;\n"
              + "})()")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        boolean declaration = node instanceof FunctionDeclaration;
        ParseTreeNode matchNode = declaration
            ? ((FunctionDeclaration) node).getInitializer()
            : node;
        Map<String, ParseTreeNode> bindings = match(matchNode);
        if (bindings != null) {
          FunctionConstructor constructorNode = (FunctionConstructor) matchNode;
          if (bindings.get("fname").getValue() == null) {
            mq.addMessage(
                RewriterMessageType.ANONYMOUS_FUNCTION_REFERENCES_THIS,
                node.getFilePosition(),
                this,
                node);
            return node;
          }
          Scope s2 = Scope.fromFunctionConstructor(scope, constructorNode);
          if (s2.hasFreeThis()) {
            checkFormals(bindings.get("ps"), mq);
            ParseTreeNode bNode = bindings.get("b");
            if (bNode instanceof ExpressionStmt) {
              // Rebind bNode to the Expression part of the ExpressionStmt.
              bNode = bNode.children().get(0);
            }
            Map<String, ParseTreeNode> superBindings = makeBindings();
            // To subclass, the very first line must be a call to the super
            // constructor, which must be a reference to a declared function.
            if (QuasiBuilder.match(
                    "@fname.super(this, @params*);", bNode, superBindings)) {
              if (!(superBindings.get("fname") instanceof Reference)) {
                mq.addMessage(
                    RewriterMessageType.SUPER_CALL_ON_NON_REFERENCE,
                    node.getFilePosition(),
                    this,
                    bNode);
                return node;
              }
              if (!((Reference) superBindings.get("fname")).getIdentifierName().equals(
                  ((Identifier) bindings.get("fname")).getName())) {
                mq.addMessage(
                    RewriterMessageType.SUPER_CALL_OUT_OF_CONTEXT,
                    node.getFilePosition(),
                    this,
                    bNode);
                return node;
              }
              Scope paramScope = Scope.fromParseTreeNodeContainer(
                  s2,
                  (ParseTreeNodeContainer) superBindings.get("params"));
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
              bNode = new ExpressionStmt((Expression) substV(
                  "@fname.super(this, @params*);",
                  "fname", superBindings.get("fname"),
                  "params", expand(superBindings.get("params"), s2, mq)));
            } else {
              // If it's not a call to a constructor, expand the entire node.
              bNode = expand(bindings.get("b"), s2, mq);
            }
            Identifier fname = (Identifier) bindings.get("fname");
            Reference fRef = new Reference(fname);
            Identifier f_init___ = s(new Identifier(fname.getName() + "_init___"));
            Reference f_init___Ref = new Reference(f_init___);
            // Add a declaration to the start of function body
            if (declaration) {
              scope.declareStartOfScopeVariable(fname);
            }
            ParseTreeNode result = substV(
                "(function () {" +
                "  ___.splitCtor(@fRef, @f_init___Ref);" +
                "  function @fname(var_args) { return new @fRef.make___(arguments); }" +
                "  function @f_init(@ps*) {" +
                "    @fh*;" +
                "    @stmts*;" +
                "    @b;" +
                "    @bs*;" +
                "  }" +
                "  return @fRef;" +
                "})()",
                "fname", fname,
                "fRef", fRef,
                "f_init", f_init___,
                "f_init___Ref", f_init___Ref,
                "ps", bindings.get("ps"),
                // It's important to expand bs before computing fh and stmts.
                "bs", expand(bindings.get("bs"), s2, mq),
                "fh", getFunctionHeadDeclarations(s2),
                "b", bNode,
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
            if (declaration) {
              // Add the initialization to the start of block
              Expression expr = (Expression) substV(
                  "@fname = @r;",
                  "fname", new Reference((Identifier) bindings.get("fname")),
                  "r", result);
              scope.addStartOfBlockStatement(new ExpressionStmt(expr));
              return substV(";");
            } else {
              // If used in an expression, it's the first use, so we freeze it.
              return substV("___.primFreeze(@result);", "result", result);
            }
          }
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // map - object literals
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="mapBadKeyValueOf",
          synopsis="Statically reject 'valueOf' as a key",
          reason="We depend on valueOf returning consistent results.",
          matches="({@keys*: @vals*})",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null
            && literalsContain(bindings.get("keys"), "valueOf")) {
          mq.addMessage(
              RewriterMessageType.VALUEOF_PROPERTY_MUST_NOT_BE_SET,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="mapBadKeySuffix",
          synopsis="Statically reject if a key with `_` suffix is found",
          reason="",
          matches="({@keys*: @vals*})",
          substitutes="<reject>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null && literalsEndWith(bindings.get("keys"), "_")) {
          mq.addMessage(
              RewriterMessageType.KEY_MAY_NOT_END_IN_UNDERSCORE,
              node.getFilePosition(), this, node);
          return node;
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="mapNonEmpty",
          synopsis="",
          reason="",
          matches="({@keys*: @vals*})",
          substitutes="___.initializeMap([@items*]) where items are interleaved "
              + "keys and vals")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          List<ParseTreeNode> items = new ArrayList<ParseTreeNode>();
          List<? extends ParseTreeNode> keys = bindings.get("keys").children();
          List<? extends ParseTreeNode> vals = expand(bindings.get("vals"), scope, mq).children();
          for (int i = 0, n = keys.size(); i < n; ++i) {
            items.add(keys.get(i));
            items.add(vals.get(i));
          }
          return substV(
              "___.initializeMap([ @items* ])",
              "items", new ParseTreeNodeContainer(items));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // multiDeclaration - multiple declarations
    ////////////////////////////////////////////////////////////////////////

    // TODO(ihab.awad): The 'multiDeclaration' implementation is hard
    // to follow or maintain. Refactor asap.
    new Rule() {
      @Override
      @RuleDescription(
          name="multiDeclaration",
          synopsis="Consider declarations separately from initializers",
          reason="",
          matches="var @a=@b?, @c=@d*",
          substitutes="{ @decl; @init; }")
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
            if (declarations.isEmpty()) {
              return new ExpressionStmt(newCommaOperation(initializers));
            } else {
              return substV(
                  "{ @decl; @init; }",
                  "decl", new MultiDeclaration(declarations),
                  "init", new ExpressionStmt(newCommaOperation(initializers)));
            }
          } else {
            return ParseTreeNodes.newNodeInstance(
                MultiDeclaration.class, null, expanded);
          }
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // other - things not otherwise covered
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="otherTypeof",
          synopsis="Typeof translates simply",
          reason="One of Caja's deviations from JavaScript is that reading a "
              + "non-existent imported variable returns 'undefined' rather than "
              + "throwing a ReferenceError. Therefore, in Caja, 'typeof' can "
              + "always evaluate its argument.",
          matches="typeof @f",
          substitutes="typeof @f)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "typeof @f",
              "f", expand(bindings.get("f"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="inInternal",
          synopsis="Is a public or protected property present on 'this'?",
          reason="",
          matches="@i in this",
          substitutes="___.canReadProp(t___, @i)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.canReadProp(t___, @i)",
              "i", expand(bindings.get("i"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="inPublic",
          synopsis="Is a public property present on the object?",
          reason="",
          matches="@i in @o",
          substitutes="___.canReadPubRev(@i, @o)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
              "___.canReadPubRev(@i, @o)",
              "i", expand(bindings.get("i"), scope, mq),
              "o", expand(bindings.get("o"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="voidOp",
          synopsis="",
          reason="",
          matches="void @x",
          substitutes="void @x")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) { return expandAll(node, scope, mq); }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="commaOp",
          synopsis="",
          reason="",
          matches="(@a, @b)",
          substitutes="(@a, @b)")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) { return expandAll(node, scope, mq); }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="labeledStatement",
          synopsis="Statically reject if a label with `__` suffix is found",
          reason="Caja reserves the `__` suffix for internal use",
          matches="@lbl: @stmt;",
          substitutes="@lbl: @stmt;")
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
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="regexLiteral",
          synopsis="Use the regular expression constructor",
          reason="So that every use of a regex literal creates a new instance "
              + "to prevent state from leaking via interned literals.  This is "
              + "consistent with the way ES4 treates regex literals.",
          matches="/foo/",
          substitutes="new ___.RegExp(@pattern, @modifiers?)")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof RegexpLiteral) {
          RegexpLiteral re = (RegexpLiteral) node;
          StringLiteral pattern = StringLiteral.valueOf(re.getMatchText());
          StringLiteral modifiers = !"".equals(re.getModifiers())
              ? StringLiteral.valueOf(re.getModifiers())
              : null;
          return QuasiBuilder.substV(
              "new ___.RegExp(@pattern, @modifiers?)",
              "pattern", pattern,
              "modifiers", modifiers);
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // recurse - automatically recurse into some structures
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="recurse",
          synopsis="Automatically recurse into some structures",
          reason="",
          matches="<many>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof ParseTreeNodeContainer ||
            node instanceof ArrayConstructor ||
            // TODO(mikesamuel): break/continue with unmentionable label
            node instanceof BreakStmt ||
            node instanceof CaseStmt ||
            node instanceof Conditional ||
            node instanceof ContinueStmt ||
            node instanceof DebuggerStmt ||
            node instanceof DefaultCaseStmt ||
            node instanceof ExpressionStmt ||
            node instanceof FormalParam ||
            node instanceof Identifier ||
            node instanceof Literal ||
            node instanceof Loop ||
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
    }
  };

  private final boolean wartsMode;

  /**
   * Creates a default caja rewriter with wartsMode off
   */
  public DefaultCajaRewriter() {
    this(false);
  }

  public DefaultCajaRewriter(boolean wartsMode) {
    this(true, wartsMode);
  }

  public DefaultCajaRewriter(boolean logging, boolean wartsMode) {
    super(logging);
    this.wartsMode = wartsMode;
    addRules(cajaRules);
  }
}
