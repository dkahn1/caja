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

package com.google.caja.plugin;

import com.google.caja.util.RhinoTestBed;
import com.google.caja.util.TestUtil;
import java.io.StringReader;
import junit.framework.TestCase;

/**
 * JUnit wrapper for html-sanitizer JSUnit unittests.
 *
 * @author mikesamuel@gmail.com
 */
public final class HtmlSanitizerTest extends TestCase {
  public void testHtmlSanitizer() throws Exception {
    RhinoTestBed.runJs(
        null,
        new RhinoTestBed.Input(getClass(), "html4-defs.js"),
        new RhinoTestBed.Input(getClass(), "html-sanitizer.js"),
        new RhinoTestBed.Input(getClass(), "asserts.js"),
        new RhinoTestBed.Input(getClass(), "html-sanitizer-test.js"),
        new RhinoTestBed.Input(getClass(), "console-stubs.js"),
        new RhinoTestBed.Input(getClass(), "jsunit.js"),
        new RhinoTestBed.Input(new StringReader("jsunitRun()"), getName())
        );
  }
}
