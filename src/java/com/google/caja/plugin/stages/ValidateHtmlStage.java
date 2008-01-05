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

import com.google.caja.parser.html.DomTree;
import com.google.caja.plugin.HtmlValidator;
import com.google.caja.plugin.Job;
import com.google.caja.plugin.Jobs;
import com.google.caja.util.Pipeline;

/**
 * Whitelist html tags and attributes, and supply values for key
 * attributes that are missing them.
 *
 * @see HtmlValidator 
 *
 * @author mikesamuel@gmail.com
 */
public class ValidateHtmlStage implements Pipeline.Stage<Jobs> {
  public boolean apply(Jobs jobs) {
    HtmlValidator v = new HtmlValidator(jobs.getMessageQueue());

    boolean valid = true;
    for (Job job : jobs.getJobsByType(Job.JobType.HTML)) {
      if (!v.validate((DomTree) job.getRoot().node, null)) {
        valid = false;
        // Keep going so that we can display error messages for all inputs.
      }
    }
    return valid;
  }
}
