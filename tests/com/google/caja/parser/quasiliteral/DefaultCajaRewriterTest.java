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
import com.google.caja.lexer.ParseException;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodes;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Operator;
import com.google.caja.parser.js.Statement;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessageType;
import com.google.caja.util.RhinoTestBed;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;

import junit.framework.AssertionFailedError;

/**
 * @author ihab.awad@gmail.com
 */
public class DefaultCajaRewriterTest extends RewriterTestCase {

  private boolean wartsMode = false;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    wartsMode = false;
  }

  /**
   * Welds together a string representing the repeated pattern of
   * expected test output for assigning to an outer variable.
   *
   * @author erights@gmail.com
   */
  private static String weldSetPub(String obj,
                                   String varName,
                                   String value,
                                   String tempObj,
                                   String tempValue) {
    return weldSet(obj, varName, value, "Pub", tempObj, tempValue);
  }

  private static String weldSetProp(String varName,
                                    String value,
                                    String tempValue) {
    return
        tempValue + " = " + value + "," +
        "    t___." + varName + "_canSet___ ?" +
        "    t___." + varName + " = " + tempValue + ":" +
        "    ___.setProp(t___, '" + varName + "', " + tempValue + ")";
  }

  private static String weldSet(String obj,
                                String varName,
                                String value,
                                String pubOrProp,
                                String tempObj,
                                String tempValue) {
    return
        tempObj + " = " + obj + "," +
        tempValue + " = " + value + "," +
        "    " + tempObj + "." + varName + "_canSet___ ?" +
        "    " + tempObj + "." + varName + " = " + tempValue + ":" +
        "    ___.set" + pubOrProp + "(" + tempObj + ", '" + varName + "', " + tempValue + ")";
  }

  /**
   * Welds together a string representing the repeated pattern of
   * expected test output for reading an outer variable.
   *
   * @author erights@gmail.com
   */
  private static String weldReadPub(String obj, String varName, String tempObj) {
    return weldReadPub(obj, varName, tempObj, false);
  }

  private static String weldReadPub(String obj, String varName, String tempObj, boolean flag) {
    return
        "(" +
        "(" + tempObj + " = " + obj + ")," +
        "(" + tempObj + "." + varName + "_canRead___ ?" +
        "    " + tempObj + "." + varName + ":" +
        "    ___.readPub(" + tempObj + ", '" + varName + "'" + (flag ? ", true" : "") + "))"+
        ")";
  }

  public static String weldPrelude(String name) {
    return "var " + name + " = ___.readImport(IMPORTS___, '" + name + "');";
  }

  public void testToString() throws Exception {
    assertConsistent("var z={toString:function(){return 'blah';}}; ''+z;");
    assertConsistent(
        "  function foo(){this.x_ = 1;}"
        + "caja.def(foo, Object, {"
        + "    toString: function (){"
        + "      return this.x_;"
        + "    }"
        + "});"
        + "'' + (new foo);");
    rewriteAndExecute(
        "testImports.exports = {};",
        "function Foo(){this.toString=function(){return '1';};}" +
        "exports.foo = new Foo;",
        "if (testImports.exports.foo.toString_canSet___) {" +
        "  fail('toString fastpath gets set.');" +
        "}"
        );
    rewriteAndExecute(
        "testImports.exports = {};",
        "exports.obj = {toString:function(){return '1';}};",
        "if (testImports.exports.obj.toString_canSet___) {" +
        "  fail('toString fastpath gets set.');" +
        "}"
        );
    rewriteAndExecute(
        "testImports.exports = {};",
        "function Foo(f){this.toString=f;}" +
        "function FooMaker(f) {return new Foo(f);}" +
        "exports.FooMaker = FooMaker;",
        "assertThrows(function() {testImports.exports.FooMaker(function(){return '1';});});"
        );
    rewriteAndExecute(
        "testImports.exports = {};",
        "function objMaker(f) {return {toString:f};}" +
        "exports.objMaker = objMaker;",
        "assertThrows(function() {testImports.exports.objMaker(function(){return '1';});});"
        );
  }

  public void testInitializeMap() throws Exception {
    assertConsistent("var zerubabel={bobble:2, apple:1}; zerubabel.apple;");
  }

  public void testValueOf() throws Exception {
    checkFails("var a = {valueOf:1};", "The valueOf property must not be set");
    checkFails("var a={}; a.valueOf=1;", "The valueOf property must not be set");
    checkFails(
        "  function f(){this;}"
        + "f.prototype.valueOf=1;",
        "The valueOf property must not be set");
    checkFails(
        "  function f(){this;}"
        + "caja.def(f, Object, {valueOf:1});",
        "The valueOf property must not be set");
    checkFails(
        "  function f(){this;}"
        + "caja.def(f, Object, {}, {valueOf:1});",
        "The valueOf property must not be set");
    checkFails(
        "var a={}; delete a.valueOf;",
        "The valueOf property must not be deleted");
    rewriteAndExecute("var a={}; assertThrows(function(){a['valueOf']=1});");
    rewriteAndExecute(
        "var a={}; assertThrows(function(){delete a['valueOf'];})");
  }

  public void testFunctionDoesNotMaskVariable() throws Exception {
    // Regress http://code.google.com/p/google-caja/issues/detail?id=370
    // TODO(ihab.awad): Enhance test framework to allow "before" and "after"
    // un-cajoled code to be executed, then change this to a functional test.
    checkSucceeds(
        "  function boo() { return x; }"
        + "var x;",
        "  var boo;"
        + "boo = ___.simpleFunc(function() { return x; }, 'boo');"
        + ";"
        + "var x;");
  }

  public void testAssertEqualsCajoled() throws Exception {
    try {
      rewriteAndExecute("assertEquals(1, 2);");
    } catch (AssertionFailedError e) {
      return;
    }
    fail("Assertions do not work in cajoled mode");
  }

  public void testAssertThrowsCajoledNoError() throws Exception {
    rewriteAndExecute(
        "  assertThrows(function() { throw 'foo'; });");
    rewriteAndExecute(
        "  assertThrows("
        + "    function() { throw 'foo'; },"
        + "    'foo');");
  }

  public void testAssertThrowsCajoledErrorNoMsg() throws Exception {
    try {
      rewriteAndExecute("assertThrows(function() {});");
    } catch (AssertionFailedError e) {
      return;
    }
    fail("Assertions do not work in cajoled mode");
  }

  public void testAssertThrowsCajoledErrorWithMsg() throws Exception {
    try {
      rewriteAndExecute("assertThrows(function() {}, 'foo');");
    } catch (AssertionFailedError e) {
      return;
    }
    fail("Assertions do not work in cajoled mode");
  }

  public void testCallAndSet() throws Exception {
      rewriteAndExecute("function Point(x){ this.x_ = x; }"
          + "caja.def(Point,Object,{"
          + "  toString: function(){ return \"<\"+this.x_+\">\"; },"
          + "  getX: function(){ return this.x_; },"
          + "  setGetX: function(newGetX) { this.getX = newGetX; }"
          + "});"
          + "var pt = new Point(3);"
          + "pt.getX();"
          + "pt.setGetX(Date);"
          + "assertThrows(function() { pt.getX(); });"
          );
  }

  public void testFreeVariables() throws Exception {
    checkSucceeds(
        "var y = x;",
        weldPrelude("x") +
        "var y = x;");
    checkSucceeds(
        "function() { var y = x; };",
        weldPrelude("x") +
        "___.simpleFrozenFunc(function() {" +
        "  var y = x;" +
        "});");
  }

  public void testWartyReflectiveMethodInvocation() throws Exception {
    wartsMode = true;
    assertConsistent(
        "(function (first, second){this; return 'a'+first+'b'+second;}).call([],8,9);");
    assertConsistent(
        "(function (a,b){this;return 'a'+a+'b'+b;}).apply([],[8,9]);");
    assertConsistent(
        "(function (first, second){this; return 'a'+first+'b'+second;}).bind([],8)(9);");
  }

  public void testReflectiveMethodInvocation() throws Exception {
    assertConsistent(
        "(function (first, second){return 'a'+first+'b'+second;}).call([],8,9);");
    assertConsistent(
        "var a=[]; [].push.call(a, 5, 6); a.join(',');");
    assertConsistent(
        "(function (a,b){return 'a'+a+'b'+b;}).apply([],[8,9]);");
    assertConsistent(
        "var a=[]; [].push.apply(a, [5, 6]); a.join(',');");
    assertConsistent(
        "[].sort.apply([6,5]).join('');");
    assertConsistent(
        "function Point() {}" +
        "Point.prototype.add3 = function(x){return x+3;};" +
        "var p = new Point();" +
        "p.add3.call(p, 4);");
    assertConsistent(
        "function Point() {}" +
        "Point.prototype.add3 = function(x){return x+3;};" +
        "var p = new Point();" +
        "p.add3.apply(p, [4]);");
    assertConsistent(
        "(function (first, second){return 'a'+first+'b'+second;}).bind([],8)(9);");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=242"
   * >bug#242</a> is fixed.
   * <p>
   * The actual Function.bind() method used to be whitelisted and written to return a frozen
   * simple-function, allowing it to be called from all code on all functions. As a result,
   * if an <i>outer hull breach</i> occurs -- if Caja code
   * obtains a reference to a JavaScript function value not marked as Caja-callable -- then
   * that Caja code could call the whitelisted bind() on it, and then call the result,
   * causing an <i>inner hull breach</i> which threatens kernel integrity.
   */
  public void testToxicBind() throws Exception {
    rewriteAndExecute(
        "var confused = false;" +
        "testImports.keystone = function() { confused = true; };",
        "assertThrows(function() {keystone.bind()();});",
        "assertFalse(confused);");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=590"
   * >bug#590</a> is fixed.
   * <p>
   * As a client of an object, Caja code must only be able to directly delete
   * <i>public</i> properties of non-frozen JSON containers. Due to this bug, Caja
   * code was able to delete <i>protected</i> properties of non-frozen JSON
   * containers.
   */
  public void testBadDelete() throws Exception {
    rewriteAndExecute(
        "testImports.badContainer = {secret_: 3469};",
        "assertThrows(function() {delete badContainer['secret_'];})",
        "assertEquals(testImports.badContainer.secret_, 3469);");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=548"
   * >bug#548</a> is fixed.
   * <p>
   * The Caja runtime (caja.js) relies on all prototype chains that it encounters being
   * well formed. One requirement is that for all functions <i>F</i>,
   * <tt><i>F</i>.prototype.constructor.prototype === <i>F</i>.prototype</tt>. If Caja
   * code could initialize the constructor property of protypical objects, then it could
   * cause this invariant to be violated.
   */
  public void testCorruptProtoChain() throws Exception {
    rewriteAndExecute(
        "function F(){}" +
        "assertThrows(function() {F.prototype.constructor = 3;})");
    rewriteAndExecute(
        "function F(){}" +
        "assertThrows(function() {caja.def(F,Object,{constructor:1});});");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=617"
   * >bug#617</a> is fixed.
   * <p>
   * The ES3 spec specifies an insane scoping rule which Firefox 2.0.0.15 "correctly"
   * implements according to the spec. The rule is that, within a named function
   * expression, the function name <i>f</i> is brought into scope by creating a new object
   * "as if by executing 'new Object()', adding an <tt>'<i>f</i>'</tt> property to this
   * object, and adding this object to the scope chain. As a result, all properties
   * inherited from <tt>Object.prototype</tt> shadow any outer lexically visible
   * declarations of those names as variable names.
   * <p>
   * Unfortunately, we're currently doing our JUnit testing using Rhino, which doesn't
   * engage in the questionable behavior of implementing specified but insane behavior.
   * As a result, the following test currently succeeds whether this bug is fixed or
   * not.
   */
  public void testNameFuncExprScoping() throws Exception {
    rewriteAndExecute(
        "assertEquals(0, function() { \n" +
        "  var propertyIsEnumerable = 0;\n" +
        "  return (function f() {\n" +
        "    return propertyIsEnumerable;\n" +
        "  })();\n" +
        "}());");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=469"
   * >bug#469</a> is fixed.
   * <p>
   * The members of the <tt>caja</tt> object available to Caja code
   * (i.e., the <tt>safeCaja</tt> object) must be frozen. And if they
   * are functions, they should be marked as simple-functions. Before
   * this bug was fixed, <tt>caja.js</tt> failed to do either.
   */
  public void testCajaPropsFrozen() throws Exception {
    rewriteAndExecute(";","0;",
    "assertTrue(___.isSimpleFunc(___.sharedImports.caja.def));");
    rewriteAndExecute(";","0;",
    "assertTrue(___.isFrozen(___.sharedImports.caja.def));");
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=292"
   * >bug#292</a> is fixed.
   * <p>
   * In anticipation of ES3.1, we should be able to index into strings
   * using indexes which are numbers or stringified numbers, so long as
   * they are in range.
   */
  public void testStringIndexing() throws Exception {
    rewriteAndExecute("assertEquals('b', 'abc'[1]);");

    // TODO(erights): This test isn't green because we haven't yet fixed the bug.
    if (false) {
      rewriteAndExecute("assertEquals('b', 'abc'['1']);");
    }
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=464"
   * >bug#464</a> is fixed.
   * <p>
   * Reading the apply property of a function should result in the apply
   * method as attached to that function.
   */
  public void testAttachedReflection() throws Exception {
    rewriteAndExecute(
        "function f() {}\n" +
        "f.apply;");
    // TODO(erights): Need more tests.
  }

  /**
   * Tests that <a href=
   * "http://code.google.com/p/google-caja/issues/detail?id=347"
   * >bug#347</a> is fixed.
   * <p>
   * The <tt>in</tt> operator should only test for properties visible to Caja.
   */
  public void testInVeil() throws Exception {
    rewriteAndExecute(
        "assertFalse('___FROZEN___' in Object);");
  }

  public void testPrimordialObjectExtension() throws Exception {
    wartsMode = true;
    // TODO(metaweta): Reenable once POE is part of warts mode.
    if (false) {
      assertConsistent(
          "caja.extend(Object, {x:1});" +
          "({}).x;");
      assertConsistent(
          "caja.extend(Number, {inc: function(){return this.valueOf() + 1;}});" +
          "(2).inc();");
      assertConsistent(
          "caja.extend(Array, {size: function(){return this.length + 1;}});" +
          "([5, 6]).size();");
      assertConsistent(
          "caja.extend(Boolean, {not: function(){return !this.valueOf();}});" +
          "(true).not();");
      assertConsistent(
          "function foo() {this;}" +
          "caja.def(foo, Object);" +
          "function bar() {this;}" +
          "caja.def(bar, foo);" +
          "var b=new bar;" +
          "caja.extend(Object, {x:1});" +
          "b.x;");
    }
  }

  public void testConstructorProperty() throws Exception {
    assertConsistent(
        "var pkg = {};" +
        "(function (){" +
        "  function Foo(x) {" +
        "    this.x_ = x;" +
        "  };" +
        "  Foo.prototype.getX = function(){ return this.x_; };" +
        "  pkg.Foo = Foo;" +
        "})();" +
        "var foo = new pkg.Foo(2);" +
        "foo.getX();");
  }

  public void testAttachedMethod() throws Exception {
    // See also <tt>testAttachedMethod()</tt> in <tt>HtmlCompiledPluginTest</tt>
    // to check cases where calling the attached method should fail.
    assertConsistent(
        "function Foo(){" +
        "  this.f = (function (){this.x_ = 1;}).bind(this);" +
        "  this.getX = (function (){return this.x_;}).bind(this);" +
        "}" +
        "var foo = new Foo();" +
        "foo.f();" +
        "foo.getX();");
    assertConsistent(
        "function Foo(){}" +
        "Foo.prototype.setX = function(x) { this.x_ = x; };" +
        "Foo.prototype.getX = function() { return this.x_; };" +
        "Foo.prototype.y = 1;" +
        "var foo=new Foo;" +
        "foo.setX(5);" +
        "''+foo.y+foo.getX();");
    assertConsistent(
        "function Foo(){}" +
        "caja.def(Foo, Object, {" +
        "  setX: function(x) { this.x_ = x; }," +
        "  getX: function() { return this.x_; }," +
        "  y: 1" +
        "});" +
        "var foo=new Foo;" +
        "foo.setX(5);" +
        "''+foo.y+foo.getX();");
    assertConsistent(
        "function Foo(){ this.gogo(); }" +
        "Foo.prototype.gogo = function() {" +
        "  this.setX = (function(x) { this.x_ = x; }).bind(this);" +
        "};" +
        "Foo.prototype.getX = function() { return this.x_; };" +
        "Foo.prototype.y = 1;" +
        "var foo=new Foo;" +
        "foo.setX(5);" +
        "''+foo.y+foo.getX();");
    assertConsistent(
        "function Foo(){ this.gogo(); }" +
        "caja.def(Foo, Object, {" +
        "  gogo: function() {" +
        "    this.setX = (function(x) { this.x_ = x; }).bind(this); " +
        "  }," +
        "  getX: function() { return this.x_; }," +
        "  y: 1" +
        "});" +
        "var foo=new Foo;" +
        "foo.setX(5);" +
        "''+foo.y+foo.getX();");
    assertConsistent(
        "function Foo() { this.gogo(); }" +
        "Foo.prototype.gogo = function () { " +
        "  this.Bar = function Bar(x){ " +
        "    this.x_ = x; " +
        "    this.getX = (function() { return this.x_; }).bind(this);" +
        "  }; " +
        "};" +
        "var foo = new Foo;" +
        "var Bar = foo.Bar;" +
        "var bar = new Bar(5);" +
        "bar.getX();");
    assertConsistent(
        "function Foo() { this.gogo(); }" +
        "Foo.prototype.gogo = function () { " +
        "  function Bar(x){ " +
        "    this.x_ = x; " +
        "  }" +
        "  Bar.prototype.getX = function () { return this.x_; };" +
        "  this.Bar = Bar;" +
        "};" +
        "var foo = new Foo;" +
        "var Bar = foo.Bar;" +
        "var bar = new Bar(5);" +
        "bar.getX();");
  }

  public void testAttachedMethodPublicProps() throws Exception {
    wartsMode = true;
    checkFails(
        "function (){" +
        "  this.x_ = 1;" +
        "}",
        "Public properties cannot end in \"_\"");
    checkFails(
        "function Foo(){}" +
        "Foo.prototype.m = function () {" +
        "  var y = function() {" +
        "    var z = function() {" +
        "      this.x_ = 1;" +
        "    }" +
        "  }" +
        "}",
        "Public properties cannot end in \"_\"");
  }

  ////////////////////////////////////////////////////////////////////////
  // Handling of synthetic nodes
  ////////////////////////////////////////////////////////////////////////

  public void testSyntheticIsUntouched() throws Exception {
    ParseTreeNode input = js(fromString("function foo() { this; arguments; }"));
    setTreeSynthetic(input);
    checkSucceeds(input, input);
  }

  public void testSyntheticNestedIsExpanded() throws Exception {
    ParseTreeNode innerInput = js(fromString("function foo() {}"));
    ParseTreeNode input = ParseTreeNodes.newNodeInstance(
        Block.class,
        null,
        Collections.singletonList(innerInput));
    setSynthetic(input);
    ParseTreeNode expectedResult = js(fromString(
        "var foo; { foo = ___.simpleFunc(function() {}, 'foo'); ; }"));
    checkSucceeds(input, expectedResult);
  }

  public void testSyntheticNestedFunctionIsExpanded() throws Exception {
    // This test checks that a synthetic function, as is commonly generated by Caja
    // to wrap JavaScript event handlers declared in HTML, is rewritten correctly.
    ParseTreeNode innerBlock = js(fromString("foo.x = 3;"));
    // By creating the function using substV(), the function nodes are all
    // synthetic. But the stuff inside it -- 'innerBlock' -- is not.
    ParseTreeNode input = substV(
        "{ function f() { @blockStmts*; } }",
        "blockStmts", innerBlock);
    // We expect the stuff in 'innerBlock' to be expanded, *but* we expect the
    // rewriter to be unaware of the enclosing function scope, so the temporary
    // variables generated by expanding 'innerBlock' spill out and get declared
    // outside the function rather than inside it.
    ParseTreeNode expectedResult = js(fromString(
        weldPrelude("foo")
        + "var x0___;"  // Temporary is declared up here ...
        + "var x1___;"
        + "function f() {"
        + "  "  // ... not down here!
        + "  " + weldSetPub("foo", "x", "3", "x0___", "x1___") + ";"
        + "}"));
    checkSucceeds(input, expectedResult);
  }

  ////////////////////////////////////////////////////////////////////////
  // Handling of nested blocks
  ////////////////////////////////////////////////////////////////////////

  public void testNestedBlockWithFunction() throws Exception {
    checkSucceeds(
        "{ function foo() {} }",
        "var foo;" +
        "{ foo = ___.simpleFunc(function() {}, 'foo'); ; }");
  }

  public void testNestedBlockWithVariable() throws Exception {
    checkSucceeds(
        "{ var x = g.y; }",
        weldPrelude("g") +
         "var x0___;" +
        "{" +
         "  var x = " + weldReadPub("g", "y", "x0___") + ";"+
         "}");
  }

  ////////////////////////////////////////////////////////////////////////
  // Specific rules
  ////////////////////////////////////////////////////////////////////////

  public void testWith() throws Exception {
    checkFails("with (dreams || ambiguousScoping) anything.isPossible();",
               "\"with\" blocks are not allowed");
    checkFails("with (dreams || ambiguousScoping) { anything.isPossible(); }",
               "\"with\" blocks are not allowed");
  }

  public void testForeachBadFreeVariable() throws Exception {
    checkAddsMessage(
        js(fromString("for (k in x) y;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FREE_VARIABLE);
    checkAddsMessage(
        js(fromString("for (k in x) { y; }")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FREE_VARIABLE);
    checkAddsMessage(
        js(fromString("for (k in x) ;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FREE_VARIABLE);
  }

  public void testForeach() throws Exception {
    // TODO(ihab.awad): Refactor some of these tests to be functional, rather than golden.
    checkSucceeds(
        "1; for (var k in x) { k; }",
        weldPrelude("x") +
        "var k;" +
        "var x0___;" +
        "var x1___;" +
        "1;" +
        "{" +
        "  x0___ = x;" +
        "  for (x1___ in x0___) {" +
        "    if (___.canEnumPub(x0___, x1___)) {" +
        "      k = x1___;" +
        "      { k; }" +
        "    }" +
        "  }" +
        "}");
    checkSucceeds(
        "2; try { } catch (e) { for (var k in x) { k; } }",
        weldPrelude("x") +
        "var k;" +
        "var x0___;" +
        "var x1___;" +
        "2;" +
        "try {" +
        "} catch (ex___) {" +
        "  try {" +
        "    throw ___.tameException(ex___);" +
        "  } catch (e) {" +
        "    {" +
        "      x0___ = x;" +
        "      for (x1___ in x0___) {" +
        "        if (___.canEnumPub(x0___, x1___)) {" +
        "          k = x1___;" +
        "          { k; }" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "}");
    checkSucceeds(
        "8; var k;" +
        "for (k in x) { k; }",
        weldPrelude("x") +
        "var x0___;" +
        "var x1___;" +
        "8;" +
        "var k;" +
        "{" +
        "  x0___ = x;" +
        "  for (x1___ in x0___) {" +
        "    if (___.canEnumPub(x0___, x1___)) {" +
        "      k = x1___;" +
        "      { k; }" +
        "    }" +
        "  }" +
        "}");
    checkSucceeds(
        "11; function foo() {" +
        "  for (var k in this) { k; }" +
        "}",
        "var foo;" +
        "foo =" +
        "(function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    var k;" +
        "    var x0___;" +
        "    var x1___;" +
        "    {" +
        "      x0___ = t___;" +
        "      for (x1___ in x0___) {" +
        "        if (___.canEnumProp(x0___, x1___)) {" +
        "          k = x1___;" +
        "          { k; }" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "  return foo;" +
        "})();" +
        "11;" +
        ";");
    checkSucceeds(
        "14; function foo() {" +
        "  var k;" +
        "  for (k in this) { k; }" +
        "}",
        "var foo;" +
        "foo =" +
        "(function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    var x0___;" +
        "    var x1___;" +
        "    var k;" +
        "    {" +
        "      x0___ = t___;" +
        "      for (x1___ in x0___) {" +
        "        if (___.canEnumProp(x0___, x1___)) {" +
        "          k = x1___;" +
        "          { k; }" +
        "        }" +
        "      }" +
        "    }" +
        "  }" +
        "  return foo;" +
        "})();" +
        "14;" +
        ";");
    assertAddsMessage(
        "function f() { for (var x__ in a) {} }",
        RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE,
        MessageLevel.FATAL_ERROR);
  }

  public void testTryCatch() throws Exception {
    checkAddsMessage(js(fromString(
        "try {" +
        "  throw 2;" +
        "} catch (e) {" +
        "  var e;" +
        "}")),
        MessageType.MASKING_SYMBOL,
        MessageLevel.ERROR);
    checkAddsMessage(js(fromString(
        "var e;" +
        "try {" +
        "  throw 2;" +
        "} catch (e) {" +
        "}")),
        MessageType.MASKING_SYMBOL,
        MessageLevel.ERROR);
    checkAddsMessage(js(fromString(
        "try {} catch (x__) { }")),
        RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE);
    // TODO(ihab.awad): The below should throw MessageType.MASKING_SYMBOL at
    // MessageLevel.ERROR. See bug #313. For the moment, we merely check that
    // it cajoles to something secure.
    checkSucceeds(
        "try {" +
        "  g[0];" +
        "  e;" +
        "  g[1];" +
        "} catch (e) {" +
        "  g[2];" +
        "  e;" +
        "  g[3];" +
        "}",
        weldPrelude("e") +
        weldPrelude("g") +
        "try {" +
        "  ___.readPub(g, 0);" +
        "  e;" +
        "  ___.readPub(g, 1);" +
        "} catch (ex___) {" +
        "  try {" +
        "    throw ___.tameException(ex___);" +
        "  } catch (e) {" +
        "    ___.readPub(g, 2);" +
        "    e;" +
        "    ___.readPub(g, 3);" +
        "  }" +
        "}");
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw null;" +
        "} catch (ex) {" +
        "  assertEquals(null, ex);" +  // Right value in ex.
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");  // Control reached and left the catch block.
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw undefined;" +
        "} catch (ex) {" +
        "  assertEquals(undefined, ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw true;" +
        "} catch (ex) {" +
        "  assertEquals(true, ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw 37639105;" +
        "} catch (ex) {" +
        "  assertEquals(37639105, ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw 'panic';" +
        "} catch (ex) {" +
        "  assertEquals('panic', ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    assertConsistent(
        "var handled = false;" +
        "try {" +
        "  throw new Error('hello');" +
        "} catch (ex) {" +
        "  assertEquals('hello', ex.message);" +
        "  assertEquals('Error', ex.name);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    rewriteAndExecute(
        "var handled = false;" +
        "try {" +
        "  throw function () { throw 'should not be called'; };" +
        "} catch (ex) {" +
        "  assertEquals(undefined, ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    rewriteAndExecute(
        "var handled = false;" +
        "try {" +
        "  throw { toString: function () { return 'hiya'; }, y: 4 };" +
        "} catch (ex) {" +
        "  assertEquals('string', typeof ex);" +
        "  assertEquals('hiya', ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
    rewriteAndExecute(
        "var handled = false;" +
        "try {" +
        "  throw { toString: function () { throw new Error(); } };" +
        "} catch (ex) {" +
        "  assertEquals(undefined, ex);" +
        "  handled = true;" +
        "}" +
        "assertTrue(handled);");
  }

  public void testTryCatchFinally() throws Exception {
    checkAddsMessage(js(fromString(
        "try {" +
        "} catch (e) {" +
        "  var e;" +
        "} finally {" +
        "}")),
        MessageType.MASKING_SYMBOL,
        MessageLevel.ERROR);
    checkAddsMessage(js(fromString(
        "var e;" +
        "try {" +
        "} catch (e) {" +
        "} finally {" +
        "}")),
        MessageType.MASKING_SYMBOL,
        MessageLevel.ERROR);
    checkAddsMessage(js(fromString(
        "try {} catch (x__) { } finally { }")),
        RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE);
    checkSucceeds(
        "try {" +
        "  g[0];" +
        "  e;" +
        "  g[1];" +
        "} catch (e) {" +
        "  g[2];" +
        "  e;" +
        "  g[3];" +
        "} finally {" +
        "  g[4];" +
        "  e;" +
        "  g[5];" +
        "}",
        weldPrelude("e") +
        weldPrelude("g") +
        "try {" +
        "  ___.readPub(g, 0);" +
        "  e;" +
        "  ___.readPub(g, 1);" +
        "} catch (ex___) {" +
        "  try {" +
        "    throw ___.tameException(ex___);" +
        "  } catch (e) {" +
        "    ___.readPub(g, 2);" +
        "    e;" +
        "    ___.readPub(g, 3);" +
        "  }" +
        "} finally {" +
        "    ___.readPub(g, 4);" +
        "    e;" +
        "    ___.readPub(g, 5);" +
        "}");
  }

  public void testTryFinally() throws Exception {
    assertConsistent(
        "var out = 0;" +
        "try {" +
        "  try {" +
        "    throw 2;" +
        "  } finally {" +
        "    out = 1;" +
        "  }" +
        "  out = 2;" +
        "} catch (e) {" +
        "}" +
        "out;");
    checkSucceeds(
        "try {" +
        "  g[0];" +
        "  e;" +
        "  g[1];" +
        "} finally {" +
        "  g[2];" +
        "  e;" +
        "  g[3];" +
        "}",
        weldPrelude("e") +
        weldPrelude("g") +
        "try {" +
        "  ___.readPub(g, 0);" +
        "  e;" +
        "  ___.readPub(g, 1);" +
        "} finally {" +
        "  ___.readPub(g, 2);" +
        "  e;" +
        "  ___.readPub(g, 3);" +
        "}");
  }

  public void testVarArgs() throws Exception {
    checkSucceeds(
        "var p;" +
        "var foo = function() {" +
        "  p = arguments;" +
        "};",
        "var p;" +
        "var foo = ___.simpleFrozenFunc(function() {" +
        "  var a___ = ___.args(arguments);" +
        "  p = a___;" +
        "});");
  }

  public void testVarThis() throws Exception {
    checkSucceeds(
        "var p;" +
        "function foo() {" +
        "  p = this;" +
        "}",
        "var foo;" +
        "foo =" +
        "(function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    p = t___;" +
        "  }" +
        "  return foo;" +
        "})();" +
        "var p;" +
        ";");
  }

  public void testVarBadSuffix() throws Exception {
    checkFails(
        "function() { foo__; };",
        "Variables cannot end in \"__\"");
    // Make sure *single* underscore is okay
    checkSucceeds(
        "function() { var foo_ = 3; }",
        "___.simpleFrozenFunc(function() { var foo_ = 3; })");
  }

  public void testVarBadSuffixDeclaration() throws Exception {
    checkFails(
        "function foo__() { }",
        "Variables cannot end in \"__\"");
    checkFails(
        "var foo__ = 3;",
        "Variables cannot end in \"__\"");
    checkFails(
        "var foo__;",
        "Variables cannot end in \"__\"");
    checkFails(
        "function() { function foo__() { } };",
        "Variables cannot end in \"__\"");
    checkFails(
        "function() { var foo__ = 3; };",
        "Variables cannot end in \"__\"");
    checkFails(
        "function() { var foo__; };",
        "Variables cannot end in \"__\"");
  }

  public void testVarBadGlobalSuffix() throws Exception {
    checkAddsMessage(
        js(fromString("foo_;")),
        RewriterMessageType.IMPORTED_SYMBOLS_CANNOT_END_IN_UNDERSCORE);
  }

  public void testVarFuncFreeze() throws Exception {
    // We can cajole and refer to a function
    rewriteAndExecute(
        "function foo() {};" +
        "foo();");
    // We can assign a dotted property of a variable
    rewriteAndExecute(
        "var foo = {};" +
        "foo.x = 3;" +
        "assertEquals(foo.x, 3);");
    // We cannot assign to a function variable
    assertAddsMessage(
        "function foo() {}" +
        "foo = 3;",
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME,
        MessageLevel.FATAL_ERROR);
    // We cannot assign to a member of an aliased simple function.
    rewriteAndExecute(
        "assertThrows(function() {" +
        "  function foo() {};" +
        "  var bar = foo;" +
        "  bar.x = 3;" +
        "});");
  }

  public void testVarGlobal() throws Exception {
    checkSucceeds(
        "foo;",
        weldPrelude("foo") +
        "foo;");
    checkSucceeds(
        "function() {" +
        "  foo;" +
        "}",
        weldPrelude("foo") +
        "___.simpleFrozenFunc(function() {" +
        "  foo;" +
        "});");
    checkSucceeds(
        "function() {" +
        "  var foo;" +
        "  foo;" +
        "}",
        "___.simpleFrozenFunc(function() {" +
        "  var foo;" +
        "  foo;" +
        "});");
  }

  public void testVarDefault() throws Exception {
    String unchanged =
        "var x = 3;" +
        "if (x) { }" +
        "x + 3;" +
        "var y = undefined;";
    checkSucceeds(
        "function() {" +
        "  " + unchanged +
        "};",
        "___.simpleFrozenFunc(function() {" +
        "  " + unchanged +
        "});");
  }

  public void testReadBadSuffix() throws Exception {
    checkFails(
        "x.y__;",
        "Properties cannot end in \"__\"");
  }

  public void testReadInternal() throws Exception {
    checkSucceeds(
        "function() {" +
        "  var p;" +
        "  function foo() {" +
        "    p = this.x;" +
        "  }" +
        "};",
        "___.simpleFrozenFunc(function() {" +
        "  var foo;" +
        "  foo = (function () {" +
        "      ___.splitCtor(foo, foo_init___);" +
        "      function foo(var_args) {" +
        "        return new foo.make___(arguments);" +
        "      }" +
        "      function foo_init___() {" +
        "        var t___ = this;" +
        "        p = t___.x_canRead___" +
        "            ? t___.x" +
        "            : ___.readProp(t___, 'x');" +
        "      }" +
        "      return foo;" +
        "    })();" +
        "  var p;" +
        "  ;" +
        "});");
  }

  public void testReadBadInternal() throws Exception {
    checkFails(
        "foo.bar_;",
        "Public properties cannot end in \"_\"");
  }

  public void testReadPublic() throws Exception {
    checkSucceeds(
        "var p;" +
        "p = foo.p;",
        weldPrelude("foo") +
        "var x0___;" +
        "var p;" +
        "p = " + weldReadPub("foo", "p", "x0___") + ";");
  }

  public void testReadIndexInternal() throws Exception {
    checkSucceeds(
        "var p;" +
        "function foo() { p = this[3]; }",
        "var foo;" +
        "foo =" +
        "(function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    p = ___.readProp(t___, 3);" +
        "  }" +
        "  return foo;" +
        "})();" +
        "var p;" +
        ";");
  }

  public void testReadIndexPublic() throws Exception {
    checkSucceeds(
        "var p, q;" +
        "p = q[3];",
        "var p, q;" +
        "p = ___.readPub(q, 3);");
  }

  public void testSetBadAssignToFunctionName() throws Exception {
    checkAddsMessage(js(fromString(
        "  function foo() {};"
        + "foo = 3;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
    checkAddsMessage(js(fromString(
        "  function foo() {};"
        + "foo += 3;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
    checkAddsMessage(js(fromString(
        "  function foo() {};"
        + "foo++;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
    checkAddsMessage(js(fromString(
        "  var x = function foo() {"
        + "  foo = 3;"
        + "};")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
    checkAddsMessage(js(fromString(
        "  var x = function foo() {"
        + "  foo += 3;"
        + "};")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
    checkAddsMessage(js(fromString(
        "  var x = function foo() {"
        + "  foo++;"
        + "};")),
        RewriterMessageType.CANNOT_ASSIGN_TO_FUNCTION_NAME);
  }

  public void testSetBadThis() throws Exception {
    checkFails(
        "function f() { this = 3; }",
        "Cannot assign to \"this\"");
  }

  // TODO(ihab.awad): Move this to the proper order of rules
  public void testBadGlobalThis() throws Exception {
    checkAddsMessage(js(fromString(
        "this = 3;")),
        RewriterMessageType.CANNOT_ASSIGN_TO_THIS);
    checkAddsMessage(js(fromString(
        "var x = this;")),
        RewriterMessageType.THIS_IN_GLOBAL_CONTEXT);
  }

  public void testSetBadSuffix() throws Exception {
    checkFails(
        "x.y__ = z;",
        "Properties cannot end in \"__\"");
  }

  public void testSetInternal() throws Exception {
    checkSucceeds(
        "function foo() { this.p = x; }",
        weldPrelude("x") +
        "var foo;" +
        "foo = (function () {" +
        "    ___.splitCtor(foo, foo_init___);" +
        "    function foo(var_args) {" +
        "      return new foo.make___(arguments);" +
        "    }" +
        "    function foo_init___() {" +
        "      var t___ = this;" +
        "      var x0___;" +
        "      " + weldSetProp("p", "x", "x0___") + ";" +
        "    }" +
        "    return foo;" +
        "})();" +
        ";");
  }

  public void testSetMember() throws Exception {
    checkSucceeds(
        "function foo() {}" +
        "foo.prototype.p = x;",
        weldPrelude("x") +
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "___.setMember(foo, 'p', x);");
    checkSucceeds(
        "function foo() {}" +
        "foo.prototype.p = function(a, b) { this; };",
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "___.setMember(" +
        "    foo, 'p', ___.method(" +
        "        function(a, b) {" +
        "          var t___ = this;" +
        "          t___;" +
        "        }));");
    checkSucceeds(  // Doesn't trigger setMember but should.
        "foo.bar.prototype.baz = boo;",
        weldPrelude("boo") +
        weldPrelude("foo") +
        "var x0___;" +
        "var x1___;" +
        "var x2___;" +
        "var x3___;" +
        weldSetPub(
            weldReadPub(
                weldReadPub("foo", "bar", "x3___"),
                "prototype",
                "x2___"),
            "baz",
            "boo",
            "x0___",
            "x1___") + ";");
    rewriteAndExecute(
        "  function Point(x,y) {"
        + "  this.x_ = x;"
        + "  this.y_ = y;"
        + "}"
        + "Point.prototype.toString = function() {"
        + "  return '<' + this.x_ + ',' + this.y_ + '>';"
        + "};"
        + "Point.prototype.getX = function() { return this.x_; };"
        + "Point.prototype.getY = function() { return this.y_; };"
        + "Point.area = function(pt) {"
        + "  return pt.getX() * pt.getY();"
        + "};"
        + "var pt1 = new Point(3, 4);"
        + "assertEquals(3, pt1.getX());"
        + "assertEquals(4, pt1.getY());"
        + "assertEquals('<3,4>', pt1.toString());"
        + "assertEquals(12, Point.area(pt1));");
  }

  public void testSetBadInternal() throws Exception {
    checkFails(
        "x.y_;",
        "Public properties cannot end in \"_\"");
  }

  public void testSetStatic() throws Exception {
    checkSucceeds(
        "function foo() {}" +
        "foo.p = x;",
        weldPrelude("x") +
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "___.setStatic(foo, 'p', x);");
    assertConsistent(
        "function C() { this; }" +
        "caja.def(C, Object, {}, { f: function () { return 4; } });" +
        "C.f();");
    assertConsistent(
        "function C() { this; }" +
        "C.f = function () { return 4; };" +
        "C.f();");
    checkFails(
        "function C() { this; }" +
        "caja.def(C, Object, {}, { f_: function () {} });",
        "Key may not end in \"_\"");
    rewriteAndExecute(
        "(function () {" +
        "  try {" +
        "    function C() { this; }" +
        "    caja.def(C, Object, {}, { call: function () {} });" +
        "  } catch (e) {" +
        "    return true;" +
        "  }" +
        "  fail('Static member overrides call');" +
        "})();");
    rewriteAndExecute(
        "(function () {" +
        "  try {" +
        "    function C() { this; }" +
        "    caja.def(C, Object, {}, { prototype: {} });" +
        "  } catch (e) {" +
        "    return true;" +
        "  }" +
        "  fail('Static member overrides prototype');" +
        "})();");
    rewriteAndExecute(
        "(function () {" +
        "  try {" +
        "    function C() { this; }" +
        "    C['f_'] = function () { return 4; };" +
        "  } catch (e) {" +
        "    return true;" +
        "  }" +
        "  fail('Bad static member name');" +
        "})();");
    rewriteAndExecute(
        "(function() {" +
        "  function Ctor() { this; }" +
        "  function foo() {}" +
        "  Ctor.prototype.f = foo;" +  // foo should be frozen now
        "  try { foo.x = 3; } catch (e) { return true; }" +
        "  fail('Static member was not frozen');" +
        "})();");
    rewriteAndExecute(
        "  (function() {"
        + "  function foo() {}"
        + "  var x = foo;"
        + "  var thrown = false;"
        + "  try { foo.x = 3; } catch (e) { thrown = true; }"
        + "  if (!thrown) { fail('Allowed static write on frozen'); }"
        + "  return true;"
        + "})();");
  }

  public void testSetPublic() throws Exception {
    checkSucceeds(
        "var x = {};" +
        "x.p = g[0];",
        weldPrelude("g") +
        "var x0___;" +
        "var x1___;" +
        "var x = ___.initializeMap([]);" +
        weldSetPub("x", "p", "___.readPub(g, 0)", "x0___", "x1___") + ";");
  }

  public void testSetIndexInternal() throws Exception {
    checkSucceeds(
        "function foo() {" +
        "   this[g[0]] = g[1];" +
        "}",
        weldPrelude("g") +
        "var foo;" +
        "foo = (function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    ___.setProp(t___, ___.readPub(g, 0), ___.readPub(g, 1));" +
        "  }" +
        "  return foo;" +
        "})();" +
        ";");
  }

  public void testSetIndexPublic() throws Exception {
    checkSucceeds(
        "g[0][g[1]] = g[2];",
        weldPrelude("g") +
        "___.setPub(___.readPub(g, 0), ___.readPub(g, 1), ___.readPub(g, 2));");
  }

  public void testSetBadInitialize() throws Exception {
    checkFails(
        "var x__ = 3",
        "Variables cannot end in \"__\"");
  }

  public void testSetInitialize() throws Exception {
    checkSucceeds(
        "var v = g[0];",
        weldPrelude("g") +
        "var v = ___.readPub(g, 0)");
  }

  public void testSetBadDeclare() throws Exception {
    checkFails(
        "var x__",
        "Variables cannot end in \"__\"");
  }

  public  void testSetDeclare() throws Exception {
    checkSucceeds(
        "var v;",
        "var v;");
    checkSucceeds(
        "try { } catch (e) { var v; }",
        "try {" +
        "} catch (ex___) {" +
        "  try {" +
        "    throw ___.tameException(ex___);" +
        "  } catch (e) {" +
        "    var v;" +
        "  }" +
        "}");
  }

  public void testSetVar() throws Exception {
    checkAddsMessage(
        js(fromString("try {} catch (x__) { x__ = 3; }")),
        RewriterMessageType.VARIABLES_CANNOT_END_IN_DOUBLE_UNDERSCORE);
    checkSucceeds(
        "var x;" +
        "x = g[0];",
        weldPrelude("g") +
        "var x;" +
        "x = ___.readPub(g, 0);");
  }

  public void testSetReadModifyWriteLocalVar() throws Exception {
    checkFails("x__ *= 2;", "");
    checkSucceeds(
        "var x; x += g[0];",
        weldPrelude("g")
        + "var x; x = x + ___.readPub(g, 0);");
    checkSucceeds(
        "myArray().key += 1;",
        weldPrelude("myArray")
        + "var x0___;"
        + "x0___ = ___.asSimpleFunc(myArray)(),"
        + "___.setPub(x0___, 'key',"
        + "           ___.readPub(x0___, 'key') + 1);");
    checkSucceeds(
        "myArray()[myKey()] += 1;",
        weldPrelude("myArray")
        + weldPrelude("myKey")
        + "var x0___;"
        + "var x1___;"
        + "x0___ = ___.asSimpleFunc(myArray)(),"
        + "x1___ = ___.asSimpleFunc(myKey)(),"
        + "___.setPub(x0___, x1___,"
        + "           ___.readPub(x0___, x1___) + 1);");
    checkSucceeds(  // Local reference need not be assigned to a temp.
        "(function (myKey) { myArray()[myKey] += 1; });",
        weldPrelude("myArray")
        + "___.simpleFrozenFunc(function (myKey) {"
        + "  var x0___;"
        + "  x0___ = ___.asSimpleFunc(myArray)(),"
        + "  ___.setPub(x0___, myKey,"
        + "             ___.readPub(x0___, myKey) + 1);"
        + "})");

    assertConsistent("var x = 3; x *= 2;");
    assertConsistent("var x = 1; x += 7;");
    assertConsistent("var o = { x: 'a' }; o.x += 'b';");

    EnumSet<Operator> ops = EnumSet.of(
        Operator.ASSIGN_MUL,
        Operator.ASSIGN_DIV,
        Operator.ASSIGN_MOD,
        Operator.ASSIGN_SUM,
        Operator.ASSIGN_SUB,
        Operator.ASSIGN_LSH,
        Operator.ASSIGN_RSH,
        Operator.ASSIGN_USH,
        Operator.ASSIGN_AND,
        Operator.ASSIGN_XOR,
        Operator.ASSIGN_OR
        );
    for (Operator op : ops) {
      checkSucceeds(
          "var x; x " + op.getSymbol() + " g[0];",
          weldPrelude("g")
          + "var x;"
          + "x = x " + op.getAssignmentDelegate().getSymbol()
              + "___.readPub(g, 0);");
    }
  }

  public void testSetIncrDecr() throws Exception {
    checkFails("x__--;", "");
    checkSucceeds(
        "g[0]++;",
        weldPrelude("g") +
        "var x0___;" +
        "var x1___;" +
        "x0___ = g," +
        "x1___ = ___.readPub(x0___, 0) - 0," +
        "___.setPub(x0___, 0, x1___ + 1)," +
        "x1___;");
    checkSucceeds(
        "g[0]--;",
        weldPrelude("g") +
        "var x0___;" +
        "var x1___;" +
        "x0___ = g," +
        "x1___ = ___.readPub(x0___, 0) - 0," +
        "___.setPub(x0___, 0, x1___ - 1)," +
        "x1___;");
    checkSucceeds(
        "++g[0];",
        weldPrelude("g") +
        "var x0___;" +
        "x0___ = g," +
        "___.setPub(x0___, 0, ___.readPub(x0___, 0) - -1);");

    assertConsistent(
        "var x = 2;" +
        "var arr = [--x, x, x--, x, ++x, x, x++, x];" +
        "assertEquals('1,1,1,0,1,1,1,2', arr.join(','));" +
        "arr.join(',');");
  }

  public void testSetIncrDecrOnLocals() throws Exception {
    checkFails("++x__;", "");
    checkSucceeds(
        "(function (x, y) { return [x--, --x, y++, ++y]; })",
        "___.simpleFrozenFunc(" +
        "  function (x, y) { return [x--, --x, y++, ++y]; })");

    assertConsistent(
        "(function () {" +
        "  var x = 2;" +
        "  var arr = [--x, x, x--, x, ++x, x, x++, x];" +
        "  assertEquals('1,1,1,0,1,1,1,2', arr.join(','));" +
        "  return arr.join(',');" +
        "})();");
  }

  public void testSetIncrDecrOfComplexLValues() throws Exception {
    checkFails("arr[x__]--;", "Variables cannot end in \"__\"");
    checkFails("arr__[x]--;", "Variables cannot end in \"__\"");

    checkSucceeds(
        "o.x++",
        weldPrelude("o") +
        "var x0___;" +
        "var x1___;" +
        "x0___ = o," +
        "x1___ = ___.readPub(x0___, 'x') - 0," +
        "___.setPub(x0___, 'x', x1___ + 1)," +
        "x1___;");

    assertConsistent(
        "(function () {" +
        "  var o = { x: 2 };" +
        "  var arr = [--o.x, o.x, o.x--, o.x, ++o.x, o.x, o.x++, o.x];" +
        "  assertEquals('1,1,1,0,1,1,1,2', arr.join(','));" +
        "  return arr.join(',');" +
        "})();");
  }

  public void testSetIncrDecrOrderOfAssignment() throws Exception {
    assertConsistent(
        "(function () {" +
        "  var arrs = [1, 2];" +
        "  var j = 0;" +
        "  arrs[++j] *= ++j;" +
        "  assertEquals(2, j);" +
        "  assertEquals(1, arrs[0]);" +
        "  assertEquals(4, arrs[1]);" +
        "  return arrs.join(',');" +
        "})()");
    assertConsistent(
        "(function () {" +
        "  var foo = (function () {" +
        "               var k = 0;" +
        "               return function () {" +
        "                 switch (k++) {" +
        "                   case 0: return [10, 20, 30];" +
        "                   case 1: return 1;" +
        "                   case 2: return 2;" +
        "                   default: throw new Error(k);" +
        "                 }" +
        "               };" +
        "             })();" +
        "  foo()[foo()] -= foo();" +
        "})()"
        );
  }

  public void testNewCalllessCtor() throws Exception {
    checkSucceeds(
        "(new Date);",
        weldPrelude("Date")
        + "new (___.asCtor(Date))();");
  }

  public void testNewCtor() throws Exception {
    checkSucceeds(
        "function foo() { this.p = 3; }" +
        "new foo(g[0], g[1]);",
        weldPrelude("g") +
        "var foo;" +
        "foo =" +
        "(function () {" +
        "  ___.splitCtor(foo, foo_init___);" +
        "  function foo(var_args) {" +
        "    return new foo.make___(arguments);" +
        "  }" +
        "  function foo_init___() {" +
        "    var t___ = this;" +
        "    var x0___;" +
        "    " + weldSetProp("p", "3", "x0___") + ";" +
        "  }" +
        "  return foo;" +
        "})();" +
        ";" +
        "new (___.asCtor(___.primFreeze(foo)))" +
        "    (___.readPub(g, 0), ___.readPub(g, 1));");
    checkSucceeds(
        "function foo() {}" +
        "new foo(g[0], g[1]);",
        weldPrelude("g") +
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "new (___.asCtor(___.primFreeze(foo)))" +
        "    (___.readPub(g, 0), ___.readPub(g, 1));");
    checkSucceeds(
        "function foo() {}" +
        "new foo();",
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "new (___.asCtor(___.primFreeze(foo)))();");
    checkSucceeds(
        "new g[0].bar(g[1]);",
        weldPrelude("g") +
        "var x0___;" +
        "new (___.asCtor(" +
        "    " + weldReadPub("___.readPub(g, 0)", "bar", "x0___") +
        "))(___.readPub(g, 1));");
    assertConsistent(
        "var foo = { bar: Date };" +
        "(new foo.bar(0)).getFullYear()");
    checkSucceeds(
        "function() {" +
        "  new g[0](g[1], g[2]);" +
        "};",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  new (___.asCtor(___.readPub(g, 0)))(___.readPub(g, 1), ___.readPub(g, 2));" +
        "});");
  }

  public void testDeleteProp() throws Exception {
    checkFails(
        "function Bar() { delete this.foo___; };",
        "Properties cannot end in \"__\"");
    checkSucceeds("function Ctor() { g[0].call(this); delete this.foo_; }",
                  weldPrelude("g") +
                  "var Ctor;" +
                  "Ctor = (function () {" +
                  "    ___.splitCtor(Ctor, Ctor_init___);" +
                  "    function Ctor(var_args) {" +
                  "      return new Ctor.make___(arguments);" +
                  "    }" +
                  "    function Ctor_init___() {" +
                  "      var t___ = this;" +
                  "      var x0___;" +
                  "      var x1___;" +
                  "      x1___ = ___.readPub(g, 0)," +
                  "      x0___ = t___," +
                  "      x1___.call_canCall___" +
                  "          ? x1___.call(x0___)" +
                  "          : ___.callPub(x1___, 'call', [x0___]);" +
                  // The important bit.  t___ used locally.
                  "      ___.deleteProp(t___, 'foo_');" +
                  "    }" +
                  "    return Ctor;" +
                  "  })();" +
                  ";");
    // TODO(ihab.awad): Refactor away the below cut/paste, hopefully into
    // a functional, rather than golden, test
    checkSucceeds("function Ctor() { g[0].call(this); delete this[g[1]]; }",
                  weldPrelude("g") +
                  "var Ctor;" +
                  "Ctor = (function () {" +
                  "    ___.splitCtor(Ctor, Ctor_init___);" +
                  "    function Ctor(var_args) {" +
                  "      return new Ctor.make___(arguments);" +
                  "    }" +
                  "    function Ctor_init___() {" +
                  "      var t___ = this;" +
                  "      var x0___;" +
                  "      var x1___;" +
                  "      x1___ = ___.readPub(g, 0)," +
                  "      x0___ = t___," +
                  "      x1___.call_canCall___" +
                  "          ? x1___.call(x0___)" +
                  "          : ___.callPub(x1___, 'call', [x0___]);" +
                  // The important bit.  t___ used locally.
                  "      ___.deleteProp(t___, ___.readPub(g, 1));" +
                  "    }" +
                  "    return Ctor;" +
                  "  })();" +
                  ";");
    assertConsistent(
        // Set up a class that can delete one of its members.
        "function P() { this; }" +
        "caja.def(P, Object, {" +
        "  toString : function () {" +
        "    var pairs = [];" +
        "    for (var k in this) {" +
        // TODO(metaweta): come up with a better way to be the same cajoled and plain
        "      if (typeof this[k] !== 'function' && caja.canInnocentEnum(this, k)) {" +
        "        pairs.push(k + ':' + this[k]);" +
        "      }" +
        "    }" +
        "    pairs.sort();" +
        "    return '(' + pairs.join(', ') + ')';" +
        "  }," +
        "  mangle: function () {" +
        "    delete this.x_;" +            // Deleteable
        "    try {" +
        "      delete this.z_;" +          // Not present.
        "    } catch (ex) {" +
        "      ;" +
        "    }" +
        "  }, " +
        "  setX: function (x) { this.x_ = x; }," +
        "  setY: function (y) { this.y_ = y; }" +
        "});" +
        "var p = new P();" +
        "p.setX(0);" +
        "p.setY(1);" +
        "var hist = [p.toString()];" +     // Record state before deletion.
        "p.mangle();" +                    // Delete
        "hist.push(p.toString());" +       // Record state after deletion.
        "hist.toString();");
    assertConsistent(
        "function Bar() {" +
        "  this.foo = 0;" +
        "  var preContained = 'foo' in this ? 'prev-in' : 'prev-not-in';" +
        "  var deleted = (delete this.foo) ? 'deleted' : 'not-deleted';" +
        "  var afterContained = 'foo' in this ? 'post-in' : 'post-not-in';" +
        "  var outcome = [preContained, deleted, afterContained].join();" +
        "  assertTrue(outcome, outcome === 'prev-in,not-deleted,post-in'" +
        "             || outcome === 'prev-in,deleted,post-not-in');" +
        "}" +
        "new Bar();" +
        "42;");
  }

  public void testDeletePub() throws Exception {
    checkFails("delete x.foo___;", "Properties cannot end in \"__\"");
    checkSucceeds(
        "delete foo()[bar()]",
        weldPrelude("bar") +
        weldPrelude("foo") +
        "___.deletePub(___.asSimpleFunc(foo)()," +
        "              ___.asSimpleFunc(bar)())");
    checkSucceeds(
        "delete foo().bar",
        weldPrelude("foo") +
        "___.deletePub(___.asSimpleFunc(foo)(), 'bar')");
    assertConsistent(
        "(function() {" +
        "  var o = { x: 3, y: 4 };" +    // A JSON object.
        "  function ptStr(o) { return '(' + o.x + ',' + o.y + ')'; }" +
        "  var history = [ptStr(o)];" +  // Record state before deletion.
        "  delete o.y;" +                // Delete
        "  delete o.z;" +                // Not present.  Delete a no-op
        "  history.push(ptStr(o));" +    // Record state after deletion.
        "  return history.toString();" +
        "})()");
    assertConsistent(
        "var alert = 'a';" +
        "var o = { a: 1 };" +
        "delete o[alert];" +
        "assertEquals(undefined, o.a);" +
        "o.a");
  }

  public void testDeleteFails() throws Exception {
    assertConsistent(
        "var status;" +
        "try {" +
        "  if (delete [].length) {" +
        "    status = 'FAILED';" +  // Passing is not ok.
        "  } else {" +
        "    status = 'PASSED';" +  // Ok to return false
        "  }" +
        "} catch (e) {" +
        "  status = 'PASSED';" +  // Ok to fail with an exception
        "}" +
        "status;");
  }

  public void testDeleteNonLvalue() throws Exception {
    checkFails("delete 4", "Invalid operand to delete");
  }

  public void testCallInternal() throws Exception {
    checkSucceeds(
        "function() {" +
        "  function foo() {" +
        "    this.f(g[0], g[1]);" +
        "  }" +
        "};",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  var foo;" +
        "  foo = (function () {" +
        "    ___.splitCtor(foo, foo_init___);" +
        "    function foo(var_args) {" +
        "      return new foo.make___(arguments);" +
        "    }" +
        "    function foo_init___() {" +
        "      var t___ = this;" +
        "      var x0___;" +
        "      var x1___;" +
        "      x0___ = ___.readPub(g, 0)," +
        "      x1___ = ___.readPub(g, 1)," +
        "      t___.f_canCall___ ?" +
        "          t___.f(x0___, x1___) :" +
        "          ___.callProp(t___, 'f', [x0___, x1___]);" +
        "    }" +
        "    return foo;" +
        "  })();" +
        "  ;" +
        "});");
  }

  public void testCallBadInternal() throws Exception {
    checkFails(
        "o.p_();",
        "Public selectors cannot end in \"_\"");
  }

  public void testCallCajaDef2() throws Exception {
    checkSucceeds(
        "function Point() {}" +
        "caja.def(Point, Object);" +
        "function WigglyPoint() {}" +
        "caja.def(WigglyPoint, Point);",
        weldPrelude("Object") +
        weldPrelude("caja") +
        "var Point;" +
        "Point = ___.simpleFunc(function() {}, 'Point');" +
        "var WigglyPoint;" +
        "WigglyPoint = ___.simpleFunc(function() {}, 'WigglyPoint');" +
        ";" +
        "caja.def(Point, Object);" +
        ";" +
        "caja.def(WigglyPoint, ___.primFreeze(Point));");
    // Test subclassing of constructors which mention 'this' explicitly
    rewriteAndExecute(
        "  function Point(x) { this.x = x; }"
        + "caja.def(Point, Object);"
        + "var p = new Point(31415);"
        + "caja.log('p = ' + p);"
        + "assertEquals(31415, p.x);"
        + "function WigglyPoint(x) {"
        + "  WigglyPoint.super(this, x + 1);"
        + "  this.y = x;"
        + "}"
        + "caja.def(WigglyPoint, Point);"
        + "var wp = new WigglyPoint(92654);"
        + "assertEquals(wp.y, 92654);"
        + "assertEquals(wp.x, 92655);");
    // Test subclassing of simple functions
    rewriteAndExecute(
        "  var shared = 0;"
        + "function Point(x) { shared = x; }"
        + "caja.def(Point, Object);"
        + "var p = new Point(31415);"
        + "assertEquals(31415, shared);"
        + "function WigglyPoint(x) { WigglyPoint.super(this, x + 1); }"
        + "caja.def(WigglyPoint, Point);"
        + "var wp = new WigglyPoint(92654);"
        + "assertEquals(shared, 92655);");
    checkAddsMessage(
        js(fromString("(function (caja) {" +
                      "  function C() { this; }" +
                      "  return caja.def(C, Object);" +
                      "})({ def: function () { return 123; } });")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    checkAddsMessage(
        js(fromString("var caja = { def: function () { return 123; } };" +
                      "function C() {}" +
                      "caja.def(C, Object, {}, {});")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    assertConsistent(
        "function foo() {}" +
        "caja.def(foo, Object, { f: function () { return 3; }});" +
        "(new foo).f()");
  }

  public void testCallCajaDef2BadFunction() throws Exception {
    checkAddsMessage(
        js(fromString(
            "  var f = function Point() {"
            + "  caja.def(Point, Object);"
            + "};")),
        RewriterMessageType.CAJA_DEF_ON_FROZEN_FUNCTION);
  }

  public void testCallCajaDef2Bad() throws Exception {
    checkAddsMessage(
        js(fromString(
            "  var Point = 3;"
            + "caja.def(Point, Object);")),
        RewriterMessageType.CAJA_DEF_ON_NON_FUNCTION);
  }

  public void testCallCajaDef3Plus() throws Exception {
    checkSucceeds(
        "function Point() {}" +
        "function WigglyPoint() {}" +
        "caja.def(WigglyPoint, Point, { m0: g[0], m1: function() { this.p = 3; } });",
        weldPrelude("caja") +
        weldPrelude("g") +
        "var Point;" +
        "Point = ___.simpleFunc(function() {}, 'Point');" +
        "var WigglyPoint;" +
        "WigglyPoint = ___.simpleFunc(function() {}, 'WigglyPoint');" +
        ";" +
        ";" +
        "caja.def(WigglyPoint, ___.primFreeze(Point), {" +
        "    m0: ___.readPub(g, 0)," +
        "    m1: ___.method(function() {" +
        "                     var t___ = this;" +
        "                     var x0___;" +
        "                     " + weldSetProp("p", "3", "x0___") + ";" +
        "                   })" +
        "});");
    checkSucceeds(
        "function Point() {}" +
        "function WigglyPoint() {}" +
        "caja.def(WigglyPoint, Point," +
        "    { m0: g[0], m1: function() { this.p = 3; } }," +
        "    { s0: g[1], s1: function() { return 3; } });",
        weldPrelude("caja") +
        weldPrelude("g") +
        "var Point;" +
        "Point = ___.simpleFunc(function() {}, 'Point');" +
        "var WigglyPoint;" +
        "WigglyPoint = ___.simpleFunc(function() {}, 'WigglyPoint');" +
        ";" +
        ";" +
        "caja.def(WigglyPoint, ___.primFreeze(Point), {" +
        "    m0: ___.readPub(g, 0)," +
        "    m1: ___.method(function() {" +
        "                     var t___ = this;" +
        "                     var x0___;" +
        "                     " + weldSetProp("p", "3", "x0___") +
        "                   })" +
        "}, {" +
        "    s0: ___.readPub(g, 1)," +
        "    s1: ___.simpleFrozenFunc(function() { return 3; })" +
        "});");
    checkFails(
        "function() {" +
        "  function Point() {}" +
        "  function WigglyPoint() {}" +
        "  caja.def(WigglyPoint, Point, x);" +
        "};",
        "Map expression expected");
    checkFails(
        "function() {" +
        "  function Point() {}" +
        "  function WigglyPoint() {}" +
        "  caja.def(WigglyPoint, Point, { foo: x }, x);" +
        "};",
        "Map expression expected");
    wartsMode = true;
    checkFails(
        "function() {\n" +
        "  function Point() {}\n" +
        "  function WigglyPoint() {}\n" +
        "  caja.def(WigglyPoint, Point, { foo: x },\n" +
        "           { bar: function() { this.x_ = 3; } });\n" +
        "};",
        "Public properties cannot end in \"_\"");
    rewriteAndExecute(
        "  function Point(x,y) {"
        + "  this.x_ = x;"
        + "  this.y_ = y;"
        + "}"
        + "caja.def(Point, Object, {"
        + "  toString: function() {"
        + "    return '<' + this.x_ + ',' + this.y_ + '>';"
        + "  },"
        + "  getX: function() { return this.x_; },"
        + "  getY: function() { return this.y_; }"
        + "}, {"
        + "  area: function(pt) {"
        + "    return pt.getX() * pt.getY();"
        + "  }"
        + "});"
        + "var pt1 = new Point(3, 4);"
        + "assertEquals(3, pt1.getX());"
        + "assertEquals(4, pt1.getY());"
        + "assertEquals('<3,4>', pt1.toString());"
        + "assertEquals(12, Point.area(pt1));");
    checkAddsMessage(
        js(fromString("(function (caja) {" +
                      "})({ def: function () { return 123; } })")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    checkAddsMessage(
        js(fromString("try {" +
                      "  throw { def: function () { return 123; } };" +
                      "} catch (caja) {" +
                      "}" +
                      "result;")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    checkAddsMessage(
        js(fromString("function caja() { this; }" +
                      "caja.def = function () { return 123; };")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    checkAddsMessage(
        js(fromString("for (var caja = { def: function () { return 123; } }" +
                      "     ; caja; caja = null) {" +
                      "}")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
    checkAddsMessage(
        js(fromString("for (var caja in { x: 0 }) {}")),
        RewriterMessageType.CANNOT_REDECLARE_CAJA);
  }

  public void testCallCajaDef3PlusBadFunction() throws Exception {
    checkAddsMessage(
        js(fromString(
            "  var f = function Point() {"
            + "  caja.def(Point, Object, {});"
            + "};")),
        RewriterMessageType.CAJA_DEF_ON_FROZEN_FUNCTION);
    checkAddsMessage(
        js(fromString(
            "  var f = function Point() {"
            + "  caja.def(Point, Object, {}, {});"
            + "};")),
        RewriterMessageType.CAJA_DEF_ON_FROZEN_FUNCTION);
  }

  public void testCallCajaDef3PlusBad() throws Exception {
    checkAddsMessage(
        js(fromString(
            "  var Point = 3;"
            + "caja.def(Point, Object, {});")),
        RewriterMessageType.CAJA_DEF_ON_NON_FUNCTION);
    checkAddsMessage(
        js(fromString(
            "  var Point = 3;"
            + "caja.def(Point, Object, {}, {});")),
        RewriterMessageType.CAJA_DEF_ON_NON_FUNCTION);
  }

  public void testCallPublic() throws Exception {
    checkSucceeds(
        "g[0].m(g[1], g[2]);",
        weldPrelude("g") +
        "var x0___;" +
        "var x1___;" +
        "var x2___;" +
        "x2___ = ___.readPub(g, 0)," +
        "(x0___ = ___.readPub(g, 1), x1___ = ___.readPub(g, 2))," +
        "x2___.m_canCall___ ?" +
        "  x2___.m(x0___, x1___) :" +
        "  ___.callPub(x2___, 'm', [x0___, x1___]);");
  }

  public void testCallIndexInternal() throws Exception {
    checkSucceeds(
        "function() {" +
        "  function foo() {" +
        "    this[g[0]](g[1], g[2]);" +
        "  }" +
        "};",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  var foo;" +
        "  foo = (function () {" +
        "    ___.splitCtor(foo, foo_init___);" +
        "    function foo(var_args) {" +
        "      return new foo.make___(arguments);" +
        "    }" +
        "    function foo_init___() {" +
        "      var t___ = this;" +
        "      ___.callProp(t___, ___.readPub(g, 0), [___.readPub(g, 1), ___.readPub(g, 2)]);" +
        "    }" +
        "    return foo;" +
        "  })();" +
        "  ;" +
        "});");
  }

  public void testCallIndexPublic() throws Exception {
    checkSucceeds(
        "g[0][g[1]](g[2], g[3]);",
        weldPrelude("g") +
        "___.callPub(" +
        "    ___.readPub(g, 0)," +
        "    ___.readPub(g, 1)," +
        "    [___.readPub(g, 2), ___.readPub(g, 3)]);");
  }

  public void testCallFunc() throws Exception {
    checkSucceeds(
        "g(g[1], g[2]);",
        weldPrelude("g") +
        "___.asSimpleFunc(g)" +
        "     (___.readPub(g, 1), ___.readPub(g, 2));");
  }

  public void testFuncAnonSimple() throws Exception {
    // TODO(ihab.awad): The below test is not as complete as it should be
    // since it does not test the "@stmts*" substitution in the rule
    checkSucceeds(
        "function(x, y) { x = arguments; y = g[0]; };",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function(x, y) {" +
        "  var a___ = ___.args(arguments);" +
        "  x = a___;" +
        "  y = ___.readPub(g, 0);" +
        "});");
    rewriteAndExecute(
        "(function () {" +
        "  var foo = function () {};" +
        "  foo();" +
        "  try {" +
        "    foo.x = 3;" +
        "  } catch (e) { return; }" +
        "  fail('mutate frozen function');" +
        "})();");
    assertConsistent(
        "var foo = (function () {" +
        "             function foo() {};" +
        "             foo.x = 3;" +
        "             return foo;" +
        "           })();" +
        "foo();" +
        "foo.x");
  }

  public void testFuncNamedSimpleDecl() throws Exception {
    checkSucceeds(
        "function() {" +
        "  function foo(x, y) {" +
        "    x = arguments;" +
        "    y = g[0];" +
        "    return foo(x - 1, y - 1);" +
        "  }" +
        "};",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  var foo;" +
        "  foo = ___.simpleFunc(function(x, y) {" +
        "      var a___ = ___.args(arguments);" +
        "      x = a___;" +
        "      y = ___.readPub(g, 0);" +
        "      return ___.asSimpleFunc(___.primFreeze(foo))(x - 1, y - 1);" +
        "  }, 'foo');" +
        "  ;"+
        "});");
    checkSucceeds(
        "function foo(x, y ) {" +
        "  return foo(x - 1, y - 1);" +
        "}",
        "var foo;" +
        "foo = ___.simpleFunc(function(x, y) {" +
        "  return ___.asSimpleFunc(___.primFreeze(foo))(x - 1, y - 1);" +
         "}, 'foo');" +
        ";");
    rewriteAndExecute(
        "(function () {" +
        "  function foo() {}" +
        "  foo();" +
        "  try {" +
        "    foo.x = 3;" +
        "  } catch (e) { return; }" +
        "  fail('mutated frozen function');" +
        "})();");
    assertConsistent(
        "function foo() {}" +
        "foo.x = 3;" +
        "foo();" +
        "foo.x;");
    rewriteAndExecute(
        "  function f_() { return 31415; }"
        + "var x = f_();"
        + "assertEquals(x, 31415);");
  }

  public void testFuncNamedSimpleValue() throws Exception {
    checkSucceeds(
        "var f = function foo(x, y) {" +
        "  x = arguments;" +
        "  y = z;" +
        "  return foo(x - 1, y - 1);" +
        "};",
        weldPrelude("z") +
        "  var f = function() {" +
        "      function foo(x, y) {" +
        "        var a___ = ___.args(arguments);" +
        "        x = a___;" +
        "        y = z;" +
        "        return ___.asSimpleFunc(___.primFreeze(foo))(x - 1, y - 1);" +
        "      }" +
        "      return ___.simpleFrozenFunc(foo, 'foo');" +
        "    }();");
    checkSucceeds(
        "var bar = function foo_(x, y ) {" +
        "  return foo_(x - 1, y - 1);" +
        "};",
        "var bar = function() {" +
        "  function foo_(x, y) {" +
        "    return ___.asSimpleFunc(___.primFreeze(foo_))(x - 1, y - 1);" +
        "  }" +
        "  return ___.simpleFrozenFunc(foo_, 'foo_');" +
        "}();");
  }

  public void testFuncExophoricFunction() throws Exception {
    wartsMode = true;
    checkSucceeds(
        "function (x) { return this.x; };",
        "___.xo4a(" +
        "    function (x) {" +
        "      var t___ = this;" +
        "      var x0___;" +
        "      return " + weldReadPub(
                              "t___",
                              "x",
                              "x0___") + ";" +
        "});");
    checkFails(
        "function (k) { return this[k]; }",
        "\"this\" in an exophoric function exposes only public fields");
    checkFails(
        "function () { delete this.k; }",
        "\"this\" in an exophoric function exposes only public fields");
    checkFails(
        "function () { x in this; }",
        "\"this\" in an exophoric function exposes only public fields");
    checkFails(
        "function () { 'foo_' in this; }",
        "\"this\" in an exophoric function exposes only public fields");
    checkSucceeds(
        "function () { 'foo' in this; }",
        "___.xo4a(" +
        "    function () {" +
        "      var t___ = this;" +
        "      ___.canReadPubRev(\'foo\', t___);" +
        "    })");
    checkFails(
        "function () { for (var k in this); }",
        "\"this\" in an exophoric function exposes only public fields");
    checkFails(
        "function (y) { this.x = y; }",
        "\"this\" in an exophoric function exposes only public fields");
    assertConsistent(
        "({ f7: function () { return this.x + this.y; }, x: 1, y: 2 }).f7()");
    assertConsistent(
        "({ f: function (y) { return this.x * y; }, x: 4 }).f(2)");
  }

  public void testFuncBadMethod() throws Exception {
    wartsMode = true;
    checkFails(
        "function(x) { this.x_ = x; };",
        "Public properties cannot end in \"_\"");
  }

  public void testMaskingFunction () throws Exception {
    assertAddsMessage(
        "function Goo() { function Goo() {} }",
        MessageType.SYMBOL_REDEFINED,
        MessageLevel.ERROR );
    assertAddsMessage(
        "function Goo() { var Goo = 1}",
        MessageType.MASKING_SYMBOL,
        MessageLevel.LINT );
    assertMessageNotPresent(
        "function Goo() { this.x = 1; }",
        MessageType.MASKING_SYMBOL );
  }

  public void testFuncCtor() throws Exception {
    checkSucceeds(
        "function Foo(x) { this.x_ = x; }",
        "var Foo;" +
        "Foo = (function () {" +
        "      ___.splitCtor(Foo, Foo_init___);" +
        "      function Foo(var_args) {" +
        "        return new Foo.make___(arguments);" +
        "      }" +
        "      function Foo_init___(x) {" +
        "        var t___ = this;" +
        "        var x0___;" +
        "        " + weldSetProp("x_", "x", "x0___") + ";" +
        "      }" +
        "      return Foo;" +
        "    })();" +
        ";");
    checkSucceeds(
        "(function(){ function Foo(x) { this.x_ = x; } })();",
        "___.asSimpleFunc(___.simpleFrozenFunc(function () {" +
        "    var Foo;" +
        "    Foo = (function () {" +
        "        ___.splitCtor(Foo, Foo_init___);" +
        "        function Foo(var_args) {" +
        "          return new Foo.make___(arguments);" +
        "        }" +
        "        function Foo_init___(x) {" +
        "          var t___ = this;" +
        "          var x0___;" +
        "          " + weldSetProp("x_", "x", "x0___") + ";" +
        "        }" +
        "        return Foo;" +
        "      })();" +
        "    ;" +
        "  }))();");
    checkSucceeds(
        "function Foo(x) { this.x_ = x; }" +
        "function Bar(y) {" +
        "  Bar.super(this,1);" +
        "  this.y = y;" +
        "}" +
        "var bar = new Bar(3);",
        "var Foo;" +
        "Foo = (function () {" +
        "        ___.splitCtor(Foo, Foo_init___);" +
        "        function Foo(var_args) {" +
        "          return new Foo.make___(arguments);" +
        "        }" +
        "        function Foo_init___(x) {" +
        "          var t___ = this;" +
        "          var x0___;" +
        "          " + weldSetProp("x_", "x", "x0___") + ";" +
        "        }" +
        "        return Foo;" +
        "    })();" +
        "var Bar;" +
        "Bar = (function () {" +
        "        ___.splitCtor(Bar, Bar_init___);" +
        "        function Bar(var_args) {" +
        "          return new Bar.make___(arguments);" +
        "        }" +
        "        function Bar_init___(y) {" +
        "          var t___ = this;" +
        "          var x0___;" +
        "          Bar.super(this, 1);" +
        "          " + weldSetProp("y", "y", "x0___") + ";" +
        "        }" +
        "        return Bar;" +
        "      })();" +
        ";" +
        ";" +
        "var bar = new (___.asCtor(___.primFreeze(Bar)))(3);");
  }

  public void testMapEmpty() throws Exception {
    checkSucceeds(
        "var f = {};",
        "var f = ___.initializeMap([]);");
  }

  public void testMapBadKeySuffix() throws Exception {
    checkFails(
        "var o = { x_: 3 };",
        "Key may not end in \"_\"");
  }

  public void testMapNonEmpty() throws Exception {
    checkSucceeds(
        "var o = { k0: g.x, k1: g.y };",
        weldPrelude("g") +
        "var x0___;" +
        "var x1___;" +
        "var o = ___.initializeMap(" +
        "    [ 'k0', " + weldReadPub("g", "x", "x0___") + ", " +
        "      'k1', " + weldReadPub("g", "y", "x1___") + " ]);");
    // Ensure that calling an untamed function throws
    rewriteAndExecute(
        "testImports.f = function() {};",
        "assertThrows(function() { f(); });",
        ";");
    // Ensure that calling a tamed function in an object literal works
    rewriteAndExecute(
        "  var f = function() {};"
        + "var m = { f : f };"
        + "m.f();");
    // Ensure that putting an untamed function into an object literal
    // causes an exception.
    rewriteAndExecute(
        "testImports.f = function() {};",
        "assertThrows(function(){({ isPrototypeOf : f });});",
        ";");
  }

  public void testOtherInstanceof() throws Exception {
    checkSucceeds(
        "function foo() {}" +
        "g[0] instanceof foo;",
        weldPrelude("g") +
        "var foo;" +
        "foo = ___.simpleFunc(function() {}, 'foo');" +
        ";" +
        "___.readPub(g, 0) instanceof ___.primFreeze(foo);");
    checkSucceeds(
        "g[0] instanceof Object;",
        weldPrelude("Object") +
        weldPrelude("g") +
        "___.readPub(g, 0) instanceof Object;");

    assertConsistent("[ (({}) instanceof Object)," +
                     "  ((new Date) instanceof Date)," +
                     "  (({}) instanceof Date)," +
                     "].toString()");
    assertConsistent("function foo() {}; (new foo) instanceof foo");
    assertConsistent("function foo() {}; !(({}) instanceof foo)");
  }

  public void testOtherTypeof() throws Exception {
    checkSucceeds(
        "typeof g[0];",
        weldPrelude("g") +
        "typeof ___.readPub(g, 0);");
    checkFails("typeof ___", "Variables cannot end in \"__\"");
    assertConsistent("[ (typeof noSuchGlobal), (typeof 's')," +
                     "  (typeof 4)," +
                     "  (typeof null)," +
                     "  (typeof (void 0))," +
                     "  (typeof [])," +
                     "  (typeof {})," +
                     "  (typeof /./)," +
                     "  (typeof (function () {}))," +
                     "  (typeof { x: 4.0 }.x)," +
                     "  (typeof { 2: NaN }[1 + 1])" +
                     "].toString()");
  }

  public void testLabeledStatement() throws Exception {
    checkFails("IMPORTS___: 1", "Labels cannot end in \"__\"");
    checkSucceeds("foo: 1", "foo: 1");
    assertConsistent(
        "var k = 0;" +
        "a: for (var i = 0; i < 10; ++i) {" +
        "  b: for (var j = 0; j < 10; ++j) {" +
        "    if (++k > 5) break a;" +
        "  }" +
        "}" +
        "k;");
    assertConsistent(
        "var k = 0;" +
        "a: for (var i = 0; i < 10; ++i) {" +
        "  b: for (var j = 0; j < 10; ++j) {" +
        "    if (++k > 5) break b;" +
        "  }" +
        "}" +
        "k;");
  }

  public void testRegexLiteral() throws Exception {
    // Regex literals create a new instance each time expression is evaluated.
    // Some browsers pool literals, but ES3.1&ES4 mandates separate instances
    // since regexs are mutable and share state across matches.
    rewriteAndExecute(
        "var regexs = [];" +
        "for (var i = 2; --i >= 0;) { regexs[i] = /x/; }" +
        "assertTrue(regexs[0] !== regexs[1]);");
    assertConsistent("/x/.test('x')");
    assertConsistent("/x/.test('X')");
    assertConsistent("/x/i.test('X')");
    assertConsistent("var RegExp = null; /x/.test('x')");
  }

  public void testOtherSpecialOp() throws Exception {
    checkSucceeds("void 0;", "void 0;");
    checkSucceeds("void g();",
                  weldPrelude("g") +
                  "void (___.asSimpleFunc)(g)()");
    checkSucceeds("g[0], g[1];",
                  weldPrelude("g") +
                  "___.readPub(g, 0), ___.readPub(g, 1);");
  }

  public void testMultiDeclaration() throws Exception {
    // 'var' in global scope, part of a block
    checkSucceeds(
        "var x, y;",
        "var x, y;");
    checkSucceeds(
        "var x = g[0], y = g[1];",
        weldPrelude("g") +
        "var x = ___.readPub(g, 0), y = ___.readPub(g, 1);");
    checkSucceeds(
        "var x, y = g[0];",
        weldPrelude("g") +
        "var x, y = ___.readPub(g, 0);");
    // 'var' in global scope, 'for' statement
    checkSucceeds(
        "for (var x, y; ; ) {}",
        "for (var x, y; ; ) {}");
    checkSucceeds(
        "for (var x = g[0], y = g[1]; ; ) {}",
        weldPrelude("g") +
        "for (var x = ___.readPub(g, 0), y = ___.readPub(g, 1); ; ) {}");
    checkSucceeds(
        "for (var x, y = g[0]; ; ) {}",
        weldPrelude("g") +
        "for (var x, y = ___.readPub(g, 0); ; ) {}");
    // 'var' in global scope, part of a block
    checkSucceeds(
        "function() {" +
        "  var x, y;" +
        "}",
        "___.simpleFrozenFunc(function() {" +
        "  var x, y;" +
        "});");
    checkSucceeds(
        "function() {" +
        "  var x = g[0], y = g[1];" +
        "}",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  var x = ___.readPub(g, 0), y = ___.readPub(g, 1);" +
        "});");
    checkSucceeds(
        "function() {" +
        "  var x, y = g[0];" +
        "}",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  var x, y = ___.readPub(g, 0);" +
        "});");
    // 'var' in global scope, 'for' statement
    checkSucceeds(
        "function() {" +
        "  for (var x, y; ; ) {}" +
        "}",
        "___.simpleFrozenFunc(function() {" +
        "  for (var x, y; ; ) {}" +
        "});");
    checkSucceeds(
        "function() {" +
        "  for (var x = g[0], y = g[1]; ; ) {}" +
        "}",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  for (var x = ___.readPub(g, 0), " +
        "           y = ___.readPub(g, 1); ; ) {}" +
        "});");
    checkSucceeds(
        "function() {" +
        "  for (var x, y = g[0]; ; ) {}" +
        "}",
        weldPrelude("g") +
        "___.simpleFrozenFunc(function() {" +
        "  for (var x, y = ___.readPub(g, 0); ; ) {}" +
        "});");
    assertConsistent(
        "var arr = [1, 2, 3], k = -1;" +
        "(function () {" +
        "  var a = arr[++k], b = arr[++k], c = arr[++k];" +
        "  return [a, b, c].join(',');" +
        "})()");
    // Check exceptions on read of uninitialized variables.
    assertConsistent(
        "(function () {" +
        "  var a = [];" +
        "  for (var i = 0, j = 10; i < j; ++i) { a.push(i); }" +
        "  return a.join(',');" +
        "})()");
    assertConsistent(
        "var a = [];" +
        "for (var i = 0, j = 10; i < j; ++i) { a.push(i); }" +
        "a.join(',')");
  }

  public void testRecurseParseTreeNodeContainer() throws Exception {
    // Tested implicitly by other cases
  }

  public void testRecurseArrayConstructor() throws Exception {
    checkSucceeds(
        "var foo = [ g[0], g[1] ];",
        weldPrelude("g") +
        "var foo = [___.readPub(g, 0), ___.readPub(g, 1)];");
  }

  public void testRecurseBlock() throws Exception {
    // Tested implicitly by other cases
  }

  public void testRecurseBreakStmt() throws Exception {
    checkSucceeds(
        "while (true) { break; }",
        "while (true) { break; }");
  }

  public void testRecurseCaseStmt() throws Exception {
    checkSucceeds(
        "switch (g[0]) { case 1: break; }",
        weldPrelude("g") +
        "switch (___.readPub(g, 0)) { case 1: break; }");
  }

  public void testRecurseConditional() throws Exception {
    checkSucceeds(
        "if (g[0] === g[1]) {" +
        "  g[2];" +
        "} else if (g[3] === g[4]) {" +
        "  g[5];" +
        "} else {" +
        "  g[6];" +
        "}",
        weldPrelude("g") +
        "if (___.readPub(g, 0) === ___.readPub(g, 1)) {" +
        "  ___.readPub(g, 2);" +
        "} else if (___.readPub(g, 3) === ___.readPub(g, 4)) {" +
        "  ___.readPub(g, 5);" +
        "} else {" +
        "  ___.readPub(g, 6);" +
        "}");
  }

  public void testRecurseContinueStmt() throws Exception {
    checkSucceeds(
        "while (true) { continue; }",
        "while (true) { continue; }");
  }

  public void testRecurseDebuggerStmt() throws Exception {
    checkSucceeds("debugger;", "debugger;");
  }

  public void testRecurseDefaultCaseStmt() throws Exception {
    checkSucceeds(
        "switch (g[0]) { default: break; }",
        weldPrelude("g") +
        "switch(___.readPub(g, 0)) { default: break; }");
  }

  public void testRecurseExpressionStmt() throws Exception {
    // Tested implicitly by other cases
  }

  public void testRecurseIdentifier() throws Exception {
    // Tested implicitly by other cases
  }

  public void testRecurseLiteral() throws Exception {
    checkSucceeds(
        "3;",
        "3;");
  }

  public void testRecurseLoop() throws Exception {
    checkSucceeds(
        "for (var k = 0; k < g[0]; k++) {" +
        "  g[1];" +
        "}",
        weldPrelude("g") +
        "for (var k = 0; k < ___.readPub(g, 0); k++) {" +
        "  ___.readPub(g, 1);" +
        "}");
    checkSucceeds(
        "while (g[0]) { g[1] }",
        weldPrelude("g") +
        "while (___.readPub(g, 0)) { ___.readPub(g, 1); }");
  }

  public void testRecurseNoop() throws Exception {
    checkSucceeds(
        ";",
        ";");
  }

  public void testRecurseOperation() throws Exception {
    checkSucceeds(
        "g[0] + g[1];",
        weldPrelude("g") +
        "___.readPub(g, 0) + ___.readPub(g, 1);");
    checkSucceeds(
        "1 + 2 * 3 / 4 - -5;",
        "1 + 2 * 3 / 4 - -5;");
    checkSucceeds(
        "var x, y;" +
        "x  = y = g[0];",
        weldPrelude("g") +
        "var x, y;" +
        "x = y = ___.readPub(g, 0);");
  }

  public void testRecurseReturnStmt() throws Exception {
    checkSucceeds(
        "return g[0];",
        weldPrelude("g") +
        "return ___.readPub(g, 0);");
  }

  public void testRecurseSwitchStmt() throws Exception {
    checkSucceeds(
        "switch (g[0]) { }",
        weldPrelude("g") +
        "switch (___.readPub(g, 0)) { }");
  }

  public void testRecurseThrowStmt() throws Exception {
    checkSucceeds(
        "throw g[0];",
        weldPrelude("g") +
        "throw ___.readPub(g, 0);");
    checkSucceeds(
        "function() {" +
        "  var x;" +
        "  throw x;" +
        "}",
        "___.simpleFrozenFunc(function() {" +
        "  var x;" +
        "  throw x;" +
        "});");
  }

  public void testCantReadProto() throws Exception {
    rewriteAndExecute(
        "function foo(){}" +
        "foo.prototype.getX = function(){};" +
        "assertTrue(foo.prototype === undefined);" +
        "assertThrows(function(){foo.prototype.getX;});");
  }

  public void testSpecimenClickme() throws Exception {
    checkSucceeds(fromResource("clickme.js"));
  }

  public void testSpecimenListfriends() throws Exception {
    checkSucceeds(fromResource("listfriends.js"));
  }

  @Override
  protected Object executePlain(String caja) throws IOException {
    mq.getMessages().clear();
    // Make sure the tree assigns the result to the unittestResult___ var.
    return RhinoTestBed.runJs(
        null,
        new RhinoTestBed.Input(getClass(), "/com/google/caja/caja.js"),
        new RhinoTestBed.Input(getClass(), "../../plugin/asserts.js"),
        new RhinoTestBed.Input(caja, getName() + "-uncajoled"));
  }

  @Override
  protected Object rewriteAndExecute(String pre, String caja, String post)
      throws IOException, ParseException {
    mq.getMessages().clear();

    Statement cajaTree = replaceLastStatementWithEmit(
        js(fromString(caja, is)), "unittestResult___;");
    String cajoledJs = render(
        rewriteStatements(js(fromResource("../../plugin/asserts.js")),
                          cajaTree));

    assertNoErrors();

    Object result = RhinoTestBed.runJs(
        null,
        new RhinoTestBed.Input(
            getClass(), "/com/google/caja/plugin/console-stubs.js"),
        new RhinoTestBed.Input(getClass(), "/com/google/caja/caja.js"),
        new RhinoTestBed.Input(getClass(), "../../plugin/asserts.js"),
        new RhinoTestBed.Input(
            getClass(), "/com/google/caja/log-to-console.js"),
        new RhinoTestBed.Input(
            // Initialize the output field to something containing a unique
            // object value that will not compare identically across runs.
            // Set up the imports environment.
            "var testImports = ___.copy(___.sharedImports);\n" +
            "testImports.unittestResult___ = {\n" +
            "    toString: function () { return '' + this.value; },\n" +
            "    value: '--NO-RESULT--'\n" +
            "};\n" +
            "___.getNewModuleHandler().setImports(testImports);",
            getName() + "-test-fixture"),
        new RhinoTestBed.Input(pre, getName()),
        // Load the cajoled code.
        new RhinoTestBed.Input(
            "___.loadModule(function (___, IMPORTS___) {" + cajoledJs + "\n});",
            getName() + "-cajoled"),
        new RhinoTestBed.Input(post, getName()),
        // Return the output field as the value of the run.
        new RhinoTestBed.Input("unittestResult___;", getName()));

    assertNoErrors();
    return result;
  }

  @Override
  protected Rewriter newRewriter() {
    return new DefaultCajaRewriter(false, wartsMode);
  }
}
