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

import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.JsTokenQueue;
import com.google.caja.lexer.JsTokenType;
import com.google.caja.lexer.Keyword;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.Punctuation;
import com.google.caja.lexer.Token;
import com.google.caja.lexer.TokenQueue.Mark;
import com.google.caja.parser.AbstractParseTreeNode;
import com.google.caja.parser.ParserBase;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageType;
import com.google.caja.reporting.RenderContext;
import com.google.caja.util.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses javascript source returning a javascript parse tree.
 *
 * <p>
 * The grammar below is a context-free representation of the grammar this
 * parser parses.  It disagrees with EcmaScript 262 where implementations
 * disagree with EcmaScript 262.  The rules for semicolon insertion and
 * the possible backtracing in expressions needed to properly handle
 * backtracking are commented thoroughly in code, since semicolon insertion
 * requires information from both the lexer and parser and is not determinable
 * with finite lookahead.
 * <p>
 * Noteworthy features
 * <ul>
 * <li>Reports warnings on a queue where an error doesn't prevent any further
 *   errors, so that we can report multiple errors in a single compile pass
 *   instead of forcing developers to play whack-a-mole.
 * <li>Does not parse Firefox style {@code catch (<Identifier> if <Expression>)}
 *   since those don't work on IE and many other interpreters.
 * <li>Recognizes {@code const} since many interpreters do (not IE) but warns.
 * <li>Allows, but warns, on trailing commas in {@code Array} and {@code Object}
 *   constructors.
 * <li>Allows keywords as identifier names but warns since different
 *   interpreters have different keyword sets.  This allows us to use an
 *   expansive keyword set.</li>
 * </ul>
 * To parse strict code, pass in a {@code PedanticWarningMessageQueue} that
 * converts {@link MessageLevel#WARNING} and above to
 * {@link MessageLevel#FATAL_ERROR}.
 *
 * <xmp>
 * // Terminal productions
 * Program                 => <TerminatedStatement>*
 * Statement               => <TerminalStatement>
 *                          | <NonTerminalStatement>
 * Expression              => <CommaOperator>
 *
 * // Non-terminal productions.  Indentation indicates that a nonterminal is
 * // used only by the unindented nonterminal above it.
 * TerminatedStatement     => <BlockStatement>
 *                          | <SimpleStatement> <StatementTerminator>
 *   StatementTerminator   => ';'
 *                          | <InsertedSemicolon>
 *   BlockStatement        => <StatementLabel>? <BlockStatementAtom>
 *   SimpleStatement       => <StatementLabel>? <SimpleStatementAtom>
 *   InsertedSemicolon     => &epsilon;  // abort if disallowed in context
 *   StatementLabel        => <Identifier> ':'
 *   BlockStatementAtom    => <Block>
 *                          | <Conditional>
 *                          | <For>
 *                          // Noop is a block statement atom since it's
 *                          // guaranteed to end with a semicolon.  If it were
 *                          // a simple statement epsilon, then it could be
 *                          // inserted anywhere that semicolon insertion is
 *                          // allowed.
 *                          | <Noop>
 *                          | <Switch>
 *                          | <Try>
 *                          | <While>
 *                          | <With>
 *   SimpleStatementAtom   => <Break>
 *                          | <Continue>
 *                          | <Debugger>
 *                          // Do-While loops are simple because, unlike other
 *                          // loops their body isn't last, so they aren't
 *                          // guaranteed to end with a right-curly or semi.
 *                          | <Do>
 *                          | <ExprStatement>
 *                          | <FunctionDeclaration>
 *                          | <Declaration>
 *                          | <Throw>
 *                          | <Return>
 *
 * Identifier              != <Keyword>
 *                          | <Word>
 * Body                    => <TerminatedStatement>
 *
 * Block                   => '{' <TerminatedStatement>* '}'
 * Conditional             => 'if' '(' <Expression> ')' <Body> <Else>
 *   Else                  => 'else' <Body>
 *                          | &epsilon;
 * For                     => 'for' '(' ';' <ExpressionOrNoop> <Expression>? ')'
 *                            <Body>
 *                          | 'for' '(' <DeclarationStart> 'in' <Expression> ')'
 *                            <Body>
 *                          | 'for' '(' <LValue> 'in' <Expression> ')'
 *                            <Body>
 *                          | 'for' '(' <Declaration> ';' <ExpressionOrNoop>
 *                            <Expression>? ')' <Body>
 *                          | 'for' '(' <Expression> ';' <ExpressionOrNoop>
 *                            <Expression>? ')' <Body>
 * Noop                    => ';'
 * Switch                  => 'switch' '(' <Expression> ')' '{' <Cases> '}'
 *   Cases                 => <Case>* <DefaultAndCases>?
 *     DefaultAndCases     => <Default> <Case>*
 *   Case                  => 'case' <Expression> ':' <TerminatedStatement>*
 *   Default               => 'default' ':' <TerminatedStatement>*
 * Try                     => 'try' <Body> <TryClauses>
 *   TryClauses            => <Catch> <Finally>?
 *                          | <Finally>
 *   Catch                 => 'catch' '(' <Identifier> ')' <Body>
 *   Finally               => 'finally' <Body>
 * While                   => 'while' '(' <Expression> ')' <Body>
 * With                    => 'with' '(' <Expression> ')' <Body>
 * Do                      => 'do' <Body> 'while' '(' <Expression> ')'
 * Break                   => 'break' [no LineTerminator] <StatementLabel>?
 * Continue                => 'continue' [no LineTerminator] <StatementLabel>?
 * Debugger                => 'debugger'
 * Return                  => 'return' [no LineTerminator] <Expression>?
 * Throw                   => 'throw' [no LineTerminator] <Expression>
 * ExprStatement           => <Expression>
 *
 * ExpressionOrNoop        => <Expression>?
 * DeclarationStart        => <DeclarationKeyword> <Identifier>
 * Declaration             => <DeclarationKeyword> <DeclarationBodyList>
 *   DeclarationKeyword    => 'var' | 'const'
 *   DeclarationBodyList   => <DeclarationBody> <DeclarationBodyTail>
 *   DeclarationBodyTail   => ',' <DeclarationBodyList>
 *                          | &epsilon;
 *   DeclarationBody       => <Identifier> <InitialValue>?
 *   InitialValue          => '=' <Expression>
 *
 * FunctionDeclaration     => <NamedFunction>
 * FunctionConstructor     => <NamedFunction>
 *                          | <AnonymousFunction>
 *   AnonymousFunction     => 'function' '(' <FormalParams> ')' <Block>
 * NamedFunction           => 'function' <Identifier>
 *                           '(' <FormalParams> ')' <Block>
 * FormalParams          => <IdentifierList>?
 *   IdentifierList        => <Identifier> <IdentifierListTail>
 *   IdentifierListTail    => ',' <IdentifierList>
 *
 * // All of the operators have an associated precedence defined in the Operator
 * // enum.  The p variable on the right of the productions is the precedence
 * // of the most recently consumed operator.
 * CommaOperator           => <Operator(MAX)>
 *                          | <CommaOperator> ',' <Operator(MAX)>
 * Operator(prec)          => <OperatorHead(prec)> <OperatorTail(prec)>
 *   OperatorHead(prec)    => <PrefixOperator(p < prec)> <Operator(p + 1)>
 *                          | <ExpressionAtom>
 *   OperatorTail(prec)    => [no LineTerminator] <PostfixOperator(p < prec)>
 *                          | <RightAssocInfixOperator(p <= prec)> <Operator(p)>
 *                          | <LeftAssocInfixOperator(p < prec)> <Operator(p)>
 *                         // below only match if p < prec
 *                          | '[' <Expression> ']'
 *                          | '(' <ActualList>? ')'
 *                          | '?' <Operator(TERNARY)> ':' <Operator(TERNARY)>
 *                          | <ExpressionAtom>
 *     // ActualList uses Operator(MAX) to prevent parsing actuals from being
 *     // treated as operands to the comma operator.  Since the parentheses
 *     // recurse to Expression under ExpressionAtom, the result of a comma
 *     // operator can be passed to a function by nesting in parens.
 *     ActualList          => <Operator(MAX)> <ActualListTail>?
 *     ActualListTail      => ',' <ActualList>
 *                          | &epsilon;
 *   ExpressionAtom        => <StringLiteral>
 *                          | <NumberLiteral>
 *                          | <RegexpLiteral>
 *                          | 'null' | 'true' | 'false' | 'this'
 *                          | <FunctionConstructor>
 *                          | <Keyword>  // error and treat as identifier
 *                          | <Identifier>
 *                          | '(' <Expression> ')'
 *                          | <ArrayConstructor>
 *                          | <ObjectConstructor>
 *                          | &epsilon;  // in error-tolerant mode
 *   ArrayConstructor      => '[' <ArrayElements> <TrailingComma>? ']'
 *     ArrayElements       => <ArrayElementsHead>* <Expression>
 *                          | &epsilon;
 *     ArrayElementsHead   => <Expression>? ','
 *   ObjectConstructor     => '{' <ObjectElementList> '}'
 *     ObjectElementList   => <ObjectElement> <ObjectElements>* <TrailingComma>?
 *     ObjectElements      => ',' <ObjectElement>
 *     ObjectElement       => <ObjectPropertyKey> ':' <Expression>
 *     ObjectPropertyKey   => <Identifier>
 *                          | <StringLiteral>
 *                          | <NumberLiteral>
 *                          | <Keyword>  // set allowed interpreter dependent
 *   TrailingComma         => ','  // warn
 * </xmp>
 * See also {@link Keyword} for definitions of keywords, {@link Operator} for
 * operator precedences and associativity, and {@link JsTokenType} for
 * definitions of Word, NumberLiteral, and StringLiteral.
 *
 * <p>Questions as to whether a semicolon should be inserted can only be
 * answered at the lexical level.  A lexer can emit a comment-like-token where
 * semicolons can be inserted though this is not the approach taken in this
 * implementation.</p>
 *
 * @author mikesamuel@gmail.com
 */
public final class Parser extends ParserBase {
  private boolean recoverFromFailure;

  public Parser(JsTokenQueue tq, MessageQueue mq) {
    this(tq, mq, false);
  }

  public Parser(JsTokenQueue tq, MessageQueue mq, boolean isQuasiliteral) {
    super(tq, mq, isQuasiliteral);
  }

  /**
   * True iff the parser will attempt to recover from errors that can be
   * attributed to a missing run of parenthetically balanced tokens by inserting
   * a placeholder reference "_".
   */
  public boolean getRecoverFromFailure() {
    return recoverFromFailure;
  }

  /**
   * Setter corresponding to {@link #getRecoverFromFailure}.
   */
  public void setRecoverFromFailure(boolean shouldRecover) {
    this.recoverFromFailure = shouldRecover;
  }

  /** Parses a top level block. */
  public Block parse() throws ParseException {
    Mark m = tq.mark();
    List<Statement> stmts = new ArrayList<Statement>();
    while (!tq.isEmpty()) {
      stmts.add(parseTerminatedStatement());
    }
    Block b = new Block(stmts);
    finish(b, m);
    return b;
  }

  /** Parses and returns a Statement. */
  public Statement parseStatement() throws ParseException {
    // look for any labels preceding statement
    Mark m = tq.mark();
    Token<JsTokenType> t = tq.peek();
    if (JsTokenType.WORD == t.type) {
      String label = parseIdentifier(false);
      if (tq.checkToken(Punctuation.COLON)) {
        t = tq.peek();
        AbstractStatement<?> s = null;
        if (JsTokenType.KEYWORD == t.type) {
          switch (Keyword.fromString(t.text)) {
            case FOR: case DO: case WHILE: case SWITCH:
              s = parseLoopOrSwitch(label);
              break;
            default:
              break;
          }
        }
        if (null == s) {
          s = new LabeledStmtWrapper(label, parseStatementWithoutLabel());
        }
        finish(s, m);
        return s;
      }
      tq.rewind(m);
    }
    return parseStatementWithoutLabel();
  }

  private LabeledStatement parseLoopOrSwitch(String label)
      throws ParseException {
    Token<JsTokenType> t = tq.peek();
    LabeledStatement s;
    switch (Keyword.fromString(t.text)) {
      case FOR:
      {
        tq.advance();
        tq.expectToken(Punctuation.LPAREN);
        if (tq.checkToken(Punctuation.SEMI)) {
          Statement initializer = noop(tq.lastPosition());
          Expression condition = parseExpressionOrNoop(
              new BooleanLiteral(true), true);
          Statement increment;

          if (!tq.checkToken(Punctuation.RPAREN)) {
            increment = parseExpressionStmt(true);
            tq.expectToken(Punctuation.RPAREN);
          } else {
            increment = noop(tq.lastPosition());
          }
          Statement body = parseBody(true);

          s = new ForLoop(label, initializer, condition, increment, body);
        } else {
          Statement initializer = parseDeclarationsOrExpression(true);
          Expression initializerExpr = null;

          s = null;
          if ((initializer instanceof Declaration  // no multi-decls
               && null == ((Declaration) initializer).getInitializer()
               && tq.checkToken(Keyword.IN))
              || (initializer instanceof ExpressionStmt
                  && !tq.lookaheadToken(Punctuation.SEMI)
                  && (initializerExpr = ((ExpressionStmt) initializer)
                      .getExpression()) instanceof Operation
                  && Operator.IN == ((Operation) initializerExpr).getOperator()
                  && (((Operation) initializerExpr).children().get(0)
                      .isLeftHandSide()))) {

            Expression iterable;
            Expression lvalue;
            if (null == initializerExpr) {
              iterable = parseExpressionInt(true);
              lvalue = null;
            } else {
              Operation op = (Operation) initializerExpr;
              lvalue = op.children().get(0);
              iterable = op.children().get(1);
            }

            tq.expectToken(Punctuation.RPAREN);
            Statement body = parseBody(true);

            if (null == lvalue) {
              s = new ForEachLoop(
                  label, (Declaration) initializer, iterable, body);
            } else {
              s = new ForEachLoop(label, lvalue, iterable, body);
            }

          } else {
            tq.expectToken(Punctuation.SEMI);
            Expression condition = parseExpressionOrNoop(
                new BooleanLiteral(true), true);
            Statement increment;

            if (!tq.checkToken(Punctuation.RPAREN)) {
              increment = parseExpressionStmt(true);
              tq.expectToken(Punctuation.RPAREN);
            } else {
              increment = noop(tq.lastPosition());
            }
            Statement body = parseBody(true);
            s = new ForLoop(label, initializer, condition, increment, body);
          }
        }
        break;
      }
      case WHILE:
      {
        tq.advance();
        tq.expectToken(Punctuation.LPAREN);
        Expression cond = parseExpressionInt(true);
        tq.expectToken(Punctuation.RPAREN);
        Statement body = parseBody(true);
        s = new WhileLoop(label, cond, body);
        break;
      }
      case DO:
      {
        tq.advance();
        Statement body = parseBody(false);
        tq.expectToken(Keyword.WHILE);
        tq.expectToken(Punctuation.LPAREN);
        Expression cond = parseExpressionInt(true);
        tq.expectToken(Punctuation.RPAREN);
        s = new DoWhileLoop(label, body, cond);
        break;
      }
      case SWITCH:
      {
        tq.advance();
        tq.expectToken(Punctuation.LPAREN);
        Expression switchValue = parseExpressionInt(true);
        tq.expectToken(Punctuation.RPAREN);
        tq.expectToken(Punctuation.LCURLY);
        List<SwitchCase> cases = new ArrayList<SwitchCase>();
        while (!tq.checkToken(Punctuation.RCURLY)) {
          Mark caseMark = tq.mark();
          Expression caseValue;
          if (tq.checkToken(Keyword.DEFAULT)) {
            caseValue = null;
          } else {
            tq.expectToken(Keyword.CASE);
            caseValue = parseExpressionInt(false);
          }
          tq.expectToken(Punctuation.COLON);
          FilePosition colonPos = tq.lastPosition();
          Mark caseBodyStart = tq.mark();
          List<AbstractStatement<?>> caseBodyContents =
            new ArrayList<AbstractStatement<?>>();
          while (!(tq.lookaheadToken(Keyword.DEFAULT)
                   || tq.lookaheadToken(Keyword.CASE)
                   || tq.lookaheadToken(Punctuation.RCURLY))) {
            caseBodyContents.add(parseTerminatedStatement());
          }
          AbstractStatement<?> caseBody;
          switch (caseBodyContents.size()) {
            case 0:
              caseBody = noop(FilePosition.endOf(colonPos));
              break;
            case 1:
              caseBody = caseBodyContents.get(0);
              break;
            default:
              caseBody = new Block(caseBodyContents);
              finish(caseBody, caseBodyStart);
              break;
          }
          SwitchCase caseStmt = (null != caseValue)
            ? new CaseStmt(caseValue, caseBody)
            : new DefaultCaseStmt(caseBody);
          finish(caseStmt, caseMark);
          cases.add(caseStmt);
        }

        s = new SwitchStmt(label, switchValue, cases);
        break;
      }
      default:
        throw new AssertionError(t.text);
    }
    return s;
  }

  private AbstractStatement<?> parseStatementWithoutLabel()
      throws ParseException {
    Mark m = tq.mark();

    Token<JsTokenType> t = tq.peek();

    if (JsTokenType.KEYWORD == t.type) {
      AbstractStatement<?> s;
      switch (Keyword.fromString(t.text)) {
        case FOR: case DO: case WHILE: case SWITCH:
          s = parseLoopOrSwitch("");
          break;
        case IF:
        {
          tq.advance();
          List<Pair<Expression, Statement>> clauses =
            new ArrayList<Pair<Expression, Statement>>();
          Statement elseClause = null;
          boolean sawElse;
          do {
            tq.expectToken(Punctuation.LPAREN);
            Expression cond = parseExpressionInt(true);
            tq.expectToken(Punctuation.RPAREN);
            Statement body = parseBody(false);
            sawElse = tq.checkToken(Keyword.ELSE);
            clauses.add(new Pair<Expression, Statement>(cond, body));
          } while (sawElse && tq.checkToken(Keyword.IF));
          if (sawElse) {
            elseClause = parseBody(true);
          }
          s = new Conditional(clauses, elseClause);
          break;
        }
        case VAR:
        case CONST:
          return parseDeclarationsOrExpression(false);
        case FUNCTION:
        {
          Mark fs = tq.mark();
          tq.advance();
          if (tq.lookaheadToken(Punctuation.LPAREN)) {
            // If no name, then treat it as an expression
            tq.rewind(fs);
            return parseExpressionStmt(false);
          } else {  // a function declaration
            Identifier identifier = parseIdentifierNode(false);
            tq.expectToken(Punctuation.LPAREN);
            FormalParamList params = parseFormalParams();
            tq.expectToken(Punctuation.RPAREN);
            if (!tq.lookaheadToken(Punctuation.LCURLY)) {
              tq.expectToken(Punctuation.LCURLY);
            }
            Statement body = parseStatementWithoutLabel();
            FunctionConstructor fc = new FunctionConstructor(
                identifier, params.params, (Block) body);
            finish(fc, m);
            s = new FunctionDeclaration(identifier, fc);
            finish(s, m);
          }
          break;
        }
        case RETURN:
        {
          tq.advance();
          AbstractExpression<?> value;
          // Check for semicolon insertion without lookahead since return is a
          // restricted production.  See the grammar above and ES262 S7.9.1
          if (semicolonInserted() || tq.lookaheadToken(Punctuation.SEMI)) {
            value = new UndefinedLiteral();
            value.setFilePosition(FilePosition.endOf(tq.lastPosition()));
          } else {
            value = parseExpressionInt(false);
          }
          s = new ReturnStmt(value);
          break;
        }
        case BREAK:
        {
          tq.advance();
          String targetLabel = "";
          if (!semicolonInserted() && JsTokenType.WORD == tq.peek().type) {
            targetLabel = parseIdentifier(false);
          }
          s = new BreakStmt(targetLabel);
          break;
        }
        case CONTINUE:
        {
          tq.advance();
          String targetLabel = "";
          if (!semicolonInserted() && JsTokenType.WORD == tq.peek().type) {
            targetLabel = parseIdentifier(false);
          }
          s = new ContinueStmt(targetLabel);
          break;
        }
        case DEBUGGER:
        {
          tq.advance();
          s = new DebuggerStmt();
          break;
        }
        case THROW:
        {
          tq.advance();
          if (semicolonInserted()) {
            throw new ParseException(new Message(
                MessageType.EXPECTED_TOKEN,
                FilePosition.endOf(tq.lastPosition()),
                MessagePart.Factory.valueOf("<expression>"),
                MessagePart.Factory.valueOf("<newline>")));
          }
          s = new ThrowStmt(parseExpressionInt(false));
          break;
        }
        case TRY:
        {
          tq.advance();
          Statement body = parseBody(true);
          CatchStmt handler;
          FinallyStmt finallyBlock;
          Mark m2 = tq.mark();
          boolean sawFinally = tq.checkToken(Keyword.FINALLY);
          if (sawFinally) {
            handler = null;
          } else {
            tq.expectToken(Keyword.CATCH);
            tq.expectToken(Punctuation.LPAREN);
            Identifier idNode = parseIdentifierNode(false);
            Declaration exvar = new Declaration(idNode, (Expression)null);
            exvar.setFilePosition(idNode.getFilePosition());
            exvar.setComments(idNode.getComments());
            tq.expectToken(Punctuation.RPAREN);
            handler = new CatchStmt(exvar, parseBody(true));
            finish(handler, m2);
            m2 = tq.mark();
            sawFinally = tq.checkToken(Keyword.FINALLY);
          }
          if (sawFinally) {
            Statement st = parseBody(true);
            finallyBlock = new FinallyStmt(st);
            finish(finallyBlock, m2);
          } else {
            finallyBlock = null;
          }
          s = new TryStmt(body, handler, finallyBlock);
          break;
        }
        case WITH:
        {
          tq.advance();
          tq.expectToken(Punctuation.LPAREN);
          Expression scopeObject = parseExpressionInt(true);
          tq.expectToken(Punctuation.RPAREN);
          Statement body = parseBody(true);
          s = new WithStmt(scopeObject, body);
          break;
        }
        default:
          return parseExpressionStmt(false);
      }
      finish(s, m);
      return s;
    } else if (tq.checkToken(Punctuation.LCURLY)) {
      // In a statement a curly block opens a block.
      // Blocks don't have a scope associated, so are effectively useless,
      // except to group statements in a loop.
      List<Statement> blockParts = new ArrayList<Statement>();
      while (!tq.checkToken(Punctuation.RCURLY)) {
        blockParts.add(parseTerminatedStatement());
      }
      Block b = new Block(blockParts);
      finish(b, m);
      return b;
    } else if (tq.checkToken(Punctuation.SEMI)) {
      return noop(tq.lastPosition());
    } else {
      return parseExpressionStmt(false);
    }
  }

  /**
   * Parses an expression.
   * @return non null.
   */
  public Expression parseExpression(boolean insertionProtected)
      throws ParseException {
    return parseExpressionInt(insertionProtected);
  }

  private AbstractExpression<?> parseExpressionInt(boolean insertionProtected)
      throws ParseException {
    Mark m = tq.mark();
    AbstractExpression<?> e = parseOp(Integer.MAX_VALUE, insertionProtected);
    // Handle comma operator
    while ((insertionProtected || !semicolonInserted())
           && tq.checkToken(Punctuation.COMMA)) {
      // The comma operator is left-associative so parse expression part in loop
      // instead of recursing
      Expression right = parseExpressionPart(insertionProtected);
      e = Operation.create(Operator.COMMA, e, right);
      finish(e, m);
    }
    return e;
  }

  /**
   * Parses an expression part -- one that can appear inside a comma separated
   * list.  This differs from {@link #parseExpression} in that it will not parse
   * <code>a, b</code> as the comma will be interpreted as a terminator,
   * although it will parse <code>(a, b)</code>.
   * @return non null.
   */
  public Expression parseExpressionPart(boolean insertionProtected)
      throws ParseException {
    return parseOp(Integer.MAX_VALUE, insertionProtected);
  }

  private AbstractExpression<?> parseOp(
      int precedence, boolean insertionProtected)
      throws ParseException {
    AbstractExpression<?> left = null;
    // Handle prefix operations
    {
      Token<JsTokenType> t = tq.peek();
      Operator op = Operator.lookupOperation(t.text, OperatorType.PREFIX);
      if (null != op) {
        Mark m = tq.mark();
        tq.advance();
        int opprec = op.getPrecedence();
        if (opprec < precedence) {
          // The opprec + 1 may look a bit odd but it allows binary operators
          // to associate when they have the same precedence as the prefix
          // op preceding them.  This is the desired behavior:
          // new Foo[4] should parenthesize as new (Foo[4]) as verified by
          // the fact that new Object['toString'] fails to parse in FF.
          // It introduces no problem since there are no right-associative
          // binary operators with precedence 2 or 5.
          left = parseOp(opprec + 1, insertionProtected);
        } else {
          throw new ParseException(
              new Message(MessageType.UNEXPECTED_TOKEN, t.pos,
                          MessagePart.Factory.valueOf(t.text)));
        }
        try {
          left = Operation.create(op, left);
        } catch (IllegalArgumentException e) {
          throw new ParseException(
              new Message(MessageType.ASSIGN_TO_NON_LVALUE, t.pos,
                          MessagePart.Factory.valueOf(t.text)));
        }
        finish(left, m);
        // Not pulling multiple operators off the stack means that
        // some prefix operator nestings are impossible.  This is intended.
        // This prevents such things as (new (++i)).
        // This only affects the new operator though since it is the only
        // prefix operator with a precedence != 4.
      }
      if (null == left) {
        left = parseExpressionAtom();
      }
    }

    // Parse binary operators, except comma.
    while (!tq.isEmpty()) {
      Token<JsTokenType> t = tq.peek();
      // If it is a binary op then we should consider using it
      Operator op = Operator.lookupOperation(t.text, OperatorType.INFIX);
      if (null == op) {
        op = Operator.lookupOperation(t.text, OperatorType.BRACKET);
        if (null == op) {
          op = Operator.lookupOperation(t.text, OperatorType.TERNARY);
          // Check for semicolon insertion since postfix operators are
          // "restricted productions" according to ES262 S7.9.1.
          if (null == op) {
            if (!semicolonInserted()) {
              op = Operator.lookupOperation(t.text, OperatorType.POSTFIX);
            }
            if (null == op) { break; }
          }
        }
      } else if (Operator.COMMA == op) {
        break;
      }
      int opprec = op.getPrecedence();
      if (!(opprec < precedence
            || (opprec == precedence
                && Associativity.RIGHT == op.getAssociativity()))) {
        break;
      }
      Mark opStart = tq.mark();
      int nMessages = mq.getMessages().size();
      tq.advance();  // Consume the operator token

      Expression right;
      try {
        // Recurse to parse operator arguments.
        if (OperatorType.BRACKET == op.getType()) {
          if (Operator.FUNCTION_CALL == op) {
            List<Expression> actuals;
            if (tq.checkToken(op.getClosingSymbol())) {
              actuals = Collections.<Expression>emptyList();
            } else {
              actuals = new ArrayList<Expression>();
              do {
                actuals.add(parseExpressionPart(true));
              } while (tq.checkToken(Punctuation.COMMA));
              tq.expectToken(op.getClosingSymbol());
            }

            right = new ActualList(actuals);
          } else {
            right = parseExpressionInt(true);
            tq.expectToken(op.getClosingSymbol());
          }
        } else if (OperatorType.POSTFIX == op.getType()) {
          right = null;
        } else if (OperatorType.TERNARY == op.getType()) {
          right = parseExpressionPart(insertionProtected);
        } else if (Operator.MEMBER_ACCESS != op) {
          right = parseOp(opprec, insertionProtected);
        } else {
          // The . operator only accepts a reference on the right.
          // No a.b.4 or a.b.(c.d)
          right = parseReference(true);
        }
      } catch (ParseException ex) {
        // According to
        // http://www.mozilla.org/js/language/js20/rationale/syntax.html
        // semicolon insertion requires that we reconsider the decision to
        // treat op as a binary op if it could be a prefix op.

        // Line-Break Semicolon Insertion
        // If the first through the nth tokens of a JavaScript program form
        // are grammatically valid but the first through the n+1st tokens
        // are not and there is a line break between the nth tokens and the
        // n+1st tokens, then the parser tries to parse the program again
        // after inserting a VirtualSemicolon token between the nth and the
        // n+1st tokens.
        if ((Operator.FUNCTION_CALL == op
             || null != Operator.lookupOperation(
                 op.getOpeningSymbol(), OperatorType.PREFIX))
            && !insertionProtected) {
          Mark m3 = tq.mark();
          tq.rewind(opStart);
          if (semicolonInserted()) {
            List<Message> messages = mq.getMessages();
            if (nMessages < messages.size()) {
              messages.subList(nMessages, messages.size()).clear();
            }
            FilePosition semiPoint = FilePosition.endOf(tq.lastPosition());
            messages.add(new Message(
                             MessageType.SEMICOLON_INSERTED, semiPoint));
            return left;
          } else {
            tq.rewind(m3);
          }
        }
        throw ex;
      }
      FilePosition leftPos = left.getFilePosition();
      switch (op.getType()) {
        case TERNARY:
          {
            tq.expectToken(op.getClosingSymbol());
            Expression farRight = parseExpressionPart(insertionProtected);
            left = Operation.create(op, left, right, farRight);
          }
          break;
          case BRACKET:
            if (Operator.FUNCTION_CALL == op) {
              // Function calls can take nothing or multiple on the right, so
              // we wrap function calls up in an ActualList.
              ActualList actuals = (ActualList) right;
              List<? extends Expression> params = actuals.children();
              Expression[] operands = new Expression[params.size() + 1];
              operands[0] = left;
              for (int i = 1; i < operands.length; ++i) {
                operands[i] = params.get(i - 1);
              }
              left = Operation.create(op, operands);
            } else {
              left = Operation.create(op, left, right);
            }
            break;
          case INFIX:
            if (op.getCategory() == OperatorCategory.ASSIGNMENT
                && !left.isLeftHandSide()) {
              throw new ParseException(
                  new Message(MessageType.ASSIGN_TO_NON_LVALUE,
                              t.pos, MessagePart.Factory.valueOf(t.text)));
            }
            left = Operation.create(op, left, right);
            break;
          case POSTFIX:
            if (op.getCategory() == OperatorCategory.ASSIGNMENT
                && !left.isLeftHandSide()) {
              throw new ParseException(
                  new Message(MessageType.ASSIGN_TO_NON_LVALUE,
                              t.pos, MessagePart.Factory.valueOf(t.text)));
            }
            left = Operation.create(op, left);
            break;
          default:
            throw new AssertionError();
      }
      finish(left, leftPos, Collections.<Token<JsTokenType>>emptyList());
    }
    return left;
  }

  private boolean semicolonInserted() throws ParseException {
    if (tq.isEmpty() || tq.lookaheadToken(Punctuation.RCURLY)) { return true; }
    FilePosition last = tq.lastPosition(),
              current = tq.currentPosition();
    return null == last
        || current.startLogicalLineNo() > last.endLogicalLineNo();
  }

  private double toNumber(Token<JsTokenType> t) {
    // Double.parseDouble is not locale dependent.
    return Double.parseDouble(t.text);
  }

  private String floatToString(Token<JsTokenType> t) {
    return NumberLiteral.numberToString(new BigDecimal(t.text));
  }

  private NumberLiteral toNumberLiteral(Token<JsTokenType> t) {
    return new RealLiteral(toNumber(t));
  }

  private long toInteger(Token<JsTokenType> t) {
    Long longValue = Long.decode(t.text);

    // Make sure that the number fits in a 51 bit mantissa
    long lv = longValue.longValue();
    if (lv < 0) { lv = ~lv; }
    if (0 != (lv & ~((1L << 51) - 1))) {
      // Could cast to double and back to long and see if precision lost
      // inside a strict fp block?
      mq.addMessage(MessageType.UNREPRESENTABLE_INTEGER_LITERAL,
                    t.pos, MessagePart.Factory.valueOf(t.text));
    }

    return longValue.longValue();
  }

  private IntegerLiteral toIntegerLiteral(Token<JsTokenType> t) {
    return new IntegerLiteral(toInteger(t));
  }

  @SuppressWarnings("fallthrough")
  private AbstractExpression<?> parseExpressionAtom() throws ParseException {
    AbstractExpression<?> e;
    Mark m = tq.mark();

    Token<JsTokenType> t = tq.pop();
    typeswitch: switch (t.type) {
      case STRING:
        e = new StringLiteral(t.text);
        break;
      case INTEGER:
        if (integerPartIsOctal(t.text)) {
          mq.addMessage(
              MessageType.OCTAL_LITERAL, MessageLevel.LINT,
              t.pos, MessagePart.Factory.valueOf(t.text));
        }
        e = toIntegerLiteral(t);
        break;
      case FLOAT:
        if (integerPartIsOctal(t.text)) {
          mq.addMessage(
              MessageType.OCTAL_LITERAL, MessageLevel.ERROR,
              t.pos, MessagePart.Factory.valueOf(t.text));
        }
        e = toNumberLiteral(t);
        break;
      case REGEXP:
      {
        e = new RegexpLiteral(t.text);
        // Check letters.  Warn on s suffix character as non-FF.
        String modifiers = t.text.substring(t.text.lastIndexOf("/") + 1);
        if (!RegexpLiteral.areRegexpModifiersValid(modifiers)) {
          mq.addMessage(
                  MessageType.UNRECOGNIZED_REGEX_MODIFIERS, t.pos,
                  MessagePart.Factory.valueOf(modifiers));
        }
        break;
      }
      case KEYWORD:
      {
        Keyword k = Keyword.fromString(t.text);
        if (null != k) {
          switch (k) {
            case NULL:
              e = new NullLiteral();
              break typeswitch;
            case TRUE:
              e = new BooleanLiteral(true);
              break typeswitch;
            case FALSE:
              e = new BooleanLiteral(false);
              break typeswitch;
            case FUNCTION:
            {
              Identifier identifier = null;
              if (!tq.isEmpty() && JsTokenType.WORD == tq.peek().type) {
                identifier = parseIdentifierNode(false);
              } else {
                identifier = new Identifier(null);
                identifier.setFilePosition(
                    FilePosition.endOf(tq.lastPosition()));
              }
              tq.expectToken(Punctuation.LPAREN);
              FormalParamList params = parseFormalParams();
              tq.expectToken(Punctuation.RPAREN);
              if (!tq.lookaheadToken(Punctuation.LCURLY)) {
                tq.expectToken(Punctuation.LCURLY);
              }
              Statement body = parseStatementWithoutLabel();
              e = new FunctionConstructor(
                  identifier, params.params, (Block) body);
              break typeswitch;
            }
            default:
              break;  // Will be handled by the word handler below
          }
        }
        // fall through
      }
      case WORD:
      {
        String identifier = t.text;
        if (UndefinedLiteral.VALUE_NAME.equals(identifier)) {
          // Can't leave this as a reference or we'd have to allow references
          // to have declared types that are not signature types
          e = new UndefinedLiteral();
        } else {
          Keyword kw = Keyword.fromString(identifier);
          if (null != kw) {
            if (Keyword.THIS != kw) {
              mq.addMessage(MessageType.RESERVED_WORD_USED_AS_IDENTIFIER,
                            tq.lastPosition(),
                            Keyword.fromString(identifier));
            }
          } else if (!isIdentifier(identifier)) {
            mq.addMessage(MessageType.INVALID_IDENTIFIER,
                          tq.lastPosition(),
                          MessagePart.Factory.valueOf(identifier));
          }
          Identifier idNode = new Identifier(decodeIdentifier(identifier));
          finish(idNode, m);
          e = new Reference(idNode);
          e.setFilePosition(idNode.getFilePosition());
        }
        break;
      }
      case PUNCTUATION:
        switch (Punctuation.fromString(t.text)) {
          case LPAREN:
            e = parseExpressionInt(true);
            tq.expectToken(Punctuation.RPAREN);
            return e;  // Don't pull comments outside parens inside
          case LSQUARE:
          {
            List<Expression> elements = new ArrayList<Expression>();

            // True iff a comma represents an implicit undefined value
            boolean empty = true;
            while (!tq.checkToken(Punctuation.RSQUARE)) {
              boolean lastComma = false;
              Mark cm = tq.mark();  // If lastComma, mark of the last comma.
              while (tq.checkToken(Punctuation.COMMA)) {
                if (empty) {
                  UndefinedLiteral vl = new UndefinedLiteral();
                  finish(vl, cm);
                  elements.add(vl);
                } else {
                  empty = true;
                }
                lastComma = true;
                cm = tq.mark();
              }

              if (!tq.checkToken(Punctuation.RSQUARE)) {
                elements.add(parseExpressionPart(true));
                lastComma = false;
                empty = false;
              } else {
                if (lastComma) {
                  mq.addMessage(MessageType.NOT_IE, cm.getFilePosition());
                }
                break;
              }
            }

            e = new ArrayConstructor(elements);
            break;
          }
          case LCURLY:
          {
            List<Pair<Literal, Expression>> properties
                = new ArrayList<Pair<Literal, Expression>>();
            if (!tq.checkToken(Punctuation.RCURLY)) {
              boolean sawComma;
              do {
                Mark km = tq.mark();
                Token<JsTokenType> keyToken = tq.peek();
                Literal key;
                switch (keyToken.type) {
                  case STRING:
                    key = new StringLiteral(keyToken.text);
                    tq.advance();
                    break;
                  case FLOAT:
                    key = new StringLiteral(
                        StringLiteral.toQuotedValue(floatToString(keyToken)));
                    tq.advance();
                    break;
                  case INTEGER:
                    key = new StringLiteral(
                        StringLiteral.toQuotedValue("" + toInteger(keyToken)));
                    tq.advance();
                    break;
                  default:
                    key = new StringLiteral(
                        StringLiteral.toQuotedValue(parseIdentifier(true)));
                    break;
                }
                finish(key, km);
                tq.expectToken(Punctuation.COLON);
                Expression value = parseExpressionPart(true);
                properties.add(Pair.pair(key, value));
                Mark cm = tq.mark();
                sawComma = tq.checkToken(Punctuation.COMMA);
                if (sawComma && tq.lookaheadToken(Punctuation.RCURLY)) {
                  tq.rewind(cm);
                  mq.addMessage(MessageType.NOT_IE, tq.currentPosition());
                  tq.advance();
                  break;
                }
              } while (sawComma);
              tq.expectToken(Punctuation.RCURLY);
            }

            e = new ObjectConstructor(properties);
            break;
          }
          default:
            e = null;
            break;
        }
        break;
      default:
        e = null;
        break;
    }

    if (null == e) {
      if (recoverFromFailure) {
        tq.rewind(m);
        // create a placeholder expression
        FilePosition pos = FilePosition.span(
            tq.lastPosition(), tq.currentPosition());
        mq.addMessage(MessageType.PLACEHOLDER_INSERTED, pos);
        Identifier idNode = new Identifier("_");
        finish(idNode, pos, Collections.<Token<JsTokenType>>emptyList());
        e = new Reference(idNode);
        e.setFilePosition(idNode.getFilePosition());
      } else {
        throw new ParseException(
            new Message(
                MessageType.UNEXPECTED_TOKEN, t.pos,
                MessagePart.Factory.valueOf(t.text)));
      }
    }

    finish(e, m);
    return e;
  }

  private Reference parseReference(boolean allowReservedWords)
      throws ParseException {
    Mark m = tq.mark();
    Identifier idNode = parseIdentifierNode(allowReservedWords);
    Reference r = new Reference(idNode);
    finish(r, m);
    return r;
  }

  private Identifier parseIdentifierNode(boolean allowReservedWords)
      throws ParseException {
    Mark m = tq.mark();
    String identifierName = parseIdentifier(allowReservedWords);
    Identifier ident = new Identifier(identifierName);
    finish(ident, m);
    return ident;
  }

  private ExpressionStmt parseExpressionStmt(boolean insertionProtected)
      throws ParseException {
    Mark m = tq.mark();
    ExpressionStmt es =
      new ExpressionStmt(parseExpressionInt(insertionProtected));
    finish(es, m);
    return es;
  }

  private Expression parseExpressionOrNoop(
      AbstractExpression<?> def, boolean insertionProtected)
      throws ParseException {
    Mark m = tq.mark();
    if (tq.checkToken(Punctuation.SEMI)) {
      finish(def, m);
      return def;
    }
    Expression e = parseExpressionInt(insertionProtected);
    tq.expectToken(Punctuation.SEMI);
    return e;
  }

  private static boolean isTerminal(Statement s) {
    return ((s instanceof Loop && !(s instanceof DoWhileLoop))
            || s instanceof Conditional || s instanceof FunctionDeclaration
            || s instanceof Block || s instanceof TryStmt
            || s instanceof ForEachLoop || s instanceof SwitchStmt)
            || s instanceof Noop;
  }

  private AbstractStatement<?> parseTerminatedStatement()
      throws ParseException {
    AbstractStatement<?> s = (AbstractStatement<?>) parseStatement();
    if (!isTerminal(s)) {
      checkSemicolon();
    }
    return s;
  }

  private void checkSemicolon() throws ParseException {
    // Look for a semicolon
    if (!tq.checkToken(Punctuation.SEMI)) {
      // none found, so maybe do insertion
      if (semicolonInserted()) {
        FilePosition semiPoint = FilePosition.endOf(tq.lastPosition());
        mq.addMessage(MessageType.SEMICOLON_INSERTED, semiPoint);
        return;
      }
      tq.expectToken(Punctuation.SEMI);  // Just used to throw an exception
    }
  }

  // Visible for testing.
  static boolean integerPartIsOctal(String numberLiteral) {
    for (int i = 0, n = numberLiteral.length(); i < n; ++i) {
      char ch = numberLiteral.charAt(i);
      if (ch == '.') { return false; }
      if (ch != '0') { return i != 0 && ch >= '1' && ch <= '9'; }
    }
    return false;
  }

  private AbstractStatement<?> parseDeclarationsOrExpression(
      boolean insertionProtected)
      throws ParseException {
    Mark m = tq.mark();

    boolean isDeclaration;

    if (tq.checkToken(Keyword.VAR)) {
      isDeclaration = true;
    } else if (tq.checkToken(Keyword.CONST)) {
      isDeclaration = true;
      mq.addMessage(
          MessageType.NOT_IE, FilePosition.span(
              m.getFilePosition(), tq.lastPosition()));
    } else {
      isDeclaration = false;
    }

    if (isDeclaration) {
      AbstractStatement<?> s;
      Declaration d;
      {
        Identifier idNode = parseIdentifierNode(false);
        Expression initializer = null;
        if (tq.checkToken(Punctuation.EQ)) {
          initializer = parseExpressionPart(insertionProtected);
        }
        d = new Declaration(idNode, initializer);
        finish(d, m);
      }
      if (tq.checkToken(Punctuation.COMMA)) {
        List<Declaration> decls = new ArrayList<Declaration>();
        decls.add(d);
        do {
          Mark m2 = tq.mark();
          Identifier idNode = parseIdentifierNode(false);
          Expression initializer = null;
          if (tq.checkToken(Punctuation.EQ)) {
            initializer = parseExpressionPart(insertionProtected);
          }
          Declaration d2 = new Declaration(idNode, initializer);
          finish(d2, m2);
          decls.add(d2);
        } while (tq.checkToken(Punctuation.COMMA));
        MultiDeclaration md = new MultiDeclaration(decls);
        finish(md, m);
        s = md;
      } else {
        s = d;
      }
      return s;
    } else {
      return parseExpressionStmt(insertionProtected);
    }
  }

  private Statement parseBody(boolean terminal) throws ParseException {
    if (terminal || tq.lookaheadToken(Punctuation.LCURLY)) {
      return parseTerminatedStatement();
    } else {
      Statement s = parseStatement();
      tq.checkToken(Punctuation.SEMI);
      return s;
    }
  }

  private FormalParamList parseFormalParams() throws ParseException {
    List<FormalParam> params = new ArrayList<FormalParam>();
    if (!tq.lookaheadToken(Punctuation.RPAREN)) {
      do {
        Mark m = tq.mark();
        Identifier idNode = new Identifier(parseIdentifier(false));
        finish(idNode, m);
        FormalParam param = new FormalParam(idNode);
        finish(param, m);
        params.add(param);
      } while (tq.checkToken(Punctuation.COMMA));
    }

    return new FormalParamList(params, mq);
  }

  private static Noop noop(FilePosition fp) {
    Noop n = new Noop();
    n.setFilePosition(fp);
    n.setComments(Collections.<Token<?>>emptyList());
    return n;
  }

  private void finish(AbstractParseTreeNode<?> n, Mark startMark)
      throws ParseException {
    FilePosition start;
    List<Token<JsTokenType>> comments;

    Mark endMark = tq.mark();
    tq.rewind(startMark);
    try {
      start = tq.currentPosition();
      comments = tq.filteredTokens();
    } finally {
      tq.rewind(endMark);
    }
    finish(n, start, comments);
  }

  private void finish(
      AbstractParseTreeNode<?> n, FilePosition start,
      List<? extends Token<?>> comments)
      throws ParseException {
    n.setComments(comments);

    if (tq.isEmpty() || tq.currentPosition() != start) {
      n.setFilePosition(FilePosition.span(start, tq.lastPosition()));
    } else {
      // Happens when no token consumed such as for the inferred noop following
      // the first case statment in
      // switch (a) { case A: case B: break; }
      n.setFilePosition(FilePosition.startOf(start));
    }
  }

  private static class FormalParamList {
    public List<FormalParam> params;

    public FormalParamList(List<FormalParam> params, MessageQueue mq) {
      Set<String> paramNames = new HashSet<String>();
      paramNames.add("arguments");
      paramNames.add("this");
      for (FormalParam p : params) {
        if (!paramNames.add(p.getIdentifierName())) {
         mq.addMessage(
             MessageType.DUPLICATE_FORMAL_PARAM,
             p.getFilePosition(),
             MessagePart.Factory.valueOf(p.getIdentifierName()));
        }
      }
      this.params = params;
    }
  }

  /**
   * Placeholder node for the actuals in a function call.  Never appears in the
   * final tree.
   */
  private static class ActualList extends AbstractExpression<Expression> {
    ActualList(List<Expression> actuals) {
      createMutation().appendChildren(actuals).execute();
    }

    @Override
    public Object getValue() { return null; }

    public void render(RenderContext rc) {
      throw new UnsupportedOperationException();
    }
  }
}
