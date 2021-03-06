// Copyright (C) 2006 Google Inc.
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

package com.google.caja.plugin;

import com.google.caja.lang.html.HTML;
import com.google.caja.lang.html.HtmlSchema;
import com.google.caja.lexer.HtmlTokenType;
import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.html.DomTree;
import com.google.caja.reporting.MessagePart;
import com.google.caja.reporting.MessageQueue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates an xhtml dom taking into account things in the gxp namespace.
 *
 * @author mikesamuel@gmail.com
 */
public final class GxpValidator {
  private final HtmlSchema schema;
  private final MessageQueue mq;

  public GxpValidator(HtmlSchema schema, MessageQueue mq) {
    this.mq = mq;
    this.schema = schema;
  }

  public boolean validate(AncestorChain<? extends DomTree> tChain) {
    DomTree t = tChain.node;
    boolean valid = true;
    switch (t.getType()) {
    case TAGBEGIN:
      {
        String tagName = t.getValue();
        if (isGxp(tagName)) {
          if ("gxp:attr".equals(tagName)) {
            String parentTagName = "*";
            ParseTreeNode parent = tChain.getParentNode();
            if (parent instanceof DomTree.Tag) {
              parentTagName = ((DomTree.Tag) parent).getValue();
            }

            // It is invalid unless it has one child which is the name attribute
            Map<String, DomTree.Value> attribs =
              new HashMap<String, DomTree.Value>();
            attribsAsMap((DomTree.Tag) t, attribs, ALLOWED_ATTR_PARAMS);
            DomTree.Value nameT = attribs.get("name");
            if (null == nameT) {
              mq.addMessage(
                  PluginMessageType.MISSING_ATTRIBUTE, t.getFilePosition(),
                  MessagePart.Factory.valueOf("name"),
                  MessagePart.Factory.valueOf(parentTagName));
              valid = false;
              break;
            }
            String name = nameT.getValue().toLowerCase();
            HTML.Attribute a = schema.lookupAttribute(parentTagName, name);
            if (null == a) {
              mq.addMessage(
                  PluginMessageType.UNKNOWN_ATTRIBUTE,
                  nameT.getFilePosition(), nameT,
                  MessagePart.Factory.valueOf(parentTagName));
              valid = false;
            }
          }
          break;
        }
        tagName = tagName.toLowerCase();
        HTML.Element e = schema.lookupElement(tagName);
        if (null == e) {
          mq.addMessage(PluginMessageType.UNKNOWN_TAG, t.getFilePosition(), t);
          valid = false;
        } else if (!schema.isElementAllowed(tagName)) {
          mq.addMessage(PluginMessageType.UNSAFE_TAG, t.getFilePosition(), t);
          valid = false;
        }
      }
      break;
    case ATTRNAME:
      String attrName = t.getValue();
      if (attrName.startsWith("expr:")) {
        attrName = attrName.substring("expr:".length());
      }
      String tagName = "*";
      ParseTreeNode parent = tChain.getParentNode();
      if (parent instanceof DomTree.Tag) {
        tagName = ((DomTree.Tag) parent).getValue();
      }
      String lTagName = tagName.toLowerCase(),
          lAttrName = attrName.toLowerCase();
      HTML.Attribute a = schema.lookupAttribute(lTagName, lAttrName);
      if (null == a || !schema.isAttributeAllowed(lTagName, lAttrName)) {
        if (!isGxp(tagName)) {
          mq.addMessage(
              PluginMessageType.UNKNOWN_ATTRIBUTE, t.getFilePosition(),
              MessagePart.Factory.valueOf(attrName),
              MessagePart.Factory.valueOf(tagName));
          valid = false;
        }
      }
      break;
    case TEXT: case CDATA:
    case ATTRVALUE:
    case COMMENT:
      break;
    default:
      throw new AssertionError(t.getType().toString());
    }
    for (DomTree child : t.children()) {
      valid &= validate(new AncestorChain<DomTree>(tChain, child));
    }
    return valid;
  }

  static boolean isGxp(String tagName) {
    return tagName.startsWith("gxp:") || tagName.startsWith("call:");
  }

  private static final Set<String> ALLOWED_ATTR_PARAMS =
    Collections.singleton("name");

  int attribsAsMap(
      DomTree.Tag t, Map<String, DomTree.Value> attribs, Set<String> ok) {
    List<? extends DomTree> children = t.children();
    int pos = 0;
    for (int n = children.size(); pos < n; ++pos) {
      DomTree child = children.get(pos);
      if (HtmlTokenType.ATTRNAME != child.getType()) { break; }
      DomTree.Attrib attrib = (DomTree.Attrib) child;
      String name = attrib.getAttribName();
      if (!attribs.containsKey(name)) {
        attribs.put(name, attrib.getAttribValueNode());
      } else {
        mq.addMessage(PluginMessageType.DUPLICATE_ATTRIBUTE,
                      t.getFilePosition(), attrib,
                      attribs.get(name).getFilePosition());
      }
      if (ok != null && !ok.contains(name)) {
        mq.addMessage(PluginMessageType.UNKNOWN_ATTRIBUTE,
                      attrib.getFilePosition(),
                      MessagePart.Factory.valueOf(attrib.getAttribName()),
                      MessagePart.Factory.valueOf(t.getTagName()));
      }
    }
    return pos;
  }
}
