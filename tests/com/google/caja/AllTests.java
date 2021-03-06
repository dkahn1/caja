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

package com.google.caja;

import com.google.caja.config.ConfigUtilTest;
import com.google.caja.demos.calendar.EventStoreTest;
import com.google.caja.demos.calendar.HcalTest;
import com.google.caja.demos.calendar.LayoutTest;
import com.google.caja.demos.calendar.RRuleTest;
import com.google.caja.lang.css.CssPropertyPatternsTest;
import com.google.caja.lang.css.CssSchemaTest;
import com.google.caja.lang.html.HtmlSchemaTest;
import com.google.caja.lexer.CharProducerTest;
import com.google.caja.lexer.CssLexerTest;
import com.google.caja.lexer.HtmlLexerTest;
import com.google.caja.lexer.JsLexerTest;
import com.google.caja.lexer.LookaheadCharProducerTest;
import com.google.caja.lexer.PunctuationTrieTest;
import com.google.caja.lexer.escaping.EscapingTest;
import com.google.caja.opensocial.DefaultGadgetRewriterTest;
import com.google.caja.opensocial.GadgetParserTest;
import com.google.caja.opensocial.applet.ExpressionLanguageStageTest;
import com.google.caja.opensocial.applet.TestBedTest;
import com.google.caja.opensocial.service.CajolingServiceTest;
import com.google.caja.opensocial.service.ContentTypeCheckTest;
import com.google.caja.parser.ParseTreeNodeTest;
import com.google.caja.parser.ParserBaseTest;
import com.google.caja.parser.css.CssParserTest;
import com.google.caja.parser.css.CssPropertySignatureTest;
import com.google.caja.parser.css.CssTreeTest;
import com.google.caja.parser.html.DomParserTest;
import com.google.caja.parser.js.FuzzedParserTest;
import com.google.caja.parser.js.NumberLiteralTest;
import com.google.caja.parser.js.ParserTest;
import com.google.caja.parser.js.StringLiteralTest;
import com.google.caja.parser.quasiliteral.CajitaRewriterTest;
import com.google.caja.parser.quasiliteral.DefaultValijaRewriterTest;
import com.google.caja.parser.quasiliteral.IllegalReferenceCheckRewriterTest;
import com.google.caja.parser.quasiliteral.InnocentCodeRewriterTest;
import com.google.caja.parser.quasiliteral.MatchTest;
import com.google.caja.parser.quasiliteral.QuasiBuilderTest;
import com.google.caja.parser.quasiliteral.RewriterTest;
import com.google.caja.parser.quasiliteral.ScopeTest;
import com.google.caja.plugin.CssCompilerTest;
import com.google.caja.plugin.CssRewriterTest;
import com.google.caja.plugin.CssValidatorTest;
import com.google.caja.plugin.DomProcessingEventsTest;
import com.google.caja.plugin.ExpressionSanitizerTest;
import com.google.caja.plugin.HtmlCompiledPluginTest;
import com.google.caja.plugin.HtmlCompilerTest;
import com.google.caja.plugin.HtmlSanitizerTest;
import com.google.caja.plugin.JsHtmlSanitizerTest;
import com.google.caja.plugin.stages.DebuggingSymbolsStageTest;
import com.google.caja.plugin.stages.InlineCssImportsStageTest;
import com.google.caja.plugin.stages.OpenTemplateStageTest;
import com.google.caja.plugin.stages.RewriteHtmlStageTest;
import com.google.caja.render.JsLinePreservingPrinterTest;
import com.google.caja.render.JsMinimalPrinterTest;
import com.google.caja.render.JsPrettyPrinterTest;
import com.google.caja.render.SideBySideRendererTest;
import com.google.caja.render.SourceSnippetRendererTest;
import com.google.caja.render.TokenClassificationTest;
import com.google.caja.reporting.AbstractMessageQueueTest;
import com.google.caja.reporting.BuildInfoTest;
import com.google.caja.reporting.HtmlSnippetProducerTest;
import com.google.caja.reporting.SnippetProducerTest;
import com.google.caja.util.AbbreviatorTest;
import com.google.caja.util.CapturingReaderTest;
import com.google.caja.util.JoinTest;
import com.google.caja.util.SparseBitSetTest;
import com.google.caja.util.StringsTest;

import java.util.regex.Pattern;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * @author mikesamuel@gmail.com
 */
public class AllTests {

  @SuppressWarnings("unchecked")
  public static Test suite() {
    TestSuite suite = new TestSuite("Caja Tests");
    Class<? extends TestCase>[] testClasses = new Class[] {
          AbstractMessageQueueTest.class,
          AbbreviatorTest.class,
          BuildInfoTest.class,
          CajitaRewriterTest.class,
          CajitaTest.class,
          CajolingServiceTest.class,
          CapturingReaderTest.class,
          CharProducerTest.class,
          ConfigUtilTest.class,
          ContentTypeCheckTest.class,
          CssCompilerTest.class,
          CssLexerTest.class,
          CssParserTest.class,
          CssPropertyPatternsTest.class,
          CssPropertySignatureTest.class,
          CssRewriterTest.class,
          CssSchemaTest.class,
          CssTreeTest.class,
          CssValidatorTest.class,
          DebuggingSymbolsStageTest.class,
          DefaultGadgetRewriterTest.class,
          DefaultValijaRewriterTest.class,
          DomParserTest.class,
          DomProcessingEventsTest.class,
          EscapingTest.class,
          EventStoreTest.class,
          ExpressionLanguageStageTest.class,
          ExpressionSanitizerTest.class,
          FuzzedParserTest.class,
          GadgetParserTest.class,
          HcalTest.class,
          HtmlCompiledPluginTest.class,
          HtmlCompilerTest.class,
          HtmlLexerTest.class,
          HtmlSanitizerTest.class,
          HtmlSchemaTest.class,
          HtmlSnippetProducerTest.class,
          IllegalReferenceCheckRewriterTest.class,
          InlineCssImportsStageTest.class,
          InnocentCodeRewriterTest.class,
          JoinTest.class,
          JsHtmlSanitizerTest.class,
          JsLexerTest.class,
          JsLinePreservingPrinterTest.class,
          JsMinimalPrinterTest.class,
          JsPrettyPrinterTest.class,
          LayoutTest.class,
          LookaheadCharProducerTest.class,
          MatchTest.class,
          NumberLiteralTest.class,
          OpenTemplateStageTest.class,
          ParseTreeNodeTest.class,
          ParserBaseTest.class,
          ParserTest.class,
          PunctuationTrieTest.class,
          QuasiBuilderTest.class,
          RRuleTest.class,
          RewriteHtmlStageTest.class,
          RewriterTest.class,
          ScopeTest.class,
          SideBySideRendererTest.class,
          SnippetProducerTest.class,
          SourceSnippetRendererTest.class,
          SparseBitSetTest.class,
          StringLiteralTest.class,
          StringsTest.class,
          TestBedTest.class,
          TokenClassificationTest.class,
    };
    Pattern testFilter = Pattern.compile(System.getProperty("test.filter", ""));
    for (Class<? extends TestCase> testClass : testClasses) {
      if (testFilter.matcher(testClass.getName()).find()) {
        suite.addTestSuite(testClass);
      }
    }
    return suite;
  }
}
