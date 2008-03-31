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

package com.google.caja.plugin.stages;

import com.google.caja.lang.css.CssSchema;
import com.google.caja.lang.html.HtmlSchema;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.ExpressionStmt;
import com.google.caja.parser.js.FunctionConstructor;
import com.google.caja.parser.js.Operation;
import com.google.caja.parser.js.Operator;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.plugin.PluginMeta;
import com.google.caja.reporting.MessageContext;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.RenderContext;
import com.google.caja.reporting.SimpleMessageQueue;
import com.google.caja.util.Pipeline;
import com.google.caja.util.TestUtil;

import junit.framework.TestCase;

/**
 * @author mikesamuel@gmail.com
 */
public final class OpenTemplateStageTest extends TestCase {
  public void testSimpleRewrite1() throws Exception {
    assertRewritten(
        "new StringInterpolation(['foo ', bar, ' baz'])",
        "eval(Template('foo $bar baz'))",
        true);
  }

  public void testSimpleRewrite2() throws Exception {
    assertRewritten(
        "new StringInterpolation(['foo', bar, 'baz'])",
        "eval(Template('foo${bar}baz'))",
        true);
  }

  public void testExpressionSubstitution() throws Exception {
    assertRewritten(
        "new StringInterpolation(['foo', bar() * 3, 'baz'])",
        "eval(Template('foo${bar() * 3}baz'))",
        true);
  }

  public void testMaskedTemplate() throws Exception {
    assertRewritten(
        "{\n"
        + "  eval(Template('foo${bar}baz'));\n"  // not rewritten
        + "  var Template;\n"
        + "}",

        "{\n"
        + "  eval(Template('foo${bar}baz'));\n"
        + "  var Template;\n"
        + "}",
        true);
  }

  public void testMaskedEval() throws Exception {
    assertRewritten(
        "{\n"
        + "  function eval() {\n"
        + "  }\n"
        + "  eval(Template('foo${bar}baz'));\n"  // not rewritten
        + "}",

        "{\n"
        + "  function eval() {\n"
        + "  }\n"
        + "  eval(Template('foo${bar}baz'));\n"
        + "}",
        true);
  }

  public void testCssRewriting() throws Exception {
    assertRewritten(
        "___OUTERS___.blessCss___('color', ___OUTERS___.cssColor___(c),"
        + " 'margin-left;marginLeft', ___OUTERS___.cssNumber___(x) + 'px')",

        "eval(Template('color: ${c};'\n"
        + "            + ' margin-left: ${x}px',\n"
        + "            'text/css;version=2.1'))",
        true);
  }

  public void testInvalidCss() throws Exception {
    assertRewritten(
        // Unsafe function expression stripped out
        "___OUTERS___.blessCss___()",

        "eval(Template('color: expression(x)', 'text/css'))",
        // Fails to cajole.
        false);
  }

  private void assertRewritten(
      String golden, String input, final boolean passes)
      throws Exception {
    MessageContext mc = new MessageContext();
    MessageQueue mq = new SimpleMessageQueue();

    CssSchema cssSchema = CssSchema.getDefaultCss21Schema(mq);
    HtmlSchema htmlSchema = HtmlSchema.getDefault(mq);

    Pipeline<Jobs> pipeline = new Pipeline<Jobs>() {
      @Override
      public boolean applyStage(Stage<? super Jobs> stage, Jobs jobs) {
        boolean result = super.applyStage(stage, jobs);
        return passes ? result : true;  // continue on failure
      }
    };
    pipeline.getStages().add(new OpenTemplateStage());
    pipeline.getStages().add(new ValidateCssStage(cssSchema, htmlSchema));
    pipeline.getStages().add(new CompileCssTemplatesStage(cssSchema));
    pipeline.getStages().add(new ConsolidateCodeStage());

    ParseTreeNode node = TestUtil.parse(input);
    PluginMeta meta = new PluginMeta("pre-");
    Jobs jobs = new Jobs(mc, mq, meta);
    jobs.getJobs().add(new Job(new AncestorChain<ParseTreeNode>(node)));

    assertTrue(pipeline.apply(jobs));
    assertEquals(
        "" + jobs.getMessageQueue().getMessages(),
        passes, jobs.hasNoErrors());
    assertEquals("" + jobs.getJobs(), 1, jobs.getJobs().size());

    StringBuilder out = new StringBuilder();
    stripBoilerPlate(jobs.getJobs().get(0).getRoot().node)
        .render(new RenderContext(mc, out));

    assertEquals(golden, out.toString());
  }
  
  private static ParseTreeNode stripBoilerPlate(ParseTreeNode node) {
    if (!(node instanceof Block && node.children().size() == 1)) {
      return node;
    }
    node = node.children().get(0);
    if (!(node instanceof ExpressionStmt)) { return node; }
    node = node.children().get(0);
    // Strip the loadModule call.
    if (!(node instanceof Operation
          && Operator.FUNCTION_CALL == ((Operation) node).getOperator())) {
      return node;
    }
    node = node.children().get(1);
    if (!(node instanceof FunctionConstructor)) { return node; }
    node = ((FunctionConstructor) node).getBody();

    if (!(node instanceof Block && node.children().size() == 1)) {
      return node;
    }
    node = node.children().get(0);
    return node;
  }
}