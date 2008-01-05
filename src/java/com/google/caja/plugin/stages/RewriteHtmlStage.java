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

package com.google.caja.plugin.stages;

import com.google.caja.lexer.CharProducer;
import com.google.caja.lexer.ExternalReference;
import com.google.caja.lexer.FilePosition;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.lexer.ParseException;
import com.google.caja.lexer.Token;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.MutableParseTreeNode;
import com.google.caja.parser.Visitor;
import com.google.caja.parser.css.Css2;
import com.google.caja.parser.css.CssTree;
import com.google.caja.parser.html.DomTree;
import com.google.caja.parser.js.Block;
import com.google.caja.plugin.HtmlPluginCompiler;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.plugin.PluginEnvironment;
import com.google.caja.plugin.PluginMessageType;
import com.google.caja.reporting.Message;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.MessagePart;
import com.google.caja.util.Pipeline;
import com.google.caja.util.SyntheticAttributeKey;

import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extract unsafe bits from HTML for processing by later stages.
 * Specifically, extracts {@code onclick} and other handlers, the contents of
 * {@code <script>} elements, and the contents of {@code <style>} elements,
 * and the content referred to by {@code <link rel=stylesheet>} elements. 
 */
public class RewriteHtmlStage implements Pipeline.Stage<Jobs> {

  /**
   * A synthetic attribute that stores the name of a function extracted from a
   * script tag.
   */
  public static final SyntheticAttributeKey<Block> EXTRACTED_SCRIPT_BODY
      = new SyntheticAttributeKey<Block>(Block.class, "extractedScript");

  public boolean apply(Jobs jobs) {
    for (Job job : jobs.getJobsByType(Job.JobType.HTML)) {
      rewriteDomTree((DomTree) job.getRoot().node, jobs);
    }
    return jobs.hasNoFatalErrors();
  }
  
  DomTree rewriteDomTree(DomTree t, final Jobs jobs) {
    // Rewrite styles and scripts.
    // <script>foo()</script>  ->  <script>(cajoled foo)</script>
    // <style>foo { ... }</style>  ->  <style>foo { ... }</style>
    // <script src=foo.js></script>  ->  <script>(cajoled, inlined foo)</script>
    // <link rel=stylesheet href=foo.css>
    //     ->  <style>(cajoled, inlined styles)</style>
    Visitor domRewriter = new Visitor() {
        public boolean visit(AncestorChain<?> ancestors) {
          DomTree n = (DomTree) ancestors.node;
          if (n instanceof DomTree.Tag) {
            MutableParseTreeNode parentNode
                = (MutableParseTreeNode) ancestors.getParentNode();
            DomTree.Tag tag = (DomTree.Tag) n;
            String name = tag.getTagName().toLowerCase();
            if ("script".equals(name)) {
              rewriteScriptTag(tag, parentNode, jobs);
            } else if ("style".equals(name)) {
              rewriteStyleTag(tag, parentNode, jobs);
            } else if ("link".equals(name)) {
              rewriteLinkTag(tag, parentNode, jobs);
            }
          }
          return true;
        }
      };
    t.acceptPreOrder(domRewriter, null);
    return t;
  }

  private void rewriteScriptTag(
      DomTree.Tag scriptTag, MutableParseTreeNode parent, Jobs jobs) {
    PluginEnvironment env = jobs.getPluginMeta().getPluginEnvironment();
    
    DomTree.Attrib type = lookupAttribute(scriptTag, "type");
    DomTree.Attrib src = lookupAttribute(scriptTag, "src");
    if (type != null && !isJavaScriptContentType(type.getAttribValue())) {
      jobs.getMessageQueue().addMessage(
          PluginMessageType.UNRECOGNIZED_CONTENT_TYPE,
          type.getFilePosition(),
          MessagePart.Factory.valueOf(type.getAttribValue()),
          MessagePart.Factory.valueOf(scriptTag.getTagName()));
      parent.removeChild(scriptTag);
      return;
    }
    // The script contents.
    CharProducer jsStream;
    if (src == null) {  // Parse the script tag body.
      jsStream = textNodesToCharProducer(scriptTag.children(), true);
      if (jsStream == null) {
        parent.removeChild(scriptTag);
        return;
      }
    } else {  // Load the src attribute
      URI srcUri;
      try {
        srcUri = new URI(src.getAttribValue());
      } catch (URISyntaxException ex) {
        jobs.getMessageQueue().getMessages().add(
            new Message(PluginMessageType.MALFORMED_URL, MessageLevel.ERROR,
                        src.getFilePosition(), src));
        parent.removeChild(scriptTag);
        return;
      }

      // Fetch the script source.
      jsStream = env.loadExternalResource(
          new ExternalReference(
              srcUri, src.getAttribValueNode().getFilePosition()),
          "text/javascript");
      if (jsStream == null) {
        parent.removeChild(scriptTag);
        jobs.getMessageQueue().addMessage(
            PluginMessageType.FAILED_TO_LOAD_EXTERNAL_URL,
            src.getFilePosition(), MessagePart.Factory.valueOf("" + srcUri));
        return;
      }
    }

    // Parse the body and create a block that will be placed inline in
    // loadModule.
    Block parsedScriptBody;
    try {
      parsedScriptBody = HtmlPluginCompiler.parseJs(
          jsStream.getCurrentPosition().source(), jsStream,
          jobs.getMessageQueue());
    } catch (ParseException ex) {
      ex.toMessageQueue(jobs.getMessageQueue());
      parent.removeChild(scriptTag);
      return;
    }

    // Build a replacment element.
    // <script type="text/javascript"></script>
    DomTree.Tag emptyScript;
    {
      Token<HtmlTokenType> endToken = Token.instance(
          ">", HtmlTokenType.TAGEND,
          FilePosition.endOf(scriptTag.getFilePosition()));
      emptyScript = new DomTree.Tag(
          Collections.<DomTree>emptyList(), scriptTag.getToken(), endToken);
      emptyScript.getAttributes().set(EXTRACTED_SCRIPT_BODY, parsedScriptBody);
    }

    // Replace the external script tag with the inlined script.
    parent.replaceChild(emptyScript, scriptTag);
  }

  private void rewriteStyleTag(
      DomTree.Tag styleTag, MutableParseTreeNode parent, Jobs jobs) {
    parent.removeChild(styleTag);

    CharProducer cssStream
        = textNodesToCharProducer(styleTag.children(), false);
    if (cssStream != null) {
      extractStyles(styleTag, cssStream, null, jobs);
    }
  }

  private void rewriteLinkTag(
      DomTree.Tag styleTag, MutableParseTreeNode parent, Jobs jobs) {
    PluginEnvironment env = jobs.getPluginMeta().getPluginEnvironment();

    parent.removeChild(styleTag);

    DomTree.Attrib rel = lookupAttribute(styleTag, "rel");
    if (rel == null
        || !rel.getAttribValue().trim().equalsIgnoreCase("stylesheet")) {
      // If it's not a stylesheet then ignore it.
      // The HtmlValidator should complain but that's not our problem.
      return;
    }

    DomTree.Attrib href = lookupAttribute(styleTag, "href");
    DomTree.Attrib media = lookupAttribute(styleTag, "media");

    if (href == null) {
      jobs.getMessageQueue().addMessage(
          PluginMessageType.MISSING_ATTRIBUTE, styleTag.getFilePosition(),
          MessagePart.Factory.valueOf("href"),
          MessagePart.Factory.valueOf("style"));
      return;
    }

    URI hrefUri;
    try {
      hrefUri = new URI(href.getAttribValue());
    } catch (URISyntaxException ex) {
      jobs.getMessageQueue().getMessages().add(
          new Message(PluginMessageType.MALFORMED_URL, MessageLevel.ERROR,
                      href.getFilePosition(), href));
      return;
    }

    // Fetch the stylesheet source.
    CharProducer cssStream = env.loadExternalResource(
        new ExternalReference(
            hrefUri, href.getAttribValueNode().getFilePosition()),
        "text/css");
    if (cssStream == null) {
      parent.removeChild(styleTag);
      jobs.getMessageQueue().addMessage(
          PluginMessageType.FAILED_TO_LOAD_EXTERNAL_URL,
          href.getFilePosition(), MessagePart.Factory.valueOf("" + hrefUri));
      return;
    }

    extractStyles(styleTag, cssStream, media, jobs);
  }
  
  private void extractStyles(
      DomTree.Tag styleTag, CharProducer cssStream, DomTree.Attrib media,
      Jobs jobs) {
    DomTree.Attrib type = lookupAttribute(styleTag, "type");

    if (type != null && !isCssContentType(type.getAttribValue())) {
      jobs.getMessageQueue().addMessage(
          PluginMessageType.UNRECOGNIZED_CONTENT_TYPE,
          type.getFilePosition(),
          MessagePart.Factory.valueOf(type.getAttribValue()),
          MessagePart.Factory.valueOf(styleTag.getTagName()));
      return;
    }

    CssTree.StyleSheet stylesheet;
    try {
      stylesheet = HtmlPluginCompiler.parseCss(
          cssStream.getCurrentPosition().source(), cssStream);
    } catch (ParseException ex) {
      ex.toMessageQueue(jobs.getMessageQueue());
      return;
    }

    Set<String> mediaTypes = Collections.<String>emptySet();
    if (media != null) {
      String[] mediaTypeArr = media.getAttribValue().trim().split("\\s*,\\s*");
      if (mediaTypeArr.length != 1 || !"".equals(mediaTypeArr[0])) {
        mediaTypes = new LinkedHashSet<String>();
        for (String mediaType : mediaTypeArr) {
          if (!Css2.isMediaType(mediaType)) {
            jobs.getMessageQueue().addMessage(
                PluginMessageType.UNRECOGNIZED_MEDIA_TYPE,
                media.getFilePosition(),
                MessagePart.Factory.valueOf(mediaType));
            continue;
          }
          mediaTypes.add(mediaType);
        }
      }
    }
    if (!(mediaTypes.isEmpty() || mediaTypes.contains("all"))) {
      final List<CssTree.RuleSet> rules = new ArrayList<CssTree.RuleSet>();
      stylesheet.acceptPreOrder(
          new Visitor() {
            public boolean visit(AncestorChain<?> ancestors) {
              CssTree node = (CssTree) ancestors.node;
              if (node instanceof CssTree.StyleSheet) { return true; }
              if (node instanceof CssTree.RuleSet) {
                rules.add((CssTree.RuleSet) node);
                ((MutableParseTreeNode) ancestors.parent.node)
                    .removeChild(node);
              }
              // Don't touch RuleSets that are not direct children of a
              // stylesheet.
              return false;
            }
          }, null);
      if (!rules.isEmpty()) {
        List<CssTree> mediaChildren = new ArrayList<CssTree>();
        for (String mediaType : mediaTypes) {
          mediaChildren.add(
              new CssTree.Medium(type.getFilePosition(), mediaType));
        }
        mediaChildren.addAll(rules);
        CssTree.Media mediaBlock = new CssTree.Media(
            styleTag.getFilePosition(), mediaChildren);
        stylesheet.appendChild(mediaBlock);
      }
    }

    jobs.getJobs().add(
        new Job(new AncestorChain<CssTree.StyleSheet>(stylesheet)));
  }
  
  /**
   * A CharProducer that produces characters from the concatenation of all
   * the text nodes in the given node list.
   */
  private static CharProducer textNodesToCharProducer(
      List<? extends DomTree> nodes, boolean stripComments) {
    List<DomTree> textNodes = new ArrayList<DomTree>();
    for (DomTree node : nodes) {
      if (node instanceof DomTree.Text || node instanceof DomTree.CData) {
        textNodes.add(node);
      }
    }
    if (textNodes.isEmpty()) { return null; }
    List<CharProducer> content = new ArrayList<CharProducer>();
    for (int i = 0, n = textNodes.size(); i < n; ++i) {
      DomTree node = textNodes.get(i);
      String text = node.getValue();
      if (stripComments) {
        if (i == 0) {
          text = text.replaceFirst("^(\\s*)<!--", "$1     ");
        }
        if (i + 1 == n) {
          text = text.replaceFirst("-->(\\s*)$", "   $1");
        }
      }
      content.add(CharProducer.Factory.create(
          new StringReader(text),
          FilePosition.startOf(node.getFilePosition())));
    }
    if (content.size() == 1) {
      return content.get(0);
    } else {
      return CharProducer.Factory.chain(content.toArray(new CharProducer[0]));
    }
  }

  /** "text/html;charset=UTF-8" -> "text/html" */
  private static String getMimeType(String contentType) {
    int typeEnd = contentType.indexOf(';');
    if (typeEnd < 0) { typeEnd = contentType.length(); }
    return contentType.substring(0, typeEnd).toLowerCase();
  }

  private static boolean isJavaScriptContentType(String contentType) {
    String mimeType = getMimeType(contentType);
    return ("text/javascript".equals(mimeType)
            || "application/x-javascript".equals(mimeType)
            || "type/ecmascript".equals(mimeType));
  }

  private static boolean isCssContentType(String contentType) {
    return "text/css".equals(getMimeType(contentType));
  }

  private static DomTree.Attrib lookupAttribute(
      DomTree.Tag el, String attribName) {
    for (DomTree child : el.children()) {
      if (!(child instanceof DomTree.Attrib)) { break; }
      DomTree.Attrib attrib = (DomTree.Attrib) child;
      if (attribName.equalsIgnoreCase(attrib.getAttribName())) {
        return attrib;
      }
    }
    return null;
  }
}
