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

package com.google.caja.parser.quasiliteral;

import com.google.caja.lexer.InputSource;
import junit.framework.TestCase;

import java.net.URI;

/**
 *
 * @author ihab.awad@gmail.com
 */
public class QuasiBuilderTest extends TestCase {
  public void testParseDoesNotFail() throws Exception {
    QuasiNode n = QuasiBuilder.parseQuasiNode(
        new InputSource(URI.create("built-in:///js-quasi-literals")),
        "function @a() { @b.@c = @d; @e = @f; }");
    assertTrue(n instanceof SimpleQuasiNode);
  }
}