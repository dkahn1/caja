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

package com.google.caja.opensocial;

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.InputSource;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.TokenQueue;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.html.DomParser;
import com.google.caja.parser.html.DomTree;
import com.google.caja.plugin.HtmlPluginCompiler;
import com.google.caja.plugin.PluginMeta;
import com.google.caja.reporting.MessageQueue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;

/**
 * A default implementation of the Caja/OpenSocial gadget rewriter.
 *
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class DefaultGadgetRewriter implements GadgetRewriter {
  private static final String JAVASCRIPT_PREFIX = "___OUTERS___";
  private static final String DOM_PREFIX = "DOM-PREFIX";
  private static final String ROOT_DIV_ID = "ROOT_DIV_ID";

  private MessageQueue mq;
  private PluginMeta.TranslationScheme translationScheme;
  public DefaultGadgetRewriter(MessageQueue mq) {
    this.mq = mq;
    this.translationScheme = PluginMeta.TranslationScheme.CAJA;
  }

  public PluginMeta.TranslationScheme getTranslationScheme() {
    return translationScheme;
  }

  public void setTranslationScheme(PluginMeta.TranslationScheme translationScheme) {
    this.translationScheme = translationScheme;
  }

  public MessageQueue getMessageQueue() {
    return mq;
  }

  public void rewrite(ExternalReference gadgetRef, UriCallback uriCallback,
                      Appendable output)
      throws UriCallbackException, GadgetRewriteException, IOException {
    assert gadgetRef.getUri().isAbsolute() : gadgetRef.toString();
    rewrite(
        gadgetRef.getUri(),
        uriCallback.retrieve(gadgetRef, "text/xml"),
        uriCallback,
        output);
  }

  public void rewrite(URI baseUri, Readable gadgetSpec, UriCallback uriCallback, Appendable output)
      throws UriCallbackException, GadgetRewriteException, IOException {
    GadgetParser parser = new GadgetParser();
    GadgetSpec spec = parser.parse(gadgetSpec);
    spec.setContent(rewriteContent(baseUri, spec.getContent(), uriCallback));
    parser.render(spec, output);
  }

  private String rewriteContent(
      URI baseUri, String content, UriCallback callback)
      throws GadgetRewriteException {

    DomTree.Fragment htmlContent;
    try {
      htmlContent = parseHtml(baseUri, content);
    } catch (ParseException ex) {
      ex.toMessageQueue(mq);
      throw new GadgetRewriteException(ex);
    }

    HtmlPluginCompiler compiler = compileGadget(htmlContent, baseUri, callback);

    String style = compiler.getOutputCss().trim();
    String script = compiler.getOutputJs().trim();

    return rewriteContent(style, script);
  }

  private DomTree.Fragment parseHtml(URI uri, String htmlContent)
      throws GadgetRewriteException, ParseException {
    InputSource is = new InputSource(uri); // TODO(mikesamuel): is this correct?
    TokenQueue<HtmlTokenType> tq = DomParser.makeTokenQueue(
        is, new StringReader(htmlContent), false);
    if (tq.isEmpty()) { return null; }
    DomTree.Fragment contentTree = DomParser.parseFragment(tq);

    if (contentTree == null) {
      mq.addMessage(OpenSocialMessageType.NO_CONTENT, is);
      throw new GadgetRewriteException("No content");
    }
    return contentTree;
  }

  private HtmlPluginCompiler compileGadget(
      DomTree.Fragment content, final URI baseUri, final UriCallback callback)
      throws GadgetRewriteException {
    HtmlPluginCompiler compiler = new HtmlPluginCompiler(
        JAVASCRIPT_PREFIX, DOM_PREFIX, ROOT_DIV_ID, translationScheme) {
          @Override
          protected CharProducer loadExternalResource(
              ExternalReference ref, String mimeType) {
            ExternalReference absRef = new ExternalReference(
                baseUri.resolve(ref.getUri()), ref.getReferencePosition());
            Reader content;
            try {
              content = callback.retrieve(absRef, mimeType);
            } catch (UriCallbackException ex) {
              ex.toMessageQueue(getMessageQueue());
              return null;
            }
            return CharProducer.Factory.create(
                content, new InputSource(absRef.getUri()));
          }

          @Override
          protected String rewriteUri(ExternalReference ref, String mimeType) {
            ExternalReference absRef = new ExternalReference(
                baseUri.resolve(ref.getUri()), ref.getReferencePosition());
            try {
              URI uri = callback.rewrite(absRef, mimeType);
              if (uri == null) { return null; }
              return uri.toString();
            } catch (UriCallbackException ex) {
              return null;
            }
          }
        };

    // Compile
    compiler.addInput(new AncestorChain<DomTree.Fragment>(content));
    compiler.setMessageQueue(mq);

    if (!compiler.run()) {
      throw new GadgetRewriteException();
    }

    return compiler;
  }

  private String rewriteContent(String style, String script) {
    StringBuilder results = new StringBuilder();
    if (!"".equals(style)) {
      results.append("<style type=\"text/css\">\n")
          .append(style)
          .append("</style>\n");
    }

    results.append("<div id=\"" + ROOT_DIV_ID + "\"></div>\n");

    if (!"".equals(script)) {
      results.append("<script type=\"text/javascript\">\n")
          .append(script)
          .append("</script>\n");
    }

    return results.toString();
  }
}
