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

import com.google.caja.parser.AncestorChain;
import com.google.caja.parser.MutableParseTreeNode;
import com.google.caja.parser.ParseTreeNode;
import com.google.caja.parser.ParseTreeNodeContainer;
import com.google.caja.parser.js.Block;
import com.google.caja.parser.js.Statement;
import com.google.caja.parser.quasiliteral.QuasiBuilder;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.util.Pipeline;

import java.util.Collections;
import java.util.ListIterator;

/**
 * Put all the top level javascript code into an initializer block
 * that will set up the plugin.
 *
 * @author mikesamuel@gmail.com
 */
public final class ConsolidateCodeStage implements Pipeline.Stage<Jobs> {
  public boolean apply(Jobs jobs) {
    // create an initializer function
    ParseTreeNodeContainer initFunctionBody = new ParseTreeNodeContainer(
        Collections.<Statement>emptyList());

    MutableParseTreeNode.Mutation mut = initFunctionBody.createMutation();

    ListIterator<Job> it = jobs.getJobs().listIterator();
    while (it.hasNext()) {
      Job job = it.next();
      if (Job.JobType.JAVASCRIPT != job.getType()) { continue; }

      if (job.getTarget() != null) {
        AncestorChain<?> toReplace = job.getTarget();
        ((MutableParseTreeNode) toReplace.parent.node).replaceChild(
            job.getRoot().cast(ParseTreeNode.class).node, toReplace.node);
      } else {
        Statement stmt = (Statement) job.getRoot().node;
        if (stmt instanceof Block) {
          Block body = (Block) stmt;
          MutableParseTreeNode.Mutation old = body.createMutation();
          for (Statement s : body.children()) {
            old.removeChild(s);
            mut.appendChild(s);
          }
          old.execute();
        } else {
          mut.appendChild(stmt);
        }
      }

      it.remove();
    }
    mut.execute();

    // Now initFunctionBody contains all the top level statements.
    Block jsTree = (Block) QuasiBuilder.substV(
        ""
        + "{"
        + "  ___./*@synthetic*/loadModule("
        + "      /*@synthetic*/function (___, IMPORTS___) { @body*; });"
        + "}",
        "body", initFunctionBody);

    jobs.getJobs().add(new Job(new AncestorChain<Block>(jsTree)));

    return jobs.hasNoFatalErrors();
  }
}
