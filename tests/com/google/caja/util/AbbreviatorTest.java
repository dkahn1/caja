// Copyright (C) 2009 Google Inc.
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

package com.google.caja.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import junit.framework.TestCase;

public class AbbreviatorTest extends TestCase {
  public void testOne() {
    Abbreviator a = new Abbreviator(
        Collections.singleton("test:///foo/bar"), "/");
    assertEquals("bar", a.unambiguousAbbreviationFor("test:///foo/bar"));
  }

  public void testAbbreviator() {
    Abbreviator a = new Abbreviator(
        new HashSet<String>(Arrays.asList(
            "/a/b/c", "/a/d/e", "/a/d/f", "/a/g/h/c",
            "/h/i", "/h/j/", "/", "/c", "/d", "x", "")),
       "/");
    assertEquals("b/c", a.unambiguousAbbreviationFor("/a/b/c"));
    assertEquals("h/c", a.unambiguousAbbreviationFor("/a/g/h/c"));
    assertEquals("e", a.unambiguousAbbreviationFor("/a/d/e"));
    assertEquals("f", a.unambiguousAbbreviationFor("/a/d/f"));
    assertEquals("i", a.unambiguousAbbreviationFor("/h/i"));
    assertEquals("j/", a.unambiguousAbbreviationFor("/h/j/"));
    assertEquals("/", a.unambiguousAbbreviationFor("/"));
    assertEquals("/c", a.unambiguousAbbreviationFor("/c"));
    assertEquals("d", a.unambiguousAbbreviationFor("/d"));
    assertEquals("x", a.unambiguousAbbreviationFor("x"));
    assertEquals("", a.unambiguousAbbreviationFor(""));
    assertEquals("/notpresent", a.unambiguousAbbreviationFor("/notpresent"));
  }

  public void testSetContainsSuffixOfOtherMember() {
    Abbreviator a = new Abbreviator(
        new HashSet<String>(Arrays.asList(
            "foo:bar/z", "foo:foo:baz/z", "foo:baz/z", "foo:bar:far/z")),
       ":");
    assertEquals("bar/z", a.unambiguousAbbreviationFor("foo:bar/z"));
    assertEquals("foo:baz/z", a.unambiguousAbbreviationFor("foo:baz/z"));
    assertEquals(
        "foo:foo:baz/z", a.unambiguousAbbreviationFor("foo:foo:baz/z"));
    assertEquals("far/z", a.unambiguousAbbreviationFor("foo:bar:far/z"));
  }
}
