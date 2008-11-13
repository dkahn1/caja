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

package com.google.caja.parser.quasiliteral;

import junit.framework.AssertionFailedError;

/**
 * Contains all the tests that should apply to any JS dialect defined
 * by the Caja project (i.e., Caja or Cajita).
 *
 * @author ihab.awad@gmail.com
 */
public abstract class CommonJsRewriterTestCase extends RewriterTestCase {

  /**
   * Tests that eval is uncallable.
   */
  public void testEval() throws Exception {
    rewriteAndExecute(
        "var success=false;" +
        "try{eval('1');}catch(e){success=true;}" +
        "if (!success)fail('Outer eval is accessible.')");
  }

  /**
   * Tests that arguments to functions are not mutable through the
   * arguments array.
   */
  public void testMutableArguments() throws Exception {
    rewriteAndExecute(
        "cajita.log('___.args = ' + ___.args);",
        "function f(a) {" +
          "try {" +
            "arguments[0] = 1;" +
            "if (a) fail('Mutable arguments');" +
          "} catch (e) {" +
             // pass
          "}" +
        "}" +
        "f(0);",
        "");
  }

  /**
   * Tests that the caller attribute is unreadable.
   */
  public void testCaller() throws Exception {
    rewriteAndExecute(
        "function f(x) {" +
        "  try {" +
        "    if (arguments.caller || f.caller) {" +
        "      fail('caller is accessible');" +
        "    }" +
        "  } catch (e) {}" +
        "}" +
        "f(1);");
  }

  /**
   * Tests that the callee attribute is unreadable.
   */
  public void testCallee() throws Exception {
    rewriteAndExecute(
        "function f(x) {" +
        "  try {" +
        "    if (arguments.callee || f.callee) {" +
        "      fail('callee is accessible');" +
        "    }" +
        "  } catch (e) {}" +
        "}" +
        "f(1);");
  }

  /**
   * Tests that arguments are immutable from another function's scope.
   */
  public void testCrossScopeArguments() throws Exception {
    rewriteAndExecute(
        "function f(a) {" +
          "g();" +
          "if (a) fail('Mutable cross scope arguments');" +
        "}\n" +
        "function g() {" +
          "if (f.arguments) " +
            "f.arguments[0] = 1;" +
        "}" +
        "f(0);");
  }

  /**
   * Tests that exceptions are not visible outside of the catch block.
   */
  public void testCatch() throws Exception {
    try {
      rewriteAndExecute(
          "var e = 0;" +
          "try{ throw 1; } catch (e) {}" +
          "if (e) fail('Exception visible out of proper scope');");
      fail("Exception that masks var should not pass");
    } catch (AssertionFailedError e) {
      // pass
    }
  }

  /**
   * Tests that setTimeout is uncallable.
   */
  public void testSetTimeout() throws Exception {
    rewriteAndExecute(
        "var success=false;try{setTimeout('1',10);}" +
        "catch(e){success=true;}" +
        "if(!success)fail('setTimeout is accessible');");
  }

  /**
   * Tests that Object.watch is uncallable.
   */
  public void testObjectWatch() throws Exception {
    rewriteAndExecute(
        "var x={}; var success=false;" +
        "try{x.watch(y, function(){});}" +
        "catch(e){success=true;}" +
        "if(!success)fail('Object.watch is accessible');");
  }

  /**
   * Tests that unreadable global properties are not readable by way of
   * Object.toSource().
   */
  public void testToSource() throws Exception {
    rewriteAndExecute(
        "var x;" +
        "try{x=toSource();}catch(e){}" +
        "if(x) fail('Global write-only values are readable.');");
  }

  public void testForIn() throws Exception {
    // TODO(ihab.awad): Disabled until we figure out how to get a test fixture
    // that allows us to add stuff to IMPORTS___ before the test is run.
    if (false) {
    rewriteAndExecute(
        "",
        "function Foo() {" +
        "  this.x_ = 1;" +
        "  this.y = 2;" +
        "  this.z = 3;" +
        "}" +
        "var obj = new Foo();" +
        "var y = {};" +
        "var result = [];" +
        "for (y.k in obj) {" +
        "  result.push(y.k);" +
        "}",
        "assertEquals(" +
        "    ___.getNewModuleHandler().getImports().result.toSource()," +
        "    (['y', 'z']).toSource());");
    rewriteAndExecute(
        "",
        "function test(obj) {" +
        "  var y = {};" +
        "  var result = [];" +
        "  for (y.k in obj) {" +
        "    result.push(y.k);" +
        "  }" +
        "  return result;" +
        "}",
        "assertEquals(" +
        "    ___.getNewModuleHandler().getImports().test({x_:1, y:2, z:3})" +
        "        .sort().toSource()," +
        "    (['y', 'z']).toSource());");
    rewriteAndExecute(
        "",
        "function Foo() {" +
        "  this.x_ = 1;" +
        "  this.y = 2;" +
        "}" +
        "cajita.def(Foo, Object, {" +
        "  test: function () {" +
        "    var y = {};" +
        "    var result = [];" +
        "    for (y.k in this) {" +
        "      result.push(y.k);" +
        "    }" +
        "    return result;" +
        "  }});" +
        "var obj = new Foo();",
        "assertEquals(" +
        "    ___.getNewModuleHandler().getImports().obj.test()" +
        "        .sort().toSource()," +
        "    (['test', 'x_', 'y']).toSource());");
    }
  }

  public void testFor() throws Exception {
    assertConsistent("var i; for (i = 0; i < 10; i++) {} i;");
    assertConsistent("for (var i = 0; i < 10; i++) {} i;");
    assertConsistent("for (var i = 0, j = 0; i < 10; i++) { j += 10; } j;");
    assertConsistent("for (var i = 0, j = 0; i < 10; i++, j += 10) { } j;");
  }

  public void testMultiDeclaration() throws Exception {
    assertConsistent("var a = 3, b = 4, c = 5; a + b + c;");
    assertConsistent("var a, b; a = 3; b = 4; a + b;");
    assertConsistent(
        "  function f() {"
        + "  var a = 3, b = 4;"
        + "  return a + b;"
        + " }"
        + "f();");
  }

  public void testCommonReformedGenerics() throws Exception {
    assertConsistent(
        "var x = [33];" +
        "x.foo = [].push;" +
        "x.foo.call(x, 44);" +
        "x;");
    assertConsistent(
        "var x = [33];" +
        "x.foo = [].push;" +
        "x.foo.apply(x, [6,7,8]);" +
        "x;");
    assertConsistent(
        "var x = [33];" +
        "x.foo = [].push;" +
        "x.foo.bind(x)(6,7,8);" +
        "x;");
    assertConsistent(
        "var x = [33];" +
        "x.foo = [].push;" +
        "x.foo.bind(x,6)(7,8);" +
        "x;");
    assertConsistent(
        "[].push.length;");
    assertConsistent(
        "var x = {blue:'green'};" +
        "x.foo = [].push;" +
        "x.foo.call(x, 44);" +
        "delete x.foo;" +
        "x;");
    assertConsistent(
        "var x = {blue:'green'};" +
        "x.foo = [].push;" +
        "x.foo.call(x, 44);" +
        "cajita.getOwnPropertyNames(x).sort();");
  }

  public void testTypeofConsistent() throws Exception {
    assertConsistent("[ (typeof noSuchGlobal), (typeof 's')," +
                     "  (typeof 4)," +
                     "  (typeof null)," +
                     "  (typeof (void 0))," +
                     "  (typeof [])," +
                     "  (typeof {})," +
                     "  (typeof (function () {}))," +
                     "  (typeof { x: 4.0 }.x)," +
                     "  (typeof { 2: NaN }[1 + 1])" +
                     "];");
    rewriteAndExecute("assertEquals(typeof new RegExp('.*'), 'object');");
  }

  /**
   * Tests that callbacks from the Cajita runtime and from the tamed ES3 API
   * to either Cajita or Valija code works.
   * <p>
   * The uncajoled branch of the tests below establish that the callbacks
   * work uncajoled when they are tamed as simple frozen functions.
   */
  public void testCommonCallback() throws Exception {
    assertConsistent(
        "'abc'.replace('b', function() {return 'xy';});");
    assertConsistent(
        "var v = [1, 2, 3, 7, 4, 5];" +
        "var cmp = function(a, b) {" +
        "  return (a < b) ? +1 : (b < a) ? -1 : 0;" +
        "};" +
        "v.sort(cmp);");
    rewriteAndExecute("",
        "var a = [];\n" +
        "cajita.forOwnKeys({x:3}, function(k, v) {a.push(k, v);});" +
        "assertEquals(a.toString(), 'x,3');",
        "var a = [];\n" +
        "cajita.forOwnKeys({x:3}, ___.frozenFunc(function(k, v) {a.push(k, v);}));" +
        "assertEquals(a.toString(), 'x,3');");
    rewriteAndExecute("",
        "var a = [];\n" +
        "cajita.forAllKeys({x:3}, function(k, v) {a.push(k, v);});" +
        "assertEquals(a.toString(), 'x,3');",
        "var a = [];\n" +
        "cajita.forAllKeys({x:3}, ___.frozenFunc(function(k, v) {a.push(k, v);}));" +
        "assertEquals(a.toString(), 'x,3');");
    assertConsistent("(function(){}).bind.call(function(a, b) {return a + b;}, {}, 3)(4);");
  }

  /**
   * Tests that neither Cajita nor Valija code can cause a privilege
   * escalation by calling a tamed exophoric function with null as the
   * this-value.
   * <p>
   * The uncajoled branch of the tests below establish that a null does cause
   * a privilege escalation for normal non-strict JavaScript.
   */
  public void testNoPrivilegeEscalation() throws Exception {
    rewriteAndExecute("",
        "assertTrue([].valueOf.call(null) === cajita.USELESS);",
        "assertTrue([].valueOf.call(null) === this);");
    rewriteAndExecute("",
        "assertTrue([].valueOf.apply(null, []) === cajita.USELESS);",
        "assertTrue([].valueOf.apply(null, []) === this);");
    rewriteAndExecute("",
        "assertTrue([].valueOf.bind(null)() === cajita.USELESS);",
        "assertTrue([].valueOf.bind(null)() === this);");
  }

  /**
   * Tests that the special handling of null on tamed exophora works.
   *
   * The reification of tamed exophoric functions contains
   * special cases for when the first argument to call, bind, or apply
   * is null or undefined, in order to protect against privilege escalation.
   * {@code #testNoPrivilegeEscalation()} tests that we do prevent the
   * privilege escalation. Here, we test that this special case preserves
   * correct functionality.
   */
  public void testTamedXo4aOkOnNull() throws Exception {
    rewriteAndExecute("this.foo = 8;",

        "var x = cajita.beget(cajita.USELESS);" +
        "assertFalse(({foo: 7}).hasOwnProperty.call(null, 'foo'));" +
        "assertTrue(cajita.USELESS.isPrototypeOf(x));" +
        "assertTrue(({foo: 7}).isPrototypeOf.call(null, x));",

        "assertTrue(({}).hasOwnProperty.call(null, 'foo'));" +
        "assertFalse(({bar: 7}).hasOwnProperty.call(null, 'bar'));");
    rewriteAndExecute("this.foo = 8;",

        "var x = cajita.beget(cajita.USELESS);" +
        "assertFalse(({foo: 7}).hasOwnProperty.apply(null, ['foo']));" +
        "assertTrue(cajita.USELESS.isPrototypeOf(x));" +
        "assertTrue(({foo: 7}).isPrototypeOf.apply(null, [x]));",

        "assertTrue(({}).hasOwnProperty.apply(null, ['foo']));" +
        "assertFalse(({bar: 7}).hasOwnProperty.apply(null, ['bar']));");
    rewriteAndExecute("this.foo = 8;",

        "var x = cajita.beget(cajita.USELESS);" +
        "assertFalse(({foo: 7}).hasOwnProperty.bind(null)('foo'));" +
        "assertTrue(cajita.USELESS.isPrototypeOf(x));" +
        "assertTrue(({foo: 7}).isPrototypeOf.bind(null)(x));",

        "assertTrue(({}).hasOwnProperty.bind(null)('foo'));" +
        "assertFalse(({bar: 7}).hasOwnProperty.bind(null)('bar'));");
  }
}
