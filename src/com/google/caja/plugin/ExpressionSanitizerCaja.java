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

package com.google.caja.plugin;

import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.MutableParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.quasiliteral.CajitaRewriter;
import com.google.caja.parser.quasiliteral.DefaultValijaRewriter;
import com.google.caja.parser.quasiliteral.IllegalReferenceCheckRewriter;
import com.google.caja.parser.quasiliteral.NonAsciiCheckVisitor;
import com.google.caja.parser.quasiliteral.Rewriter;
import com.google.caja.reporting.MessageQueue;
import com.google.caja.reporting.MessageLevel;
import com.google.caja.reporting.BuildInfo;

/**
 * @author ihab.awad@gmail.com (Ihab Awad)
 */
public class ExpressionSanitizerCaja {
  private final BuildInfo buildInfo;
  private final MessageQueue mq;
  private final PluginMeta meta;

  public ExpressionSanitizerCaja(BuildInfo buildInfo,
                                 MessageQueue mq,
                                 PluginMeta meta) {
    this.buildInfo = buildInfo;
    this.mq = mq;
    this.meta = meta;
  }

  public ParseTreeNode sanitize(AncestorChain<?> toSanitize) {
    MutableParseTreeNode input = (MutableParseTreeNode) toSanitize.node;
    ParseTreeNode result;
    if (this.meta.isValijaMode()) {
      result = newValijaRewriter().expand(input, this.mq);
      if (!this.mq.hasMessageAtLevel(MessageLevel.ERROR)) {
        result = newCajitaRewriter().expand(result, this.mq);
      }
    } else {
      result = newCajitaRewriter().expand(input, this.mq);
    }
    if (!this.mq.hasMessageAtLevel(MessageLevel.ERROR)) {
      result = new IllegalReferenceCheckRewriter(false)
          .expand(result, this.mq);
      if (!this.mq.hasMessageAtLevel(MessageLevel.ERROR)) {
        result.acceptPreOrder(new NonAsciiCheckVisitor(mq), null);
      }
    }
    return result;
  }

  /** Visible for testing. */
  protected Rewriter newCajitaRewriter() {
    return new CajitaRewriter(buildInfo, false);
  }

  protected Rewriter newValijaRewriter() {
    return new DefaultValijaRewriter(false);
  }
}
