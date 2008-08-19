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

import static com.google.caja.parser.quasiliteral.QuasiBuilder.substV;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.caja.lexer.Keyword;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.js.AssignOperation;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Expression;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.FunctionDeclaration;
import com.google.caja.parser.js.Identifier;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.Reference;
import com.google.caja.parser.js.RegexpLiteral;
import com.google.caja.parser.js.StringLiteral;
import com.google.caja.parser.js.SyntheticNodes;
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
  private int tempVarCount = 0;
  private final String tempVarPrefix = "$caja$";

  final public Rule[] valijaRules = {
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

    new Rule() {
      @Override
      @RuleDescription(
          name="module",
          synopsis="Assume an imported \"valija\" that knows our shared outers. " +
            "Name it $dis so top level uses of \"this\" in Valija work.",
          reason="",
          matches="@ss*;",
          substitutes="var $dis = valija.getOuters(); @startStmts*; @ss*;")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        if (node instanceof Block && scope == null) {
          Scope s2 = Scope.fromProgram((Block)node, mq);
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          for (ParseTreeNode c : node.children()) {
            expanded.add(expand(c, s2, mq));
          }
          return substV(
              "var $dis = valija.getOuters(); @startStmts*; @expanded*;",
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "expanded", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

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
              "@startStmts*; @ss*;",
              "startStmts", new ParseTreeNodeContainer(s2.getStartStatements()),
              "ss", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },

    new Rule () {
      @Override
      @RuleDescription(
          name="foreachExpr",
          synopsis="Get the keys, then iterate over them.",
          reason="",
          matches="for (@k in @o) @ss;",
          substitutes="<approx>var @t1 = valija.keys(@o);" +
                      "for (var @t2 = 0; @t2 < @t1.length; @t2++) {" +
                      "  @k = @t1[@t2];" +
                      "  @ss;" +
                      "}")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null &&
            bindings.get("k") instanceof ExpressionStmt) {
          ExpressionStmt es = (ExpressionStmt)bindings.get("k");
          bindings.put("k", es.getExpression());

          Identifier t1 = new Identifier(tempVarPrefix + tempVarCount++);
          scope.declareStartOfScopeVariable(t1);
          Reference rt1 = new Reference(t1);

          Identifier t2 = new Identifier(tempVarPrefix + tempVarCount++);
          scope.declareStartOfScopeVariable(t2);
          Reference rt2 = new Reference(t2);

          Identifier t3 = new Identifier(tempVarPrefix + tempVarCount++);
          scope.declareStartOfScopeVariable(t3);
          Reference rt3 = new Reference(t3);

          ParseTreeNode assignment = substV(
              "@k = @t3;",
              "k", bindings.get("k"),
              "t3", rt3);
          assignment.getAttributes().set(ParseTreeNode.TAINTED, true);

          return substV(
              "@t1 = valija.keys(@o);" +
              "for (@t2 = 0; @t2 < @t1.length; ++@t2) {" +
              "  @t3 = @t1[@t2];" +
              "  @assign;" +
              "  @ss;" +
              "}",
              "t1", rt1,
              "o", expand(bindings.get("o"), scope, mq),
              "t2", rt2,
              "t3", rt3,
              "assign", SyntheticNodes.s(
                  new ExpressionStmt((Expression) expand(assignment, scope, mq))),
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
          substitutes="<approx>var t1, t2;" +
                      "@t1 = valija.keys(@o);" +
                      "for (@t2 = 0; @t2 < @t1.length; @t2++) {" +
                      "  var @k = @t1[@t2];" +
                      "  @ss;" +
                      "}")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Identifier t1 = scope.declareStartOfScopeTempVariable();
          Identifier t2 = scope.declareStartOfScopeTempVariable();
          
          Reference rt1 = new Reference(t1);
          Reference rt2 = new Reference(t2);
          return substV(
              "@t1 = valija.keys(@o);" +
              "for (@t2 = 0; @t2 < @t1.length; ++@t2) {" +
              "  var @k = @t1[@t2];" +
              "  valija;" +
              "  @ss;" +
              "}",
              "t1", rt1,
              "o", expand(bindings.get("o"), scope, mq),
              "t2", rt2,
              "k", bindings.get("k"),
              "ss", expand(bindings.get("ss"), scope, mq));
        } else {
          return NONE;
        }
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="this",
          synopsis="Replace all occurrences of \"this\" with $dis.",
          reason="",
          matches="this",
          substitutes="$dis")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (QuasiBuilder.match(Keyword.THIS.toString(), node, bindings)) {
          return newReference(ReservedNames.DIS);
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
          matches="<in outer scope>var @v = @r",
          substitutes="<approx>valija.setOuter(@'v', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (QuasiBuilder.match("var @v = @r", node, bindings) &&
            scope.getParent() == null) {
          return substV(
              "valija.setOuter(@rv, @r)",
              "rv", toStringLiteral(bindings.get("v")),
              "r", expand(bindings.get("r"), scope, mq));
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
          matches="<declared in outer scope>@v = @r",
          substitutes="<approx>valija.setOuter(@'v', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (QuasiBuilder.match("@v = @r", node, bindings) &&
            bindings.get("v") instanceof Reference &&
            scope.isImported(getReferenceName(bindings.get("v")))) {
          return substV(
              "valija.setOuter(@rv, @r)",
              "rv", toStringLiteral(bindings.get("v")),
              "r", expand(bindings.get("r"), scope, mq));
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
          matches="<in outer scope>var @v",
          substitutes="<approx>valija.initOuter(@'v')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (QuasiBuilder.match("var @v", node, bindings) &&
            bindings.get("v") instanceof Reference &&
            scope.isImported(getReferenceName(bindings.get("v")))) {
          return substV(
              "valija.initOuter(@rv)",
              "rv", toStringLiteral(bindings.get("v")));
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
          matches="<declared in outer scope>@v",
          substitutes="<approx>valija.readOuter(@'v')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        if (QuasiBuilder.match("@v", node, bindings) &&
            bindings.get("v") instanceof Reference &&
            scope.isImported(getReferenceName(bindings.get("v")))) {
          return substV(
              "valija.readOuter(@rv)",
              "rv", toStringLiteral(bindings.get("v")));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="readPublic",
          synopsis="Read @'p' from @o or @o's POE table",
          reason="",
          matches="@o.@p",
          substitutes="<approx> valija.read(@o, @'p')")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "valija.read(@o, @rp)",
              "o", expand(bindings.get("o"), scope, mq),
              "rp", toStringLiteral(p));
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
          substitutes="valija.read(@o, @p)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.read(@o, @p)",
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="setPublic",
          synopsis="Set @'p' on @o or @o's POE table",
          reason="",
          matches="@o.@p = @r",
          substitutes="<approx> valija.set(@o, @'p', @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "valija.set(@o, @rp, @r)",
              "o", expand(bindings.get("o"), scope, mq),
              "rp", toStringLiteral(p),
              "r", expand(bindings.get("r"), scope, mq));
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
          substitutes="valija.set(@o, @p, @r)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.set(@o, @p, @r)",
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
              op.getAssignmentDelegate(),
              ops.getUncajoledLValue(), aNode.children().get(1));
          rhs.setFilePosition(aNode.children().get(0).getFilePosition());
          Operation assignment = ops.makeAssignment(rhs);
          assignment.setFilePosition(aNode.getFilePosition());
          if (ops.getTemporaries().isEmpty()) {
            return expand(assignment, scope, mq);
          } else {
            return substV(
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
              return substV("@v ++", "v", ops.getCajoledLValue());
            } else {
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = (Expression) expand(
                  ops.makeAssignment(
                      (Expression) substV("@tmpVal + 1", "tmpVal", tmpVal)),
                  scope, mq);
              return substV(
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
              return substV("++@v", "v", ops.getCajoledLValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return expand(
                  ops.makeAssignment(
                      (Expression) substV("@rvalue - -1",
                         "rvalue", ops.getUncajoledLValue())),
                  scope, mq);
            } else {
              return substV(
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", expand(
                      ops.makeAssignment((Expression)
                          substV("@rvalue - -1",
                                 "rvalue", ops.getUncajoledLValue())),
                      scope, mq));
            }
          case POST_DECREMENT:
            if (ops.isSimpleLValue()) {
              return substV("@v--", "v", ops.getCajoledLValue());
            } else {
              Reference tmpVal = new Reference(
                  scope.declareStartOfScopeTempVariable());
              Expression assign = (Expression) expand(
                  ops.makeAssignment(
                      (Expression) substV("@tmpVal - 1", "tmpVal", tmpVal)),
                  scope, mq);
              return substV(
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
              return substV("--@v", "v", ops.getCajoledLValue());
            } else if (ops.getTemporaries().isEmpty()) {
              return expand(
                  ops.makeAssignment(
                      (Expression) substV(
                          "@rvalue - 1", "rvalue",
                          ops.getUncajoledLValue())),
                  scope, mq);
            } else {
              return substV(
                  "  @tmps,"
                  + "@assign",
                  "tmps", newCommaOperation(ops.getTemporaries()),
                  "assign", expand(
                      ops.makeAssignment((Expression)
                          substV("@rvalue - 1",
                                 "rvalue", ops.getUncajoledLValue())),
                      scope, mq));
            }
          default:
            return NONE;
        }
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="constructNoArgs",
          synopsis="Construct a new object and supply the missing empty argument list.",
          reason="",
          matches="new @c",
          substitutes="valija.construct(@c, [])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.construct(@c, [])",
              "c", expand(bindings.get("c"), scope, mq));
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
          substitutes="valija.construct(@c, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          expandEntries(bindings, scope, mq);
          return QuasiBuilder.subst(
              "valija.construct(@c, [@as*])",
              bindings);
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="deletePublic",
          synopsis="Delete a statically known property of an object.",
          reason="",
          matches="delete @o.@p",
          substitutes="<approx>valija.remove(@o, @'p')")
          public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Reference p = (Reference) bindings.get("p");
          return substV(
              "valija.remove(@o, @rp)",
              "o", expand(bindings.get("o"), scope, mq),
              "rp", toStringLiteral(p));
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
          substitutes="valija.remove(@o, @p)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.remove(@o, @p)",
              "o", expand(bindings.get("o"), scope, mq),
              "p", expand(bindings.get("p"), scope, mq));
        }
        return NONE;
      }
    },

    new Rule() {
      @Override
      @RuleDescription(
          name="callNamed",
          synopsis="Call a property with a statically known name.",
          reason="",
          matches="@o.@p(@as*)",
          substitutes="<approx> valija.callMethod(@o, @'p', [@as*])")
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
              "valija.callMethod(@o, @rp, [@as*])",
              "o", expand(bindings.get("o"), scope, mq),
              "rp", toStringLiteral(p),
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
          substitutes="valija.callMethod(@o, @p, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          ParseTreeNodeContainer args = (ParseTreeNodeContainer)bindings.get("as");
          for (ParseTreeNode c : args.children()) {
            expanded.add(expand(c, scope, mq));
          }
          return substV(
              "valija.callMethod(@o, @p, [@as*])",
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
          substitutes="valija.callFunc(@f, [@as*])")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          List<ParseTreeNode> expanded = new ArrayList<ParseTreeNode>();
          ParseTreeNodeContainer args = (ParseTreeNodeContainer)bindings.get("as");
          for (ParseTreeNode c : args.children()) {
            expanded.add(expand(c, scope, mq));
          }
          return substV(
              "valija.callFunc(@f, [@as*])",
              "f", expand(bindings.get("f"), scope, mq),
              "as", new ParseTreeNodeContainer(expanded));
        }
        return NONE;
      }
    },
  
    new Rule() {
      @Override
      @RuleDescription(
          name="disfuncAnon",
          synopsis="Transmutes functions into disfunctions.",
          reason="",
          matches="function (@ps*) {@bs*;}",
          substitutes="valija.dis(function ($dis, @ps*) {@fh*; @stmts*; @bs*;})")
      public ParseTreeNode fire(
          ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(scope, (FunctionConstructor)node);
          return substV(
              "valija.dis(function ($dis, @ps*) {@fh*; @stmts*; @bs*;})",
              "ps", bindings.get("ps"),
              // It's important to expand bs before computing fh and stmts.
              "bs", expand(bindings.get("bs"), s2, mq),
              "fh", getFunctionHeadDeclarations(s2),
              "stmts", new ParseTreeNodeContainer(s2.getStartStatements()));
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
          substitutes="<approx>var @fname = valija.dis(" +
                                   "function($dis, @ps*) {" +
                                   "  @fh*;" +
                                   "  @stmts*;" +
                                   "  @bs*;" +
                                   "}, " +
                                   "@'fname');")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = new LinkedHashMap<String, ParseTreeNode>();
        // Named simple function declaration
        if (node instanceof FunctionDeclaration &&
            QuasiBuilder.match(
                "function @fname(@ps*) { @bs*; }",
                node.children().get(1), bindings)) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor)node.children().get(1));
          checkFormals(bindings.get("ps"), mq);
          Identifier fname = (Identifier)bindings.get("fname");
          scope.declareStartOfScopeVariable(fname);
          Expression expr = (Expression)substV(
              "@fname = valija.dis(" +
              "  function($dis, @ps*) {" +
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
          substitutes=
            "<approx>" +
            "(function() {" +
            "  var @fname = valija.dis(function ($dis, @ps*) {" +
            "    @fh*;" +
            "    @stmts*;" +
            "    @bs*;" +
            "  }," +
            "  @'fname')" +
            "  return @fname;" +
            "})();")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        // Named simple function expression
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          Scope s2 = Scope.fromFunctionConstructor(
              scope,
              (FunctionConstructor)node);
          checkFormals(bindings.get("ps"), mq);
          Identifier fname = (Identifier)bindings.get("fname");
          Reference fRef = new Reference(fname);
          return substV(
              "(function() {" +
              "  var @fRef = valija.dis(function ($dis, @ps*) {" +
              "    @fh*;" +
              "    @stmts*;" +
              "    @bs*;" +
              "  }," +
              "  @rf);" +
              "  return @fRef;" +
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
        return NONE;
      }
    },
  
    new Rule() {
      @Override
      @RuleDescription(
          name="metaDisfunc",
          synopsis="Shadow the constructor Function().",
          reason="",
          matches="Function",
          substitutes="valija.Disfunction")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV("valija.Disfunction");
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
          substitutes="valija.typeOf(@f)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.typeOf(@f)",
              "f", expand(bindings.get("f"), scope, mq));
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
          substitutes="valija.instanceOf(@o, @f)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null) {
          return substV(
              "valija.instanceOf(@o, @f)",
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
          substitutes="valija.canReadRev(@i, @o)")
      public ParseTreeNode fire(ParseTreeNode node, Scope scope, MessageQueue mq) {
        Map<String, ParseTreeNode> bindings = this.match(node);
        if (bindings != null &&
            scope.getParent() == null) {
          return substV(
              "valija.canReadRev(@i, @o)",
              "i", expand(bindings.get("i"), scope, mq),
              "r", expand(bindings.get("o"), scope, mq));
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
              "new RegExp(@pattern, @modifiers?)",
              "pattern", pattern,
              "modifiers", modifiers);
        }
        return NONE;
      }
    },

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
