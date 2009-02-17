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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.caja.lexer.FilePosition;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.js.AssignOperation;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.FunctionDeclaration;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.ModuleEnvelope;
import com.google.caja.parser.js.MultiDeclaration;
import com.google.caja.parser.js.Noop;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.QuotedExpression;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.RegexpLiteral;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.SyntheticNodes;
import com.google.caja.parser.js.TryStmt;
import com.google.caja.reporting.MessageQueue;

/**
 * Rewrites a JavaScript parse tree to comply with default Valija rules.
 *
 * @author metaweta@gmail.com (Mike Stay)
 */
@RulesetDescription(
    name="Valija-to-Cajita Transformation Rules",
    synopsis="Default set of transformations used by Valija"
  )

public class DefaultValijaRewriter extends Rewriter {

  private int tempVarCount = 1;
  private final String tempVarPrefix = "$caja$";

  Reference newTempVar(Scope scope) {
    Identifier t = new Identifier(
        FilePosition.UNKNOWN, tempVarPrefix + tempVarCount++);
    scope.declareStartOfScopeVariable(t);
    return new Reference(t);
  }

  final public Rule[] valijaRules = {

    ////////////////////////////////////////////////////////////////////////
    // Do nothing if the node is already the result of some translation
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="syntheticReference",
          synopsis="Pass through synthetic references.",
          reason="A variable may not be mentionable otherwise.",
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
          synopsis="Pass through deletes of synthetic members.",
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
          matches="/* synthetic */ @lhs = @rhs",
          substitutes="<expanded>")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && bindings.get("lhs") instanceof Reference) {
          if (isSynthetic((Reference) bindings.get("lhs"))) {
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
          matches="/* synthetic */ var @v = @initial?;",
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
              "try { @body*; } catch (/* synthetic */ @ex___) { @handler*; }"),
          substitutes="try { @body*; } catch (@ex___) { @handler*; }")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Identifier ex = (Identifier) bindings.get("ex");
          if (isSynthetic(ex)) {
            expandEntries(bindings, scope, mq);
            return subst(bindings);
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
               "try { @body*; } catch (/* synthetic */ @ex___) { @handler*; }"
               + " finally { @cleanup*; }"),
          substitutes=(
               "try { @body*; } catch (/* synthetic */ @ex___) { @handler*; }"
               + " finally { @cleanup*; }"))
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Identifier ex = (Identifier) bindings.get("ex");
          if (isSynthetic(ex)) {
            expandEntries(bindings, scope, mq);
            return subst(bindings);
          }
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // 'use strict,*'; pragmas
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="cajitaUseSubset",
          synopsis="Skip subtrees with a 'use strict,cajita' declaration",
          reason="Valija rules should not be applied to embedded cajita code",
          // TODO(mikesamuel): check after Kona meeting
          matches="'use cajita'; @stmt*",
          substitutes="{ @stmt* }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block) {
          Map<String, ParseTreeNode> bindings = this.match(node);
          if (bindings != null) {
            // Do not descend into children.  Cajita nodes are exempt
            // from the Valija -> Cajita translation since they
            // presumably already contain Cajita.  If they do not
            // contain valid Cajita code, the CajitaRewriter will
            // complain.
            return subst(bindings);
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="cajitaUseSubsetFnDecl",
          synopsis="Skip functions with a 'use strict,cajita' declaration",
          reason="Valija rules should not be applied to embedded cajita code",
          matches="/*outer*/function @name(@actuals*) { 'use cajita'; @body* }",
          substitutes="$v.so('@name', function @name(@actuals*) { @body* })")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof FunctionDeclaration) {
          Map<String, ParseTreeNode> bindings = this.match(
              ((FunctionDeclaration) node).getInitializer());
          if (bindings != null) {
            // Do not expand children. See discussion in cajitaUseSubset above.
            // But we do want to make the name visible on outers, so expand the
            // declaration to an assignment to outers and hoist it to the top
            // of the block.
            if (scope.isOuter(((Identifier) bindings.get("name")).getName())) {
              Expression initScope = (Expression)
                  QuasiBuilder.subst("$v.initOuter('@name');", bindings);
              Expression initBlock = (Expression) subst(bindings);
              scope.addStartOfScopeStatement(newExprStmt(initScope));
              scope.addStartOfBlockStatement(newExprStmt(initBlock));
              return new Noop(node.getFilePosition());
            }
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="cajitaUseSubsetFn",
          synopsis="Skip functions with a 'use strict,cajita' declaration",
          reason="Valija rules should not be applied to embedded cajita code",
          matches="function @i?(@actuals*) { 'use cajita'; @stmt* }",
          substitutes="function @i?(@actuals*) { @stmt* }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        FunctionConstructor ctor;
        if (node instanceof FunctionDeclaration) {
          ctor = ((FunctionDeclaration) node).getInitializer();
        } else if (node instanceof FunctionConstructor) {
          ctor = (FunctionConstructor) node;
        } else {
          return NONE;
        }
        Map<String, ParseTreeNode> bindings = this.match(ctor);
        if (bindings != null) {
          // Do not expand children. See discussion in cajitaUseSubset above.
          FunctionConstructor newCtor = (FunctionConstructor) subst(bindings);
          if (node instanceof FunctionDeclaration) {
            return new FunctionDeclaration(newCtor.getIdentifier(), newCtor);
          } else {
            return newCtor;
          }
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // Module envelope
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="moduleEnvelope",
          synopsis="Expand to a Caja module using an isolated scope.",
          reason="The 'module' rule should fire on the body of the module.",
          matches="<a Valija ModuleEnvelope>",
          substitutes="<a Caja ModuleEnvelope>")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof ModuleEnvelope) {
          return expandAll(node, null, mq);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="module",
          synopsis="Assume an imported \"$v\" that knows our shared outers. " +
            "Name it $dis so top level uses of \"this\" in Valija work.",
          reason="",
          matches="@ss*;",
          substitutes="var $dis = $v.getOuters();"
            + "$v.initOuter('onerror');"
            + "@startStmts*;"
            + "@ss*;")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block && scope == null) {
          Scope s2 = Scope.fromProgram((Block)node, mq);
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          for (ParseTreeNode c : node.children()) {
            expanded.add(expand(c, s2, mq));
          }
          return substV(
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "ss", new ParseTreeNodeContainer(expanded));
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
          synopsis="Initialize named functions at the beginning of their enclosing block.",
          reason="Nested named function declarations are illegal in ES3 but are universally " +
            "supported by all JavaScript implementations, though in different ways. " +
            "The compromise semantics currently supported by Caja is to hoist the " +
            "declaration of a variable with the function's name to the beginning of " +
            "the enclosing function body or module top level, and to initialize " +
            "this variable to a new anonymous function every time control re-enters " +
            "the enclosing block." +
            "\n" +
            "Note that ES3.1 and ES4 specify a better and safer semantics -- block " +
            "level lexical scoping -- that we'd like to adopt into Caja eventually. " +
            "However, it so challenging to implement this semantics by " +
            "translation to currently-implemented JavaScript that we provide " +
            "something quicker and dirtier for now.",
          matches="{@ss*;}",
          substitutes="@startStmts*; @ss*;")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          Scope s2 = Scope.fromPlainBlock(scope);
          for (ParseTreeNode c : node.children()) {
            expanded.add(expand(c, s2, mq));
          }
          return substV(
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "ss", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // foreach - "for ... in" loops
    ////////////////////////////////////////////////////////////////////////

    new Rule () {
      @Override
      @RuleDescription(
          name="foreachExpr",
          synopsis="Get the keys, then iterate over them.",
          reason="",
          matches="for (@k in @o) @ss;",
          substitutes="@t1 = $v.keys(@o);\n" +
                      "for (@t2 = 0; @t2 < @t1.length; ++@t2) {\n" +
                      "  @assign;\n" +
                      "  @ss;\n" +
                      "}\n" +
                      "/* where @assign is the expansion of\n" +
                      "@k = QuotedExpression( @t1[@t2] ); */")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null &&
            bindings.get("k") instanceof ExpressionStmt) {
          ExpressionStmt es = (ExpressionStmt) bindings.get("k");
          bindings.put("k", es.getExpression());

          Reference rt1 = newTempVar(scope);
          Reference rt2 = newTempVar(scope);

          ParseTreeNode assignment = QuasiBuilder.substV(
              "@k = @t3;",
              "k", bindings.get("k"),
              "t3", new QuotedExpression((Expression) QuasiBuilder.substV(
                  "@t1[@t2]",
                  "t1", rt1,
                  "t2", rt2)));
          assignment.getAttributes().set(ParseTreeNode.TAINTED, true);

          Expression assign = (Expression) expand(assignment, scope, mq);
          return substV(
              "t1", rt1,
              "o", expand(bindings.get("o"), scope, mq),
              "t2", rt2,
              "assign", SyntheticNodes.s(newExprStmt(assign)),
              "ss", expand(bindings.get("ss"), scope, mq));
        } else {
          return NONE;
        }
      }
    },

    new Rule () {
      @Override
      @RuleDescription(
          name="foreach",
          synopsis="Get the keys, then iterate over them.",
          reason="",
          matches="for (var @k in @o) @ss;",
          substitutes="@t1 = $v.keys(@o);\n" +
                      "for (@t2 = 0; @t2 < @t1.length; ++@t2) {\n" +
                      "  @assign\n" +
                      "  @ss;\n" +
                      "}\n" +
                      "/* where @assign is the expansion of\n" +
                      "var @k = QuotedExpression( @t1[@t2] ); */")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference rt1 = newTempVar(scope);
          Reference rt2 = newTempVar(scope);

          ParseTreeNode assignment = QuasiBuilder.substV(
              "var @k = @t3;",
              "k", bindings.get("k"),
              "t3", new QuotedExpression((Expression) QuasiBuilder.substV(
                  "@t1[@t2]",
                  "t1", rt1,
                  "t2", rt2)));

          assignment.getAttributes().set(ParseTreeNode.TAINTED, true);

          return substV(
              "t1", rt1,
              "o", expand(bindings.get("o"), scope, mq),
              "t2", rt2,
              "assign", SyntheticNodes.s(expand(assignment, scope, mq)),
              "ss", expand(bindings.get("ss"), scope, mq));
        } else {
          return NONE;
        }
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // try - try/catch/finally constructs
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="tryCatch",
          synopsis="",
          reason="",
          matches="try { @s0*; } catch (@x) { @s1*; }",
          substitutes="try { @s0*; } catch (@x) { @s1*; }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          TryStmt t = (TryStmt) node;
          bindings.put("s0", expandAll(bindings.get("s0"), scope, mq));
          bindings.put("s1",
              expandAll(bindings.get("s1"),
                  Scope.fromCatchStmt(scope, t.getCatchClause()), mq));
          return subst(bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="tryCatchFinally",
          synopsis="",
          reason="",
          matches="try { @s0*; } catch (@x) { @s1*; } finally { @s2*; }",
          substitutes="try { @s0*; } catch (@x) { @s1*; } finally { @s2*; }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          TryStmt t = (TryStmt) node;
          bindings.put("s0", expandAll(bindings.get("s0"), scope, mq));
          bindings.put("s1",
              expandAll(bindings.get("s1"),
                  Scope.fromCatchStmt(scope, t.getCatchClause()), mq));
          bindings.put("s2", expandAll(bindings.get("s2"), scope, mq));
          return subst(bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="tryFinally",
          synopsis="",
          reason="",
          matches="try { @s0*; } finally { @s1*; }",
          substitutes="try { @s0*; } finally { @s1*; }")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          return substV(
            "s0", expandAll(bindings.get("s0"), scope, mq),
            "s1", expandAll(bindings.get("s1"), scope, mq));
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
          name="this",
          synopsis="Replace all occurrences of \"this\" with $dis.",
          reason="",
          matches="this",
          substitutes="$dis")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return newReference(node.getFilePosition(), ReservedNames.DIS);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="initGlobalVar",
          synopsis="",
          reason="",
          matches="/* in outer scope */ var @v = @r",
          substitutes="$v.so('@v', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Identifier v = (Identifier) bindings.get("v");
          String vname = v.getName();
          if (scope.isOuter(vname)) {
            ParseTreeNode r = bindings.get("r");
            return newExprStmt((Expression) substV(
                "v", v,
                "r", expand(nymize(r, vname, "var"), scope, mq)));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setGlobalVar",
          synopsis="",
          reason="",
          matches="/* declared in outer scope */ @v = @r",
          substitutes="$v.so('@v', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          ParseTreeNode v = bindings.get("v");
          if (v instanceof Reference) {
            String vname = ((Reference) v).getIdentifierName();
            if (scope.isOuter(vname)) {
              ParseTreeNode r = bindings.get("r");
              return substV(
                  "v", v,
                  "r", expand(nymize(r, vname, "var"), scope, mq));
            }
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="declGlobalVar",
          synopsis="",
          reason="",
          matches="/* in outer scope */ var @v",
          substitutes="$v.initOuter('@v')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null &&
            bindings.get("v") instanceof Identifier &&
            scope.isOuter(((Identifier) bindings.get("v")).getName())) {
          return newExprStmt((Expression) substV("v", bindings.get("v")));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readArguments",
          synopsis="Translate reference to 'arguments' unmodified",
          reason="",
          matches="arguments",
          substitutes="Array.slice(arguments,1)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return subst(bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readGlobalVar",
          synopsis="",
          reason="",
          matches="/* declared in outer scope */ @v",
          substitutes="$v.ro('@v')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null && bindings.get("v") instanceof Reference) {
          Reference v = (Reference) bindings.get("v");
          if (scope.isOuter(v.getIdentifierName())) {
            return subst(bindings);
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="initLocalVar",
          synopsis="",
          reason="",
          matches="/* not in outer scope */ var @v = @r",
          substitutes="var @v = @r")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Identifier v = (Identifier) bindings.get("v");
          String vname = v.getName();
          if (!scope.isOuter(vname)) {
            ParseTreeNode r = bindings.get("r");
            return substV(
                "v", v,
                "r", expand(nymize(r, vname, "var"), scope, mq));
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setLocalVar",
          synopsis="",
          reason="",
          matches="/* not in outer scope */ @v = @r",
          substitutes="@v = @r")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          ParseTreeNode v = bindings.get("v");
          if (v instanceof Reference) {
            String vname = ((Reference) v).getIdentifierName();
            if (!scope.isOuter(vname)) {
              ParseTreeNode r = bindings.get("r");
              return substV(
                  "v", v,
                  "r", expand(nymize(r, vname, "var"), scope, mq));
            }
          }
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
          name="readPublic",
          synopsis="Read @'p' from @o or @o's POE table",
          reason="",
          matches="@o.@p",
          substitutes="$v.r(@o, '@p')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", p);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readIndexPublic",
          synopsis="Read @p from @o or @o's POE table",
          reason="",
          matches="@o[@p]",
          substitutes="$v.r(@o, @p)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq));
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
          name="setPublic",
          synopsis="Set @'p' on @o or @o's POE table",
          reason="",
          matches="@o.@p = @r",
          substitutes="$v.s(@o, '@p', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          ParseTreeNode o = bindings.get("o");
          Reference p = (Reference) bindings.get("p");
          ParseTreeNode r = bindings.get("r");
          return substV(
              "o", expand(o, scope, mq),
              "p", p,
              "r", expand(nymize(r, p.getIdentifierName(), "meth"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setIndexPublic",
          synopsis="Set @p on @o or @o's POE table",
          reason="",
          matches="@o[@p] = @r",
          substitutes="$v.s(@o, @p, @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq),
              "r", expand(bindings.get("r"), scope, mq));
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
          matches="<approx> @x @op= @y",  // TODO(mikesamuel): better lower limit
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
              aNode.children().get(0), scope, mq, false);
          if (ops == null) { return node; }  // Error deconstructing

          // For x += 3, rhs is (x + 3)
          Operation rhs = Operation.create(
              aNode.children().get(0).getFilePosition(),
              op.getAssignmentDelegate(),
              ops.getUncajoledLValue(), aNode.children().get(1));
          Operation assignment = ops.makeAssignment(rhs);
          if (ops.getTemporaries().isEmpty()) {
            return expand(assignment, scope, mq);
          } else {
            return QuasiBuilder.substV(
                "@tmps, @assignment",
                "tmps", newCommaOperation(ops.getTemporaries()),
                "assignment", expand(assignment, scope, mq));
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
        ReadAssignOperands ops = deconstructReadAssignOperand(v, scope, mq, false);
        if (ops == null) { return node; }

        // TODO(mikesamuel): Figure out when post increments are being
        // used without use of the resulting value and switch them to
        // pre-increments.
        switch (op.getOperator()) {
          case POST_INCREMENT:
            if (ops.isSimpleLValue()) {
              return QuasiBuilder.substV("@v ++", "v", ops.getCajoledLValue());
            } else {
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = (Expression) expand(
                  ops.makeAssignment((Expression) QuasiBuilder.substV(
                      "@tmpVal + 1", "tmpVal", tmpVal)),
                  scope, mq);
              return QuasiBuilder.substV(
                  "  @tmps,"
                  + "@tmpVal = +@rvalue,"  // Coerce to a number.
                  + "@assign,"  // Assign value.
                  + "@tmpVal",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "tmpVal", tmpVal,
                  "rvalue", ops.getCajoledLValue(),
                  "assign", assign);
            }
          case PRE_INCREMENT:
            // We subtract -1 instead of adding 1 since the - operator coerces
            // to a number in the same way the ++ operator does.
            if (ops.isSimpleLValue()) {
              return QuasiBuilder.substV("++@v", "v", ops.getCajoledLValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return expand(
                  ops.makeAssignment((Expression) QuasiBuilder.substV(
                      "@rvalue - -1",
                      "rvalue", ops.getUncajoledLValue())),
                  scope, mq);
            } else {
              return QuasiBuilder.substV(
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", expand(
                      ops.makeAssignment((Expression) QuasiBuilder.substV(
                          "@rvalue - -1",
                          "rvalue", ops.getUncajoledLValue())),
                      scope, mq));
            }
          case POST_DECREMENT:
            if (ops.isSimpleLValue()) {
              return QuasiBuilder.substV("@v--", "v", ops.getCajoledLValue());
            } else {
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = (Expression) expand(
                  ops.makeAssignment((Expression) QuasiBuilder.substV(
                      "@tmpVal - 1", "tmpVal", tmpVal)),
                  scope, mq);
              return QuasiBuilder.substV(
                  "  @tmps,"
                  + "@tmpVal = +@rvalue,"  // Coerce to a number.
                  + "@assign,"  // Assign value.
                  + "@tmpVal;",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "tmpVal", tmpVal,
                  "rvalue", ops.getCajoledLValue(),
                  "assign", assign);
            }
          case PRE_DECREMENT:
            if (ops.isSimpleLValue()) {
              return QuasiBuilder.substV("--@v", "v", ops.getCajoledLValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return expand(
                  ops.makeAssignment((Expression) QuasiBuilder.substV(
                      "@rvalue - 1", "rvalue",
                      ops.getUncajoledLValue())),
                  scope, mq);
            } else {
              return QuasiBuilder.substV(
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", expand(
                      ops.makeAssignment((Expression) QuasiBuilder.substV(
                          "@rvalue - 1",
                          "rvalue", ops.getUncajoledLValue())),
                      scope, mq));
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
          name="constructNoArgs",
          synopsis="Construct a new object and supply the missing empty argument list.",
          reason="",
          matches="new @c",
          substitutes="$v.construct(@c, [])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV("c", expand(bindings.get("c"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="construct",
          synopsis="Construct a new object.",
          reason="",
          matches="new @c(@as*)",
          substitutes="$v.construct(@c, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          expandEntries(bindings, scope, mq);
          return QuasiBuilder.subst(
              "$v.construct(@c, [@as*])",
              bindings);
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
          name="deletePublic",
          synopsis="Delete a statically known property of an object.",
          reason="",
          matches="delete @o.@p",
          substitutes="$v.remove(@o, '@p')")
          public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", p);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deleteIndexPublic",
          synopsis="Delete a dynamically chosen property of an object.",
          reason="",
          matches="delete @o[@p]",
          substitutes="$v.remove(@o, @p)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq));
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
          name="callNamed",
          synopsis="Call a property with a statically known name.",
          reason="",
          matches="@o.@p(@as*)",
          substitutes="$v.cm(@o, '@p', [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          ParseTreeNodeContainer args = (ParseTreeNodeContainer)bindings.get("as");
          for (ParseTreeNode c : args.children()) {
            expanded.add(expand(c, scope, mq));
          }
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", p,
              "as", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callMethod",
          synopsis="Call a property with a computed name.",
          reason="",
          matches="@o[@p](@as*)",
          substitutes="$v.cm(@o, @p, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          ParseTreeNodeContainer args = (ParseTreeNodeContainer)bindings.get("as");
          for (ParseTreeNode c : args.children()) {
            expanded.add(expand(c, scope, mq));
          }
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq),
              "as", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callFunc",
          synopsis="Call a function.",
          reason="",
          matches="@f(@as*)",
          substitutes="$v.cf(@f, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          ParseTreeNodeContainer args = (ParseTreeNodeContainer)bindings.get("as");
          for (ParseTreeNode c : args.children()) {
            expanded.add(expand(c, scope, mq));
          }
          return substV(
              "f", expand(bindings.get("f"), scope, mq),
              "as", new ParseTreeNodeContainer(expanded));
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
          name="disfuncAnon",
          synopsis="Transmutes functions into disfunctions.",
          reason="",
          matches="function (@ps*) {@bs*;}",
          substitutes="$v.dis(function ($dis, @ps*) {@stmts*; @bs*;})")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)node);
          return substV(
              "ps", bindings.get("ps"),
              // It's important to expand bs before computing stmts.
              "bs", expand(bindings.get("bs"), s2, mq),
              "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="disfuncNamedGlobalDecl",
          synopsis="Transmutes functions into disfunctions and hoists them.",
          reason="",
          matches="/* at top level */ function @f(@ps*) {@bs*;}",
          substitutes=(
              "$v.so('@f', (function () {" +
              "  var @f;" +
              "  function @fcaller($dis, @ps*) {" +
              "    @stmts*;" +
              "    @bs*;" +
              "  }" +
              "  @rf = $v.dis(@rfcaller, '@f');" +
              "  return @rf;" +
              "})());"))
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        // Named simple function declaration
        if (node instanceof FunctionDeclaration) {
          FunctionConstructor c = ((FunctionDeclaration) node).getInitializer();
          Map<String, ParseTreeNode> bindings = match(c);
          if (bindings != null
              && scope.isOuter(((Identifier) bindings.get("f")).getName())) {
            Scope s2 = Scope.fromFunctionConstructor(scope, c);
            checkFormals(bindings.get("ps"), mq);
            Identifier f = (Identifier) bindings.get("f");
            Identifier fcaller = new Identifier(
                f.getFilePosition(), nym(node, f.getName(), "caller"));
            Expression expr = (Expression) substV(
                "f", f,
                "rf", new Reference(f),
                "fcaller", fcaller,
                "rfcaller", new Reference(fcaller),
                "ps", bindings.get("ps"),
                // It's important to expand bs before computing stmts.
                "bs", expand(bindings.get("bs"), s2, mq),
                "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
            scope.addStartOfBlockStatement(newExprStmt(expr));
            return QuasiBuilder.substV(";");
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="disfuncNamedDecl",
          synopsis="Transmutes functions into disfunctions.",
          reason="",
          matches="function @fname(@ps*) {@bs*;}",
          substitutes=(
              "function @fcaller($dis, @ps*) {" +
              "    @stmts*;" +
              "    @bs*;" +
              "}" +
              "@fname = $v.dis(@rfcaller, '@fname');"))
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        // Named simple function declaration
        if (node instanceof FunctionDeclaration &&
            QuasiBuilder.match(
                "function @fname(@ps*) { @bs*; }",
                ((FunctionDeclaration) node).getInitializer(), bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor) node.children().get(1));
          checkFormals(bindings.get("ps"), mq);
          Identifier fname = (Identifier) bindings.get("fname");
          Identifier fcaller = new Identifier(
              FilePosition.UNKNOWN, nym(node, fname.getName(), "caller"));
          scope.declareStartOfScopeVariable(fname);
          Block block = (Block) substV(
              "fname", new Reference(fname),
              "fcaller", fcaller,
              "rfcaller", new Reference(fcaller),
              "ps", bindings.get("ps"),
              // It's important to expand bs before computing stmts.
              "bs", expand(bindings.get("bs"), s2, mq),
              "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
          for (Statement stat : block.children()) {
            scope.addStartOfBlockStatement(stat);
          }
          return QuasiBuilder.substV(";");
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="disfuncNamedValue",
          synopsis="",
          reason="",
          matches="function @fname(@ps*) { @bs*; }",
          substitutes=(
              "(function() {" +
              "  function @fcaller($dis, @ps*) {" +
              "    @stmts*;" +
              "    @bs*;" +
              "  }" +
              "  var @fname = $v.dis(@rfcaller, '@fname');" +
              "  return @fRef;" +
              "})();"))
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        // Named simple function expression
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor)node);
          checkFormals(bindings.get("ps"), mq);
          Identifier fname = (Identifier) bindings.get("fname");
          Identifier fcaller = new Identifier(
              FilePosition.UNKNOWN, nym(node, fname.getName(), "caller"));
          return substV(
              "fname", fname,
              "fRef", new Reference(fname),
              "fcaller", fcaller,
              "rfcaller", new Reference(fcaller),
              "ps", bindings.get("ps"),
              // It's important to expand bs before computing stmts.
              "bs", expand(bindings.get("bs"), s2, mq),
              "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
        }
        return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // multiDeclaration - multiple declarations
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="multiDeclaration",
          synopsis="Convert a MultiDeclaration into a comma expression",
          reason="")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof MultiDeclaration
            && scope.isOuter()) {
          List <Expression> newChildren = new ArrayList<Expression>();
          for (int i = 0, len = node.children().size(); i < len; i++) {
            ExpressionStmt result = (ExpressionStmt)
                expand(node.children().get(i), scope, mq);
            newChildren.add(i, result.getExpression());
          }
          return newExprStmt(newCommaOperation(newChildren));
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
          name="mapSingle",
          synopsis="",
          reason="",
          matches="({@key: @val})",
          substitutes="({@key: @val})")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = matchSingleMap(node);
        if (bindings != null) {
          StringLiteral key = (StringLiteral) bindings.get("key");
          ParseTreeNode val = bindings.get("val");
          return substSingleMap(
              key,
              expand(nymize(val, key.getUnquotedValue(), "lit"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="mapPlural",
          synopsis="",
          reason="",
          matches="({@keys*: @vals*})",
          substitutes="({@keys*: @vals*})")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = match(node);
        if (bindings != null) {
          List<ParseTreeNode> newVals = new ArrayList<ParseTreeNode>();
          List<? extends ParseTreeNode> keys = bindings.get("keys").children();
          List<? extends ParseTreeNode> vals = bindings.get("vals").children();
          int len = keys.size();
          if (1 == len) {
            mq.addMessage(
                RewriterMessageType.MAP_RECURSION_FAILED,
                node.getFilePosition(), node);
          }
          for (int i = 0, n = len; i < n; ++i) {
            ParseTreeNode pairIn = substSingleMap(keys.get(i), vals.get(i));
            ParseTreeNode pairOut = expand(pairIn, scope, mq);
            Map<String, ParseTreeNode> pairBindings = matchSingleMap(pairOut);
            if (null == pairBindings) {
              mq.addMessage(
                  RewriterMessageType.MAP_RECURSION_FAILED,
                  node.getFilePosition(), node);
            }
            newVals.add(pairBindings.get("val"));
          }
          return substV(
              "keys", new ParseTreeNodeContainer(keys),
              "vals", new ParseTreeNodeContainer(newVals));
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
          name="outerTypeof",
          synopsis="typeof of a global reference.",
          reason="Typeof should not throw an error for undefined outers",
          matches="typeof /* global reference */ @f",
          substitutes="$v.typeOf($v.ros('@f'))")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          ParseTreeNode f = bindings.get("f");
          if (f instanceof Reference) {
            Reference fRef = (Reference) f;
            if (scope.isOuter(fRef.getIdentifierName())) {
              return subst(bindings);
            }
          }
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="otherTypeof",
          synopsis="Rewrites typeof.",
          reason="Both typeof function and typeof disfunction need to return \"function\".",
          matches="typeof @f",
          substitutes="$v.typeOf(@f)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV("f", expand(bindings.get("f"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="otherInstanceof",
          synopsis="Rewrites instanceof.",
          reason="Need to check both the shadow prototype chain and the real one.",
          matches="@o instanceof @f",
          substitutes="$v.instanceOf(@o, @f)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "o", expand(bindings.get("o"), scope, mq),
              "f", expand(bindings.get("f"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="inPublic",
          synopsis="",
          reason="",
          matches="@i in @o",
          substitutes="$v.canReadRev(@i, @o)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "i", expand(bindings.get("i"), scope, mq),
              "o", expand(bindings.get("o"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="regexLiteral",
          synopsis="Use the regular expression constructor",
          reason="So that every use of a regex literal creates a new instance"
               + " to prevent state from leaking via interned literals.  This"
               + " is consistent with the way ES4 treates regex literals.",
          substitutes="$v.construct(RegExp, [@pattern, @modifiers?])")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof RegexpLiteral) {
          RegexpLiteral re = (RegexpLiteral) node;
          StringLiteral pattern = StringLiteral.valueOf(
              re.getFilePosition(), re.getMatchText());
          StringLiteral modifiers = !"".equals(re.getModifiers())
              ? StringLiteral.valueOf(
                  FilePosition.endOf(re.getFilePosition()), re.getModifiers())
              : null;
          return substV(
              "pattern", pattern,
              "modifiers", modifiers);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="unquote",
          synopsis="Removes a QuotedExpression wrapper.",
          reason="")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
         if (node instanceof QuotedExpression) {
           return ((QuotedExpression) node).unquote();
         }
         return NONE;
      }
    },

    ////////////////////////////////////////////////////////////////////////
    // recurse - automatically recurse into remaining structures
    ////////////////////////////////////////////////////////////////////////

    new Rule() {
      @Override
      @RuleDescription(
          name="recurse",
          synopsis="Automatically recurse into any remaining structures",
          reason="")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        return expandAll(node, scope, mq);
      }
    }
  };

  /**
   * Creates a default valija rewriter with logging on.
   */
  public DefaultValijaRewriter() {
    this(true);
  }

  public DefaultValijaRewriter(boolean logging) {
    super(logging);
    addRules(valijaRules);
  }

}
